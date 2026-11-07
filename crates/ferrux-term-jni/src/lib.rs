// SPDX-License-Identifier: Apache-2.0
//! `libferruxterm.so`: the terminal-only JNI library shipped with the
//! `terminal-view` AAR, for apps that render terminals but bring no Rust code
//! of their own (e.g. Ferrux API clients rendering a pty fd from Ferrux).
//!
//! It only binds `ferrux-jni-core`'s natives to
//! `dev.ferrux.terminal.TerminalNative`. Apps with their own native library
//! (Ferrux's `libferrux.so`, Ferride) link `ferrux-jni-core` instead and must
//! not package this one: each library holds its own session manager. See
//! `docs/REUSE.md`.

use std::ffi::c_void;

use ferrux_jni_core::jni::sys::{JavaVM, jint};
use ferrux_jni_core::{TERMINAL_CLASS, on_load, register_terminal_natives};

/// # Safety
/// Called by the JVM from `System.loadLibrary` with a valid `vm`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    // SAFETY: `vm` comes straight from the JVM.
    unsafe {
        on_load(vm, "ferrux-term", |env| {
            register_terminal_natives(env, TERMINAL_CLASS)
        })
    }
}
