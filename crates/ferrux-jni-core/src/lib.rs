// SPDX-License-Identifier: Apache-2.0
//! `ferrux-jni-core`: the terminal half of Ferrux's JNI, reusable by any app
//! that embeds `terminal-view` (Ferride, API clients). See `docs/REUSE.md`.
//!
//! * No `#[no_mangle]` `Java_…` exports: a host library binds the natives to
//!   a Kotlin class of its choice with [`register_terminal_natives`] from its
//!   `JNI_OnLoad` (see [`on_load`]). `terminal-view` uses [`TERMINAL_CLASS`].
//! * No Ferrux environment: spawning policy (program, env, exec mode) belongs
//!   to the host, which calls [`core::spawn_session`] with a full
//!   `SpawnConfig`, or adopts a pty fd with `attachPty`.
//! * Every native is declared with [`jni::native_method!`], so its JNI
//!   signature is derived from the Rust types at compile time; a test checks
//!   the table against the Kotlin `external fun`s.
//!
//! Panics and JNI errors become a Java `RuntimeException`. The integer
//! layouts are documented on [`ferrux_term::Snapshot::encode_ints`] and
//! mirrored in the Kotlin `SnapshotLayout` / `TerminalKeys` objects.

pub mod core;

use jni::objects::{JByteArray, JClass, JIntArray, JObjectArray, JString};
use jni::strings::{JNIStr, JNIString};
use jni::sys::{jboolean, jint, jlong};
use jni::{Env, JavaVM, NativeMethod, jni_str, native_method};

pub use jni;

pub type JResult<T> = Result<T, jni::errors::Error>;

/// The Kotlin class in `terminal-view` that declares the terminal natives.
pub const TERMINAL_CLASS: &JNIStr = jni_str!("dev/ferrux/terminal/TerminalNative");

/// The terminal natives, for [`register_terminal_natives`].
// jni-macros 0.22.4 expands its ABI check to `AtomicBool::fetch_update`,
// deprecated since Rust 1.99.
#[allow(deprecated)]
pub const TERMINAL_NATIVES: &[NativeMethod<'static>] = &[
    native_method! { static fn destroy_session(id: jlong), fn = natives::destroy_session },
    native_method! { static fn write(id: jlong, data: jbyte[]), fn = natives::write },
    native_method! {
        static fn send_text(id: jlong, text: JString, mods: jint),
        fn = natives::send_text,
    },
    native_method! {
        static fn send_key(id: jlong, key: jint, mods: jint) -> jboolean,
        fn = natives::send_key,
    },
    native_method! { static fn paste(id: jlong, text: JString), fn = natives::paste },
    native_method! {
        static fn resize(id: jlong, rows: jint, cols: jint),
        fn = natives::resize,
    },
    native_method! { static fn scroll(id: jlong, lines: jint), fn = natives::scroll },
    native_method! {
        static fn scroll_gesture(id: jlong, lines: jint, row: jint, col: jint),
        fn = natives::scroll_gesture,
    },
    native_method! {
        static fn mouse_event(
            id: jlong,
            button: jint,
            action: jint,
            row: jint,
            col: jint,
            mods: jint,
        ) -> jboolean,
        fn = natives::mouse_event,
    },
    native_method! {
        static fn snapshot(id: jlong, out: jint[]) -> jint,
        fn = natives::snapshot,
    },
    native_method! {
        static fn start_selection(id: jlong, kind: jint, row: jint, col: jint) -> jboolean,
        fn = natives::start_selection,
    },
    native_method! {
        static fn update_selection(id: jlong, row: jint, col: jint),
        fn = natives::update_selection,
    },
    native_method! {
        static fn set_selection(
            id: jlong,
            start_row: jint,
            start_col: jint,
            end_row: jint,
            end_col: jint,
            block: jboolean,
        ),
        fn = natives::set_selection,
    },
    native_method! { static fn select_all(id: jlong), fn = natives::select_all },
    native_method! { static fn clear_selection(id: jlong), fn = natives::clear_selection },
    native_method! {
        static fn selection_text(id: jlong) -> JString,
        fn = natives::selection_text,
    },
    native_method! { static fn screen_text(id: jlong) -> JString, fn = natives::screen_text },
    native_method! { static fn feed_local(id: jlong, text: JString), fn = natives::feed_local },
    native_method! { static fn cwd(id: jlong) -> JString, fn = natives::cwd },
    native_method! {
        static fn await_change(last_seq: jlong, timeout_ms: jint) -> jlong,
        fn = natives::await_change,
    },
    native_method! {
        static fn poll_events(id: jlong) -> JString[],
        fn = natives::poll_events,
    },
    native_method! {
        static fn attach_pty(fd: jint, rows: jint, cols: jint) -> jlong,
        fn = natives::attach_pty,
    },
];

/// Binds [`TERMINAL_NATIVES`] to `class` (normally [`TERMINAL_CLASS`]).
pub fn register_terminal_natives(env: &mut Env<'_>, class: &JNIStr) -> JResult<()> {
    register_natives(env, class, TERMINAL_NATIVES)
}

/// Binds `methods` to the Kotlin/Java class `class` (`a/b/C` form).
pub fn register_natives(
    env: &mut Env<'_>,
    class: &JNIStr,
    methods: &[NativeMethod<'_>],
) -> JResult<()> {
    let class = env.find_class(class)?;
    // SAFETY: every entry comes from `native_method!`, which derives the
    // signature from the function's types and checks static vs. instance.
    unsafe { env.register_native_methods(&class, methods) }
}

/// The body of a host library's `JNI_OnLoad`: initialises logging under
/// `tag`, then runs `register` (which should call
/// [`register_terminal_natives`] and register the host's own natives).
///
/// Returns `JNI_ERR` if registration fails, so `System.loadLibrary` throws
/// instead of the first native call crashing later.
///
/// # Safety
/// `vm` must be the pointer passed to `JNI_OnLoad`.
pub unsafe fn on_load(
    vm: *mut jni::sys::JavaVM,
    tag: &str,
    register: impl FnOnce(&mut Env<'_>) -> JResult<()>,
) -> jint {
    init_logging(tag);
    // SAFETY: forwarded from the caller.
    let vm = unsafe { JavaVM::from_raw(vm) };
    match vm.attach_current_thread(|env| register(env)) {
        Ok(()) => jni::sys::JNI_VERSION_1_6,
        Err(e) => {
            log::error!("cannot register natives: {e}");
            jni::sys::JNI_ERR
        }
    }
}

/// Routes `log` output to logcat under `tag` (no-op off Android).
pub fn init_logging(tag: &str) {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag(tag),
    );
    #[cfg(not(target_os = "android"))]
    let _ = tag;
}

/// Reads a Java `String[]`.
pub fn string_array(
    env: &mut Env<'_>,
    array: &JObjectArray<'_, JString<'_>>,
) -> JResult<Vec<String>> {
    let len = array.len(env)?;
    let mut out = Vec::with_capacity(len);
    for i in 0..len {
        let s = array.get_element(env, i)?;
        out.push(s.try_to_string(env)?);
    }
    Ok(out)
}

/// Creates a Java `String[]`.
pub fn new_string_array<'local>(
    env: &mut Env<'local>,
    items: &[String],
) -> JResult<JObjectArray<'local, JString<'local>>> {
    let array = JObjectArray::<JString>::new(env, items.len(), JString::null())?;
    for (i, item) in items.iter().enumerate() {
        let s = JString::from_str(env, item)?;
        array.set_element(env, i, &s)?;
    }
    Ok(array)
}

/// Throws `java.io.IOException(message)`; the native then returns a default.
pub fn throw_io(env: &mut Env<'_>, message: String) -> JResult<()> {
    env.throw_new(jni_str!("java/io/IOException"), JNIString::from(message))
}

/// The native implementations; thin adapters over [`core`].
mod natives {
    use super::*;

    fn clamp_u16(v: jint) -> u16 {
        v.clamp(1, u16::MAX as jint) as u16
    }

    pub fn destroy_session(_: &mut Env<'_>, _: JClass<'_>, id: jlong) -> JResult<()> {
        core::destroy_session(id);
        Ok(())
    }

    pub fn write(env: &mut Env<'_>, _: JClass<'_>, id: jlong, data: JByteArray<'_>) -> JResult<()> {
        if let Some(s) = core::session(id) {
            s.write(&env.convert_byte_array(&data)?);
        }
        Ok(())
    }

    pub fn send_text(
        env: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        text: JString<'_>,
        mods: jint,
    ) -> JResult<()> {
        if let Some(s) = core::session(id) {
            let text = text.try_to_string(env)?;
            s.send_text(
                &text,
                ferrux_term::Modifiers::from_bits_truncate(mods as u32),
            );
        }
        Ok(())
    }

    pub fn send_key(
        _: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        key: jint,
        mods: jint,
    ) -> JResult<jboolean> {
        Ok(core::send_key(id, key, mods))
    }

    pub fn paste(env: &mut Env<'_>, _: JClass<'_>, id: jlong, text: JString<'_>) -> JResult<()> {
        if let Some(s) = core::session(id) {
            s.paste(&text.try_to_string(env)?);
        }
        Ok(())
    }

    pub fn resize(
        _: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        rows: jint,
        cols: jint,
    ) -> JResult<()> {
        core::resize(id, rows, cols);
        Ok(())
    }

    pub fn scroll(_: &mut Env<'_>, _: JClass<'_>, id: jlong, lines: jint) -> JResult<()> {
        if let Some(s) = core::session(id) {
            s.scroll(lines);
        }
        Ok(())
    }

    pub fn scroll_gesture(
        _: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        lines: jint,
        row: jint,
        col: jint,
    ) -> JResult<()> {
        core::scroll_gesture(id, lines, row, col);
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    pub fn mouse_event(
        _: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        button: jint,
        action: jint,
        row: jint,
        col: jint,
        mods: jint,
    ) -> JResult<jboolean> {
        Ok(core::mouse_event(id, button, action, row, col, mods))
    }

    pub fn snapshot(
        env: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        out: JIntArray<'_>,
    ) -> JResult<jint> {
        let capacity = out.len(env)?;
        let mut result = Ok(());
        let n = core::snapshot(id, capacity, |ints| result = out.set_region(env, 0, ints));
        result?;
        Ok(n)
    }

    pub fn start_selection(
        _: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        kind: jint,
        row: jint,
        col: jint,
    ) -> JResult<jboolean> {
        Ok(core::start_selection(id, kind, row, col))
    }

    pub fn update_selection(
        _: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        row: jint,
        col: jint,
    ) -> JResult<()> {
        core::update_selection(id, row, col);
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    pub fn set_selection(
        _: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        start_row: jint,
        start_col: jint,
        end_row: jint,
        end_col: jint,
        block: jboolean,
    ) -> JResult<()> {
        core::set_selection(id, (start_row, start_col), (end_row, end_col), block);
        Ok(())
    }

    pub fn select_all(_: &mut Env<'_>, _: JClass<'_>, id: jlong) -> JResult<()> {
        core::select_all(id);
        Ok(())
    }

    pub fn clear_selection(_: &mut Env<'_>, _: JClass<'_>, id: jlong) -> JResult<()> {
        core::clear_selection(id);
        Ok(())
    }

    pub fn selection_text<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
        id: jlong,
    ) -> JResult<JString<'local>> {
        match core::selection_text(id) {
            Some(text) => JString::from_str(env, text),
            // null: nothing selected or no such session.
            None => Ok(JString::default()),
        }
    }

    pub fn feed_local(
        env: &mut Env<'_>,
        _: JClass<'_>,
        id: jlong,
        text: JString<'_>,
    ) -> JResult<()> {
        core::feed_local(id, &text.try_to_string(env)?);
        Ok(())
    }

    pub fn cwd<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
        id: jlong,
    ) -> JResult<JString<'local>> {
        match core::cwd(id) {
            Some(cwd) => JString::from_str(env, cwd),
            // null: exited, attached, or no such session.
            None => Ok(JString::default()),
        }
    }

    pub fn screen_text<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
        id: jlong,
    ) -> JResult<JString<'local>> {
        match core::screen_text(id) {
            Some(text) => JString::from_str(env, text),
            // null: no such session.
            None => Ok(JString::default()),
        }
    }

    pub fn await_change(
        _: &mut Env<'_>,
        _: JClass<'_>,
        last_seq: jlong,
        timeout_ms: jint,
    ) -> JResult<jlong> {
        Ok(core::await_change(last_seq, timeout_ms))
    }

    pub fn poll_events<'local>(
        env: &mut Env<'local>,
        _: JClass<'local>,
        id: jlong,
    ) -> JResult<JObjectArray<'local, JString<'local>>> {
        match core::poll_events(id) {
            Some(events) => new_string_array(env, &events),
            // A null array tells Kotlin the session is gone.
            None => Ok(JObjectArray::default()),
        }
    }

    pub fn attach_pty(
        env: &mut Env<'_>,
        _: JClass<'_>,
        fd: jint,
        rows: jint,
        cols: jint,
    ) -> JResult<jlong> {
        match core::attach_pty(fd, clamp_u16(rows), clamp_u16(cols)) {
            Ok(id) => Ok(id as jlong),
            Err(e) => {
                log::error!("cannot attach pty fd {fd}: {e}");
                throw_io(env, format!("attachPty: {e}"))?;
                Ok(0)
            }
        }
    }
}

/// Checks a native table against the Kotlin `external fun`s that declare it,
/// so a native added on only one side fails `cargo test` instead of
/// `System.loadLibrary` on a device. Used by this crate's and the hosts'
/// tests.
#[doc(hidden)]
pub mod natives_check {
    use std::collections::BTreeSet;
    use std::ffi::CStr;

    use jni::NativeMethod;

    /// `(name, JNI signature)` of every entry in `methods`.
    pub fn table(methods: &[NativeMethod<'_>]) -> BTreeSet<(String, String)> {
        methods
            .iter()
            .map(|m| {
                // SAFETY: `NativeMethod` is a `repr(transparent)` wrapper
                // around `JNINativeMethod` whose strings are NUL-terminated
                // (they come from `jni_str!`).
                let raw =
                    unsafe { &*(m as *const NativeMethod as *const jni::sys::JNINativeMethod) };
                let s = |p| unsafe { CStr::from_ptr(p) }.to_str().unwrap().to_owned();
                (s(raw.name), s(raw.signature))
            })
            .collect()
    }

    /// `(name, JNI signature)` of every `external fun` in a Kotlin source.
    pub fn kotlin_externals(source: &str) -> BTreeSet<(String, String)> {
        let mut out = BTreeSet::new();
        let mut rest = source;
        while let Some(at) = rest.find("external fun ") {
            let line_start = rest[..at].rfind('\n').map_or(0, |i| i + 1);
            let prefix = rest[line_start..at].trim();
            let decl = &rest[at + "external fun ".len()..];
            rest = decl;
            if !(prefix.is_empty() || prefix == "@JvmStatic") {
                continue; // mentioned in a comment
            }
            let open = decl.find('(').unwrap();
            let close = decl.find(')').unwrap();
            let name = decl[..open].trim().to_owned();
            let params: String = decl[open + 1..close]
                .split(',')
                .map(str::trim)
                .filter(|p| !p.is_empty())
                .map(|p| jni_type(p.split_once(':').expect("param: Type").1.trim()))
                .collect();
            let after = &decl[close + 1..];
            let line_end = after.find('\n').unwrap_or(after.len());
            let ret = after[..line_end]
                .trim()
                .strip_prefix(':')
                .map_or("V".to_owned(), |t| jni_type(t.trim()));
            out.insert((name, format!("({params}){ret}")));
        }
        out
    }

    fn jni_type(kotlin: &str) -> String {
        match kotlin.trim_end_matches('?') {
            "Unit" => "V",
            "Boolean" => "Z",
            "Byte" => "B",
            "Char" => "C",
            "Short" => "S",
            "Int" => "I",
            "Long" => "J",
            "Float" => "F",
            "Double" => "D",
            "String" => "Ljava/lang/String;",
            "ByteArray" => "[B",
            "IntArray" => "[I",
            "LongArray" => "[J",
            "Array<String>" => "[Ljava/lang/String;",
            other => panic!("add a JNI mapping for Kotlin type {other}"),
        }
        .to_owned()
    }
}

#[cfg(test)]
mod tests;
