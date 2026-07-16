//! wingsvd - the optional WINGS V root helper.
//!
//! It exists because kernel routing state must not be owned by a process Android is
//! free to kill. The app's teardown runs under a foreground-service contract and the
//! low-memory killer; when it loses that race the ip rules survive their tunnel and
//! take the device's connectivity with them. Here the same work is owned by a daemon
//! init supervises, so no Android lifecycle rule applies to it.
//!
//! Security model, in one place:
//!   * the socket lives in the abstract namespace, so any process may *reach* it;
//!   * therefore every connection is authenticated by SO_PEERCRED against the app's
//!     uid, which the kernel fills in and a caller cannot forge;
//!   * the uid is resolved per connection from the package database, because it
//!     changes whenever the app is reinstalled;
//!   * the daemon never runs a string it was handed: routing is rebuilt from a typed
//!     spec, and only binaries inside the app's native library dir may be exec'd.

mod children;
mod packages;
mod routing;
mod wire;

pub mod rootd {
    include!(concat!(env!("OUT_DIR"), "/wingsv.rootd.rs"));
}

use children::Children;
use prost::Message;
use rootd::client_envelope::Command as ClientCommand;
use rootd::daemon_envelope::Frame;
use rootd::reply_frame::Payload;
use rootd::{
    Ack, ClientEnvelope, Counters, DaemonEnvelope, ErrorFrame, HelloReply, ReplyFrame, RoutingSpec,
    SessionState,
};
use std::io;
// std gates the abstract-socket extension on target_os, so the same trait lives under
// two names even though the kernel behaviour is identical. The daemon only ever ships
// for Android; the linux arm exists so it still builds - and so clippy still runs - on
// a development host.
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(target_os = "linux")]
use std::os::linux::net::SocketAddrExt;
use std::os::unix::io::AsRawFd;
use std::os::unix::net::{SocketAddr, UnixListener, UnixStream};

const SOCKET_NAME: &str = "wings.v.rootd";
const APP_PACKAGE: &str = "wings.v";
const PROTOCOL_VERSION: u32 = 1;
/// Oldest client we still speak to. Moves only when semantics change, never when a
/// field is added.
const MIN_SUPPORTED: u32 = 1;
const MODULE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// What the daemon believes it installed. Survives the client that asked for it: an
/// app that disappears is almost always the low-memory killer rather than a user who
/// wanted the tunnel down, so the session is kept and marked orphaned instead of being
/// torn down under them.
#[derive(Default)]
struct Session {
    spec: Option<RoutingSpec>,
    orphaned: bool,
    children: Children,
}

fn main() {
    let addr = match SocketAddr::from_abstract_name(SOCKET_NAME) {
        Ok(addr) => addr,
        Err(error) => {
            eprintln!("wingsvd: abstract name {SOCKET_NAME}: {error}");
            std::process::exit(1);
        }
    };
    let listener = match UnixListener::bind_addr(&addr) {
        Ok(listener) => listener,
        Err(error) => {
            // Another instance already holds the name; the module's service.sh restarts
            // us, so exiting is the right move rather than fighting over it.
            eprintln!("wingsvd: bind @{SOCKET_NAME}: {error}");
            std::process::exit(1);
        }
    };
    eprintln!("wingsvd {MODULE_VERSION} listening on @{SOCKET_NAME}");

    let mut session = Session::default();
    for stream in listener.incoming() {
        match stream {
            Ok(stream) => serve(stream, &mut session),
            Err(error) => eprintln!("wingsvd: accept: {error}"),
        }
    }
}

/// Serves one client to completion. Connections are handled one at a time on purpose:
/// there is exactly one session, and a lock-free single thread is easier to be sure
/// about than concurrent mutation of routing state.
fn serve(stream: UnixStream, session: &mut Session) {
    let uid = match peer_uid(&stream) {
        Ok(uid) => uid,
        Err(error) => {
            eprintln!("wingsvd: SO_PEERCRED failed, dropping connection: {error}");
            return;
        }
    };
    match packages::app_uid(APP_PACKAGE) {
        Some(expected) if expected == uid => {}
        Some(expected) => {
            eprintln!("wingsvd: rejected uid={uid} (expected {expected})");
            return;
        }
        None => {
            eprintln!("wingsvd: rejected uid={uid}: {APP_PACKAGE} not installed");
            return;
        }
    }

    if session.spec.is_some() {
        // Someone is back for a session that outlived its owner.
        session.orphaned = false;
    }

    loop {
        let request = match wire::read_frame(&stream) {
            Ok(Some(bytes)) => bytes,
            // EOF. The routing deliberately stays up; only an explicit clear, or a
            // returning app that decides otherwise, takes it down.
            Ok(None) => break,
            Err(error) => {
                eprintln!("wingsvd: read: {error}");
                break;
            }
        };
        let envelope = match ClientEnvelope::decode(request.as_slice()) {
            Ok(envelope) => envelope,
            Err(error) => {
                eprintln!("wingsvd: malformed envelope: {error}");
                break;
            }
        };
        let call_id = envelope.call_id;
        let frame = match dispatch(envelope, session) {
            Ok(payload) => Frame::Reply(ReplyFrame {
                payload: Some(payload),
            }),
            Err(message) => Frame::Error(ErrorFrame { message }),
        };
        let response = DaemonEnvelope {
            call_id,
            frame: Some(frame),
        };
        if let Err(error) = wire::write_frame(&stream, &response.encode_to_vec()) {
            eprintln!("wingsvd: write: {error}");
            break;
        }
    }

    if session.spec.is_some() {
        session.orphaned = true;
        eprintln!("wingsvd: client gone, keeping the session (orphaned)");
    }
}

fn dispatch(envelope: ClientEnvelope, session: &mut Session) -> Result<Payload, String> {
    let command = envelope
        .command
        .ok_or_else(|| "empty command".to_string())?;
    match command {
        ClientCommand::Hello(hello) => {
            // Answer even when the versions disagree: the client needs our numbers to
            // tell the user which side is behind.
            eprintln!(
                "wingsvd: hello from app {} (protocol {})",
                hello.app_version, hello.protocol_version
            );
            Ok(Payload::Hello(HelloReply {
                protocol_version: PROTOCOL_VERSION,
                min_supported: MIN_SUPPORTED,
                module_version: MODULE_VERSION.to_string(),
                caps: vec![
                    "routing".to_string(),
                    "children".to_string(),
                    "counters".to_string(),
                ],
            }))
        }
        ClientCommand::ApplyRouting(command) => {
            let spec = command
                .spec
                .ok_or_else(|| "apply_routing without a spec".to_string())?;
            routing::apply(&spec)?;
            session.spec = Some(spec);
            session.orphaned = false;
            Ok(Payload::Ack(Ack {}))
        }
        ClientCommand::ClearRouting(_) => {
            if let Some(spec) = session.spec.take() {
                routing::clear(&spec);
            }
            session.children.kill_all();
            session.orphaned = false;
            Ok(Payload::Ack(Ack {}))
        }
        ClientCommand::SessionState(_) => Ok(Payload::SessionState(SessionState {
            routing_active: session.spec.is_some(),
            orphaned: session.orphaned,
            tunnel_name: session
                .spec
                .as_ref()
                .map(|spec| spec.tunnel_name.clone())
                .unwrap_or_default(),
            children: session.children.snapshot(),
        })),
        ClientCommand::SpawnChild(command) => {
            let handle = session.children.spawn(
                command.kind,
                &command.binary_path,
                &command.args,
                &command.working_dir,
            )?;
            Ok(Payload::Child(handle))
        }
        ClientCommand::KillChild(command) => {
            session.children.kill(command.child_id)?;
            Ok(Payload::Ack(Ack {}))
        }
        ClientCommand::ReadCounters(_) => {
            let spec = session
                .spec
                .as_ref()
                .ok_or_else(|| "no active session".to_string())?;
            let tproxy_mark_bytes = match &spec.tproxy {
                Some(tproxy) => routing::read_tproxy_mark_bytes(&tproxy.mark_chains),
                None => 0,
            };
            Ok(Payload::Counters(Counters { tproxy_mark_bytes }))
        }
    }
}

/// The kernel fills these in on the socket, so a client cannot lie about who it is.
fn peer_uid(stream: &UnixStream) -> io::Result<u32> {
    let mut cred: libc::ucred = unsafe { std::mem::zeroed() };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut libc::ucred as *mut libc::c_void,
            &mut len,
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(cred.uid)
}
