package com.jervis.service.indexer

import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.vectordb.VectorStorageService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.bson.types.ObjectId
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
    private val vectorStorageService: VectorStorageService,
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
    suspend fun indexProject(project: ProjectDocument) =
        coroutineScope {
            val projectPath = project.path
            val projectDir = File(projectPath)

            if (!projectDir.exists() || !projectDir.isDirectory) {
                logger.warn { "Project directory does not exist: $projectPath" }
                return@coroutineScope
            }

            logger.info { "Indexing project: ${project.name}" }

            // Collect files to process
            val filesToProcess = mutableListOf<Path>()

            // Walk through all files in the project
            Files.walk(Paths.get(projectPath)).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    val path = iterator.next()
                    if (Files.isRegularFile(path) && isRelevantFile(path)) {
                        filesToProcess.add(path)
                    }
                }
            }

            // Process files in parallel
            val fileJobs =
                filesToProcess.map { filePath ->
                    async {
                        try {
                            indexFile(project.id!!, projectDir.toPath(), filePath)
                        } catch (e: Exception) {
                            logger.error(e) { "Error indexing file $filePath: ${e.message}" }
                        }
                    }
                }

            // Wait for all file indexing jobs to complete
            fileJobs.awaitAll()

            logger.info { "Finished indexing project: ${project.name}" }
        }

    /**
     * Index a specific file
     *
     * @param projectId The ID of the project
     * @param projectRoot The root directory of the project
     * @param filePath The path to the file to index
     */
    /**
     * Index a specific file
     *
     * @param projectId The ID of the project
     * @param projectRoot The root directory of the project
     * @param filePath The path to the file to index
     */
    suspend fun indexFile(
        projectId: ObjectId,
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

        if (extension in codeExtensions) {
            // Existing code path for code chunks
            indexCodeFile(projectId, content)
        } else if (extension in textExtensions) {
            // Minimal text indexing with required basic payload
            val ragDocument = RagDocument(
                projectId = projectId,
                documentType = RagDocumentType.TEXT,
                ragSourceType = RagSourceType.FILE,
                pageContent = content,
                source = RagSourceType.FILE.name,
                language = extension,
                path = relativePath,
            )
            val embedding = embeddingService.generateEmbedding(ragDocument.pageContent)
            vectorStorageService.storeDocumentSuspend(ragDocument, embedding)
        }
    }

    /**
     * Index a code file by splitting it into logical chunks (classes, methods, etc.)
     *
     * @param projectId The ID of the project
     * @param content The content of the file
     */
    private suspend fun indexCodeFile(projectId: ObjectId, content: String) =
        coroutineScope {
            // Use the ChunkingService to split the content into meaningful chunks
            // based on the structure of the code
            val codeChunks = chunkingService.createCodeChunks(content)

            // Collect class-related information for generating summaries
            codeChunks.filter {
                it.type == "class" || it.type == "interface" || it.type == "object" || it.type == "enum class"
            }

            // Map to store methods and fields for each class
            val classMethods = mutableMapOf<String, MutableList<String>>()
            val classFields = mutableMapOf<String, MutableList<String>>()

            // Collect methods and fields for each class
            codeChunks.forEach { chunk ->
                if (chunk.type == "method" && chunk.parentName != null) {
                    classMethods.getOrPut(chunk.parentName) { mutableListOf() }.add(chunk.name)
                } else if (chunk.type == "field" && chunk.parentName != null) {
                    classFields.getOrPut(chunk.parentName) { mutableListOf() }.add(chunk.name)
                }
            }

            // Process chunks in parallel
            val chunkJobs =
                codeChunks.mapIndexed { index, chunk ->
                    async {
                        if (chunk.content.isNotBlank()) {
                            // Create a simple metadata map for the chunk
                            val chunkMetadata = mapOf(
                                "symbol" to chunk.name,
                                "chunkStart" to chunk.startLine.toString(),
                                "chunkEnd" to chunk.endLine.toString(),
                                "parentName" to (chunk.parentName ?: ""),
                                "className" to if (chunk.type == "class" ||
                                    chunk.type == "interface" ||
                                    chunk.type == "object" ||
                                    chunk.type == "enum class"
                                ) {
                                    chunk.name
                                } else {
                                    ""
                                }
                            )

                            // Create a document for the chunk
                            val ragDocument =
                                RagDocument(
                                    projectId = projectId,
                                    documentType = RagDocumentType.CLASS_SUMMARY,
                                    ragSourceType = RagSourceType.CLASS,
                                    pageContent = chunk.content,
                                    source = RagSourceType.CLASS.name
                                )

                            // Generate embeddings and store in vector database
                            val embedding = embeddingService.generateEmbedding(chunk.content)
                            vectorStorageService.storeDocumentSuspend(ragDocument, embedding)

                            // TODO: Class summary generation removed due to metadata dependency issues
                        }
                    }
                }

            // Wait for all chunk processing jobs to complete
            chunkJobs.awaitAll()
        }

    // TODO: indexTextFile method removed due to problematic TextMetadata dependency

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
     * Extract package name from code content
     *
     * @param content The code content
     * @return The package name or null if not found
     */
    private fun extractPackageName(content: String): String? {
        // Regular expressions for different languages
        val kotlinPackageRegex = Regex("^\\s*package\\s+([\\w.]+)")
        val javaPackageRegex = Regex("^\\s*package\\s+([\\w.]+);")

        // Check each line for package declaration
        val lines = content.lines()
        for (line in lines) {
            // Try Kotlin style
            val kotlinMatch = kotlinPackageRegex.find(line)
            if (kotlinMatch != null) {
                return kotlinMatch.groupValues[1]
            }

            // Try Java style
            val javaMatch = javaPackageRegex.find(line)
            if (javaMatch != null) {
                return javaMatch.groupValues[1]
            }
        }

        return null
    }

    /**
     * Index a file with commit information
     *
     * @param projectId The ID of the project
     * @param projectRoot The root directory of the project
     * @param filePath The path to the file to index
     * @param commitId The ID of the commit that last modified the file
     * @param commitTime The time of the commit
     */
    suspend fun indexFileWithCommitInfo(
        projectId: ObjectId,
        projectRoot: Path,
        filePath: Path,
        commitId: String,
        authorName: String,
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

        // Create tags with commit information
        val commitTags =
            listOf(
                "commit:$commitId",
                "author:$authorName",
            )

        // Process the file based on its type
        if (extension in codeExtensions) {
            // Index code file without metadata (metadata path removed as requested)
            indexCodeFile(projectId, content)
        } else if (extension in textExtensions) {
            // Index text file without metadata (metadata path removed as requested)
            // TODO: indexTextFile needs to be updated to work without metadata
            logger.info { "Text file indexing temporarily disabled due to metadata removal: $relativePath" }
        }

        logger.info { "Indexed file $relativePath with commit $commitId by $authorName" }
    }
}
