package com.jervis.module.indexer

import com.jervis.entity.Project
import com.jervis.module.vectordb.VectorDbService
import com.jervis.rag.Document
import com.jervis.rag.DocumentType
import com.jervis.rag.RagMetadata
import com.jervis.rag.SourceType
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Service for indexing code and text files.
 * This service extracts structure and creates embeddings for code and text files.
 */
@Service
class IndexerService(
    private val vectorDbService: VectorDbService,
    private val embeddingService: EmbeddingService,
    private val chunkingService: ChunkingService,
) {
    private val logger = KotlinLogging.logger {}

    // File extensions to process
    private val codeExtensions =
        setOf(
            // JVM languages
            "kt",
            "java",
            "groovy",
            "scala",
            "clj",
            // Web languages
            "js",
            "ts",
            "jsx",
            "tsx",
            "html",
            "css",
            "scss",
            "sass",
            "less",
            "vue",
            "svelte",
            // Scripting languages
            "py",
            "rb",
            "php",
            "sh",
            "bash",
            "zsh",
            "ps1",
            "bat",
            "cmd",
            // Systems languages
            "c",
            "cpp",
            "h",
            "hpp",
            "cs",
            "go",
            "rs",
            "swift",
            // Data formats
            "json",
            "yaml",
            "yml",
            "xml",
            "toml",
            "ini",
            "properties",
            "sql",
        )

    private val textExtensions =
        setOf(
            "md",
            "txt",
            "rst",
            "adoc",
            "tex",
            "rtf",
            "csv",
            "log",
        )

    // Directories to ignore
    private val ignoredDirs =
        setOf(
            ".git",
            "build",
            "target",
            "out",
            "dist",
            "node_modules",
            ".idea",
            ".gradle",
            ".vscode",
            "bin",
            "obj",
            "venv",
            "env",
            "__pycache__",
        )

    /**
     * Index all files in a project
     *
     * @param project The project to index
     */
    fun indexProject(project: Project) {
        val projectPath = project.path
        val projectDir = File(projectPath)

        if (!projectDir.exists() || !projectDir.isDirectory) {
            logger.warn { "Project directory does not exist: $projectPath" }
            return
        }

        logger.info { "Indexing project: ${project.name}" }

        // Walk through all files in the project
        Files
            .walk(Paths.get(projectPath))
            .filter { Files.isRegularFile(it) }
            .filter { isRelevantFile(it) }
            .forEach { filePath ->
                try {
                    indexFile(project.id ?: 0, projectDir.toPath(), filePath)
                } catch (e: Exception) {
                    logger.error(e) { "Error indexing file $filePath: ${e.message}" }
                }
            }

        logger.info { "Finished indexing project: ${project.name}" }
    }

    /**
     * Index a specific file
     *
     * @param projectId The ID of the project
     * @param projectRoot The root directory of the project
     * @param filePath The path to the file to index
     */
    fun indexFile(
        projectId: Long,
        projectRoot: Path,
        filePath: Path,
    ) {
        val relativePath = projectRoot.relativize(filePath).toString()
        val content = String(Files.readAllBytes(filePath), StandardCharsets.UTF_8)
        val extension = filePath.toString().substringAfterLast('.', "")

        // Skip empty files
        if (content.isBlank()) {
            return
        }

        // Create metadata for the document
        val documentType = if (extension in codeExtensions) DocumentType.CODE else DocumentType.TEXT
        val metadata =
            RagMetadata(
                type = documentType,
                project = projectId.toInt(),
                source = "FILE",
                sourceId = SourceType.FILE,
                filePath = relativePath,
                language = extension,
            )

        // Process the file based on its type
        if (extension in codeExtensions) {
            indexCodeFile(content, metadata)
        } else if (extension in textExtensions) {
            indexTextFile(content, metadata)
        }
    }

    /**
     * Index a code file by splitting it into logical chunks (classes, methods, etc.)
     *
     * @param content The content of the file
     * @param metadata The metadata for the file
     */
    private fun indexCodeFile(
        content: String,
        metadata: RagMetadata,
    ) {
        // Use the ChunkingService to split the content into meaningful chunks
        // based on the structure of the code
        val codeChunks = chunkingService.createCodeChunks(content, metadata.language ?: "")

        codeChunks.forEachIndexed { index, chunk ->
            if (chunk.content.isNotBlank()) {
                val chunkMetadata =
                    metadata.copy(
                        chunkIndex = index,
                        extra = metadata.extra + mapOf(
                            "chunk_type" to chunk.type,
                            "chunk_name" to chunk.name,
                            "start_line" to chunk.startLine,
                            "end_line" to chunk.endLine,
                            "parent_name" to (chunk.parentName ?: "")
                        ),
                    )

                // Create a document for the chunk
                val document =
                    Document(
                        pageContent = chunk.content,
                        metadata = chunkMetadata,
                    )

                // Generate embeddings and store in vector database
                val embedding = embeddingService.generateEmbedding(chunk.content)
                vectorDbService.storeDocument(document, embedding)
            }
        }
    }

    /**
     * Index a text file by splitting it into paragraphs
     *
     * @param content The content of the file
     * @param metadata The metadata for the file
     */
    private fun indexTextFile(
        content: String,
        metadata: RagMetadata,
    ) {
        // Use the ChunkingService to split the content into meaningful chunks
        // based on the structure of the text
        val textChunks = chunkingService.createTextChunks(content, metadata.language ?: "")

        textChunks.forEachIndexed { index, chunk ->
            if (chunk.content.isNotBlank()) {
                val chunkMetadata =
                    metadata.copy(
                        chunkIndex = index,
                        extra = metadata.extra + mapOf(
                            "chunk_type" to chunk.type,
                            "heading" to chunk.heading,
                            "level" to chunk.level,
                            "start_line" to chunk.startLine,
                            "end_line" to chunk.endLine
                        ),
                    )

                // Create a document for the chunk
                val document =
                    Document(
                        pageContent = chunk.content,
                        metadata = chunkMetadata,
                    )

                // Generate embeddings and store in vector database
                val embedding = embeddingService.generateEmbedding(chunk.content)
                vectorDbService.storeDocument(document, embedding)
            }
        }
    }

    /**
     * Check if a file should be indexed
     *
     * @param path The path to the file
     * @return True if the file should be indexed, false otherwise
     */
    fun isRelevantFile(path: Path): Boolean {
        val pathString = path.toString()
        val extension = pathString.substringAfterLast('.', "")

        // Check if the file has a relevant extension
        if (extension !in codeExtensions && extension !in textExtensions) {
            return false
        }

        // Check if the file is in an ignored directory
        for (ignoredDir in ignoredDirs) {
            if (pathString.contains("/$ignoredDir/")) {
                return false
            }
        }

        return true
    }

    /**
     * Index a file with commit information
     *
     * @param projectId The ID of the project
     * @param projectRoot The root directory of the project
     * @param filePath The path to the file to index
     * @param commitId The ID of the commit that last modified the file
     * @param author The author of the commit
     * @param commitTime The time of the commit
     */
    fun indexFileWithCommitInfo(
        projectId: Long,
        projectRoot: Path,
        filePath: Path,
        commitId: String,
        author: String,
        commitTime: Instant,
    ) {
        val relativePath = projectRoot.relativize(filePath).toString()
        val content = String(Files.readAllBytes(filePath), StandardCharsets.UTF_8)
        val extension = filePath.toString().substringAfterLast('.', "")

        // Skip empty files
        if (content.isBlank()) {
            return
        }

        // Convert Instant to LocalDateTime
        val commitDateTime = LocalDateTime.ofInstant(commitTime, ZoneId.systemDefault())

        // Create metadata for the document with commit information
        val documentType = if (extension in codeExtensions) DocumentType.CODE else DocumentType.TEXT
        val metadata =
            RagMetadata(
                type = documentType,
                project = projectId.toInt(),
                source = "FILE",
                sourceId = SourceType.FILE,
                filePath = relativePath,
                language = extension,
                timestamp = commitDateTime,
                createdBy = author,
                extra =
                    mapOf(
                        "commit_id" to commitId,
                        "commit_time" to commitTime.toString(),
                        "commit_author" to author,
                    ),
            )

        // Process the file based on its type
        if (extension in codeExtensions) {
            indexCodeFile(content, metadata)
        } else if (extension in textExtensions) {
            indexTextFile(content, metadata)
        }

        logger.info { "Indexed file $relativePath with commit $commitId by $author" }
    }

}
