//! Listener TCP media autenticado y relay opaco H.264 (SPEC-003 v1.4.0).

use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use twinpad_core::media_crypto::{
    MAX_MEDIA_RECORD_BYTES, client_proof, derive_media_key, open_record, seal_record, server_proof,
};
use twinpad_core::{SessionId, SessionKey};

pub const MEDIA_PORT: u16 = twinpad_core::media_crypto::MEDIA_PORT;
const MEDIA_HANDSHAKE_BYTES: usize = 16 + 32 + 32;

#[derive(Clone, Debug)]
pub struct AccessUnit {
    pub fn_: u64,
    pub timestamp_us: u64,
    pub idr: bool,
    pub annex_b: Arc<[u8]>,
}

#[derive(Clone, Debug)]
pub struct MediaSource {
    pub game_id: String,
    pub width: u16,
    pub height: u16,
    pub latest: AccessUnit,
}

#[derive(Default)]
pub struct MediaHub {
    sources: std::sync::Mutex<BTreeMap<String, MediaSource>>,
}

impl MediaHub {
    pub fn publish(&self, source: MediaSource) {
        if let Ok(mut sources) = self.sources.lock() {
            sources.insert(source.game_id.clone(), source);
        }
    }

    pub fn clear_game(&self, game_id: &str) {
        if let Ok(mut sources) = self.sources.lock() {
            sources.remove(game_id);
        }
    }

    fn latest_for(&self, game_id: &str) -> Option<MediaSource> {
        self.sources.lock().ok()?.get(game_id).cloned()
    }
}

pub type MediaCredential = (Arc<SessionKey>, Arc<AtomicBool>);
pub type MediaCredentialLookup = dyn Fn(&SessionId) -> Option<MediaCredential> + Send + Sync;
pub type SelectedGameLookup = dyn Fn(&SessionId) -> Option<String> + Send + Sync;
pub type MediaGameLookup = dyn Fn(&SessionId) -> Option<String> + Send + Sync;

pub fn run_media_listener(
    listener: TcpListener,
    keys: Arc<MediaCredentialLookup>,
    hub: Arc<MediaHub>,
    selected_game: Arc<SelectedGameLookup>,
    media_game: Arc<MediaGameLookup>,
) {
    for incoming in listener.incoming() {
        let Ok(stream) = incoming else { continue };
        let keys = Arc::clone(&keys);
        let hub = Arc::clone(&hub);
        let selected = Arc::clone(&selected_game);
        let media = Arc::clone(&media_game);
        std::thread::spawn(move || handle_media_client(stream, keys, hub, selected, media));
    }
}

fn handle_media_client(
    mut stream: TcpStream,
    keys: Arc<MediaCredentialLookup>,
    hub: Arc<MediaHub>,
    selected_game: Arc<SelectedGameLookup>,
    media_game: Arc<MediaGameLookup>,
) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(5)));
    let mut request = [0_u8; MEDIA_HANDSHAKE_BYTES];
    if stream.read_exact(&mut request).is_err() {
        return;
    }
    let mut session_bytes = [0_u8; 16];
    session_bytes.copy_from_slice(&request[..16]);
    let session_id = SessionId(session_bytes);
    let nonce_c: [u8; 32] = match request[16..48].try_into() {
        Ok(value) => value,
        Err(_) => return,
    };
    let proof: [u8; 32] = match request[48..].try_into() {
        Ok(value) => value,
        Err(_) => return,
    };
    let Some((key, pair_active)) = keys(&session_id) else {
        return;
    };
    let expected = client_proof(&key.0, &session_id.0, &nonce_c);
    if !constant_time_eq(&expected, &proof) || !pair_active.load(Ordering::Acquire) {
        return;
    }

    let mut nonce_s = [0_u8; 32];
    if getrandom::fill(&mut nonce_s).is_err() {
        return;
    }
    let response_proof = server_proof(&key.0, &session_id.0, &nonce_c, &nonce_s);
    if stream
        .write_all(&nonce_s)
        .and_then(|_| stream.write_all(&response_proof))
        .is_err()
    {
        return;
    }
    let media_key = derive_media_key(&key.0, &nonce_c, &nonce_s);
    let mut counter = 0_u64;
    let mut current_game = None::<String>;
    let mut last_fn = None;
    let mut last_timestamp_us = None;

    while pair_active.load(Ordering::Acquire) {
        let Some(game_id) = selected_game(&session_id) else {
            current_game = None;
            last_fn = None;
            last_timestamp_us = None;
            std::thread::sleep(Duration::from_millis(5));
            continue;
        };
        if current_game.as_deref() != Some(game_id.as_str()) {
            current_game = Some(game_id.clone());
            last_fn = None;
            last_timestamp_us = None;
        }
        if media_game(&session_id).as_deref() != Some(game_id.as_str()) {
            std::thread::sleep(Duration::from_millis(2));
            continue;
        }
        let Some(source) = hub.latest_for(&game_id) else {
            std::thread::sleep(Duration::from_millis(2));
            continue;
        };
        if source.width < 16
            || source.width > 3840
            || source.height < 16
            || source.height > 2160
            || source.latest.annex_b.is_empty()
        {
            return;
        }
        if last_fn.is_none() && !source.latest.idr {
            std::thread::sleep(Duration::from_millis(1));
            continue;
        }
        if last_fn.is_some_and(|last| source.latest.fn_ <= last)
            || last_timestamp_us.is_some_and(|last| source.latest.timestamp_us <= last)
        {
            std::thread::sleep(Duration::from_millis(1));
            continue;
        }
        let mut plaintext = Vec::with_capacity(17 + source.latest.annex_b.len());
        plaintext.extend_from_slice(&source.latest.fn_.to_be_bytes());
        plaintext.extend_from_slice(&source.latest.timestamp_us.to_be_bytes());
        plaintext.push(u8::from(source.latest.idr));
        plaintext.extend_from_slice(&source.latest.annex_b);
        if plaintext.len() + 24 > MAX_MEDIA_RECORD_BYTES {
            return;
        }
        let Ok(record) = seal_record(&media_key, counter, &plaintext) else {
            return;
        };
        if stream.write_all(&record).is_err() {
            return;
        }
        counter = match counter.checked_add(1) {
            Some(value) => value,
            None => return,
        };
        last_fn = Some(source.latest.fn_);
        last_timestamp_us = Some(source.latest.timestamp_us);
        // CR-029: sin pacing por fps; la próxima iteración espera por la
        // AU nueva (1 ms de poll) y retransmite en cuanto llega.
        std::thread::sleep(Duration::from_millis(1));
    }
}

pub fn validate_client_record(key: &[u8; 32], expected_counter: u64, record: &[u8]) -> bool {
    open_record(key, expected_counter, record).is_ok()
}

fn constant_time_eq(left: &[u8; 32], right: &[u8; 32]) -> bool {
    left.iter()
        .zip(right.iter())
        .fold(0_u8, |difference, (a, b)| difference | (a ^ b))
        == 0
}

pub fn read_record(stream: &mut TcpStream) -> std::io::Result<Vec<u8>> {
    let mut length = [0_u8; 4];
    stream.read_exact(&mut length)?;
    let length = u32::from_be_bytes(length) as usize;
    if !(24..=MAX_MEDIA_RECORD_BYTES).contains(&length) {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "invalid media record length",
        ));
    }
    let mut record = Vec::with_capacity(4 + length);
    record.extend_from_slice(&(length as u32).to_be_bytes());
    record.resize(4 + length, 0);
    stream.read_exact(&mut record[4..])?;
    Ok(record)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;
    use twinpad_core::media_crypto::{MediaCryptoError, seal_record};

    // TEST-072 — CR-014 listener authorization and relay primitives.
    #[test]
    fn only_exact_expected_counter_and_valid_authenticate_tag_are_accepted() {
        let key = [0x71; 32];
        let record = seal_record(&key, 0, b"unit").unwrap();
        assert!(validate_client_record(&key, 0, &record));
        assert!(!validate_client_record(&key, 1, &record));
        let mut bad = record;
        *bad.last_mut().unwrap() ^= 1;
        assert_eq!(
            open_record(&key, 0, &bad),
            Err(MediaCryptoError::AuthenticationFailed)
        );
    }

    fn h264_source(fn_: u64, timestamp_us: u64, idr: bool, bytes: &[u8]) -> MediaSource {
        MediaSource {
            game_id: "cemu".into(),
            width: 64,
            height: 64,
            latest: AccessUnit {
                fn_,
                timestamp_us,
                idr,
                annex_b: Arc::from(bytes),
            },
        }
    }

    #[test]
    fn authenticated_loopback_relay_waits_for_selected_source_and_forwards_latest_aus() {
        use std::io::{Read, Write};

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (server, _) = listener.accept().unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let session_id = SessionId([0x23; 16]);
        let session_key = Arc::new(SessionKey([0x41; 32]));
        let active = Arc::new(AtomicBool::new(true));
        let session_id_for_lookup = session_id;
        let key_for_lookup = Arc::clone(&session_key);
        let active_for_lookup = Arc::clone(&active);
        let keys: Arc<MediaCredentialLookup> = Arc::new(move |requested| {
            (*requested == session_id_for_lookup)
                .then(|| (Arc::clone(&key_for_lookup), Arc::clone(&active_for_lookup)))
        });
        let selected: Arc<SelectedGameLookup> = Arc::new(|_| Some("cemu".into()));
        let media_game_slot = Arc::new(std::sync::Mutex::new(None::<String>));
        let media_game_for_lookup = Arc::clone(&media_game_slot);
        let media_game: Arc<MediaGameLookup> =
            Arc::new(move |_| media_game_for_lookup.lock().unwrap().clone());
        let hub = Arc::new(MediaHub::default());
        hub.publish(h264_source(0, 100, true, b"idr-sps-pps"));
        let server_hub = Arc::clone(&hub);
        let server_thread = std::thread::spawn(move || {
            handle_media_client(server, keys, server_hub, selected, media_game);
        });

        let nonce_c = [0x5a; 32];
        let mut request = Vec::with_capacity(MEDIA_HANDSHAKE_BYTES);
        request.extend_from_slice(&session_id.0);
        request.extend_from_slice(&nonce_c);
        request.extend_from_slice(&client_proof(&session_key.0, &session_id.0, &nonce_c));
        client.write_all(&request).unwrap();
        let mut nonce_s = [0_u8; 32];
        let mut proof = [0_u8; 32];
        client.read_exact(&mut nonce_s).unwrap();
        client.read_exact(&mut proof).unwrap();
        assert_eq!(
            proof,
            server_proof(&session_key.0, &session_id.0, &nonce_c, &nonce_s)
        );
        let media_key = derive_media_key(&session_key.0, &nonce_c, &nonce_s);

        client
            .set_read_timeout(Some(Duration::from_millis(80)))
            .unwrap();
        assert!(
            matches!(read_record(&mut client), Err(error) if error.kind() == std::io::ErrorKind::WouldBlock || error.kind() == std::io::ErrorKind::TimedOut)
        );
        *media_game_slot.lock().unwrap() = Some("cemu".into());
        client
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let idr_record = read_record(&mut client).unwrap();
        let idr = open_record(&media_key, 0, &idr_record).unwrap();
        assert_eq!(&idr[..8], &0_u64.to_be_bytes());
        assert_eq!(&idr[8..16], &100_u64.to_be_bytes());
        assert_eq!(idr[16], 1);
        assert_eq!(&idr[17..], b"idr-sps-pps");

        hub.publish(h264_source(1, 200, false, b"p-frame"));
        let p_record = read_record(&mut client).unwrap();
        let p_frame = open_record(&media_key, 1, &p_record).unwrap();
        assert_eq!(&p_frame[..8], &1_u64.to_be_bytes());
        assert_eq!(&p_frame[8..16], &200_u64.to_be_bytes());
        assert_eq!(p_frame[16], 0);
        assert_eq!(&p_frame[17..], b"p-frame");

        active.store(false, Ordering::Release);
        let mut after_revoke = [0_u8; 1];
        assert_eq!(client.read(&mut after_revoke).unwrap(), 0);
        server_thread.join().unwrap();
    }

    #[test]
    fn media_listener_closes_loopback_client_with_invalid_handshake_proof() {
        use std::io::{Read, Write};

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (server, _) = listener.accept().unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let session_id = SessionId([0x31; 16]);
        let key = Arc::new(SessionKey([0x72; 32]));
        let active = Arc::new(AtomicBool::new(true));
        let lookup_id = session_id;
        let lookup_key = Arc::clone(&key);
        let lookup_active = Arc::clone(&active);
        let keys: Arc<MediaCredentialLookup> = Arc::new(move |requested| {
            (*requested == lookup_id).then(|| (Arc::clone(&lookup_key), Arc::clone(&lookup_active)))
        });
        let selected: Arc<SelectedGameLookup> = Arc::new(|_| None);
        let media_game: Arc<MediaGameLookup> = Arc::new(|_| None);
        let hub = Arc::new(MediaHub::default());
        let server_thread = std::thread::spawn(move || {
            handle_media_client(server, keys, hub, selected, media_game);
        });
        let mut request = Vec::with_capacity(MEDIA_HANDSHAKE_BYTES);
        request.extend_from_slice(&session_id.0);
        request.extend_from_slice(&[0x11; 32]);
        request.extend_from_slice(&[0_u8; 32]);
        client.write_all(&request).unwrap();
        let mut response = [0_u8; 1];
        assert_eq!(client.read(&mut response).unwrap(), 0);
        server_thread.join().unwrap();
    }
}
