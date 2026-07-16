//! Length-prefixed framing: a big-endian u32 followed by one encoded message.
//! Mirrors the app side (DaemonIpc.kt) and vpnhotspotd's control/wire.rs, cap included.

use std::io::{self, Read, Write};
use std::os::unix::net::UnixStream;

/// Matches Android's documented Binder transaction buffer size, which is what the
/// existing daemon uses; nothing we send comes close, so a frame above it means a
/// desynchronised or hostile stream rather than a big message.
const MAX_FRAME_SIZE: usize = 1024 * 1024;

/// Reads one frame, or None at a clean EOF (the client went away).
pub fn read_frame(mut stream: &UnixStream) -> io::Result<Option<Vec<u8>>> {
    let mut header = [0u8; 4];
    match stream.read_exact(&mut header) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(error) => return Err(error),
    }
    let len = u32::from_be_bytes(header) as usize;
    if len > MAX_FRAME_SIZE {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("frame of {len} bytes"),
        ));
    }
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body)?;
    Ok(Some(body))
}

pub fn write_frame(mut stream: &UnixStream, body: &[u8]) -> io::Result<()> {
    if body.len() > MAX_FRAME_SIZE {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "frame too large",
        ));
    }
    stream.write_all(&(body.len() as u32).to_be_bytes())?;
    stream.write_all(body)?;
    stream.flush()
}
