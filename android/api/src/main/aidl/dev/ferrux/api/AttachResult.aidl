// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.api;

parcelable AttachResult {
    long handle;
    // SOCK_STREAM; raw terminal bytes both ways, starting with a screen replay.
    ParcelFileDescriptor socket;
}
