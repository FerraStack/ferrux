// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.api;

parcelable SpawnResult {
    // For resize/signal/kill/close; > 0.
    long handle;
    // In Ferrux's pid namespace (diagnostics only).
    int pid;
    // Shared sessions only, else 0.
    long sessionId;
    // MODE_PIPES: stdin (write end), stdout and stderr (read ends);
    // stderr is null with mergeStderr.
    @nullable ParcelFileDescriptor stdin;
    @nullable ParcelFileDescriptor stdout;
    @nullable ParcelFileDescriptor stderr;
    // MODE_PTY: the PTY master, or for shared sessions a connected SOCK_STREAM
    // socket carrying the same byte stream.
    @nullable ParcelFileDescriptor pty;
    boolean ptyIsSocket;
}
