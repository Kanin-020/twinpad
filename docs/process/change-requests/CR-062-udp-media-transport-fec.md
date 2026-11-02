# Change Request (CR) — CR-062 — Twinpad

> ⚠️ TEMPLATE — the ONLY mechanism to change an `Approved` document. One CR per logical change.

| | |
|---|---|
| ID | CR-062 |
| Title | Transporte UDP para video/audio con FEC y degradación por pérdida (evitar head-of-line blocking de TCP) |
| Type | `Scope change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-02 |
| Decided | 2026-11-02 |
| Decided by | Product owner (vía chat: «Firmar la decisión del CR-062 y arrancar la implementación del canal UDP») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-003, DES-001, DES-002, ARCH (impacto indirecto REQ-005) |
| Sections | SPEC-003 §4/§8/§11/§12/§16 (nuevo §4.4: canal media UDP), DES-001/002 §apéndice cableado, ARCH §6 |
| Current version → new version | SPEC-003 1.11.x → 1.12.0; SPEC-005 1.4.x → 1.5.0 |

## 2. Requested change

**Current:**

```text
El canal media (AU H.264 juego→server→app y audio) viaja íntegramente
por TCP: 
- juego→server: TCP 127.0.0.1:8426, framing [32 B LE][AU] (CR-054).
- server→app: TCP 8425, framing [32 B LE][AU] con SNDBUF acotado +
  TCP_NODELAY + latest-frame-wins (CR-051) y escalera de bitrate por
  único indicador de congestión: descartes D/P del relay (CR-058).
Sin perdida de paquetes como señal; sin FEC; sin UDP.
```

**Proposed:**

```text
Canal media server→app como UDP OPCIONAL y preferente con la misma
semántica de negocio del payload (framing de AU, cifrado y NONCE
derivan de SessionKey establecida ya en el handshake):

- Path de video/audio: UDP payload por datagrama ≤ 1400 B; AU mayor
  fragmenta (seq, fragmento, último fragmento).
- Cifrado: AES-128-GCM con nonce derivado de SessionKey + contador
  por datagrama; AAD = seq; ventana anti-replay por sesión.
- FEC: bloque XOR entre AU consecutivos (≥ esfuerzo inicial: 1
  paquete de recuperación por cada N=4 de video), que cubre pérdida
  de 1 paquete por bloque sin retardo tras bloque completo.
- Degradación por pérdida (R10 extendido): además del ratio D/P del
  relay actual, la escalador de CR-058 también alimenta con la
  fracción de paquetes perdidos tras FEC en ventana móvil de 2 000 ms
  (pérdida ≥ 5 % ≡ congestión; igual histéresis de 10 s para subir).
- Fallback: sin respuesta UDP en 3 intentos (900 ms), la app
  revierte al TCP media existente (8425) — sin cultura perdida,
  compatible con las instancias actuales de SPEC-003.
- Control/inputs/heartbeat permanecen en TCP (SPEC-001): solo media
  se muda (framing estable, tolera pérdida, drop-old por semántica
  de.latest-frame-wins ya diseñada).
```

## 3. Motivation

**Evidencia (análisis 2026-11-02, tras CR-051/054/058):** el repaso de
cuellos de botella tras los gates verdes concluyó que el único cuello
**estructural** pendiente es TCP end-to-end para video/audio: ante pérdida
real de paquetes (WiFi adverso, no solo ancho de banda insuficiente), las
retransmisiones de TCP bloquean los frames de detrás (head-of-line
blocking). La escalera de bitrate de CR-058 reacciona en ventanas de
2 000 ms; no reacciona a microbursts de pérdida de <1 s, que son exactos
los que causan micro-stutter perceptible. Con UDP + FEC, la pérdida de 1
paquete por bloque de video/audio se recupera sin latencia añadida y el
framing ya tolera descarte por semántica latest-frame-wins.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-005 | sí (indirecto) | transporte del stream de video cambia de canal, misma calidad | verificación en revisión de revisión |
| SPEC-003 | **sí** | nuevo §4.4 (canal media UDP, join/framing/FEC), §8.5 (fallback), R11, §12/§16 | **redline** al aprobar (hecho) |
| SPEC-005 | no | **corrección al CR original:** el audio del juego viaja por la conexión NDJSON de la sesión (SPEC-005 §4), NO por el canal media TCP 8425; el canal UDP solo cubre la AU de video, y el audio queda intacto | none |
| SPEC-001 | no | control TCP intacto (heartbeat, resume) | none |
| ARCH | sí | §6 transporte: UDP media + TCP control conviven | nota de arquitectura en §6, sin nuevo ADR (no es cambio de estrategia, es refinamiento) |
| DES-001 | sí | §4: cableado de socket UDP en server y cliente Android | redline |
| DES-002 | sí | H264MediaClient → H264UdpMediaClient con fallback TCP | redline |
| TEST-0063/0064/0113 | sí | re-escritura para el canal UDP | update |
| Code | sí (grande) | core::media_crypto + relay UDP en server + cliente Android | ~2–3 iteraciones |
| Data / migrations | no | sin estado persistente nuevo | none |
| Integrations / contracts | sí | propagación de protocolo en wire (kernel par por versión de test invariante) | ensayo de compatibilidad mixta |

## 5. Options considered

1. **Canal media UDP + FEC + degradación por pérdida (proposed)** — elimina
   head-of-line, máximo beneficio con cambio acotado a media.
2. Do nothing (TCP + escalera CR-058) — micro-stutter bajo pérdida real de
   paquetes persiste; escalera reacciona en 2 s, no al microburst (<1 s).
3. QUIC (implementación completa) — resuelve lo mismo con auth total integrada,
   pero añade dependencia runtime Rust y Android y adopción más larga; quedaría
   como paso 2 si la política XOR no basta (a evaluar en banco físico).
4. UDP sin FEC (retransmitir NACK por AU) — reintroduce latencia en cada
   pérdida; el descarte ya está cubierto mejor con latest-frame-wins.
5. Mover audio a UDP también — descartado tras verificar SPEC-005 §4: el audio
   viaja por la conexión NDJSON de la sesión (no por el canal media); mudarlo
   ampliaría el blast radius sin beneficio (8 KB/s de PCM toleran TCP).

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** único cuello estructural restante según el análisis de
  2026-11-02 con gates verdes (head-of-line blocking de TCP ante pérdida real
  de paquetes). Impacto acotado al canal media; el control/inputs/heartbeat no
  se toca (SPEC-001 intacto) y el fallback TCP de SPEC-003 §4 conserva
  compatibilidad con las versiones actuales. El owner firmó la continuación
  («Firmar la decisión del CR-062 y arrancar la implementación del canal UDP
  core+server+Android») por chat en 2026-11-02.
- **Rollout:** misma release con doble canal (UDP preferente + TCP fallback);
  sin migración de estado.

## 7. Execution checklist

- [ ] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [ ] Dependent documents updated (from impact table)
- [ ] Tests added/updated (TEST IDs)
- [ ] `testing/traceability.md` rows updated (including CR history table)
- [ ] Indexes (`_index.md`) updated
- [ ] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-02 | Buffy (agente) | Opened: transporte UDP para media (decisión prevista del owner por chat) |
| 0.2.0 | 2026-11-02 | Buffy (agente) | Approved por el owner por chat: arranque de implementación core+server+Android |
| 0.3.0 | 2026-11-02 | Buffy (agente) | Corrección de alcance: el audio viaja por la sesión NDJSON (SPEC-005 §4), no por el canal media — SPEC-005 fuera del target; video AU only |
