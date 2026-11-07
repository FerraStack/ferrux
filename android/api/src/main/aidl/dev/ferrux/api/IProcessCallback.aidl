// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.api;

// Implemented by the client. Ferrux links to its death: when the client process
// dies, everything spawned with this callback is closed (docs/API.md §6.6).
oneway interface IProcessCallback {
    // exitCode is 0..255 for a normal exit, -1 if killed by a signal;
    // signal is the terminating signal, or 0.
    void onExit(long handle, int exitCode, int signal) = 1;
}
