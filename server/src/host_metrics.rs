//! CR-076 v2: medición del HOST para los gauges de la ventana de espera
//! del teléfono (CPU/GPU/RAM/FPS de la PC que corre el server).
//!
//! - CPU: delta de `/proc/stat` (busy/total entre dos lecturas); el primer
//!   muestreo solo instala el previo y no emite (sin delta no hay %).
//! - RAM: `/proc/meminfo` (`MemTotal` − `MemAvailable` como uso).
//! - GPU: busy del driver si el host lo expone por sysfs; `None` si no
//!   (el gauge queda «—», sin datos inventados).
//! - FPS: tasa de publicación del juego seleccionado (Δgfn/Δs del Plugin
//!   vivo), consultada en cada tick del emisor.
//!
//! La emisión va por la sesión NDJSON (SPEC-001 §4.2) como `host_metrics`
//! de `core::game`, atómica bajo el mismo lock de línea que ping/video.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::games::GameRegistry;

/// Techo de porcentajes del wire (`HOST_METRICS_PERCENT_MAX` de core).
const PERCENT_MAX: u8 = 100;

/// FPS máximo interpretable del contador `gfn` (publicaciones del Plugin):
/// tasas absurdas, gfn igual o retrocedido no se reportan como fps.
const FPS_MAX: f32 = 240.0;

/// kB por MB al pasar de `/proc/meminfo` (valores en kB según Linux).
const KIB_PER_MIB: f64 = 1024.0;

/// Bytes por MiB para la lectura de VRAM DRM (CR-086).
const BYTES_PER_MIB: u64 = 1024 * 1024;

/// Temperaturas del hwmon en mºC (miligrados por grado, CR-085).
const MILLI_C_PER_C: f64 = 1000.0;

/// Muestra de consumo del host ya validada (CR-076 v2).
#[derive(Clone, PartialEq, Debug)]
pub struct HostMetrics {
    pub cpu_percent: u8,
    pub gpu_percent: Option<u8>,
    pub ram_used_mb: u32,
    pub ram_total_mb: u32,
    pub game_fps: Option<f32>,
    /// °C enteros del chip de CPU del hwmon (CR-085); `None` sin sensor.
    pub cpu_temp_c: Option<u8>,
    /// °C enteros del chip de GPU del hwmon (CR-085); `None` sin sensor.
    pub gpu_temp_c: Option<u8>,
    /// VRAM usada/total del host en MiB; ambos `None` si DRM no expone
    /// el par o si la lectura es incoherente (CR-086).
    pub vram_used_mb: Option<u32>,
    pub vram_total_mb: Option<u32>,
}

/// Clase de chip hwmon para clasificar temperaturas (CR-085).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum HwmonChipClass {
    /// Chip de CPU (`k10temp`/`zenpower` AMD, `coretemp` Intel).
    Cpu,
    /// Chip de GPU (`amdgpu`, y por si el hardware cambia `nvidia`/
    /// `nouveau`) — dueño de la temperatura de GPU.
    Gpu,
}

/// Clasificación del chip hwmon por su `name`; `None` sin clase (chip
/// ajeno: nvme, acpitz… no alimentan los gauges).
pub fn hwmon_chip_class(name: &str) -> Option<HwmonChipClass> {
    match name.trim() {
        "k10temp" | "zenpower" | "coretemp" => Some(HwmonChipClass::Cpu),
        "amdgpu" | "nouveau" | "nvidia" => Some(HwmonChipClass::Gpu),
        _ => None,
    }
}

/// Parseo de un `tempN_input` en mºC («58000») → °C entero redondeado;
/// `None` con ruido o valor fuera de 0..255.
pub fn parse_temp_input(text: &str) -> Option<u8> {
    let milli = text.trim().parse::<f64>().ok()?;
    let celsius = (milli / MILLI_C_PER_C).round();
    (0.0..=f64::from(u8::MAX))
        .contains(&celsius)
        .then_some(celsius as u8)
}

/// Convierte el par de contadores DRM de bytes a MiB redondeados; requiere
/// total positivo y uso ≤ total. Un resultado por encima de u32 no se emite.
fn parse_vram_bytes(used_text: &str, total_text: &str) -> Option<(u32, u32)> {
    fn mib(text: &str) -> Option<u32> {
        let bytes = text.trim().parse::<u64>().ok()?;
        let whole = bytes / BYTES_PER_MIB;
        let rounded = whole + u64::from(bytes % BYTES_PER_MIB >= BYTES_PER_MIB / 2);
        u32::try_from(rounded).ok()
    }

    let used_bytes = used_text.trim().parse::<u64>().ok()?;
    let total_bytes = total_text.trim().parse::<u64>().ok()?;
    if total_bytes == 0 || used_bytes > total_bytes {
        return None;
    }
    let used_mb = mib(used_text)?;
    let total_mb = mib(total_text)?;
    (total_mb > 0 && used_mb <= total_mb).then_some((used_mb, total_mb))
}

/// Parseo de la primera línea de `/proc/stat` («cpu u n i iow …»):
/// `(busy, total)` en jiffies; `None` si no parsea.
pub fn parse_cpu_stat(line: &str) -> Option<(u64, u64)> {
    let mut fields = line.split_whitespace();
    if fields.next()? != "cpu" {
        return None;
    }
    let counts: Vec<u64> = fields.map_while(|token| token.parse().ok()).collect();
    if counts.len() < 5 {
        return None;
    }
    let idle = counts[3] + counts[4];
    let total: u64 = counts.iter().sum();
    if total == 0 {
        return None;
    }
    Some((total - idle, total))
}

/// Parseo de `/proc/meminfo`: `(used_mb, total_mb)` con
/// `used = MemTotal − MemAvailable`; `None` si faltan las claves.
pub fn parse_meminfo(text: &str) -> Option<(u32, u32)> {
    let field = |prefix: &str| {
        text.lines()
            .find(|line| line.starts_with(prefix))
            .and_then(|line| line.split_whitespace().nth(1))
            .and_then(|value| value.parse::<f64>().ok())
    };
    let total_kib = field("MemTotal:")?;
    let available_kib = field("MemAvailable:")?;
    if total_kib <= 0.0 {
        return None;
    }
    let used_kib = (total_kib - available_kib).max(0.0);
    let total_mb = (total_kib / KIB_PER_MIB).round().max(0.0) as u32;
    let used_mb = (used_kib / KIB_PER_MIB).round().max(0.0) as u32;
    Some((used_mb, total_mb))
}

/// Parseo del busy del driver GPU; acepta enteros nativos de sysfs DRM
/// y formas «37 %»/«37%»; `None` con ruido, negativos o fuera de 0..100.
pub fn parse_gpu_busy(line: &str) -> Option<u8> {
    let token = line.trim_start_matches([' ', '\t']);
    let digits: String = token.chars().take_while(|c| c.is_ascii_digit()).collect();
    let percent = digits.parse::<u8>().ok()?;
    let suffix = token[digits.len()..].trim();
    if percent > PERCENT_MAX || !(suffix.is_empty() || suffix == "%") {
        return None;
    }
    Some(percent)
}

/// Rutas sysfs conocidas del busy de GPU del host (fallback fijo tras el
/// escaneo de tarjetas; CR-085 v2).
const GPU_BUSY_PATHS: [&str; 2] = [
    "/sys/class/drm/card0/device/gpu_busy_percent",
    "/sys/kernel/gpu/gpu_busy",
];

/// Rutas del par de contadores VRAM DRM por tarjeta.
fn scan_vram_paths(drm_root: &str) -> Option<Vec<(String, String)>> {
    let cards = std::fs::read_dir(drm_root).ok()?;
    let pairs: Vec<_> = cards
        .flatten()
        .filter(|card| card.file_name().to_string_lossy().starts_with("card"))
        .filter_map(|card| {
            let device = card.path().join("device");
            let used = device.join("mem_info_vram_used");
            let total = device.join("mem_info_vram_total");
            (used.is_file() && total.is_file()).then(|| {
                (
                    used.to_string_lossy().to_string(),
                    total.to_string_lossy().to_string(),
                )
            })
        })
        .collect();
    (!pairs.is_empty()).then_some(pairs)
}

fn read_vram_from_paths(paths: &[(String, String)]) -> Option<(u32, u32)> {
    paths.iter().find_map(|(used_path, total_path)| {
        let used = std::fs::read_to_string(used_path).ok()?;
        let total = std::fs::read_to_string(total_path).ok()?;
        parse_vram_bytes(&used, &total)
    })
}

fn read_vram() -> Option<(u32, u32)> {
    let paths = scan_vram_paths("/sys/class/drm")?;
    read_vram_from_paths(&paths)
}

fn read_cpu_stat() -> Option<(u64, u64)> {
    let stat = std::fs::read_to_string("/proc/stat").ok()?;
    let first = stat.lines().next()?.to_owned();
    parse_cpu_stat(&first)
}

fn cpu_percent(prev: (u64, u64), now: (u64, u64)) -> Option<u8> {
    let d_total = now.1.checked_sub(prev.1)?;
    let d_busy = now.0.checked_sub(prev.0)?.min(d_total);
    if d_total == 0 {
        return None;
    }
    Some(((d_busy * u64::from(PERCENT_MAX)) / d_total) as u8)
}

/// Escaneo de `gpu_busy_percent` en TODAS las tarjetas del drm_root
/// (CR-085 v2): el servidor puede correr con la GPU del stream como
/// `card1`/`card2` — el busy fijo de `card0` dejaba la GPU «—» en banco.
/// Devuelve las rutas existentes en orden de tarjeta leída.
pub fn scan_gpu_busy_paths(drm_root: &str) -> Option<Vec<String>> {
    const BUSY_FILE: &str = "device/gpu_busy_percent";
    let cards = std::fs::read_dir(drm_root).ok()?;
    let mut paths = Vec::new();
    for card in cards.flatten() {
        let name = card.file_name();
        if !name.to_string_lossy().starts_with("card") {
            continue;
        }
        let busy = card.path().join(BUSY_FILE);
        if busy.is_file() {
            paths.push(busy.to_string_lossy().to_string());
        }
    }
    paths.retain(|path| std::fs::read_to_string(path).is_ok());
    (!paths.is_empty()).then_some(paths)
}

fn read_gpu_busy_from_paths(scanned: Vec<String>, fallback_paths: &[&str]) -> Option<u8> {
    scanned
        .into_iter()
        .chain(fallback_paths.iter().map(|path| (*path).to_owned()))
        .find_map(|path| {
            std::fs::read_to_string(path)
                .ok()
                .and_then(|text| parse_gpu_busy(&text))
        })
}

fn read_gpu_busy() -> Option<u8> {
    let scanned = scan_gpu_busy_paths("/sys/class/drm").unwrap_or_default();
    read_gpu_busy_from_paths(scanned, &GPU_BUSY_PATHS)
}

/// Lecturas del hwmon del host ya agregadas por chip (CR-085).
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
struct HwmonReadings {
    cpu_temp_c: Option<u8>,
    gpu_temp_c: Option<u8>,
}

/// Lecturas térmicas del hwmon del host (CR-085): recorre `/sys/class/hwmon`,
/// clasifica los chips conocidos y agrega el máximo de sus `temp*_input`.
/// Sin hwmon, las lecturas quedan en `None` (sin inventar valores).
fn read_hwmon() -> HwmonReadings {
    let Ok(chips) = std::fs::read_dir(HWMON_ROOT) else {
        return HwmonReadings::default();
    };
    let mut readings = HwmonReadings::default();
    for chip in chips.flatten() {
        let class = std::fs::read_to_string(chip.path().join("name"))
            .ok()
            .and_then(|name| hwmon_chip_class(&name));
        let Some(class) = class else { continue };
        let Ok(sensors) = std::fs::read_dir(chip.path()) else {
            continue;
        };
        for sensor in sensors.flatten() {
            let name = sensor.file_name();
            let name = name.to_string_lossy();
            if !name.starts_with("temp") || !name.ends_with("_input") {
                continue;
            }
            let Ok(text) = std::fs::read_to_string(sensor.path()) else {
                continue;
            };
            if let Some(celsius) = parse_temp_input(&text) {
                let slot = match class {
                    HwmonChipClass::Cpu => &mut readings.cpu_temp_c,
                    HwmonChipClass::Gpu => &mut readings.gpu_temp_c,
                };
                *slot = Some(slot.map_or(celsius, |prev| prev.max(celsius)));
            }
        }
    }
    readings
}

/// Raíz del hwmon del host (CR-085).
const HWMON_ROOT: &str = "/sys/class/hwmon";

/// Muestreos del host con el previo de CPU a mano (CR-076 v2).
pub struct HostMetricsSampler {
    prev: Option<(u64, u64)>,
}

impl HostMetricsSampler {
    pub fn new() -> Self {
        Self { prev: None }
    }

    /// Muestrea el host ahora; `None` en el primer muestreo (el delta
    /// de CPU necesita el previo) o si `/proc/meminfo` no está.
    ///
    /// CR-078: el previo de CPU se instala ANTES de evaluar el delta —
    /// la versión anterior salía por `?` con `prev == None` sin guardar
    /// `now` (el previo jamás se instalaba y el emisor corría sin emitir
    /// nunca: medido en banco, wchar del proceso solo crecía con pings).
    pub fn sample(&mut self) -> Option<HostMetrics> {
        let now = read_cpu_stat()?;
        let cpu_percent = self.prev.and_then(|prev| cpu_percent(prev, now));
        self.prev = Some(now);
        let meminfo = std::fs::read_to_string("/proc/meminfo").ok()?;
        let (ram_used_mb, ram_total_mb) = parse_meminfo(&meminfo)?;
        cpu_percent.map(|cpu_percent| {
            let hwmon = read_hwmon();
            let vram = read_vram();
            HostMetrics {
                cpu_percent,
                gpu_percent: read_gpu_busy(),
                ram_used_mb,
                ram_total_mb,
                game_fps: None, // el emisor rellena el fps del juego.
                cpu_temp_c: hwmon.cpu_temp_c,
                gpu_temp_c: hwmon.gpu_temp_c,
                vram_used_mb: vram.map(|(used, _)| used),
                vram_total_mb: vram.map(|(_, total)| total),
            }
        })
    }
}

impl Default for HostMetricsSampler {
    fn default() -> Self {
        Self::new()
    }
}

/// FPS de publicación del Plugin: `Δgfn/Δs` entre observaciones;
/// `None` sin juego, sin frames, con gfn retrocedido (reinicio de la
/// conexión) o con tasa sobre el techo
pub fn fps_from_gfn(
    prev: Option<(u64, u64)>,
    now: Option<(u64, u64)>,
    elapsed_s: f32,
) -> Option<f32> {
    let (Some((prev_gfn, _)), Some((now_gfn, _))) = (prev, now) else {
        return None;
    };
    let delta = now_gfn.checked_sub(prev_gfn)?;
    if elapsed_s <= 0.0 {
        return None;
    }
    let fps = delta as f32 / elapsed_s;
    (delta > 0 && fps <= FPS_MAX).then_some(fps)
}

/// Intervalo de producción (2 s: los gauges son diagnóstico, no telemetría).
pub fn production_metrics_interval() -> Duration {
    Duration::from_secs(2)
}

/// Unit atómica de escritura de línea NDJSON (patrón heartbeat CR-055).
fn write_all_line(socket: &mut std::net::TcpStream, line: &str) -> bool {
    use std::io::Write;
    let payload = format!("{line}\n");
    socket.write_all(payload.as_bytes()).is_ok()
}

/// Línea canónica `host_metrics` de la muestra; `None` no puede pasar aquí
/// con un `GameMsg` correcto (encode solo falla ante futuras reglas del
/// catálogo).
pub fn host_metrics_line(sample: &HostMetrics) -> Option<String> {
    twinpad_core::game::GameMsg::HostMetrics {
        cpu_percent: sample.cpu_percent,
        gpu_percent: sample.gpu_percent,
        ram_used_mb: sample.ram_used_mb,
        ram_total_mb: sample.ram_total_mb,
        game_fps: sample.game_fps,
        cpu_temp_c: sample.cpu_temp_c,
        gpu_temp_c: sample.gpu_temp_c,
        vram_used_mb: sample.vram_used_mb,
        vram_total_mb: sample.vram_total_mb,
    }
    .encode()
    .ok()
}

/// Emisor por sesión: cada `interval` publica `host_metrics` al teléfono
/// mientras la sesión viva; un fallo de escritura (EOF/HB4) termina el
/// hilo. La unidad de línea va bajo el lock compartido de la sesión.
pub fn spawn_host_metrics_sender(
    session_alive: bool,
    socket: Option<std::net::TcpStream>,
    write_lock: Arc<Mutex<()>>,
    running: Arc<AtomicBool>,
    registry: Arc<GameRegistry>,
    selected_game: Arc<Mutex<Option<String>>>,
    interval: Duration,
) -> Option<std::thread::JoinHandle<()>> {
    let Some(mut socket) = socket.filter(|_| session_alive) else {
        // Diagnóstico explícito: sin sesión no hay emisor (patrón CR-055).
        println!("host_metrics-emisor: no arranca (sin sesión viva/socket).");
        return None;
    };
    Some(std::thread::spawn(move || {
        let mut sampler = HostMetricsSampler::new();
        let mut prev_gfn: Option<(u64, u64)> = None;
        let mut tick_at = Instant::now();
        while running.load(Ordering::Acquire) {
            std::thread::sleep(interval);
            if !running.load(Ordering::Acquire) {
                break;
            }
            let elapsed = tick_at.elapsed().as_secs_f32();
            tick_at = Instant::now();
            // FPS del juego seleccionado (Δgfn del Plugin vivo).
            let game_id = selected_game.lock().ok().and_then(|slot| slot.clone());
            let now_gfn = game_id.as_deref().and_then(|game_id| {
                registry
                    .latest_frame(game_id)
                    .map(|frame| (frame.gfn, frame.ts_capture_us.unwrap_or_default()))
            });
            let game_fps = fps_from_gfn(prev_gfn, now_gfn, elapsed);
            if now_gfn.is_some() {
                prev_gfn = now_gfn;
            }
            let Some(mut sample) = sampler.sample() else {
                continue; // primero instala el previo; luego emite.
            };
            sample.game_fps = game_fps;
            let Some(line) = host_metrics_line(&sample) else {
                println!("host_metrics-emisor: encode falló; emisor termina.");
                break;
            };
            let written = {
                let _guard = write_lock.lock().ok();
                write_all_line(&mut socket, &line)
            };
            if !written {
                println!("host_metrics-emisor: FALLO de escritura; emisor termina.");
                break;
            }
        }
    }))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    // TEST-0157 (server): parsers puros del HOST — CPU, RAM, GPU y fps.
    #[test]
    fn cpu_stat_and_meminfo_parse_host_values() {
        assert_eq!(
            parse_cpu_stat("cpu 1000 0 1000 7000 1000 0 1000 0 0 0"),
            Some((3_000, 11_000))
        );
        assert_eq!(parse_cpu_stat("cpu2 1 2 3 4 5"), None);
        assert_eq!(parse_cpu_stat("cpu 1 2 3"), None);

        let meminfo = "MemTotal:       16106120 kB\nMemAvailable:    5242880 kB\nSwapTotal: 0";
        assert_eq!(parse_meminfo(meminfo), Some((10_609, 15_729)));
        assert_eq!(parse_meminfo("MemAvailable: 1 kB"), None);
    }

    #[test]
    fn gpu_busy_and_fps_follow_the_wire_contract() {
        assert_eq!(parse_gpu_busy("37 %"), Some(37));
        assert_eq!(parse_gpu_busy("100%"), Some(100));
        // gpu_busy_percent de DRM publica un entero sin sufijo.
        assert_eq!(parse_gpu_busy("37\n"), Some(37));
        assert_eq!(parse_gpu_busy("100"), Some(100));
        assert_eq!(parse_gpu_busy("37 percent"), None);
        assert_eq!(parse_gpu_busy("-5 %"), None);
        assert_eq!(parse_gpu_busy("137 %"), None);
        assert_eq!(parse_gpu_busy("busy"), None);

        assert_eq!(
            fps_from_gfn(Some((100, 0)), Some((160, 0)), 1.0),
            Some(60.0)
        );
        assert_eq!(fps_from_gfn(Some((100, 0)), Some((100, 0)), 2.0), None);
        assert_eq!(fps_from_gfn(None, Some((160, 0)), 1.0), None);
        assert_eq!(fps_from_gfn(Some((160, 0)), Some((100, 0)), 1.0), None);
        assert_eq!(fps_from_gfn(Some((0, 0)), Some((2_400, 0)), 1.0), None);
    }

    #[test]
    fn sampler_needs_two_cpu_ticks_and_no_negative_ram() {
        let mut sampler = HostMetricsSampler::new();
        // Primer tick: instala el previo (sin %, no emite).
        assert!(sampler.sample().is_none());
        // Segundo tick: emite con delta de CPU y RAM del host (en Linux);
        // tolera entornos sin /proc (CI no-Linux) sin fallar una vez viste
        // el primero (regla: ambas lecturas provienen del mismo FS).
        if let Some(second) = sampler.sample() {
            assert!(second.cpu_percent <= 100);
            assert!(second.ram_total_mb > 0);
            assert!(second.ram_total_mb >= second.ram_used_mb);
        }
    }

    // TEST-0175 (server, CR-085): clasificación de chips y parser de
    // temperaturas hwmon; TEST-0181 (CR-086) cubre la lectura VRAM.
    #[test]
    fn hwmon_chip_and_temperature_parsers_follow_the_wire_contract() {
        assert_eq!(hwmon_chip_class("amdgpu"), Some(HwmonChipClass::Gpu));
        assert_eq!(hwmon_chip_class("k10temp"), Some(HwmonChipClass::Cpu));
        assert_eq!(hwmon_chip_class("coretemp\n"), Some(HwmonChipClass::Cpu));
        assert_eq!(hwmon_chip_class("nvme"), None);

        assert_eq!(parse_temp_input("58000"), Some(58));
        assert_eq!(parse_temp_input(" 45500\n"), Some(46));
        assert_eq!(parse_temp_input("-1000"), None);
        assert_eq!(parse_temp_input("999999"), None);
        assert_eq!(parse_temp_input("ruido"), None);
    }

    // TEST-0175 (CR-085): lectura térmica REAL del hwmon, tolerante — un
    // host sin sensores cae en `None` sin entrar en pánico (las guardas de
    // rango viven en `parse_temp_input`). TEST-0181 (CR-086) verifica el
    // escaneo VRAM con un fixture DRM temporal.
    #[cfg(target_os = "linux")]
    #[test]
    fn hwmon_readings_tolerate_hosts_without_sensors() {
        // Solo verifica que el escaneo no entre en pánico; los valores son
        // del host real y pueden ser `None` (CI sin hwmon).
        let _readings = read_hwmon();
    }

    // TEST-0178 (server, CR-085 v2): busy de GPU en CUALQUIER tarjeta —
    // el escaneo del árbol drm encuentra las rutas legibles de
    // `gpu_busy_percent` de cada tarjeta (ágil con la GPU del stream que
    // no siempre es card0) y las prioriza sobre fallbacks fijos para no
    // elegir una GPU distinta cuando card0 también expone busy.
    #[test]
    fn gpu_busy_scan_finds_busy_on_any_card() {
        let root =
            std::env::temp_dir().join(format!("twinpad_gpu_scan_test_{}", std::process::id()));
        assert!(std::fs::create_dir_all(root.join("card0/device")).is_ok());
        assert!(std::fs::create_dir_all(root.join("card1/device")).is_ok());
        assert!(std::fs::write(root.join("card0/device/otro"), "ruido").is_ok());
        assert!(std::fs::create_dir_all(root.join("cardN/device")).is_ok());
        // El sysfs DRM real devuelve el entero sin un sufijo de porcentaje.
        assert!(std::fs::write(root.join("cardN/device/gpu_busy_percent"), "37\n").is_ok());
        assert!(std::fs::write(root.join("fallback_busy"), "12 %\n").is_ok());
        let paths = scan_gpu_busy_paths(root.to_str().unwrap());
        assert_eq!(
            paths,
            Some(Vec::from([format!(
                "{}/cardN/device/gpu_busy_percent",
                root.to_str().unwrap(),
            )]))
        );
        assert_eq!(
            parse_gpu_busy(&std::fs::read_to_string(&paths.as_ref().unwrap()[0]).unwrap()),
            Some(37)
        );
        assert_eq!(
            read_gpu_busy_from_paths(
                paths.unwrap(),
                &[root.join("fallback_busy").to_str().unwrap()],
            ),
            Some(37),
            "la tarjeta DRM detectada tiene prioridad sobre el fallback genérico",
        );
        std::fs::remove_dir_all(&root).ok();
    }

    // TEST-0181 — CR-086: par VRAM DRM completo, lectura de cualquier tarjeta,
    // conversión a MiB y ausencia cuando falta un contador.
    #[test]
    fn vram_scan_reads_and_validates_drm_counter_pair() {
        let root = std::env::temp_dir().join(format!("twinpad_vram_test_{}", std::process::id()));
        let device = root.join("card1/device");
        assert!(std::fs::create_dir_all(&device).is_ok());
        assert!(std::fs::write(device.join("mem_info_vram_used"), "487698432\n").is_ok());
        assert!(std::fs::write(device.join("mem_info_vram_total"), "536870912\n").is_ok());

        assert_eq!(
            parse_vram_bytes("487698432\n", "536870912\n"),
            Some((465, 512))
        );
        assert_eq!(parse_vram_bytes("536870913", "536870912"), None);
        assert_eq!(parse_vram_bytes("0", "0"), None);
        assert_eq!(parse_vram_bytes("invalid", "10"), None);

        let paths = scan_vram_paths(root.to_str().unwrap()).unwrap();
        assert_eq!(read_vram_from_paths(&paths), Some((465, 512)));

        std::fs::remove_file(device.join("mem_info_vram_total")).ok();
        assert!(scan_vram_paths(root.to_str().unwrap()).is_none());
        std::fs::remove_dir_all(&root).ok();
    }

    // TEST-0159 (server, CR-078): el sampler EMITE en el segundo tick —
    // regresión del defecto bancario del cual el pre-EL previo de CPU se
    // instalaba tras el `?` del delta (el emisor corría sin emitir nunca).
    #[cfg(target_os = "linux")]
    #[test]
    fn sampler_emits_from_the_second_tick() {
        let mut sampler = HostMetricsSampler::new();
        assert!(sampler.sample().is_none(), "primer tick: instala previo");
        // Jiffies nuevos: con HZ≥100, 300 ms dan ≥30 ticks de CPU agregada
        // (un delta total de 0 es legítimo del wire y el emisor lo salta).
        std::thread::sleep(std::time::Duration::from_millis(300));
        let sample = sampler.sample();
        assert!(sample.is_some(), "segundo tick: emite (CR-078)");
        if let Some(second) = sample {
            assert!(second.cpu_percent <= 100);
            assert!(second.ram_total_mb > 0);
            assert!(second.ram_total_mb >= second.ram_used_mb);
        }
        // Tercer tick emite también (jiffies nuevos, como arriba).
        std::thread::sleep(std::time::Duration::from_millis(300));
        assert!(sampler.sample().is_some(), "tercer tick: emite");
    }
}
