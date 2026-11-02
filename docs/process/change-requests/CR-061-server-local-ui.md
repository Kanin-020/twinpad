# Change Request (CR) — CR-061 — Twinpad

| | |
|---|---|
| ID | CR-061 |
| Title | Interfaz local del Servidor PC: tray (Estado Notifier) + página HTTP en loopback con QR copiable, configs, autoarranque y parada |
| Type | `New behavior` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-01 |
| Decided | 2026-11-01 |
| Decided by | Product owner (solicitud directa en la iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | REQ-001 (implementación), nuevo SPEC-010, nuevo DES-005, nuevo ADR-010 |
| Sections | REQ-001 §10/§11 (asignación), SPEC-010 (todo), ADR-010 (todo) |
| Current version → new version | REQ-001 0.1.0 → 0.2.0 |

## 2. Requested change

**Current:**

```text
REQ-001 no tiene SPEC asignado (§10 «- until assigned»). El Servidor PC
funciona en segundo plano pero su única superficie de gestión es el stdout
del terminal (QR en ASCII, URI copiable solo leyendo ANSI). No hay
indicador gráfico de estado (AC-2), no hay mecanismo de autoarranque
ni forma de detenerlo sin matar el proceso.
```

**Proposed:**

```text
1. El Servidor PC crea una entrada de autoarranque XDG (desktop file en
   ~/.config/autostart) instalada por defecto; conmutador visible en la
   UI local (toggle: instalar/eliminar).
2. Un icono de bandeja (StatusNotifierItem, tray) persiste mientras el
   server corre, con menú: «Abrir interfaz», «Iniciar al arrancar el
   equipo» (casilla), «Detener servidor». Si el entorno no ofrece un host
   de bandeja, el servidor continúa operando (fail-soft) y el URI sigue
   visible en stdout.
3. Una página HTTP servida SOLO en 127.0.0.1:<puerto local> muestra:
   QR de vinculación renderizado, el URI en una casilla copiable,
   el estado del servidor (activo, sesiones activas, juegos compatibles),
   las configs de video vigentes (resolución/bitrate del encoder del
   Plugin) y un botón «Detener servidor».
4. «Detener servidor» finaliza con gracia: ya no se aceptan nuevas
   sesiones y el  proceso sale; los clientes vivos de la app observan el fin de conexión
  por su canal de control (fin OBSERVABLEMENTE equivalente a EOF,
  SPEC-001 §4.3 HB6).
```

## 3. Motivation

Feedback del owner en la iteración 2026-11-01: «Quiero una UI ligera al
server con configuraciones: Parar server, Iniciar server al arranque,
Mostrar QR, un label para copiar y pegar el código directo. La UI solo
muestra configs; el server sigue funcionando en segundo plano y muestra
un tray». Hoy el operador depende del terminal; REQ-001 AC-2 (distinguir
activo/inactivo) y el autoarranque (AC-1) quedaron sin mecanismo. La
página en loopback es accesible desde el propio PC sin exponer nada a la
LAN (NFR-004 preservado).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-001 | yes | Asigna SPEC-010 como implementación | Update (§10/§11) |
| SPEC-001 | no | La página consume el URI vigente vía `TokenStore::ensure_fresh_qr`; sin cambio de wire | none |
| ARCH | no | El tray/UI viven en la capa de presentación del adaptador ejecutable; ADR-010 registra la decisión | ADR-010 nuevo |
| DES-005 | yes | Nuevo diseño que realiza SPEC-010 | create |
| ADR-010 | yes | Nueva decisión (tray + página loopback, sin framework web) | create |
| TEST-0135..0137 | yes | Nuevos tests de integración webui/autostart | create |
| Code | yes | `server/src/webui.rs`, `server/src/tray.rs`, `server/src/autostart.rs`, cableado en `main.rs`; dependencia `ksni` | fino ~1 día |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Ningún mensaje de wire cambia | none |

## 5. Options considered

1. **Tray (ksni) + página HTTP artesanal en loopback** — elegido: sin runtime async ni framework web; dos dependencias pequeñas; el QR se reutiliza del `TokenStore` existente.
2. Página servida también en la LAN — rechazada: expondría el token de pairing y las configs a la red (violación de NFR-004).
3. Solo tray con dialogs nativos — rechazada: no hay toolkit GUI en el server y exigiría dependencias por plataforma.
4. Do nothing — el operador sigue dependiendo del terminal; AC-2 de REQ-001 sin mecanismo.

## 6. Decision

- **Outcome:** Approved
- **Rationale:** Es la superficie mínima que cierra AC-1/AC-2 de REQ-001 sin tocar el wire ni la sesión; loopback-only mantiene el incentivo de seguridad; el fail-soft del tray evita un punto único de fallo en el arranque.
- **Rollout:** Same release.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-010 nuevo, REQ-001 0.2.0)
- [x] Dependent documents updated (DES-005, ADR-010, índices)
- [x] Tests added/updated (TEST-0135, TEST-0136, TEST-0137)
- [x] Traceability rows updated
- [x] Indexes updated (specs, design, decisions, change-requests)
- [x] Code change merged (`server/src/webui.rs`, `tray.rs`, `autostart.rs`, `main.rs`)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Opened |
| 0.2.0 | 2026-11-01 | Buffy (agente) | Applied — implementación, tests y trazabilidad |
