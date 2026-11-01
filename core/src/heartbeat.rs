//! Política pura del heartbeat de sesión — SPEC-001 §4.3, CR-055 (DES-001 §3).
//!
//! Sin I/O: recibe el reloj y los contadores por parámetro (ENG §5 P11).
//! Los timeouts y el envío de pings los aplican los adaptadores
//! (`server/heartbeat.rs` y el lector Android). La expiración no produce
//! errores nuevos (SPEC-001 §14/R5): el `SessionEnded` observable es
//! exactamente el de un corte de conexión (§4.3 HB4–HB6).

/// Cadencia del `ping` del Servidor (§4.3 HB1).
pub const HEARTBEAT_INTERVAL_MS: u64 = 5_000;

/// Ventana sin `pong` válido que declara la sesión caída (§4.3 HB4/HB5).
pub const HEARTBEAT_DEADLINE_MS: u64 = 15_000;

/// Frontera del deadline (§4.3 HB4): exactamente 15 s sigue vivo, a los
/// 15 001 ms vence (paridad con el patrón de §9 aplicado a `proof`).
///
/// Lado Servidor: aceptación de `pong` (HB3) y vida de la ligadura de S3.
/// `now_ms` es un contador monótono del adaptador (ms desde el inicio de
/// la sesión, p. ej. `Instant::elapsed`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PongLiveness {
    /// Último `pong` aceptado (estricamente mayor requerido, HB3); `None`
    /// hasta el primer eco (los pings empiezan en `t=0`).
    last_accepted_t: Option<u64>,
    /// Momento del último `pong` aceptado (ms desde el origen del reloj).
    accepted_at_ms: u64,
    /// Ventana configurable (tests usan valores cortos; producción 15 s).
    deadline_ms: u64,
}

impl PongLiveness {
    /// S3 recién alcanzado: la vida arranca en el `pair_ack` y el primer
    /// `pong` válido la refresca (§7 paso 12).
    pub fn new(now_ms: u64) -> Self {
        Self::with_deadline(HEARTBEAT_DEADLINE_MS, now_ms)
    }

    /// Variante con deadline inyectado (ENG §5 P11: tests acotan el reloj).
    pub fn with_deadline(deadline_ms: u64, now_ms: u64) -> Self {
        Self {
            last_accepted_t: None,
            accepted_at_ms: now_ms,
            deadline_ms,
        }
    }

    /// HB3: acepta el eco solo si `t` es nuevo (estrictamente mayor que el
    /// último aceptado) y no adelantado (≤ último `ping` enviado).
    /// Un `pong` inválido no refresca la vida y no cierra la sesión.
    /// Un `pong` tardío sobre un deadline ya vencido tampoco (HB4).
    pub fn accept(&mut self, t: u64, last_sent_t: u64, now_ms: u64) -> bool {
        if self.is_expired(now_ms) {
            return false;
        }
        let fresh = self.last_accepted_t.is_none_or(|last| t > last) && t <= last_sent_t;
        if fresh {
            self.last_accepted_t = Some(t);
            self.accepted_at_ms = now_ms;
            true
        } else {
            false
        }
    }

    /// HB4: sin `pong` válido durante más del deadline la sesión murió.
    pub fn is_expired(&self, now_ms: u64) -> bool {
        now_ms.saturating_sub(self.accepted_at_ms) > self.deadline_ms
    }

    /// Milisegundos restantes hasta el deadline (> 0 mientras viva;
    /// 0 solo tras la expiración).
    pub fn remaining_ms(&self, now_ms: u64) -> u64 {
        if self.is_expired(now_ms) {
            0
        } else {
            (self.deadline_ms - now_ms.saturating_sub(self.accepted_at_ms)).max(1)
        }
    }

    /// Último `t` aceptado (0 antes del primer eco; diagnóstico/tests).
    pub fn last_accepted_t(&self) -> u64 {
        self.last_accepted_t.unwrap_or(0)
    }
}

/// Tiempo ficticio para los tests (el reloj entra por parámetro en producción).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    // TEST-0120 — verifies SPEC-001 (§4.3 HB1–HB3: eco válido y validación de `t`).
    #[test]
    fn accepts_echo_of_latest_ping_within_sent_window() {
        let mut liveness = PongLiveness::new(0);
        let last_sent = 5; // pings t=0..5 enviados por el adaptador
        // El eco del último ping es válido.
        assert!(liveness.accept(5, last_sent, 200));
        assert_eq!(liveness.last_accepted_t(), 5);
        // Eco repetido (HB3: `t` estrictamente mayor exigido).
        assert!(!liveness.accept(5, last_sent, 300));
        // Eco adelantado: mayor que el último ping enviado.
        assert!(!liveness.accept(9, last_sent, 300));
        // Eco viejo.
        assert!(!liveness.accept(3, last_sent, 300));
    }

    // TEST-0120 — verifies SPEC-001 (§4.3 HB4: frontera exacta del deadline).
    #[test]
    fn expiry_boundary_15s_is_fresh_and_15001ms_is_expired() {
        let mut liveness = PongLiveness::with_deadline(15_000, 0);
        assert!(liveness.accept(0, 0, 1_000));
        // Exactamente 15 s desde el último pong válido: aún vivo.
        assert!(!liveness.is_expired(1_000 + 15_000));
        assert_eq!(liveness.remaining_ms(1_000 + 15_000), 1);
        // Un milisegundo más: vencido (HB4).
        assert!(liveness.is_expired(1_000 + 15_001));
        assert_eq!(liveness.remaining_ms(1_000 + 15_001), 0);
        // Un pong tardío no revive la sesión (HB4).
        assert!(!liveness.accept(6, 6, 1_000 + 15_002));
    }

    // TEST-0120 — verifies SPEC-001 (§4.3 HB3: ping válido refresca la ventana).
    #[test]
    fn accepted_pong_refreshes_deadline_window() {
        let mut liveness = PongLiveness::with_deadline(15_000, 0);
        assert!(liveness.accept(0, 0, 10_000));
        // 10 s tras el pong aceptado: vivo (hubiera vencido sin el eco).
        assert!(!liveness.is_expired(20_000));
        assert!(liveness.remaining_ms(20_000) > 0);
    }
}
