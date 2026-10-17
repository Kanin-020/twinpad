//! Emisor del Stream de audio en el Servidor PC — SPEC-005 §7 (lado origen).
//!
//! El códec es PCM `s16le` sin compresión (OQ §17 resuelta por el owner);
//! los fragmentos ≤ 16 KiB viajan con `seq` incremental (R2: sin retransmisión).
//! Si no hay fuente PCM real, no se anuncia ni envía audio (SPEC-005 §3/§5).

use std::time::Instant;

use twinpad_core::audio::{AudioMsg, AudioStopReason, MAX_CHUNK_BYTES};

use crate::video::FrameSink;

/// Errores del emisor de audio (igual criterio que `video::VideoSourceError`).
#[derive(Debug)]
pub enum AudioSourceError {
    /// La sesión dejó de recibir (conexión caída).
    Disconnected,
}

/// Tamaño de fragmento: múltiplo de 4 bytes (2 muestras s16 × 2 canales)
/// por debajo del límite de 16 KiB (SPEC-005 §4).
pub const CHUNK_SAMPLES: usize = 1_024; // muestras por canal por chunk

/// Fuente PCM real anunciada por el juego (SPEC-005 §3): recibe el `seq`
/// y devuelve bytes s16le intercalados, o `None` al terminar el stream (§7).
pub struct AudioStreamSource {
    /// Muestras por segundo (SPEC-005 §4).
    pub rate: u32,
    /// Canales mono o estéreo (SPEC-005 §4).
    pub channels: u8,
    /// Generador incremental de fragmentos PCM.
    pub next_chunk: Box<dyn FnMut(u64) -> Option<Vec<u8>> + Send + 'static>,
}

/// Fuente PCM s16le: devuelve los bytes de un fragmento o `None` al terminar.
pub type AudioChunkSource<'a> = &'a mut dyn FnMut(u64) -> Option<Vec<u8>>;

/// Inicia el emisor solo cuando existe una fuente PCM de juego.
/// Sin origen real, devuelve `None` sin clonar el socket ni emitir audio.
pub fn spawn_audio_stream_if_available<S>(
    source: Option<AudioStreamSource>,
    make_sink: impl FnOnce() -> Option<S>,
) -> Option<std::thread::JoinHandle<()>>
where
    S: FrameSink + Send + 'static,
{
    let mut source = source?;
    let mut sink = make_sink()?;
    Some(std::thread::spawn(move || {
        let _ = run_audio_stream(
            &mut sink,
            source.rate,
            source.channels,
            source.next_chunk.as_mut(),
        );
    }))
}

/// Emite un stream de audio completo sobre `sink` (SPEC-005 §7 pasos 1–4):
/// `audio_start`, chunks con `seq` incremental y `audio_stop` al terminar.
/// El ritmo lo marca `rate`/`channels` con fragmentos de `CHUNK_SAMPLES`
/// muestras por canal. Bloquea el hilo caller (hilo propio por sesión).
///
/// # Errors
/// `Disconnected` si el receptor deja de estar disponible.
pub fn run_audio_stream(
    sink: &mut dyn FrameSink,
    rate: u32,
    channels: u8,
    source: AudioChunkSource,
) -> Result<(), AudioSourceError> {
    send(
        sink,
        &AudioMsg::AudioStart {
            rate,
            channels,
            sample_fmt: twinpad_core::audio::SAMPLE_FMT_S16LE.into(),
        },
    )?;

    // Duración real de un fragmento: muestras/canal × canales × 2 bytes
    // a `rate` muestras/s (§7 paso 2: fragmentación ≤ 16 KiB).
    let bytes_per_chunk = CHUNK_SAMPLES * usize::from(channels) * 2;
    debug_assert!(bytes_per_chunk <= MAX_CHUNK_BYTES);
    let chunk_period = std::time::Duration::from_secs_f64(CHUNK_SAMPLES as f64 / f64::from(rate));
    let mut next_deadline = Instant::now();
    let mut seq: u64 = 0;
    while let Some(pcm) = source(seq) {
        let data = base64_url(&pcm);
        send(
            sink,
            &AudioMsg::AudioChunk {
                seq,
                ts: next_deadline.elapsed().as_millis() as u64,
                data,
            },
        )?;
        seq += 1;
        next_deadline += chunk_period;
        let now = Instant::now();
        if next_deadline > now {
            std::thread::sleep(next_deadline - now);
        }
    }

    send(
        sink,
        &AudioMsg::AudioStop {
            reason: AudioStopReason::PluginStopped,
        },
    )
}

fn send(sink: &mut dyn FrameSink, msg: &AudioMsg) -> Result<(), AudioSourceError> {
    let line = msg.encode().map_err(|_| AudioSourceError::Disconnected)?;
    if sink.send_line(&line) {
        Ok(())
    } else {
        Err(AudioSourceError::Disconnected)
    }
}

/// base64url canónico sin relleno (igual criterio que `core::message`).
fn base64_url(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    struct SinkSpy(RefCell<Vec<String>>);
    impl FrameSink for SinkSpy {
        fn send_line(&mut self, line: &str) -> bool {
            self.0.borrow_mut().push(line.to_string());
            true
        }
    }

    // TEST-020 — verifies SPEC-005 (§7: flujo principal del audio).
    #[test]
    fn audio_stream_sends_start_chunks_and_stop_in_order() {
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        let mut emitted = 0u64;
        {
            let mut source = |_seq: u64| -> Option<Vec<u8>> {
                if emitted >= 2 {
                    return None;
                }
                emitted += 1;
                Some(vec![1u8; 128])
            };
            run_audio_stream(&mut sink, 8_000, 1, &mut source).unwrap();
        }
        let lines = sink.0.borrow();
        // start + 2 chunks + stop
        assert_eq!(lines.len(), 4);
        assert!(lines[0].starts_with("{\"type\":\"audio_start\""));
        assert!(lines[1].contains("\"seq\":0"));
        assert!(lines[2].contains("\"seq\":1"));
        assert!(lines[3].starts_with("{\"type\":\"audio_stop\""));
        // Paridad con el codec compartido (test-strategy §10).
        for line in lines.iter() {
            AudioMsg::decode(line).unwrap();
        }
    }

    // TEST-035 — verifies SPEC-005 §3/§5: no audio is emitted without a game PCM source.
    #[test]
    fn unavailable_audio_source_does_not_start_a_stream() {
        use std::sync::Arc;
        use std::sync::atomic::{AtomicBool, Ordering};

        let sink_factory_called = Arc::new(AtomicBool::new(false));
        let factory_called = Arc::clone(&sink_factory_called);
        let handle = spawn_audio_stream_if_available::<SinkSpy>(None, move || {
            factory_called.store(true, Ordering::SeqCst);
            Some(SinkSpy(RefCell::new(Vec::new())))
        });

        assert!(handle.is_none());
        assert!(!sink_factory_called.load(Ordering::SeqCst));
    }

    #[test]
    fn disconnected_sink_stops_the_audio_source() {
        struct DeadSink;
        impl FrameSink for DeadSink {
            fn send_line(&mut self, _line: &str) -> bool {
                false
            }
        }
        let mut sink = DeadSink;
        let mut source = |_seq: u64| -> Option<Vec<u8>> { Some(vec![0u8; 64]) };
        assert!(matches!(
            run_audio_stream(&mut sink, 8_000, 1, &mut source),
            Err(AudioSourceError::Disconnected)
        ));
    }
}
