// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.api;

parcelable SessionInfo {
    long sessionId;
    @nullable String title;
    int pid;
    int rows;
    int cols;
    boolean exited;
    // System.currentTimeMillis() at creation.
    long createdAtMillis;
}
