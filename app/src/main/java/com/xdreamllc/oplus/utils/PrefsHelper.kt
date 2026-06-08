package com.xdreamllc.oplus.utils

import android.content.SharedPreferences
import com.xdreamllc.oplus.Config
import io.github.libxposed.api.XposedInterface

object PrefsHelper {

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    fun attach(api: XposedInterface) {
        remotePrefs = try {
            api.getRemotePreferences(Config.PREFS_NAME).also {
                XLog.debug("PrefsHelper: remote preferences attached")
            }
        } catch (e: Throwable) {
            XLog.error("PrefsHelper: getRemotePreferences failed: ${e.message}")
            null
        }
    }

    fun getPowerMode(): Int {
        return remotePrefs?.getInt(Config.KEY_POWER_MODE, Config.DEFAULT_POWER_MODE)
            ?: Config.DEFAULT_POWER_MODE
    }

    fun getCustomPackage(): String {
        return remotePrefs?.getString(Config.KEY_CUSTOM_PACKAGE, Config.DEFAULT_CUSTOM_PACKAGE)
            ?: Config.DEFAULT_CUSTOM_PACKAGE
    }

    fun isGestureBarEnabled(): Boolean {
        return remotePrefs?.getBoolean(
            Config.KEY_GESTURE_BAR_ENABLED,
            Config.DEFAULT_GESTURE_BAR_ENABLED
        ) ?: Config.DEFAULT_GESTURE_BAR_ENABLED
    }
}
