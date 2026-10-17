//! Bench del pipeline del emisor: escala + JPEG con contenido variado.
use std::sync::Arc;
use twinpad_capture::rgb8_scaled;
use twinpad_server::tile_video::{RgbFrame, diff_tiles};
use twinpad_server::video::encode_jpeg;

fn main() -> Result<(), &'static str> {
    let (sw, sh, dw, dh) = (1920u32, 1080u32, 1280u16, 720u16);
    let mut rgba = vec![0u8; (sw * sh * 4) as usize];
    for (i, b) in rgba.iter_mut().enumerate() {
        *b = ((i * 2654435761) >> (i % 7)) as u8; // pseudo-ruido (peor caso JPEG)
    }
    let rgb = rgb8_scaled(&rgba, sw, sh, dw, dh, twinpad_capture::ChannelLayout::Rgba);
    let _warmup = encode_jpeg(&rgb, dw, dh, 80).ok_or("JPEG warmup failed")?;
    let n = 120u32;

    let t0 = std::time::Instant::now();
    for _ in 0..n {
        std::hint::black_box(rgb8_scaled(
            &rgba,
            sw,
            sh,
            dw,
            dh,
            twinpad_capture::ChannelLayout::Rgba,
        ));
    }
    let scale = t0.elapsed() / n;

    let t1 = std::time::Instant::now();
    let mut sizes = 0u64;
    for _ in 0..n {
        let jpeg = encode_jpeg(&rgb, dw, dh, 80).ok_or("JPEG encoding failed")?;
        sizes += jpeg.len() as u64;
        std::hint::black_box(jpeg);
    }
    let encode = t1.elapsed() / n;
    let jpeg_size = sizes / u64::from(n);

    let t2 = std::time::Instant::now();
    for _ in 0..n {
        let scaled = rgb8_scaled(&rgba, sw, sh, dw, dh, twinpad_capture::ChannelLayout::Rgba);
        let jpeg = encode_jpeg(&scaled, dw, dh, 80).ok_or("JPEG encoding failed")?;
        std::hint::black_box(jpeg);
    }
    let combined = t2.elapsed() / n;

    let mut changed_pixels = rgb.clone();
    let changed_tile_x = 640usize;
    let changed_tile_y = 360usize;
    let changed_index = (changed_tile_y * usize::from(dw) + changed_tile_x) * 3;
    changed_pixels[changed_index..changed_index + 3].copy_from_slice(&[255, 0, 0]);
    let previous = RgbFrame {
        width: dw,
        height: dh,
        pixels: Arc::from(rgb),
    };
    let changed = RgbFrame {
        width: dw,
        height: dh,
        pixels: Arc::from(changed_pixels),
    };

    let t3 = std::time::Instant::now();
    for _ in 0..n {
        let equal = std::hint::black_box(&previous.pixels) == std::hint::black_box(&changed.pixels);
        if !equal {
            std::hint::black_box(diff_tiles(&previous, &changed));
        }
    }
    let old_tile_scan = t3.elapsed() / n;

    let t4 = std::time::Instant::now();
    for _ in 0..n {
        std::hint::black_box(diff_tiles(&previous, &changed));
    }
    let tile_scan = t4.elapsed() / n;

    let unchanged = RgbFrame {
        width: dw,
        height: dh,
        pixels: Arc::clone(&previous.pixels),
    };
    let t5 = std::time::Instant::now();
    for _ in 0..n {
        let equal =
            std::hint::black_box(&previous.pixels) == std::hint::black_box(&unchanged.pixels);
        if !equal {
            std::hint::black_box(diff_tiles(&previous, &unchanged));
        }
    }
    let old_static_scan = t5.elapsed() / n;

    let t6 = std::time::Instant::now();
    for _ in 0..n {
        std::hint::black_box(diff_tiles(&previous, &unchanged));
    }
    let static_scan = t6.elapsed() / n;
    println!(
        "720p peor caso: scale {scale:?} ({:.1} fps), JPEG SIMD {encode:?} ({:.1} fps), scale+JPEG {combined:?} ({:.1} fps), changed tiles old {old_tile_scan:?} ({:.1} frames/s) vs optimized {tile_scan:?} ({:.1}), static old {old_static_scan:?} ({:.1}) vs optimized {static_scan:?} ({:.1}), JPEG promedio {} B",
        1.0 / scale.as_secs_f64(),
        1.0 / encode.as_secs_f64(),
        1.0 / combined.as_secs_f64(),
        1.0 / old_tile_scan.as_secs_f64(),
        1.0 / tile_scan.as_secs_f64(),
        1.0 / old_static_scan.as_secs_f64(),
        1.0 / static_scan.as_secs_f64(),
        jpeg_size
    );
    Ok(())
}
