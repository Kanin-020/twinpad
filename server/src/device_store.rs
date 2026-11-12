//! Registro persistente de dispositivos emparejados (CR-094, SPEC-001 §8.2).
//!
//! Guarda `session_id` y `session_key` de cada dispositivo registrado en
//! `<carpeta de datos>/devices.bin`, cifrado con AES-256-GCM (`media_crypto`
//! de `twinpad-core`). La clave de cifrado vive en `<carpeta de datos>/device.key`
//! con permisos 0600. Nunca se guarda el token del QR.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use twinpad_core::media_crypto::{open_record, seal_record};
use twinpad_core::{SessionId, SessionKey};

const DEVICES_FILE: &str = "devices.bin";
const KEY_FILE: &str = "device.key";
const ENTRY_BYTES: usize = 16 + 32;

#[derive(Debug)]
pub enum DeviceStoreError {
    Io(std::io::Error),
    Corrupt,
    Crypto,
}

impl std::fmt::Display for DeviceStoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DeviceStoreError::Io(error) => write!(f, "E/S del registro de dispositivos: {error}"),
            DeviceStoreError::Corrupt => write!(f, "registro de dispositivos corrupto"),
            DeviceStoreError::Crypto => write!(f, "fallo de cifrado del registro de dispositivos"),
        }
    }
}

impl From<std::io::Error> for DeviceStoreError {
    fn from(error: std::io::Error) -> Self {
        DeviceStoreError::Io(error)
    }
}

/// Almacén en disco. No mantiene estado en memoria: cada operación lee o escribe el fichero.
pub struct DeviceStore {
    dir: PathBuf,
}

impl DeviceStore {
    pub fn new(dir: &Path) -> Self {
        Self {
            dir: dir.to_path_buf(),
        }
    }

    fn devices_path(&self) -> PathBuf {
        self.dir.join(DEVICES_FILE)
    }

    fn key_path(&self) -> PathBuf {
        self.dir.join(KEY_FILE)
    }

    /// Lee o crea la clave de cifrado (32 bytes aleatorios, permisos 0600).
    fn load_or_create_key(&self) -> Result<[u8; 32], DeviceStoreError> {
        match fs::read(self.key_path()) {
            Ok(bytes) => bytes.try_into().map_err(|_| DeviceStoreError::Corrupt),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                let mut key = [0u8; 32];
                getrandom::fill(&mut key).map_err(|_| DeviceStoreError::Crypto)?;
                fs::create_dir_all(&self.dir)?;
                let mut options = fs::OpenOptions::new();
                options.write(true).create_new(true);
                #[cfg(unix)]
                {
                    use std::os::unix::fs::OpenOptionsExt;
                    options.mode(0o600);
                }
                let mut file = options.open(self.key_path())?;
                file.write_all(&key)?;
                Ok(key)
            }
            Err(error) => Err(DeviceStoreError::Io(error)),
        }
    }

    /// Carga todos los dispositivos registrados. Sin fichero → lista vacía.
    pub fn load(&self) -> Result<Vec<(SessionId, SessionKey)>, DeviceStoreError> {
        let record = match fs::read(self.devices_path()) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(error) => return Err(DeviceStoreError::Io(error)),
        };
        let key = self.load_or_create_key()?;
        let plain = open_record(&key, 0, &record).map_err(|_| DeviceStoreError::Crypto)?;
        if plain.len() % ENTRY_BYTES != 0 {
            return Err(DeviceStoreError::Corrupt);
        }
        Ok(plain
            .chunks_exact(ENTRY_BYTES)
            .map(|chunk| {
                let mut sid = [0u8; 16];
                sid.copy_from_slice(&chunk[..16]);
                let mut sk = [0u8; 32];
                sk.copy_from_slice(&chunk[16..]);
                (SessionId(sid), SessionKey(sk))
            })
            .collect())
    }

    /// Guarda la lista completa de dispositivos (escritura atómica: temporal + renombrado).
    pub fn save(&self, devices: &[(SessionId, SessionKey)]) -> Result<(), DeviceStoreError> {
        let key = self.load_or_create_key()?;
        let mut plain = Vec::with_capacity(devices.len() * ENTRY_BYTES);
        for (sid, sk) in devices {
            plain.extend_from_slice(&sid.0);
            plain.extend_from_slice(&sk.0);
        }
        let record = seal_record(&key, 0, &plain).map_err(|_| DeviceStoreError::Crypto)?;
        fs::create_dir_all(&self.dir)?;
        let tmp = self.dir.join(format!("{DEVICES_FILE}.tmp"));
        fs::write(&tmp, &record)?;
        fs::rename(&tmp, self.devices_path())?;
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("twinpad-devices-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn saved_devices_survive_a_new_store_instance() {
        let dir = temp_dir("roundtrip");
        let devices = vec![(SessionId([1; 16]), SessionKey([2; 32]))];
        DeviceStore::new(&dir).save(&devices).expect("save");
        let loaded = DeviceStore::new(&dir).load().expect("load");
        assert_eq!(loaded, devices);
        let on_disk = fs::read(dir.join(DEVICES_FILE)).expect("read");
        assert!(
            !on_disk.windows(32).any(|w| w == [2u8; 32]),
            "la clave no debe quedar en claro"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn missing_file_loads_as_empty() {
        let dir = temp_dir("empty");
        assert!(DeviceStore::new(&dir).load().expect("load").is_empty());
    }
}
