//! Canal media UDP (SPEC-003 §4.4, CR-062) — primitivas puras.
//!
//! UDP Servidor→Android al mismo `media_port=8425`, preferente con fallback
//! TCP (§8.5). Este módulo posee el contrato de bytes: JOIN autenticado,
//! derivación de `udp_media_key`, envelope `TWDU` AES-256-GCM con AAD del
//! header, fragmentación declarativa y recovery FEC XOR; en recepción,
//! ventana anti-replay de 256, ensamblado por `au_id` (tope 4 pendientes)
//! y entrega latest-frame-wins de §4.3. Sin I/O: los sockets son de los
//! adaptadores (server y app Android).

use std::collections::{BTreeMap, BTreeSet};

use aes_gcm::aead::{AeadInPlace, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce, Tag};

use crate::media_crypto::{MediaCryptoError, hmac_sha256};

pub const JOIN_CONTEXT: &[u8] = b"twinpad.v1 media udp client";
pub const UDP_KEY_INFO: &[u8] = b"twinpad.v1 media udp server-to-client aes-256-gcm";
pub const JOIN_MAGIC: [u8; 8] = *b"TWPUDP01";
pub const DATA_MAGIC: [u8; 4] = *b"TWDU";
pub const FEC_AU_ID: u16 = 0xFFFF;
pub const FLAG_FIRST: u8 = 1;
pub const FLAG_LAST: u8 = 0b10;
pub const FLAG_FEC: u8 = 0b100;
pub const FLAG_RESERVED_MASK: u8 = 0b1111_1000;
/// En envelope de DATOS los bits 2–7 deben ser 0 (§4.4); el bit 2 es
/// exclusivo del envelope FEC.
pub const FLAG_DATA_RESERVED_MASK: u8 = !(FLAG_FIRST | FLAG_LAST);
/// JOIN completo: magic || session_id || last_seq || join_nonce || mac.
pub const JOIN_BYTES: usize = 8 + 16 + 4 + 32 + 32;
pub const DATAGRAM_HEADER_BYTES: usize = 4 + 4 + 2 + 1 + 2 + 2;
pub const DATAGRAM_TAG_BYTES: usize = 16;
pub const MAX_PAYLOAD_BYTES: usize = 1360;
pub const MAX_DATAGRAM_BYTES: usize =
    DATAGRAM_HEADER_BYTES + MAX_PAYLOAD_BYTES + DATAGRAM_TAG_BYTES;
/// Tamaño máximo del envelope recovery FEC: 5 B fijos + len×2 (grupo
/// ≤ 4) + metas (7 B × 4) + XOR del ct+tag más largo + tag del recovery.
pub const MAX_RECOVERY_DATAGRAM_BYTES: usize = DATAGRAM_HEADER_BYTES
    + 5
    + FEC_GROUP_SIZE * 2
    + FEC_GROUP_SIZE * FEC_MEMBER_BYTES
    + MAX_MEMBER_BYTES
    + DATAGRAM_TAG_BYTES;
pub const MAX_PENDING_AUS: usize = 4;
pub const REPLAY_WINDOW: usize = 256;
/// Plano máximo de §4.3 en fragmentos: ceil((17 + 4 MiB) / 1 360).
pub const MAX_FRAG_TOTAL: usize = 3_085;
pub const FEC_GROUP_SIZE: usize = 4;
/// Header del plaintext de AU de §4.3: fn || ts_capture_us || flag IDR.
const AU_PLAINTEXT_HEADER_BYTES: usize = 8 + 8 + 1;
/// Bytes por miembro del grupo FEC dentro del plaintext de recovery:
/// au_id[2] || flags[1] || frag_index[2] || frag_total[2].
const FEC_MEMBER_BYTES: usize = 7;
/// Máximo de ct+tag de un miembro del grupo en el plaintext de recovery.
const MAX_MEMBER_BYTES: usize = MAX_PAYLOAD_BYTES + DATAGRAM_TAG_BYTES;

fn udp_media_nonce(seq: u32) -> [u8; 12] {
    let mut nonce = [0_u8; 12];
    nonce[4..].copy_from_slice(&(u64::from(seq)).to_be_bytes());
    nonce
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ParsedJoin {
    pub session_id: [u8; 16],
    pub last_seq: u32,
    pub join_nonce: [u8; 32],
}

#[must_use]
pub fn join_mac(
    session_key: &[u8; 32],
    session_id: &[u8; 16],
    last_seq: u32,
    join_nonce: &[u8; 32],
) -> [u8; 32] {
    hmac_sha256(
        session_key,
        &[
            JOIN_CONTEXT,
            session_id,
            &last_seq.to_be_bytes(),
            join_nonce,
        ],
    )
}

/// JOIN wire de 92 B según §4.4.
#[must_use]
pub fn seal_join(
    session_key: &[u8; 32],
    session_id: &[u8; 16],
    last_seq: u32,
    join_nonce: &[u8; 32],
) -> Vec<u8> {
    let mut join = Vec::with_capacity(JOIN_BYTES);
    join.extend_from_slice(&JOIN_MAGIC);
    join.extend_from_slice(session_id);
    join.extend_from_slice(&last_seq.to_be_bytes());
    join.extend_from_slice(join_nonce);
    join.extend_from_slice(&join_mac(session_key, session_id, last_seq, join_nonce));
    join
}

/// Comparación en tiempo constante del MAC del JOIN contra el provisto.
#[must_use]
pub fn mac_matches(expected: &[u8; 32], provided: &[u8]) -> bool {
    if provided.len() != 32 {
        return false;
    }
    expected
        .iter()
        .zip(provided.iter())
        .fold(0_u8, |diff, (a, b)| diff | (a ^ b))
        == 0
}

/// Parseo estructural del JOIN de 92 B. La autenticidad (HMAC contra el
/// almacén de claves + sesión activa) la valida el Servidor después (§4.2).
///
/// # Errors
/// `InvalidLength` si el datagrama no es un JOIN de 92 B con la magia exacta.
pub fn parse_join(datagram: &[u8]) -> Result<ParsedJoin, MediaCryptoError> {
    if datagram.len() != JOIN_BYTES || datagram[..8] != JOIN_MAGIC {
        return Err(MediaCryptoError::InvalidLength);
    }
    Ok(ParsedJoin {
        session_id: datagram[8..24]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?,
        last_seq: u32::from_be_bytes(
            datagram[24..28]
                .try_into()
                .map_err(|_| MediaCryptoError::InvalidLength)?,
        ),
        join_nonce: datagram[28..60]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?,
    })
}

/// `udp_media_key = HKDF-SHA256(ikm = session_key, salt = join_nonce,
/// info = UDP_KEY_INFO)` — §4.4, misma técnica RFC 5869 (un bloque) que la
/// subclave de §4.2.
#[must_use]
pub fn derive_udp_media_key(session_key: &[u8; 32], join_nonce: &[u8; 32]) -> [u8; 32] {
    let prk = hmac_sha256(join_nonce, &[session_key]);
    hmac_sha256(&prk, &[UDP_KEY_INFO, &[1]])
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OpenEnvelope {
    pub seq: u32,
    pub au_id: u16,
    pub flags: u8,
    pub frag_index: u16,
    pub frag_total: u16,
    pub plaintext: Vec<u8>,
}

fn build_header(
    seq: u32,
    au_id: u16,
    flags: u8,
    frag_index: u16,
    frag_total: u16,
) -> [u8; DATAGRAM_HEADER_BYTES] {
    let mut header = [0_u8; DATAGRAM_HEADER_BYTES];
    header[..4].copy_from_slice(&DATA_MAGIC);
    header[4..8].copy_from_slice(&seq.to_be_bytes());
    header[8..10].copy_from_slice(&au_id.to_be_bytes());
    header[10] = flags;
    header[11..13].copy_from_slice(&frag_index.to_be_bytes());
    header[13..15].copy_from_slice(&frag_total.to_be_bytes());
    header
}

fn seal_envelope(
    key: &[u8; 32],
    seq: u32,
    header: &[u8; DATAGRAM_HEADER_BYTES],
    plaintext: &[u8],
) -> Result<Vec<u8>, MediaCryptoError> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| MediaCryptoError::InvalidLength)?;
    let mut ciphertext = plaintext.to_vec();
    let tag = cipher
        .encrypt_in_place_detached(
            Nonce::from_slice(&udp_media_nonce(seq)),
            header,
            &mut ciphertext,
        )
        .map_err(|_| MediaCryptoError::AuthenticationFailed)?;
    let mut datagram = Vec::with_capacity(header.len() + ciphertext.len() + DATAGRAM_TAG_BYTES);
    datagram.extend_from_slice(header);
    datagram.extend_from_slice(&ciphertext);
    datagram.extend_from_slice(tag.as_slice());
    Ok(datagram)
}

/// Sella un datagrama de datos: header TWDU de 15 B (ADR) + AES-256-GCM.
///
/// # Errors
/// `InvalidLength` ante flags reservados o payload > 1 360 B.
pub fn seal_data_datagram(
    key: &[u8; 32],
    seq: u32,
    au_id: u16,
    flags: u8,
    frag_index: u16,
    frag_total: u16,
    plaintext: &[u8],
) -> Result<Vec<u8>, MediaCryptoError> {
    if flags & !(FLAG_FIRST | FLAG_LAST) != 0 || plaintext.len() > MAX_PAYLOAD_BYTES {
        return Err(MediaCryptoError::InvalidLength);
    }
    seal_envelope(
        key,
        seq,
        &build_header(seq, au_id, flags, frag_index, frag_total),
        plaintext,
    )
}

/// Abre cualquier datagrama TWDU (datos o recovery) validando framing
/// estructural y autenticidad GCM. El anti-replay es del receptor.
///
/// # Errors
/// `InvalidLength` ante magia/tamaño/framing inválidos;
/// `AuthenticationFailed` ante tag inválido.
pub fn open_datagram(key: &[u8; 32], datagram: &[u8]) -> Result<OpenEnvelope, MediaCryptoError> {
    let mut size_limit = MAX_DATAGRAM_BYTES;
    if datagram.len() >= DATAGRAM_HEADER_BYTES + DATAGRAM_TAG_BYTES
        && datagram[..4] == DATA_MAGIC
        && datagram[8..10] == FEC_AU_ID.to_be_bytes()
        && datagram[10] == FLAG_FEC
    {
        // El recovery FEC lleva metas + XOR del ct+tag del grupo: su
        // total excede el tope del envelope de datos (≤ 1 448 B).
        size_limit = MAX_RECOVERY_DATAGRAM_BYTES;
    }
    if datagram.len() < DATAGRAM_HEADER_BYTES + DATAGRAM_TAG_BYTES
        || datagram[..4] != DATA_MAGIC
        || datagram.len() > size_limit
    {
        return Err(MediaCryptoError::InvalidLength);
    }
    let header: [u8; DATAGRAM_HEADER_BYTES] = datagram[..DATAGRAM_HEADER_BYTES]
        .try_into()
        .map_err(|_| MediaCryptoError::InvalidLength)?;
    let seq = u32::from_be_bytes(
        header[4..8]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?,
    );
    let au_id = u16::from_be_bytes(
        header[8..10]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?,
    );
    let flags = header[10];
    let frag_index = u16::from_be_bytes(
        header[11..13]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?,
    );
    let frag_total = u16::from_be_bytes(
        header[13..15]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?,
    );
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| MediaCryptoError::InvalidLength)?;
    let tag_start = datagram.len() - DATAGRAM_TAG_BYTES;
    let mut ciphertext = datagram[DATAGRAM_HEADER_BYTES..tag_start].to_vec();
    let tag = Tag::from_slice(&datagram[tag_start..]);
    cipher
        .decrypt_in_place_detached(
            Nonce::from_slice(&udp_media_nonce(seq)),
            &header,
            &mut ciphertext,
            tag,
        )
        .map_err(|_| MediaCryptoError::AuthenticationFailed)?;
    Ok(OpenEnvelope {
        seq,
        au_id,
        flags,
        frag_index,
        frag_total,
        plaintext: ciphertext,
    })
}

/// Divide el plaintext §4.3 de una AU en trozos ≤ payload (envío). El
/// sufijo corto conserva el resto; nunca devuelve listas vacías para AU.
#[must_use]
pub fn split_payload(plaintext: &[u8]) -> Vec<Vec<u8>> {
    if plaintext.is_empty() {
        return Vec::new();
    }
    plaintext
        .chunks(MAX_PAYLOAD_BYTES)
        .map(<[u8]>::to_vec)
        .collect()
}

/// AU completamente ensamblada: plaintext exacto de §4.3 reconstituido,
/// con fn/ts estrictamente crecientes respecto de lo ya entregado.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AssembledAu {
    pub fn_: u64,
    pub timestamp_us: u64,
    pub idr: bool,
    pub annex_b: Vec<u8>,
}

/// Emisor del Servidor (un `Emitter` por JOIN): sella AU en datagramas de
/// datos ≤ 1 400 B con fragmentación declarativa y cierra cada grupo FEC
/// de hasta 4 datagramas con su recovery (`au_id = 0xFFFF`).
pub struct UdpEmitter {
    key: [u8; 32],
    seq: u32,
    au_id: u16,
    group: Vec<SealedMember>,
}

#[derive(Clone)]
struct SealedMember {
    /// ciphertext+tag del datagrama de datos (sin header) para la XOR.
    ciphertext_and_tag: Vec<u8>,
    au_id: u16,
    flags: u8,
    frag_index: u16,
    frag_total: u16,
}

impl UdpEmitter {
    /// `seq` corriente (estrictamente creciente por datagrama, recovery
    /// incluido); el Servidor la expondrá como `seq_corriente` para la
    /// señal `last_seq` de los keepalives (R11).
    #[must_use]
    pub fn current_seq(&self) -> u32 {
        self.seq
    }

    #[must_use]
    pub fn new(key: [u8; 32], seq: u32, au_id: u16) -> Self {
        Self {
            key,
            seq,
            au_id,
            group: Vec::new(),
        }
    }

    /// Sella una AU completa: devuelve los datagramas en orden de envío
    /// (datos + recovery FEC cuando cierra un grupo completo a mitad de
    /// AU o al terminar la AU con 1–3 miembros pendientes).
    ///
    /// # Errors
    /// `InvalidLength` si la AU excede el plano de `MAX_FRAG_TOTAL`.
    pub fn seal_au(
        &mut self,
        fn_: u64,
        timestamp_us: u64,
        idr: bool,
        annex_b: &[u8],
    ) -> Result<Vec<Vec<u8>>, MediaCryptoError> {
        let mut plaintext = Vec::with_capacity(AU_PLAINTEXT_HEADER_BYTES + annex_b.len());
        plaintext.extend_from_slice(&fn_.to_be_bytes());
        plaintext.extend_from_slice(&timestamp_us.to_be_bytes());
        plaintext.push(u8::from(idr));
        plaintext.extend_from_slice(annex_b);
        let chunks = split_payload(&plaintext);
        let frag_total =
            u16::try_from(chunks.len()).map_err(|_| MediaCryptoError::InvalidLength)?;
        let mut datagrams = Vec::new();
        for (frag_index, chunk) in chunks.iter().enumerate() {
            let mut flags = 0_u8;
            if frag_index == 0 {
                flags |= FLAG_FIRST;
            }
            if frag_index + 1 == chunks.len() {
                flags |= FLAG_LAST;
            }
            let fragment = seal_data_datagram(
                &self.key,
                self.seq,
                self.au_id,
                flags,
                u16::try_from(frag_index).map_err(|_| MediaCryptoError::InvalidLength)?,
                frag_total,
                chunk,
            )?;
            self.group.push(SealedMember {
                ciphertext_and_tag: fragment[DATAGRAM_HEADER_BYTES..].to_vec(),
                au_id: self.au_id,
                flags,
                frag_index: u16::try_from(frag_index)
                    .map_err(|_| MediaCryptoError::InvalidLength)?,
                frag_total,
            });
            datagrams.push(fragment);
            self.seq = self.seq.wrapping_add(1);
            if self.group.len() == FEC_GROUP_SIZE {
                datagrams.extend(self.seal_recovery()?);
            }
        }
        // Cierre al final de la AU con grupo parcial (1–3 miembros).
        datagrams.extend(self.seal_recovery()?);
        self.au_id = self.au_id.wrapping_add(1);
        Ok(datagrams)
    }

    /// Recovery del grupo vigente (si lo hay) reseteado tras emitirlo.
    fn seal_recovery(&mut self) -> Result<Vec<Vec<u8>>, MediaCryptoError> {
        if self.group.is_empty() {
            return Ok(Vec::new());
        }
        let count = u8::try_from(self.group.len()).unwrap_or(0);
        let group_base = self.group_base();
        let max_len = self
            .group
            .iter()
            .map(|member| member.ciphertext_and_tag.len())
            .max()
            .unwrap_or(0);
        let mut xor = vec![0_u8; max_len];
        for member in &self.group {
            for (slot, byte) in xor.iter_mut().zip(&member.ciphertext_and_tag) {
                *slot ^= byte;
            }
        }
        let mut plaintext = Vec::with_capacity(
            5 + usize::from(count) * 2 + usize::from(count) * FEC_MEMBER_BYTES + max_len,
        );
        plaintext.extend_from_slice(&group_base.to_be_bytes());
        plaintext.push(count);
        for member in &self.group {
            let member_len = u16::try_from(member.ciphertext_and_tag.len())
                .map_err(|_| MediaCryptoError::InvalidLength)?;
            plaintext.extend_from_slice(&member_len.to_be_bytes());
        }
        for member in &self.group {
            plaintext.extend_from_slice(&member.au_id.to_be_bytes());
            plaintext.push(member.flags);
            plaintext.extend_from_slice(&member.frag_index.to_be_bytes());
            plaintext.extend_from_slice(&member.frag_total.to_be_bytes());
        }
        plaintext.extend_from_slice(&xor);
        let recovery = seal_envelope(
            &self.key,
            self.seq,
            &build_header(self.seq, FEC_AU_ID, FLAG_FEC, u16::from(count), 0),
            &plaintext,
        )?;
        self.group.clear();
        self.seq = self.seq.wrapping_add(1);
        Ok(vec![recovery])
    }

    /// Base del grupo: derivable de la longitud de bytes del datagrama —
    /// en su lugar la modelamos con la meta cumplida al emitir cada
    /// miembro (los miembros consecutivos de un grupo son contiguos):
    fn group_base(&self) -> u32 {
        self.seq - u32::try_from(self.group.len()).unwrap_or(0)
    }
}

struct RecoveryMember {
    au_id: u16,
    flags: u8,
    frag_index: u16,
    frag_total: u16,
}

struct RecoveryParsed {
    group_base: u32,
    count: u8,
    lens: Vec<usize>,
    metas: Vec<RecoveryMember>,
    xor: Vec<u8>,
}

fn parse_recovery_plaintext(plain: &[u8]) -> Result<RecoveryParsed, MediaCryptoError> {
    if plain.len() < 5 {
        return Err(MediaCryptoError::InvalidLength);
    }
    let group_base = u32::from_be_bytes(
        plain[..4]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?,
    );
    let count = plain[4];
    if count == 0 || count as usize > FEC_GROUP_SIZE {
        return Err(MediaCryptoError::InvalidLength);
    }
    let count_usize = usize::from(count);
    let mut lens = Vec::with_capacity(count_usize);
    for i in 0..count_usize {
        let bytes: [u8; 2] = plain[5 + i * 2..7 + i * 2]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?;
        lens.push(usize::from(u16::from_be_bytes(bytes)));
    }
    if lens.iter().any(|len| *len > MAX_MEMBER_BYTES) {
        return Err(MediaCryptoError::InvalidLength);
    }
    let metas_start = 5 + count_usize * 2;
    let metas_end = metas_start + count_usize * FEC_MEMBER_BYTES;
    if plain.len() < metas_end {
        return Err(MediaCryptoError::InvalidLength);
    }
    let mut metas = Vec::with_capacity(count_usize);
    for i in 0..count_usize {
        let chunk =
            &plain[metas_start + i * FEC_MEMBER_BYTES..metas_start + (i + 1) * FEC_MEMBER_BYTES];
        let au_id_bytes: [u8; 2] = chunk[..2]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?;
        let frag_index_bytes: [u8; 2] = chunk[3..5]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?;
        let frag_total_bytes: [u8; 2] = chunk[5..7]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?;
        metas.push(RecoveryMember {
            au_id: u16::from_be_bytes(au_id_bytes),
            flags: chunk[2],
            frag_index: u16::from_be_bytes(frag_index_bytes),
            frag_total: u16::from_be_bytes(frag_total_bytes),
        });
    }
    let xor = plain[metas_end..].to_vec();
    let max_len = lens.iter().copied().max().unwrap_or(0);
    if xor.len() != max_len {
        return Err(MediaCryptoError::InvalidLength);
    }
    Ok(RecoveryParsed {
        group_base,
        count,
        lens,
        metas,
        xor,
    })
}

/// Reconstructor del miembro faltante: XOR del recovery contra todos los
/// miembros presentes rellenados con ceros al máximo `len`.
fn reconstruct_member(
    parsed: &RecoveryParsed,
    missing_index: usize,
    present: impl Fn(usize) -> Option<Vec<u8>>,
) -> Option<Vec<u8>> {
    let len = parsed.lens[missing_index];
    if parsed.xor.len() < len {
        return None;
    }
    let mut restored = parsed.xor[..len].to_vec();
    for index in 0..usize::from(parsed.count) {
        if index == missing_index {
            continue;
        }
        let member = present(index)?;
        for (slot, &byte) in restored.iter_mut().zip(member.iter()) {
            *slot ^= byte;
        }
    }
    Some(restored)
}

struct PendingAu {
    first_seq: u32,
    frag_total: u16,
    fragments: BTreeMap<u16, Vec<u8>>,
}

impl PendingAu {
    fn is_complete(&self) -> bool {
        self.fragments.len() == usize::from(self.frag_total)
    }

    fn push(&mut self, frag_index: u16, plaintext: Vec<u8>) {
        self.fragments.insert(frag_index, plaintext);
    }

    fn is_superfluous(&self, frag_index: u16) -> bool {
        self.fragments.contains_key(&frag_index)
    }

    fn assemble(&self) -> Vec<u8> {
        let mut assembled = Vec::with_capacity(usize::from(self.frag_total) * MAX_PAYLOAD_BYTES);
        for index in 0..self.frag_total {
            assembled.extend_from_slice(&self.fragments[&index]);
        }
        assembled
    }
}

/// Receptor (el apropiado por JOIN, poseído por el stream de Android):
/// ventana anti-replay de 256 por join, ensamblado por `au_id` con tope
/// de 4 AUs pendientes y abandono ≥ 256 `seq` atrás, recovery FEC XOR y
/// entrega latest-frame-wins de §4.3. Jamás entrega AU no autenticada.
pub struct UdpReassembler {
    key: [u8; 32],
    watermark: u32,
    seen: BTreeSet<u32>,
    raw_by_seq: BTreeMap<u32, Vec<u8>>,
    pending: BTreeMap<u16, PendingAu>,
    delivered: Option<(u64, u64)>,
}

impl UdpReassembler {
    #[must_use]
    pub fn new(key: [u8; 32]) -> Self {
        Self {
            key,
            watermark: 0,
            seen: BTreeSet::new(),
            raw_by_seq: BTreeMap::new(),
            pending: BTreeMap::new(),
            delivered: None,
        }
    }

    /// Ingesta de un datagrama UDP. Rejugado o fuera de ventana →
    /// `Ok(None)` (descarte silencioso, el canal NO cierra). Fallo de
    /// autenticidad/framing → error (el caller también descarta).
    ///
    /// # Errors
    /// Propaga el error de `open_datagram` cuando la autenticidad falla.
    pub fn ingest(&mut self, datagram: &[u8]) -> Result<Option<AssembledAu>, MediaCryptoError> {
        let env = open_datagram(&self.key, datagram)?;
        if !self.in_window(env.seq) || !self.seen.insert(env.seq) {
            return Ok(None);
        }
        if env.au_id != FEC_AU_ID && env.flags & !(FLAG_FIRST | FLAG_LAST) != 0 {
            return Err(MediaCryptoError::InvalidLength);
        }
        let delivered = if env.au_id != FEC_AU_ID {
            self.accept_data(&env)
        } else if env.flags != FLAG_FEC || env.frag_total != 0 {
            // Envelope FEC con flags/framing inválidos: descarte.
            None
        } else {
            self.accept_recovery(&env)
        };
        self.raw_by_seq.insert(env.seq, datagram.to_vec());
        if env.seq > self.watermark {
            self.watermark = env.seq;
            self.prune(env.seq);
        }
        self.purge_abandoned(env.seq);
        Ok(delivered)
    }

    /// §4.4: se acepta todo `seq` no visto previamente con
    /// `seq > watermark` (alto) o `watermark − seq < 256`.
    fn in_window(&self, seq: u32) -> bool {
        seq >= self.watermark || self.watermark - seq < REPLAY_WINDOW as u32
    }

    /// Retención de ct+tag crudo para recovery; recorte de lo que sale
    /// de la ventana.
    fn prune(&mut self, watermark: u32) {
        if watermark < REPLAY_WINDOW as u32 {
            return;
        }
        let cutoff = watermark - REPLAY_WINDOW as u32;
        self.seen.retain(|stored| *stored > cutoff);
        self.raw_by_seq.retain(|stored, _| *stored > cutoff);
    }

    /// Abandona AUs pendientes cuyo primer fragmento quedó ≥ 256 `seq`
    /// atrás del corriente (discontinuidad: se espera IDR).
    fn purge_abandoned(&mut self, seq: u32) {
        if seq < REPLAY_WINDOW as u32 {
            return;
        }
        let cutoff = seq - REPLAY_WINDOW as u32;
        self.pending.retain(|_, pending| pending.first_seq > cutoff);
    }

    fn accept_data(&mut self, env: &OpenEnvelope) -> Option<AssembledAu> {
        let assembled = self.enqueue_fragment(env)?;
        self.validate_and_yield(assembled)
    }

    /// Almacena el fragmento y ensambla la AU completa si lo es.
    fn enqueue_fragment(&mut self, env: &OpenEnvelope) -> Option<Vec<u8>> {
        let frag_total = env.frag_total;
        if frag_total == 0
            || usize::from(frag_total) > MAX_FRAG_TOTAL
            || env.frag_index >= frag_total
        {
            return None;
        }
        // Tope de AUs pendientes: la más vieja abandona ante la nueva.
        if self.pending.len() >= MAX_PENDING_AUS && !self.pending.contains_key(&env.au_id) {
            let oldest = *self.pending.keys().next()?;
            self.pending.remove(&oldest);
        }
        let pending = self.pending.entry(env.au_id).or_insert_with(|| PendingAu {
            first_seq: env.seq,
            frag_total,
            fragments: BTreeMap::new(),
        });
        // Contradicción de framing: la AU viva se vuelve inválida.
        if pending.frag_total != frag_total {
            pending.frag_total = frag_total;
            pending.fragments.retain(|index, _| *index < frag_total);
        }
        if pending.is_superfluous(env.frag_index) {
            return None; // repetido dentro del pendiente: sin efecto.
        }
        pending.push(env.frag_index, env.plaintext.clone());
        if !pending.is_complete() {
            return None;
        }
        let assembled = pending.assemble();
        self.pending.remove(&env.au_id);
        Some(assembled)
    }

    /// Recovery del grupo (§4.4): `group_base || count || len_i ||
    /// metas_i || XOR`; EXACTAMENTE un miembro faltante se reconstruye
    /// (cabecera desde metas + GCM) y su AU (si completa) se entrega;
    /// con 0 o ≥ 2 faltantes no hay entrega.
    fn accept_recovery(&mut self, env: &OpenEnvelope) -> Option<AssembledAu> {
        let parsed = parse_recovery_plaintext(&env.plaintext).ok()?;
        let count = usize::from(parsed.count);
        let members: Vec<u32> = (0..count).map(|i| parsed.group_base + i as u32).collect();
        let missing: Vec<usize> = (0..count)
            .filter(|i| !self.raw_by_seq.contains_key(&members[*i]))
            .collect();
        if missing.len() != 1 {
            return None;
        }
        let missing_index = missing[0];
        let restored = reconstruct_member(&parsed, missing_index, |i| {
            self.raw_by_seq
                .get(&members[i])
                .map(|raw| raw[DATAGRAM_HEADER_BYTES..].to_vec())
        })?;
        if self.seen.contains(&members[missing_index]) {
            return None;
        }
        let meta = &parsed.metas[missing_index];
        let datagram = seal_header_restored(
            members[missing_index],
            meta.au_id,
            meta.flags,
            meta.frag_index,
            meta.frag_total,
            &restored,
        );
        let env_restored = open_datagram(&self.key, &datagram).ok()?;
        if env_restored.flags & !(FLAG_FIRST | FLAG_LAST) != 0 {
            return None;
        }
        self.seen.insert(env_restored.seq);
        self.raw_by_seq.insert(env_restored.seq, datagram);
        let assembled = self.enqueue_fragment(&env_restored)?;
        self.validate_and_yield(assembled)
    }

    /// La entrega respeta latest-frame-wins de §4.3: AU con fn/ts no
    /// estrictamente crecientes se descarta sin entregar.
    fn validate_and_yield(&mut self, assembled: Vec<u8>) -> Option<AssembledAu> {
        if assembled.len() < AU_PLAINTEXT_HEADER_BYTES {
            return None;
        }
        let fn_ = u64::from_be_bytes(assembled[..8].try_into().ok()?);
        let timestamp_us = u64::from_be_bytes(assembled[8..16].try_into().ok()?);
        if let Some((last_fn, last_ts)) = self.delivered {
            if fn_ <= last_fn || timestamp_us <= last_ts {
                return None;
            }
        }
        self.delivered = Some((fn_, timestamp_us));
        Some(AssembledAu {
            fn_,
            timestamp_us,
            idr: assembled[16] == 1,
            annex_b: assembled[AU_PLAINTEXT_HEADER_BYTES..].to_vec(),
        })
    }
}
/// Rebuild restoration header + raw ct+tag of a recovered member.
fn seal_header_restored(
    seq: u32,
    au_id: u16,
    flags: u8,
    frag_index: u16,
    frag_total: u16,
    ciphertext_and_tag: &[u8],
) -> Vec<u8> {
    let mut datagram = Vec::with_capacity(DATAGRAM_HEADER_BYTES + ciphertext_and_tag.len());
    datagram.extend_from_slice(&build_header(seq, au_id, flags, frag_index, frag_total));
    datagram.extend_from_slice(ciphertext_and_tag);
    datagram
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::media_crypto::MediaCryptoError::AuthenticationFailed;

    const KEY: [u8; 32] = [0x54; 32];
    const SESSION_ID: [u8; 16] = [0x11; 16];
    const JOIN_NONCE: [u8; 32] = [0x22; 32];
    const OTHER_NONCE: [u8; 32] = [0x33; 32];

    fn h264_bytes(pattern: u8, len: usize) -> Vec<u8> {
        (0..len).map(|i| pattern ^ ((i % 251) as u8)).collect()
    }

    fn ingest_all(reassembler: &mut UdpReassembler, datagrams: &[Vec<u8>]) -> Vec<AssembledAu> {
        datagrams
            .iter()
            .filter_map(|datagram| reassembler.ingest(datagram).unwrap())
            .collect()
    }

    fn seq_of(header_only: &[u8]) -> u32 {
        u32::from_be_bytes(header_only[4..8].try_into().unwrap())
    }

    fn with_seq(datagrams: Vec<Vec<u8>>, skip: impl Fn(u32) -> bool) -> Vec<Vec<u8>> {
        datagrams
            .into_iter()
            .filter(|datagram| !skip(seq_of(datagram)))
            .collect()
    }

    // TEST-0138 — CR-062 (SPEC-003 §4.4): JOIN de 92 B, HMAC que liga
    // todos los campos y parseo estricto de magia/longitud.
    #[test]
    fn join_roundtrip_binds_fields_and_rejects_framing() {
        let join = seal_join(&KEY, &SESSION_ID, 42, &JOIN_NONCE);
        assert_eq!(join.len(), JOIN_BYTES);
        assert_eq!(parse_join(&join).unwrap().last_seq, 42);
        let mut tampered = join.clone();
        tampered[24] ^= 1; // last_seq
        let parsed = parse_join(&tampered).unwrap();
        assert_ne!(
            join_mac(
                &KEY,
                &parsed.session_id,
                parsed.last_seq,
                &parsed.join_nonce
            ),
            join_mac(&KEY, &SESSION_ID, 42, &JOIN_NONCE)
        );
        assert_eq!(
            parse_join(&join[..JOIN_BYTES - 1]),
            Err(MediaCryptoError::InvalidLength)
        );
        let mut wrong_magic = join;
        wrong_magic[0] = b'X';
        assert_eq!(
            parse_join(&wrong_magic),
            Err(MediaCryptoError::InvalidLength)
        );
    }

    // TEST-0138 (CR-062): udp_media_key separa joins por nonce/sesión.
    #[test]
    fn udp_media_key_binds_join_nonce_and_session_key() {
        assert_eq!(
            derive_udp_media_key(&KEY, &JOIN_NONCE),
            derive_udp_media_key(&KEY, &JOIN_NONCE)
        );
        assert_ne!(
            derive_udp_media_key(&KEY, &JOIN_NONCE),
            derive_udp_media_key(&KEY, &OTHER_NONCE)
        );
        assert_ne!(
            derive_udp_media_key(&KEY, &JOIN_NONCE),
            derive_udp_media_key(&OTHER_NONCE, &JOIN_NONCE)
        );
    }

    // TEST-0138 (CR-062): envelope TWDU — roundtrip GCM, AAD ligado al
    // header, payload máximo, flags reservados y tamaño total ≤ 1 400.
    #[test]
    fn data_envelope_roundtrip_rejects_tamper_limits_and_flags() {
        let plain = b"unit".to_vec();
        let datagram =
            seal_data_datagram(&KEY, 0, 7, FLAG_FIRST | FLAG_LAST, 0, 1, &plain).unwrap();
        assert!(datagram.len() <= MAX_DATAGRAM_BYTES);
        let env = open_datagram(&KEY, &datagram).unwrap();
        assert_eq!(env.au_id, 7);
        assert_eq!(env.flags, FLAG_FIRST | FLAG_LAST);
        assert_eq!(env.plaintext, plain);
        let mut tampered = datagram.clone();
        tampered[DATAGRAM_HEADER_BYTES] ^= 1;
        assert_eq!(open_datagram(&KEY, &tampered), Err(AuthenticationFailed));
        let mut header_swapped = datagram.clone();
        header_swapped[8] ^= 1; // au_id cambia el AAD
        assert_eq!(
            open_datagram(&KEY, &header_swapped),
            Err(AuthenticationFailed)
        );
        let oversize = vec![0_u8; MAX_PAYLOAD_BYTES + 1];
        assert_eq!(
            seal_data_datagram(&KEY, 0, 7, 0, 0, 1, &oversize),
            Err(MediaCryptoError::InvalidLength)
        );
        assert_eq!(
            seal_data_datagram(&KEY, 0, 7, FLAG_RESERVED_MASK, 0, 1, &plain),
            Err(MediaCryptoError::InvalidLength)
        );
        assert_eq!(
            open_datagram(&KEY, &vec![0_u8; MAX_DATAGRAM_BYTES + 1]),
            Err(MediaCryptoError::InvalidLength)
        );
    }

    // TEST-0138 (CR-062): AU de un fragmento — entrega exacta fn/ts/IDR.
    #[test]
    fn single_fragment_au_is_delivered_in_order() {
        let mut emitter = UdpEmitter::new(KEY, 0, 0);
        let datagrams = emitter.seal_au(5, 900, true, b"idr").unwrap();
        let mut r = UdpReassembler::new(KEY);
        let delivered = ingest_all(&mut r, &datagrams);
        assert_eq!(delivered.len(), 1);
        assert_eq!(delivered[0].fn_, 5);
        assert_eq!(delivered[0].timestamp_us, 900);
        assert!(delivered[0].idr);
        assert_eq!(delivered[0].annex_b, b"idr");
    }

    // TEST-0138 (CR-062): AU fragmentada llega fuera de orden y se
    // ensambla al llegar el último fragmento que faltaba.
    #[test]
    fn multi_fragment_au_completes_out_of_order() {
        let mut emitter = UdpEmitter::new(KEY, 0, 0);
        let datagrams = emitter
            .seal_au(9, 1_100, false, &h264_bytes(0xA3, 2_750))
            .unwrap();
        assert!(datagrams.len() >= 3); // 2 fragmentos + recovery
        let mut reordered: Vec<Vec<u8>> = datagrams.iter().cloned().rev().collect();
        let mut r = UdpReassembler::new(KEY);
        // Orden invertido: la AU se completa con el último que llega.
        let delivered = ingest_all(&mut r, &reordered);
        assert_eq!(delivered.len(), 1);
        assert_eq!(delivered[0].fn_, 9);
        assert_eq!(delivered[0].annex_b, h264_bytes(0xA3, 2_750));
        // Reenvío completo: todo repetido, sin duplicados ni fallos.
        assert!(ingest_all(&mut r, &reordered).is_empty());
        let _ = &mut reordered;
    }

    // TEST-0138 (CR-062): AU fragmentada completa (sin perdidas).
    #[test]
    fn multi_fragment_au_delivery_not_duplicated_and_matches_payload() {
        let mut emitter = UdpEmitter::new(KEY, 0, 0);
        let datagrams = emitter
            .seal_au(3, 2_200, true, &h264_bytes(0x5C, 4_000))
            .unwrap();
        let mut r = UdpReassembler::new(KEY);
        let delivered = ingest_all(&mut r, &datagrams);
        assert_eq!(delivered.len(), 1);
        assert_eq!(delivered[0].annex_b, h264_bytes(0x5C, 4_000));
        assert_eq!(delivered[0].fn_, 3);
    }

    // TEST-0138 (CR-062): anti-replay — repetido y fuera de ventana se
    // descartan silenciosos; el mismo seq de nuevo NO entrega dos veces.
    #[test]
    fn replayed_and_out_of_window_datagrams_are_dropped() {
        let mut emitter = UdpEmitter::new(KEY, 0, 0);
        let datagrams = emitter.seal_au(1, 10, true, b"frame").unwrap();
        let mut r = UdpReassembler::new(KEY);
        assert_eq!(ingest_all(&mut r, &datagrams).len(), 1);
        // Rejugado exacto: repetido.
        assert_eq!(r.ingest(&datagrams[0]).unwrap(), None);
        // Muy detrás de la ventana tras avanzar el watermark: fuera.
        let stale = seal_data_datagram(&KEY, 0, 99, FLAG_FIRST | FLAG_LAST, 0, 1, b"old").unwrap();
        let fresh = seal_data_datagram(
            &KEY,
            REPLAY_WINDOW as u32,
            98,
            FLAG_FIRST | FLAG_LAST,
            0,
            1,
            b"new",
        )
        .unwrap();
        assert_eq!(r.ingest(&fresh).unwrap(), None); // AU no complete
        assert_eq!(r.ingest(&stale).unwrap(), None); // fuera de ventana
    }

    // TEST-0138 (CR-062): tope de 4 AUs pendientes — la más vieja se
    // abandona y su fragmento tardío ya no ensambla nada.
    #[test]
    fn pending_aus_cap_drops_oldest() {
        let mut r = UdpReassembler::new(KEY);
        // 4 AUs de 2 fragmentos, sin el último parche
        for au_id in 0..4_u16 {
            let datagram = seal_data_datagram(
                &KEY,
                au_id as u32,
                au_id,
                FLAG_FIRST,
                0,
                2,
                &h264_bytes(au_id as u8, MAX_PAYLOAD_BYTES),
            )
            .unwrap();
            assert_eq!(r.ingest(&datagram).unwrap(), None);
        }
        // 5ª AU obliga a drop de la más vieja (au_id 0).
        let fifth = seal_data_datagram(&KEY, 4, 4, FLAG_FIRST, 0, 2, b"x").unwrap();
        assert_eq!(r.ingest(&fifth).unwrap(), None);
        let tail_of_oldest = seal_data_datagram(&KEY, 5, 0, FLAG_LAST, 1, 2, b"y").unwrap();
        assert_eq!(r.ingest(&tail_of_oldest).unwrap(), None);
    }

    // TEST-0138 (CR-062): abandono de AU pendiente al superar el seq
    // corriente en ≥ 256.
    #[test]
    fn pending_au_abandoned_after_256_seq_gap() {
        let mut r = UdpReassembler::new(KEY);
        let head = seal_data_datagram(
            &KEY,
            0,
            0,
            FLAG_FIRST,
            0,
            2,
            &h264_bytes(1, MAX_PAYLOAD_BYTES),
        )
        .unwrap();
        assert_eq!(r.ingest(&head).unwrap(), None);
        let filler = seal_data_datagram(
            &KEY,
            REPLAY_WINDOW as u32,
            1,
            FLAG_FIRST | FLAG_LAST,
            0,
            1,
            b"filler",
        )
        .unwrap();
        assert_eq!(r.ingest(&filler).unwrap(), None);
        let tail =
            seal_data_datagram(&KEY, REPLAY_WINDOW as u32 + 1, 0, FLAG_LAST, 1, 2, b"end").unwrap();
        assert_eq!(r.ingest(&tail).unwrap(), None); // AU viva abandonada
    }

    // TEST-0138 (CR-062): latest-frame-wins — AU ensamblada con fn/ts
    // no estrictamente creciente no se entrega.
    #[test]
    fn stale_fn_or_ts_is_discarded() {
        let mut emitter = UdpEmitter::new(KEY, 0, 0);
        let datagrams = emitter.seal_au(7, 700, true, b"newer").unwrap();
        let mut r = UdpReassembler::new(KEY);
        assert_eq!(ingest_all(&mut r, &datagrams).len(), 1);
        let mut stale_emitter = UdpEmitter::new(KEY, 1_000, 0);
        let stale = stale_emitter.seal_au(6, 800, false, b"stale").unwrap();
        assert!(ingest_all(&mut r, &stale).is_empty());
    }

    // TEST-0138 (CR-062): recovery FEC XOR reconstruye EXACTAMENTE un
    // miembro faltante del grupo y completa la AU.
    #[test]
    fn fec_recovers_exactly_one_member() {
        let mut emitter = UdpEmitter::new(KEY, 0, 0);
        let datagrams = emitter
            .seal_au(4, 400, true, &h264_bytes(0xE1, 2_000))
            .unwrap();
        // datagrams: d0, d1, r(base 0, count 2). Perder d1.
        let kept = with_seq(datagrams, |seq| seq == 1);
        let mut r = UdpReassembler::new(KEY);
        let delivered = ingest_all(&mut r, &kept);
        assert_eq!(delivered.len(), 1);
        assert_eq!(delivered[0].fn_, 4);
        assert_eq!(delivered[0].annex_b, h264_bytes(0xE1, 2_000));
    }

    // TEST-0138 (CR-062): con ≥ 2 faltantes NO hay entrega (AU perdida).
    #[test]
    fn fec_gives_up_with_two_losses() {
        let mut emitter = UdpEmitter::new(KEY, 0, 0);
        let datagrams = emitter
            .seal_au(4, 400, true, &h264_bytes(0xE2, 2_000))
            .unwrap();
        let kept = with_seq(datagrams, |seq| seq == 0 || seq == 1);
        let mut r = UdpReassembler::new(KEY);
        assert!(ingest_all(&mut r, &kept).is_empty());
    }

    // TEST-0138 (CR-062): grupo de 4 cierra a mitad de AU; dos grupos
    // con una pérdida cada uno se recuperan de forma independiente.
    #[test]
    fn fec_group_closes_mid_au_and_recovers_both_holes() {
        let mut emitter = UdpEmitter::new(KEY, 0, 0);
        let datagrams = emitter
            .seal_au(8, 800, true, &h264_bytes(0x77, 6_800))
            .unwrap();
        // 5 fragmentos: d0..d3, r(base 0), d4, r(base 4). Perder d2 y d4.
        let kept = with_seq(datagrams, |seq| seq == 2 || seq == 5);
        let mut r = UdpReassembler::new(KEY);
        let delivered = ingest_all(&mut r, &kept);
        assert_eq!(delivered.len(), 1);
        assert_eq!(delivered[0].annex_b, h264_bytes(0x77, 6_800));
    }

    // TEST-0138 (CR-062): el recovery con framing inválido (flags o
    // frag_total) se descarta sin entregar y sin cerrar el canal.
    #[test]
    fn recovery_with_bad_framing_is_rejected() {
        let mut r = UdpReassembler::new(KEY);
        let mut fake = vec![0_u8; DATAGRAM_HEADER_BYTES + DATAGRAM_TAG_BYTES + 8];
        fake[..4].copy_from_slice(&DATA_MAGIC);
        assert_eq!(r.ingest(&fake).unwrap_err(), AuthenticationFailed);
        // El canal sigue vivo: un datagrama válido no entrega AU (sin
        // AU pendiente) pero el return es Ok.
        let datagram = seal_data_datagram(&KEY, 0, 9, FLAG_FIRST | FLAG_LAST, 0, 1, b"ok").unwrap();
        assert_eq!(r.ingest(&datagram).unwrap(), None);
    }
}
