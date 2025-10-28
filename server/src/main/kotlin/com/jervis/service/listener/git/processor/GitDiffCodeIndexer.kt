package com.jervis.service.listener.git.processor

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.rag.VectorStoreIndexService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

/**
 * Indexes Git code diffs (actual code changes) into RAG system.
 * Focuses on CODE embeddings of what changed in each commit.
 *
 * Responsibilities:
 * - Extract code diffs from commits (git show)
 * - Parse changed code blocks (added/modified lines)
 * - Create CODE embeddings (not TEXT)
 * - Track in vector store with branch awareness
 * - Support reindexing when files change
 *
 * Does NOT handle:
 * - Commit metadata (see GitCommitMetadataIndexer)
 * - Pending task creation (see GitTaskCreator)
 */
@Service
class GitDiffCodeIndexer(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val vectorStoreIndexService: VectorStoreIndexService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of code diff indexing operation
     */
    data class CodeIndexingResult(
        val indexedFiles: Int,
        val indexedChunks: Int,
        val errorFiles: Int,
    )

    /**
     * Code change from a commit
     */
    data class CodeChange(
        val filePath: String,
        val language: String,
        val changeType: ChangeType,
        val addedLines: List<String>,
        val removedLines: List<String>,
        val contextBefore: String,
        val contextAfter: String,
    )

    enum class ChangeType {
        ADDED,
        MODIFIED,
        DELETED,
    }

    /**
     * Index code changes from a specific commit.
     * Extracts diffs and creates CODE embeddings.
     */
    suspend fun indexCommitCodeChanges(
        project: ProjectDocument,
        projectPath: Path,
        commitHash: String,
        branch: String,
    ): CodeIndexingResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Indexing code changes for commit ${commitHash.take(8)} in project ${project.name}" }

                // Extract code changes from commit
                val codeChanges = extractCodeChangesFromCommit(projectPath, commitHash)

                if (codeChanges.isEmpty()) {
                    logger.debug { "No code changes found in commit ${commitHash.take(8)}" }
                    return@withContext CodeIndexingResult(0, 0, 0)
                }

                var indexedFiles = 0
                var indexedChunks = 0
                var errorFiles = 0

                // Index each code change
                for (codeChange in codeChanges) {
                    try {
                        val chunksIndexed = indexCodeChange(project, commitHash, branch, codeChange)
                        indexedChunks += chunksIndexed
                        indexedFiles++
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to index code change for ${codeChange.filePath}" }
                        errorFiles++
                    }
                }

                logger.info {
                    "Code indexing completed for commit ${commitHash.take(8)}: " +
                        "files=$indexedFiles, chunks=$indexedChunks, errors=$errorFiles"
                }

                CodeIndexingResult(indexedFiles, indexedChunks, errorFiles)
            } catch (e: Exception) {
                logger.error(e) { "Error indexing code changes for commit $commitHash" }
                CodeIndexingResult(0, 0, 1)
            }
        }

    /**
     * Index a single code change as CODE embeddings
     */
    private suspend fun indexCodeChange(
        project: ProjectDocument,
        commitHash: String,
        branch: String,
        codeChange: CodeChange,
    ): Int {
        // Skip deleted files - no code to index
        if (codeChange.changeType == ChangeType.DELETED) {
            logger.debug { "Skipping deleted file: ${codeChange.filePath}" }
            return 0
        }

        // Prepare file for reindexing if it was modified
        if (codeChange.changeType == ChangeType.MODIFIED) {
            val needsReindex =
                vectorStoreIndexService.prepareFileReindexing(
                    projectId = project.id,
                    branch = branch,
                    filePath = codeChange.filePath,
                    newContent = codeChange.addedLines.joinToString("\n"),
                )

            if (!needsReindex) {
                logger.debug { "File ${codeChange.filePath} unchanged, skipping reindex" }
                return 0
            }
        }

        // Create chunks from added code
        val codeChunks = createCodeChunks(codeChange)
        var indexedChunks = 0

        for ((index, chunk) in codeChunks.withIndex()) {
            val sourceId = "$commitHash:${codeChange.filePath}:chunk-$index"

            // Generate CODE embedding (not TEXT!)
            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_CODE, chunk)

            val ragDocument =
                RagDocument(
                    projectId = project.id,
                    ragSourceType = RagSourceType.CODE_CHANGE,
                    summary = "Code change in ${codeChange.filePath}",
                    clientId = project.clientId,
                    // Code-specific metadata
                    fileName = codeChange.filePath,
                    language = codeChange.language,
                    gitCommitHash = commitHash,
                    branch = branch,
                    chunkId = index,
                    // Content
                    contentType = "code-diff",
                    from = "git-commit",
                    timestamp =
                        java.time.Instant
                            .now()
                            .toString(),
                )

            // Store in Qdrant CODE collection
            val vectorStoreId = vectorStorage.store(ModelType.EMBEDDING_CODE, ragDocument, embedding)

            // Track in MongoDB
            vectorStoreIndexService.trackIndexed(
                projectId = project.id,
                clientId = project.clientId,
                branch = branch,
                sourceType = RagSourceType.CODE_CHANGE,
                sourceId = sourceId,
                vectorStoreId = vectorStoreId,
                vectorStoreName = "code-change-${commitHash.take(8)}-$index",
                content = chunk,
                filePath = codeChange.filePath,
                symbolName = null,
                commitHash = commitHash,
            )

            indexedChunks++
        }

        logger.debug { "Indexed ${codeChunks.size} code chunks for ${codeChange.filePath}" }
        return indexedChunks
    }

    /**
     * Extract code changes from a commit using git show
     */
    private suspend fun extractCodeChangesFromCommit(
        projectPath: Path,
        commitHash: String,
    ): List<CodeChange> =
        withContext(Dispatchers.IO) {
            try {
                val process =
                    ProcessBuilder(
                        "git",
                        "show",
                        "--no-color",
                        "--unified=3",
                        commitHash,
                    ).directory(projectPath.toFile())
                        .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val changes = mutableListOf<CodeChange>()
                var currentFile: String? = null
                var currentLanguage: String? = null
                var currentChangeType: ChangeType? = null
                val addedLines = mutableListOf<String>()
                val removedLines = mutableListOf<String>()

                reader.useLines { lines ->
                    for (line in lines) {
                        when {
                            line.startsWith("diff --git") -> {
                                // Save previous file if exists
                                if (currentFile != null && currentChangeType != null) {
                                    changes.add(
                                        CodeChange(
                                            filePath = currentFile,
                                            language = currentLanguage ?: "unknown",
                                            changeType = currentChangeType,
                                            addedLines = addedLines.toList(),
                                            removedLines = removedLines.toList(),
                                            contextBefore = "",
                                            contextAfter = "",
                                        ),
                                    )
                                }

                                // Start new file
                                addedLines.clear()
                                removedLines.clear()
                                currentFile = null
                                currentChangeType = ChangeType.MODIFIED
                            }

                            line.startsWith("+++ b/") -> {
                                val filePath = line.removePrefix("+++ b/").trim()
                                currentFile = filePath
                                currentLanguage = detectLanguage(filePath)
                            }

                            line.startsWith("new file mode") -> {
                                currentChangeType = ChangeType.ADDED
                            }

                            line.startsWith("deleted file mode") -> {
                                currentChangeType = ChangeType.DELETED
                            }

                            line.startsWith("+") && !line.startsWith("+++") -> {
                                addedLines.add(line.removePrefix("+"))
                            }

                            line.startsWith("-") && !line.startsWith("---") -> {
                                removedLines.add(line.removePrefix("-"))
                            }
                        }
                    }
                }

                // Add last file
                if (currentFile != null && currentChangeType != null) {
                    changes.add(
                        CodeChange(
                            filePath = currentFile,
                            language = currentLanguage ?: "unknown",
                            changeType = currentChangeType,
                            addedLines = addedLines.toList(),
                            removedLines = removedLines.toList(),
                            contextBefore = "",
                            contextAfter = "",
                        ),
                    )
                }

                process.waitFor()
                logger.debug { "Extracted ${changes.size} code changes from commit ${commitHash.take(8)}" }
                changes
            } catch (e: Exception) {
                logger.error(e) { "Failed to extract code changes from commit $commitHash" }
                emptyList()
            }
        }

    /**
     * Create code chunks from code change (max 512 tokens per chunk)
     */
    private fun createCodeChunks(codeChange: CodeChange): List<String> {
        val allCode = codeChange.addedLines.joinToString("\n")

        // If code is small, return as single chunk
        if (allCode.length < 2000) {
            return listOf(allCode)
        }

        // Split into chunks of ~50 lines
        val chunks = mutableListOf<String>()
        val lines = codeChange.addedLines
        var currentChunk = mutableListOf<String>()

        for (line in lines) {
            currentChunk.add(line)

            if (currentChunk.size >= 50) {
                chunks.add(currentChunk.joinToString("\n"))
                currentChunk.clear()
            }
        }

        // Add remaining lines
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.joinToString("\n"))
        }

        return chunks
    }

    /**
     * Detect programming language from file path
     */
    private fun detectLanguage(filePath: String): String =
        when {
            filePath.endsWith(".kt") -> "kotlin"
            filePath.endsWith(".java") -> "java"
            filePath.endsWith(".py") -> "python"
            filePath.endsWith(".js") || filePath.endsWith(".ts") -> "javascript"
            filePath.endsWith(".go") -> "go"
            filePath.endsWith(".rs") -> "rust"
            filePath.endsWith(".cpp") || filePath.endsWith(".cc") -> "cpp"
            filePath.endsWith(".c") -> "c"
            filePath.endsWith(".rb") -> "ruby"
            filePath.endsWith(".php") -> "php"
            filePath.endsWith(".cs") -> "csharp"
            filePath.endsWith(".swift") -> "swift"
            else -> "unknown"
        }
}
