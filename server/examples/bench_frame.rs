//! Bench del pipeline del emisor: escala + JPEG con contenido variado.
use twinpad_capture::rgb8_scaled;
use twinpad_server::video::encode_jpeg;

fn main() {
    let (sw, sh, dw, dh) = (1920u32, 1080u32, 1280u16, 720u16);
    let mut rgba = vec![0u8; (sw * sh * 4) as usize];
    for (i, b) in rgba.iter_mut().enumerate() {
        *b = ((i * 2654435761) >> (i % 7)) as u8; // pseudo-ruido (peor caso JPEG)
    }
    let rgb = rgb8_scaled(&rgba, sw, sh, dw, dh, twinpad_capture::ChannelLayout::Rgba);
    let _ = encode_jpeg(&rgb, dw, dh, 80); // warmup
    let n = 60u32;
    let t0 = std::time::Instant::now();
    let mut sizes = 0u64;
    let mut encoded = 0u32;
    for _ in 0..n {
        let rgb = rgb8_scaled(&rgba, sw, sh, dw, dh, twinpad_capture::ChannelLayout::Rgba);
        let Some(jpeg) = encode_jpeg(&rgb, dw, dh, 80) else {
            continue; // fallo del encoder: no suma tamaño ni linéaliza
        };
        sizes += jpeg.len() as u64;
        encoded += 1;
        std::hint::black_box(&jpeg);
    }
    let per = t0.elapsed() / encoded.max(1);
    println!(
        "720p peor caso: per-frame {per:?} → max fps {:.1}; jpeg promedio {} B",
        1.0 / per.as_secs_f64(),
        sizes / u64::from(encoded).max(1)
    );
}
