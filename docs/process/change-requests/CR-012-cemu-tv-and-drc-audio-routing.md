# Change Request (CR) — CR-012 — Twinpad

| | |
|---|---|
| ID | CR-012 |
| Title | Enrutamiento separado de audio TV en PC y GamePad/DRC en Android |
| Type | `Clarification / Behavior change` |
| Status | `Approved` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-19 |
| Decided | 2026-10-19 |
| Decided by | Product owner (user) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-005, SPEC-006, ARCH, DES-004 |
| Sections | SPEC-005 §3/§5/§7/§12; SPEC-006 §4/§7; ARCH §4/§6; DES-004 §1/§4/§6 |
| Current version → new version | SPEC-005 1.2.0 → 1.3.0; SPEC-006 1.8.0 → 1.9.0; ARCH 0.8.0 → 0.9.0; DES-004 0.6.0 → 0.7.0 |

## 2. Requested change

**Current:**

```text
SPEC-005 §3/§5 define un único Stream de audio PCM del Plugin seleccionado
que la app reproduce en Android. SPEC-006 §7 dice que Cemu publica PCM DRC,
pero no fija normativamente que el audio TV permanezca en la salida local de
Cemu/PC ni que el PCM encaminado a Android sea exclusivamente el DRC/GamePad.
```

**Proposed:**

```text
El audio de TV del juego permanece en la salida local de Cemu/PC y no se
captura, mezcla ni retransmite a Android. El audio GamePad/DRC se publica como
la fuente PCM del Plugin Cemu seleccionado y se reproduce en Android. Los dos
ruteos son independientes: ausencia o fallo del stream Android no silencia
el audio TV local, y audio TV local no sustituye ni mezcla el stream DRC.
El contrato de audio de sesión sigue siendo un solo PCM stream del Plugin
seleccionado; este cambio no introduce un segundo stream Android ni campos
wire nuevos.
```

## 3. Motivation

El owner precisa que el audio debe dividirse por destino: TV → PC y GamePad/DRC → Android. La implementación Cemu ya posee salidas separadas `g_tvAudio`/`g_padAudio` y publica PCM DRC al IPC Twinpad; la configuración local actual tiene `TVVolume=50` y `PadVolume=0`. Sin embargo, la prueba de dispositivo disponible no registra `audio_start`/chunks. La propuesta se aprobó explícitamente el 2026-10-19; permanece pendiente la verificación física audible de ambas rutas.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-005 / REQ-006 | Por revisar | Aclarar el destino de las dos salidas del adaptador Cemu | Confirmar cobertura de los requisitos existentes; elevar cambio si contradicen el ruteo solicitado |
| SPEC-005 | Sí | Precisar que el stream PCM hacia Android es GamePad/DRC para Cemu; el audio TV local no se captura ni retransmite | Actualizados §8.4/R6/§16 (TEST-062) |
| SPEC-006 | Sí | Precisar publicación de fuente DRC y mantener audio TV en Cemu/PC | Actualizados §7/R11/§16 (TEST-062) |
| ARCH | Sí | Reflejar los dos destinos físicos en límites de integración Cemu | Actualizado ARCH §4 |
| DES-004 | Sí | Documentar ambos ruteos y la independencia de su ciclo de vida | Actualizados §1/§4/§6/§7/§12; TEST-062 físico sigue pendiente |
| TEST | Sí | Verificar audio TV local y audio DRC en Android de forma independiente | Reservado TEST-062 y registrado en trazabilidad; ejecución física pendiente |
| Code | Sí | DRC se publica como PCM estéreo intercalado, preservando el callback de salida local GamePad; TV conserva callback/salida propios | Corregido `AIInitDRCDMA`; Cemu build verificado; audio físico pendiente |
| Data / migrations | No | No hay persistencia nueva | Ninguna |
| Integrations / contracts | Sin cambio wire | Un solo `audio_start/chunk/stop` PCM de la fuente seleccionada; no se añaden mensajes/campos | Conservado; solo se retransmite el PCM DRC del Plugin Cemu |

## 5. Options considered

1. Formalizar TV→PC y DRC→Android como rutas independientes, sin cambiar el wire — corresponde a la intención aclarada por el owner y conserva la separación ya prevista por Cemu.
2. Do nothing — deja ambiguo si el PCM de Android debe ser TV, DRC o una mezcla, e impide aceptar la prueba actual que no mide audio.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el Product owner confirmó y aprobó explícitamente el 2026-10-19 el destino TV → salida local del PC y GamePad/DRC → Android. Se mantienen rutas y controles de volumen independientes; la interrupción del transporte Android no debe modificar la salida TV local. No se cambia el esquema wire ni se mezcla TV con DRC.
- **Rollout:** aplicar en esta iteración; aceptación audible en Cemu/Android queda como gate de banco físico.

## 7. Execution checklist

- [x] Aprobar el ruteo propuesto (Product owner, 2026-10-19).
- [x] Revisar REQ-005/REQ-006: el cambio clarifica la ruta específica del adaptador Cemu y mantiene un stream PCM Android sin cambios wire.
- [x] Actualizar SPEC-005/SPEC-006/ARCH/DES-004 conforme a la autoridad temporal de este CR.
- [x] Reservar TEST ID de banco, añadir verificación de audio independiente y registrar trazabilidad.
- [ ] Ejecutar prueba física: TV audible en PC; DRC audible en Android; verificar que las rutas no se mezclan.
- [x] Revisar y limitar la corrección Cemu al callback DRC/IPC, preservando controles de volumen separados.
- [x] Actualizar SPEC-005, SPEC-006, ARCH y DES-004, y registrar TEST-062 en trazabilidad.
- [x] Compilar el target Cemu `CemuBin` con el callback DRC corregido.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-19 | Product owner + Twinpad team | Propuesta abierta para audio TV local y GamePad/DRC en Android |
| 0.2.0 | 2026-10-19 | Product owner + Twinpad team | Owner aprueba TV local en PC y DRC en Android; wire sin cambios, ruteos y volúmenes independientes |
