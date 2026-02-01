package com.jervis.domain.git

/**
 * Git workflow configuration including authentication credentials.
 * Contains workflow rules (commit conventions, signing) and raw (unencrypted) auth credentials.
 * Intended for internal deployments only.
 */
data class GitConfig(
    val gitUserName: String? = null,
    val gitUserEmail: String? = null,
    val commitMessageTemplate: String? = null,
    val requireGpgSign: Boolean = false,
    val gpgKeyId: String? = null,
    val requireLinearHistory: Boolean = false,
    val conventionalCommits: Boolean = false,
    val commitRules: Map<String, String> = emptyMap(),
    val sshPrivateKey: String? = null,
    val sshPublicKey: String? = null,
    val sshPassphrase: String? = null,
    val httpsToken: String? = null,
    val httpsUsername: String? = null,
    val httpsPassword: String? = null,
    val gpgPrivateKey: String? = null,
    val gpgPublicKey: String? = null,
    val gpgPassphrase: String? = null,
    val joernGraphPath: String? = null,
)
