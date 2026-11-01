# Change Request (CR) — CR-060 — Twinpad

| | |
|---|---|
| ID | CR-060 |
| Title | Método de vinculación alternativo al QR: código escrito (dispositivos sin cámara) |
| Type | New behavior |
| Status | `Applied (owner-requested)` |
| Author | Buffy (agente) |
| Created | 2026-11-01 |
| Decided | 2026-11-01 |
| Decided by | Owner (usuario) — «Añade un método de sincronización alternativo al QR (código escrito) para dispositivos sin cámara» |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002, DES-002 |
| Sections | SPEC-002: §3, §4, §6, §8.4 (nueva), §9 (nota E5), §10, §11 (R20), §16, Changelog; DES-002: realización + traceability + changelog |
| Current version → new version | SPEC-002 `1.10.0 → 1.11.0`; DES-002 `x → +0.1.0` |

## 2. Requested change

**Current:**

```text
La única forma de iniciar la vinculación es escanear el QR por cámara:
SPEC-002 §3 exige «cámara operativa», §6 tiene U1→U2 solo por la acción
«escanear QR» y la máquina (PairingStateMachine) solo maneja QrScanned en
U2/Escaneando. Un teléfono sin cámara (o con cámara rota) no puede vincular.
El Servidor PC ya muestra el payload textual junto al QR («URI (por si
necesitas copiarlo): …», server/src/main.rs) y lo publica por IPC a Cemu.
```

**Proposed:**

```text
§3 — precondición alternativa: cámara operativa O código escrito disponible.

§4 — nuevo input:
| Código de vinculación escrito | string | debe cumplir SPEC-001 §4.1
  (idéntico al contenido del QR) | entrada manual de la Pantalla de Pairing |

§6 — filas nuevas (estado de la app; SPEC-001 queda intacto):
| U1 | código escrito válido | U3 | abre TCP a server_addr:server_port y
       envía pair_request (efectos idénticos al escaneo; sin cámara) |
| U1 | código escrito inválido (E5) | U5 | mensaje «QR no válido» |

§8.4 — «Entrada manual del código sin cámara»:
- Trigger: el dispositivo no tiene cámara (o su cámara no es usable).
- Flow: en U1 el usuario elige «Vincular con código», escribe o pega el
  payload textual publicado por el Servidor PC (§4.1); la app recorta
  espacios/blancos al inicio y al final («trim») y lo valida con el parser
  estricto de SPEC-001 §4.1. Válido → U3 (Handshake.Spec específico del
  §7); inválido → E5. La cámara NUNCA se solicita en esta ruta (R2 solo
  aplica al escaneo).
- Result: un intento de vinculación equivalente al del QR; sin cambios
  de wire, token, R2/R3 ni criptografía.

§9 — E5 cubre ambas vías: el payload no conforme llega por cámara O por
código escrito; el mensaje exacto en pantalla sigue siendo «QR no válido».

§10 — edge cases:
| Código escrito con blancos al inicio/fin | la app los recorta antes de
  validar; blancos EN EL MEDIO son E5 |
| Código de sistema sin cámara | la app no consulta la cámara ni muestra
  el preview; el flujo es U1 → U3 |

§11 — R20: la entrada manual no pide permiso de cámara (R2 se exige solo
en la vía de escaneo); el texto del código no se registra en logs (R3).

§16 — | Entrada manual del código (U1 → U3 y E5, sin cámara) | TEST-0124 |

Cabecera: Version 1.11.0, Verified by añade TEST-0124, Last updated
2026-11-01. Changelog: «1.11.0 — 2026-11-01 — Buffy (agente) — CR-060:
entrada manual del código de vinculación sin cámara (§8.4, R20); wire y
criptografía sin cambios; TEST-0124».
```

## 3. Motivation

Un teléfono sin cámara (CTX-C1) hoy no puede vincular: la única transición
U1→U2 exige cámara (§6, §7 paso 1, R2/R2 de SPEC-001). El Servidor PC ya
imprime el payload textual copiable, pero la app no tiene UI para escribirlo.
La alternativa mínima que respeta ADR-004 (token de 32 bytes, NFR-004) y R1
(sin nuevos mensajes de red) es rutilizar el MISMO parser estricto con la
cadena URI completa que escanea el QR. No se reduce entropía ni se introduce
un credencial derivado (esa opción se descarta en §5).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | el paring permanece idéntico; solo cambia la manera en que la app obtiene el payload (cámara → texto) | none |
| SPEC-001 | no | §4.1 ya define el payload textual como contrato; el QR es solo el contenedor | none |
| SPEC-002 | yes | §3/§4/§6/§8.4/§9/§10/§11-R20/§16 + changelog | update (1.11.0) |
| ADR-004 (QR + token) | no | el token de 32 B base64url sigue siendo la única credencial; el código escrito ES el mismo token | none |
| NFR-004 | no | no se reduce entropía; el payload completo se valida | none |
| ARCH | no | sin nuevos componentes; reutiliza máquina/VM/parser | none |
| DES-002 | yes | realización de la entrada manual (diálogo + evento en la máquina) | update |
| TEST-0124 (nuevo) | yes | máquina pura: U1 + código escrito → U3 con Connect/SendLine y SIN cámara; E5 desde U1 | add row |
| Code | yes | `PairingStateMachine` (QrScanned en Idle), `PairingViewModel` (método de entrada, trim), `PairingActivity` (diálogo), layout + strings | done esta iteración |
| PC / integrate host | no | el Servidor PC ya publica el URI textual junto al QR (terminal y IPC de Cemu); sin cambio | none |
| SPEC-006 / Cemu | no | — | none |

## 5. Options considered

1. **URI completo como código escrito (propuesto)** — el payload es exactamente
   el que impone el Securo respalte; se reusan parser (E5), máquina y wire;
   cero cambios de seguridad; la PC ya muestra el texto. Contra: 43 caracteres
   para tipear el token; mitigado por copiar/pegar y por el trim del §8.4.
2. **PIN corto derivado del token (KDF → 12 dígitos)** — más cómodo, pero en la
   práctica SUSTITUYE la credencial (adivinable por fuerza bruta local, hay
   que añadir anti-bruteforce en el servidor, y rompe NFR-004/ADR-004):
   descartado por riesgo y alcance.
3. **mDNS/servicio local sin código (CR-057)** — su CR ya existe pero es
   par: la autenticación sigue requiriendo el token; no sustituye el QR.
   Descartado para este problema.

## 6. Decision

- **Outcome:** Applied (owner-requested).
- **Rationale:** ruta mínima y segura: el «código escrito» es el mismo payload
  de SPEC-001 §4.1; la PC ya lo muestra en texto junto al QR y por IPC; la app
  valida con el parser estricto existente y entra al mismo Handshake. Sin
  cambios de contratos, tokens ni criptografía.
- **Rollout:** solo la app cambia; cualquier Servidor PC existente funciona
  porque ya imprime el URI (no hay dependencia de versión server).

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-002 1.11.0, changelog con CR-060)
- [x] Dependent documents updated (DES-002)
- [x] Tests added/updated (TEST-0124)
- [x] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes updated (`change-requests/_index.md`)
- [x] Code change (máquina + VM + Activity + layout/strings)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Opened, aprobado y aplicado en la misma iteración (orden directa del owner) |
