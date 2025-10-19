package com.jervis.service.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Service for encrypting and decrypting sensitive keys (SSH, GPG) using AES-256-GCM.
 * Uses master key from environment variable for encryption.
 */
@Service
class KeyEncryptionService(
    @Value("\${encryption.master-key:REPLACE_ME_WITH_SECURE_KEY_32BYTES!!}") private val masterKeyString: String,
) {
    private val logger = KotlinLogging.logger {}
    private val masterKey: SecretKey = SecretKeySpec(masterKeyString.toByteArray().copyOf(32), "AES")

    suspend fun encryptSshKey(plainKey: String): String =
        withContext(Dispatchers.Default) {
            encrypt(plainKey)
        }

    suspend fun decryptSshKey(encryptedKey: String): String =
        withContext(Dispatchers.Default) {
            decrypt(encryptedKey)
        }

    suspend fun encryptGpgKey(plainKey: String): String =
        withContext(Dispatchers.Default) {
            encrypt(plainKey)
        }

    suspend fun decryptGpgKey(encryptedKey: String): String =
        withContext(Dispatchers.Default) {
            decrypt(encryptedKey)
        }

    suspend fun encryptPassphrase(plainPassphrase: String): String =
        withContext(Dispatchers.Default) {
            encrypt(plainPassphrase)
        }

    suspend fun decryptPassphrase(encryptedPassphrase: String): String =
        withContext(Dispatchers.Default) {
            decrypt(encryptedPassphrase)
        }

    suspend fun encryptToken(plainToken: String): String =
        withContext(Dispatchers.Default) {
            encrypt(plainToken)
        }

    suspend fun decryptToken(encryptedToken: String): String =
        withContext(Dispatchers.Default) {
            decrypt(encryptedToken)
        }

    suspend fun encryptPassword(plainPassword: String): String =
        withContext(Dispatchers.Default) {
            encrypt(plainPassword)
        }

    suspend fun decryptPassword(encryptedPassword: String): String =
        withContext(Dispatchers.Default) {
            decrypt(encryptedPassword)
        }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = iv + encrypted
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(encrypted: String): String {
        val combined = Base64.getDecoder().decode(encrypted)
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        val decrypted = cipher.doFinal(ciphertext)

        return String(decrypted, Charsets.UTF_8)
    }
}
