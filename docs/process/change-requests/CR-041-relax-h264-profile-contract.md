# CR-041 - Relax H.264 profile contract (Baseline/Main, nivel ≤4.2)

## Descripción
Relajar el contrato de perfil H.264 para desbloquear el encoder VAAPI en controladores freeworld (AMD) que no exponen `Constrained Baseline`. El nuevo contrato acepta perfiles **Baseline** (66, con o sin `constraint_set0`) o **Main** (77) con `level_idc ≤ 42`, manteniendo YUV 4:2:0, sin B-frames, Annex B e IDR con SPS/PPS.

## Motivo
El driver VAAPI con contrato estricto (Constrained Baseline nivel 4.2) rebota definitemente en el hardware freeworld (log: `negotiated profile=578, result=-38`). Al relajar a Baseline/Main nivel ≤4.2, el driver expone su perfil y el encoder queda en modo hardware, liberando la CPU.

## Superficie afectada
- `SPEC-003 §4.3`: tabla de perfiles actualizada
- `DES-003`: documentación del `EncoderState`
- `DES-004`: descripción de la ruta hardware
- `traceability.md`: TEST-073 y TEST-082 actualizados

## Cambios en el código
- `TwinpadClient.cpp`: campo `contractCompliant` que evalúa:
  - `profile_idc == 66` y (`constraint_set0 & 0x01` o `true`) → Baseline
  - `profile_idc == 77` → Main
  - `level_idc <= 42`
- `Base64UrlTest.cpp`: aserción actualizada al nuevo contrato

## Versionado
- SPEC-003: 1.10.0 → 1.11.0
- DES-003: 0.2.0 → 0.3.0
- DES-004: 1.9.0 → 1.10.0