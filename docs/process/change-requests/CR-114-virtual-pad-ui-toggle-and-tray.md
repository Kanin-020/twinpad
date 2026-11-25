# Change Request (CR) — CR-114 — Twinpad

| | |
|---|---|
| ID | CR-114 |
| Title | Mando virtual gobernable desde la página local (on/off persistente) y bandeja que espera/reaparece con el host SNI |
| Type | Feature (servidor) + fix de fiabilidad del tray |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-25 |
| Decided | 2026-11-25 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-010 (§4, §5 W1/W7/W10, §6, §7, §9), SPEC-004 §8.4 |
| Sections | SPEC-010 §5 W10 (nueva), W1/W7 (precisión); SPEC-004 §8.4 (trigger/resultado) |
| Current version → new version | SPEC-010 0.4.1 → 0.5.0; SPEC-004 1.13.0 → 1.14.0 |

## 2. Requested change

**Current:** el mando virtual DualSense de CR-113 solo se activa por variable de
entorno al arrancar (`TWINPAD_VIRTUAL_PAD`); la página local no lo muestra ni lo
puede conmutar. Además, el tray se registra UNA sola vez: si el servidor arranca
antes que el host de bandeja (caso típico del autostart de sesión, antes de que
cargue la extensión AppIndicator de GNOME) o si el panel se reinicia, el icono
no aparece aunque el servidor siga vivo y trabajando.

**Proposed:**

1. **Página local (SPEC-010 W10):** un panel «Mando virtual» con estado
   (Activado/Desactivado) y botón que llama a `POST /virtual-pad`
   (`enabled=1|0`, con cabecera `X-Twinpad-Action`, misma regla S1 que
   `/autostart`). El servidor enciende o apaga el HID en caliente y **recuerda la
   elección** en `<carpeta de datos>/virtual-pad` (`on`/`off`); la variable de
   entorno pasa a ser solo el valor por defecto cuando no hay preferencia
   guardada. Un fallo al crear `/dev/uhid` responde 500 con el motivo, no cambia
   el estado y la nota del panel lo muestra (E9).
2. **Menú del tray (revisión 0.2.0, a petición del owner):** el menú del
   tray añade «Mando virtual (DualSense)» con marca de verificación y el mismo
   comportamiento que la página (misma preferencia, mismo HID); el estado se
   lee del servidor en cada apertura del menú, no de una copia local.
3. **Tray (W1/W7 precisados):** el servicio de bandeja se crea con
   `assume_sni_available`: la ausencia del `org.kde.StatusNotifierWatcher` no es
   un fallo, el servicio queda vivo y `ksni` registra el icono en cuanto el
   watcher aparece (autostart temprano, panel reiniciado). Los fallos duros
   (sin bus de sesión, p. ej. sandbox) se reintentan 5 veces cada 2 s y, si
   persisten, el log dice explícitamente que la página local sigue disponible.

Texto normativo añadido a SPEC-010:

- **W10 — Mando virtual on/off desde la página (CR-114):** la página muestra
  «Mando virtual (DualSense): Activado/Desactivado» con un botón que conmuta por
  `POST /virtual-pad` (`enabled=1|0`, cabecera `X-Twinpad-Action`). Encender
  crea el HID de CR-113; apagar lo destruye de inmediato. La elección se guarda
  en la carpeta de datos y manda sobre `TWINPAD_VIRTUAL_PAD` en el siguiente
  arranque. Si `/dev/uhid` no está disponible, la respuesta es 500 con el
  motivo, el estado no cambia y la nota del panel lo muestra (E9). El menú
  del tray expone el mismo interruptor (revisión 0.2.0).

Precisión a W1/W7: el icono puede aparecer después que el servidor (el host de
bandeja no existe todavía) y debe reaparecer si el host se reinicia; la ausencia
del host no apaga el servicio de bandeja ni el servidor.

## 3. Motivation

El owner pide gestionar el mando desde la interfaz en vez de una variable de
entorno y reporta que a veces el servidor corre de fondo sin icono de bandeja.
Las dos causas observables son el arranque del tray antes que el host SNI y la
falta de reintento del registro.

## 4. Impact analysis

| Area | Impact |
|---|---|
| SPEC-004 §8.4 | El disparador pasa a ser «preferencia guardada (página local) o, si no hay, `TWINPAD_VIRTUAL_PAD`»; sin cambio de reporte ni de mapeo. |
| SPEC-010 | W10 nueva; W1/W7 precisados; §4/§6/§7/§9 extendidos (toggle en página y tray, `POST /virtual-pad`, E9, AC-7). |
| REQ-001 | Sin cambio: la superficie local sigue siendo loopback y el fail-soft del tray se mantiene. |
| NFR-004 | Sin dependencias nuevas ni puertos nuevos; un fichero pequeño en la carpeta de datos. |
| Compatibilidad | Sin preferencia guardada el comportamiento es el de CR-113 (env var); la variable sigue funcionando. |
| Riesgo | El reinicio del panel SNI re-registra el icono (ksni), no se probó aquí con el panel caído; verificación manual en escritorio real pendiente. |

## 5. Options considered

1. Solo variable de entorno — descartada por el owner: quiere gestionarlo desde
   la UI.
2. Toggle en la página + preferencia persistida (elegida): misma superficie
   local ya existente, sin tocar la app ni añadir puertos.
3. Toggle también en el menú del tray — añadida en la revisión 0.2.0 a
   petición del owner, reutilizando el manager y la preferencia de la página.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** gestión desde la UI local con persistencia, y tray que no se
  rinde ante un host SNI tardío.
- **Rollout:** misma release; sin migración (si no hay fichero, manda el env).

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-010 0.5.0, SPEC-004 1.14.0)
- [x] Dependent documents updated
- [x] Tests added/updated (TEST-0204/0205)
- [x] Verificación manual del tray con watcher ausente/tardío/reiniciado
      (§8)
- [x] `testing/traceability.md` rows updated
- [x] Indexes (`_index.md`) updated
- [x] Code: `server/src/virtual_pad.rs` (manager), `server/src/webui.rs`,
      `server/ui/index.html`, `server/src/tray.rs`, cableado en `main.rs`

## 8. Verification notes

- End-to-end en Linux real con `/dev/uhid`: `POST /virtual-pad` 204 y el
  `hidraw` aparece/desaparece; la preferencia sobrevive a un reinicio; con
  `/dev/uhid` simulado inaccesible, 500 y estado intacto (TEST-0204/0205).
- Tray en sesión GNOME real: el ítem SNI se registra y el menú DBusMenu
  contiene «Mando virtual (DualSense)»; pulsarlo enciende/apaga el HID y
  actualiza la marca (`toggle-state`) y la página.
- Ciclo de vida del watcher (equivalente a reiniciar el panel, sin cerrar la
  sesión Wayland): en un bus de sesión aislado, el server arranca sin
  `org.kde.StatusNotifierWatcher`, sigue sirviendo la página y registra el
  icono cuando el watcher aparece; al caer el watcher vuelve a
  `watcher_offline` sin morir y se **re-registra** al reaparecer.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-25 | Buffy (agente) | Abierto y aprobado por el owner en la misma sesión; implementado |
| 0.2.0 | 2026-11-25 | Buffy (agente) | A petición del owner: el interruptor del mando virtual también está en el menú del tray (SPEC-010 W10, §4); E9 como identificador del error |
