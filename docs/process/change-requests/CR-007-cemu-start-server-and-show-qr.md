# Change Request — CR-007 — Iniciar servidor Twinpad y mostrar QR en Cemu

| | |
|---|---|
| ID | CR-007 |
| Title | Iniciar servidor Twinpad y mostrar QR en Cemu |
| Type | `New behavior` |
| Status | `Approved` |
| Author | Twinpad team (propuesta solicitada por el owner) |
| Created | 2026-10-18 |
| Decided | 2026-10-18 |
| Decided by | Product owner (usuario) |

> Aprobado por el usuario el 2026-10-18: “Iniciar conexión” presenta el QR de conexión; Cemu inicia `twinpad-server` desde PATH si no hay servidor utilizable, consulta el QR vigente de un servidor existente mediante loopback y, si Cemu inició el proceso, pregunta al salir si debe detenerlo.

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006, ARCH, DES-004; endpoint de consulta loopback del Servidor PC |
| Sections | SPEC-006 §3/§7/§9/§11/§13/§16; ARCH §2/§3/§4/§6/§7; DES-004 §1/§3/§4/§6/§7/§8/§12; TEST-047 |
| Current version → new version | SPEC-006 1.4.0 → 1.5.0; ARCH 0.4.0 → 0.5.0; DES-004 0.2.0 → 0.3.0 |

## 2. Requested change

**Current — CR-006:**

```text
“Iniciar conexión” inicia el adaptador Cemu con un título activo. El menú tiene dos opciones exactas: “Iniciar conexión” y “Configuraciones”. La conexión requiere que el Servidor PC ya esté activo.
```

**Proposed — aprobado:**

```text
- Al pulsar “Iniciar conexión” con un título activo, Cemu obtiene el QR vigente del servidor, lo muestra en una ventana Cemu y, al aceptarla/cerrarla, inicia la conexión al adaptador; cancelar no inicia la conexión.
- Cemu consulta el QR por una interfaz TCP exclusiva de 127.0.0.1. El endpoint devuelve el payload canónico twinpad.v1 y su matriz QR derivada del mismo token vigente en memoria; no abre otro transporte de Pairing ni crea tokens paralelos.
- Si no responde un servidor local, Cemu ejecuta `twinpad-server` por nombre desde PATH y vuelve a solicitar el QR. Si el servidor ya existe, lo reutiliza.
- Un servidor iniciado por Cemu permanece ejecutándose después de mostrar el QR y de terminar el título. Al cerrar Cemu se pregunta si el usuario quiere detener únicamente el proceso que inició Cemu; responder no deja el servidor activo.
- Si no se puede encontrar/iniciar el ejecutable, obtener un QR vigente o conectar al endpoint local, Cemu muestra un error observable y no afirma haber iniciado la conexión.
```

## 3. Motivation

El usuario pidió explícitamente que “Iniciar conexión” muestre el QR. SPEC-001 define que el QR contiene el token de un solo uso y que solo el Servidor PC es dueño del registro. Cemu no puede generar un QR válido independientemente ni consultar el token en memoria sin un canal local. El usuario eligió localizar el proceso por PATH, reutilizar una instancia activa, preguntar al cerrar si detener el proceso iniciado por Cemu y aprobó un endpoint estrictamente loopback para recuperar el QR.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-001 | No | El URI/payload, token y expiración existentes siguen siendo canónicos | Ninguna modificación; referenciarlo |
| SPEC-006 | Yes | Iniciar conexión obtiene y presenta el QR antes de conectar; errores de lanzamiento/QR son locales a la integración Cemu | Actualizar flujo/reglas/verificación vía CR |
| ARCH | Yes | Cemu coordina el proceso local y consume un endpoint que solo escucha en loopback | Actualizar límites, superficie de seguridad y ciclo de proceso |
| DES-004 | Yes | Capturar respuesta QR, generar matriz y presentar ventana Cemu; lifecycle del proceso administrado | Actualizar diseño |
| TEST-047 | Yes | Probar endpoint loopback, token/URI actual y matriz QR; build Cemu | Registrar suite |
| Code | Yes | Servidor Rust añade listener loopback; Cemu lanza por PATH/reutiliza/visualiza/pregunta al cerrar | Implementar |
| Data / migrations | No | No persiste token ni copia el QR | Ninguna |
| Integrations / contracts | Yes | Nuevo protocolo privado TCP limitado a loopback para consulta local del QR; el Pairing de Android permanece intacto | Mantener fuera de interfaces no-loopback y documentar payload |

## 5. Options considered

1. Cemu lanza/reutiliza Servidor PC y consulta el QR actual por loopback — satisface el pedido conservando la autoridad del token en el servidor.
2. Generar el QR dentro de Cemu — se rechaza: Cemu no posee el token vigente y un QR generado aparte no autenticaría Pairing.
3. Mostrar solo instrucciones para leer el terminal — no satisface el pedido de mostrar el QR en Cemu.

## 6. Decision

- **Outcome:** `Approved` por el usuario el 2026-10-18.
- **Rationale:** el Servidor PC sigue siendo la autoridad única del token; la interfaz de loopback comparte el URI y la matriz del QR vigente solamente entre procesos de la misma PC. Cemu localiza `twinpad-server` en PATH, reutiliza una instancia que ya responda y solo pregunta por el proceso que él mismo inició.
- **Rollout:** build local experimental Cemu/Twinpad; el listener de QR no se expone en la LAN ni altera el protocolo de Pairing.

## 7. Execution checklist

- [x] SPEC-006, ARCH y DES-004 actualizados con CR-007
- [x] TEST-047 registrado en trazabilidad e implementado (cobertura loopback del servidor)
- [x] Servidor expone QR URI + matriz solo en loopback
- [x] Cemu reutiliza una instancia local que responde en el endpoint QR o inicia twinpad-server desde PATH si no responde
- [x] “Iniciar conexión” muestra el QR vigente y, al aceptar la ventana, activa el cliente
- [x] Cemu pregunta al cerrar si detiene su propia instancia
- [x] Rebuild Cemu (`CemuWxGui`, `CemuCafe`, `CemuBin`) y prueba loopback; smoke visual de la ventana pendiente por no lanzar el emulador sin un título de prueba
- [ ] Revisión final del diff

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-18 | Product owner + Twinpad team | Aprobado mostrar el QR vigente al iniciar, iniciar/reutilizar twinpad-server desde PATH, endpoint solo loopback y consulta al cerrar por el proceso propio |
