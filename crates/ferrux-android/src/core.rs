// SPDX-License-Identifier: Apache-2.0
//! Platform-neutral logic behind the Ferrux app natives (Ferrux's Linux
//! environment: layout, default shell and env, spawning with the exec mode),
//! kept separate so it can be unit-tested on the desktop without a JVM. The
//! terminal side lives in `ferrux-jni-core`.

use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};

use ferrux_env::Layout;
use ferrux_env::bootstrap::{PinnedBootstrap, Stage};
use ferrux_jni_core::core::spawn_session;
use ferrux_pty::{ExecMode, SpawnConfig, SpawnError, WinSize};
use ferrux_session::SessionId;

static LAYOUT: OnceLock<Layout> = OnceLock::new();

/// Sets the layout from the app's real files dir. First call wins.
pub fn init(files_dir: &str) {
    // Android reports /data/user/0/<pkg>/files; /data/data/<pkg> is the same
    // directory and is the path packages are compiled for, so prefer it.
    let canonical = |p: &str| std::fs::canonicalize(p).ok();
    let layout = LAYOUT.get_or_init(|| {
        if canonical(files_dir).is_some()
            && canonical(files_dir) == canonical(ferrux_env::FILES_DIR)
        {
            Layout::default()
        } else {
            Layout::new(files_dir)
        }
    });
    if !layout.matches_compiled_prefix() {
        log::warn!(
            "files dir {} does not match the compiled prefix {}; packages built for {} will not work",
            layout.files_dir().display(),
            ferrux_env::PREFIX,
            ferrux_env::APP_PACKAGE
        );
    }
    if let Err(e) = ferrux_env::bootstrap::prepare_dirs(layout) {
        log::error!("cannot create home/tmp dirs: {e}");
    }
}

pub fn layout() -> &'static Layout {
    LAYOUT.get_or_init(Layout::default)
}

/// Progress of the running [`install_bootstrap`]: stage (0 = idle,
/// 1 = verify, 2 = extract, 3 = finish), done, total.
static INSTALL_PROGRESS: [AtomicU64; 3] = [AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0)];

/// Whether `$PREFIX` holds an installed bootstrap.
pub fn is_bootstrapped() -> bool {
    layout().is_bootstrapped()
}

/// `[url, sha256, release]` of the bootstrap pinned for this ABI, if any.
pub fn bootstrap_info() -> Option<[String; 3]> {
    let pin = PinnedBootstrap::current()?;
    Some([
        pin.url(),
        pin.sha256.to_owned(),
        ferrux_env::bootstrap::BOOTSTRAP_RELEASE.to_owned(),
    ])
}

/// Verifies and installs a downloaded bootstrap archive (blocking). Returns
/// an error message on failure.
pub fn install_bootstrap(archive: &str, sha256: &str) -> Result<(), String> {
    let set = |stage: u64, done: u64, total: u64| {
        INSTALL_PROGRESS[1].store(done, Ordering::Relaxed);
        INSTALL_PROGRESS[2].store(total, Ordering::Relaxed);
        INSTALL_PROGRESS[0].store(stage, Ordering::Relaxed);
    };
    let result = ferrux_env::bootstrap::install(
        layout(),
        std::path::Path::new(archive),
        sha256,
        &mut |stage, done, total| {
            let stage = match stage {
                Stage::Verify => 1,
                Stage::Extract => 2,
                Stage::Finish => 3,
            };
            set(stage, done, total);
        },
    );
    set(0, 0, 0);
    result.map_err(|e| {
        log::error!("{e}");
        e.to_string()
    })
}

/// `[stage, done, total]` of the running install (see [`INSTALL_PROGRESS`]).
pub fn bootstrap_progress() -> [i64; 3] {
    INSTALL_PROGRESS
        .each_ref()
        .map(|v| v.load(Ordering::Relaxed) as i64)
}

/// Path of the default shell.
pub fn default_shell() -> String {
    ferrux_env::default_shell(layout())
        .program
        .to_string_lossy()
        .into_owned()
}

/// The default session environment as `KEY=VALUE` strings.
pub fn default_env() -> Vec<String> {
    ferrux_env::build_env(layout(), std::env::vars())
        .into_iter()
        .map(|(k, v)| format!("{k}={v}"))
        .collect()
}

/// Creates a session; `env` entries are `KEY=VALUE`.
pub fn create_session(
    program: &str,
    args: Vec<String>,
    env: Vec<String>,
    cwd: &str,
    rows: u16,
    cols: u16,
) -> Result<SessionId, SpawnError> {
    let shell = ferrux_env::default_shell(layout());
    let is_default_shell = shell.program.as_os_str() == program;
    // The default shell gets its login flags (`-l`, or a `-sh` argv[0]) unless
    // the caller passed arguments of its own.
    let args = if is_default_shell && args.is_empty() {
        shell.args.clone()
    } else {
        args
    };
    let mut config = SpawnConfig::new(program)
        .args(args)
        .size(WinSize::new(rows.max(1), cols.max(1)));
    if is_default_shell {
        config.arg0 = shell.arg0.map(Into::into);
    }
    config.env = env
        .into_iter()
        .filter_map(|kv| kv.split_once('=').map(|(k, v)| (k.into(), v.into())))
        .collect();
    // Programs in the app data dir cannot be exec'd directly (W^X); run them
    // as `linker64 <program>`, like termux-exec does for their children.
    if layout().needs_linker(std::path::Path::new(program)) {
        config.exec_mode = ExecMode::system_linker();
        config
            .env
            .push(("TERMUX_EXEC__PROC_SELF_EXE".into(), program.into()));
    }
    if !cwd.is_empty() {
        config.cwd = Some(cwd.into());
    }
    spawn_session(&config)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ferrux_jni_core::core::{await_change, destroy_session, poll_events, screen_text};
    use std::time::{Duration, Instant};

    #[test]
    fn create_session_parses_env_and_runs() {
        let id = create_session(
            "/bin/sh",
            vec!["-c".into(), "printf '%s' \"$FOO\"; exit 4".into()],
            vec![
                "PATH=/usr/bin:/bin".into(),
                "FOO=a=b".into(),
                "BROKEN".into(),
            ],
            "/",
            5,
            20,
        )
        .unwrap() as i64;
        let start = Instant::now();
        let mut seq = 0;
        let mut events = Vec::new();
        while !events.iter().any(|e: &String| e.starts_with('X')) {
            assert!(
                start.elapsed() < Duration::from_secs(10),
                "no exit: {events:?}"
            );
            seq = await_change(seq, 100);
            events.extend(poll_events(id).unwrap());
        }
        assert_eq!(events, ["X4"]);
        let start = Instant::now();
        while screen_text(id).as_deref() != Some("a=b") {
            assert!(
                start.elapsed() < Duration::from_secs(10),
                "{:?}",
                screen_text(id)
            );
            seq = await_change(seq, 100);
        }
        destroy_session(id);
    }

    #[test]
    fn missing_program_is_an_error() {
        assert!(create_session("/nonexistent", vec![], vec![], "", 5, 20).is_err());
    }

    #[test]
    fn env_and_shell_defaults() {
        let env = default_env();
        assert!(env.iter().any(|kv| kv.starts_with("HOME=")));
        assert!(env.iter().any(|kv| kv == "TERM=xterm-256color"));
        assert!(!default_shell().is_empty());
    }
}
