package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.entity.mongo.RagIndexingStatusDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.ParsedResponse
import com.jervis.service.indexing.dto.FileAnalysisChunk
import com.jervis.service.rag.RagIndexingStatusService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension

/**
 * File-level LLM indexing service.
 * Processes each file once -> LLM returns [{ code, descriptionChunks[] }] -> embeds code and descriptions.
 * No extra metadata is used in payload beyond minimal fields (summary = code, path, gitCommitHash).
 */
@Service
class FileLevelIndexingService(
    private val llmGateway: LlmGateway,
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val ragIndexingStatusService: RagIndexingStatusService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun indexProjectFiles(
        project: ProjectDocument,
        projectPath: Path,
    ) = coroutineScope {
        val files = Files.walk(projectPath).filter { Files.isRegularFile(it) }.toList()
        logger.info { "FILE_INDEX: Found ${files.size} files to consider for indexing" }

        files
            .map { filePath ->
                async {
                    try {
                        indexSingleFile(project, filePath)
                    } catch (e: Exception) {
                        logger.warn(e) { "FILE_INDEX: Failed to index $filePath: ${e.message}" }
                    }
                }
            }.awaitAll()
    }

    private suspend fun indexSingleFile(
        project: ProjectDocument,
        filePath: Path,
    ) = coroutineScope {
        val contentBytes = Files.readAllBytes(filePath)
        val filePathStr =
            project.projectPath?.let { base ->
                // If repository stores projectPath, prefer relative
                runCatching { Path.of(base).relativize(filePath).toString() }.getOrElse { filePath.toString() }
            } ?: filePath.toString()
        val gitCommitHash = "current" // TODO: supply actual commit hash from context

        val shouldIndex =
            ragIndexingStatusService.shouldIndexFile(
                projectId = project.id,
                filePath = filePathStr,
                gitCommitHash = gitCommitHash,
                fileContent = contentBytes,
            )
        if (!shouldIndex) return@coroutineScope

        // Start indexing record
        ragIndexingStatusService.startIndexing(
            projectId = project.id,
            filePath = filePathStr,
            gitCommitHash = gitCommitHash,
            fileContent = contentBytes,
            language = detectLanguage(filePath),
        )

        // Delete old embeddings safely by ID in parallel with LLM call
        val deleteJob =
            async {
                ragIndexingStatusService.deleteOldEmbeddings(
                    projectId = project.id,
                    filePath = filePathStr,
                    gitCommitHash = gitCommitHash,
                )
            }

        val llm: ParsedResponse<List<FileAnalysisChunk>> =
            llmGateway.callLlm(
                type = PromptTypeEnum.COMPREHENSIVE_FILE_ANALYSIS,
                responseSchema = listOf(FileAnalysisChunk()),
                quick = false,
                mappingValue =
                    mapOf(
                        "filePath" to filePathStr,
                        "language" to (detectLanguage(filePath) ?: "unknown"),
                        "fileContent" to contentBytes.toString(Charsets.UTF_8),
                    ),
            )

        // Ensure deletion finished (best-effort)
        runCatching { deleteJob.await() }

        val vectorInfos = mutableListOf<RagIndexingStatusDocument.IndexedContentInfo>()

        // For each chunk, embed code and descriptions
        llm.result.forEach { chunk ->
            val code = chunk.code
            if (code.isNotBlank()) {
                val codeEmbedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_CODE, code)
                val codeVectorId =
                    vectorStorage.store(
                        collectionType = ModelType.EMBEDDING_CODE,
                        ragDocument =
                            RagDocument(
                                projectId = project.id,
                                clientId = project.clientId,
                                ragSourceType = RagSourceType.LLM,
                                summary = code, // summary = code per spec
                                gitCommitHash = gitCommitHash,
                            ),
                        embedding = codeEmbedding,
                    )
                vectorInfos +=
                    RagIndexingStatusDocument.IndexedContentInfo(
                        vectorStoreId = codeVectorId,
                        contentHash = sha256(code),
                        contentLength = code.length,
                        description = "file-level",
                    )
            }

            chunk.descriptionChunks.forEach { desc ->
                if (desc.isBlank()) return@forEach
                val textEmbedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, desc)
                val textVectorId =
                    vectorStorage.store(
                        collectionType = ModelType.EMBEDDING_TEXT,
                        ragDocument =
                            RagDocument(
                                projectId = project.id,
                                clientId = project.clientId,
                                ragSourceType = RagSourceType.LLM,
                                summary = code, // summary stays code: find code by description
                                gitCommitHash = gitCommitHash,
                            ),
                        embedding = textEmbedding,
                    )
                vectorInfos +=
                    RagIndexingStatusDocument.IndexedContentInfo(
                        vectorStoreId = textVectorId,
                        contentHash = sha256(desc),
                        contentLength = desc.length,
                        description = "file-level",
                    )
            }
        }

        // Complete indexing
        ragIndexingStatusService.completeIndexing(
            projectId = project.id,
            filePath = filePathStr,
            gitCommitHash = gitCommitHash,
            vectorStoreIds = vectorInfos,
        )
    }

    private fun detectLanguage(path: Path): String? =
        when (path.extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "rb" -> "ruby"
            "php" -> "php"
            "go" -> "go"
            "rs" -> "rust"
            "swift" -> "swift"
            "scala" -> "scala"
            "groovy" -> "groovy"
            "cpp", "cc", "cxx" -> "cpp"
            "c", "h", "hpp" -> "c"
            else -> null
        }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
