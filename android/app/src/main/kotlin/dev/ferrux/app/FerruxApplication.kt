// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.app.Application
import android.util.Log

class FerruxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val loadError = FerruxAppNative.loadError
        if (loadError != null) {
            Log.e(TAG, "libferrux.so not available", loadError)
            return
        }
        FerruxAppNative.init(filesDir.absolutePath, applicationInfo.nativeLibraryDir)
        // TODO(M3): bootstrap installation (ferrux-env installer) goes here, before
        // the first session: extract the prefix into files/usr via a staging dir.
    }

    companion object {
        const val TAG = "Ferrux"
    }
}
