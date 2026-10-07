# Non-functional requirements (NFR) — Twinpad

> ⚠️ TEMPLATE — NFRs are measurable global constraints on quality that apply across requirements. Requirement-specific constraints live in the REQ file; precise behavior lives in SPECs.

| | |
|---|---|
| Version | 0.1.0 |
| Status | Draft |
| Owner | Twinpad team |
| Last updated | 2026-10-07 |

## How to write an NFR

> 💡 An NFR must be measurable (number + unit + measurement condition) and verifiable (a test or a monitoring rule can check it). "Fast", "scalable" and "secure" are NOT NFRs; "p95 ≤ 300 ms for GET /orders under 100 rps" is.

Template:

```
NFR-XXX — <name>
Statement:  <system> shall <observable constraint> measured as <metric> ≤ / ≥ / = <threshold> under <condition>.
Rationale:  <why>
Verify by:  <TEST-XXX / monitoring rule / review>
Applies to: <all system / component <X> / flow <Y>>
Priority:   Must / Should / Could
```

## Catalog

> 💡 Los valores son propuesta inicial de la entrevista de definición; se afinan con medición en la v1 (marcados como Draft). Cambiar un NFR `Approved` exige CR.

### NFR-001 — Latencia de la Segunda pantalla (framebuffer)

```
Statement:  El servidor PC transmitirá cada Framebuffer de Segunda pantalla al Android de forma que el frame sea visible en pantalla del teléfono con latencia p95 ≤ 60 ms medido en la ruta PC-captura → transporte LAN → renderizado Android, bajo WiFi LAN en router IEEE 802.11ac.
Rationale:  Para que la Segunda pantalla sea útil jugando, la información secundaria no puede quedar desincronizada del juego principal (CTX-C2, CTX-C6).
Verify by:  TEST de performance con captura óptica de pantalla (marcador en pantalla, cámara de alta tasa)
Applies to: PC Host → Android device (Stream de video)
Priority:   Must
```

### NFR-002 — Input lag HID (botón → juego)

```
Statement:  El sistema transmitirá cada Input físico o táctil desde el Android hasta la inyección en el juego con latencia p95 ≤ 15 ms medido en la ruta evento Android → transporte (Bluetooth o LAN) → API HID de Windows.
Rationale:  Un gamepad con input lag perceptible hace el proyecto inutilizable para juegos competitivos (CTX-C3; entrevista pregunta 5).
Verify by:  TEST con simulador de entrada (señal en GPIO del adaptador Bluetooth o mediante inyección de HIDs sintética midiendo respuesta del SO)
Applies to: Android device → PC Host (Stream de inputs)
Priority:   Must
```

### NFR-003 — Servidor PC persistente y de bajo consumo

```
Statement:  El Servidor PC correrá como servicio/daemon persistente (sin ventana requerida) y consumirá ≤ 2 % de un núcleo CPU en estado idle conectado, medido en ventana de 60 s en un PC i5 de mitad de gama.
Rationale:  El servidor debe estar siempre listo para recibir Pairing sin afectar la experiencia de juego (REQ-001; entrevista pregunta 7).
Verify by:  TEST de monitoreo de recursos + revisión del mecanismo de instalación
Applies to: PC Host (Servidor Twinpad)
Priority:   Must
```

### NFR-004 — Seguridad de la sesión de Pairing

```
Statement:  La sesión establecida tras escanear el QR de vinculación autenticará ambos extremos con un challenge criptográfico (por ejemplo, clave de sesión derivada del contenido del QR) y rechazará como no autorizada cualquier conexión que no presente ese token.
Rationale:  Cualquier dispositivo de la LAN que conozca la dirección del Servidor PC no debe poder conectarse sin escanear el QR (CTX-C2; riesgo de " atacante en la LAN" planteado en la entrevista).
Verify by:  TEST de seguridad: conexión sin token es rechazada; revisión criptográfica del mecanismo
Applies to: Pairing (REQ-002) y todas las conexiones derivadas
Priority:   Must
```

### NFR-005 — Utilización de CPU del renderizado de segunda pantalla

```
Statement:  El renderizado del Stream de video de Segunda pantalla en el Android consumirá ≤ 25 % de CPU a 30 fps en un dispositivo gama media Android 12, medido en ventana de 5 min.
Rationale:  El celular ejecuta además el juego y otros apps; el costo de la Segunda pantalla debe ser asequible (CTX-A4).
Verify by:  TEST de performance en dispositivo físico
Applies to: Android device (app Twinpad)
Priority:   Should
```

### NFR-006 — Múltiples sesiones simultáneos

```
Statement:  El Servidor PC aceptará y mantendrá ≥ 2 pares de Android conectados y en uso simultáneo, sin degradación > 10 % de las latencias de NFR-001/NFR-002 respecto a una sesión única (medido en 802.11ac).
Rationale:  Un mismo juego puede beneficiarse de dos jugadores remotos o de pantalla + controles en dispositivos separados (alcance multi-jugador de la entrevista).
Verify by:  TEST de concurrencia con 2 dispositivos físicos
Applies to: PC Host (Servidor Twinpad)
Priority:   Should
```

### NFR-007 — Eficiencia energética del Android

```
Statement:  La app Android con session activa (Segunda pantalla + inputs HID) no drenará más de ~20 % de batería por hora en un gama media con brillo al 50 %, medido con Battery Historian en una sesión de 1 h estándar.
Rationale:  Twinpad es una sesión de juego prolongada; drenar la batería es inaceptable para uso real (CTX-A1).
Verify by:  TEST de batería con dispositivo físico
Applies to: Android device
Priority:   Should
```

## Definition of Done

- [ ] Every NFR has Statement with metric + threshold + condition.
- [ ] Every NFR has a verification method (test ID or monitoring rule).
- [ ] No vague adjectives without numbers ("fast", "robust", "user-friendly").
- [ ] REQs reference NFRs by ID instead of restating thresholds.
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft with interview-based NFRs (latencia HID, latencia segunda pantalla, seguridad del pairing, recursos) |
