//! Sonda: latencia real del grabber en release (portal screenshot vs pipewire).
fn main() {
    let Ok(mut g) = twinpad_capture::DesktopGrabber::new() else {
        eprintln!("grabber no disponible (sin monitores o sin portal)");
        std::process::exit(1);
    };
    println!("modo: {:?}", g.mode());
    for i in 0..10 {
        let t0 = std::time::Instant::now();
        let frame = g.frame();
        match frame {
            Ok((w, h, layout, buf)) => println!(
                "f{i}: {:.1} ms {}×{} {:?} ({} B)",
                t0.elapsed().as_secs_f64() * 1000.0,
                w,
                h,
                layout,
                buf.len()
            ),
            Err(e) => println!(
                "f{i}: ERR {e:?} tras {:.1} ms",
                t0.elapsed().as_secs_f64() * 1000.0
            ),
        }
    }
}
