//! Mando virtual DualSense opcional en el Servidor PC (CR-113) — Linux.
//!
//! El Servidor expone un HID virtual por `/dev/uhid` para que el SO de la PC
//! (y Steam) vea Twinpad como un gamepad propio con touchpad, giroscopio y
//! botón PS; cosas que el descriptor BT genérico no puede ofrecer porque el
//! SDP de HID no transporta VID/PID (CR-095 §5 había descartado el mando
//! virtual y CR-113 lo recupera como modo opcional).
//!
//! Solo se activa con `TWINPAD_VIRTUAL_PAD=dualsense` (o `1`) en el entorno del
//! servidor y consume los `input_report` que llegan por la sesión LAN: si la
//! app está usando BT HID no llega nada y el mando virtual queda en reposo
//! (SPEC-004 §8.4). El descriptor y los reportes de feature se portan de
//! inputtino (MIT, <https://github.com/games-on-whales/inputtino>), que a su
//! vez documenta el descriptor USB del DualSense.

use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use twinpad_core::input::InputMsg;
use twinpad_core::input::MotionSample;

/// Variable de entorno que habilita el backend (`dualsense`, `ds5` o `1`).
pub const ENV_VAR: &str = "TWINPAD_VIRTUAL_PAD";

const UHID_PATH: &str = "/dev/uhid";
/// `sizeof(struct uhid_event)` con `HID_MAX_DESCRIPTOR_SIZE = 4096`: el kernel
/// rechaza cualquier escritura que no mida exactamente esto (la unión se
/// alinea a 4 tras `uhid_create2_req`).
const UHID_EVENT_SIZE: usize = 4380;
const UHID_DESTROY: u32 = 1;
const UHID_GET_REPORT: u32 = 9;
const UHID_GET_REPORT_REPLY: u32 = 10;
const UHID_CREATE2: u32 = 11;
const UHID_INPUT2: u32 = 12;
const BUS_USB: u16 = 0x03;
const SONY_VENDOR: u32 = 0x054c;
const DUALSENSE_PRODUCT: u32 = 0x0ce6;
const DEVICE_VERSION: u32 = 0x0100;
const HID_DESCRIPTOR_MAX: usize = 4096;
const INPUT_REPORT_LEN: usize = 64;
/// Cadencia de repetición del reporte (Steam espera eventos continuos).
const REPEAT_INTERVAL_MS: u64 = 10;
/// Unidades crudas por rad/s que espera SDL/Steam para el giroscopio DualSense.
const GYRO_UNITS_PER_RAD_S: f32 = 1145.0;
/// Unidades crudas por m/s² (inputtino: g × 9.80665 × 100).
const ACCEL_UNITS_PER_M_S2: f32 = 98.0665;
const TOUCHPAD_WIDTH: i32 = 1920;
const TOUCHPAD_HEIGHT: i32 = 1080;
const TOUCH_COORD_MAX: i32 = 65_535;

/// Estado del mando que se pinta en el reporte USB.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PadState {
    pub stick_lx: i8,
    pub stick_ly: i8,
    pub stick_rx: i8,
    pub stick_ry: i8,
    pub dpad: u8,
    pub buttons: u16,
    pub trigger_l2: u8,
    pub trigger_r2: u8,
    pub motion: Option<MotionSample>,
    /// Toque normalizado a 0…65535; `None` = sin contacto.
    pub touch: Option<(u16, u16)>,
}

impl Default for PadState {
    fn default() -> Self {
        Self {
            stick_lx: 0,
            stick_ly: 0,
            stick_rx: 0,
            stick_ry: 0,
            dpad: 8,
            buttons: 0,
            trigger_l2: 0,
            trigger_r2: 0,
            motion: None,
            touch: None,
        }
    }
}

impl PadState {
    fn set_from(&mut self, msg: &InputMsg) {
        let InputMsg::InputReport {
            stick_lx,
            stick_ly,
            stick_rx,
            stick_ry,
            dpad,
            buttons,
            trigger_l2,
            trigger_r2,
            motion,
            touch_x,
            touch_y,
            touch_pressed,
            ..
        } = msg;
        self.stick_lx = *stick_lx;
        self.stick_ly = *stick_ly;
        self.stick_rx = *stick_rx;
        self.stick_ry = *stick_ry;
        self.dpad = *dpad;
        self.buttons = *buttons;
        self.trigger_l2 = *trigger_l2;
        self.trigger_r2 = *trigger_r2;
        self.motion = motion.clone();
        self.touch = if *touch_pressed {
            Some((*touch_x, *touch_y))
        } else {
            None
        };
    }
}

/// Eje de la Signature (−127…127) → eje DualSense (0…255, centro 128).
fn scale_stick(value: i8) -> u8 {
    let scaled = (i32::from(value) + 127) * 255 + 127;
    (scaled / 254).clamp(0, 255) as u8
}

/// Coordenada normalizada del toque → rango del touchpad DualSense.
fn scale_touch(value: u16, max: i32) -> u16 {
    let scaled = i32::from(value) * max / TOUCH_COORD_MAX;
    scaled.clamp(0, max) as u16
}

fn pack_touch_point(point: Option<(u16, u16)>) -> [u8; 4] {
    match point {
        // El bit alto de `id` marca el contacto como inactivo (hid-playstation).
        Some((x, y)) => {
            let x = scale_touch(x, TOUCHPAD_WIDTH);
            let y = scale_touch(y, TOUCHPAD_HEIGHT);
            [
                0x00, // contacto activo, id 0
                (x & 0xFF) as u8,
                (((x >> 8) & 0x0F) as u8) | (((y & 0x0F) as u8) << 4),
                ((y >> 4) & 0xFF) as u8,
            ]
        }
        None => [0x80, 0, 0, 0],
    }
}

fn motion_axis(value: f32, units: f32) -> i16 {
    let raw = value * units;
    if raw.is_nan() {
        0
    } else {
        raw.clamp(f32::from(i16::MIN), f32::from(i16::MAX)) as i16
    }
}

/// Arma el reporte de input USB del DualSense (report id 0x01 + 63 bytes).
pub(crate) fn encode_input_report(
    state: &PadState,
    seq: u8,
    sensor_timestamp: u32,
) -> [u8; INPUT_REPORT_LEN] {
    let mut report = [0u8; INPUT_REPORT_LEN];
    report[0] = 0x01;
    report[1] = scale_stick(state.stick_lx);
    report[2] = scale_stick(state.stick_ly);
    report[3] = scale_stick(state.stick_rx);
    report[4] = scale_stick(state.stick_ry);
    report[5] = state.trigger_l2;
    report[6] = state.trigger_r2;
    report[7] = seq;

    // Botones de cara + hat: máscara 0x10…0x80 y D-pad en el nibble bajo.
    let hat = if state.dpad <= 7 { state.dpad } else { 0x08 };
    let mut face = hat;
    if state.buttons & (1 << 2) != 0 {
        face |= 0x10; // X → cuadrado
    }
    if state.buttons & (1 << 0) != 0 {
        face |= 0x20; // A → cruz
    }
    if state.buttons & (1 << 1) != 0 {
        face |= 0x40; // B → círculo
    }
    if state.buttons & (1 << 3) != 0 {
        face |= 0x80; // Y → triángulo
    }
    report[8] = face;
    let mut shoulder = 0u8;
    if state.buttons & (1 << 4) != 0 {
        shoulder |= 0x01; // L1
    }
    if state.buttons & (1 << 5) != 0 {
        shoulder |= 0x02; // R1
    }
    if state.trigger_l2 > 0 {
        shoulder |= 0x04; // L2 digital
    }
    if state.trigger_r2 > 0 {
        shoulder |= 0x08; // R2 digital
    }
    if state.buttons & (1 << 9) != 0 {
        shoulder |= 0x10; // Select → Create
    }
    if state.buttons & (1 << 8) != 0 {
        shoulder |= 0x20; // Start → Options
    }
    if state.buttons & (1 << 6) != 0 {
        shoulder |= 0x40; // L3
    }
    if state.buttons & (1 << 7) != 0 {
        shoulder |= 0x80; // R3
    }
    report[9] = shoulder;
    let mut extra = 0u8;
    if state.buttons & (1 << 10) != 0 {
        extra |= 0x01; // Home → PS
    }
    if state.buttons & (1 << 11) != 0 {
        extra |= 0x02; // extra_2 → click del touchpad
    }
    report[10] = extra;

    if let Some(motion) = &state.motion {
        for (index, value) in motion.gyro.iter().enumerate() {
            let raw = motion_axis(*value, GYRO_UNITS_PER_RAD_S).to_le_bytes();
            let offset = 16 + index * 2;
            report[offset] = raw[0];
            report[offset + 1] = raw[1];
        }
        for (index, value) in motion.accel.iter().enumerate() {
            let raw = motion_axis(*value, ACCEL_UNITS_PER_M_S2).to_le_bytes();
            let offset = 22 + index * 2;
            report[offset] = raw[0];
            report[offset + 1] = raw[1];
        }
    }
    report[28..32].copy_from_slice(&sensor_timestamp.to_le_bytes());

    report[33..37].copy_from_slice(&pack_touch_point(state.touch));
    // Segundo punto del touchpad: siempre inactivo (Twinpad usa un dedo).
    report[37..41].copy_from_slice(&pack_touch_point(None));

    // Batería reportada como llena (el porcentaje del teléfono va por su HUD).
    report[53] = 0x0A | (0x02 << 4);
    report[54] = 0x0C;
    report
}

/// Feature report que pide el host al enumerar el DualSense (Steam lo usa
/// para calibrar el giroscopio y reconocer el mando).
fn feature_report(rnum: u8, mac: &[u8; 6]) -> Option<Vec<u8>> {
    match rnum {
        0x05 => Some(PS5_CALIBRATION_INFO.to_vec()),
        0x09 => {
            let mut report = PS5_PAIRING_INFO.to_vec();
            for (index, byte) in mac.iter().rev().enumerate() {
                if let Some(slot) = report.get_mut(1 + index) {
                    *slot = *byte;
                }
            }
            Some(report)
        }
        0x20 => Some(PS5_FIRMWARE_INFO.to_vec()),
        _ => None,
    }
}

struct Shared {
    state: Mutex<PadState>,
    writer: Mutex<File>,
    stop: AtomicBool,
    mac: [u8; 6],
}

/// Mando virtual en ejecución; `update` pinta el estado de la Signature.
pub struct VirtualPad {
    shared: Arc<Shared>,
}

impl VirtualPad {
    /// Crea el dispositivo UHID y arranca los hilos de reporte.
    #[cfg(target_os = "linux")]
    pub fn start() -> std::io::Result<Self> {
        if PS5_USB_REPORT_DESCRIPTOR.len() > HID_DESCRIPTOR_MAX {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "descriptor HID demasiado grande",
            ));
        }
        let mut writer = OpenOptions::new().read(true).write(true).open(UHID_PATH)?;
        let mac = device_mac();
        let uniq = format_mac(&mac);
        writer.write_all(&build_create_event(&uniq))?;
        let reader = writer.try_clone()?;
        let shared = Arc::new(Shared {
            state: Mutex::new(PadState::default()),
            writer: Mutex::new(writer),
            stop: AtomicBool::new(false),
            mac,
        });
        spawn_reader(reader, Arc::clone(&shared));
        spawn_reporter(Arc::clone(&shared));
        Ok(Self { shared })
    }

    #[cfg(not(target_os = "linux"))]
    pub fn start() -> std::io::Result<Self> {
        Err(std::io::Error::new(
            std::io::ErrorKind::Unsupported,
            "el mando virtual DualSense solo está disponible en Linux (/dev/uhid)",
        ))
    }

    /// Aplica la última instantánea aceptada de la Signature (SPEC-004 §4).
    pub fn update(&self, msg: &InputMsg) {
        if let Ok(mut state) = self.shared.state.lock() {
            state.set_from(msg);
        }
    }

    /// Suelta todos los controles (fin de sesión): evita botones pegados.
    pub fn release_all(&self) {
        if let Ok(mut state) = self.shared.state.lock() {
            *state = PadState::default();
        }
    }
}

impl Drop for VirtualPad {
    fn drop(&mut self) {
        self.shared.stop.store(true, Ordering::Release);
        if let Ok(mut writer) = self.shared.writer.lock() {
            let mut event = [0u8; UHID_EVENT_SIZE];
            event[0..4].copy_from_slice(&UHID_DESTROY.to_le_bytes());
            let _ = writer.write_all(&event);
        }
    }
}

/// Fichero de preferencia del mando virtual en la carpeta de datos (SPEC-010
/// W10, CR-114): `on`/`off`, elegido desde la página local.
const PREFERENCE_FILE: &str = "virtual-pad";

/// Mando virtual gobernable en caliente (SPEC-010 W10, CR-114).
///
/// La preferencia guardada manda; si no hay fichero, decide la variable de
/// entorno [`ENV_VAR`] (modo de arranque de CR-113). `update`/`release_all`
/// son no-ops con el mando apagado: las sesiones siguen su curso.
pub struct VirtualPadManager {
    preference: Option<std::path::PathBuf>,
    pad: Mutex<Option<Arc<VirtualPad>>>,
}

impl VirtualPadManager {
    /// Arranca el servidor con la preferencia guardada o, si no existe, con
    /// `TWINPAD_VIRTUAL_PAD`. Un fallo al crear el HID no aborta el server.
    pub fn from_environment() -> Arc<Self> {
        let preference = crate::data_dir::current_dir()
            .ok()
            .map(|dir| dir.join(PREFERENCE_FILE));
        let wanted = read_preference(preference.as_deref()).unwrap_or_else(env_requested);
        let manager = Self {
            preference,
            pad: Mutex::new(None),
        };
        if wanted {
            match VirtualPad::start() {
                Ok(pad) => {
                    println!(
                        "Mando virtual DualSense activo (/dev/uhid, VID 054C:0CE6); recibe los \
                         input_report de la LAN (SPEC-004 §8.4)."
                    );
                    if let Ok(mut slot) = manager.pad.lock() {
                        *slot = Some(Arc::new(pad));
                    }
                }
                Err(error) => {
                    eprintln!("Twinpad: mando virtual no disponible ({error}); sigo sin él.")
                }
            }
        }
        Arc::new(manager)
    }

    pub fn enabled(&self) -> bool {
        self.pad.lock().map(|slot| slot.is_some()).unwrap_or(false)
    }

    /// Enciende o apaga el HID virtual y recuerda la elección. Apagar es
    /// inmediato (el dispositivo desaparece del SO); encender puede fallar
    /// si `/dev/uhid` no es accesible, y entonces el estado no cambia.
    pub fn set_enabled(&self, enabled: bool) -> Result<(), String> {
        {
            let mut slot = self
                .pad
                .lock()
                .map_err(|_| "estado del mando virtual no disponible".to_string())?;
            if enabled {
                if slot.is_none() {
                    let pad = VirtualPad::start().map_err(|error| error.to_string())?;
                    *slot = Some(Arc::new(pad));
                }
            } else {
                *slot = None;
            }
        }
        // La elección ya se aplicó; si el fichero no se puede escribir, el
        // mando funciona igual hasta el próximo arranque.
        if let Err(error) = write_preference(self.preference.as_deref(), enabled) {
            eprintln!("Twinpad: no se pudo guardar la preferencia del mando virtual: {error}");
        }
        Ok(())
    }

    pub fn update(&self, msg: &InputMsg) {
        if let Ok(slot) = self.pad.lock() {
            if let Some(pad) = slot.as_ref() {
                pad.update(msg);
            }
        }
    }

    pub fn release_all(&self) {
        if let Ok(slot) = self.pad.lock() {
            if let Some(pad) = slot.as_ref() {
                pad.release_all();
            }
        }
    }
}

/// ¿El entorno pide el mando virtual por defecto (CR-113)?
fn env_requested() -> bool {
    match std::env::var(ENV_VAR) {
        Ok(value) => matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "dualsense" | "ds5" | "1" | "true"
        ),
        Err(_) => false,
    }
}

/// Preferencia guardada; `None` si no hay fichero o no se reconoce.
fn read_preference(path: Option<&std::path::Path>) -> Option<bool> {
    let text = std::fs::read_to_string(path?).ok()?;
    match text.trim().to_ascii_lowercase().as_str() {
        "on" | "1" | "true" => Some(true),
        "off" | "0" | "false" => Some(false),
        _ => None,
    }
}

fn write_preference(path: Option<&std::path::Path>, enabled: bool) -> std::io::Result<()> {
    let Some(path) = path else {
        return Ok(());
    };
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    std::fs::write(path, if enabled { "on\n" } else { "off\n" })
}

fn device_mac() -> [u8; 6] {
    let mut mac = [0x02u8, 0, 0, 0, 0, 1];
    if getrandom::fill(&mut mac).is_err() {
        return mac;
    }
    // MAC localmente administrada y unicast.
    mac[0] = (mac[0] & 0xFC) | 0x02;
    mac
}

fn format_mac(mac: &[u8; 6]) -> String {
    mac.iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}

fn build_create_event(uniq: &str) -> [u8; UHID_EVENT_SIZE] {
    let mut event = [0u8; UHID_EVENT_SIZE];
    event[0..4].copy_from_slice(&UHID_CREATE2.to_le_bytes());
    write_cstr(&mut event[4..132], "Twinpad DualSense");
    write_cstr(&mut event[132..196], "TWINPAD_UHID");
    write_cstr(&mut event[196..260], uniq);
    event[260..262].copy_from_slice(&(PS5_USB_REPORT_DESCRIPTOR.len() as u16).to_le_bytes());
    event[262..264].copy_from_slice(&BUS_USB.to_le_bytes());
    event[264..268].copy_from_slice(&SONY_VENDOR.to_le_bytes());
    event[268..272].copy_from_slice(&DUALSENSE_PRODUCT.to_le_bytes());
    event[272..276].copy_from_slice(&DEVICE_VERSION.to_le_bytes());
    // country = 0; el MAC viaja en el feature report de emparejamiento.
    event[280..280 + PS5_USB_REPORT_DESCRIPTOR.len()].copy_from_slice(&PS5_USB_REPORT_DESCRIPTOR);
    event
}

fn write_cstr(slot: &mut [u8], value: &str) {
    let bytes = value.as_bytes();
    let len = bytes.len().min(slot.len().saturating_sub(1));
    slot[..len].copy_from_slice(&bytes[..len]);
}

fn build_input_event(report: &[u8; INPUT_REPORT_LEN]) -> [u8; UHID_EVENT_SIZE] {
    let mut event = [0u8; UHID_EVENT_SIZE];
    event[0..4].copy_from_slice(&UHID_INPUT2.to_le_bytes());
    event[4..6].copy_from_slice(&(report.len() as u16).to_le_bytes());
    event[6..6 + report.len()].copy_from_slice(report);
    event
}

fn spawn_reporter(shared: Arc<Shared>) {
    thread::spawn(move || {
        let mut seq = 0u8;
        while !shared.stop.load(Ordering::Acquire) {
            let elapsed_ns = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0);
            let timestamp = (elapsed_ns / 333) as u32;
            let report = match shared.state.lock() {
                Ok(state) => encode_input_report(&state, seq, timestamp),
                Err(_) => return,
            };
            seq = seq.wrapping_add(1);
            let event = build_input_event(&report);
            let written = match shared.writer.lock() {
                Ok(mut writer) => writer.write_all(&event),
                Err(_) => return,
            };
            if written.is_err() {
                return;
            }
            thread::sleep(Duration::from_millis(REPEAT_INTERVAL_MS));
        }
    });
}

fn spawn_reader(mut reader: File, shared: Arc<Shared>) {
    thread::spawn(move || {
        let mut buffer = [0u8; UHID_EVENT_SIZE];
        loop {
            match reader.read(&mut buffer) {
                Ok(0) | Err(_) => return,
                Ok(size) if size >= 12 => {
                    if shared.stop.load(Ordering::Acquire) {
                        return;
                    }
                    handle_event(&buffer, &shared);
                }
                Ok(_) => {}
            }
        }
    });
}

fn handle_event(event: &[u8; UHID_EVENT_SIZE], shared: &Shared) {
    let kind = u32::from_le_bytes([event[0], event[1], event[2], event[3]]);
    if kind != UHID_GET_REPORT {
        return;
    }
    let id = u32::from_le_bytes([event[4], event[5], event[6], event[7]]);
    let rnum = event[8];
    let mut reply = [0u8; UHID_EVENT_SIZE];
    reply[0..4].copy_from_slice(&UHID_GET_REPORT_REPLY.to_le_bytes());
    reply[4..8].copy_from_slice(&id.to_le_bytes());
    match feature_report(rnum, &shared.mac) {
        Some(data) => {
            reply[8..10].copy_from_slice(&0u16.to_le_bytes());
            reply[10..12].copy_from_slice(&(data.len() as u16).to_le_bytes());
            reply[12..12 + data.len()].copy_from_slice(&data);
        }
        None => reply[8..10].copy_from_slice(&22u16.to_le_bytes()),
    }
    if let Ok(mut writer) = shared.writer.lock() {
        let _ = writer.write_all(&reply);
    }
}

/// Descriptor HID USB del DualSense (input 0x01, feature 0x05/0x09/0x20).
const PS5_USB_REPORT_DESCRIPTOR: [u8; 273] = [
    0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
    0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x06, 0x81, 0x02, 0x06,
    0x00, 0xFF, 0x09, 0x20, 0x95, 0x01, 0x81, 0x02, 0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07,
    0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x65, 0x00, 0x05,
    0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0F, 0x81, 0x02, 0x06,
    0x00, 0xFF, 0x09, 0x21, 0x95, 0x0D, 0x81, 0x02, 0x06, 0x00, 0xFF, 0x09, 0x22, 0x15, 0x00, 0x26,
    0xFF, 0x00, 0x75, 0x08, 0x95, 0x34, 0x81, 0x02, 0x85, 0x02, 0x09, 0x23, 0x95, 0x2F, 0x91, 0x02,
    0x85, 0x05, 0x09, 0x33, 0x95, 0x28, 0xB1, 0x02, 0x85, 0x08, 0x09, 0x34, 0x95, 0x2F, 0xB1, 0x02,
    0x85, 0x09, 0x09, 0x24, 0x95, 0x13, 0xB1, 0x02, 0x85, 0x0A, 0x09, 0x25, 0x95, 0x1A, 0xB1, 0x02,
    0x85, 0x20, 0x09, 0x26, 0x95, 0x3F, 0xB1, 0x02, 0x85, 0x21, 0x09, 0x27, 0x95, 0x04, 0xB1, 0x02,
    0x85, 0x22, 0x09, 0x40, 0x95, 0x3F, 0xB1, 0x02, 0x85, 0x80, 0x09, 0x28, 0x95, 0x3F, 0xB1, 0x02,
    0x85, 0x81, 0x09, 0x29, 0x95, 0x3F, 0xB1, 0x02, 0x85, 0x82, 0x09, 0x2A, 0x95, 0x09, 0xB1, 0x02,
    0x85, 0x83, 0x09, 0x2B, 0x95, 0x3F, 0xB1, 0x02, 0x85, 0x84, 0x09, 0x2C, 0x95, 0x3F, 0xB1, 0x02,
    0x85, 0x85, 0x09, 0x2D, 0x95, 0x02, 0xB1, 0x02, 0x85, 0xA0, 0x09, 0x2E, 0x95, 0x01, 0xB1, 0x02,
    0x85, 0xE0, 0x09, 0x2F, 0x95, 0x3F, 0xB1, 0x02, 0x85, 0xF0, 0x09, 0x30, 0x95, 0x3F, 0xB1, 0x02,
    0x85, 0xF1, 0x09, 0x31, 0x95, 0x3F, 0xB1, 0x02, 0x85, 0xF2, 0x09, 0x32, 0x95, 0x0F, 0xB1, 0x02,
    0x85, 0xF4, 0x09, 0x35, 0x95, 0x3F, 0xB1, 0x02, 0x85, 0xF5, 0x09, 0x36, 0x95, 0x03, 0xB1, 0x02,
    0xC0,
];

/// Feature 0x05 — calibración de giroscopio/acelerómetro que Steam consulta.
const PS5_CALIBRATION_INFO: [u8; 41] = [
    0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x27, 0xF0, 0xD8, 0x10, 0x27, 0xF0, 0xD8, 0x10,
    0x27, 0xF0, 0xD8, 0xF4, 0x01, 0xF4, 0x01, 0x10, 0x27, 0xF0, 0xD8, 0x10, 0x27, 0xF0, 0xD8, 0x10,
    0x27, 0xF0, 0xD8, 0x0B, 0x00, 0x00, 0x00, 0x00, 0x00,
];

/// Feature 0x09 — info de emparejamiento; el MAC va en los bytes 1…6.
const PS5_PAIRING_INFO: [u8; 20] = [
    0x09, 0x74, 0xE7, 0xD6, 0x3A, 0x53, 0x35, 0x08, 0x25, 0x00, 0x1E, 0x00, 0xEE, 0x74, 0xD0, 0xBC,
    0x00, 0x00, 0x00, 0x00,
];

/// Feature 0x20 — info de firmware (versión de placa).
const PS5_FIRMWARE_INFO: [u8; 64] = [
    0x20, 0x4A, 0x75, 0x6E, 0x20, 0x31, 0x39, 0x20, 0x32, 0x30, 0x32, 0x33, 0x31, 0x34, 0x3A, 0x34,
    0x37, 0x3A, 0x33, 0x34, 0x03, 0x00, 0x44, 0x00, 0x08, 0x02, 0x00, 0x01, 0x36, 0x00, 0x00, 0x01,
    0xC1, 0xC8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x54, 0x01, 0x00, 0x00,
    0x14, 0x00, 0x00, 0x00, 0x0B, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
];

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    fn pressed(bits: &[u16]) -> PadState {
        let mut state = PadState::default();
        for bit in bits {
            state.buttons |= 1 << bit;
        }
        state
    }

    #[test]
    fn dualsense_report_maps_signature_buttons_and_hat() {
        let neutral = encode_input_report(&PadState::default(), 0, 0);
        assert_eq!(0x01, neutral[0]);
        assert_eq!(
            [128, 128, 128, 128],
            [neutral[1], neutral[2], neutral[3], neutral[4]]
        );
        assert_eq!(0x08, neutral[8]); // hat neutro
        assert_eq!(0x00, neutral[9]);
        assert_eq!(0x00, neutral[10]);

        // A/B/X/Y → cruz/círculo/cuadrado/triángulo.
        let face = encode_input_report(&pressed(&[0, 1, 2, 3]), 0, 0);
        assert_eq!(0x08 | 0x10 | 0x20 | 0x40 | 0x80, face[8]);

        // L1/R1, Select/Start, L3/R3.
        let shoulders = encode_input_report(&pressed(&[4, 5, 6, 7, 8, 9]), 0, 0);
        assert_eq!(0x01 | 0x02 | 0x10 | 0x20 | 0x40 | 0x80, shoulders[9]);

        // Home → PS; extra_2 → click del touchpad.
        let extra = encode_input_report(&pressed(&[10, 11]), 0, 0);
        assert_eq!(0x01 | 0x02, extra[10]);
    }

    #[test]
    fn dualsense_report_scales_sticks_and_triggers() {
        let state = PadState {
            stick_lx: -127,
            stick_ly: 127,
            trigger_l2: 200,
            trigger_r2: 255,
            dpad: 2,
            ..PadState::default()
        };
        let report = encode_input_report(&state, 7, 0);
        assert_eq!(0, report[1]);
        assert_eq!(255, report[2]);
        assert_eq!(128, report[3]);
        assert_eq!(200, report[5]);
        assert_eq!(255, report[6]);
        assert_eq!(7, report[7]);
        assert_eq!(0x02, report[8]); // hat E
        assert_eq!(0x0C, report[9] & 0x0C); // L2/R2 digitales
    }

    #[test]
    fn dualsense_report_packs_touch_and_motion() {
        let state = PadState {
            touch: Some((32_768, 32_768)),
            motion: Some(MotionSample {
                timestamp_us: 1,
                accel: [0.0, 0.0, 9.80665],
                gyro: [0.0, 1.0, -1.0],
            }),
            ..PadState::default()
        };
        let report = encode_input_report(&state, 0, 0x1234_5678);
        assert_eq!(0x00, report[33]); // contacto activo (bit alto = inactivo)
        assert_eq!(
            960,
            (u16::from(report[34]) | (u16::from(report[35] & 0x0F) << 8))
        );
        assert_eq!(
            540,
            (u16::from(report[35] >> 4) | (u16::from(report[36]) << 4))
        );
        assert_eq!([0x80, 0, 0, 0], report[37..41]);
        assert_eq!(1145i16, i16::from_le_bytes([report[18], report[19]]));
        assert_eq!(-1145i16, i16::from_le_bytes([report[20], report[21]]));
        assert_eq!(961i16, i16::from_le_bytes([report[26], report[27]]));
        assert_eq!(
            0x1234_5678u32,
            u32::from_le_bytes(report[28..32].try_into().unwrap())
        );
    }

    #[test]
    fn dualsense_feature_reports_match_declared_sizes() {
        let mac = [0x02, 0x11, 0x22, 0x33, 0x44, 0x55];
        let calibration = feature_report(0x05, &mac).expect("calibración");
        assert_eq!(0x05, calibration[0]);
        assert_eq!(41, calibration.len());
        let pairing = feature_report(0x09, &mac).expect("emparejamiento");
        assert_eq!(0x09, pairing[0]);
        assert_eq!(20, pairing.len());
        assert_eq!([0x55, 0x44, 0x33, 0x22, 0x11, 0x02], pairing[1..7]);
        let firmware = feature_report(0x20, &mac).expect("firmware");
        assert_eq!(0x20, firmware[0]);
        assert_eq!(64, firmware.len());
        assert!(feature_report(0x99, &mac).is_none());
    }

    /// TEST-0205 (W10, CR-114): la preferencia `on`/`off` se guarda y se
    /// relee de la carpeta de datos; ausente o desconocida, decide el entorno.
    #[test]
    fn virtual_pad_preference_round_trips_and_falls_back_to_env() {
        let dir = std::env::temp_dir().join(format!(
            "twinpad-virtual-pad-{}-{}",
            std::process::id(),
            line!()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        let path = dir.join(PREFERENCE_FILE);
        assert_eq!(None, read_preference(Some(&path)));
        write_preference(Some(&path), true).expect("guardar on");
        assert_eq!(Some(true), read_preference(Some(&path)));
        write_preference(Some(&path), false).expect("guardar off");
        assert_eq!(Some(false), read_preference(Some(&path)));
        std::fs::write(&path, "otra-cosa\n").expect("valor raro");
        assert_eq!(None, read_preference(Some(&path)));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn uhid_device_is_created_when_kernel_supports_it() {
        if !std::path::Path::new(UHID_PATH).exists() {
            return; // sin uhid en este host: no hay nada que verificar
        }
        match VirtualPad::start() {
            Ok(pad) => {
                assert!(
                    hidraw_registered(),
                    "el kernel no publicó el hidraw del mando virtual"
                );
                pad.update(&InputMsg::InputReport {
                    seq: 1,
                    stick_lx: 0,
                    stick_ly: 0,
                    stick_rx: 0,
                    stick_ry: 0,
                    dpad: 8,
                    buttons: 1,
                    trigger_l2: 0,
                    trigger_r2: 0,
                    motion: None,
                    touch_x: 0,
                    touch_y: 0,
                    touch_pressed: false,
                });
                pad.release_all();
            }
            // Sin permiso sobre /dev/uhid (CI sin el módulo) no es un fallo del codec.
            Err(error) if error.kind() == std::io::ErrorKind::PermissionDenied => {}
            Err(error) => panic!("no se pudo crear el mando virtual: {error}"),
        }
    }

    /// El kernel registra el HID como `/sys/class/hidraw/hidrawN`.
    fn hidraw_registered() -> bool {
        for _ in 0..20 {
            if let Ok(entries) = std::fs::read_dir("/sys/class/hidraw") {
                for entry in entries.flatten() {
                    let uevent = entry.path().join("device/uevent");
                    if let Ok(text) = std::fs::read_to_string(uevent) {
                        if text.contains("Twinpad DualSense") && text.contains("054C") {
                            return true;
                        }
                    }
                }
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        false
    }
}
