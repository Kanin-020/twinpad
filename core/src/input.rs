//! Input report del Gamepad — SPEC-004 §4/§12 (Signature HID, CTX-C5).
//!
//! Transporte inicial (ADR-007): viaja por la sesión LAN persistente de
//! SPEC-001 (mismo canal NDJSON que SPEC-003). Decode estricto como el
//! resto del protocolo: campo faltante, `type` desconocido o valor fuera
//! de rango → E4. El orden lo determina exclusivamente `seq` (R2).

use crate::error::{PairError, PairResult};
use serde::Deserialize;
use serde::Serialize;

/// Límites de la Signature HID (SPEC-004 §2/§4).
pub const STICK_MIN: i8 = -127;
pub const STICK_MAX: i8 = 127;
pub const TRIGGER_MAX: u8 = 255;
pub const DPAD_NEUTRAL: u8 = 8;
pub const BUTTON_COUNT: u16 = 12;
pub const GYRO_AXES: usize = 3;

/// Nombres canónicos de los 12 botones (SPEC-004 §2/§4: bit 0 = button_a).
pub const BUTTON_NAMES: [&str; 12] = [
    "button_a",
    "button_b",
    "button_x",
    "button_y",
    "button_l1",
    "button_r1",
    "button_l3",
    "button_r3",
    "button_start",
    "button_select",
    "button_extra_1",
    "button_extra_2",
];

/// Muestra inercial opcional de Android (SPEC-004 §4, CR-021).
#[derive(Clone, PartialEq, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MotionSample {
    /// Tiempo monotónico del evento de sensor, en microsegundos.
    pub timestamp_us: u64,
    /// Aceleración Android en m/s², ejes X/Y/Z del sensor.
    pub accel: [f32; GYRO_AXES],
    /// Velocidad angular Android en rad/s, ejes X/Y/Z del sensor.
    pub gyro: [f32; GYRO_AXES],
}

/// Mensaje de input del Gamepad (SPEC-004 §4). `type` es el discriminante wire.
#[derive(Clone, PartialEq, Debug, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum InputMsg {
    /// app → server: la instantánea completa del estado del gamepad (§7 paso 2).
    #[serde(rename = "input_report")]
    InputReport {
        /// Secuencial creciente por sesión; nunca se repite (R2).
        seq: u32,
        /// Stick izquierdo, eje X (−127…127; 0 = centro).
        stick_lx: i8,
        /// Stick izquierdo, eje Y (−127…127; 0 = centro).
        stick_ly: i8,
        /// Stick derecho, eje X (−127…127; 0 = centro).
        stick_rx: i8,
        /// Stick derecho, eje Y (−127…127; 0 = centro).
        stick_ry: i8,
        /// D-pad: 0–7 (dirección) u 8 (neutro).
        dpad: u8,
        /// Máscara de bits de los 12 botones canónicos (bit 0 = `button_a`).
        buttons: u16,
        /// Trigger analógico L2 (0–255).
        trigger_l2: u8,
        /// Trigger analógico R2 (0–255).
        trigger_r2: u8,
        /// Muestra de acelerómetro + giroscopio; ausente si ambos sensores no están disponibles.
        #[serde(skip_serializing_if = "Option::is_none")]
        motion: Option<MotionSample>,
        /// Posición X del toque sobre la imagen DRC, normalizada a 0…65535.
        touch_x: u16,
        /// Posición Y del toque sobre la imagen DRC, normalizada a 0…65535.
        touch_y: u16,
        /// Estado de contacto; al soltar se conserva touch_x/touch_y.
        touch_pressed: bool,
    },
}

impl InputMsg {
    /// Serializa a su línea NDJSON canónica (sin `\n` final).
    pub fn encode(&self) -> PairResult<String> {
        serde_json::to_string(self).map_err(|_| PairError::MalformedMessage)
    }

    /// Decodifica y valida una línea NDJSON de input; cualquier desviación
    /// de §4 → E4 (SPEC-004 §9).
    pub fn decode(line: &str) -> PairResult<Self> {
        let msg: Self = serde_json::from_str(line).map_err(|_| PairError::MalformedMessage)?;
        msg.validate()?;
        Ok(msg)
    }

    /// Valida rangos de los elementos (§4/§9: fuera de rango es E4 en la
    /// frontera del protocolo; el clamp es decisión del receptor).
    fn validate(&self) -> PairResult<()> {
        match self {
            InputMsg::InputReport { dpad, motion, .. } => {
                if *dpad > DPAD_NEUTRAL {
                    return Err(PairError::MalformedMessage);
                }
                if let Some(motion) = motion {
                    if motion
                        .accel
                        .iter()
                        .chain(motion.gyro.iter())
                        .any(|axis| !axis.is_finite())
                    {
                        return Err(PairError::MalformedMessage);
                    }
                }
                Ok(())
            }
        }
    }
}

// ENG §6: unwrap/panic permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    // TEST-018 — verifies SPEC-004 (§7: flujo de inputs y orden por `seq`).
    fn report(seq: u32) -> InputMsg {
        InputMsg::InputReport {
            seq,
            stick_lx: 0,
            stick_ly: 0,
            stick_rx: 0,
            stick_ry: 0,
            dpad: DPAD_NEUTRAL,
            buttons: 0,
            trigger_l2: 0,
            trigger_r2: 0,
            motion: None,
            touch_x: 0,
            touch_y: 0,
            touch_pressed: false,
        }
    }

    #[test]
    fn input_report_roundtrip() {
        let msg = InputMsg::InputReport {
            seq: 7,
            stick_lx: -127,
            stick_ly: 127,
            stick_rx: -1,
            stick_ry: 1,
            dpad: 3,
            buttons: 0b1010_0000_0001,
            trigger_l2: 128,
            trigger_r2: 255,
            motion: Some(MotionSample {
                timestamp_us: 123_456,
                accel: [0.0, 9.8066, 0.0],
                gyro: [0.1, -0.2, 0.3],
            }),
            touch_x: 1234,
            touch_y: 65535,
            touch_pressed: true,
        };
        let line = msg.encode().unwrap();
        assert!(!line.contains('\n'));
        assert_eq!(InputMsg::decode(&line).unwrap(), msg);
    }

    #[test]
    fn wire_type_name_matches_spec() {
        let line = report(0).encode().unwrap();
        assert!(line.starts_with("{\"type\":\"input_report\""));
    }

    #[test]
    fn seq_zero_is_valid() {
        assert_eq!(report(0).validate(), Ok(()));
    }

    #[test]
    fn motion_omitted_is_valid() {
        let line = report(1).encode().unwrap();
        assert!(!line.contains("gyro"));
        assert_eq!(InputMsg::decode(&line).unwrap(), report(1));
    }

    #[test]
    fn non_finite_motion_values_are_e4() {
        let mut msg = report(1);
        let InputMsg::InputReport { ref mut motion, .. } = msg;
        *motion = Some(MotionSample {
            timestamp_us: 1,
            accel: [0.0, f32::NAN, 0.0],
            gyro: [0.0; GYRO_AXES],
        });
        assert_eq!(msg.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn missing_field_is_e4() {
        // Sin `buttons` (obligatorio).
        assert_eq!(
            InputMsg::decode(
                "{\"type\":\"input_report\",\"seq\":1,\"stick_lx\":0,\"stick_ly\":0,\"stick_rx\":0,\"stick_ry\":0,\"dpad\":8,\"trigger_l2\":0,\"trigger_r2\":0}",
            ),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn unknown_type_is_e4() {
        assert_eq!(
            InputMsg::decode("{\"type\":\"rumble\",\"seq\":1}"),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn extra_field_is_e4() {
        let line = format!(
            "{},\"extra\":0}}",
            &report(1).encode().unwrap()[..report(1).encode().unwrap().len() - 1]
        );
        assert_eq!(InputMsg::decode(&line), Err(PairError::MalformedMessage));
    }

    // TEST-019 — verifies SPEC-004 (§9/§10: reporte fuera de rango → E4).
    #[test]
    fn dpad_out_of_range_is_e4() {
        let mut msg = report(1);
        let InputMsg::InputReport { ref mut dpad, .. } = msg;
        *dpad = 9;
        assert_eq!(msg.validate(), Err(PairError::MalformedMessage));
    }

    // TEST-083 — verifies SPEC-004/CR-021 touch fields and timestamped inertial motion wire contract.
    #[test]
    fn touch_and_inertial_motion_roundtrip() {
        let msg = InputMsg::InputReport {
            seq: 12,
            stick_lx: 0,
            stick_ly: 0,
            stick_rx: 0,
            stick_ry: 0,
            dpad: DPAD_NEUTRAL,
            buttons: 0,
            trigger_l2: 0,
            trigger_r2: 0,
            motion: Some(MotionSample {
                timestamp_us: 987_654_321,
                accel: [1.0, -2.5, 9.8066],
                gyro: [0.25, -0.5, 1.25],
            }),
            touch_x: 32768,
            touch_y: 65535,
            touch_pressed: true,
        };
        let line = msg.encode().unwrap();
        assert_eq!(InputMsg::decode(&line).unwrap(), msg);
        assert!(line.contains("\"touch_pressed\":true"));
        assert!(line.contains("\"motion\":{\"timestamp_us\":987654321,"));
        assert!(line.contains("\"accel\":[1.0,-2.5,9.8066]"));
        assert!(line.contains("\"gyro\":[0.25,-0.5,1.25]"));
    }

    #[test]
    fn button_names_match_signature() {
        assert_eq!(BUTTON_NAMES.len(), usize::from(BUTTON_COUNT));
        assert_eq!(BUTTON_NAMES[0], "button_a");
        assert_eq!(BUTTON_NAMES[BUTTON_NAMES.len() - 1], "button_extra_2");
    }
}
