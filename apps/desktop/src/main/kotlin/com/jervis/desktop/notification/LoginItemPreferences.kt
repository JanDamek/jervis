package com.jervis.desktop.notification

import com.jervis.ui.notification.MacAppSocketBridge
import java.util.prefs.Preferences

object LoginItemPreferences {
    private val prefs: Preferences = Preferences.userRoot().node("com/jervis/desktop/loginItem")

    private const val KEY_PROMPT_SHOWN = "promptShown"
    private const val KEY_ENABLED = "enabled"

    fun shouldPromptUser(): Boolean = !prefs.getBoolean(KEY_PROMPT_SHOWN, false)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun applyUserChoice(enabled: Boolean) {
        prefs.putBoolean(KEY_PROMPT_SHOWN, true)
        prefs.putBoolean(KEY_ENABLED, enabled)
        MacAppSocketBridge.setLoginItem(enabled)
    }

    fun setEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_ENABLED, enabled)
        MacAppSocketBridge.setLoginItem(enabled)
    }
}
