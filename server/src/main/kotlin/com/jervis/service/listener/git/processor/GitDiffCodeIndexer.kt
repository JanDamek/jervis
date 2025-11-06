package com.jervis.service.listener.git.processor

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.domain.model.ModelTypeEnum
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
import java.util.Base64

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
    private val tikaClient: ITikaClient,
    private val textChunkingService: com.jervis.service.text.TextChunkingService,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val MAX_CHUNK_SIZE = 4000 // chars per chunk
        private const val MAX_FILE_SIZE = 1_000_000 // 1MB max for processing
    }

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

    // ========== Standalone Project Methods ==========

    /**
     * Index code changes from a specific commit for standalone project.
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
     * Index a single code change with file type classification
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

        // Classify file type
        val fileType = classifyFileType(codeChange.filePath)

        when (fileType) {
            FileType.BINARY -> {
                logger.debug { "Skipping binary file: ${codeChange.filePath}" }
                return 0
            }

            FileType.CODE -> {
                return indexAsCodeEmbedding(project, commitHash, branch, codeChange)
            }

            FileType.TEXT -> {
                return indexAsTextEmbedding(project, commitHash, branch, codeChange)
            }
        }
    }

    /**
     * Index source code file using CODE embeddings
     */
    private suspend fun indexAsCodeEmbedding(
        project: ProjectDocument,
        commitHash: String,
        branch: String,
        codeChange: CodeChange,
    ): Int {
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
            // Validate chunk is not empty
            if (chunk.isBlank()) {
                logger.debug { "Skipping empty chunk $index for ${codeChange.filePath}" }
                continue
            }

            try {
                val sourceId = "$commitHash:${codeChange.filePath}:chunk-$index"

                // Generate CODE embedding with validation
                val embedding = embeddingGateway.callEmbedding(ModelTypeEnum.EMBEDDING_CODE, chunk)

                // Validate embedding
                if (embedding.isEmpty() || embedding.all { it == 0f }) {
                    throw IllegalStateException("Embedding returned empty/zero vector")
                }

                val ragDocument =
                    RagDocument(
                        projectId = project.id,
                        ragSourceType = RagSourceType.CODE_CHANGE,
                        text = "Code change in ${codeChange.filePath}",
                        clientId = project.clientId,
                        fileName = codeChange.filePath,
                        branch = branch,
                        chunkId = index,
                        from = "git-commit",
                        timestamp =
                            java.time.Instant
                                .now()
                                .toString(),
                    )

                // Store in Qdrant CODE collection
                val vectorStoreId = vectorStorage.store(ModelTypeEnum.EMBEDDING_CODE, ragDocument, embedding)

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
            } catch (e: Exception) {
                logger.warn(e) {
                    "Failed to embed chunk $index of ${codeChange.filePath}: ${e.message}. " +
                        "File may contain unsupported characters."
                }
            }
        }

        logger.debug { "Indexed $indexedChunks code chunks for ${codeChange.filePath}" }
        return indexedChunks
    }

    /**
     * Index text/document file using TEXT embeddings + Tika
     */
    private suspend fun indexAsTextEmbedding(
        project: ProjectDocument,
        commitHash: String,
        branch: String,
        codeChange: CodeChange,
    ): Int =
        withContext(Dispatchers.IO) {
            try {
                logger.debug { "Processing ${codeChange.filePath} as TEXT file (using Tika)" }

                // Get full file content (not just diff)
                val fileContent = codeChange.addedLines.joinToString("\n")

                // Check size limit
                if (fileContent.length > MAX_FILE_SIZE) {
                    logger.warn { "File ${codeChange.filePath} too large (${fileContent.length} chars), skipping" }
                    return@withContext 0
                }

                // Parse with Tika (handles RTF, DOCX, etc.) via internal HTTP client
                val parseResult =
                    tikaClient.process(
                        TikaProcessRequest(
                            source =
                                TikaProcessRequest.Source.FileBytes(
                                    fileName = codeChange.filePath,
                                    dataBase64 = Base64.getEncoder().encodeToString(fileContent.toByteArray()),
                                ),
                            includeMetadata = true,
                        ),
                    )

                if (!parseResult.success) {
                    logger.warn { "Tika parsing failed for ${codeChange.filePath}: ${parseResult.errorMessage}" }
                    return@withContext 0
                }

                val extractedText = parseResult.plainText

                if (extractedText.isBlank()) {
                    logger.debug { "No text extracted from ${codeChange.filePath}" }
                    return@withContext 0
                }

                // Chunk extracted text
                val textChunks = chunkText(extractedText)
                var indexedChunks = 0

                for ((index, chunk) in textChunks.withIndex()) {
                    try {
                        val sourceId = "$commitHash:${codeChange.filePath}:text-chunk-$index"

                        // Generate TEXT embedding
                        val embedding = embeddingGateway.callEmbedding(ModelTypeEnum.EMBEDDING_TEXT, chunk)

                        // Validate embedding
                        if (embedding.isEmpty() || embedding.all { it == 0f }) {
                            throw IllegalStateException("Embedding returned empty/zero vector")
                        }

                        val ragDocument =
                            RagDocument(
                                projectId = project.id,
                                ragSourceType = RagSourceType.CODE_CHANGE,
                                text = "Document change in ${codeChange.filePath}",
                                clientId = project.clientId,
                                fileName = codeChange.filePath,
                                branch = branch,
                                chunkId = index,
                                from = "git-commit-tika",
                                timestamp =
                                    java.time.Instant
                                        .now()
                                        .toString(),
                            )

                        // Store in Qdrant TEXT collection
                        val vectorStoreId = vectorStorage.store(ModelTypeEnum.EMBEDDING_TEXT, ragDocument, embedding)

                        // Track in MongoDB
                        vectorStoreIndexService.trackIndexed(
                            projectId = project.id,
                            clientId = project.clientId,
                            branch = branch,
                            sourceType = RagSourceType.CODE_CHANGE,
                            sourceId = sourceId,
                            vectorStoreId = vectorStoreId,
                            vectorStoreName = "text-change-${commitHash.take(8)}-$index",
                            content = chunk,
                            filePath = codeChange.filePath,
                            symbolName = null,
                            commitHash = commitHash,
                        )

                        indexedChunks++
                    } catch (e: Exception) {
                        logger.warn(e) {
                            "Failed to embed text chunk $index of ${codeChange.filePath}: ${e.message}"
                        }
                    }
                }

                logger.debug { "Indexed $indexedChunks text chunks for ${codeChange.filePath}" }
                indexedChunks
            } catch (e: Exception) {
                logger.error(e) { "Failed to index text file ${codeChange.filePath}" }
                0
            }
        }

    /**
     * Chunk text into smaller pieces for embedding
     */
    private fun chunkText(text: String): List<String> = textChunkingService.splitText(text).map { it.text() }

    // ========== Mono-Repo Methods ==========

    /**
     * Index code changes from a specific commit for mono-repo.
     * Extracts diffs and creates CODE embeddings (no projectId).
     */
    suspend fun indexMonoRepoCommitCodeChanges(
        clientId: org.bson.types.ObjectId,
        monoRepoId: String,
        monoRepoPath: Path,
        commitHash: String,
        branch: String,
    ): CodeIndexingResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Indexing code changes for mono-repo commit ${commitHash.take(8)} in $monoRepoId" }

                // Extract code changes from commit
                val codeChanges = extractCodeChangesFromCommit(monoRepoPath, commitHash)

                if (codeChanges.isEmpty()) {
                    logger.debug { "No code changes found in mono-repo commit ${commitHash.take(8)}" }
                    return@withContext CodeIndexingResult(0, 0, 0)
                }

                var indexedFiles = 0
                var indexedChunks = 0
                var errorFiles = 0

                // Index each code change
                for (codeChange in codeChanges) {
                    try {
                        val chunksIndexed =
                            indexMonoRepoCodeChange(clientId, monoRepoId, commitHash, branch, codeChange)
                        indexedChunks += chunksIndexed
                        indexedFiles++
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to index mono-repo code change for ${codeChange.filePath}" }
                        errorFiles++
                    }
                }

                logger.info {
                    "Mono-repo code indexing completed for commit ${commitHash.take(8)}: " +
                        "files=$indexedFiles, chunks=$indexedChunks, errors=$errorFiles"
                }

                CodeIndexingResult(indexedFiles, indexedChunks, errorFiles)
            } catch (e: Exception) {
                logger.error(e) { "Error indexing mono-repo code changes for commit $commitHash" }
                CodeIndexingResult(0, 0, 1)
            }
        }

    /**
     * Index a single code change from mono-repo as CODE embeddings (projectId = null)
     */
    private suspend fun indexMonoRepoCodeChange(
        clientId: org.bson.types.ObjectId,
        monoRepoId: String,
        commitHash: String,
        branch: String,
        codeChange: CodeChange,
    ): Int {
        // Skip deleted files - no code to index
        if (codeChange.changeType == ChangeType.DELETED) {
            logger.debug { "Skipping deleted file: ${codeChange.filePath}" }
            return 0
        }

        // Create chunks from added code
        val codeChunks = createCodeChunks(codeChange)
        var indexedChunks = 0

        for ((index, chunk) in codeChunks.withIndex()) {
            val sourceId = "$commitHash:${codeChange.filePath}:chunk-$index"

            // Generate CODE embedding (not TEXT!)
            val embedding = embeddingGateway.callEmbedding(ModelTypeEnum.EMBEDDING_CODE, chunk)

            val ragDocument =
                RagDocument(
                    projectId = null, // No projectId for mono-repo
                    ragSourceType = RagSourceType.CODE_CHANGE,
                    text = "Code change in ${codeChange.filePath}",
                    clientId = clientId,
                    // Code-specific metadata
                    fileName = codeChange.filePath,
                    branch = branch,
                    chunkId = index,
                    // Content
                    from = "git-commit",
                    timestamp =
                        java.time.Instant
                            .now()
                            .toString(),
                )

            // Store in Qdrant CODE collection
            val vectorStoreId = vectorStorage.store(ModelTypeEnum.EMBEDDING_CODE, ragDocument, embedding)

            // Track in MongoDB with monoRepoId
            vectorStoreIndexService.trackIndexedForMonoRepo(
                clientId = clientId,
                monoRepoId = monoRepoId,
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

        logger.debug { "Indexed ${codeChunks.size} mono-repo code chunks for ${codeChange.filePath}" }
        return indexedChunks
    }

    // ========== Shared Methods ==========

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
     * Create code chunks from code change using TextChunkingService.
     */
    private fun createCodeChunks(codeChange: CodeChange): List<String> =
        textChunkingService.splitText(codeChange.addedLines.joinToString("\n")).map { it.text() }

    /**
     * Detect programming language from file path
     */

    /**
     * File type classification for embedding strategy
     */
    private enum class FileType {
        CODE, // Source code files → EMBEDDING_CODE
        TEXT, // Documents/text files → EMBEDDING_TEXT + Tika
        BINARY, // Binary files → Skip
    }

    /**
     * Classify file type based on extension
     */
    private fun classifyFileType(filePath: String): FileType {
        val extension = filePath.substringAfterLast('.', "").lowercase()

        // CODE: Source code extensions
        val codeExtensions =
            setOf(
                "kt",
                "kts",
                "java",
                "py",
                "js",
                "ts",
                "jsx",
                "tsx",
                "go",
                "rs",
                "cpp",
                "cc",
                "c",
                "h",
                "hpp",
                "rb",
                "php",
                "cs",
                "swift",
                "m",
                "mm",
                "scala",
                "clj",
                "hs",
                "ex",
                "exs",
                "erl",
                "sh",
                "bash",
                "zsh",
                "fish",
                "sql",
                "graphql",
                "proto",
                "yaml",
                "yml",
                "json",
                "xml",
                "toml",
                "ini",
                "properties",
                "gradle",
                "maven",
                "pom",
                "Dockerfile",
                "makefile",
            )

        // BINARY: Binary/media files to skip
        val binaryExtensions =
            setOf(
                // Documents (binary formats)
                "pdf",
                "doc",
                "docx",
                "xls",
                "xlsx",
                "ppt",
                "pptx",
                "odt",
                "ods",
                "odp",
                // Images
                "png",
                "jpg",
                "jpeg",
                "gif",
                "bmp",
                "svg",
                "ico",
                "webp",
                "tiff",
                "tif",
                "psd",
                "ai",
                "eps",
                // Audio/Video
                "mp3",
                "mp4",
                "avi",
                "mov",
                "wmv",
                "flv",
                "mkv",
                "wav",
                "flac",
                "aac",
                "ogg",
                "m4a",
                // Archives
                "zip",
                "tar",
                "gz",
                "bz2",
                "7z",
                "rar",
                "jar",
                "war",
                "ear",
                // Executables/Libraries
                "exe",
                "dll",
                "so",
                "dylib",
                "a",
                "o",
                "class",
                // Fonts
                "ttf",
                "otf",
                "woff",
                "woff2",
                "eot",
                // Other binary
                "bin",
                "dat",
                "db",
                "sqlite",
                "mdb",
            )

        return when {
            extension in codeExtensions -> FileType.CODE
            extension in binaryExtensions -> FileType.BINARY
            // Special cases: no extension but known patterns
            filePath.endsWith("Dockerfile") || filePath.endsWith("Makefile") -> FileType.CODE
            // Default: TEXT (will try Tika)
            else -> FileType.TEXT
        }
    }

    private fun detectLanguage(filePath: String): String =
        when {
            filePath.endsWith(".kt") || filePath.endsWith(".kts") -> "kotlin"
            filePath.endsWith(".java") -> "java"
            filePath.endsWith(".py") -> "python"
            filePath.endsWith(".js") || filePath.endsWith(".jsx") -> "javascript"
            filePath.endsWith(".ts") || filePath.endsWith(".tsx") -> "typescript"
            filePath.endsWith(".go") -> "go"
            filePath.endsWith(".rs") -> "rust"
            filePath.endsWith(".cpp") || filePath.endsWith(".cc") -> "cpp"
            filePath.endsWith(".c") -> "c"
            filePath.endsWith(".rb") -> "ruby"
            filePath.endsWith(".php") -> "php"
            filePath.endsWith(".cs") -> "csharp"
            filePath.endsWith(".swift") -> "swift"
            filePath.endsWith(".scala") -> "scala"
            filePath.endsWith(".sh") || filePath.endsWith(".bash") -> "bash"
            filePath.endsWith(".sql") -> "sql"
            filePath.endsWith(".yaml") || filePath.endsWith(".yml") -> "yaml"
            filePath.endsWith(".json") -> "json"
            filePath.endsWith(".xml") -> "xml"
            else -> "unknown"
        }
}
