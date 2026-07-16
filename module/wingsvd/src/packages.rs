//! Resolves the app's uid from the package database.
//!
//! Read fresh on every connection rather than cached: the uid changes whenever the app
//! is reinstalled, and a stale value would either lock the real app out or - far worse -
//! hand root to whatever package inherited the old number.

use std::fs::File;
use std::io::{BufRead, BufReader};

const PACKAGES_LIST: &str = "/data/system/packages.list";

/// Each line is "<package> <uid> <debuggable> <dataDir> <seinfo> ...".
pub fn app_uid(package: &str) -> Option<u32> {
    let file = File::open(PACKAGES_LIST).ok()?;
    for line in BufReader::new(file).lines().map_while(Result::ok) {
        let mut fields = line.split(' ');
        if fields.next() != Some(package) {
            continue;
        }
        return fields.next().and_then(|uid| uid.parse().ok());
    }
    None
}
