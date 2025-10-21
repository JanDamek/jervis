package com.jervis.service.git

import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

/**
 * Manages GPG keys for Git commit signing.
 * Handles key import, trust configuration, and GPG home directory setup.
 */
@Service
class GpgKeyManager(
    private val directoryStructureService: DirectoryStructureService,
) {
    private val logger = KotlinLogging.logger {}

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
