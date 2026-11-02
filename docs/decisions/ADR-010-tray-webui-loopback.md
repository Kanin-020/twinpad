# ADR-010 — Interfaz local del Servidor PC: tray StatusNotifier + página HTTP en loopback, sin framework web

## Status

Accepted (2026-11-01, CR-061)

## Context

REQ-001 exige un Servidor PC persistente con estado visible (AC-2) y
autoarranque (AC-1); CR-061 añade la necesidad de parar el servidor, ver
el QR copiable y las configs desde el PC sin terminal. El Servidor PC es
un proceso Rust bloqueante (std-only, sin runtime async) y la superficie
de gestión actual es el stdout.

## Decision

1. **Bandeja del sistema vía StatusNotifierItem** (protocolo de D-Bus)
   con la biblioteca `ksni`: es el estándar de facto en KDE/GNOME (con
   extensiones/bibliotecas de appindicator) y es puro Rust, sin toolkit
   GUI. Menú: Abrir interfaz, Mostrar URI, Iniciar al arrancar el
   equipo (casilla), Detener servidor.
2. **Página de gestión HTTP artesanal** (std::net, sin framework web, sin
   runtime async) servida exclusivamente en `127.0.0.1:8430`: QR SVG
   generado a partir de la matriz ya existente de `qr_render`, URI
   copiable, estado del server y configs. Bind estricto a loopback
   (NFR-004).
3. **Autoarranque por desktop entry XDG** (`~/.config/autostart/`)
   instalada por defecto, conmutada desde el tray. (Open: systemd user
   unit como alternativa futura — DES-005 §14.)
4. **Parada con gracia** mediante `Arc<AtomicBool>` compartido y
   accept-loop con `poll` de 500 ms; sin señales POSIX nuevas.

Se descartan: framework web pesado (axum/actix) por desviarse del
contrato std-only del server; dialogs GUI nativos por falta de toolkit;
solo-tray sin página, por la necesidad de un QR copiable de un click.

## Consequences

- Dos dependencias nuevas en `server`: `ksni` (y su `zbus` transitivo).
- La página nunca se sirve fuera de loopback; cualquier intento externo
  muere en el bind (sin código nuevo de filtrado en caliente).
- El tray es mejor-esfuerzo: en entornos sin host StatusNotifier el
  server sigue siendo plenamente funcional (stdout + página).
- Sin efectos sobre el wire ni sobre SPEC-001–SPEC-009.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Initial (CR-061) |
