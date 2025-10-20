package com.jervis.domain.git

/**
 * Git workflow configuration including authentication credentials.
 * Contains both workflow rules (commit conventions, signing) and encrypted authentication credentials.
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
    val encryptedSshPrivateKey: String? = null,
    val sshPublicKey: String? = null,
    val encryptedSshPassphrase: String? = null,
    val encryptedHttpsToken: String? = null,
    val httpsUsername: String? = null,
    val encryptedHttpsPassword: String? = null,
    val encryptedGpgPrivateKey: String? = null,
    val gpgPublicKey: String? = null,
    val encryptedGpgPassphrase: String? = null,
)
