package com.jervis.service.git

import com.jervis.entity.mongo.ServiceCredentialsDocument
import com.jervis.service.security.KeyEncryptionService
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Manages GPG keys for Git commit signing.
 * Handles key import, trust configuration, and GPG home directory setup.
 */
@Service
class GpgKeyManager(
    private val directoryStructureService: DirectoryStructureService,
    private val keyEncryptionService: KeyEncryptionService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun importGpgKey(credentials: ServiceCredentialsDocument): String? =
        withContext(Dispatchers.IO) {
            try {
                val gpgPrivateKey = credentials.gpgPrivateKey
                if (gpgPrivateKey.isNullOrBlank()) {
                    logger.warn { "No GPG private key found in credentials" }
                    return@withContext null
                }

                val decryptedKey = keyEncryptionService.decryptGpgKey(gpgPrivateKey)
                val gpgHome = directoryStructureService.gpgKeysDir()
                directoryStructureService.ensureDirectoryExists(gpgHome)

                val tempKeyFile = Files.createTempFile(gpgHome, "gpg-import-", ".key")
                try {
                    Files.writeString(tempKeyFile, decryptedKey)

                    val importProcess =
                        ProcessBuilder(
                            "gpg",
                            "--homedir",
                            gpgHome.toAbsolutePath().toString(),
                            "--batch",
                            "--import",
                            tempKeyFile.toAbsolutePath().toString(),
                        ).redirectErrorStream(true)
                            .start()

                    val output = BufferedReader(InputStreamReader(importProcess.inputStream)).use { it.readText() }
                    val exitCode = importProcess.waitFor()

                    if (exitCode == 0) {
                        val keyId = extractKeyId(output)
                        if (keyId != null) {
                            setTrustLevel(gpgHome, keyId)
                            logger.info { "Successfully imported GPG key: $keyId" }
                            return@withContext keyId
                        } else {
                            logger.warn { "GPG import succeeded but could not extract key ID from output: $output" }
                        }
                    } else {
                        logger.error { "GPG import failed with exit code $exitCode: $output" }
                    }
                } finally {
                    Files.deleteIfExists(tempKeyFile)
                }

                null
            } catch (e: Exception) {
                logger.error(e) { "Failed to import GPG key" }
                null
            }
        }

    private fun extractKeyId(gpgOutput: String): String? {
        val keyPattern = Regex("key ([A-F0-9]+):")
        return keyPattern.find(gpgOutput)?.groupValues?.getOrNull(1)
    }

    private fun setTrustLevel(
        gpgHome: Path,
        keyId: String,
    ) {
        try {
            val trustProcess =
                ProcessBuilder(
                    "gpg",
                    "--homedir",
                    gpgHome.toAbsolutePath().toString(),
                    "--batch",
                    "--command-fd",
                    "0",
                    "--edit-key",
                    keyId,
                ).redirectErrorStream(true)
                    .start()

            trustProcess.outputStream.use { outputStream ->
                outputStream.write("trust\n5\ny\nquit\n".toByteArray())
                outputStream.flush()
            }

            val exitCode = trustProcess.waitFor()
            if (exitCode == 0) {
                logger.info { "Set ultimate trust for GPG key: $keyId" }
            } else {
                logger.warn { "Failed to set trust level for GPG key $keyId, exit code: $exitCode" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Exception while setting trust level for GPG key $keyId" }
        }
    }

    suspend fun configureGitGpgSigning(
        gitDir: Path,
        keyId: String,
        userName: String?,
        userEmail: String?,
    ) {
        withContext(Dispatchers.IO) {
            try {
                directoryStructureService.gpgKeysDir()

                val commands =
                    listOf(
                        listOf("git", "config", "commit.gpgsign", "true"),
                        listOf("git", "config", "user.signingkey", keyId),
                        listOf("git", "config", "gpg.program", "gpg"),
                    ).toMutableList()

                if (userName != null) {
                    commands.add(listOf("git", "config", "user.name", userName))
                }
                if (userEmail != null) {
                    commands.add(listOf("git", "config", "user.email", userEmail))
                }

                for (command in commands) {
                    val process =
                        ProcessBuilder(command)
                            .directory(gitDir.toFile())
                            .redirectErrorStream(true)
                            .start()

                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                        logger.warn { "Git config command failed: ${command.joinToString(" ")}. Output: $output" }
                    }
                }

                logger.info { "Configured GPG signing for git directory: $gitDir with key $keyId" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to configure Git GPG signing" }
            }
        }
    }
}
