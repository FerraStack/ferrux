// SPDX-License-Identifier: Apache-2.0
//! Platform-neutral logic behind the Ferrux app natives (Ferrux's Linux
//! environment: layout, default shell and env, spawning with the exec mode),
//! kept separate so it can be unit-tested on the desktop without a JVM. The
//! terminal side lives in `ferrux-jni-core`.

use std::sync::OnceLock;

use ferrux_env::Layout;
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
    let mut config = SpawnConfig::new(program)
        .args(args)
        .size(WinSize::new(rows.max(1), cols.max(1)));
    // A login-style argv[0] only for the default system shell.
    if shell.program.as_os_str() == program {
        config.arg0 = shell.arg0.map(Into::into);
        if shell.needs_linker {
            config.exec_mode = ExecMode::system_linker();
        }
    }
    config.env = env
        .into_iter()
        .filter_map(|kv| kv.split_once('=').map(|(k, v)| (k.into(), v.into())))
        .collect();
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
