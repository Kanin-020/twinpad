# ADR-009 — Canal Bluetooth HID nativo: alternativa completa sin Wi-Fi + failover automático de inputs

| | |
|---|---|
| ID | ADR-009 |
| Title | Canal Bluetooth HID nativo: alternativa completa + failover automático de inputs |
| Version | 1.0.0 |
| Status | `Accepted` |
| Date | 2026-11-01 |
| Deciders | Owner del proyecto (usuario, en chat de sesión) |
| Motivated by | CR-059 (aprobado) |
| Affects | SPEC-004 §4/§5/§6/§12, DES-004 (por asignar), módulo `hid` (ARCH §2), app Android |

## 1. Context

CR-059 (aprobado por el owner) pide reactivar el Gamepad HID Bluetooth nativo de
ADR-003 — diferido por CR-024 — para que los inputs del teléfono no dependan del
estado de la Wi-Fi LAN. El owner fijó en chat (2026-11-01) el alcance: el canal
BT HID debe ser **alternativa completa** (jugar sin Wi-Fi con el mando operativo
hacia el SO del PC) **además de** fallback automático ante cortes de la sesión
LAN de SPEC-001 (heartbeat CR-055). La pieza que faltaba era el ADR de detalle:
perfil BT, receptor en Windows y política de handover (lo que CR-059 §7 exige
antes de implementar).

Prerrequisito físico ya construido: la Signature HID de SPEC-004 §2 (contrato de
composición del gamepad) y el módulo `hid` de ARCH §2 (estructurado, sin
comportamiento hasta que existiera un ADR de detalle — era este).

## 2. Problem

¿Por qué perfil Bluetooth llega el gamepad del teléfono al SO Windows, con qué
software en la PC y con qué política de conmutación entre la ruta LAN y la ruta
BT? El HID device role de Android (CTX-A2) tiene cobertura variable y Windows
necesita recibir algo que su stack Bluetooth entienda sin fricción de setup.

## 3. Options considered

### Option A — HID Device role clásico (BluetoothHidDevice, API 28+)

- **Pros:** Windows lista el teléfono como gamepad estándar **sin software
  adicional** (AC-1 de REQ-003 de una vez); API Android estable y documentada;
  el adaptador Cemu ya consume el gamepad directamente del sistema (no requiere
  el Servidor PC vivo); zero surface de red nueva.
- **Cons:** cobertura del role por fabricante (la open question de CR-024);
  requiere emparejamiento Bluetooth previo PC↔teléfono hecho por el SO; el HID
  clásico no transporta touch DRC ni giroscopio como usos llanos (semántica
  distinta — extender el descriptor es otro CR); ancho de banda del enlace
  clásico no lleva video/audio (por eso los streams siguen en LAN, ADR-002).

### Option B — BLE-HOGP (HID over GATT desde la app actúa de periférico)

- **Pros:** mismo beneficio de "sin software en la PC"; perfil robusto.
- **Cons:** Android solo permite el role HOGP periférico a sistema/mouse
  (la app no puede exponer un HOGP gamepad completo con steering host-side
  desde la API pública); cobertura de soporte GATT-HID en Windows para
  periféricos no-mouse es irregular.

### Option C — RFCOMM SPP custom + driver virtual (ViGEm) en Windows

- **Pros:** descriptor flexible, ampliable; no depende del HID device role;
  permitiría más adelante el giroscopio/touch por el mismo canal.
- **Cons:** contradice la decisión ADR-003 (Opción A del owner: "sin software
  adicional"); instalación de driver = fricción y permisos (NFR-004 de setup);
  dos pies de receptor que mantener (viene a ser ADR-003 Opción B descartada).

## 4. Decision

- **Perfil:** classic **HID Device role** vía `BluetoothHidDevice` (Option A).
  La app registra un SDP "Twinpad Gamepad" y el descriptor HID del report map
  gamepad (sticks L/R, D-pad hat, 12 botones, triggers L2/R2 analógicos) según
  la Signature HID de SPEC-004 §2. El prerrequisito es el emparejamiento BT
  PC↔teléfono hecho por el SO (una vez); después la app reconecta el
  dispositivo ya emparejado.
- **Receptor en Windows:** ninguno — el stack HID del sistema operativo
  consume el gamepad directamente (eso hace viable el "modo alternativo
  completo": el juego recibe inputs aunque el Servidor PC esté caído o la LAN
  no exista). ViGEm/virtual driver queda como escalamiento posterior solo si
  en banco físico algún juego no reconoce el gamepad estándar.
- **Alcance de la Signature en la ruta BT:** sticks, D-pad, 12 botones,
  triggers L2/R2. Touch DRC y muestras inerciales (giroscopio/acelerómetro)
  no viajan por BT — quedan exclusivos de la ruta LAN (SPEC-006 SDK/SPEC-004
  §4). Extender el descriptor con ellos es otro CR si se demuestra que el
  transporte BT los necesita.
- **Política de handover (especificada en SPEC-004 §6):**
  - **Failover automático:** cuando el estado de la app sale de `Linked`
    (heartbeat CR-055 HB5/expiry o caída del socket) y el canal BT HID está
    operativo, los input reports conmutan a la ruta BT sin re-pairing; los
    `seq` siguen en la misma GamepadStateMachine (R2).
- **Alternativa completa:** sin Wi-Fi, el usuario activa la app y si el canal
  BT HID está listo, el gamepad envía por BT (el Servidor PC no participa).
  - **Regreso a LAN:** cuando la sesión vuelve a `Linked`, la app deja de
    enviar por BT y libera el perfil tras histéresis de 5 s (evita
    re-registros oscilantes si la LAN degrada intermitentemente).
  - **Video y audio:** nunca viajan por BT (ADR-002): en modo BT la Segunda
    pantalla/audio no están disponibles — UI lo comunica como estado, no como
    error.
- **Rumble:** v1 no cambia — sigue por la ruta LAN (des-de-la-PC). Extensión BT
  del rumble (output report) es otro CR.

## 5. Consequences

**Positive:**

- REQ-003 AC-1 (gamepad listado por el SO de la PC) se cumple sin software.
- Inputs no dependen de la Wi-Fi: alternativa completa y failover resuelven
  el quinto cuello de botella de conexión de CR-059.
- El mismo contrato de Signature (SPEC-004 §2) en ambos caminos: reusa el
  GamepadStateMachine y el comportamiento `seq`.

**Negative / trade-offs accepted:**

- Touch DRC, giroscopio y rumble no funcionan en la ruta BT (v1).
- Cobertura dependiente del HID device role del teléfono (CTX-A2) — se
  precisa en banco físico (Paso 2 del rollout de CR-059).
- Emparejamiento BT previo manual (una vez por dispositivo PC).

## 6. Compliance and validation

- Enforcement: codec/descriptor puro verificable (TEST-0126); política de
  handover verificable (TEST-0127); el flujo end-to-end (teléfono→Windows)
  requiere banco físico (prueba del Paso 2 de CR-059).
- Revisit trigger: cobertura insuficiente del HID device role en pruebas
  físicas, o si un juego relevante no consume el gamepad estándar (abre la
  alternativa ViGEm).

## 7. Related

| Relation | ID |
|---|---|
| Motivated by | CR-059 |
| Relates to | SPEC-004, REQ-003, ADR-002, ADR-003, ADR-007 |
| Changed by CR | CR-059 |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-11-01 | Owner + Buffy (agente) | Accepted: perfil HID Device role, receptor = stack Windows, handover autom + manual con histéresis 5 s |
