# Change Request — CR-049 — Latencia de input: TCP_NODELAY en todo el camino y espera por poll en el InputLoop

| | |
|---|---|
| ID | CR-049 |
| Title | Latencia de input Android→server→Cemu (que se sienta como el GamePad real) |
| Type | `Defect fix` |
| Status | `Applied` |
| Author | Twinpad team (agente) |
| Created | 2026-10-31 |
| Decided | 2026-10-31 |
| Decided by | Product owner (conversación; petición directa de reducción de lag) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-004 |
| Sections | DES-004 camino de input (sockets de sesión, InputLoop del plugin) |
| Current version → new version | DES-004 1.12.0 → 1.13.0 |

## 2. Requested change

**Current (al abrir el CR):**

```text
Camino de input: el teléfono envía input_report por un socket sin
TCP_NODELAY (Java lo deja en false); el server reenvía la línea al
adaptador por el socket del plugin SIN TCP_NODELAY (flush sobre TcpStream
no desactiva Nagle: con ACK diferido la línea queda colgada decenas de ms);
el InputLoop de Cemu despéierta con una cadencia fija de 10 ms
(recv NO-bloqueante + sleep(10) SIEMPRE, ambas plataformas: el socket es
NONBLOCK, y en Windows recv flags=0 no bloquea): un reporte que llega
durante el sleep espera hasta el próximo ciclo.
```

**Applied (2026-10-31):**

```text
TCP_NODELAY en los tres tramos del camino de input: socket de sesión del
teléfono (server, ya existía: StreamSink::prepare_for_streaming), socket
de la app (TcpPairingClient: tcpNoDelay=true tras el connect) y socket
del plugin (GameRegistry::register: socket2::set_tcp_nodelay).
InputLoop de Cemu: espera por poll(2ms) SOLO cuando no hay línea
pendiente en el buffer; datos disponibles → drain inmediato (recv
no-bloqueante + drenado de TODAS las líneas, sin cadencia fija). El
sleep(10) del final del loop se elimina. Sin cambio de wire ni estados.
```

## 3. Motivation

El owner pide que los controles se sientan como el GamePad físico de la
Wii U (latencia de input imperceptible). Medición del camino: el tramo
server→Cemu queda a merced de Nagle vs ACK diferido (hasta ~200 ms por
tramo stall clásico), el tramo telefone→server igual (Java default
tcpNoDelay=false), y el InputLoop añade ≤10 ms (media ~5 ms) fijos por
reporte. Ninguno afecta throughput (los marcadores de audio/video
coincidían), solo latencia.

## 4. Impact analysis

| Área | Documents to update | Implements | Version change |
|---|---|---|---|
| Server | DES-004 | NODELAY en socket del plugin | 1.13.0 |
| Android | DES-004 | NODELAY en socket de la app | 1.13.0 |
| Cemu plugin | DES-004 | espera por poll + drain inmediato | 1.13.0 |

- Wire: sin cambio (mismas líneas input_report, misma máquina §6/§10).
- SPEC-002/004/006/007: intactas (no definen opciones de socket ni cadencia
  de lectura del plugin).
- Riesgos: NODELAY aumenta el número de paquetes pequeños (mitigado: el
  destino es localhost/steamboat LAN; el server ya corre NODELAY en el
  tramo hacia el teléfono). El poll de 2 ms mantiene el InputLoop
  reactivo a m_running/desconexiones (los caminos 50 ms de reconexión no
  cambian).

## 5. Implementation notes

- `games.rs`: en `GameRegistry::register`, `socket2::SockRef::from(stream).
  set_tcp_nodelay(true)` antes de insertar el `GameEntry`.
- `TcpPairingClient.kt`: `tcpNoDelay = true` tras el connect (mismo uso
  que `H264MediaClient`, línea 60).
- `TwinpadClient.cpp`: helper `WaitReadable(socket, 2)` (poll/WSAPoll);
  en el InputLoop: poll solo si `m_receiveBuffer` no tiene línea completa;
  eliminado el `sleep(10)` del final de la iteración.

## 6. Verification / decision log

| | |
|---|---|

→ Gate Cemu: `ninja Cemu_release` en verde. Gate Server: `cargo check` en
verde. Gate Android: `:app:detekt` + `:app:compileDebugKotlin` en verde.
La verificación física de latencia (>60 fps Nintendo Land, controles con
respuesta imperceptible) queda al owner.

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-31 | Twinpad team (agente) | Created + applied pending owner physical verification |
