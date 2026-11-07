// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.api;

// Fields are only ever appended (docs/API.md §4).
parcelable SpawnRequest {
    const int MODE_PIPES = 0;
    const int MODE_PTY = 1;

    // argv[0] is the program: a path, or a name looked up in the resulting PATH
    // ($PREFIX/bin:/system/bin:/system/xbin by default). It is also passed to
    // the program as argv[0], like execvp.
    String[] argv;
    // KEY=VALUE entries applied over Ferrux's session environment.
    @nullable String[] env;
    // Start from an empty environment instead of Ferrux's.
    boolean clearEnv = false;
    // Absolute or $HOME-relative; null or empty = $HOME. Must be inside $HOME.
    @nullable String cwd;
    int mode = MODE_PIPES;
    // Pipes mode: stderr goes to the stdout pipe (no separate stderr fd).
    boolean mergeStderr = false;
    // PTY mode: initial size.
    int rows = 24;
    int cols = 80;
    // PTY mode: create a Ferrux session and attach to it (capability "attach").
    boolean shared = false;
    // Shared sessions: title shown in Ferrux's drawer.
    @nullable String title;
}
