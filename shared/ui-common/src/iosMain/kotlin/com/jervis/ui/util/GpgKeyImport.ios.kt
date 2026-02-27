package com.jervis.ui.util

// System GPG keyring is not available on iOS.
actual fun listSystemGpgKeys(): List<SystemGpgKey> = emptyList()
actual fun exportSystemGpgKey(keyId: String): String? = null
