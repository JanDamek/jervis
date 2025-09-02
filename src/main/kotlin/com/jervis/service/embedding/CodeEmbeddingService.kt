package com.jervis.service.embedding

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Service dedicated to code embedding operations.
 * Handles code file processing and embedding generation using code models.
 */
@Service
class CodeEmbeddingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of code embedding operation
     */
    data class CodeEmbeddingResult(
        val processedFiles: Int,
        val skippedFiles: Int,
        val errorFiles: Int,
    )

    /**
     * Process code file and generate embedding
     */
    suspend fun processCodeFile(
        project: ProjectDocument,
        content: String,
        filePath: Path,
        projectPath: Path,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                logger.debug { "Processing code file: ${filePath.fileName}" }

                if (content.isBlank()) {
                    logger.debug { "Skipping empty file: ${filePath.fileName}" }
                    return@withContext false
                }

                // Generate code embedding for the entire file content
                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_CODE, content)

                // Create RAG document for code content
                val ragDocument =
                    RagDocument(
                        projectId = project.id!!,
                        documentType = RagDocumentType.CODE,
                        ragSourceType = RagSourceType.FILE,
                        pageContent = content,
                        source = filePath.pathString,
                        path = projectPath.relativize(filePath).pathString,
                        module = filePath.fileName.toString(),
                    )

                // Store in CODE vector store
                vectorStorage.store(ModelType.EMBEDDING_CODE, ragDocument, embedding)

                logger.debug { "Successfully processed code file: ${filePath.fileName}" }
                return@withContext true
            } catch (e: Exception) {
                logger.warn(e) { "Failed to process code file: ${filePath.fileName}" }
                return@withContext false
            }
        }

    /**
     * Process multiple code files in parallel
     */
    suspend fun processCodeFiles(
        project: ProjectDocument,
        codeContents: Map<Path, String>,
        projectPath: Path,
    ): CodeEmbeddingResult =
        withContext(Dispatchers.Default) {
            logger.info { "Processing ${codeContents.size} code files for project: ${project.name}" }

            val jobs =
                codeContents.map { (filePath, content) ->
                    async {
                        processCodeFile(project, content, filePath, projectPath)
                    }
                }

            val results = jobs.awaitAll()
            val processedFiles = results.count { it }
            val errorFiles = results.count { !it }
            val skippedFiles = 0 // Currently no skipping logic in code processing

            logger.info {
                "Code processing batch completed for project ${project.name}: " +
                    "processed=$processedFiles, errors=$errorFiles"
            }

            CodeEmbeddingResult(processedFiles, skippedFiles, errorFiles)
        }

    /**
     * Generate embedding for a single code snippet
     */
    suspend fun generateCodeEmbedding(code: String): List<Float> = embeddingGateway.callEmbedding(ModelType.EMBEDDING_CODE, code)

    /**
     * Check if file should be processed as code
     */
    fun isCodeFile(filePath: Path): Boolean {
        val fileName = filePath.fileName.toString().lowercase()
        val codeExtensions =
            setOf(
                // Java/JVM languages
                "java",
                "kt",
                "kts",
                "scala",
                "groovy",
                "clj",
                "cljs",
                // Web technologies
                "js",
                "ts",
                "jsx",
                "tsx",
                "vue",
                "svelte",
                "html",
                "htm",
                "css",
                "scss",
                "sass",
                "less",
                // Python
                "py",
                "pyx",
                "pyi",
                // C/C++
                "c",
                "cpp",
                "cxx",
                "cc",
                "h",
                "hpp",
                "hxx",
                // C#/.NET
                "cs",
                "vb",
                "fs",
                // Other languages
                "go",
                "rs",
                "php",
                "rb",
                "swift",
                "dart",
                "sql",
                "r",
                "m",
                "mm",
                // Scripts and configs that contain code logic
                "sh",
                "bash",
                "zsh",
                "ps1",
                "bat",
                "cmd",
                "py",
                "pl",
                "awk",
                "sed",
                // Build and config files with code-like content
                "gradle",
                "maven",
                "ant",
                "make",
                "cmake",
                "dockerfile",
                "docker",
                "vagrantfile",
                "jenkinsfile",
                "pipeline",
                // Infrastructure as code
                "tf",
                "tfvars",
                "hcl",
                "yaml",
                "yml",
            )

        return codeExtensions.any { fileName.endsWith(".$it") } ||
            fileName.contains("dockerfile") ||
            fileName.contains("makefile") ||
            fileName.contains("rakefile") ||
            fileName.contains("gemfile")
    }

    /**
     * Check if file content appears to be code based on patterns
     */
    fun looksLikeCode(content: String): Boolean {
        val codeIndicators =
            listOf(
                // Common programming constructs
                Regex("\\b(function|class|interface|import|export)\\b"),
                Regex("\\b(def|let|var|const|public|private)\\b"),
                Regex("\\b(if|else|for|while|switch|case)\\b"),
                Regex("\\b(return|throw|catch|try|finally)\\b"),
                // Common code patterns
                Regex("[{}();]"),
                Regex("=\\s*[\"']|[\"']\\s*[+]"),
                Regex("//|/\\*|\\*/|#\\s*[a-zA-Z]"),
                // Package/import statements
                Regex("^\\s*(package|import|from|using|require)\\s+", RegexOption.MULTILINE),
            )

        val indicatorCount = codeIndicators.count { it.containsMatchIn(content) }
        val totalLines = content.lines().size
        val nonEmptyLines = content.lines().count { it.trim().isNotEmpty() }

        // Consider it code if:
        // - It has multiple code indicators
        // - Or it has at least one strong indicator and reasonable line density
        return indicatorCount >= 2 || (indicatorCount >= 1 && nonEmptyLines > totalLines * 0.5)
    }
}
