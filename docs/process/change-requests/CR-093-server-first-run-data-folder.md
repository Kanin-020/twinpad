# Change Request (CR) — CR-093 — Twinpad

| | |
|---|---|
| ID | CR-093 |
| Title | Primera ejecución del Servidor PC: preguntar al usuario la carpeta de datos |
| Type | `New behavior` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-11 |
| Decided | 2026-11-11 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-010 (Servidor PC, UI local) |
| Sections | §2 (nueva sección de primera ejecución) |
| Current version → new version | 0.x.y → 0.x.z (pendiente de decisión) |

## 2. Requested change

**Current:**

```text
El Servidor PC no pregunta al usuario dónde guardar sus datos; usa una
ubicación fija definida en código.
```

**Proposed:**

```text
Al abrir el Servidor PC por primera vez (no existe configuración previa),
la interfaz local pregunta al usuario en qué carpeta guardará sus datos
locales. El usuario puede aceptar la ruta sugerida o elegir otra. La
elección se persiste y se reutiliza en las siguientes ejecuciones. Mientras
no haya elección, el servidor no escribe datos fuera de la ruta sugerida.
```

## 3. Motivation

Pedido del owner (2026-11-11) durante la prueba del plugin de Decky en
Fedora: el servidor debe permitir que el usuario decida dónde guarda sus
datos la primera vez, en lugar de imponer una ruta fija. Hoy SPEC-010 no
define esta pregunta, por lo que el comportamiento requiere CR.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-XXX | yes | Requisito de configurabilidad local del servidor | update (pendiente de revisar) |
| SPEC-010 | yes | Nueva sección de primera ejecución y persistencia de la ruta | update al aprobar |
| ARCH | no | No cambia límites de componentes | none |
| DES-005 | yes | Nueva pantalla/diálogo de primera ejecución | update al aprobar |
| TEST-XXX | yes | Tests de primera ejecución y persistencia | update / nuevos TEST IDs |
| Code | yes | `server/src/` (persistencia de la ruta) y `server/ui/` (diálogo) | PR de implementación |
| Data / migrations | yes | Nuevo fichero de configuración con la ruta elegida | migration plan: ninguno (instalaciones existentes sin fichero usan la ruta actual) |
| Integrations / contracts | no | El protocolo del plugin Decky no cambia | none |

## 5. Options considered

1. Pregunta en la primera ejecución con ruta sugerida y persistencia — mejor equilibrio entre control del usuario y simplicidad.
2. Ruta fija con opción de cambio en Ajustes — no cumple lo pedido de preguntar al abrir por primera vez.
3. Do nothing — el usuario no puede elegir la carpeta de datos.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El owner pide que, al abrir el servidor por primera vez, el usuario elija la carpeta de datos. La interfaz local debe mostrar la ubicación actual y permitir cambiarla.
- **Rollout:** misma versión; instalaciones existentes sin configuración usan la ruta actual.

## 7. Execution checklist

- [ ] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [ ] Dependent documents updated (from impact table)
- [ ] Tests added/updated (TEST IDs)
- [ ] `testing/traceability.md` rows updated (including CR history table)
- [ ] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-11 | Buffy (agente) | Opened |
| 0.2.0 | 2026-11-11 | Buffy (agente) | Applied: carpeta de datos (`server/src/data_dir.rs`), panel «Carpeta de datos» con cambio vía `POST /data-dir` (cabecera de acción S1) |
| 0.3.0 | 2026-11-11 | Buffy (agente) | Asistente de instalación: bloque de bienvenida en la UI local con la ruta sugerida, visible solo sin elección previa; «Guardar y continuar» usa el mismo `POST /data-dir` |
| 0.4.0 | 2026-11-11 | Buffy (agente) | Asistente como pop-up modal: «Elegir carpeta…» abre el selector del escritorio (`zenity`) vía `POST /data-dir/pick` (S1); aparece sin elección guardada o si la carpeta elegida no existe |
| 0.5.0 | 2026-11-11 | Buffy (agente) | Pop-up de primera vez: «Aceptar» guarda la ruta sugerida por defecto; «Elegir carpeta…» pasa las variables de sesión gráfica (`DISPLAY`, `WAYLAND_DISPLAY`, `DBUS_SESSION_BUS_ADDRESS`, `XAUTHORITY`) a `zenity` y muestra el error si el selector no abre |
| 0.6.0 | 2026-11-11 | Buffy (agente) | Corrige: lectura del cuerpo de `POST /data-dir` hasta `Content-Length` (antes «ruta no absoluta»); `zenity` por ruta absoluta (el plugin lanza el servidor con PATH reducido); botones del pop-up con variantes `btn--primary`/`btn--ghost` y estilo del campo `.panel__input` |
