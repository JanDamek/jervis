package com.jervis.ui.util

/**
 * Platform-specific browser opener
 */
expect fun openUrlInBrowser(url: String)

/**
 * Opens URL in a private/incognito browser window.
 * Used for OAuth2 re-authentication to force a fresh login as a different user.
 * On platforms where incognito cannot be controlled, falls back to [openUrlInBrowser].
 */
expect fun openUrlInPrivateBrowser(url: String)
