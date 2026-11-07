// SPDX-License-Identifier: Apache-2.0
//! `libferrux.so`: JNI entry points for `dev.ferrux.terminal.FerruxNative`.
//!
//! Every function here is a thin adapter; the logic lives in [`core`] and the
//! crates below it. Panics and JNI errors are caught by `with_env` and turned
//! into a Java `RuntimeException`. The integer layouts are documented on
//! [`ferrux_term::Snapshot::encode_ints`] and mirrored in the Kotlin
//! `SnapshotLayout` / `TerminalKeys` objects.

pub mod core;

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass, JIntArray, JObjectArray, JString};
use jni::strings::JNIString;
use jni::sys::{jboolean, jint, jlong};
use jni::{Env, EnvUnowned, jni_str};

type JResult<T> = Result<T, jni::errors::Error>;

fn string_array(env: &mut Env<'_>, array: &JObjectArray<'_, JString<'_>>) -> JResult<Vec<String>> {
    let len = array.len(env)?;
    let mut out = Vec::with_capacity(len);
    for i in 0..len {
        let s = array.get_element(env, i)?;
        out.push(s.try_to_string(env)?);
    }
    Ok(out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_init<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    files_dir: JString<'caller>,
    _native_lib_dir: JString<'caller>,
) {
    env.with_env(|env| -> JResult<()> {
        init_logging();
        let files_dir = files_dir.try_to_string(env)?;
        core::init(&files_dir);
        // TODO(M3): remember native_lib_dir for helper executables shipped as
        // lib*.so (proot loader, …).
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_defaultShell<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    env.with_env(|env| JString::from_str(env, core::default_shell()))
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_defaultEnv<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JObjectArray<'caller, JString<'caller>> {
    env.with_env(|env| new_string_array(env, &core::default_env()))
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn new_string_array<'local>(
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_createSession<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    program: JString<'caller>,
    args: JObjectArray<'caller, JString<'caller>>,
    environment: JObjectArray<'caller, JString<'caller>>,
    cwd: JString<'caller>,
    rows: jint,
    cols: jint,
) -> jlong {
    env.with_env(|env| -> JResult<jlong> {
        let program = program.try_to_string(env)?;
        let args = string_array(env, &args)?;
        let environment = string_array(env, &environment)?;
        let cwd = cwd.try_to_string(env)?;
        let clamp = |v: jint| v.clamp(1, u16::MAX as jint) as u16;
        match core::create_session(&program, args, environment, &cwd, clamp(rows), clamp(cols)) {
            Ok(id) => Ok(id as jlong),
            Err(e) => {
                log::error!("cannot start {program}: {e}");
                env.throw_new(
                    jni_str!("java/io/IOException"),
                    JNIString::from(format!("{program}: {e}")),
                )?;
                Ok(0)
            }
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_destroySession<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
) {
    env.with_env(|_| -> JResult<()> {
        core::destroy_session(id);
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_write<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    data: JByteArray<'caller>,
) {
    env.with_env(|env| -> JResult<()> {
        if let Some(s) = core::session(id) {
            s.write(&env.convert_byte_array(&data)?);
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_sendText<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    text: JString<'caller>,
    mods: jint,
) {
    env.with_env(|env| -> JResult<()> {
        if let Some(s) = core::session(id) {
            let text = text.try_to_string(env)?;
            s.send_text(
                &text,
                ferrux_term::Modifiers::from_bits_truncate(mods as u32),
            );
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_sendKey<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    key: jint,
    mods: jint,
) -> jboolean {
    env.with_env(|_| -> JResult<jboolean> { Ok(core::send_key(id, key, mods)) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_paste<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    text: JString<'caller>,
) {
    env.with_env(|env| -> JResult<()> {
        if let Some(s) = core::session(id) {
            s.paste(&text.try_to_string(env)?);
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_resize<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    rows: jint,
    cols: jint,
) {
    env.with_env(|_| -> JResult<()> {
        core::resize(id, rows, cols);
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_scroll<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    lines: jint,
) {
    env.with_env(|_| -> JResult<()> {
        if let Some(s) = core::session(id) {
            s.scroll(lines);
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_scrollGesture<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    lines: jint,
    row: jint,
    col: jint,
) {
    env.with_env(|_| -> JResult<()> {
        core::scroll_gesture(id, lines, row, col);
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_mouseEvent<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    button: jint,
    action: jint,
    row: jint,
    col: jint,
    mods: jint,
) -> jboolean {
    env.with_env(|_| -> JResult<jboolean> {
        Ok(core::mouse_event(id, button, action, row, col, mods))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_snapshot<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    out: JIntArray<'caller>,
) -> jint {
    env.with_env(|env| -> JResult<jint> {
        let capacity = out.len(env)?;
        let mut result = Ok(());
        let n = core::snapshot(id, capacity, |ints| result = out.set_region(env, 0, ints));
        result?;
        Ok(n)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_screenText<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
) -> JString<'caller> {
    env.with_env(|env| match core::screen_text(id) {
        Some(text) => JString::from_str(env, text),
        // null: no such session.
        None => Ok(JString::default()),
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_startSelection<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    kind: jint,
    row: jint,
    col: jint,
) -> jboolean {
    env.with_env(|_| -> JResult<jboolean> { Ok(core::start_selection(id, kind, row, col)) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_updateSelection<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    row: jint,
    col: jint,
) {
    env.with_env(|_| -> JResult<()> {
        core::update_selection(id, row, col);
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_setSelection<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    start_row: jint,
    start_col: jint,
    end_row: jint,
    end_col: jint,
    block: jboolean,
) {
    env.with_env(|_| -> JResult<()> {
        core::set_selection(id, (start_row, start_col), (end_row, end_col), block);
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_selectAll<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
) {
    env.with_env(|_| -> JResult<()> {
        core::select_all(id);
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_clearSelection<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
) {
    env.with_env(|_| -> JResult<()> {
        core::clear_selection(id);
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_selectionText<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
) -> JString<'caller> {
    env.with_env(|env| match core::selection_text(id) {
        Some(text) => JString::from_str(env, text),
        // null: nothing selected or no such session.
        None => Ok(JString::default()),
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_awaitChange<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    last_seq: jlong,
    timeout_ms: jint,
) -> jlong {
    env.with_env(|_| -> JResult<jlong> { Ok(core::await_change(last_seq, timeout_ms)) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ferrux_terminal_FerruxNative_pollEvents<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
) -> JObjectArray<'caller, JString<'caller>> {
    env.with_env(|env| match core::poll_events(id) {
        Some(events) => new_string_array(env, &events),
        // A null array tells Kotlin the session is gone.
        None => Ok(JObjectArray::default()),
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

fn init_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("ferrux"),
    );
}
