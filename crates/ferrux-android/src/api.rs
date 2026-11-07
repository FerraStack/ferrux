// SPDX-License-Identifier: Apache-2.0
//! The Ferrux API v1 natives (docs/API.md) on `dev.ferrux.app.api.ApiNative`,
//! registered by `JNI_OnLoad`. Thin adapters over [`ferrux_host`]; errors
//! become `IllegalStateException("E<code>: message")` (`dev.ferrux.api.ApiErrors`),
//! which Binder passes to the client unchanged.

use std::os::fd::{IntoRawFd, OwnedFd};
use std::sync::OnceLock;
use std::time::Duration;

use ferrux_host::files::{FileStat, OpenMode};
use ferrux_host::{Host, HostError};
use ferrux_jni_core::JResult;
use jni::objects::JString;
use jni::strings::{JNIStr, JNIString};
use jni::{Env, NativeMethod, jni_str, native_method};

/// The Kotlin class in the app module that declares the API natives.
pub const API_CLASS: &JNIStr = jni_str!("dev/ferrux/app/api/ApiNative");

/// The API natives.
// jni-macros 0.22.4 expands its ABI check to `AtomicBool::fetch_update`,
// deprecated since Rust 1.99.
#[allow(deprecated)]
pub const API_NATIVES: &[NativeMethod<'static>] = &[
    native_method! {
        static fn spawn(
            argv: JString[],
            environment: JString[],
            clear_env: jboolean,
            cwd: JString,
            pty: jboolean,
            merge_stderr: jboolean,
            rows: jint,
            cols: jint,
            owner: jlong,
        ) -> jlong[],
        fn = natives::spawn,
    },
    native_method! {
        static fn resize(handle: jlong, rows: jint, cols: jint),
        fn = natives::resize,
    },
    native_method! { static fn signal(handle: jlong, signo: jint), fn = natives::signal },
    native_method! { static fn kill(handle: jlong), fn = natives::kill },
    native_method! { static fn close(handle: jlong), fn = natives::close },
    native_method! { static fn close_owner(owner: jlong) -> jint, fn = natives::close_owner },
    native_method! { static fn owner_of(handle: jlong) -> jlong, fn = natives::owner_of },
    native_method! { static fn live_count() -> jint, fn = natives::live_count },
    native_method! { static fn owner_limit() -> jint, fn = natives::owner_limit },
    native_method! {
        static fn await_exits(timeout_ms: jint) -> jlong[],
        fn = natives::await_exits,
    },
    native_method! { static fn info() -> JString[], fn = natives::info },
    native_method! { static fn stat(path: JString) -> JString, fn = natives::stat },
    native_method! {
        static fn list(path: JString, offset: jint, limit: jint) -> JString[],
        fn = natives::list,
    },
    native_method! {
        static fn open_file(path: JString, mode: JString) -> jint,
        fn = natives::open_file,
    },
    native_method! { static fn mkdirs(path: JString), fn = natives::mkdirs },
    native_method! { static fn rename(from: JString, to: JString), fn = natives::rename },
    native_method! {
        static fn delete(path: JString, recursive: jboolean),
        fn = natives::delete,
    },
];

static HOST: OnceLock<Host> = OnceLock::new();

/// The process-wide API host, rooted at the layout chosen by `core::init`.
pub fn host() -> &'static Host {
    HOST.get_or_init(|| Host::new(crate::core::layout().clone(), std::env::vars().collect()))
}

/// The exception message for an API error: `E<code>: <message>`.
pub fn error_message(e: &HostError) -> String {
    format!("E{}: {}", e.code as i32, e.message)
}

/// `FileStat` as one string: fields separated by NUL (which no path
/// contains): type, size, mtime ms, mode, path, name, symlink target.
pub fn encode_stat(s: &FileStat) -> String {
    format!(
        "{}\0{}\0{}\0{}\0{}\0{}\0{}",
        s.file_type as i32,
        s.size,
        s.modified_millis,
        s.mode,
        s.path.to_string_lossy(),
        s.name,
        s.symlink_target
            .as_deref()
            .map(|p| p.to_string_lossy())
            .unwrap_or_default()
    )
}

mod natives {
    use super::*;
    use ferrux_host::{SpawnRequest, SpawnedIo};
    use ferrux_jni_core::{new_string_array, string_array};
    use ferrux_pty::WinSize;
    use jni::objects::{JClass, JLongArray, JObjectArray};
    use jni::sys::{jboolean, jint, jlong};

    /// Throws `IllegalStateException("E<code>: <message>")`.
    fn throw_api(env: &mut Env<'_>, e: &HostError) -> JResult<()> {
        env.throw_new(
            jni_str!("java/lang/IllegalStateException"),
            JNIString::from(error_message(e)),
        )
    }

    /// Returns `r`'s value, or throws its error and returns `default`.
    fn api<T>(env: &mut Env<'_>, default: T, r: Result<T, HostError>) -> JResult<T> {
        match r {
            Ok(v) => Ok(v),
            Err(e) => {
                throw_api(env, &e)?;
                Ok(default)
            }
        }
    }

    fn opt_string(env: &mut Env<'_>, s: &JString<'_>) -> JResult<Option<String>> {
        if s.is_null() {
            Ok(None)
        } else {
            s.try_to_string(env).map(Some)
        }
    }

    fn raw(fd: Option<OwnedFd>) -> jlong {
        fd.map_or(-1, |fd| jlong::from(fd.into_raw_fd()))
    }

    fn clamp_u16(v: jint) -> u16 {
        v.clamp(1, jint::from(u16::MAX)) as u16
    }

    fn handle(v: jlong) -> u64 {
        u64::try_from(v).unwrap_or(0)
    }

    /// Returns `[handle, pid, stdin, stdout, stderr, pty]`; absent fds are -1.
    /// The caller owns the descriptors.
    #[allow(clippy::too_many_arguments)]
    pub fn spawn<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
        argv: JObjectArray<'local, JString<'local>>,
        environment: JObjectArray<'local, JString<'local>>,
        clear_env: jboolean,
        cwd: JString<'local>,
        pty: jboolean,
        merge_stderr: jboolean,
        rows: jint,
        cols: jint,
        owner: jlong,
    ) -> JResult<JLongArray<'local>> {
        let req = SpawnRequest {
            argv: string_array(env, &argv)?,
            env: string_array(env, &environment)?,
            clear_env,
            cwd: opt_string(env, &cwd)?,
            pty: pty.then(|| WinSize::new(clamp_u16(rows), clamp_u16(cols))),
            merge_stderr,
        };
        let out = env.new_long_array(6)?;
        let Some(spawned) = api(env, None, host().spawn(&req, handle(owner)).map(Some))? else {
            return Ok(out);
        };
        let fds = match spawned.io {
            SpawnedIo::Pipes {
                stdin,
                stdout,
                stderr,
            } => [raw(Some(stdin)), raw(Some(stdout)), raw(stderr), -1],
            SpawnedIo::Pty { master } => [-1, -1, -1, raw(Some(master))],
        };
        let values = [
            spawned.handle as jlong,
            jlong::from(spawned.pid),
            fds[0],
            fds[1],
            fds[2],
            fds[3],
        ];
        out.set_region(env, 0, &values)?;
        Ok(out)
    }

    pub fn resize(
        env: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        rows: jint,
        cols: jint,
    ) -> JResult<()> {
        let r = host().resize(handle(id), clamp_u16(rows), clamp_u16(cols));
        api(env, (), r)
    }

    pub fn signal(env: &mut Env<'_>, _: JClass<'_>, id: jlong, signo: jint) -> JResult<()> {
        api(env, (), host().signal(handle(id), signo))
    }

    pub fn kill(env: &mut Env<'_>, _: JClass<'_>, id: jlong) -> JResult<()> {
        api(env, (), host().kill(handle(id)))
    }

    pub fn close(env: &mut Env<'_>, _: JClass<'_>, id: jlong) -> JResult<()> {
        api(env, (), host().close(handle(id)))
    }

    pub fn close_owner(_: &mut Env<'_>, _: JClass<'_>, owner: jlong) -> JResult<jint> {
        Ok(host().close_owner(handle(owner)) as jint)
    }

    /// The owner of a handle, or 0.
    pub fn owner_of(_: &mut Env<'_>, _: JClass<'_>, id: jlong) -> JResult<jlong> {
        Ok(host().owner_of(handle(id)).map_or(0, |o| o as jlong))
    }

    pub fn live_count(_: &mut Env<'_>, _: JClass<'_>) -> JResult<jint> {
        Ok(host().live_count() as jint)
    }

    pub fn owner_limit(_: &mut Env<'_>, _: JClass<'_>) -> JResult<jint> {
        Ok(host().owner_limit() as jint)
    }

    /// Blocks up to `timeout_ms`; returns `[handle, owner, exitCode, signal]*`.
    pub fn await_exits<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
        timeout_ms: jint,
    ) -> JResult<JLongArray<'local>> {
        let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
        let values: Vec<jlong> = host()
            .wait_exits(timeout)
            .iter()
            .flat_map(|e| {
                let (code, signal) = e.code_and_signal();
                [
                    e.handle as jlong,
                    e.owner as jlong,
                    jlong::from(code),
                    jlong::from(signal),
                ]
            })
            .collect();
        let out = env.new_long_array(values.len())?;
        out.set_region(env, 0, &values)?;
        Ok(out)
    }

    /// `[prefix, home, tmpdir, "1" if bootstrapped else "0"]`.
    pub fn info<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
    ) -> JResult<JObjectArray<'local, JString<'local>>> {
        let layout = host().layout();
        let bootstrapped = layout.is_bootstrapped();
        let tmp = if bootstrapped {
            layout.prefix_tmp()
        } else {
            layout.fallback_tmp()
        };
        let items = [
            layout.prefix().to_string_lossy().into_owned(),
            layout.home().to_string_lossy().into_owned(),
            tmp.to_string_lossy().into_owned(),
            if bootstrapped { "1" } else { "0" }.to_owned(),
        ];
        new_string_array(env, &items)
    }

    pub fn stat<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
        path: JString<'local>,
    ) -> JResult<JString<'local>> {
        let path = path.try_to_string(env)?;
        match host().files().stat(&path) {
            Ok(s) => JString::from_str(env, encode_stat(&s)),
            Err(e) => {
                throw_api(env, &e)?;
                Ok(JString::null())
            }
        }
    }

    pub fn list<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
        path: JString<'local>,
        offset: jint,
        limit: jint,
    ) -> JResult<JObjectArray<'local, JString<'local>>> {
        let path = path.try_to_string(env)?;
        let page = host()
            .files()
            .list(&path, offset.max(0) as usize, limit.max(0) as usize);
        match page {
            Ok(page) => {
                let items: Vec<String> = page.iter().map(encode_stat).collect();
                new_string_array(env, &items)
            }
            Err(e) => {
                throw_api(env, &e)?;
                new_string_array(env, &[])
            }
        }
    }

    /// Returns a raw fd the caller owns.
    pub fn open_file(
        env: &mut Env<'_>,
        _: JClass<'_>,
        path: JString<'_>,
        mode: JString<'_>,
    ) -> JResult<jint> {
        let path = path.try_to_string(env)?;
        let mode = mode.try_to_string(env)?;
        let r = OpenMode::parse(&mode)
            .and_then(|m| host().files().open(&path, m))
            .map(|fd| fd.into_raw_fd());
        api(env, -1, r)
    }

    pub fn mkdirs(env: &mut Env<'_>, _: JClass<'_>, path: JString<'_>) -> JResult<()> {
        let path = path.try_to_string(env)?;
        api(env, (), host().files().mkdirs(&path).map(|_| ()))
    }

    pub fn rename(
        env: &mut Env<'_>,
        _: JClass<'_>,
        from: JString<'_>,
        to: JString<'_>,
    ) -> JResult<()> {
        let from = from.try_to_string(env)?;
        let to = to.try_to_string(env)?;
        api(env, (), host().files().rename(&from, &to))
    }

    pub fn delete(
        env: &mut Env<'_>,
        _: JClass<'_>,
        path: JString<'_>,
        recursive: jboolean,
    ) -> JResult<()> {
        let path = path.try_to_string(env)?;
        api(env, (), host().files().delete(&path, recursive))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ferrux_host::files::FileType;
    use ferrux_jni_core::natives_check::{kotlin_externals, table};

    const API_NATIVE_KT: &str =
        include_str!("../../../android/app/src/main/kotlin/dev/ferrux/app/api/ApiNative.kt");

    #[test]
    fn api_natives_match_kotlin_externals() {
        assert_eq!(kotlin_externals(API_NATIVE_KT), table(API_NATIVES));
    }

    #[test]
    fn error_message_format() {
        let e = HostError::new(ferrux_host::ErrorCode::OutsideHome, "/etc: outside");
        assert_eq!(error_message(&e), "E4: /etc: outside");
    }

    #[test]
    fn stat_encoding() {
        let s = FileStat {
            path: "/h/a b".into(),
            name: "a b".into(),
            file_type: FileType::Symlink,
            size: 12,
            modified_millis: 34,
            mode: 0o644,
            symlink_target: Some("t".into()),
        };
        let enc = encode_stat(&s);
        let fields: Vec<&str> = enc.split('\0').collect();
        assert_eq!(fields, ["2", "12", "34", "420", "/h/a b", "a b", "t"]);
        let none = FileStat {
            symlink_target: None,
            ..s
        };
        assert!(encode_stat(&none).ends_with("a b\0"));
    }
}
