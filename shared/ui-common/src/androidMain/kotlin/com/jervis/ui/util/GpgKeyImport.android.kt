package com.jervis.ui.util

// System GPG keyring is not available on Android.
actual fun listSystemGpgKeys(): List<SystemGpgKey> = emptyList()
actual fun exportSystemGpgKey(keyId: String): String? = null
