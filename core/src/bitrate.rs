//! Degradación automática de bitrate por congestión — SPEC-003 §4.3/§8.4
//! (CR-058, regla R10).
//!
//! Política pura: recibe observaciones del relay de media (AUs publicadas
//! vs. descartadas por latest-frame-wins) y una ventana móvil; decide si hay
//! congestión y mueve la escalera del encoder. Umbrales §4.3:
//!
//! - Ventana móvil de 2 000 ms por sesión: `P` = AUs observadas, `D` = AUs
//!   descartadas (reemplazadas sin escribirse al socket).
//! - Congestionada si `D/P ≥ 0,25` y `P ≥ 10` en la ventana.
//! - Sale de congestión si `D/P < 0,05` durante 10 000 ms continuados.
//! - Escalera: `[config → 1/2 → 1/4 → 1/8 → suelo 500 kbps]`; ≥ 2 ventanas
//!   congestionadas consecutivas bajan un rung; 10 s sin congestión suben
//!   un rung (nunca por encima del bitrate del usuario). Todo cambio exige
//!   IDR.

/// Duración de la ventana móvil de observación (ms).
pub const WINDOW_MS: u64 = 2_000;
/// Mínimo de AUs observadas en la ventana para decidir (evita falsos positivos).
pub const WINDOW_MIN_PUBLISHED: u64 = 10;
/// Fracción de descarte que declara congestión (D/P).
pub const CONGESTED_RATIO: f64 = 0.25;
/// Fracción de descarte que permite iniciar la recuperación (D/P).
pub const CLEAR_RATIO: f64 = 0.05;
/// Sin congestión continua requerida para subir un rung (ms).
pub const CLEAR_HOLD_MS: u64 = 10_000;
/// Ventanas congestionadas consecutivas antes de bajar de rung (§4.3).
pub const CONGESTED_WINDOWS_TO_DROP: u32 = 2;
/// Suelo de la escalera (kbps).
pub const LADDER_FLOOR_KBPS: u32 = 500;
/// Run escalada/desescalada.
pub const LADDER_STEPS: u32 = 3; // /2, /4, /8

/// Observación de un tramo de ventana del relay (CR-058 §4.3).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WindowStats {
    pub published: u64,
    pub discarded: u64,
}

impl WindowStats {
    /// Ratio de descarte de la ventana (0.0 si no hay AUs).
    pub fn ratio(&self) -> f64 {
        if self.published == 0 {
            return 0.0;
        }
        self.discarded as f64 / self.published as f64
    }

    /// ¿Ventana congestionada según §4.3? (umbral + muestras mínimas).
    pub fn is_congested(&self) -> bool {
        self.published >= WINDOW_MIN_PUBLISHED && self.ratio() >= CONGESTED_RATIO
    }

    /// ¿Ventana limpia (candidata a acumular el hold de recuperación)?
    pub fn is_clear(&self) -> bool {
        self.published >= WINDOW_MIN_PUBLISHED && self.ratio() < CLEAR_RATIO
    }
}

/// Estado del contador de descartes del relay de una sesión (lado server).
/// El server lo alimenta con cada reenvío y rollover de ventana; cuando
/// cierra la ventana entrega [WindowStats].
#[derive(Debug)]
pub struct CongestionObserver {
    window_start_ms: u64,
    published: u64,
    discarded: u64,
}

impl CongestionObserver {
    pub fn new(now_ms: u64) -> Self {
        Self {
            window_start_ms: now_ms,
            published: 0,
            discarded: 0,
        }
    }

    /// Registra el paso de `fn` actual al siguiente en el relay: los AUs
    /// intermedios reemplazados en el slot `latest` son descartes (§4.3).
    /// `prev_fn` = None (primer reenvío) solo cuenta 1 publicada.
    pub fn observe(&mut self, prev_fn: Option<u64>, next_fn: u64) {
        self.published += 1;
        if let Some(prev) = prev_fn {
            // fn_ es un contador del plugin; el hueco son AUs publicadas
            // pero sustituidas antes de poder escribirse.
            self.discarded += next_fn.saturating_sub(prev + 1);
        }
    }

    /// Cierra la ventana si venció; devuelve sus estadísticas y la reabre
    /// con la ventana siguiente (el residuo acumulado se conserva en la
    /// nueva para solventar la granularidad del tick del caller).
    pub fn rollover(&mut self, now_ms: u64) -> Option<WindowStats> {
        if now_ms.saturating_sub(self.window_start_ms) < WINDOW_MS {
            return None;
        }
        let stats = WindowStats {
            published: self.published,
            discarded: self.discarded,
        };
        self.window_start_ms = now_ms;
        self.published = 0;
        self.discarded = 0;
        Some(stats)
    }
}

/// Controlador de la escalera de bitrate (§4.3). Un solo encoder
/// compartido ⇒ una instancia en el servidor; con múltiples sesiones el
/// caller agrega (el peor estado gana).
#[derive(Debug)]
pub struct BitrateLadder {
    /// Rung actual: 0 = bitrate del usuario; 1..=LADDER_STEPS divisiones.
    rung: u32,
    /// Ventanas congestionadas consecutivas acumuladas.
    congested_streak: u32,
    /// Inicio del tramo sin congestión continuada (para el histéresis).
    clear_since_ms: Option<u64>,
    /// Cambio pendiente de aplicar (el caller envía video_config + IDR).
    pending_change: bool,
}

impl BitrateLadder {
    pub fn new() -> Self {
        Self {
            rung: 0,
            congested_streak: 0,
            clear_since_ms: None,
            pending_change: false,
        }
    }

    /// Bitrate efectivo actual dado el configurado por el usuario (§8.4).
    pub fn effective_kbps(&self, configured_kbps: u32) -> u32 {
        let mut value = configured_kbps.max(LADDER_FLOOR_KBPS);
        for _ in 0..self.rung {
            let next = (value / 2).max(LADDER_FLOOR_KBPS);
            if next == value {
                break;
            }
            value = next;
        }
        value
    }

    /// Rung actual (0 = sin degradación).
    pub fn rung(&self) -> u32 {
        self.rung
    }

    /// ¿Hay cambio pendiente por aplicar?
    pub fn take_change_pending(&mut self) -> bool {
        std::mem::replace(&mut self.pending_change, false)
    }

    /// Ingesta de una ventana cerrada del relay: mantiene la escalera.
    /// `configured_kbps` es la referencia del usuario (§8.4: nunca superar).
    pub fn on_window(&mut self, stats: WindowStats, now_ms: u64) {
        if stats.is_congested() {
            self.congested_streak += 1;
            self.clear_since_ms = None;
            if self.congested_streak >= CONGESTED_WINDOWS_TO_DROP && self.rung < LADDER_STEPS {
                self.rung += 1;
                self.pending_change = true;
                self.congested_streak = 0;
            }
        } else {
            if stats.is_clear() {
                let hold_start = *self.clear_since_ms.get_or_insert(now_ms);
                if self.rung > 0 && now_ms.saturating_sub(hold_start) >= CLEAR_HOLD_MS {
                    self.rung -= 1;
                    self.pending_change = true;
                    self.clear_since_ms = Some(now_ms); // cada hold sube un solo rung
                }
            } else {
                // Ventana indefinida: reinicia el hold de recuperación.
                self.clear_since_ms = None;
            }
            self.congested_streak = 0;
        }
    }

    /// Clasificación de la ventana para el caller (agregación entre sesiones).
    pub fn last_window_congested(stats: WindowStats) -> bool {
        stats.is_congested()
    }
}

impl Default for BitrateLadder {
    fn default() -> Self {
        Self::new()
    }
}

// ENG §6: unwrap/expect permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    fn stats(published: u64, discarded: u64) -> WindowStats {
        WindowStats { published, discarded }
    }

    // TEST-0131 — CR-058 (SPEC-003 §4.3): política pura de congestión
    // histéresis y límites de la escalera).
    #[test]
    fn congestion_threshold_and_minimum_samples() {
        // D/P = 0.25 exacto y ≥10 muestras → congestionada (frontera).
        assert!(stats(10, 3).is_congested()); // 0.3
        assert!(stats(40, 10).is_congested()); // 0.25
        assert!(!stats(40, 9).is_congested()); // 0.225 < 0.25
        // Menos de 10 AUs no decide aunque el ratio sea alto (§4.3 P ≥ 10).
        assert!(!stats(4, 4).is_congested());
        // Ventana limpia solo por debajo de 0.05.
        assert!(stats(100, 4).is_clear()); // 0.04
        assert!(!stats(100, 5).is_clear()); // 0.05
        assert!(!stats(0, 0).is_clear());
    }

    #[test]
    fn observer_counts_discards_from_fn_gaps_and_rolls_windows() {
        let mut obs = CongestionObserver::new(0);
        obs.observe(None, 10); // primer envío: 1 publicada
        obs.observe(Some(10), 14); // hueco de 3 (11..13) descartadas
        assert!(obs.rollover(1_999).is_none());
        let stats = obs.rollover(2_000).unwrap();
        assert_eq!(stats.published, 2);
        assert_eq!(stats.discarded, 3);
    }

    #[test]
    fn ladder_requires_two_congested_windows_to_drop() {
        let mut ladder = BitrateLadder::new();
        let congested = stats(40, 20); // 0.5
        ladder.on_window(congested, 100);
        assert_eq!(ladder.rung(), 0); // 1 ventana no degrada aún
        ladder.on_window(congested, 3_000);
        assert_eq!(ladder.rung(), 1);
        assert!(ladder.take_change_pending());
        assert_eq!(ladder.effective_kbps(8_000), 4_000); // 1/2
    }

    #[test]
    fn ladder_floors_at_500_kbps_and_never_exceeds_configured() {
        let mut ladder = BitrateLadder::new();
        let congested = stats(40, 30); // 0.75
        // 6 ventanas congestionadas: bajaría hasta el suelo.
        ladder.on_window(congested, 100);
        ladder.on_window(congested, 2_100);
        ladder.on_window(congested, 4_200);
        ladder.on_window(congested, 6_300);
        ladder.on_window(congested, 8_400);
        ladder.on_window(congested, 10_500);
        assert_eq!(ladder.rung(), LADDER_STEPS);
        assert!(ladder.take_change_pending());
        // Suelo 500: 3000 → 1500 → 750 → max(375, 500) = 500; y al
        // recuperar nunca por encima del bitrate configurado.
        let configured = 3_000;
        let effective = ladder.effective_kbps(configured);
        assert_eq!(effective, LADDER_FLOOR_KBPS.max(configured / 8));
        // Recuperar hasta rung 0 con ventanas claras de 10 s cada una.
        let clear = stats(100, 0);
        ladder.on_window(clear, 100_000);
        ladder.on_window(clear, 110_001);
        ladder.on_window(clear, 120_002);
        ladder.on_window(clear, 130_003);
        assert_eq!(ladder.rung(), 0);
        assert_eq!(ladder.effective_kbps(configured), configured);
    }

    #[test]
    fn recovery_uses_clear_hold_of_10s_and_single_rung_per_hold() {
        let mut ladder = BitrateLadder::new();
        // Bajar a rung 2.
        let congested = stats(40, 40);
        ladder.on_window(congested, 0);
        ladder.on_window(congested, 2_000);
        ladder.on_window(congested, 4_000);
        ladder.on_window(congested, 6_000);
        assert_eq!(ladder.rung(), 2);

        let clear = stats(100, 0); // 0.0
        ladder.on_window(clear, 8_000);
        ladder.on_window(clear, 10_000);
        ladder.on_window(clear, 17_999);
        assert_eq!(ladder.rung(), 2); // el hold no venció aún
        ladder.on_window(clear, 18_000); // clear desde 8_000 → 10 s
        assert_eq!(ladder.rung(), 1);
        assert!(ladder.take_change_pending());
        // Un rung por hold: no baja a 0 hasta el siguiente hold (§4.3).
        ladder.on_window(clear, 28_000); // nuevo hold desde 18_000
        assert_eq!(ladder.rung(), 0);
    }

    #[test]
    fn dirty_window_resets_recovery_hold() {
        let mut ladder = BitrateLadder::new();
        ladder.on_window(stats(40, 20), 0);
        ladder.on_window(stats(40, 20), 2_000);
        assert_eq!(ladder.rung(), 1);
        ladder.on_window(stats(100, 4), 4_000); // clear abre el hold
        ladder.on_window(stats(100, 12), 6_000); // 0.12: ventana intermedia, reinicia el hold
        ladder.on_window(stats(100, 4), 8_000);
        ladder.on_window(stats(100, 4), 17_999);
        // El hold se reinició en 8_000 → no puede vencer antes de 18_000.
        assert_eq!(ladder.rung(), 1);
        ladder.on_window(stats(100, 4), 18_000);
        assert_eq!(ladder.rung(), 0);
    }
}
