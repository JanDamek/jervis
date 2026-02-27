package com.jervis.ui.util

/**
 * System GPG key discovered from the local keyring.
 */
data class SystemGpgKey(
    val keyId: String,
    val fingerprint: String,
    val userName: String,
    val userEmail: String,
)

/**
 * Lists secret GPG keys available in the system keyring.
 * Desktop (JVM): Calls `gpg --list-secret-keys`.
 * Android/iOS: Returns empty list (system GPG not available).
 */
expect fun listSystemGpgKeys(): List<SystemGpgKey>

/**
 * Exports a secret GPG key as ASCII-armored PGP block.
 * Desktop (JVM): Calls `gpg --export-secret-keys --armor <keyId>`.
 * Android/iOS: Returns null (not supported).
 */
expect fun exportSystemGpgKey(keyId: String): String?
