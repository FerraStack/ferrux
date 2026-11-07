// SPDX-License-Identifier: Apache-2.0
// Ferrux API v1. Specification: docs/API.md. Versioning rules (§4): transaction
// codes are never reused or renumbered; new methods and constants are only added.
package dev.ferrux.api;

import dev.ferrux.api.AttachResult;
import dev.ferrux.api.FerruxInfo;
import dev.ferrux.api.FileStat;
import dev.ferrux.api.IProcessCallback;
import dev.ferrux.api.SessionInfo;
import dev.ferrux.api.SpawnRequest;
import dev.ferrux.api.SpawnResult;

interface IFerruxApi {
    const int API_VERSION = 1;

    // Error codes, thrown as IllegalStateException("E<code>: message") (ApiErrors, docs/API.md §7).
    const int ERR_INVALID_ARGUMENT = 1;
    const int ERR_NOT_FOUND = 2;
    const int ERR_NO_SUCH_HANDLE = 3;
    const int ERR_OUTSIDE_HOME = 4;
    const int ERR_BOOTSTRAP_REQUIRED = 5;
    const int ERR_SPAWN_FAILED = 6;
    const int ERR_IO = 7;
    const int ERR_LIMIT = 8;
    const int ERR_KEEPALIVE_DENIED = 9;
    const int ERR_UNSUPPORTED = 10;

    int getApiVersion() = 1;
    FerruxInfo getInfo() = 2;

    // Processes. `callback` is required: it receives onExit and is the lifetime
    // anchor of everything spawned with it (§6.6).
    SpawnResult spawn(in SpawnRequest request, IProcessCallback callback) = 3;
    void resize(long handle, int rows, int cols) = 4;
    void signal(long handle, int signo) = 5;
    void kill(long handle) = 6;
    void close(long handle) = 7;

    // Sessions shared with the Ferrux app (§6.4).
    SessionInfo[] listSessions() = 8;
    AttachResult attachSession(long sessionId, int rows, int cols, IProcessCallback callback) = 9;

    // Files under $HOME (§6.5).
    FileStat stat(String path) = 10;
    FileStat[] list(String path, int offset, int limit) = 11;
    ParcelFileDescriptor openFile(String path, String mode) = 12;
    void mkdirs(String path) = 13;
    void rename(String from, String to) = 14;
    void delete(String path, boolean recursive) = 15;

    // Lifecycle (§6.7). `token` is a binder the client owns.
    void acquireKeepAlive(IBinder token, String reason) = 16;
    void releaseKeepAlive(IBinder token) = 17;
}
