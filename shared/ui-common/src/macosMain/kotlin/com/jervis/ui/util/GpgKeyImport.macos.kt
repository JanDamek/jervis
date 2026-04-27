package com.jervis.ui.util

actual fun listSystemGpgKeys(): List<SystemGpgKey> = emptyList()

actual fun exportSystemGpgKey(keyId: String): String? = null
