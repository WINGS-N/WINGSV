//! Installs and removes the root routing described by a RoutingSpec.
//!
//! Every command is exec'd directly rather than handed to `sh -c`. A root daemon that
//! composes shell strings is one quoting mistake away from executing something it did
//! not mean to, and there is nothing here that needs a shell.
//!
//! Teardown never fails: rules that were never installed, or that something else
//! already removed, are not errors. It has to be safe to run against an unknown state,
//! because that is exactly the state left behind by an app the system killed.

use crate::rootd::{RoutingSpec, TproxySpec};
use std::process::{Command, Stdio};

const IP: &str = "ip";
const IPTABLES: &str = "iptables";
const IP6TABLES: &str = "ip6tables";

/// Runs a command, returning whether it exited 0. Output is discarded: callers either
/// do not care or check the status.
fn run(program: &str, args: &[&str]) -> bool {
    Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|status| status.success())
        .unwrap_or(false)
}

/// Best-effort variant for teardown, where "it was not there" is success.
fn run_quiet(program: &str, args: &[&str]) {
    let _ = run(program, args);
}

fn ip_family(v6: bool) -> Vec<&'static str> {
    if v6 {
        vec!["-6"]
    } else {
        vec![]
    }
}

fn rule_del_pref(v6: bool, pref: u32) {
    let pref = pref.to_string();
    let mut args = ip_family(v6);
    args.extend_from_slice(&["rule", "del", "pref", &pref]);
    run_quiet(IP, &args);
}

pub fn apply(spec: &RoutingSpec) -> Result<(), String> {
    // Start from a clean slate: a previous session may have left rules behind, and
    // stacking a second copy on top of them would make the priorities ambiguous.
    clear(spec);

    // Let our own uid reach the network outside the tunnel, or the relay cannot talk
    // to its server once the default route points into the tunnel.
    run_quiet(
        "ndc",
        &["network", "protect", "allow", &spec.app_uid.to_string()],
    );

    if spec.dhcp_workaround_enabled {
        let pref = spec.dhcp_workaround_priority.to_string();
        run_quiet(
            IP,
            &[
                "rule", "add", "iif", "lo", "uidrange", "0-0", "lookup", "97", "priority", &pref,
            ],
        );
    }

    let table = spec.upstream_table.to_string();
    for route in &spec.ipv4_default_routes {
        let mut args = vec!["route", "add", "table", &table];
        args.extend(route.split_whitespace());
        if !run(IP, &args) {
            return Err(format!("ip route add table {table} {route} failed"));
        }
    }
    for route in &spec.ipv6_default_routes {
        let mut args = vec!["-6", "route", "add", "table", &table];
        args.extend(route.split_whitespace());
        // IPv6 is optional: plenty of networks have no v6 default at all.
        run_quiet(IP, &args);
    }

    // The base rule steers root's own traffic; one rule per bypassed uid follows it.
    add_uid_rule(spec.rule_priority_start, 0, 0, &table);
    for (index, uid) in spec.bypass_uids.iter().enumerate() {
        let priority = spec.rule_priority_start + 1 + index as u32;
        if priority > spec.rule_priority_end {
            return Err(format!(
                "more bypass uids than the {}..{} priority block holds",
                spec.rule_priority_start, spec.rule_priority_end
            ));
        }
        add_uid_rule(priority, *uid, *uid, &table);
    }

    if spec.bypass_mark_enabled {
        apply_bypass_mark(spec);
    }
    if let Some(tproxy) = &spec.tproxy {
        apply_tproxy(tproxy)?;
    }
    Ok(())
}

fn add_uid_rule(priority: u32, uid_start: u32, uid_end: u32, table: &str) {
    let pref = priority.to_string();
    let range = format!("{uid_start}-{uid_end}");
    run_quiet(
        IP,
        &[
            "rule", "add", "pref", &pref, "uidrange", &range, "lookup", table,
        ],
    );
    run_quiet(
        IP,
        &[
            "-6", "rule", "add", "pref", &pref, "uidrange", &range, "lookup", table,
        ],
    );
}

/// Re-marks bypassed apps' egress with the underlying network's fwmark. Without it the
/// packets keep carrying the tunnel's netId mark and the real uplink drops them.
fn apply_bypass_mark(spec: &RoutingSpec) {
    let chain = spec.bypass_mark_chain.as_str();
    let mark = format!("0x{:x}", spec.bypass_mark_fwmark);
    for tool in [IPTABLES, IP6TABLES] {
        run_quiet(tool, &["-w", "-t", "mangle", "-N", chain]);
        run_quiet(tool, &["-w", "-t", "mangle", "-F", chain]);
        for uid in &spec.bypass_uids {
            let uid = uid.to_string();
            run_quiet(
                tool,
                &[
                    "-w",
                    "-t",
                    "mangle",
                    "-A",
                    chain,
                    "-m",
                    "owner",
                    "--uid-owner",
                    &uid,
                    "-j",
                    "MARK",
                    "--set-mark",
                    &mark,
                ],
            );
        }
        run_quiet(tool, &["-w", "-t", "mangle", "-A", "OUTPUT", "-j", chain]);
    }
}

fn apply_tproxy(spec: &TproxySpec) -> Result<(), String> {
    let table = spec.route_table.to_string();
    let pref = spec.rule_priority.to_string();
    let mark = format!("0x{:x}", spec.fwmark);
    if !run(
        IP,
        &[
            "route", "add", "local", "default", "dev", "lo", "table", &table,
        ],
    ) {
        return Err("ip route add local default dev lo failed".to_string());
    }
    run_quiet(
        IP,
        &[
            "rule", "add", "fwmark", &mark, "lookup", &table, "pref", &pref,
        ],
    );
    Ok(())
}

/// Removes everything apply() may have installed. Safe to call at any time, including
/// against rules left by an app that died: the priority block is swept by number, so
/// no bookkeeping has to survive the daemon.
pub fn clear(spec: &RoutingSpec) {
    for pref in spec.rule_priority_start..=spec.rule_priority_end {
        rule_del_pref(false, pref);
        rule_del_pref(true, pref);
    }
    rule_del_pref(false, spec.app_tunnel_priority);
    rule_del_pref(true, spec.app_tunnel_priority);

    let table = spec.upstream_table.to_string();
    run_quiet(IP, &["route", "flush", "table", &table]);
    run_quiet(IP, &["-6", "route", "flush", "table", &table]);

    if spec.dhcp_workaround_enabled {
        let pref = spec.dhcp_workaround_priority.to_string();
        run_quiet(
            IP,
            &[
                "rule", "del", "iif", "lo", "uidrange", "0-0", "lookup", "97", "priority", &pref,
            ],
        );
    }

    let chain = spec.bypass_mark_chain.as_str();
    if !chain.is_empty() {
        for tool in [IPTABLES, IP6TABLES] {
            run_quiet(tool, &["-w", "-t", "mangle", "-D", "OUTPUT", "-j", chain]);
            run_quiet(tool, &["-w", "-t", "mangle", "-F", chain]);
            run_quiet(tool, &["-w", "-t", "mangle", "-X", chain]);
        }
    }

    if let Some(tproxy) = &spec.tproxy {
        let table = tproxy.route_table.to_string();
        rule_del_pref(false, tproxy.rule_priority);
        run_quiet(IP, &["route", "flush", "table", &table]);
    }
}

/// Sums the byte counters of the MARK rules in the app's tproxy mangle chains - the
/// exact count of what was steered into xray.
///
/// The app polls these at most once a second because every read costs it a root shell;
/// here the chains are one exec away, so the throttle is not needed.
pub fn read_tproxy_mark_bytes(chains: &[String]) -> u64 {
    let mut total = 0u64;
    for tool in [IPTABLES, IP6TABLES] {
        for chain in chains {
            total += mark_bytes_in_chain(tool, chain);
        }
    }
    total
}

fn mark_bytes_in_chain(tool: &str, chain: &str) -> u64 {
    let output = Command::new(tool)
        .args(["-w", "-t", "mangle", "-nvxL", chain])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output();
    let output = match output {
        Ok(output) if output.status.success() => output,
        // A chain that does not exist yet contributes nothing.
        _ => return 0,
    };
    String::from_utf8_lossy(&output.stdout)
        .lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            let _packets = fields.next()?;
            let bytes: u64 = fields.next()?.parse().ok()?;
            // Column order is: pkts bytes target prot ...
            if fields.next()? == "MARK" {
                Some(bytes)
            } else {
                None
            }
        })
        .sum()
}
