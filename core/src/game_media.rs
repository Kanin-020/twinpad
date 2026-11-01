//! Framing del canal media binario juego → server (CR-054).
//!
//! Transporta la misma carga de `game_frame` H.264 de ADR-008 sin la
//! codificación base64url+JSON: una línea NDJSON `game_media_start` abre
//! la conexión y a partir de ahí cada access unit llega como
//!
//! ```text
//! [header de 32 bytes little-endian][anexo H.264 sin transformar]
//! ```
//!
//! Layout del header (todos los campos little-endian):
//!
//! | offset | tamaño | campo            | válido                            |
//! |--------|--------|------------------|-----------------------------------|
//! | 0      | 2      | `magic`          | `0x5457` (= "TW")                 |
//! | 2      | 8      | `gfn`            | monotónico creciente desde 0      |
//! | 10     | 8      | `ts_capture_us`  | libre (monotónico del encoder)    |
//! | 18     | 1      | `idr`            | 0 o 1                             |
//! | 19     | 2      | `w`              | 16–3840 (SPEC-003 §4)             |
//! | 21     | 2      | `h`              | 16–2160 (SPEC-003 §4)             |
//! | 23     | 4      | `len`            | 1–[`MAX_AU_BYTES`]                |
//! | 27     | 1      | `fmt`            | 1 (H.264, único soportado)        |
//! | 28     | 4      | reservado        | 0 (el emisor debe escribir 0)     |
//!
//! El receptor descarta el frame (y restablece el stream) cuando el magic
//! no coincide, `fmt`≠1, `idr`∉{0,1} o las dimensiones/`len` salen de
//! rango; no hay re-sincronización por bytes: el canal se cierra y el
//! Plugin decide volver al NDJSON o reabrir el binario.

use crate::error::{PairError, PairResult};
use crate::game::GameMsg;

/// Puerto loopback del canal media binario del Plugin (CR-054). DES-004 §7.6:
/// 8421 IPC primario, 8422 audio, 8423 código QR, 8424 hook E2E de Cemu,
/// 8425 media del teléfono y **8426 media del Plugin**.
pub const MEDIA_IPC_PORT: u16 = 8426;

/// Tamaño fijo del header por access unit (CR-054).
pub const MEDIA_FRAME_HEADER_BYTES: usize = 32;

/// Magic del header: "TW" en ASCII little-endian.
pub const MEDIA_FRAME_MAGIC: u16 = 0x5457;

/// Tag de formato soportado en el header (H.264).
pub const MEDIA_FRAME_FMT_H264: u8 = 1;

/// Techo de la carga de un frame binario (CR-054): el mismo del canal
/// media cifrado (`MAX_MEDIA_RECORD_BYTES`, 4 MiB); el binario no paga
/// base64url (×4/3) del NDJSON, así que 4 MiB de anexo caben de sobra.
pub const MAX_AU_BYTES: usize = crate::media_crypto::MAX_MEDIA_RECORD_BYTES;

/// Un access unit H.264 desempaquetado del canal binario (CR-054).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GameMediaFrame {
    /// Número de frame del Plugin (monotónico desde 0 por conexión).
    pub gfn: u64,
    /// Timestamp monotónico del encoder en µs.
    pub ts_capture_us: u64,
    /// ¿Es IDR? (SUITE-001 §7: el receptor usa esto para IDR-on-join).
    pub idr: bool,
    /// Ancho del framebuffer origen en píxeles.
    pub w: u16,
    /// Alto del framebuffer origen en píxeles.
    pub h: u16,
    /// Anexo H.264 sin transformar (AU completo, SPS/PPS de IDR).
    pub annex_b: Vec<u8>,
}

/// Escribe el header de 32 bytes little-endian y sobra espacio reservado
/// en cero (CR-054). `data.len()` de un frame válido cabe `u32` por el
/// techo de [`MAX_AU_BYTES`].
pub fn encode_header(frame: &GameMediaFrame) -> PairResult<[u8; MEDIA_FRAME_HEADER_BYTES]> {
    let mut header = [0_u8; MEDIA_FRAME_HEADER_BYTES];
    if frame.annex_b.is_empty() || frame.annex_b.len() > MAX_AU_BYTES {
        return Err(PairError::MalformedMessage);
    }
    // mismas dimensiones válidas que `game_frame` H.264 (SPEC-003 §4).
    crate::stream::validate_dims(frame.w, frame.h)?;
    header[0..2].copy_from_slice(&MEDIA_FRAME_MAGIC.to_le_bytes());
    header[2..10].copy_from_slice(&frame.gfn.to_le_bytes());
    header[10..18].copy_from_slice(&frame.ts_capture_us.to_le_bytes());
    header[18] = u8::from(frame.idr);
    header[19..21].copy_from_slice(&frame.w.to_le_bytes());
    header[21..23].copy_from_slice(&frame.h.to_le_bytes());
    header[23..27].copy_from_slice(&(frame.annex_b.len() as u32).to_le_bytes());
    header[27] = MEDIA_FRAME_FMT_H264;
    Ok(header)
}

/// ¿El header de un frame binario es sintácticamente válido? (CR-054).
/// `header` debe medir exactamente [`MEDIA_FRAME_HEADER_BYTES`].
pub fn header_valid(header: &[u8]) -> bool {
    if header.len() != MEDIA_FRAME_HEADER_BYTES {
        return false;
    }
    let magic = match header[0..2].try_into() {
        Ok(bytes) => u16::from_le_bytes(bytes),
        Err(_) => return false,
    };
    if magic != MEDIA_FRAME_MAGIC || header[18] > 1 || header[27] != MEDIA_FRAME_FMT_H264 {
        return false;
    }
    let (Ok(w_bytes), Ok(h_bytes), Ok(len_bytes)) = (
        header[19..21].try_into(),
        header[21..23].try_into(),
        header[23..27].try_into(),
    ) else {
        return false;
    };
    let w = u16::from_le_bytes(w_bytes);
    let h = u16::from_le_bytes(h_bytes);
    if crate::stream::validate_dims(w, h).is_err() {
        return false;
    }
    let len = u32::from_le_bytes(len_bytes) as usize;
    !(len == 0 || len > MAX_AU_BYTES)
}

/// Decodifica el header de un frame binario (CR-054); el llamante ya
/// verificó [`header_valid`] y descartó el resto del frame si es falso.
#[must_use]
pub fn decode_header(header: &[u8]) -> Option<(u64, u64, bool, u16, u16, usize)> {
    let (Ok(gfn), Ok(ts), Ok(w_bytes), Ok(h_bytes), Ok(len_bytes)) = (
        header[2..10].try_into(),
        header[10..18].try_into(),
        header[19..21].try_into(),
        header[21..23].try_into(),
        header[23..27].try_into(),
    ) else {
        return None;
    };
    let len = u32::from_le_bytes(len_bytes) as usize;
    if len > MAX_AU_BYTES {
        return None;
    }
    Some((
        u64::from_le_bytes(gfn),
        u64::from_le_bytes(ts),
        header[18] == 1,
        u16::from_le_bytes(w_bytes),
        u16::from_le_bytes(h_bytes),
        len,
    ))
}

/// Línea NDJSON de handshake canónica para abrir el canal (CR-054).
pub fn encode_start_line(game_id: &str) -> PairResult<String> {
    GameMsg::GameMediaStart {
        game_id: game_id.to_owned(),
    }
    .encode()
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::error::PairError;
    use crate::game::GameMsg;

    fn frame(annex_len: usize, w: u16, h: u16) -> GameMediaFrame {
        GameMediaFrame {
            gfn: 7,
            ts_capture_us: 1_234_567,
            idr: true,
            w,
            h,
            annex_b: vec![0xAB; annex_len],
        }
    }

    /// TEST-0118a — CR-054: el header codificado mide 32, respeta little-
    /// endian y su roundtrip con [`decode_header`] recupera los campos.
    #[test]
    fn header_roundtrip() {
        let source = frame(640, 1280, 720);
        let header = encode_header(&source).unwrap();
        assert_eq!(header.len(), MEDIA_FRAME_HEADER_BYTES);
        assert_eq!(u16::from_le_bytes(header[0..2].try_into().unwrap()), 0x5457);
        assert!(header[28..32].iter().all(|&byte| byte == 0));
        let (gfn, ts, idr, w, h, len) = decode_header(&header).unwrap();
        assert_eq!(
            (gfn, ts, idr, w, h, len),
            (7, 1_234_567, true, 1280, 720, 640)
        );
    }

    /// TEST-0118b — CR-054: len 0, payload sobre el techo, magic erróneo,
    /// `idr`≠0/1 y `fmt`≠1 invalidan el header; buffer mal medido también.
    #[test]
    fn header_rejections() {
        let mut header = encode_header(&frame(640, 1280, 720)).unwrap();
        header[23..27].copy_from_slice(&0_u32.to_le_bytes());
        assert!(!header_valid(&header));

        let mut header = encode_header(&frame(640, 1280, 720)).unwrap();
        header[23..27].copy_from_slice(&(MAX_AU_BYTES as u32 + 1).to_le_bytes());
        assert!(!header_valid(&header));

        let mut header = encode_header(&frame(640, 1280, 720)).unwrap();
        header[0] = 0x00;
        assert!(!header_valid(&header));

        let mut header = encode_header(&frame(640, 1280, 720)).unwrap();
        header[18] = 2;
        assert!(!header_valid(&header));

        let mut header = encode_header(&frame(640, 1280, 720)).unwrap();
        header[27] = 2;
        assert!(!header_valid(&header));

        assert!(!header_valid(&header[..16]));

        // una carga válida pasa siempre (sanity).
        let header = encode_header(&frame(640, 1280, 720)).unwrap();
        assert!(header_valid(&header));

        // el techo exacto pasa; los extremos de dims (16 y 3840/2160) pasan.
        let header = encode_header(&frame(MAX_AU_BYTES, 16, 2160)).unwrap();
        assert!(header_valid(&header));
        let header = encode_header(&frame(1, 3840, 16)).unwrap();
        assert!(header_valid(&header));

        // fuera de los límites de dims falla.
        let header = encode_header(&frame(1, 3841, 720));
        assert!(matches!(header, Err(PairError::MalformedMessage)));
    }

    /// TEST-0118c — CR-054: la línea de handshake valida como `GameMsg`.
    #[test]
    fn start_line_decodes() {
        let line = encode_start_line("cemu").unwrap();
        let msg = GameMsg::decode(&line).unwrap();
        assert_eq!(
            msg,
            GameMsg::GameMediaStart {
                game_id: "cemu".into()
            }
        );

        // id de juego inválido → rechazado por la validación central.
        let bad = encode_start_line("Cemu").unwrap();
        assert!(GameMsg::decode(&bad).is_err());
    }
}
