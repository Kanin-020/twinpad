# Change Request (CR) — CR-126 — Twinpad

| | |
|---|---|
| ID | CR-126 |
| Title | Amiibo por NFC del teléfono: lectura Android, relay y tag virtual persistente en Cemu |
| Type | `New behavior` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Owner (petición directa) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `REQ-006`, `SPEC-006`, `DES-004` |
| Sections | `REQ-006 §6`; `SPEC-006 §4/§9/§12/§16`; `DES-004 §5/§6/§7/§8/§12` |
| Current version → new version | `REQ-006 0.3.0 → 0.4.0`; `SPEC-006 1.29.0 → 1.30.0`; `DES-004 1.19.0 → 1.20.0` |

## 2. Requested change

**Current:** no existe ningún mensaje, lector ni tag virtual Amiibo. El teléfono no lee NFC y Cemu solo puede montar Amiibos desde archivos locales elegidos a mano.

**Proposed:**

1. Nuevo mensaje de sesión `amiibo_tag`, Android→Servidor→Cemu:
   - `type:"amiibo_tag"`, `present:boolean` exactamente.
   - Con `present:true`: `uid` (14 hex minúscula, UID NTAG215 de 7 bytes) y `data`
     (base64url sin relleno de **exactamente 540 bytes**: volcado completo de la
     memoria NTAG215, páginas 0–134).
   - Con `present:false`: exactamente sin `uid` ni `data`.
2. Android **solo lee**; nunca escribe el tag físico. El lector NFC se activa
   únicamente con la Activity visible, la sesión `Linked` y el juego `cemu`
   seleccionado. Envía `present:true` al completar la lectura y `present:false`
   al retirar el tag, al salir de la app, al pausar o al cambiar de juego. Una
   lectura fallida no envía nada. El teléfono avisa con un Toast al detectar la
   lectura (UID) y otro si la lectura NFC falla. El mensaje no se persiste ni se
   reintenta.
3. El Servidor valida el mensaje con el codec estricto de `core::game` y lo
   reenvía **solo** al Plugin Cemu vivo y seleccionado; sin destino se descarta.
4. Cemu monta el tag en el HLE `nn::nfp` como tag virtual:
   - Copia virtual por UID en `<userData>/twinpad/amiibo/<uid>.bin`.
   - Si la copia no existe, se crea con los primeros **532 bytes** del volcado
     (el tamaño de `AmiiboRawNFCData`, páginas 0–16).
   - Si la copia ya existe **no se reimporta** el volcado físico: se conserva el
     progreso virtual (los cambios del juego se cifran y escriben en esa copia
     con `nnNfp_writeCurrentAmiibo`).
   - Mientras `present:true`, el adaptador refresca la presencia cada ~250 ms
     para que `nnNfp_update` no desmonte el tag a los 1500 ms.
   - `present:false`, desconexión o salida del título ejecutan
     `nnNfp_unloadAmiibo`; la copia virtual y sus cambios se conservan.
   - Si el juego no abrió `nn::nfp` (`nnNfp_isInitialized() == false`), la
     lectura se ignora; el usuario vuelve a presentar el tag cuando el juego lo
     pida.

## 3. Motivation

Petición directa del owner: Android debe poder usar su NFC como lector Amiibo y
Cemu emular el tag, sin escribir nunca el Amiibo físico. El contrato de 540 B es
el volcado NTAG215 completo que entrega `MifareUltralight.readPages` (33 lecturas
de 16 B, páginas 0–131, más una lectura solapada desde la página 131 para las
páginas 132–134; pedir la página 135, inexistente, hace que el tag responda NAK);
Cemu ya consume 532 B (`AmiiboRawNFCData`,
`static_assert` en `nn_nfp.cpp`). La copia por UID evita reimportar el dump
físico y perder el progreso guardado por el juego.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | yes | Nuevo AC-7: tag Amiibo virtual desde el lector NFC del teléfono, solo lectura | AC-7 + changelog 0.4.0 |
| SPEC-006 | yes | Nuevo mensaje `amiibo_tag` y su entrega | §4/§9/§12/§16 + changelog 1.30.0 |
| ARCH | no | Sin componente nuevo: usa la sesión y el canal IPC existentes | none |
| DES-004 | yes | Persistencia de la copia por UID, flujo, concurrencia y errores | §5/§6/§7/§8/§12 + changelog 1.20.0 |
| TEST-XXX | yes | TEST-0220 (core), TEST-0221 (servidor), TEST-0222 (Android), TEST-0223 (Cemu) | filas en `traceability.md` |
| Code | yes | `core/src/game.rs`, `server/src/{main,games}.rs`, lector NFC Android, canal Amiibo de Cemu y refresco en `nn_nfp` | medios |
| Data / migrations | no | Archivos nuevos en userData de Cemu; sin migración | none |
| Integrations / contracts | yes | Wire NDJSON aditivo; un cliente viejo lo rechaza como desconocido (E4), comportamiento actual | version bump |

## 5. Options considered

1. `amiibo_tag` con volcado NTAG215 completo (540 B) y copia por UID — el
   adaptador decide qué 532 B consume Cemu y el teléfono no interpreta el tag.
2. Enviar 532 B ya recortados desde Android — el recorte pertenece al contrato de
   Cemu y obliga a duplicar el invariante `sizeof(AmiiboRawNFCData)` en la app.
3. Do nothing — no hay Amiibo por NFC; el owner no puede usar sus tags desde el
   teléfono.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** la petición del owner define el alcance; el wire es aditivo y la
  lectura es de solo lectura, con la copia virtual como único destino de escritura.
- **Rollout:** misma release.

## 7. Execution checklist

- [x] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [x] Dependent documents updated (from impact table)
- [x] Tests added/updated (TEST IDs)
- [x] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado por petición del owner |
