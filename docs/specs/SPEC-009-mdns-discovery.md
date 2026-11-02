# SPEC-009 — Descubrimiento mDNS/DNS-SD del Servidor PC

> **What this is:** the precise, testable behavior the system must exhibit for one capability. It is the contract that both tests and implementation must satisfy, and the reference an AI agent implements against.
> **What it is NOT:** a description of code structure (see `design/`) or of why the capability exists (see the REQ it implements).

| | |
|---|---|
| ID | SPEC-009 |
| Title | Descubrimiento mDNS/DNS-SD del Servidor PC (CR-057) |
| Version | 1.0.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-002 AC-4 |
| Realized by | DES-001 (anunciador en `server/`), DES-002 (NsdManager en `android/app`) |
| Verified by | TEST-0130, TEST-0133, TEST-0134 (registrados en `testing/traceability.md`) |
| Last updated | 2026-11-01 |

## 1. Purpose

Define el descubrimiento zeroconf del Servidor PC en la LAN para tolerar cambios de IP DHCP sin re-escanear el QR de vinculación (REQ-002 AC-4, CR-057). Complementa el payload del QR de SPEC-001 §4.1: no lo reemplaza, no añade credenciales ni mensajes de red nuevos.

## 2. Definitions

- **Anunciador mDNS**: componente del Servidor PC que publica el servicio DNS-SD de §3 mientras su socket de control de Pairing (SPEC-001) está escuchando.
- **Resolución NSD**: búsqueda del servicio `_twinpad._tcp` en la app Android vía `NsdManager` y obtención del par (host, puerto) de la instancia `Twinpad`.
- **Re-resolución (CR-057)**: resolver `s` de nuevo antes de cada intento de conexión (U2→U3 en SPEC-002 §6), en cada reanudación de control (SPEC-001 §8.2) y en cada reconexión de media (SPEC-003 §8.2); un fallo de TCP fuerza re-resolver antes del próximo intento.

## 3. Preconditions

- El Servidor PC está activo y su socket de control de Pairing escuchando (SPEC-001 §3). Sin él NO hay anuncio.
- PC y app en la misma LAN con mDNS multicast operativo (CTX-C2); si el demonio mDNS del SO o la interfaz multicast no están disponibles, el anunciador es best-effort (§6) y el anuncio queda suspendido.

## 4. Inputs

| Input | Type | Constraints | Source |
|---|---|---|---|
| `s` del QR (SPEC-001 §4.1) | string | IP literal (v4/v6) o el nombre `twinpad.local.` | payload escaneado o código escrito |
| Anuncio mDNS del Servidor | DNS-SD | tipo `_twinpad._tcp.local.`, instancia `Twinpad`, TXT `ver=twinpad.v1` | anunciador del Servidor (§5) |

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Par (host, puerto) resuelto | `(hostAddress, port)` del anuncio; el puerto es igual al `server_port` de SPEC-001 §4.1 | `NsdManager` resuelve la instancia `Twinpad._twinpad._tcp.local.` dentro del timeout (§6) |
| Conexión directa | sin resolución: la IP literal del QR se usa tal cual | `s` es IP literal (comportamiento previo a CR-057, sin tocar `NsdManager`) |
| Estado «Servidor no disponible» | E1 de SPEC-002 §9 | sin respuesta mDNS dentro del timeout aplicado |

## 6. States / comportamiento con mDNS ausente

- El Servidor PC anuncia `_twinpad._tcp.local.` con nombre de instancia fijo `Twinpad`, puerto igual al `server_port` y TXT `ver=twinpad.v1`, mientras su socket de control esté escuchando.
- La app resuelve `s` vía `NsdManager` SOLO cuando `s` no es IP literal; en v1 el único nombre válido es `twinpad.local.` (el anuncio usa hostname `twinpad.local.`).
- Timeout del resolver (lado app): 5 000 ms. Sin respuesta → el flujo resultante es la fila `E1` «Servidor no disponible» de SPEC-002 §9 intacta (SPEC-001 §9).
- **Falla blando (lugar del anunciador):** si el host no ofrece mDNS (sin interfaz multicast, demonio del SO ausente), el anuncio queda suspendido con un aviso en el log; el pairing por IP literal del QR permanece intacto y NINGÚN otro comportamiento cambia.
- La re-resolución se aplica en cada intento de conexión, en cada reanudación de control (CR-056) y en cada reconexión de media (SPEC-003 §8.2); un fallo de TCP fuerza re-resolver antes del próximo intento.

## 7. Main flow

1. El Servidor PC arranca el socket de control y publica el anuncio mDNS (§3/§5).
2. La app recibe el payload (QR o código escrito, SPEC-001 §4.1) y decide: `s` IP literal → conexión directa; `s = twinpad.local.` → resolución NSD (§5).
3. La app descubre `_twinpad._tcp` (Android añade el dominio `local.`), encuentra la instancia `Twinpad` y la resuelve: host + puerto.
4. Con el par resuelto (o el literal), el flujo continúa con el Handshake de SPEC-001 §7 sin cambios.

## 8. Alternative flows

- **Trigger:** renovación DHCP de la IP del PC después del emparejamiento.
- **Flow:** la app ya emparejada (U4/U6) re-resuelve `twinpad.local.` vía NSD en la reconexión (§6) y golpea la IP nueva sin QR nuevo (REQ-002 AC-4, en combinación con CR-056).
- **Result:** la sesión se reanuda sin re-escanear; combinado con SPEC-001 §8.2, sin pedir al usuario ninguna acción.

## 9. Errors

| Condition | Observable behavior |
|---|---|
| `s` no es IP literal ni `twinpad.local.` | sin resolución: `E1` «Servidor no disponible» (el payload ya es E5 según SPEC-001 §9 en la validación del parser; este caso solo existe para valores aceptados por el parser en el futuro) |
| Sin respuesta mDNS en 5 000 ms | `E1` «Servidor no disponible» (SPEC-002 §9) |
| `NsdManager` no disponible en el sistema (servicio NSD ausente) | `E1` «Servidor no disponible» |
| El anuncio mDNS no puede registrarse en el host | falla blando: solo aviso; sin efecto en el Handshake ni en el pairing (§6) |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| IP renovada por DHCP entre dos intentos | la re-resolución (§6) entrega la IP nueva; se fuerza re-resolver tras cualquier fallo de TCP |
| Varias instancias `_twinpad._tcp` en la LAN | el Servicio anuncia el nombre de instancia fijo `Twinpad`: fuera de v1 solo esa instancia es elegible (§5); las demás se ignoran |
| Instancia `Twinpad` sin host/huerto resolvible | el resolver espera dentro del timeout; seguido `E1` |
| Red «tranquila» del host sin router (loopback) | el anuncio y el browse de loopback siguen funcionando sobre la interfaz de loopback (TEST-0133) |

## 11. Business rules

- **R1:** el descubrimiento es complemento del QR: NO añade mensajes wire nuevos, credenciales ni permisos de red adicionales (NFR-004, ADR-002 intactos; la autenticación sigue siendo la de SPEC-001 §7/§4.2).
- **R2:** el nombre de instancia es fijo `Twinpad`; el hostname anunciado es `twinpad.local.` y el TXT mínimo es `ver=twinpad.v1`.
- **R3:** el anunciador es exclusive del Servidor PC y seEDA mientras su socket de control escuche; la app solo llama a `NsdManager` cuando `s` no es IP literal.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Tipo de servicio DNS-SD | este documento §5 | `_twinpad._tcp.local.`; cambiarlo exige CR |
| Nombre de instancia y TXT | este documento §5/§11-R2 | `Twinpad`, `ver=twinpad.v1` |
| Resolución `s` del QR | SPEC-001 §4.1 + este documento §5/§6 | sin mDNS → E1 o IP literal |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Sin re-scan tras renovación DHCP (REQ-002 AC-4)
  Given un teléfono ya emparejado y una IP del PC renovada por DHCP
  When la app re-resuelve twinpad.local. vía NsdManager y conecta
  Then alcanza la IP nueva y la sesión se reanuda sin QR nuevo

Scenario: Sin mDNS en el host
  Given un Servidor PC cuyo SO no ofrece mDNS
  When la app intenta resolver `twinpad.local.`
  Then recibe E1 "Servidor no disponible" tras el timeout, y el QR por IP literal sigue operando

Scenario: IP literal en el QR
  Given un QR con s=192.168.100.101
  When la app procesa el payload
  Then no se consulta NsdManager: la conexión es directa a esa IP
```

## 14. Invariants

- El anuncio mDNS solo existe mientras el socket de control de Pairing está escuchando; nunca anuncia un servidor apagado.
- La resolución NSD nunca sustituye la autenticación de SPEC-001 (el token sigue siendo la única credencial; ADR-004 intacto).
- Un fallo del anunciador jamás degrada el pairing por IP literal del QR (§6).

## 15. What "complies" means

Una implementación cumple este SPEC si y solo si: el Servidor PC anuncia §3/§5, la app resuelve §5/§6 con el timeout y sin respuesta produce E1, y las IP literales se comportan exactamente como antes de CR-057; además todos los casos §9/§10 se reproducen con sus triggers y las verificaciones §16 pasan.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Anunciador: ServiceInfo canónico (nombre/puerto/TXT) y resolución loopback propia | TEST-0130, TEST-0133 (`server/src/mdns.rs`) |
| Lado app: clasificación de `s` (IP literal → directa; `twinpad.local.` → NSD) | TEST-0134 (`HostResolutionTest`) |
| Checklist del estado U6 y resolución en U2→U3 (lugar app) | TEST-0130 (`PairingStateMachineTest`) |

## 17. Open questions

- Ninguna abierta.

## Definition of Done

- [x] Deterministic: two readers cannot reasonably interpret any statement differently.
- [x] All flows, errors and edge cases enumerated — no «etc.».
- [x] Every error and edge case has exact expected behavior.
- [x] Inputs/outputs fully enumerated with types and constraints.
- [x] No implementation detail beyond the boundary contract.
- [x] Every aspect has a TEST row; rows mirrored in traceability.md.
- [x] No contradictions with the implementing REQ.
- [x] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Draft operativo del anunció y resolución (CR-057) |
| 1.0.0 | 2026-11-01 | Buffy (agente) | `Approved` — anunciador server (`_twinpad._tcp.local.`) y NsdManager en la app implementados; TEST-0130/0133/0134 |
