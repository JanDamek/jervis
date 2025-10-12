package com.jervis.service.indexing.pipeline

import com.jervis.domain.model.ModelType
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.nio.file.Path

/**
 * Data classes for pipeline-based streaming processing.
 * These classes support the flow of data through the indexing pipeline stages.
 */

enum class JoernSymbolType {
    CLASS,
    METHOD,
    FUNCTION,
    VARIABLE,
    CALL,
    IMPORT,
    FIELD,
    PARAMETER,
    FILE,
    PACKAGE,
    MODULE,
}

/**
 * Joern symbol representation for pipeline processing (unified with JoernStructuredIndexingService)
 */
@Serializable
data class JoernSymbol(
    val type: JoernSymbolType,
    val name: String,
    val fullName: String,
    val signature: String? = null,
    var filePath: String,
    val lineStart: Int,
    val lineEnd: Int,
    val nodeId: String,
    val parentClass: String? = null,
    var language: String? = null,
    // not from joern JSON, bud added by code
    var code: String? = null,
)

/**
 * Pipeline item representing a Joern analysis result
 */
data class JoernAnalysisItem(
    val filePath: Path,
    val symbol: JoernSymbol,
    val projectId: ObjectId,
    val workerId: Int,
    val timestamp: Long,
)

/**
 * Pipeline task for code embedding processing
 */
data class CodeEmbeddingTask(
    val analysisItem: JoernAnalysisItem,
    val content: String,
)

/**
 * Pipeline task for text embedding processing
 */
data class TextEmbeddingTask(
    val analysisItem: JoernAnalysisItem,
    val content: String,
)

/**
 * Pipeline task for class analysis processing
 */
data class ClassAnalysisTask(
    val analysisItem: JoernAnalysisItem,
    val classSymbol: JoernSymbol,
)

/**
 * Pipeline item representing processed embedding ready for storage
 */
data class EmbeddingPipelineItem(
    val analysisItem: JoernAnalysisItem,
    val content: String,
    val embedding: List<Float>,
    val embeddingType: ModelType,
    val processingTimeMs: Long,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
)

/**
 * Pipeline item representing storage operation result
 */
data class StoragePipelineItem(
    val analysisItem: JoernAnalysisItem,
    val success: Boolean,
    val error: String? = null,
    val workerId: Int,
    val processingTimeMs: Long = 0,
)

/**
 * Result of a pipeline indexing operation
 */
data class IndexingPipelineResult(
    val totalProcessed: Int,
    val totalErrors: Int,
    val processingTimeMs: Long,
    val throughput: Double, // items/second
    val errorMessage: String? = null,
)

/**
 * Wrapper for pipeline operations with error handling
 */
sealed class PipelineResult<out T> {
    data class Success<T>(
        val value: T,
    ) : PipelineResult<T>()
}
