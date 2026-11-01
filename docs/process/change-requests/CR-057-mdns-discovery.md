# Change Request (CR) — CR-057 — Twinpad

| | |
|---|---|
| ID | CR-057 |
| Title | Descubrimiento mDNS/zeroconf del Servidor PC (tolerancia a cambio de IP DHCP) |
| Type | Scope change |
| Status | `Open` |
| Author | Buffy (agente) |
| Created | 2026-11-01 |
| Decided | — |
| Decided by | — |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | REQ-002 (Draft, edición directa), SPEC-001, SPEC-002, nuevo SPEC-009 |
| Sections | SPEC-001: §4.1, Changelog · SPEC-002: §6 (U2→U3 step), Changelog · SPEC-009: documento nuevo |
| Current version → new version | REQ-002 `Draft 0.1.0 → Draft 0.2.0` · SPEC-001 `1.2.0 → 1.3.0` (rebasear) · SPEC-002 `1.11.0 → 1.12.0` · SPEC-009 `1.0.0` (nuevo) |

## 2. Requested change

**Current:**

```text
Los payloads de pareo (SPEC-001 §4.1) ya aceptan la cadena "twinpad.local."
como сервер en el campo `s`; pero no existe anunciador mDNS en el Servidor
ni NsdManager/zeroconf en la app. `validate_addr` solo valida la forma; el
paso U2→U3 de SPEC-002 §6 abre un TCP directo al literal del QR. Con la IP
del PC por DHCP renovada, el QR queda obsoleto: una app ya emparejada no
puede encontrar el servidor sin re-escanear (E1 «Servidor no disponible»).
```

**Proposed:**

```text
(1) REQ-002 — añadir AC al final de la lista:

- AC-4: Dado un teléfono ya emparejado con el PC y una IP del PC renovada
  tras un reinicio del router, cuando la app intenta conectar o reanudar
  (CR-055/CR-056), entonces resuelve el servidor vía mDNS (SPEC-009) usando
  el nombre anunciado y la sesión se restablece sin re-escanear.

(2) Nuevo SPEC-009 «Descubrimiento mDNS del Servidor PC» (reservar SPEC-009):

- El Servidor PC anuncia el servicio mDNS/DNS-SD `_twinpad._tcp.local.` con
  nombre de instancia fijo `Twinpad`, puerto igual al server_port y TXT
  `ver=twinpad.v1`, mientras su socket de control esté escuchando.
- La app, cuando el campo `s` del QR no es una IP literal, resuelve `s`
  (host + tipo + dominio, p. ej. «twinpad», «_twinpad», «.tcp», «local.»)
  vía NsdManager; el literal IP se usa directo (comportamiento actual).
- Re-resolución: en cada intento de conexión (U2→U3), en cada reanudación
  de control (CR-056) y en cada reconexión de media (SPEC-003 §8.2); un
  fallo de TCP fuerza re-resolver antes del próximo intento.
- Sin respuesta mDNS (timeout del resolver aplicado): comportamiento E1 de
  SPEC-002 intacto («Servidor no disponible»).

(3) SPEC-001 §4.1 — tras la frase que enumera las formas válidas de `s`
    («IP literal o twinpad.local.») añadir:

  «El nombre twinpad.local. se resuelve vía mDNS (SPEC-009); sin mDNS
  operativo el payload requiere IP literal.»

(4) SPEC-002 §6 — U2→U3 step pasa a: «resuelve `s` (IP literal directa;
    nombre vía mDNS SPEC-009) y abre TCP al server_port».
```

## 3. Motivation

Tercero de los cuellos de botella de conexión: un cambio de IP del PC
(práctica normal en DHCP) rompe todos los payloads QR existentes y obliga a
re-pairing manual, violando NFR-006 y «sesión reutilizable» de REQ-002. La
estructura ya existe (además `twinpad.local.` es válido en el QR desde el
día uno), pero no hay nadie que anuncie ni resuelva.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | yes | AC-4 nuevo (Draft: edición directa permitida) | update al aplicar |
| SPEC-001 | yes | §4.1 nota de resolución + changelog | update |
| SPEC-002 | yes | U2→U3 step (resolución antes de open TCP) | update |
| SPEC-009 | new | documento nuevo (descubrimiento mDNS) | crear |
| ARCH | no | mecanismo dentro de la frontera LAN ya cubierta por ADR-002 y CTX-C2 | none |
| DES-002 (Android NsdManager) / DES-001 (announcer servidor) | yes | — | al implementar |
| CODE | yes | `server/` (announcer mDNS, dependencia zeroconf) + `android/` (NsdManager) | al implementar |
| Data / migrations | no | — | none |
| Integrations / contracts | yes | nueva dependencia en servidor (announcer zeroconf/mDNS nativo o crate) | decidir al aplicar |

## 5. Options considered

1. **mDNS/DNS-SD con NsdManager (propuesto)** — estándar de facto en Android,
   sin nueva fricción de permisos, y la cadena `twinpad.local.` ya es válida
   en el QR.
2. Broadcast UDP de descubrimiento custom — descartado: no ofrece nombre
   estable enlazado al payload QR, más problemático con firewalls/AP
   isolation, requiere protocolo propio nuevo (más CR).
3. Do nothing — cualquier renovación de DHCP exige nuevo QR: el flujo
   actual del owner dice literalmente que «un celular ya vinculado no puede
   volver a encontrar la PC sin un QR nuevo».

## 6. Decision

- **Outcome:** (pendiente) — aprobado el requisito general, pendiente
  decidir crate/runtime del anunciador en servidor.
- **Rationale:** cierra el hueco de descubrimiento identificado en el
  análisis; reutiliza el formato de QR y la topología LAN ya existentes.
- **Rollout:** mismo release; Servidor y app actualizados juntos.

## 7. Execution checklist

- [ ] Target document(s) updated (incluye crear SPEC-009)
- [ ] Dependent documents updated
- [ ] Tests added/updated
- [ ] `testing/traceability.md` updated
- [ ] Indexes updated
- [ ] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Opened en lote de cuellos de botella de conexión |
