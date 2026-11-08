// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.api;

parcelable FileStat {
    const int TYPE_FILE = 0;
    const int TYPE_DIR = 1;
    const int TYPE_SYMLINK = 2;
    const int TYPE_OTHER = 3;

    // Absolute, with symlinks in the parent resolved.
    String path;
    String name;
    int type;
    long size;
    long modifiedMillis;
    // Permission bits (st_mode & 07777).
    int mode;
    @nullable String symlinkTarget;
}
