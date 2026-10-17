//! Diff de video por tiles fijos — CR-002 / SPEC-003 §4, §7.
use crate::video::{DEFAULT_JPEG_QUALITY, FrameSink, VideoSourceError, encode_jpeg};
use twinpad_core::stream::{FrameFormat, StreamMsg, TILE_SIZE, VideoTile};

/// Imagen RGB y dimensiones recibida desde una fuente de video.
#[derive(Clone, Debug)]
pub struct RgbFrame {
    pub width: u16,
    pub height: u16,
    pub pixels: Vec<u8>,
}

/// Lote de tiles codificados para un cambio entre imágenes.
#[derive(Clone, Debug)]
pub struct EncodedPatch {
    pub tiles: Vec<VideoTile>,
}

/// Estado por stream que selecciona keyframe o patch y mantiene la base wire.
#[derive(Default)]
pub struct TileImageSender {
    previous: Option<RgbFrame>,
    last_image_fn: Option<u64>,
    next_fn: u64,
    last_keyframe_at: Option<std::time::Instant>,
    width: u16,
    height: u16,
}

impl TileImageSender {
    /// Invalida la base de imagen y fuerza un keyframe sin reiniciar `fn`.
    pub fn reset_image(&mut self) {
        self.previous = None;
        self.last_image_fn = None;
        self.last_keyframe_at = None;
        self.width = 0;
        self.height = 0;
    }

    /// Emite un keyframe al inicio/cambio, cada segundo, o cuando el patch no ahorra bytes.
    pub fn emit(
        &mut self,
        sink: &mut dyn FrameSink,
        current: RgbFrame,
        jpeg: Vec<u8>,
        ts: u64,
    ) -> Result<(), VideoSourceError> {
        if !valid_rgb(&current) {
            return Ok(());
        }
        let dimensions_changed = self.width != current.width || self.height != current.height;
        let keyframe_due = self
            .last_keyframe_at
            .is_none_or(|at| at.elapsed().as_millis() >= 1_000);
        let mut keyframe = dimensions_changed || keyframe_due || self.previous.is_none();
        if !keyframe {
            let Some(previous) = self.previous.as_ref() else {
                return Ok(());
            };
            if previous.width == current.width
                && previous.height == current.height
                && previous.pixels == current.pixels
            {
                return Ok(());
            }
        }
        let patch = if keyframe {
            None
        } else {
            self.previous
                .as_ref()
                .and_then(|previous| diff_tiles(previous, &current))
        };

        let patch_line = patch.as_ref().and_then(|delta| {
            let base_fn = self.last_image_fn?;
            StreamMsg::VideoPatch {
                fn_: self.next_fn,
                base_fn,
                ts,
                w: current.width,
                h: current.height,
                tile_size: TILE_SIZE,
                patches: delta.tiles.clone(),
            }
            .encode()
            .ok()
        });
        let keyframe_line = StreamMsg::VideoFrame {
            fn_: self.next_fn,
            ts,
            w: current.width,
            h: current.height,
            fmt: FrameFormat::Jpeg,
            data: base64_url(&jpeg),
        }
        .encode()
        .map_err(|_| VideoSourceError::Disconnected)?;
        let patch_wins = patch_line
            .as_ref()
            .is_some_and(|line| line.len() < keyframe_line.len());
        if !keyframe && !patch_wins {
            keyframe = true;
        }

        let emitted = if keyframe {
            keyframe_line
        } else if let Some(line) = patch_line {
            line
        } else {
            self.previous = Some(current);
            return Ok(());
        };
        if !sink.send_line(&emitted) {
            return Err(VideoSourceError::Disconnected);
        }
        self.last_image_fn = Some(self.next_fn);
        self.next_fn = self.next_fn.saturating_add(1);
        if keyframe {
            self.last_keyframe_at = Some(std::time::Instant::now());
        }
        self.width = current.width;
        self.height = current.height;
        self.previous = Some(current);
        Ok(())
    }
}

/// Devuelve los tiles modificados contra `previous`; `None` significa que no cambió ninguno.
pub fn diff_tiles(previous: &RgbFrame, current: &RgbFrame) -> Option<EncodedPatch> {
    if previous.width != current.width
        || previous.height != current.height
        || !valid_rgb(previous)
        || !valid_rgb(current)
    {
        return None;
    }
    let mut tiles = Vec::new();
    for tile_y in 0..previous.height.div_ceil(TILE_SIZE) {
        for tile_x in 0..previous.width.div_ceil(TILE_SIZE) {
            let x = tile_x * TILE_SIZE;
            let y = tile_y * TILE_SIZE;
            let width = TILE_SIZE.min(previous.width - x);
            let height = TILE_SIZE.min(previous.height - y);
            if tile_equal(previous, current, x, y, width, height) {
                continue;
            }
            let pixels = extract_tile(current, x, y, width, height);
            let jpeg = encode_jpeg(&pixels, width, height, DEFAULT_JPEG_QUALITY)?;
            tiles.push(VideoTile {
                x,
                y,
                w: width,
                h: height,
                fmt: FrameFormat::Jpeg,
                data: base64_url(&jpeg),
            });
        }
    }
    if tiles.is_empty() {
        None
    } else {
        Some(EncodedPatch { tiles })
    }
}

fn valid_rgb(frame: &RgbFrame) -> bool {
    frame.pixels.len() == usize::from(frame.width) * usize::from(frame.height) * 3
}

fn tile_equal(a: &RgbFrame, b: &RgbFrame, x: u16, y: u16, w: u16, h: u16) -> bool {
    for row in y..y + h {
        let begin = (usize::from(row) * usize::from(a.width) + usize::from(x)) * 3;
        let end = begin + usize::from(w) * 3;
        if a.pixels[begin..end] != b.pixels[begin..end] {
            return false;
        }
    }
    true
}

fn extract_tile(frame: &RgbFrame, x: u16, y: u16, w: u16, h: u16) -> Vec<u8> {
    let mut pixels = Vec::with_capacity(usize::from(w) * usize::from(h) * 3);
    for row in y..y + h {
        let begin = (usize::from(row) * usize::from(frame.width) + usize::from(x)) * 3;
        let end = begin + usize::from(w) * 3;
        pixels.extend_from_slice(&frame.pixels[begin..end]);
    }
    pixels
}

fn base64_url(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    struct SinkSpy(RefCell<Vec<String>>);
    impl FrameSink for SinkSpy {
        fn send_line(&mut self, line: &str) -> bool {
            self.0.borrow_mut().push(line.to_owned());
            true
        }
    }

    // TEST-028 — verifies SPEC-003/CR-002 tile diffing, keyframes and wire savings.
    #[test]
    fn static_image_has_no_patch_and_one_moving_tile_isolated() {
        let mut before = RgbFrame {
            width: 128,
            height: 64,
            pixels: vec![0; 128 * 64 * 3],
        };
        let unchanged = before.clone();
        assert!(diff_tiles(&before, &unchanged).is_none());
        before.pixels[(5 * 128 + 70) * 3] = 255;
        let patch = diff_tiles(&unchanged, &before).expect("one changed tile");
        assert_eq!(patch.tiles.len(), 1);
        assert_eq!((patch.tiles[0].x, patch.tiles[0].y), (64, 0));
        assert_eq!((patch.tiles[0].w, patch.tiles[0].h), (64, 64));
    }

    #[test]
    fn edge_tiles_are_clipped_to_frame_dimensions() {
        let before = RgbFrame {
            width: 80,
            height: 70,
            pixels: vec![0; 80 * 70 * 3],
        };
        let mut after = before.clone();
        after.pixels[(69 * 80 + 79) * 3] = 255;
        let patch = diff_tiles(&before, &after).expect("changed bottom-right tile");
        assert_eq!(patch.tiles.len(), 1);
        assert_eq!((patch.tiles[0].x, patch.tiles[0].y), (64, 64));
        assert_eq!((patch.tiles[0].w, patch.tiles[0].h), (16, 6));
    }

    #[test]
    fn static_frames_emit_nothing_until_periodic_keyframe() {
        let frame = RgbFrame {
            width: 64,
            height: 64,
            pixels: vec![12; 64 * 64 * 3],
        };
        let jpeg = encode_jpeg(&frame.pixels, 64, 64, DEFAULT_JPEG_QUALITY).unwrap();
        let mut sender = TileImageSender::default();
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        sender
            .emit(&mut sink, frame.clone(), jpeg.clone(), 0)
            .unwrap();
        sender
            .emit(&mut sink, frame.clone(), jpeg.clone(), 500)
            .unwrap();
        assert_eq!(sink.0.borrow().len(), 1);
        std::thread::sleep(std::time::Duration::from_millis(1_010));
        sender.emit(&mut sink, frame, jpeg, 1_000).unwrap();
        assert_eq!(sink.0.borrow().len(), 2);
        assert!(sink.0.borrow()[1].contains("video_frame"));
    }

    #[test]
    fn dense_changes_fall_back_to_a_full_keyframe_when_patch_is_larger() {
        let mut state = 1u32;
        let mut make_noise = || {
            let mut pixels = vec![0; 128 * 128 * 3];
            for byte in &mut pixels {
                state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                *byte = (state >> 24) as u8;
            }
            pixels
        };
        let first = RgbFrame {
            width: 128,
            height: 128,
            pixels: make_noise(),
        };
        let second = RgbFrame {
            width: 128,
            height: 128,
            pixels: make_noise(),
        };
        let first_jpeg = encode_jpeg(&first.pixels, 128, 128, DEFAULT_JPEG_QUALITY).unwrap();
        let second_jpeg = encode_jpeg(&second.pixels, 128, 128, DEFAULT_JPEG_QUALITY).unwrap();
        let mut sender = TileImageSender::default();
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        sender.emit(&mut sink, first, first_jpeg, 0).unwrap();
        sender.emit(&mut sink, second, second_jpeg, 1).unwrap();

        let messages: Vec<StreamMsg> = sink
            .0
            .borrow()
            .iter()
            .map(|line| StreamMsg::decode(line).unwrap())
            .collect();
        assert!(matches!(messages[0], StreamMsg::VideoFrame { fn_: 0, .. }));
        assert!(matches!(messages[1], StreamMsg::VideoFrame { fn_: 1, .. }));
    }
}
