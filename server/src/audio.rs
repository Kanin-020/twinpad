//! Emisor del Stream de audio en el Servidor PC — SPEC-005 §7 (lado origen).
//!
//! v1 sin Plugin: fuente sintética de demostración (tono suave) mientras no
//! exista el juego con audio (SPEC-005 §3: el origen real es el Plugin). El
//! códec es PCM `s16le` sin compresión (OQ §17 resuelta por el owner); los
//! fragmentos ≤ 16 KiB viajan con `seq` incremental (R2: sin retransmisión).

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

/// Parámetros del stream de demostración (v1 sin compresión: OQ §17).
pub const DEMO_RATE: u32 = 44_100;
pub const DEMO_CHANNELS: u8 = 2;

/// Fuente de audio PCM s16le: recibe el `seq` y devuelve los bytes del
/// fragmento, o `None` para terminar el stream limpio (§7 paso 4).
pub type AudioChunkSource<'a> = &'a mut dyn FnMut(u64) -> Option<Vec<u8>>;

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

/// Tono de demostración: seno suave determinista por `seq` (ENG P10).
/// Genera `samples` muestras por canal intercaladas (s16le estéreo).
pub fn demo_chunk(seq: u64, samples_per_channel: usize, rate: u32) -> Vec<u8> {
    let phase0 = (seq * CHUNK_SAMPLES as u64) as f64;
    let mut out = Vec::with_capacity(samples_per_channel * usize::from(DEMO_CHANNELS) * 2);
    for i in 0..samples_per_channel {
        // La2 (440 Hz) amortiguado, bajo volumen (±10 % de rango).
        let t = (phase0 + f64::from(i as u32)) / f64::from(rate);
        let amplitude = 3_276.0; // ~10 % de i16::MAX
        let sample = (amplitude * (2.0 * std::f64::consts::PI * 440.0 * t).sin()) as i16;
        for _ in 0..usize::from(DEMO_CHANNELS) {
            out.extend_from_slice(&sample.to_le_bytes());
        }
    }
    out
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

    #[test]
    fn demo_chunk_is_deterministic_and_sized() {
        let a = demo_chunk(0, 1_024, 44_100);
        let b = demo_chunk(0, 1_024, 44_100);
        assert_eq!(a, b);
        // 1 024 muestras × 2 canales × 2 bytes = 4 096 B (≤ 16 KiB, §4).
        assert_eq!(a.len(), 1_024 * 2 * 2);
        // El patrón cambia con el fragmento.
        assert_ne!(demo_chunk(0, 1_024, 44_100), demo_chunk(1, 1_024, 44_100));
    }

    #[test]
    fn demo_chunks_fit_the_16kib_limit() {
        // Cada fragmento del stream demo debe validar en el codec compartido.
        for seq in 0..3u64 {
            let chunk = AudioMsg::AudioChunk {
                seq,
                ts: 0,
                data: base64_url(&demo_chunk(seq, CHUNK_SAMPLES, DEMO_RATE)),
            };
            let line = chunk.encode().unwrap();
            assert_eq!(AudioMsg::decode(&line), Ok(chunk));
        }
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
