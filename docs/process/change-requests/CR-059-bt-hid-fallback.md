# Change Request (CR) — CR-059 — Twinpad

| | |
|---|---|
| ID | CR-059 |
| Title | Canal de inputs redundante por Bluetooth HID (fallback de red) |
| Type | Scope change |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-01 |
| Decided | 2026-11-01 |
| Decided by | Owner del proyecto (usuario, en chat de sesión) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004, ADR-007, ADR-003 (nota), nuevo ADR (reservar número) |
| Sections | SPEC-004: §4, §12, Changelog · ADR-007: trigger de revisión · ADR-003: nota de reactivación |
| Current version → new version | SPEC-004 `1.10.0 → 1.11.0` · ADR-007 → |Aceptado → Aceptado con revisión abierta| · nuevo ADR `Proposed` |

## 2. Requested change

**Current:**

```text
SPEC-004 v1.0.0 §4: el canal de inputs es la sesión LAN autenticada de
SPEC-001 sobre NDJSON; ADR-007 decidió BT HID diferido (meta en el roadmap)
y ADR-003 lo suspendió vía CR-024. Hoy el único medio de inputs es la LAN:
un router saturado, un AP que reinicia o NFR-002 fuera de umbral no tienen
ningún fallback y el juego queda sin control sobre Wi-Fi (video puede
tolerar picos, pero los inputs se quedan pegados).
```

**Proposed:**

```text
SPEC-004 §4 — dos rutas de transporte para la Signature HID (decisión del
owner en chat, 2026-11-01: NO solo fallback; alternativa COMPLETA):

- Ruta LAN (primaria para la entrega completa): sesión autenticada de
  SPEC-001, sin cambios — es LA única ruta con Segunda pantalla/audio/
  touch/giroscopio (video/audio no viajan por BT: ADR-002, ancho de
  banda del enlace clásico)
- Ruta Bluetooth HID nativa (motor hid de ARCH §2): teléfono actúa como
  dispositivo HID clásico (BluetoothHidDevice, role HID Device, API 28+)
  frente al PC ya emparejado por SO; Windows lista el gamepad estándar
  SIN software adicional; los inputs llegan al juego por el HID del
  SO, no por el Servidor PC.
  - Elementos transportados: sticks L/R, D-pad, 12 botones, triggers
    L2/R2 (mixed-style gamepad).
  - Elementos NO transportados por BT: touch DRC e inercia (giroscopio
    queda solo en la ruta LAN/SDK; el HID clásico no tiene uso paralelo
    a la Signature sin extender el descriptor — ADR-009 §5.

Modo alternativo completo: cuando no hay Wi-Fi la app puede entrar en
  BT activamente (manual/selector) y el juego recibe el mando por el
  SO aunque el Servidor PC no sea alcanzable.

Modo failover automático ante cortes: cuando la sesión LAN cae
  (heartbeat CR-055 HB5/expiry), la app conmuta el envío de
  `input_report` a la ruta BT sin re-pairing; al volver la LAN sana,
  liberación del perfil BT con histéresis de 5 s.

SPEC-004 §12 — filas de transporte BT HID → ADR-009.

ADR-007 — nota de que CR-059 activó el trigger de revisión (la meta
  final BTHID transport se activa, no solo failover).

ADR-003 — nota: CR-024 suspendió el BT HID; CR-059 lo REACTIVA como
  alternativa completa + failover, con la decisión de detalle en
  ADR-009 (nuevo ADR).

ADR-009 «Canal Bluetooth HID nativo: alternativa completa + failover»:
  - Decisor: owner del proyecto (usuario) por chat de sesión 2026-11-01.
  - Perfil: classic HID Device role (BluetoothHidDevice) — BLE-HOGP
    descartado (Android no permite host-side HOGP steering desde la app
    para gamepads; SPP custom descartado: requiere software en la PC).
  - Receptor en Windows: ninguno (HID nativo del stack BT de Windows);
    ViGEm/virtual driver solo si en prueba de banco físico el gamepad
    estándar no llega a algún juego (escala posterior).
  - Handover: automático en caída de sesión LAN + manual como
    alternativa completa sin Wi-Fi; histéresis 5 s de liberación.
```

## 3. Motivation

Quinto cuello de botella: la LAN es un punto único de fallo para todo el
producto (video + audio + inputs). CR-055 (heartbeat) detecta el corte con
fiabilidad, y CR-056 reanuda el canal sobre la misma ruta; pero si la ruta
LAN entera sigue degradada, no hay por dónde reanudar. ADR-002 ya contempla
BT para HID como uso en el roadmap: la única pieza que falta es el
receptor en Windows y la política de handover.

Refinamiento del owner (chat 2026-11-01): el alcance NO queda limitado a
fallback; BT HID debe funcionar también como ALTERNATIVA COMPLETA cuando
no hay Wi-Fi disponible. Video/audio permanecen en LAN por diseño
(ADR-002: el enlace BT clásico no soporta SPEC-003 NFR-001).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 (HID) | no | ya contempla el transporte BT como alternativa viable (lectura del intento original de ADR-003); el SPEC realiza esa parte | none |
| SPEC-001 | no | usa el heartbeat de CR-055 como trigger (dependencia en tiempo de implementación, no de documento) | none |
| SPEC-004 | yes | §4 doble transporte, §12 fila | update (1.11.0) |
| SPEC-002 | no | — | none |
| ARCH | no | la frontera de hid está definida; el failover no cruza módulos | none |
| DES-003 (android HID) | yes | descriptor/UI de activación del excelente failover | al implementar |
| ADR-007 | yes | nota de revisión | update |
| ADR-003 | yes | nota de reactivación | update |
| Nuevo ADR | new | perfil BT, receptor Windows, handover | crear |
| Tests | yes | reservados TEST-0128+ (failover, handover) | al aplicar |
| Code | yes | `hid/` y/o `server/` receptor Windows + `android/` servicio BT | al implementar |
| Data / migrations | no | — | none |
| Integrations / contracts | yes | nueva dependencia en Windows (driver de gamepad virtual) — riesgo de instalación/permiso | decidir en ADR |

## 5. Options considered

1. **Alternativa completa + failover BT HID (aprobado por el owner)** —
   mismo contrato de Signature (SPEC-004 §2) en ambos caminos; los inputs
   nunca dependen del estado de la Wi-Fi; sin Wi-Fi el juego igual recibe
   el mando (HID nativo al SO).
2. Mantener LAN como único camino — sin work, los inputs permanecen pegados
   durante cualquier degradación de red: el mayor daño de runtime posible.
3. USB OTG (phone cable) — fuera de alcance v1: no ad-hoc, exige cable
   dedicado y rompe la portabilidad del teléfono mientras juega.

## 6. Decision

- **Outcome:** APPROVED por el owner del proyecto (usuario, chat de sesión
  2026-11-01): alternativa completa + failover automático, con rollout en
  dos pasos.
- **Rationale:** es el único fallback físico disponible sin hardware extra;
  el descriptor ya existe (SPEC-004 §2) y el Heartbeat de CR-055 provee el
  trigger; el owner amplió el objetivo más allá del failover inicial.
- **Paso 1 (aprobado, code merged):** módulo `hid` (descriptor + codec
  Signature→report map) + `BluetoothHidGamepad` (BluetoothHidDevice profile
  proxy) + handover en `PairingViewModel` con histéresis; prerrequisito:
  Emparejamiento BT PC↔teléfono hecho por el SO la primera vez.
- **Paso 2 (banco físico):** validar en teléfono real (cobertura del HID
  device role, CTX-A2; NFR-002 en la ruta BT; receiver en Windows), y en
  caso de que algún juego no reconozca el gamepad, considerar ViGEm.
- **Rollout:** release separado del CR-055/056/057; requiere banco físico.

## 7. Execution checklist

- [ ] Target document(s) updated
- [ ] Dependent documents updated (ADR-007, ADR-003)
- [ ] New ADR (perfil, receptor, handover)
- [ ] Tests added/updated
- [ ] `testing/traceability.md` updated
- [ ] Indexes updated
- [ ] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Opened en lote de cuellos de botella de conexión |
| 0.2.0 | 2026-11-01 | Owner + Buffy | CR Approved: owner amplió el scope de «failover de inputs» a «alternativa completa (sin Wi-Fi) + failover automático»; ADR-009 lo registra |
