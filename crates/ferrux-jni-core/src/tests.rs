// SPDX-License-Identifier: Apache-2.0
use super::natives_check::{kotlin_externals, table};
use super::*;

const TERMINAL_NATIVE_KT: &str = include_str!(
    "../../../android/terminal-view/src/main/kotlin/dev/ferrux/terminal/TerminalNative.kt"
);

#[test]
fn terminal_natives_match_kotlin_externals() {
    let kotlin = kotlin_externals(TERMINAL_NATIVE_KT);
    let rust = table(TERMINAL_NATIVES);
    let only_kotlin: Vec<_> = kotlin.difference(&rust).collect();
    let only_rust: Vec<_> = rust.difference(&kotlin).collect();
    assert!(
        only_kotlin.is_empty() && only_rust.is_empty(),
        "TerminalNative.kt and TERMINAL_NATIVES differ:\n  only in Kotlin: {only_kotlin:?}\n  only in Rust: {only_rust:?}"
    );
}

#[test]
fn signatures_are_derived_from_rust_types() {
    let rust = table(TERMINAL_NATIVES);
    for expected in [
        ("snapshot", "(J[I)I"),
        ("write", "(J[B)V"),
        ("pollEvents", "(J)[Ljava/lang/String;"),
        ("selectionText", "(J)Ljava/lang/String;"),
        ("setSelection", "(JIIIIZ)V"),
        ("attachPty", "(III)J"),
    ] {
        assert!(
            rust.contains(&(expected.0.into(), expected.1.into())),
            "{expected:?} in {rust:?}"
        );
    }
}

#[test]
fn kotlin_parser_handles_multiline_and_comments() {
    let src = "
        /** Not this: external fun inComment(x: Int) */
        @JvmStatic external fun a(id: Long, data: ByteArray)
        @JvmStatic external fun b(
            id: Long,
            names: Array<String>,
        ): Array<String>?
        @JvmStatic external fun c(): String?
    ";
    let parsed: Vec<_> = kotlin_externals(src).into_iter().collect();
    assert_eq!(
        parsed,
        [
            ("a".to_owned(), "(J[B)V".to_owned()),
            (
                "b".to_owned(),
                "(J[Ljava/lang/String;)[Ljava/lang/String;".to_owned()
            ),
            ("c".to_owned(), "()Ljava/lang/String;".to_owned()),
        ]
    );
}
