package com.jervis.service.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Service for managing security credentials needed for JERVIS to access secured portals.
 * Handles storage and retrieval of passwords, certificates, tokens, and API keys.
 */
@Service
class SecurityCredentialService {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val CREDENTIAL_FILE = "jervis-credentials.properties"
        private const val KEYSTORE_FILE = "jervis-keystore.jks"
        private const val ENCRYPTION_ALGORITHM = "AES"
        private const val KEY_LENGTH = 256
    }

    /**
     * Types of credentials that can be stored
     */
    enum class CredentialType {
        PASSWORD,
        API_TOKEN,
        ACCESS_TOKEN,
        REFRESH_TOKEN,
        API_KEY,
        SSH_KEY,
        CERTIFICATE,
        DATABASE_CONNECTION,
        SERVICE_ACCOUNT,
        OAUTH_CLIENT_SECRET,
    }

    /**
     * Credential information
     */
    data class Credential(
        val id: String,
        val type: CredentialType,
        val name: String,
        val value: String,
        val metadata: Map<String, String> = emptyMap(),
        val expiresAt: Long? = null,
    )

    private val credentialsDirectory = System.getProperty("user.home") + "/.jervis/credentials"
    private val keystorePassword = "jervis-default-password"

    private val credentialsPath: Path by lazy {
        Paths.get(credentialsDirectory, CREDENTIAL_FILE)
    }

    private val keystorePath: Path by lazy {
        Paths.get(credentialsDirectory, KEYSTORE_FILE)
    }

    private val encryptionKey: SecretKey by lazy {
        initializeEncryptionKey()
    }

    /**
     * Store a credential securely
     */
    suspend fun storeCredential(credential: Credential): Boolean =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Storing credential: ${credential.name} (${credential.type})" }

                // Create credentials directory if it doesn't exist
                Files.createDirectories(credentialsPath.parent)

                // Load existing credentials
                val credentials = loadCredentials().toMutableMap()

                // Encrypt the credential value
                val encryptedValue = encryptValue(credential.value)

                // Store the credential
                val credentialKey = "${credential.type.name.lowercase()}.${credential.id}"
                credentials[credentialKey] =
                    buildString {
                        append("name=${credential.name}")
                        append(";value=$encryptedValue")
                        if (credential.metadata.isNotEmpty()) {
                            append(";metadata=${encodeMetadata(credential.metadata)}")
                        }
                        credential.expiresAt?.let { append(";expires=$it") }
                    }

                // Save credentials
                saveCredentials(credentials)

                logger.info { "Successfully stored credential: ${credential.name}" }
                true
            } catch (e: Exception) {
                logger.error(e) { "Failed to store credential: ${credential.name}" }
                false
            }
        }

    /**
     * Retrieve a credential by ID and type
     */
    suspend fun getCredential(
        id: String,
        type: CredentialType,
    ): Credential? =
        withContext(Dispatchers.IO) {
            try {
                val credentials = loadCredentials()
                val credentialKey = "${type.name.lowercase()}.$id"
                val credentialData = credentials[credentialKey] ?: return@withContext null

                parseCredential(id, type, credentialData)
            } catch (e: Exception) {
                logger.error(e) { "Failed to retrieve credential: $id ($type)" }
                null
            }
        }

    /**
     * List all credentials (without values for security)
     */
    suspend fun listCredentials(): List<Credential> =
        withContext(Dispatchers.IO) {
            try {
                val credentials = loadCredentials()
                credentials.mapNotNull { (key, data) ->
                    val (typeStr, id) = key.split(".", limit = 2)
                    val type = CredentialType.valueOf(typeStr.uppercase())
                    parseCredential(id, type, data)?.copy(value = "***HIDDEN***")
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to list credentials" }
                emptyList()
            }
        }

    /**
     * Delete a credential
     */
    suspend fun deleteCredential(
        id: String,
        type: CredentialType,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val credentials = loadCredentials().toMutableMap()
                val credentialKey = "${type.name.lowercase()}.$id"

                if (credentials.remove(credentialKey) != null) {
                    saveCredentials(credentials)
                    logger.info { "Successfully deleted credential: $id ($type)" }
                    true
                } else {
                    logger.warn { "Credential not found for deletion: $id ($type)" }
                    false
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete credential: $id ($type)" }
                false
            }
        }

    /**
     * Store a certificate in the keystore
     */
    suspend fun storeCertificate(
        alias: String,
        certificatePath: String,
        password: String? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Storing certificate: $alias" }

                // Create keystore directory if it doesn't exist
                Files.createDirectories(keystorePath.parent)

                loadOrCreateKeyStore()
                val certFile = File(certificatePath)

                if (!certFile.exists()) {
                    logger.error { "Certificate file not found: $certificatePath" }
                    return@withContext false
                }

                // For this implementation, we'll store the certificate path as a credential
                // In a production environment, you'd load the actual certificate into the keystore
                val credential =
                    Credential(
                        id = alias,
                        type = CredentialType.CERTIFICATE,
                        name = "Certificate: $alias",
                        value = certificatePath,
                        metadata =
                            mapOf(
                                "keystore_alias" to alias,
                                "has_password" to (password != null).toString(),
                            ),
                    )

                storeCredential(credential) && saveCertificatePassword(alias, password)
            } catch (e: Exception) {
                logger.error(e) { "Failed to store certificate: $alias" }
                false
            }
        }

    /**
     * Get credentials for accessing a specific service
     */
    suspend fun getServiceCredentials(serviceName: String): Map<CredentialType, Credential> =
        withContext(Dispatchers.IO) {
            try {
                val allCredentials = listCredentials()
                allCredentials
                    .filter { credential ->
                        credential.metadata["service"] == serviceName ||
                            credential.name.contains(serviceName, ignoreCase = true)
                    }.associateBy { it.type }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get service credentials for: $serviceName" }
                emptyMap()
            }
        }

    /**
     * Store common service credentials (username/password, API key, etc.)
     */
    suspend fun storeServiceCredentials(
        serviceName: String,
        username: String? = null,
        password: String? = null,
        apiKey: String? = null,
        accessToken: String? = null,
        additionalCredentials: Map<CredentialType, String> = emptyMap(),
    ): Boolean =
        try {
            val results = mutableListOf<Boolean>()

            username?.let { user ->
                password?.let { pass ->
                    val credential =
                        Credential(
                            id = serviceName,
                            type = CredentialType.PASSWORD,
                            name = "$serviceName Login",
                            value = "$user:$pass",
                            metadata = mapOf("service" to serviceName, "username" to user),
                        )
                    results.add(storeCredential(credential))
                }
            }

            apiKey?.let { key ->
                val credential =
                    Credential(
                        id = serviceName,
                        type = CredentialType.API_KEY,
                        name = "$serviceName API Key",
                        value = key,
                        metadata = mapOf("service" to serviceName),
                    )
                results.add(storeCredential(credential))
            }

            accessToken?.let { token ->
                val credential =
                    Credential(
                        id = serviceName,
                        type = CredentialType.ACCESS_TOKEN,
                        name = "$serviceName Access Token",
                        value = token,
                        metadata = mapOf("service" to serviceName),
                    )
                results.add(storeCredential(credential))
            }

            additionalCredentials.forEach { (type, value) ->
                val credential =
                    Credential(
                        id = serviceName,
                        type = type,
                        name = "$serviceName ${type.name}",
                        value = value,
                        metadata = mapOf("service" to serviceName),
                    )
                results.add(storeCredential(credential))
            }

            results.all { it }
        } catch (e: Exception) {
            logger.error(e) { "Failed to store service credentials for: $serviceName" }
            false
        }

    /**
     * Initialize encryption key for securing credentials
     */
    private fun initializeEncryptionKey(): SecretKey {
        val keyFile = Paths.get(credentialsDirectory, "encryption.key")

        return if (Files.exists(keyFile)) {
            // Load existing key
            val keyBytes = Files.readAllBytes(keyFile)
            SecretKeySpec(keyBytes, ENCRYPTION_ALGORITHM)
        } else {
            // Generate new key
            val keyGenerator = KeyGenerator.getInstance(ENCRYPTION_ALGORITHM)
            keyGenerator.init(KEY_LENGTH)
            val key = keyGenerator.generateKey()

            // Save key for future use
            Files.createDirectories(keyFile.parent)
            Files.write(keyFile, key.encoded)

            key
        }
    }

    /**
     * Encrypt a credential value
     */
    private fun encryptValue(value: String): String {
        val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val encryptedBytes = cipher.doFinal(value.toByteArray())
        return Base64.getEncoder().encodeToString(encryptedBytes)
    }

    /**
     * Decrypt a credential value
     */
    private fun decryptValue(encryptedValue: String): String {
        val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey)
        val encryptedBytes = Base64.getDecoder().decode(encryptedValue)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes)
    }

    /**
     * Load credentials from file
     */
    private fun loadCredentials(): Map<String, String> =
        if (Files.exists(credentialsPath)) {
            val properties = Properties()
            Files.newInputStream(credentialsPath).use { input ->
                properties.load(input)
            }
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        } else {
            emptyMap()
        }

    /**
     * Save credentials to file
     */
    private fun saveCredentials(credentials: Map<String, String>) {
        val properties = Properties()
        credentials.forEach { (key, value) ->
            properties.setProperty(key, value)
        }

        Files.newOutputStream(credentialsPath).use { output ->
            properties.store(output, "JERVIS Security Credentials - DO NOT SHARE")
        }
    }

    /**
     * Parse credential from stored data
     */
    private fun parseCredential(
        id: String,
        type: CredentialType,
        data: String,
    ): Credential? =
        try {
            val parts = data.split(";")
            var name = ""
            var encryptedValue = ""
            var metadata = emptyMap<String, String>()
            var expiresAt: Long? = null

            parts.forEach { part ->
                val (key, value) = part.split("=", limit = 2)
                when (key) {
                    "name" -> name = value
                    "value" -> encryptedValue = value
                    "metadata" -> metadata = decodeMetadata(value)
                    "expires" -> expiresAt = value.toLongOrNull()
                }
            }

            val decryptedValue = decryptValue(encryptedValue)

            Credential(id, type, name, decryptedValue, metadata, expiresAt)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse credential: $id" }
            null
        }

    /**
     * Encode metadata for storage
     */
    private fun encodeMetadata(metadata: Map<String, String>): String = metadata.entries.joinToString(",") { "${it.key}:${it.value}" }

    /**
     * Decode metadata from storage
     */
    private fun decodeMetadata(encoded: String): Map<String, String> =
        if (encoded.isBlank()) {
            emptyMap()
        } else {
            encoded.split(",").associate { pair ->
                val (key, value) = pair.split(":", limit = 2)
                key to value
            }
        }

    /**
     * Load or create keystore
     */
    private fun loadOrCreateKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())

        if (Files.exists(keystorePath)) {
            Files.newInputStream(keystorePath).use { input ->
                keyStore.load(input, keystorePassword.toCharArray())
            }
        } else {
            keyStore.load(null, null)
        }

        return keyStore
    }

    /**
     * Save certificate password separately (encrypted)
     */
    private suspend fun saveCertificatePassword(
        alias: String,
        password: String?,
    ): Boolean =
        if (password != null) {
            val credential =
                Credential(
                    id = "${alias}_password",
                    type = CredentialType.PASSWORD,
                    name = "Certificate Password: $alias",
                    value = password,
                    metadata = mapOf("certificate_alias" to alias),
                )
            storeCredential(credential)
        } else {
            true
        }
}
