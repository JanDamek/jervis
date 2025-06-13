package com.jervis.module.gitwatcher

import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Client for interacting with Git repositories.
 * This class provides methods for retrieving information about Git repositories,
 * such as commit history and changed files.
 */
@Component
class GitClient {

    /**
     * Represents information about a Git commit
     */
    data class CommitInfo(
        val id: String,
        val author: String,
        val time: Instant,
        val message: String
    )

    /**
     * Get the timestamp of the last commit in the repository
     * 
     * @param repoPath Path to the Git repository
     * @return Timestamp of the last commit
     */
    fun getLastCommitTime(repoPath: String?): Instant {
        if (repoPath == null) return Instant.EPOCH

        val gitDir = File(repoPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) {
            return Instant.EPOCH
        }

        val process = ProcessBuilder()
            .directory(File(repoPath))
            .command("git", "log", "-1", "--format=%cd", "--date=iso")
            .start()

        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(5, TimeUnit.SECONDS)

        return if (output.isNotEmpty()) {
            try {
                // Parse the Git date format (e.g., "2023-05-15 10:30:45 +0200")
                val dateTime = java.time.LocalDateTime.parse(
                    output.substring(0, 19),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                )
                dateTime.atZone(ZoneId.systemDefault()).toInstant()
            } catch (e: Exception) {
                println("Error parsing Git date: $output")
                Instant.EPOCH
            }
        } else {
            Instant.EPOCH
        }
    }

    /**
     * Get information about the last commit in the repository
     * 
     * @param repoPath Path to the Git repository
     * @return Information about the last commit, or null if no commits exist
     */
    fun getLastCommitInfo(repoPath: String?): CommitInfo? {
        if (repoPath == null) return null

        val gitDir = File(repoPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) {
            return null
        }

        val process = ProcessBuilder()
            .directory(File(repoPath))
            .command("git", "log", "-1", "--format=%H%n%an%n%cd%n%s", "--date=iso")
            .start()

        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)

        if (output.size < 4) return null

        return try {
            val commitId = output[0]
            val author = output[1]
            val dateStr = output[2]
            val message = output[3]

            // Parse the Git date format (e.g., "2023-05-15 10:30:45 +0200")
            val dateTime = java.time.LocalDateTime.parse(
                dateStr.substring(0, 19),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            )
            val time = dateTime.atZone(ZoneId.systemDefault()).toInstant()

            CommitInfo(commitId, author, time, message)
        } catch (e: Exception) {
            println("Error parsing Git commit info: ${e.message}")
            null
        }
    }

    /**
     * Get commit information for a specific file
     * 
     * @param repoPath Path to the Git repository
     * @param filePath Path to the file, relative to the repository root
     * @return Information about the last commit that modified the file, or null if no commits exist
     */
    fun getFileCommitInfo(repoPath: String?, filePath: String): CommitInfo? {
        if (repoPath == null) return null

        val gitDir = File(repoPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) {
            return null
        }

        val process = ProcessBuilder()
            .directory(File(repoPath))
            .command("git", "log", "-1", "--format=%H%n%an%n%cd%n%s", "--date=iso", "--", filePath)
            .start()

        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)

        if (output.size < 4) return null

        return try {
            val commitId = output[0]
            val author = output[1]
            val dateStr = output[2]
            val message = output[3]

            // Parse the Git date format (e.g., "2023-05-15 10:30:45 +0200")
            val dateTime = java.time.LocalDateTime.parse(
                dateStr.substring(0, 19),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            )
            val time = dateTime.atZone(ZoneId.systemDefault()).toInstant()

            CommitInfo(commitId, author, time, message)
        } catch (e: Exception) {
            println("Error parsing Git commit info for file $filePath: ${e.message}")
            null
        }
    }

    /**
     * Get a list of files that have changed since the specified timestamp
     * 
     * @param repoPath Path to the Git repository
     * @param since Timestamp to check changes since
     * @return List of changed file paths
     */
    fun getChangedFilesSince(repoPath: String?, since: Instant?): List<String> {
        if (repoPath == null) return emptyList()

        val gitDir = File(repoPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) {
            return emptyList()
        }

        // If no previous timestamp, return all files
        if (since == null || since == Instant.EPOCH) {
            val process = ProcessBuilder()
                .directory(File(repoPath))
                .command("git", "ls-files")
                .start()

            val output = process.inputStream.bufferedReader().readLines()
            process.waitFor(5, TimeUnit.SECONDS)

            return output
        }

        // Convert the timestamp to a Git-friendly format
        val sinceDate = since.atZone(ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val process = ProcessBuilder()
            .directory(File(repoPath))
            .command("git", "diff", "--name-only", "--diff-filter=ACMRT", "--since=\"$sinceDate\"")
            .start()

        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)

        return output
    }
}
