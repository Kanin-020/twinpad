# Change Request (CR) — CR-063 — Twinpad

> ⚠️ TEMPLATE — the ONLY mechanism to change an `Approved` document. One CR per logical change.

> **What this is:** a controlled, reviewable change to an approved artifact (REQ, SPEC, ARCH, DES, guidelines, strategy). It records what, why, impact and decision.
> **What it is NOT:** a bug report (use the issue tracker) or a discussion thread — keep it decidable.

| | |
|---|---|
| ID | CR-063 |
| Title | Interfaz local en ventana web del sistema (modo app del navegador del PC) en lugar de pestaña de navegador |
| Type | `Behavior change / New behavior` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-02 |
| Decided | 2026-11-02 |
| Decided by | Product owner (solicitud directa en sesión) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-010`, `DES-005` |
| Sections | SPEC-010 §W3; DES-005 §2, §6 (nota), §12 |
| Current version → new version | SPEC-010 `0.1.0 → 0.2.0`; DES-005 `0.1.0 → 0.2.0` |

## 2. Requested change

**Current:**

```text
SPEC-010 W3: «la acción de menú del tray abre la página de gestión (solicitud
HTTP GET al puerto local, desde el propio PC)», implementada con `xdg-open`
→ el navegador predeterminado abre la página como pestaña completa.
```

**Proposed:**

```text
SPEC-010 W3: «la acción de menú del tray abre la página de gestión (solicitud
HTTP GET al puerto local, desde el propio PC) en una VENTANA WEB DEL SISTEMA:
modo app del navegador del PC (navegador Blink con `--app=URL`: ventana propia,
sin barra de direcciones ni pestañas). Si ningún navegador de modo app está
disponible, fallback al navegador predeterminado (comportamiento CR-061).
La superficie HTTP del servidor no cambia».
```

## 3. Motivation

- Feedback del owner: la UI de gestión «se abre en el navegador» y eso se
  siente como pestaña ajena a la app; quiere una ventana del sistema aunque
  el contenido siga siendo HTML servido en loopback (des-web-app, no nativa).
- Evidencia: `server/src/tray.rs` `open_in_browser()` usa `xdg-open`
  únicamente (CR-061, W3).
- Alineado con la filosofía del repo: **sin dependencias nuevas** ni runtime
  webview (no `wry`/WebKit: rompería el vendoring y añadiría requisitos de
  sistema); el flag `--app` está soportado por Chrome, Edge, Brave y
  Chromium en Linux/Windows/Fedora.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-001 | no | la interfaz local no contrato cambia (sigue en 127.0.0.1:8430) | none |
| SPEC-010 | yes | W3 claifica la forma de apertura | update §W3 1.1.0 |
| SPEC-XXX (otros) | no | — | none |
| ARCH | no | un módulo `tray` existente hace la apertura | none |
| DES-005 | yes | §2 (modo de apertura), §6 nota, roster TEST | update 0.2.0 |
| TEST-0141 | new | argv de la ventana app (`--app=URL` único) | add row |
| Code | yes | `tray.rs`: `open_in_browser()` → `open_app_window()` con canasta de candidatos y fallback | 1 PR ~40 líneas |
| Data / migrations | no | — | none |
| Integrations / contracts | no | URL y puerto intactos | none |

## 5. Options considered

1. **Modo app del navegador del PC (`--app=URL`, propuesto)** — ventana de
   sistema real del escritorio, sin dependencias nuevas ni requisitos de
   sistema; mantiene la página HTTP loopback intacta (W6/W8 sin tocar).
2. Do nothing — la página sigue abriéndose como pestaña completa.
3. `wry`+`tao` (webview nativo embebido) — descendido: añade dependencias
   pesadas y requisitos de sistema (webkit2gtk) que romperían el vendoring
   del workspace para un beneficio marginal.

## 6. Decision

- **Outcome:** `Approved (owner-requested)`
- **Rationale:** el owner pidió una ventana del sistema; el modo app
  satisface la necesidad (ventana propia sin navegador chrome) con el
  menor costo y sin tocar el protocolo ni la página.
- **Rollout:** same release.

## 7. Execution checklist

- [x] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [x] Dependent documents updated (from impact table)
- [x] Tests added/updated (TEST-0141)
- [x] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-02 | Buffy (agente) | Opened, decidido y aplicado en la misma iteración (owner-requested) |
