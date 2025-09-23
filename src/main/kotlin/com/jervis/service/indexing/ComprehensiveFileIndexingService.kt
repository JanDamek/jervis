package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.embedding.TextEmbeddingService
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.indexing.dto.ComprehensiveFileAnalysisResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString

/**
 * Service for creating comprehensive file descriptions using LLM analysis.
 * Indexes every source file with detailed textual descriptions for better RAG searchability.
 * This meets requirement: "projde přes všechny úrovně path a najde veškeré soubory v kterých je kód a projede nad tím indexaci celého souboru a udelá textový popis přes model"
 */
@Service
class ComprehensiveFileIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    data class FileIndexingResult(
        val processedFiles: Int,
        val skippedFiles: Int,
        val errorFiles: Int,
    )

    /**
     * Index all source files with comprehensive LLM-generated descriptions
     */
    suspend fun indexAllSourceFiles(
        project: ProjectDocument,
        projectPath: Path,
    ): FileIndexingResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Starting comprehensive file indexing for project: ${project.name}" }

                var processedFiles = 0
                val skippedFiles = 0
                var errorFiles = 0

                // Find all source code files
                Files
                    .walk(projectPath)
                    .filter { it.isRegularFile() }
                    .filter { isSourceCodeFile(it) }
                    .filter { shouldProcessFile(it, projectPath) }
                    .map { filePath ->
                        async {
                            try {
                                if (indexSourceFile(project, filePath, projectPath)) processedFiles++ else errorFiles++
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to index file: ${filePath.pathString}" }
                                errorFiles++
                            }
                        }
                    }

                val result = FileIndexingResult(processedFiles, skippedFiles, errorFiles)
                logger.info {
                    "Comprehensive file indexing completed for project: ${project.name} - " +
                        "Processed: $processedFiles, Skipped: $skippedFiles, Errors: $errorFiles"
                }

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during comprehensive file indexing for project: ${project.name}" }
                FileIndexingResult(0, 0, 1)
            }
        }

    /**
     * Index a single source file with comprehensive description
     */
    private suspend fun indexSourceFile(
        project: ProjectDocument,
        filePath: Path,
        projectPath: Path,
    ): Boolean {
        val relativePath = projectPath.relativize(filePath).pathString

        try {
            logger.debug { "Indexing source file: $relativePath" }

            // Check if file should be processed (path filtering)
            if (!shouldProcessFile(filePath, projectPath)) {
                logger.debug { "Skipping excluded file: $relativePath" }
                return false
            }

            // Check if file is a supported source code file
            if (!isSourceCodeFile(filePath)) {
                logger.debug { "Skipping non-source file: $relativePath" }
                return false
            }

            // Check if file is binary
            if (isBinaryFile(filePath)) {
                logger.debug { "Skipping binary file: $relativePath" }
                return false
            }

            val fileContent = Files.readString(filePath)
            if (fileContent.isBlank()) {
                logger.debug { "Skipping empty file: $relativePath" }
                return false
            }

            // Check file size to avoid processing extremely large files
            if (fileContent.length > 1_000_000) { // 1MB limit
                logger.debug { "Skipping large file (${fileContent.length} chars): $relativePath" }
                return false
            }

            // Generate comprehensive file analysis as sentence array using LLM
            val sentences = generateFileSentences(filePath, fileContent, projectPath, project)

            // Create individual embeddings for each sentence with proper metadata
            val embeddingResult =
                processSentenceEmbeddings(
                    project = project,
                    sentences = sentences,
                    filePath = filePath,
                    projectPath = projectPath,
                )

            logger.debug {
                "Created ${embeddingResult.processedSentences} sentence embeddings " +
                    "for file: $relativePath"
            }

            logger.debug { "Successfully indexed source file: $relativePath" }
            return true
        } catch (e: IllegalArgumentException) {
            logger.error { "Configuration error while indexing file $relativePath: ${e.message}" }
            throw e // Re-throw configuration errors as they need to be fixed
        } catch (e: java.nio.file.NoSuchFileException) {
            logger.warn { "File not found during indexing: $relativePath" }
            return false
        } catch (e: java.nio.file.AccessDeniedException) {
            logger.warn { "Access denied while reading file: $relativePath" }
            return false
        } catch (e: java.nio.charset.MalformedInputException) {
            logger.warn { "File contains invalid characters, skipping: $relativePath" }
            return false
        } catch (e: OutOfMemoryError) {
            logger.error { "Out of memory while processing file: $relativePath" }
            return false
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error while indexing file $relativePath: ${e.message}" }
            return false
        }
    }

    /**
     * Generate short, independently searchable sentences for a source file using LLM
     */
    private suspend fun generateFileSentences(
        filePath: Path,
        fileContent: String,
        projectPath: Path,
        project: ProjectDocument,
    ): List<String> {
        val userPrompt =
            buildString {
                appendLine("Analyze this source code file:")
                appendLine()
                appendLine("File: ${filePath.name}")
                appendLine("Path: ${projectPath.relativize(filePath)}")
                appendLine("Size: ${fileContent.length} characters")
                appendLine()
                appendLine("Source Code:")
                appendLine("```")
                appendLine(fileContent)
                appendLine("```")
            }

        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.COMPREHENSIVE_FILE_ANALYSIS,
                userPrompt = userPrompt,
                quick = false,
                responseSchema = ComprehensiveFileAnalysisResponse(emptyList()),
            )

        // Extract sentences from the typed response
        return llmResponse.sentences
    }

    /**
     * Process individual sentence embeddings with proper metadata
     */
    private suspend fun processSentenceEmbeddings(
        project: ProjectDocument,
        sentences: List<String>,
        filePath: Path,
        projectPath: Path,
    ): TextEmbeddingService.TextEmbeddingResult {
        var processedSentences = 0
        var skippedSentences = 0
        var errorSentences = 0

        val relativePath = projectPath.relativize(filePath).pathString

        for ((index, sentence) in sentences.withIndex()) {
            try {
                if (sentence.trim().length >= 10) { // Skip very short sentences
                    // Generate text embedding for the sentence
                    val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, sentence.trim())

                    // Create RAG document with proper metadata for each sentence
                    val ragDocument =
                        RagDocument(
                            projectId = project.id,
                            clientId = project.clientId,
                            documentType = RagDocumentType.TEXT,
                            ragSourceType = RagSourceType.FILE,
                            pageContent = sentence.trim(),
                            source = "file://${filePath.pathString}#sentence-$index",
                            path = relativePath,
                            module = filePath.fileName.toString(),
                            chunkId = "sentence-$index",
                        )

                    // Store in TEXT vector store
                    vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
                    processedSentences++
                } else {
                    skippedSentences++
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to process sentence from ${filePath.fileName}: $sentence" }
                errorSentences++
            }
        }

        logger.debug {
            "Sentence processing completed for ${filePath.fileName}: " +
                "processed=$processedSentences, skipped=$skippedSentences, errors=$errorSentences"
        }

        return TextEmbeddingService.TextEmbeddingResult(processedSentences, skippedSentences, errorSentences)
    }

    /**
     * Check if the file is a source code file that should be indexed
     */
    private fun isSourceCodeFile(filePath: Path): Boolean {
        val extension = filePath.extension.lowercase()
        val supportedExtensions =
            setOf(
                "kt",
                "java",
                "js",
                "ts",
                "py",
                "rb",
                "go",
                "rs",
                "cpp",
                "c",
                "h",
                "hpp",
                "cs",
                "php",
                "swift",
                "scala",
                "clj",
                "ml",
                "hs",
                "elm",
                "dart",
                "vue",
                "jsx",
                "tsx",
                "mjs",
                "cjs",
                "yaml",
                "yml",
                "json",
                "xml",
                "sql",
                "gradle",
                "properties",
                "conf",
                "config",
                "toml",
                "ini",
                "sh",
                "bash",
                "zsh",
                "fish",
            )
        return extension in supportedExtensions
    }

    /**
     * Check if the specific file should be processed (exclude certain paths)
     */
    private fun shouldProcessFile(
        filePath: Path,
        projectPath: Path,
    ): Boolean {
        val relativePath = projectPath.relativize(filePath).pathString.replace('\\', '/')

        // Exclude common build/dependency directories and third-party libraries
        val excludePatterns =
            listOf(
                // Build and output directories
                "target/",
                "build/",
                "bin/",
                "out/",
                "dist/",
                "release/",
                "debug/",
                // Dependency directories
                "node_modules/",
                "vendor/",
                "packages/",
                ".bundle/",
                "bower_components/",
                // Version control
                ".git/",
                ".svn/",
                ".hg/",
                ".bzr/",
                // IDE and editor files
                ".gradle/",
                ".idea/",
                ".vscode/",
                ".settings/",
                ".eclipse/",
                ".metadata/",
                "*.iml",
                "*.ipr",
                "*.iws",
                // Temporary directories
                "logs/",
                "tmp/",
                "temp/",
                ".tmp/",
                // Test coverage and reports
                "coverage/",
                ".nyc_output/",
                "htmlcov/",
                "test-results/",
                "reports/",
                // Language-specific directories
                "*.egg-info/",
                ".tox/",
                ".venv/",
                "venv/",
                ".env/",
                "env/",
                ".cargo/",
                "Cargo.lock",
                "go.sum",
                ".stack-work/",
                ".cabal-sandbox/",
                "cabal.sandbox.config",
                ".nuget/",
                "packages.config",
                ".gems/",
                "Gemfile.lock",
                ".next/",
                ".nuxt/",
                "elm-stuff/",
                // OS-specific files
                ".DS_Store",
                "Thumbs.db",
                "desktop.ini",
                "*.swp",
                "*.swo",
                "*~",
            )

        return excludePatterns.none { pattern ->
            when {
                pattern.endsWith("/") -> relativePath.contains(pattern)
                pattern.contains("*") -> {
                    val regex = pattern.replace("*", ".*").toRegex()
                    regex.containsMatchIn(relativePath)
                }

                else -> relativePath.contains(pattern) || relativePath.endsWith("/$pattern")
            }
        }
    }

    /**
     * Check if file is a binary file that should not be indexed
     */
    private fun isBinaryFile(filePath: Path): Boolean {
        val extension = filePath.extension.lowercase()

        // Known binary file extensions
        val binaryExtensions =
            setOf(
                // Images
                "jpg",
                "jpeg",
                "png",
                "gif",
                "bmp",
                "tiff",
                "tif",
                "webp",
                "ico",
                "svg",
                // Videos
                "mp4",
                "avi",
                "mkv",
                "mov",
                "wmv",
                "flv",
                "webm",
                "m4v",
                "3gp",
                // Audio
                "mp3",
                "wav",
                "flac",
                "aac",
                "ogg",
                "wma",
                "m4a",
                // Archives and compressed files
                "zip",
                "rar",
                "tar",
                "gz",
                "bz2",
                "xz",
                "7z",
                "dmg",
                "iso",
                // Executables and libraries
                "exe",
                "dll",
                "so",
                "dylib",
                "lib",
                "a",
                "jar",
                "war",
                "ear",
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
                // Fonts
                "ttf",
                "otf",
                "woff",
                "woff2",
                "eot",
                // Other binary formats
                "bin",
                "dat",
                "db",
                "sqlite",
                "sqlite3",
                "class",
                "pyc",
                "pyo",
                "elc",
            )

        if (extension in binaryExtensions) {
            return true
        }

        return if (!filePath.isRegularFile() || Files.size(filePath) == 0L) {
            false
        } else {
            val bytes = Files.readAllBytes(filePath).take(512).toByteArray()
            detectBinaryContent(bytes)
        }
    }

    /**
     * Detect if content appears to be binary based on byte analysis
     */
    private fun detectBinaryContent(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false

        var nullBytes = 0
        var controlChars = 0
        val totalBytes = bytes.size

        for (byte in bytes) {
            when (byte.toInt()) {
                0 -> nullBytes++
                in 1..8, in 14..31 -> controlChars++
            }
        }

        // If more than 1% null bytes or more than 30% control characters, likely binary
        val nullRatio = nullBytes.toDouble() / totalBytes
        val controlRatio = controlChars.toDouble() / totalBytes

        return nullRatio > 0.01 || controlRatio > 0.30
    }

    /**
     * Detect programming language from file extension
     */
    private fun detectLanguage(filePath: Path): String =
        when (filePath.extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "py" -> "python"
            "rb" -> "ruby"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "h", "hpp" -> "c-header"
            "cs" -> "csharp"
            "php" -> "php"
            "swift" -> "swift"
            "scala" -> "scala"
            "clj", "cljs" -> "clojure"
            "ml", "mli" -> "ocaml"
            "hs" -> "haskell"
            "elm" -> "elm"
            "dart" -> "dart"
            "vue" -> "vue"
            "jsx" -> "jsx"
            "tsx" -> "tsx"
            "yaml", "yml" -> "yaml"
            "json" -> "json"
            "xml" -> "xml"
            "sql" -> "sql"
            "gradle" -> "gradle"
            "properties" -> "properties"
            "conf", "config" -> "config"
            "toml" -> "toml"
            "ini" -> "ini"
            "sh", "bash" -> "bash"
            "zsh" -> "zsh"
            "fish" -> "fish"
            else -> "text"
        }

    /**
     * Extract module name from file path
     */
    private fun extractModuleName(relativePath: String): String {
        val pathParts = relativePath.replace('\\', '/').split('/')

        // For Java/Kotlin projects, try to extract meaningful module name
        return when {
            pathParts.size > 2 && pathParts[0] == "src" -> {
                // Standard Maven/Gradle structure: src/main/kotlin/com/example/...
                if (pathParts.size > 4 && (pathParts[1] == "main" || pathParts[1] == "test")) {
                    pathParts.drop(3).dropLast(1).joinToString(".")
                } else {
                    pathParts.dropLast(1).joinToString(".")
                }
            }

            pathParts.size > 1 -> pathParts.dropLast(1).joinToString(".")
            else -> "root"
        }
    }
}
