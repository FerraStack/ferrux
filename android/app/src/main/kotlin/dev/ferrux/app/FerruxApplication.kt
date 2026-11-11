// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.app.Application
import android.util.Log

class FerruxApplication : Application() {
    /** First-run bootstrap installer; null when libferrux.so is missing. */
    var bootstrap: BootstrapInstaller? = null
        private set

    override fun onCreate() {
        super.onCreate()
        val loadError = FerruxAppNative.loadError
        if (loadError != null) {
            Log.e(TAG, "libferrux.so not available", loadError)
            return
        }
        FerruxAppNative.init(filesDir.absolutePath, applicationInfo.nativeLibraryDir)
        bootstrap = BootstrapInstaller(this)
    }

    companion object {
        const val TAG = "Ferrux"
    }
}
