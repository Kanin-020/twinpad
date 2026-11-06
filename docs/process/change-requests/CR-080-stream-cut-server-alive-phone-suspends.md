# CR-080 — Corte del streaming con el server vivo: el teléfono suspende su receptor de medios

## 0. Metadatos

- **Fecha:** 2026-11-05
- **Autor:** Buffy (Codebuff) bajo petición del owner (repo SDD).
- **Estado:** ABIERTO — provisorio en evidencia, rama de responsable por cerrar.
- **Área:** Server PC / App Android — camino media UDP (SPEC-003, §14).

## 1. Síntoma

Durante la sesión activa la transmisión (video/audio) se «corta» a la vista del
owner: el teléfono vuelve a la vista de espera («medidor sin conexión», CR-076)
mientras la táctil y la sesión TCP siguen funcionando. El ciclo vuelve a
repetirse al re-emparejar.

## 2. Reglas de owner para asentar la prueba

- Aviso del owner al instante del corte; el registro del server lleva el rastro
  completo desde el re-lanzamiento persistente (`setsid`, fuera del ciclo del
  shell del agente).
- Re-empareje controlado con el token vigente del QR; cada reinicio del server
  invalida la sesión previa (E3 en el registro es el rastro esperado).

## 3. Diagnóstico hecho hasta ahora

1. **Corte 14:12:58 (provisional, logcat del teléfono):** pings TCP cada 5 s
   vivos antes y después; `MediaCodec getRealTimeFps is 0` señala el momento del
   abandono del decodificador. El relay UDP del server sessionalive — la
   salida «Pairing inactivo» es un corte del server viejo, no en reproducción
   con el server persistente.
2. **Registro del server persistente (14:22 → 14:24):** el relay UDP no salió
   de la sesión (cero «udp-relay salida»); solo entra en **park** cada ~30 s
   («sin AU forwardable en 50 ms») y en ambos tramos revisados (fn 479→510,
   fn 1593→1618+) el renglón siguiente a la línea de park ya es `AU fn…` con
   cadencia regular (~30 fps, ts monotónico, IDR cada ~30 frames). El park dura
   menos de un frame desde el punto de vista del server.
3. **Teléfono:** pings `PairingVM` cada 5 s (3.11/13.32 en distintos
   re-lanzamientos) confirman sesión TCP viva; el corte se percibe en el
   teléfono sin que el server suspenda el envío.

## 4. Veredicto final (causa confirmada el 14:28:43)

El corte vive íntegramente del lado de la app: el server despachó AU sin pausa
(fn 9672+ tras el corte) y la sesión TCP respondió cada 5 s, mientras el
decodificador del teléfono paralizó la presentación. El logcat
(`/tmp/media_phone.log`) lo fijó:

- `MediaCodec: AV Sync Warning: delta=527917369306us (content=1114066us vs
  render=527918483372us)` — el salto entre bases de reloj exacto del uptime del
  teléfono (~6 días).
- Inmediatamente después: `getRealTimeFps is 0` — la presentación paralizada.

La causa es la mezcla de bases de reloj en `H264SurfaceDecoder.drainOutput`
(CR-048): la rama diferida pasa `releaseOutputBuffer(index,
lastPresentNs + emaIntervalNs)` con el reloj del teléfono (`System.nanoTime()`,
uptime), mientras la rama inmediata pasa `releaseOutputBuffer(index, true)` y
MediaCodec usa el PTS del contenido (decenas de segundos). Dos renders
consecutivos con bases distintas hacen saltar el reloj de render ±6 días y el
AV Sync del MediaCodec deja de renderizar. Las ramas (A) y (B) quedan
descartadas: el watchdog y la pérdida UDP no intervienen.

## 5. Evidencias

- Registro del server: `/tmp/twinpad_server.log` (parks cada ~30 s con AU
  monotónicas e IDR; cero «udp-relay salida»).
- Logcat del teléfono: `/tmp/media_phone.log` líneas 261–266 (aviso AV Sync
  Warning + fps 0 a las 14:28:43, tag `MediaCodec` del proceso de streaming).
- Pings TCP del teléfono cada 5 s (`PairingVM`) durante y tras el corte.
- Server persistente con `setsid` (pid 196521): su registro no se pierde con
  el shell del agente (lección operativa del día: nohup dentro de SYNC muere;
  la captura del teléfono viva: logcat a `/tmp/media_phone.log`).

## 6. Fix aprobado (lado app, un solo punto)

- En `H264SurfaceDecoder.drainOutput` (CR-048): la rama de presentación
  inmediata pasa `releaseOutputBuffer(index, true)` — cambia a
  `releaseOutputBuffer(index, nowNs)` para que todos los timestamps de
  presentación queden en la base del reloj del teléfono y el AV Sync de
  MediaCodec no reciba saltos de base entre renders consecutivos.
- El frame pacing de CR-048 queda intacto (rama diferida sin cambios, EMA del
  intervalo de llegada, resincronización del ancla) y CR-029 no se toca (nada
  de metadata de fps del wire).
- El corte con el server persistente queda fuera del alcance del CR-079.
- Verificación: gates de Android verdes, APK reinstalado y prueba en vivo del
  owner (sin fps 0 en el logcat del teléfono durante el streaming).
