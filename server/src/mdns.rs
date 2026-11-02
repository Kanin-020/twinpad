//! Anunciador mDNS/DNS-SD del Servidor PC — CR-057 (SPEC-009).
//!
//! El servidor anuncia `_twinpad._tcp.local.` con nombre de instancia fijo
//! `Twinpad`, puerto igual al server_port y TXT `ver=twinpad.v1` mientras su
//! socket de control esté escuchando (SPEC-009 §3): una app ya emparejada
//! re-resuelve tras una renovación DHCP sin re-escanear el QR (REQ-002 AC-4).
//!
//! Falla blando: si el host no tiene mDNS operativo (sin interfaz multicast,
//! demonio del SO ausente), el pairing por IP literal del QR sigue intacto
//! (SPEC-009 §6) y solo se registra un aviso.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use mdns_sd::{DaemonEvent, ServiceDaemon, ServiceInfo};

/// Tipo DNS-SD anunciado (SPEC-009 §3): `_twinpad._tcp.local.`
pub const SERVICE_TYPE: &str = "_twinpad._tcp.local.";

/// Nombre de instancia fijo (SPEC-009 §4): un solo servidor por LAN.
pub const INSTANCE_NAME: &str = "Twinpad";

/// Host del anunciador: el nombre que el QR ya acepta en `s` (§4.1).
pub const HOST_NAME: &str = "twinpad.local.";

/// TXT de versión de protocolo (SPEC-009 §3).
const TXT_VER_KEY: &str = "ver";
const TXT_VER_VALUE: &str = "twinpad.v1";

/// Construye el `ServiceInfo` canónico del anunciador (§3). Expuesto para el
/// test de TEST-0133: es una unidad pura que verifica nombre/puerto/TXT.
pub(crate) fn service_info(port: u16, ip: IpAddr) -> mdns_sd::Result<ServiceInfo> {
    let mut properties: HashMap<String, String> = HashMap::new();
    properties.insert(TXT_VER_KEY.to_owned(), TXT_VER_VALUE.to_owned());
    ServiceInfo::new(SERVICE_TYPE, INSTANCE_NAME, HOST_NAME, ip, port, properties)
}

/// Arranca el anunciador en un hilo demonio y devuelve una bandera que queda
/// a `true` si el registro pudo hacerse (diagnóstico; el pairing no depende
/// de ella: SPEC-009 §6, IP literal siempre disponible).
pub fn spawn_announcer(port: u16, ip: IpAddr) -> Arc<AtomicBool> {
    let registered = Arc::new(AtomicBool::new(false));
    let registered_ref = Arc::clone(&registered);
    std::thread::Builder::new()
        .name("twinpad-mdns-announcer".into())
        .spawn(move || announce_loop(port, ip, registered_ref))
        .ok();
    registered
}

fn announce_loop(port: u16, ip: IpAddr, registered: Arc<AtomicBool>) {
    let Ok(daemon) = ServiceDaemon::new() else {
        eprintln!(
            "Twinpad: mDNS no disponible; el pairing por IP literal del QR sigue operando (SPEC-009 §6)."
        );
        return;
    };
    let info = match service_info(port, ip) {
        Ok(info) => info,
        Err(error) => {
            eprintln!("Twinpad: ServiceInfo mDNS inválido ({error}); sin anuncio zeroconf.");
            return;
        }
    };
    if let Err(error) = daemon.register(info) {
        eprintln!(
            "Twinpad: no se pudo registrar el servicio mDNS ({error}); sin anuncio zeroconf."
        );
        return;
    }

    // Vibración de hilo: el daemon vive mientras este hilo retenga su
    // handle y el monitor nos avisa de errores/renovaciones de interfaz.
    match daemon.monitor() {
        Ok(events) => {
            registered.store(true, Ordering::Release);
            println!(
                "Twinpad: anunciando {SERVICE_TYPE} instancia {INSTANCE_NAME} en {ip}:{port} (SPEC-009)."
            );
            while let Ok(event) = events.recv() {
                match event {
                    DaemonEvent::Error(error) => {
                        eprintln!("Twinpad: error del daemon mDNS: {error}");
                    }
                    DaemonEvent::IpAdd(_) | DaemonEvent::IpDel(_) => {
                        eprintln!(
                            "Twinpad: interfaz del host cambió; el anuncio mDNS se re-emitirá automáticamente."
                        );
                    }
                    _ => {}
                }
            }
        }
        Err(error) => {
            registered.store(true, Ordering::Release);
            eprintln!("Twinpad: mDNS sin monitor ({error}); anuncio registrado una vez.");
            // Retención igual: el daemon necesita sobrevivir al anunciarse.
            loop {
                std::thread::park();
            }
        }
    }
    // Al perder el monitor el daemon se apaga con el hilo; el servicio deja
    // de anunciarse, coherente con «mientras su socket de control listening»
    // (SPEC-009 §4) porque este hilo solo muere con el proceso (R7 del QR).
}

/// Browse de loopback para TEST-0133 (esquema del test de integración):
/// descubre nuestra propia instancia en una red tranquila del host.
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;
    use mdns_sd::ServiceEvent;
    use std::net::Ipv4Addr;

    #[test]
    fn service_info_matches_spec009() {
        let info = service_info(8420, Ipv4Addr::new(192, 168, 100, 101).into())
            .expect("ServiceInfo canónico");
        assert_eq!(info.get_fullname(), "Twinpad._twinpad._tcp.local.");
        assert_eq!(info.get_port(), 8420);
        assert_eq!(info.get_hostname(), "twinpad.local.");
        let ver = info.get_property("ver").expect("TXT ver presente");
        assert_eq!(ver.val_str(), "twinpad.v1");
    }

    #[test]
    fn resolves_our_own_instance_in_loopback() {
        let daemon = ServiceDaemon::new().expect("daemon loopback");
        let info =
            service_info(8420, Ipv4Addr::new(192, 168, 100, 101).into()).expect("ServiceInfo");
        daemon.register(info).expect("register loopback");
        let receiver = daemon.browse(SERVICE_TYPE).expect("browse");
        let mut resolved = false;
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        while std::time::Instant::now() < deadline {
            match receiver.recv_timeout(std::time::Duration::from_millis(500)) {
                Ok(ServiceEvent::ServiceResolved(_)) => {
                    resolved = true;
                    break;
                }
                Ok(_) => {}
                // Timeout del canal: seguimos hasta agotar la fecha límite.
                Err(mdns_sd::RecvTimeoutError::Timeout) => {}
                Err(mdns_sd::RecvTimeoutError::Disconnected) => break,
            }
        }
        let _ = daemon.stop_browse(SERVICE_TYPE);
        assert!(
            resolved,
            "la instancia anunciada debe resolverse en loopback"
        );
    }
}
