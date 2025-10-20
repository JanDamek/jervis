package com.jervis.service.security

import com.jervis.configuration.EncryptionProperties
import org.springframework.stereotype.Service
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class KeyEncryptionService(
    encryptionProperties: EncryptionProperties,
) {
    private val masterKey: SecretKey =
        SecretKeySpec(encryptionProperties.masterKey.toByteArray().copyOf(32), "AES")

    suspend fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = iv + encrypted
        return Base64.getEncoder().encodeToString(combined)
    }

    suspend fun decrypt(encrypted: String): String {
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
