// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.api;

parcelable FerruxInfo {
    const int BOOTSTRAP_NOT_INSTALLED = 0;
    const int BOOTSTRAP_INSTALLING = 1;
    const int BOOTSTRAP_READY = 2;
    const int BOOTSTRAP_FAILED = 3;

    int apiVersion;
    // Ferrux app version.
    String versionName;
    long versionCode;
    String packageName;
    // $PREFIX, $HOME, $TMPDIR.
    String prefix;
    String home;
    String tmpDir;
    int bootstrapState;
    // "pipes", "pty", "pty-master-fd", "attach", "files", "keepalive", …
    String[] capabilities;
    // Build.VERSION.SDK_INT and the primary ABI, e.g. "arm64-v8a".
    int androidApi;
    String abi;
    // Processes spawned through the API that are still running (all clients).
    int liveProcesses;
    // Max live processes per client.
    int clientProcessLimit;
}
