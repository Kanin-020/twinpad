# Change Request (CR) — CR-094 — Twinpad

| | |
|---|---|
| ID | CR-094 |
| Title | Vinculación Android–Servidor persistente y desconexión de dispositivo |
| Type | `New behavior` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-11 |
| Decided | 2026-11-11 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-001 (emparejamiento y sesión), REQ-002 (AC), SPEC-002 (pantalla de emparejamiento), SPEC-010 (UI local) |
| Sections | SPEC-001 R2, §8.2 / S4; REQ-002 AC-4; SPEC-002 estados U4/U6 y botón de desconexión; SPEC-010 lista de dispositivos |
| Current version → new version | pendiente de decisión (se fija al aplicar) |

## 2. Requested change

**Current:**

```text
- Cada token se consume en un único Handshake exitoso (R2); re-escanear el
  QR da E3.
- Si se corta la conexión, la ligadura se retiene 60 000 ms (RETENTION_MS);
  pasado ese tiempo el servidor la destruye y la app pide un QR nuevo.
- Los tokens y el registro de sesiones viven solo en memoria del servidor.
```

**Proposed:**

```text
1. Tras un Handshake exitoso, el Servidor PC registra el dispositivo
   (identificador, nombre, clave de sesión) en un almacén persistente cifrado
   dentro de la carpeta de datos (CR-093).
2. Un dispositivo registrado reanuda la sesión con `pair_resume` al abrir la
   app o al reiniciar el servidor, sin escanear un QR nuevo. Para dispositivos
   registrados no aplica el límite de 60 000 ms de retención; el servidor
   acepta la reanudación mientras el registro siga vigente.
3. La app Android ofrece el botón «Desconectar dispositivo». Desconectar en la
   app quita la vinculación local; el servidor revoca el registro.
4. La UI local del Servidor PC lista los dispositivos registrados y permite
   revocar cada uno (solo con cabecera de acción S1).
5. Un QR nuevo se exige solo si el registro fue revocado, si el dispositivo no
   está registrado o si falla la verificación de la reanudación.
6. R2 (un token, un Handshake) se mantiene para el token del QR: el registro
   persistente no reutiliza tokens.
```

## 3. Reason

El owner pidió que la conexión sea persistente: el usuario escanea el QR una
vez y, después, la app reconecta al abrirse aunque el servidor se haya
reiniciado o la app se haya cerrado. El límite actual de 60 s y la falta de
registro obligan a re-escanear.

## 4. Impact

- **Seguridad:** las claves de sesión de dispositivos registrados quedan en
  disco. Deben cifrarse; el almacén nunca guarda el token del QR. La revocación
  es explícita (botón en Android o en la UI local).
- **Alcance:** servidor (`server/`: registro persistente, `pair_resume` sin
  límite para registrados, endpoint de revocación), app Android (botón de
  desconexión, reconexión al abrir), UI local (lista y revocación), SPEC-001,
  REQ-002, SPEC-002, SPEC-010.
- **Compatibilidad:** el wire de `pair_resume` no cambia; cambia solo cuándo el
  servidor acepta la reanudación.

## 5. Acceptance

- [ ] Tras un Handshake exitoso, el dispositivo aparece en el registro persistente.
- [ ] Reabrir la app reconecta con `pair_resume` sin escanear QR.
- [ ] Reiniciar el servidor conserva el registro y permite reconectar.
- [ ] «Desconectar dispositivo» en Android revoca el registro en el servidor.
- [ ] Un dispositivo revocado vuelve a exigir QR nuevo.
- [ ] Ningún token ni clave de sesión aparece en logs ni en la UI (SPEC-001 R5/R6).

## 6. Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-11 | Buffy (agente) | Creación; aprobado por el owner |
