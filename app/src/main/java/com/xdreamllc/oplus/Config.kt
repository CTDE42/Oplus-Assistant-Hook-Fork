package com.xdreamllc.oplus

object Config {
    const val PREFS_NAME = "oplus_hook_config"

    const val KEY_POWER_MODE = "power_mode"
    const val POWER_MODE_GEMINI = 0
    const val POWER_MODE_CIRCLE = 1
    const val POWER_MODE_CUSTOM = 2
    const val POWER_MODE_NONE = -1
    const val DEFAULT_POWER_MODE = POWER_MODE_NONE

    const val KEY_CUSTOM_PACKAGE = "custom_package"
    const val DEFAULT_CUSTOM_PACKAGE = ""

    const val KEY_GESTURE_BAR_ENABLED = "gesture_bar_enabled"
    const val DEFAULT_GESTURE_BAR_ENABLED = false

    const val PKG_GOOGLE = "com.google.android.googlequicksearchbox"
}
