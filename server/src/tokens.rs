//! Registro de tokens y sesiones del Servidor PC — SPEC-001 R2/R3/R6/R7,
//! DES-001 §5/§7. Owner único del almacén (ARCH §5): en esta iteración,
//! memoria del proceso (la persistencia cifrada es posterior).

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard};

use crate::qr;
use twinpad_core::crypto;
use twinpad_core::handshake::TokenRecord;
use twinpad_core::{SessionId, Token};

/// Toma el lock del almacén; si el mutex está envenenado (pánico en otra
/// hebra), degrada a un almacén vacío: ninguna sesión previa sigue activa
/// y todo intento posterior recibe E3 (SPEC-001 §9) — seguro y sin `expect`.
fn lock_or_reset<'a>(
    mutex: &'a Mutex<HashMap<Token, IssuedToken>>,
) -> MutexGuard<'a, HashMap<Token, IssuedToken>> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Registro activo de un token emitido por el servidor.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IssuedToken {
    pub record: TokenRecord,
    /// QR canónico asociado (para regeneración R7 y visualización).
    pub qr_uri: String,
}

/// Fuente de entropía y reloj inyectadas (DES-001 §11: `core` no hace I/O).
pub trait RandomBytes {
    fn fill(&self, buf: &mut [u8]);
}

/// Reloj del servidor en ms (R3: jamás el reloj del teléfono).
pub trait Clock {
    fn now_ms(&self) -> u64;
}

impl<T: Clock + ?Sized> Clock for &T {
    fn now_ms(&self) -> u64 {
        (**self).now_ms()
    }
}

/// Almacén en memoria de tokens emitidos. Un solo writer: el Servidor PC.
pub struct TokenStore<R: RandomBytes, C: Clock> {
    rng: R,
    clock: C,
    listen_addr: String,
    listen_port: u16,
    inner: Mutex<HashMap<Token, IssuedToken>>,
}

impl<R: RandomBytes, C: Clock> TokenStore<R, C> {
    pub fn new(rng: R, clock: C, listen_addr: String, listen_port: u16) -> Self {
        Self {
            rng,
            clock,
            listen_addr,
            listen_port,
            inner: Mutex::new(HashMap::new()),
        }
    }

    /// Genera un token nuevo con su QR (SPEC-001 §7 paso 1).
    pub fn issue(&self) -> IssuedToken {
        let mut bytes = [0u8; 32];
        self.rng.fill(&mut bytes);
        let token = Token(bytes);
        let issued = IssuedToken {
            record: TokenRecord {
                token,
                issued_at_ms: self.clock.now_ms(),
                consumed: false,
            },
            qr_uri: qr::format_uri(&self.listen_addr, self.listen_port, &token),
        };
        lock_or_reset(&self.inner).insert(token, issued.clone());
        issued
    }

    /// R7: si el QR vigente expiró sin consumirse, lo reemplaza por uno
    /// nuevo automáticamente. Devuelve el QR vigente tras la operación.
    pub fn ensure_fresh_qr(&self) -> IssuedToken {
        let now = self.clock.now_ms();
        let expired_key = {
            let map = lock_or_reset(&self.inner);
            map.values()
                .find(|t| !t.record.consumed && crypto::is_expired(t.record.issued_at_ms, now))
                .map(|t| t.record.token)
        };
        if let Some(token) = expired_key {
            let mut map = lock_or_reset(&self.inner);
            map.remove(&token); // el token expirado no se reutiliza (R7)
        }
        match self.current_unconsumed(now) {
            Some(current) => current,
            None => self.issue(),
        }
    }

    /// El QR vigente: el único token no consumido y no expirado (invariante §14).
    fn current_unconsumed(&self, now: u64) -> Option<IssuedToken> {
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .values()
            .find(|t| !t.record.consumed && !crypto::is_expired(t.record.issued_at_ms, now))
            .cloned()
    }

    /// Busca el registro de un token presentado en `pair_request` (paso 6).
    pub fn lookup(&self, token: &Token) -> Option<TokenRecord> {
        lock_or_reset(&self.inner)
            .get(token)
            .map(|t| t.record.clone())
    }

    /// R2: marca el token como consumido (solo tras `Established`, §14).
    pub fn mark_consumed(&self, token: &Token) {
        if let Some(entry) = lock_or_reset(&self.inner).get_mut(token) {
            entry.record.consumed = true;
        }
    }

    /// §8.3: destruye la credencial; el reintento con esas credenciales es E3.
    pub fn revoke(&self, token: &Token) -> bool {
        lock_or_reset(&self.inner).remove(token).is_some()
    }

    /// Crea un session_id criptográficamente aleatorio (paso 10).
    pub fn new_session_id(&self) -> SessionId {
        let mut bytes = [0u8; 16];
        self.rng.fill(&mut bytes);
        SessionId(bytes)
    }
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    struct FixedRng(u8);
    impl RandomBytes for FixedRng {
        fn fill(&self, buf: &mut [u8]) {
            for b in buf.iter_mut() {
                *b = self.0;
            }
        }
    }

    /// RNG determinista que emite bytes distintos en cada llamada: útil para
    /// verificar que dos emisiones producen tokens diferentes (R7).
    struct IncrementingRng(std::cell::Cell<u8>);
    impl RandomBytes for IncrementingRng {
        fn fill(&self, buf: &mut [u8]) {
            let seed = self.0.get();
            for (i, b) in buf.iter_mut().enumerate() {
                *b = seed.wrapping_add(i as u8);
            }
            self.0.set(seed.wrapping_add(1));
        }
    }

    struct SteadyClock(u64);
    impl Clock for SteadyClock {
        fn now_ms(&self) -> u64 {
            self.0
        }
    }

    struct AdvancingClock(std::cell::Cell<u64>);
    impl Clock for AdvancingClock {
        fn now_ms(&self) -> u64 {
            self.0.get()
        }
    }

    fn store(clock: &impl Clock) -> TokenStore<FixedRng, &impl Clock> {
        TokenStore::new(FixedRng(7), clock, "192.168.1.50".into(), 8420)
    }

    // TEST-009 — verifies SPEC-001 (R7: regeneración automática del QR).
    #[test]
    fn expired_unconsumed_qr_is_replaced_automatically() {
        let clock = AdvancingClock(std::cell::Cell::new(1_000));
        let store = TokenStore::new(
            IncrementingRng(std::cell::Cell::new(1)),
            &clock,
            "192.168.1.50".into(),
            8420,
        );
        let first = store.ensure_fresh_qr();
        assert!(!first.record.consumed);

        // > 300 s después, `ensure_fresh_qr` reemplaza el QR sin intervención.
        clock.0.set(1_000 + 300_001);
        let second = store.ensure_fresh_qr();
        assert_ne!(second.record.token, first.record.token);
        assert_ne!(second.qr_uri, first.qr_uri);
        // El token expirado ya no está: si se presenta, responde E2 vía lookup.
        assert!(store.lookup(&first.record.token).is_none());
    }

    #[test]
    fn fresh_qr_within_300s_is_kept_not_regenerated() {
        let clock = AdvancingClock(std::cell::Cell::new(1_000));
        let store = store(&clock);
        let first = store.ensure_fresh_qr();
        clock.0.set(1_000 + 300_000); // frontera exacta: sigue vigente (R3)
        let second = store.ensure_fresh_qr();
        assert_eq!(second.record.token, first.record.token);
    }

    // TEST-005 — verifies SPEC-001 (R2: un solo uso; segundo intento → E3).
    #[test]
    fn consumed_token_is_not_served_again() {
        let store = store(&SteadyClock(1_000));
        let issued = store.issue();
        store.mark_consumed(&issued.record.token);
        let record = store.lookup(&issued.record.token).unwrap();
        assert!(record.consumed);
        // La máquina de `core` rechaza un registro consumido con E3.
        store.revoke(&issued.record.token);
        assert!(store.lookup(&issued.record.token).is_none());
    }

    #[test]
    fn issue_uses_server_clock_and_canonical_uri() {
        let store = store(&SteadyClock(42_000));
        let issued = store.issue();
        assert_eq!(issued.record.issued_at_ms, 42_000);
        let parsed = twinpad_core::qr::parse(&issued.qr_uri).unwrap();
        assert_eq!(parsed.server_port, 8420);
        assert_eq!(
            parsed.token,
            twinpad_core::message::b64_32(&issued.record.token.0)
        );
    }
}
