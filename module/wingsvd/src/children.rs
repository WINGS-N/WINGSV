//! Runs xray / vk-turn-proxy as children of the daemon rather than of the app.
//!
//! The point is ownership: children re-parented here keep running when the app dies
//! (which is the whole reason the session survives an LMK kill), and they are reaped
//! deterministically on teardown instead of being hunted down by scanning /proc for a
//! cmdline, which is what the app has to do today.

use crate::rootd::{ChildHandle, ChildKind};
use std::collections::HashMap;
use std::path::Path;
use std::process::{Child, Command, Stdio};

#[derive(Default)]
pub struct Children {
    next_id: u64,
    running: HashMap<u64, Entry>,
}

struct Entry {
    kind: i32,
    child: Child,
}

/// The uid check on the socket is the trust boundary, but it should not be the only
/// thing standing between a client and root exec: a binary is only startable if it
/// lives in the app's extracted native library directory, which nothing but the
/// package manager can write.
fn is_allowed_binary(path: &str) -> bool {
    let path = Path::new(path);
    if !path.is_absolute() || !path.is_file() {
        return false;
    }
    // Reject symlinks and .. games by resolving first.
    let resolved = match path.canonicalize() {
        Ok(resolved) => resolved,
        Err(_) => return false,
    };
    let text = resolved.to_string_lossy();
    text.starts_with("/data/app/") && text.contains("/lib/")
}

impl Children {
    pub fn spawn(
        &mut self,
        kind: i32,
        binary_path: &str,
        args: &[String],
        working_dir: &str,
    ) -> Result<ChildHandle, String> {
        if kind == ChildKind::Unspecified as i32 {
            return Err("child kind not set".to_string());
        }
        if !is_allowed_binary(binary_path) {
            return Err(format!(
                "refusing to exec {binary_path}: not an app native library"
            ));
        }
        let mut command = Command::new(binary_path);
        command
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        if !working_dir.is_empty() {
            command.current_dir(working_dir);
        }
        let child = command
            .spawn()
            .map_err(|error| format!("spawn {binary_path}: {error}"))?;
        let pid = child.id() as i32;
        self.next_id += 1;
        let child_id = self.next_id;
        self.running.insert(child_id, Entry { kind, child });
        Ok(ChildHandle {
            kind,
            child_id,
            pid,
            running: true,
        })
    }

    pub fn kill(&mut self, child_id: u64) -> Result<(), String> {
        match self.running.remove(&child_id) {
            // Already gone is the outcome the caller wanted, not an error.
            None => Ok(()),
            Some(mut entry) => {
                let _ = entry.child.kill();
                let _ = entry.child.wait();
                Ok(())
            }
        }
    }

    pub fn kill_all(&mut self) {
        let ids: Vec<u64> = self.running.keys().copied().collect();
        for id in ids {
            let _ = self.kill(id);
        }
    }

    /// Handles for the session state, reaping any child that exited on its own so the
    /// app is not told something is running when it is not.
    pub fn snapshot(&mut self) -> Vec<ChildHandle> {
        let mut handles = Vec::new();
        let mut dead = Vec::new();
        for (id, entry) in self.running.iter_mut() {
            let running = matches!(entry.child.try_wait(), Ok(None));
            if !running {
                dead.push(*id);
            }
            handles.push(ChildHandle {
                kind: entry.kind,
                child_id: *id,
                pid: entry.child.id() as i32,
                running,
            });
        }
        for id in dead {
            self.running.remove(&id);
        }
        handles
    }
}
