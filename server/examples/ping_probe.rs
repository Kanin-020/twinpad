//! Sonda de repro local (CR-055): cliente REAL (usa `twinpad-core::ClientHandshake`)
//! contra el binario real `twinpad-server`. Completa el handshake
//! (pair_request → pair_challenge → pair_proof → pair_ack) y luego hace eco de
//! todo `pong` recibido, con la misma cadencia del teléfono. Loguea TTD lo que
//! llega. Suficiente 20 s conectado para ver ~4 pings.
//!
//! Ejecutar: cargo run -p twinpad-server --example ping_probe -- <ip> <port>

use std::io::{BufRead, BufReader, Write};
use std::net::TcpStream;
use std::time::Duration;

use twinpad_core::Nonce;
use twinpad_core::handshake::{ClientHandshake, StepIn, StepOut};
use twinpad_core::message::HandshakeMsg;

fn main() {
    let mut args = std::env::args().skip(1);
    let addr = args.next().unwrap_or_else(|| "127.0.0.1".to_string());
    let port: u16 = args.next().and_then(|p| p.parse().ok()).unwrap_or(18480);

    // Token del QR: lo validamos de un URI `twinpad://v1#...` o lo tomamos tal
    // cual si ya es base64url de 32 bytes. Requisito: t out-of-band por argumento.
    let token = std::env::var("TWINPAD_TOKEN").expect("TWINPAD_TOKEN no definido");

    let Ok(stream) = TcpStream::connect((addr.as_str(), port)) else {
        eprintln!("probe: no conecta a {addr}:{port}");
        std::process::exit(1);
    };
    let mut out = stream.try_clone().expect("clonar stream");
    out.set_read_timeout(Some(Duration::from_secs(30)))
        .expect("timeout de lectura");
    let mut reader = BufReader::new(stream);

    let (mut hs, req) = ClientHandshake::start(&token).expect("start del cliente");
    println!("probe: handake, envio pair_request");
    send(&mut out, &req);

    let mut held_stream = out.try_clone().expect("clone");
    // Bucle de regreso hasta establecido; igual que la app.
    let mut nonce_c = Nonce([0u8; 32]);
    let mut acked = false;
    for _ in 0..10 {
        let line = read_line(&mut reader);
        let Some(line) = line else {
            eprintln!("probe: EOF en handshake");
            std::process::exit(1);
        };
        let Ok(msg) = twinpad_core::message::decode(&line) else {
            eprintln!("probe: línea inválida, {line}");
            continue;
        };
        if let HandshakeMsg::PairChallenge { .. } = msg {
            nonce_c = Nonce([42u8; 32]);
        }
        match hs.step(StepIn::Msg(&msg), nonce_c) {
            StepOut::Out(m) => send(&mut held_stream, &m),
            StepOut::Established { session_id, .. } => {
                println!(
                    "probe: ESTABLISHED session_id={}",
                    twinpad_core::message::b64_16(&session_id.0)
                );
                acked = true;
                break;
            }
            StepOut::Rejected(e) => {
                eprintln!("probe: rechazado {e:?}");
                std::process::exit(1);
            }
            StepOut::NeedMore => {}
        }
    }
    assert!(acked, "handshake incompleto tras 10 roundtrips");
    //cmd de cierre: no cerramos la conexión; alive.

    // S3: eco de todo lo que llega, como el teléfono. Pings → pong.
    let mut got_pings = 0u64;
    let deadline = std::time::Instant::now() + Duration::from_secs(20);
    let mut buf = String::new();
    while std::time::Instant::now() < deadline {
        buf.clear();
        match reader.read_line(&mut buf) {
            Ok(0) => {
                println!("probe: EOF del server en {got_pings} pings.");
                break;
            }
            Ok(_) => {
                let line = buf.trim_end();
                if line.is_empty() {
                    continue;
                }
                if let Ok(m) = twinpad_core::message::decode(line) {
                    if let HandshakeMsg::Ping { t } = m {
                        got_pings += 1;
                        println!("probe: recibo ping t={t}. Replico pong.");
                        let _ = send(&mut held_stream, &HandshakeMsg::Pong { t });
                    } else {
                        println!("probe: msg no latido llegado.");
                    }
                } else {
                    println!("probe: línea no-NDJSON llegada (len={}).", line.len());
                }
            }
            Err(e) => {
                println!("probe: error de lectura ({e});",);
                break;
            }
        }
    }
    println!("probe: FIN ({got_pings} pings ecoados en total).");
}

fn send(out: &mut TcpStream, msg: &HandshakeMsg) {
    let line = twinpad_core::message::encode(msg).expect("encode probe");
    out.write_all(line.as_bytes())
        .and_then(|_| out.write_all(b"\n"))
        .and_then(|_| out.flush())
        .expect("escribir en probe");
}

fn read_line(reader: &mut BufReader<TcpStream>) -> Option<String> {
    let mut buf = String::new();
    reader.read_line(&mut buf).ok().map(|_| buf)
}
