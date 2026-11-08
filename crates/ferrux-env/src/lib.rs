// SPDX-License-Identifier: Apache-2.0
//! `ferrux-env`: Ferrux's filesystem layout and process environment.
//!
//! **The Android package name lives here** ([`APP_PACKAGE`]) and in
//! `android/gradle.properties` (`ferrux.applicationId`). Change both together:
//! every package built for Ferrux has the prefix compiled in, so the package
//! name is effectively permanent once packages ship.
//!
//! The layout mirrors Termux's (`files/usr`, `files/home`, `$PREFIX/tmp`) so
//! termux-packages build recipes work with only the package name changed.

pub mod bootstrap;

use std::path::{Path, PathBuf};

/// Expands to the Android application id as a string literal, so it can be
/// used in `concat!`.
#[macro_export]
macro_rules! app_package {
    () => {
        "dev.ferrux"
    };
}

/// Android application id.
pub const APP_PACKAGE: &str = app_package!();
/// The app's private data directory.
pub const DATA_DIR: &str = concat!("/data/data/", app_package!());
/// `Context.getFilesDir()`.
pub const FILES_DIR: &str = concat!("/data/data/", app_package!(), "/files");
/// `$PREFIX`: where packages are installed. Compiled into every package.
pub const PREFIX: &str = concat!("/data/data/", app_package!(), "/files/usr");
/// `$HOME`.
pub const HOME: &str = concat!("/data/data/", app_package!(), "/files/home");

/// Android's shell, used until the bootstrap is installed.
pub const SYSTEM_SHELL: &str = "/system/bin/sh";

/// Variables passed through from the app process to sessions when present.
/// Android tools (`am`, `pm`, `dalvikvm`, …) need them.
pub const PASSTHROUGH_VARS: &[&str] = &[
    "ANDROID_ART_ROOT",
    "ANDROID_ASSETS",
    "ANDROID_DATA",
    "ANDROID_I18N_ROOT",
    "ANDROID_ROOT",
    "ANDROID_RUNTIME_ROOT",
    "ANDROID_STORAGE",
    "ANDROID_TZDATA_ROOT",
    "BOOTCLASSPATH",
    "DEX2OATBOOTCLASSPATH",
    "EXTERNAL_STORAGE",
    "SYSTEMSERVERCLASSPATH",
];

/// Paths of a Ferrux installation, derived from the app's files directory.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layout {
    files_dir: PathBuf,
}

impl Default for Layout {
    /// The layout for [`APP_PACKAGE`] on Android.
    fn default() -> Self {
        Self::new(FILES_DIR)
    }
}

impl Layout {
    /// Layout rooted at `files_dir` (`Context.getFilesDir()`). Tests and
    /// desktop harnesses can point this anywhere.
    pub fn new(files_dir: impl Into<PathBuf>) -> Self {
        Self {
            files_dir: files_dir.into(),
        }
    }

    pub fn files_dir(&self) -> &Path {
        &self.files_dir
    }

    pub fn prefix(&self) -> PathBuf {
        self.files_dir.join("usr")
    }

    /// Where a bootstrap is extracted before being atomically renamed to
    /// [`Layout::prefix`].
    pub fn staging_prefix(&self) -> PathBuf {
        self.files_dir.join("usr-staging")
    }

    pub fn home(&self) -> PathBuf {
        self.files_dir.join("home")
    }

    pub fn bin(&self) -> PathBuf {
        self.prefix().join("bin")
    }

    /// `$TMPDIR` once bootstrapped (`$PREFIX/tmp`).
    pub fn prefix_tmp(&self) -> PathBuf {
        self.prefix().join("tmp")
    }

    /// `$TMPDIR` before the bootstrap exists.
    pub fn fallback_tmp(&self) -> PathBuf {
        self.files_dir.join("tmp")
    }

    /// Ferrux-managed files outside the prefix (regenerated on start).
    pub fn ferrux_dir(&self) -> PathBuf {
        self.files_dir.join("ferrux")
    }

    /// Startup file for interactive system-shell sessions (`$ENV`).
    pub fn shell_rc(&self) -> PathBuf {
        self.ferrux_dir().join("shrc")
    }

    /// Whether this layout matches the prefix packages are compiled for.
    pub fn matches_compiled_prefix(&self) -> bool {
        self.prefix() == Path::new(PREFIX)
    }

    /// Whether a bootstrap is installed (a shell exists in the prefix).
    pub fn is_bootstrapped(&self) -> bool {
        self.bin().join("sh").exists()
    }

    /// Whether `program` is in the app's data directory, where `execve()` is
    /// denied at targetSdk ≥ 29 (RESEARCH.md §2.1), so it must be started as
    /// `/system/bin/linker64 <program>`.
    pub fn needs_linker(&self, program: &Path) -> bool {
        program.starts_with(&self.files_dir)
    }

    /// The termux-exec `LD_PRELOAD` library, if installed.
    pub fn exec_preload_lib(&self) -> Option<PathBuf> {
        ["libtermux-exec-ld-preload.so", "libtermux-exec.so"]
            .iter()
            .map(|name| self.prefix().join("lib").join(name))
            .find(|p| p.exists())
    }
}

/// The program a new session runs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Shell {
    pub program: PathBuf,
    /// Arguments after `argv[0]`, e.g. `-l` for a login shell.
    pub args: Vec<String>,
    /// `argv[0]`, e.g. `-sh` to request a login shell. Lost when the program
    /// runs through the linker, hence [`Shell::args`].
    pub arg0: Option<String>,
    /// The program lives in the app data dir and must be run through the
    /// system linker on targetSdk ≥ 29.
    pub needs_linker: bool,
}

/// Picks the session shell: `$PREFIX/bin/bash -l` (or `sh -l`) once
/// bootstrapped, otherwise Android's `/system/bin/sh` as a login shell.
pub fn default_shell(layout: &Layout) -> Shell {
    // TODO(M4): honour a user-chosen shell (~/.ferrux/shell, chsh).
    if layout.is_bootstrapped() {
        for name in ["bash", "sh"] {
            let program = layout.bin().join(name);
            if program.exists() {
                return Shell {
                    needs_linker: layout.needs_linker(&program),
                    program,
                    args: vec!["-l".into()],
                    arg0: None,
                };
            }
        }
    }
    Shell {
        program: SYSTEM_SHELL.into(),
        args: Vec::new(),
        arg0: Some("-sh".into()),
        needs_linker: false,
    }
}

/// Builds the environment for a session.
///
/// `host_env` is the app process's environment (`std::env::vars()`); only
/// [`PASSTHROUGH_VARS`] are taken from it.
pub fn build_env<I, K, V>(layout: &Layout, host_env: I) -> Vec<(String, String)>
where
    I: IntoIterator<Item = (K, V)>,
    K: AsRef<str>,
    V: AsRef<str>,
{
    let path_str = |p: PathBuf| p.to_string_lossy().into_owned();
    let bootstrapped = layout.is_bootstrapped();
    let prefix = path_str(layout.prefix());
    let tmp = if bootstrapped {
        layout.prefix_tmp()
    } else {
        layout.fallback_tmp()
    };

    let mut env: Vec<(String, String)> = host_env
        .into_iter()
        .filter(|(k, _)| PASSTHROUGH_VARS.contains(&k.as_ref()))
        .map(|(k, v)| (k.as_ref().to_owned(), v.as_ref().to_owned()))
        .collect();
    env.sort();

    let mut set = |k: &str, v: String| env.push((k.to_owned(), v));
    set("HOME", path_str(layout.home()));
    set("PREFIX", prefix.clone());
    set("TMPDIR", path_str(tmp));
    set("PATH", format!("{prefix}/bin:/system/bin:/system/xbin"));
    set("TERM", "xterm-256color".into());
    set("COLORTERM", "truecolor".into());
    set("LANG", "en_US.UTF-8".into());
    set("FERRUX_VERSION", env!("CARGO_PKG_VERSION").into());
    set("FERRUX_PREFIX", prefix.clone());
    set("FERRUX_APP_PACKAGE", APP_PACKAGE.into());
    if !bootstrapped {
        // Android's mksh would read /system/etc/mkshrc, whose prompt is the
        // full path and scrolls the line editor on phone-width terminals.
        set("ENV", path_str(layout.shell_rc()));
    }
    if bootstrapped {
        set("SHELL", path_str(default_shell(layout).program));
        // Packages from termux-packages read these (termux-tools, termux-exec,
        // patched upstream code); they are part of the packages' ABI. We do
        // NOT export TERMUX_VERSION: tools use it to detect the Termux app and
        // its APIs (termux-am, Termux:API), which Ferrux does not provide.
        // See docs/PACKAGES.md.
        set("TERMUX__PREFIX", prefix.clone());
        set("TERMUX__ROOTFS", path_str(layout.files_dir().to_path_buf()));
        set("TERMUX__HOME", path_str(layout.home()));
        set("TERMUX_APP__PACKAGE_NAME", APP_PACKAGE.into());
    }
    if let Some(lib) = layout.exec_preload_lib() {
        // termux-exec: routes exec() of prefix programs through the system
        // linker and fixes /bin/sh-style shebangs.
        set("LD_PRELOAD", path_str(lib));
    }
    env
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constants_are_consistent() {
        assert_eq!(APP_PACKAGE, "dev.ferrux");
        assert_eq!(PREFIX, "/data/data/dev.ferrux/files/usr");
        assert_eq!(HOME, "/data/data/dev.ferrux/files/home");
        let layout = Layout::default();
        assert!(layout.matches_compiled_prefix());
        assert_eq!(layout.home(), Path::new(HOME));
        assert_eq!(layout.files_dir(), Path::new(FILES_DIR));
        assert!(DATA_DIR.ends_with(APP_PACKAGE));
    }

    #[test]
    fn unbootstrapped_layout_uses_system_shell() {
        let layout = Layout::new("/nonexistent/files");
        assert!(!layout.is_bootstrapped());
        assert!(!layout.matches_compiled_prefix());
        let shell = default_shell(&layout);
        assert_eq!(shell.program, Path::new(SYSTEM_SHELL));
        assert!(!shell.needs_linker);
    }

    #[test]
    fn env_contents() {
        let layout = Layout::new("/x/files");
        let host = [
            ("ANDROID_ROOT", "/system"),
            ("SECRET", "nope"),
            ("BOOTCLASSPATH", "/a.jar"),
        ];
        let env = build_env(&layout, host);
        let get = |k: &str| {
            env.iter()
                .find(|(key, _)| key == k)
                .map(|(_, v)| v.as_str())
        };
        assert_eq!(get("HOME"), Some("/x/files/home"));
        assert_eq!(get("PREFIX"), Some("/x/files/usr"));
        assert_eq!(get("TMPDIR"), Some("/x/files/tmp"));
        assert_eq!(
            get("PATH"),
            Some("/x/files/usr/bin:/system/bin:/system/xbin")
        );
        assert_eq!(get("TERM"), Some("xterm-256color"));
        assert_eq!(get("ANDROID_ROOT"), Some("/system"));
        assert_eq!(get("BOOTCLASSPATH"), Some("/a.jar"));
        assert_eq!(get("SECRET"), None);
        assert_eq!(get("LD_PRELOAD"), None);
        assert_eq!(get("ENV"), Some("/x/files/ferrux/shrc"));
        let mut keys: Vec<_> = env.iter().map(|(k, _)| k).collect();
        let n = keys.len();
        keys.dedup();
        assert_eq!(keys.len(), n, "no duplicate keys");
    }

    #[test]
    fn bootstrapped_layout_is_detected() {
        let dir = std::env::temp_dir().join(format!("ferrux-env-test-{}", std::process::id()));
        let layout = Layout::new(&dir);
        std::fs::create_dir_all(layout.bin()).unwrap();
        std::fs::create_dir_all(layout.prefix().join("lib")).unwrap();
        std::fs::write(layout.bin().join("sh"), "").unwrap();
        std::fs::write(layout.bin().join("bash"), "").unwrap();
        std::fs::write(layout.prefix().join("lib/libtermux-exec-ld-preload.so"), "").unwrap();

        let shell = default_shell(&layout);
        assert_eq!(shell.program, layout.bin().join("bash"));
        assert_eq!(shell.args, ["-l"]);
        assert!(shell.needs_linker);
        assert!(!layout.needs_linker(Path::new(SYSTEM_SHELL)));
        let env = build_env(&layout, std::iter::empty::<(&str, &str)>());
        let get = |k: &str| env.iter().find(|(key, _)| key == k).map(|(_, v)| v.clone());
        let bash = layout.bin().join("bash").to_string_lossy().into_owned();
        assert_eq!(get("SHELL").as_deref(), Some(bash.as_str()));
        assert_eq!(
            get("TERMUX__PREFIX"),
            Some(layout.prefix().to_string_lossy().into_owned())
        );
        assert_eq!(get("TERMUX_VERSION"), None);
        assert_eq!(get("ENV"), None, "mksh rc only without a bootstrap");
        assert_eq!(
            get("TMPDIR"),
            Some(layout.prefix_tmp().to_string_lossy().into_owned())
        );
        assert!(
            get("LD_PRELOAD")
                .unwrap()
                .ends_with("libtermux-exec-ld-preload.so")
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
