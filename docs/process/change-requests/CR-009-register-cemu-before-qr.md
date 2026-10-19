# Change Request — CR-009 — Registrar Cemu antes de mostrar el QR de pairing

| | |
|---|---|
| ID | CR-009 |
| Title | Registrar Cemu antes de mostrar el QR de pairing |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Twinpad team (solicitada por el Product owner) |
| Created | 2026-10-18 |
| Decided | 2026-10-18 |
| Decided by | Product owner (usuario) |

> Aprobación explícita del Product owner: Cemu debe registrarse antes de presentar el QR; al cancelar el diálogo QR, la solicitud manual se cancela.

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006, ARCH, DES-004 |
| Sections | SPEC-006 §7/§9/§11/§13/§16; ARCH §3/§4/§6; DES-004 §1/§4/§6/§7/§8/§12 |
| Current version → new version | SPEC-006 1.6.0 → 1.7.0; ARCH 0.6.0 → 0.7.0; DES-004 0.4.0 → 0.5.0 |

## 2. Requested change

**Current — SPEC-006 §7 / CR-007:**

```text
Cemu obtiene y muestra el QR vigente antes de solicitar la conexión del adaptador.
```

**Proposed — approved:**

```text
- Al pulsar “Iniciar conexión”, Cemu obtiene el QR y solicita primero el anuncio del adaptador Cemu al Servidor PC.
- El Servidor PC incluye en la respuesta local del QR un booleano `cemu_live` que confirma que el anuncio `game_id=cemu` está registrado.
- Cemu muestra el QR solo después de confirmar `cemu_live=true`; Android que escanee ese QR encuentra la fuente DRC viva desde el inicio de la sesión.
- Si el diálogo se cancela, Cemu cancela la solicitud manual y cierra las conexiones localhost; auto-connect configurado no se cancela.
- La captura manual de escritorio/monitor queda reservada a la ruta de pruebas (`TWINPAD_SOURCE=desktop`); no se inicia como fallback para una sesión Cemu.
```

## 3. Motivation

En Linux, Android puede escanear el QR y completar Pairing mientras el diálogo de Cemu está abierto. Cemu solo pedía el anuncio IPC después de cerrar ese diálogo; por tanto, el servidor iniciaba `DesktopGrabber::new()` para la sesión y el portal ScreenCast pedía seleccionar monitor. Además, esa sesión quedaba ligada al stream de escritorio incluso cuando Cemu se anunciaba después. Se requiere ordenar el anuncio antes de exponer el QR.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-006 | Yes | Se invierte el orden anuncio/QR y se especifica confirmación del registro y cancelación | Actualizar flujo, regla y ejemplos |
| ARCH | Yes | El endpoint local del QR informa si Cemu está registrado | Actualizar interfaz y límite |
| DES-004 | Yes | Cemu solicita el adaptador antes de abrir el diálogo QR; cancelación retira la solicitud manual | Actualizar secuencia y diseño |
| TEST-050 | Yes | Cubrir el estado Cemu vivo incluido en la respuesta QR | Registrar e implementar |
| Code | Yes | Campo `cemu_live`, polling pre-QR y cancelación limpia | Implementar en server y Cemu |
| Data / migrations | No | Estado en memoria | None |
| Integrations / contracts | Yes | Extiende la respuesta privada loopback del QR; no cambia pairing ni wire Android | Mantener loopback |

## 5. Options considered

1. Registrar Cemu antes del QR y confirmar el registro — elimina la carrera y preserva el QR como puerta de entrada al pairing.
2. Mantener QR antes del anuncio — deja que el primer Pairing inicie ScreenCast de escritorio y reproduce el defecto.
3. Suprimir permanentemente la captura de escritorio — evitaría el diálogo, pero quitaría el camino genérico útil para pruebas.

## 6. Decision

- **Outcome:** `Approved` por el Product owner el 2026-10-18.
- **Rationale:** confirmar el registro Cemu antes de exponer el QR asegura que el primer stream de la sesión use la fuente DRC, manteniendo la captura desktop únicamente como opción explícita de pruebas.
- **Rollout:** versión experimental actual; sin cambio en el protocolo Android ni en el QR canónico.

## 7. Execution checklist

- [x] SPEC-006, ARCH y DES-004 actualizados
- [x] TEST-050 registrado y cubierto
- [x] Cemu se anuncia y espera confirmación antes de presentar QR
- [x] Cancelar el diálogo desactiva solo la solicitud manual
- [x] Captura desktop solo puede iniciarse explícitamente para pruebas
- [ ] Build Cemu y gates Rust
- [ ] Smoke con Pairing durante el diálogo QR; requiere banco Android/Cemu

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-18 | Product owner + Twinpad team | Aprobado registrar Cemu antes de mostrar QR para eliminar el selector de monitor en Pairing Cemu |
