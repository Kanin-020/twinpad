# Informe técnico — Cemu DRC, GamePad y objetivo de 60 fps

Fecha: 2026-10-19
Alcance: revisión del código/artefactos existentes y microbenchmark local; no es una prueba de Cemu + Android físico.

## Estado de “Separate GamePad View”

La captura Twinpad está conectada a la copia del color buffer marcada como DRC (`RENDER_TARGET_DRC`) dentro de `LatteRenderTarget_itHLECopyColorBufferToScanBuffer`. Con la vista separada cerrada, actualiza la textura y llama al readback Twinpad; la ventana separada solo atiende la visualización local. Por ello, esa vista **no es requisito** para transmitir el DRC. Esta ruta ya está cubierta por la decisión CR-008 y la regla SPEC-006 R6. Falta un E2E físico reciente para comprobarlo en un título real con dicha ventana cerrada.

## Medición disponible

- E2E TEST-057 existente (`artifacts/test-results/device-e2e/20261019T054326Z-577198/device.log`): DRC 1920×1080, 7 frames progresivos en 2001 ms; recepción 3.77 fps y presentación 3.73 fps, 3747.5 kbps, cero gaps/rechazos.
- Otra corrida E2E registrada: cerca de 0.98 fps a 854×480.
- Microbenchmark ejecutado localmente: `cargo run --release -p twinpad-server --example bench_frame`.
  - Escala 1920×1080 RGBA a 1280×720 RGB: 2.72 ms/frame (~368 fps).
  - JPEG q80 peor caso pseudo-ruido 720p: 17.86 ms/frame (~56 fps).
  - Escala + JPEG peor caso: 20.41 ms/frame (~49 fps).
  - JPEG promedio: 673,736 B/frame (fixture de ruido artificial, deliberadamente adverso).

Los números son de esta máquina/fixture y **no prueban** rendimiento en hardware objetivo. En particular, frame completo JPEG repetido a 60 fps necesitaría superar ese microbenchmark, y tiles cambian sustancialmente el coste/bitrate según el contenido.

## Ruta que falta optimizar/medir

1. **Cemu readback/GPU→CPU:** single-flight: hay un único buffer Vulkan; si su command buffer no terminó, la siguiente llamada regresa y no encola otro readback. Esto serializa captura y consume una oportunidad de frame. Medir tiempo de copia y latencia de finalización en GPU.
2. **Cemu CPU→IPC:** cada framebuffer se convierte a Base64URL y se inserta en JSON NDJSON; el volumen raw RGB se expande ~33% antes de copiar por TCP localhost. TEST-057 no mide capture, conversión de formato o write. Reemplacé el encoder byte-a-byte (push_back por cada carácter) por salida preasignada y escritura directa; TEST-065 verifica casos de borde/formato. Microbenchmark aislado repetido con bytes pseudoaleatorios: 720p 15.61→2.47 ms (6.31×); 1080p 34.68→5.85 ms (5.93×). Es una medición de CPU para Base64 solamente, no de la aplicación de Cemu en ejecución ni una predicción de fps total.
3. **Servidor PC:** recibe RGB8, calcula diffs y codifica JPEG q80 (también en `run_selected_game_stream`); el ejemplo local mide encoding 720p adverso a 17.86 ms, >16.67 ms/frame a 60 fps. Esto debe optimizarse/perfilarse después de la serialización y con una imagen real: frame entero ruidoso es un peor caso no representativo de patches.
4. **LAN y Android:** TEST-057 da un flujo de ~3.75 Mbps de salida; no parece evidencia suficiente para culpar a LAN. Falta medir intervalos/timestamps de captura, `game_frame`, salida server, decode y presentación Android; tampoco tenemos tasa real de framebuffer DRC previo al server.
5. **Cadencia Cemu:** el límite se configura a 30/60, pero esa tasa es objetivo. La captura Vulkan tiene un solo vuelo; si el command buffer no termina se descarta la siguiente oportunidad. No se ha separado la tasa de render DRC de fps realmente encolados/enviados.

La prueba con el título ya abierto (`Lunch.rpx`) muestra la ruta real DRC activa; “Separate GamePad View” estaba habilitada en la configuración de Cemu, así que esa corrida no demuestra el comportamiento cerrado de la ventana. El Cemu activo es anterior al relink (`/proc/<pid>/exe` aparece como deleted); la build nueva tiene timestamp 2026-10-19 17:54 y contiene el símbolo `TwinpadClient_base64url_test`. Un arranque debug con `--help` confirma CLI, pero omite la inicialización GUI/tests; dos intentos de GUI aislada (display actual y Xvfb) llegaron al inicio del emulador pero se quedaron sin completar en 45/60 s, así que no marco TEST-065 como ejecutado. No hubo cambio ni cierre del Cemu abierto ni de sus datos. El selector Android puede solicitar 60, eso no demuestra tasa efectiva.

## Prioridad recomendada para llegar a 60 fps

1. Correr TEST-057 como corrida base controlada con la build nueva: vista separada cerrada, mismo título y resolución, solicitando 60 fps; registrar tasa del callback DRC, readback encolado/completado, frames IPC, server y Android. La sesión visible mantiene cargado el ejecutable anterior; el relaunch en build nueva y el cambio de preferencia/ventana deben hacerse con el título disponible y la configuración preservada.
2. Comparar Base64 optimizado con esta build en el mismo escenario. La medición aislada ya predice ≈5.9× menos CPU dedicado a Base64 para RGB8 1080p, no 5.9× de throughput total. Si los frames IPC suben pero Android no, perfilar `run_selected_game_stream` JPEG/diff; el encoder a q80 puede superar 16.67 ms en su fixture adverso.
3. Si el readback sigue siendo menos de 60/s, hacer un anillo Vulkan de 2–3 slots con recursos y fencing por slot, y descartar el más antiguo sin sincronización bloqueante. Requiere instrumentación por slot, revisión de layouts/vida útil GPU y validación con validation layers antes de aceptarlo.
4. Si el server JPEG es el siguiente límite, optimizar RGB→diff→JPEG en el servidor; el wire existente (`game_frame` RGB8 y stream `jpeg_tiles`) puede conservarse y no es necesario introducir protocolo nuevo aún. Solo evaluar JPEG en el IPC Cemu con CR/SPEC si datos medidos justifican cruzar el límite actual.
5. Ejecutar E2E sostenido: fuente ≥60 frames nuevos/s, pipeline de cada etapa dentro de 16.67 ms p95 sin colas antiguas, then medir NFR-001 (≤60 ms captura-visible), NFR-005 y A/V ±150 ms.

Conclusión: “Separate GamePad View” no es requisito de código: el callback se ejecuta en el camino del render target DRC y actualiza el recurso incluso si la vista local está cerrada; falta la demostración física con ventana cerrada. El selector de Android configura 60 fps, no los garantiza. Medición E2E previa: 3.77 fps recibidos / 3.73 presentados. La optimización Base64URL redujo el microbenchmark aislado ≈5.9×, pero el nuevo binario no se ha probado con el título; no se afirma mejora end-to-end ni 60 fps. Build debug y relink Cemu terminaron bien; las pruebas Rust fmt/Clippy/workspace pasaron. Queda TEST-065 runtime y TEST-057 E2E controlado. El cambio no altera formato de IPC ni SPEC-006.
