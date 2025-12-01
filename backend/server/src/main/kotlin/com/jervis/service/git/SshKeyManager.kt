package com.jervis.service.git

import com.jervis.entity.ProjectDocument
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Manages SSH keys for Git authentication.
 * Handles key storage, SSH config generation, and permission management.
 *
 * Supports both:
 * - Standalone project SSH keys (stored per project)
 * - Mono-repo SSH keys (stored per client + monoRepoId)
 */
@Service
class SshKeyManager(
    private val directoryStructureService: DirectoryStructureService,
) {
    private val logger = KotlinLogging.logger {}

    private fun createSshConfig(
        keyDir: Path,
        privateKeyPath: Path,
        project: ProjectDocument,
    ): Path {
        val configPath = keyDir.resolve("config")
        val configContent =
            """
            Host *
                StrictHostKeyChecking no
                UserKnownHostsFile=/dev/null
                IdentityFile ${privateKeyPath.toAbsolutePath()}
            """.trimIndent()

        Files.writeString(configPath, configContent)
        setFilePermissions(configPath, "600")
        return configPath
    }

    suspend fun createSshWrapper(
        sshConfigPath: Path,
        keyDir: Path,
    ): Path =
        withContext(Dispatchers.IO) {
            val wrapperPath = keyDir.resolve("git-ssh-wrapper.sh")
            val wrapperContent =
                """
                #!/bin/sh
                ssh -F ${sshConfigPath.toAbsolutePath()} "$@"
                """.trimIndent()

            Files.writeString(wrapperPath, wrapperContent)
            setFilePermissions(wrapperPath, "700")
            wrapperPath
        }

    private fun setFilePermissions(
        path: Path,
        mode: String,
    ) {
        try {
            val permissions = mutableSetOf<PosixFilePermission>()
            when (mode) {
                "600" -> {
                    permissions.add(PosixFilePermission.OWNER_READ)
                    permissions.add(PosixFilePermission.OWNER_WRITE)
                }

                "644" -> {
                    permissions.add(PosixFilePermission.OWNER_READ)
                    permissions.add(PosixFilePermission.OWNER_WRITE)
                    permissions.add(PosixFilePermission.GROUP_READ)
                    permissions.add(PosixFilePermission.OTHERS_READ)
                }

                "700" -> {
                    permissions.add(PosixFilePermission.OWNER_READ)
                    permissions.add(PosixFilePermission.OWNER_WRITE)
                    permissions.add(PosixFilePermission.OWNER_EXECUTE)
                }
            }
            Files.setPosixFilePermissions(path, permissions)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to set POSIX permissions for $path, might be on non-POSIX filesystem" }
        }
    }

    suspend fun cleanupSshKeys(project: ProjectDocument) {
        withContext(Dispatchers.IO) {
            try {
                val keyDir = directoryStructureService.projectSshKeyDir(project)
                if (Files.exists(keyDir)) {
                    Files.walk(keyDir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                    logger.info { "Cleaned up SSH keys for project ${project.name}" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to cleanup SSH keys for project ${project.name}" }
            }
        }
    }

    /**
     * Create temporary SSH key for validation purposes.
     * Returns path to SSH wrapper script.
     */
    suspend fun createTemporarySshKey(
        tempDir: Path,
        privateKey: String,
    ): Path =
        withContext(Dispatchers.IO) {
            Files.createDirectories(tempDir)

            val privateKeyPath = tempDir.resolve("id_rsa_temp")
            Files.writeString(privateKeyPath, privateKey)
            setFilePermissions(privateKeyPath, "600")

            val sshConfigPath = tempDir.resolve("ssh_config")
            val sshConfig =
                """
                Host *
                    IdentityFile ${privateKeyPath.toAbsolutePath()}
                    StrictHostKeyChecking no
                    UserKnownHostsFile=/dev/null
                """.trimIndent()
            Files.writeString(sshConfigPath, sshConfig)
            setFilePermissions(sshConfigPath, "600")

            createSshWrapper(sshConfigPath, tempDir)
        }

    /**
     * Prepare SSH authentication with decrypted private key.
     * Returns path to SSH config file.
     */
    suspend fun prepareSshAuthenticationWithKey(
        project: ProjectDocument,
        decryptedPrivateKey: String,
        publicKey: String? = null,
    ): Path? =
        withContext(Dispatchers.IO) {
            try {
                val keyDir = directoryStructureService.projectSshKeyDir(project)
                directoryStructureService.ensureDirectoryExists(keyDir)

                val privateKeyPath = keyDir.resolve("id_rsa")
                Files.writeString(privateKeyPath, decryptedPrivateKey)
                setFilePermissions(privateKeyPath, "600")

                publicKey?.let { pubKey ->
                    val publicKeyPath = keyDir.resolve("id_rsa.pub")
                    Files.writeString(publicKeyPath, pubKey)
                    setFilePermissions(publicKeyPath, "644")
                }

                val sshConfigPath = createSshConfig(keyDir, privateKeyPath, project)
                logger.info { "SSH authentication prepared for project ${project.name} at $keyDir" }

                sshConfigPath
            } catch (e: Exception) {
                logger.error(e) { "Failed to prepare SSH authentication for project ${project.name}" }
                null
            }
        }
}
