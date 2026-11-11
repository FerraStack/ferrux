// SPDX-License-Identifier: Apache-2.0
//! `libferrux.so`: the Ferrux app's native library.
//!
//! `JNI_OnLoad` binds three sets of natives with `RegisterNatives`:
//!
//! * the terminal natives of `ferrux-jni-core` on
//!   `dev.ferrux.terminal.TerminalNative` (shared with other apps),
//! * the Ferrux app natives below on `dev.ferrux.app.FerruxAppNative`: the
//!   Linux environment (`ferrux-env`) and spawning with it, and
//! * the Ferrux API v1 natives ([`api`]) on `dev.ferrux.app.api.ApiNative`.
//!
//! Every function here is a thin adapter; the logic lives in [`core`] and the
//! crates below it. Panics and JNI errors become a Java `RuntimeException`.
//! See `docs/REUSE.md`.

pub mod api;
pub mod core;

use std::ffi::c_void;

use ferrux_jni_core::{
    JResult, TERMINAL_CLASS, new_string_array, register_natives, register_terminal_natives,
    string_array, throw_io,
};
use jni::objects::{JClass, JObjectArray, JString};
use jni::strings::JNIStr;
use jni::sys::{JavaVM, jboolean, jint, jlong};
use jni::{Env, NativeMethod, jni_str, native_method};

/// The Kotlin class in the app module that declares the app natives.
pub const APP_CLASS: &JNIStr = jni_str!("dev/ferrux/app/FerruxAppNative");

/// The Ferrux app natives.
// jni-macros 0.22.4 expands its ABI check to `AtomicBool::fetch_update`,
// deprecated since Rust 1.99.
#[allow(deprecated)]
pub const APP_NATIVES: &[NativeMethod<'static>] = &[
    native_method! {
        static fn init(files_dir: JString, native_lib_dir: JString),
        fn = natives::init,
    },
    native_method! {
        static fn default_shell() -> JString,
        fn = natives::default_shell,
    },
    native_method! {
        static fn default_env() -> JString[],
        fn = natives::default_env,
    },
    native_method! {
        static fn create_session(
            program: JString,
            args: JString[],
            environment: JString[],
            cwd: JString,
            rows: jint,
            cols: jint,
        ) -> jlong,
        fn = natives::create_session,
    },
    native_method! {
        static fn is_bootstrapped() -> jboolean,
        fn = natives::is_bootstrapped,
    },
    native_method! {
        static fn bootstrap_info() -> JString[],
        fn = natives::bootstrap_info,
    },
    native_method! {
        static fn install_bootstrap(archive: JString, sha256: JString) -> JString,
        fn = natives::install_bootstrap,
    },
    native_method! {
        static fn bootstrap_progress() -> JString[],
        fn = natives::bootstrap_progress,
    },
];

/// # Safety
/// Called by the JVM from `System.loadLibrary` with a valid `vm`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    // SAFETY: `vm` comes straight from the JVM.
    unsafe {
        ferrux_jni_core::on_load(vm, "ferrux", |env| {
            register_terminal_natives(env, TERMINAL_CLASS)?;
            register_natives(env, APP_CLASS, APP_NATIVES)?;
            register_natives(env, api::API_CLASS, api::API_NATIVES)
        })
    }
}

mod natives {
    use super::*;

    pub fn init(
        env: &mut Env<'_>,
        _: JClass<'_>,
        files_dir: JString<'_>,
        _native_lib_dir: JString<'_>,
    ) -> JResult<()> {
        let files_dir = files_dir.try_to_string(env)?;
        core::init(&files_dir);
        // TODO(M3): remember native_lib_dir for helper executables shipped as
        // lib*.so (proot loader, …).
        Ok(())
    }

    pub fn default_shell<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
    ) -> JResult<JString<'local>> {
        JString::from_str(env, core::default_shell())
    }

    pub fn default_env<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
    ) -> JResult<JObjectArray<'local, JString<'local>>> {
        new_string_array(env, &core::default_env())
    }

    pub fn is_bootstrapped(_: &mut Env<'_>, _: JClass<'_>) -> JResult<jboolean> {
        Ok(core::is_bootstrapped())
    }

    pub fn bootstrap_info<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
    ) -> JResult<JObjectArray<'local, JString<'local>>> {
        match core::bootstrap_info() {
            Some(info) => new_string_array(env, &info),
            // null: no bootstrap for this ABI.
            None => Ok(JObjectArray::default()),
        }
    }

    pub fn install_bootstrap<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
        archive: JString<'local>,
        sha256: JString<'local>,
    ) -> JResult<JString<'local>> {
        let archive = archive.try_to_string(env)?;
        let sha256 = sha256.try_to_string(env)?;
        match core::install_bootstrap(&archive, &sha256) {
            // null: success.
            Ok(()) => Ok(JString::default()),
            Err(message) => JString::from_str(env, message),
        }
    }

    pub fn bootstrap_progress<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
    ) -> JResult<JObjectArray<'local, JString<'local>>> {
        let progress = core::bootstrap_progress().map(|v| v.to_string());
        new_string_array(env, &progress)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn create_session(
        env: &mut Env<'_>,
        _: JClass<'_>,
        program: JString<'_>,
        args: JObjectArray<'_, JString<'_>>,
        environment: JObjectArray<'_, JString<'_>>,
        cwd: JString<'_>,
        rows: jint,
        cols: jint,
    ) -> JResult<jlong> {
        let program = program.try_to_string(env)?;
        let args = string_array(env, &args)?;
        let environment = string_array(env, &environment)?;
        let cwd = cwd.try_to_string(env)?;
        let clamp = |v: jint| v.clamp(1, u16::MAX as jint) as u16;
        match core::create_session(&program, args, environment, &cwd, clamp(rows), clamp(cols)) {
            Ok(id) => Ok(id as jlong),
            Err(e) => {
                log::error!("cannot start {program}: {e}");
                throw_io(env, format!("{program}: {e}"))?;
                Ok(0)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use ferrux_jni_core::natives_check::{kotlin_externals, table};

    const APP_NATIVE_KT: &str =
        include_str!("../../../android/app/src/main/kotlin/dev/ferrux/app/FerruxAppNative.kt");

    #[test]
    fn app_natives_match_kotlin_externals() {
        assert_eq!(kotlin_externals(APP_NATIVE_KT), table(super::APP_NATIVES));
    }
}
