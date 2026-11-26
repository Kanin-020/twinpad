# Change Request (CR) — CR-121 — Twinpad

| | | |
|---|---|---|
| ID | CR-121 |
| Title | Notificación de sesión visible en segundo plano: se pide `POST_NOTIFICATIONS` y el texto refleja si hay transmisión |
| Type | `New behavior` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 (§11, changelog) |
| Sections | SPEC-002 §11 (R23), §16 (Verified by), changelog |
| Current version → new version | SPEC-002 1.13.0 → 1.14.0 |

## 2. Requested change

Petición del owner (chat, 2026-11-26): «Quiero que la app muestre notificación
cuando haya transmisión y la app esté en segundo plano».

**Current (código; ningún SPEC describe la notificación):**

```text
- PairingActivity arranca SessionForegroundService al entrar a U4 y al
  reanudar en U6: el servicio en primer plano impide que Android mate el
  proceso y corta el video al cambiar de app.
- El manifest declara POST_NOTIFICATIONS, pero la app nunca lo pide en
  runtime: en Android 13+ el sistema NO muestra la notificación del
  servicio (el servicio sigue vivo), así que en segundo plano no hay señal
  visible de la transmisión.
- El texto de la notificación es fijo («Segunda pantalla y controles
  conectados al PC») aunque el stream esté entregando frames.
```

**Proposed:**

```text
SPEC-002 §11 — R23 (CR-121):
- Con sesión viva (U4 o U6) la app mantiene el servicio en primer plano de
  la sesión: es lo que permite seguir transmitiendo al cambiar de app.
- La notificación de ese servicio es la señal visible en segundo plano.
  Al entrar en sesión, en Android 13+ la app pide POST_NOTIFICATIONS; si el
  usuario lo niega, la sesión y la transmisión siguen igual y solo falta la
  notificación.
- El texto depende del stream (SPEC-003 §6): con frames en V1/V2 dice
  «Transmitiendo la Segunda pantalla al teléfono»; sin frames dice
  «Segunda pantalla y controles conectados al PC».
- Tocar la notificación vuelve a la app; la notificación desaparece al
  terminar la sesión.
```

## 3. Motivation

Implementation discovery: el servicio y la notificación ya existían, pero sin
el permiso de runtime la notificación nunca se mostraba en Android 13+, y con
el texto fijo no distinguía «conectado» de «transmitiendo». El owner quiere ver
en segundo plano que la transmisión está viva.

## 4. Impact analysis

| Area | Impact |
|---|---|
| SPEC-002 §11 | Nueva regla R23: notificación de sesión, permiso y textos |
| SPEC-003 | Sin cambio: el texto refleja V0/V1/V2 de §6, no altera el stream |
| REQ-004 AC de sesión | Sin cambio de wire ni de video; solo cara visible del servicio |
| Wire / protocolo | Sin cambio: no se añaden mensajes ni campos |
| Permisos | `POST_NOTIFICATIONS` ya estaba declarado en el manifest (ENG §8); se añade su petición en runtime |
| Tests | TEST-0214 nuevo (texto de transmisión vs sesión conectada, canal de sesión) |
| Código | `session/SessionForegroundService`, `session/SessionNotificationSync`, `ui/PairingActivity`, `res/values/strings.xml` |
| Datos / migraciones | Ninguna |

## 5. Options considered

1. **Servicio en primer plano de toda la sesión con notificación que dice si
   transmite (elegida).** Es la única forma admitida por Android de mantener
   viva la transmisión al cambiar de app, y el texto da la señal pedida.
2. Notificación solo mientras llegan frames (arrancar/parar el servicio con el
   stream) — descartada: al ir a segundo plano el stream se detendría justo
   cuando se quiere conservar la sesión.
3. Detach de la notificación en primer plano para ocultarla — descartada: no
   aporta nada al pedido y añade complejidad de ciclo de vida.
4. Do nothing — la notificación sigue sin verse en Android 13+.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pide la señal visible de transmisión en segundo plano;
  el servicio ya existía y solo faltaba el permiso y el texto.
- **Rollout:** misma release; sin migración.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-002 1.14.0, R23 + changelog)
- [x] Dependent documents updated (nota en este CR)
- [x] Tests added/updated (TEST-0214)
- [x] `testing/traceability.md` rows updated (incluida la tabla de historial de CR)
- [x] Indexes (`docs/process/change-requests/_index.md`) updated
- [x] Code change applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado con la petición del owner; código y SPEC aplicados |
| 0.2.0 | 2026-11-26 | Buffy (agente) | Icono pequeño de la notificación: los dos trapecios del logo (`ic_notification`, blanco monocromo) en lugar del icono de touch; el texto de la notificación no cambia |
