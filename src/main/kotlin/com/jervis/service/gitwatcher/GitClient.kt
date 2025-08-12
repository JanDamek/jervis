package com.jervis.service.gitwatcher

import com.jervis.domain.git.CommitInfo
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Client for interacting with Git repositories.
 * This class provides methods for retrieving information about Git repositories,
 * such as commit history and changed files.
 */
@Component
class GitClient {
    private val logger = KotlinLogging.logger {}

    fun getLastCommitTime(repoPath: String?): Instant {
        if (repoPath == null) return Instant.EPOCH
        val gitDir = File(repoPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) return Instant.EPOCH

        val process =
            ProcessBuilder()
                .directory(File(repoPath))
                .command("git", "log", "-1", "--format=%aI")
                .start()

        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        process.waitFor(5, TimeUnit.SECONDS)

        return try {
            Instant.parse(output)
        } catch (e: Exception) {
            logger.error(e) { "Error parsing Git date: $output" }
            Instant.EPOCH
        }
    }

    fun getLastCommitInfo(repoPath: String?): CommitInfo? {
        if (repoPath == null) return null
        val gitDir = File(repoPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) return null

        val process =
            ProcessBuilder()
                .directory(File(repoPath))
                .command("git", "log", "-1", "--pretty=format:%H%n%an%n%ae%n%aI%n%s")
                .start()

        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)

        if (output.size < 5) return null

        return try {
            val commitId = output[0]
            val authorName = output[1]
            val authorEmail = output[2]
            val time = Instant.parse(output[3])
            val message = output[4]

            CommitInfo(commitId, authorName, authorEmail, time, message)
        } catch (e: Exception) {
            println("Error parsing Git commit info: ${e.message}")
            null
        }
    }

    fun getFileCommitInfo(
        repoPath: String?,
        filePath: String,
    ): CommitInfo? {
        if (repoPath == null) return null
        val gitDir = File(repoPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) return null

        val process =
            ProcessBuilder()
                .directory(File(repoPath))
                .command("git", "log", "-1", "--pretty=format:%H%n%an%n%ae%n%aI%n%s", "--", filePath)
                .start()

        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)

        if (output.size < 5) return null

        return try {
            val commitId = output[0]
            val authorName = output[1]
            val authorEmail = output[2]
            val time = Instant.parse(output[3])
            val message = output[4]

            CommitInfo(commitId, authorName, authorEmail, time, message)
        } catch (e: Exception) {
            println("Error parsing Git commit info for file $filePath: ${e.message}")
            null
        }
    }

    fun getChangedFilesSince(
        repoPath: String?,
        since: Instant?,
    ): List<String> {
        if (repoPath == null) return emptyList()
        val gitDir = File(repoPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) return emptyList()

        if (since == null || since == Instant.EPOCH) {
            val process =
                ProcessBuilder()
                    .directory(File(repoPath))
                    .command("git", "ls-files")
                    .start()

            val output = process.inputStream.bufferedReader().readLines()
            process.waitFor(5, TimeUnit.SECONDS)
            return output
        }

        val sinceDate = since.toString()

        val process =
            ProcessBuilder()
                .directory(File(repoPath))
                .command("git", "log", "--since=$sinceDate", "--name-only", "--pretty=format:")
                .start()

        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)
        return output.filter { it.isNotBlank() }.distinct()
    }

    fun getCommitHistory(
        repoPath: String?,
        maxCommits: Int = 100,
    ): List<CommitInfo> {
        if (repoPath == null) return emptyList()
        val gitDir = File(repoPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) return emptyList()

        val process =
            ProcessBuilder()
                .directory(File(repoPath))
                .command("git", "log", "-$maxCommits", "--pretty=format:%H%n%an%n%ae%n%aI%n%s", "--date=iso")
                .start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)

        if (output.isBlank()) return emptyList()

        val commits = mutableListOf<CommitInfo>()
        val commitBlocks = output.trim().split("\n").chunked(5)

        for (block in commitBlocks) {
            if (block.size < 5) continue
            try {
                val commitId = block[0]
                val authorName = block[1]
                val authorEmail = block[2]
                val time = Instant.parse(block[3])
                val message = block[4]

                val filesProcess =
                    ProcessBuilder()
                        .directory(File(repoPath))
                        .command("git", "show", "--name-only", "--format=", commitId)
                        .start()

                val changedFiles =
                    filesProcess.inputStream
                        .bufferedReader()
                        .readLines()
                        .filter { it.isNotBlank() }
                filesProcess.waitFor(5, TimeUnit.SECONDS)

                commits.add(CommitInfo(commitId, authorName, authorEmail, time, message, changedFiles))
            } catch (e: Exception) {
                println("Error parsing Git commit info: ${e.message}")
            }
        }

        return commits
    }
}
