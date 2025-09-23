package com.jervis.service.indexing.pipeline

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.LineRange
import com.jervis.domain.rag.SymbolRelation
import org.bson.types.ObjectId
import java.nio.file.Path

/**
 * Data classes for pipeline-based streaming processing.
 * These classes support the flow of data through the indexing pipeline stages.
 */

/**
 * Types of Joern symbols that can be processed (unified with JoernChunkingService)
 */
enum class JoernSymbolType {
    NAMESPACE,
    CLASS,
    METHOD,
    FUNCTION
}

/**
 * Joern symbol representation for pipeline processing (unified with JoernChunkingService)
 */
data class JoernSymbol(
    val type: JoernSymbolType,
    val name: String,
    val fullName: String,
    val signature: String? = null,
    val filePath: String,
    val lineRange: LineRange,
    val code: String? = null,
    val joernNodeId: String,
    val language: String,
    val relations: List<SymbolRelation> = emptyList(),
    val parentClass: String? = null,
    val namespace: String? = null,
    val packageName: String? = null  // Keep this for backward compatibility
)

/**
 * Pipeline item representing a Joern analysis result
 */
data class JoernAnalysisItem(
    val filePath: Path,
    val symbol: JoernSymbol,
    val projectId: ObjectId,
    val workerId: Int,
    val timestamp: Long
)

/**
 * Pipeline task for code embedding processing
 */
data class CodeEmbeddingTask(
    val analysisItem: JoernAnalysisItem,
    val content: String
)

/**
 * Pipeline task for text embedding processing
 */
data class TextEmbeddingTask(
    val analysisItem: JoernAnalysisItem,
    val content: String
)

/**
 * Pipeline task for class analysis processing
 */
data class ClassAnalysisTask(
    val analysisItem: JoernAnalysisItem,
    val classSymbol: JoernSymbol
)

/**
 * Pipeline item representing processed embedding ready for storage
 */
data class EmbeddingPipelineItem(
    val analysisItem: JoernAnalysisItem,
    val content: String,
    val embedding: List<Float>,
    val embeddingType: ModelType,
    val processingTimeMs: Long
)

/**
 * Pipeline item representing storage operation result
 */
data class StoragePipelineItem(
    val analysisItem: JoernAnalysisItem,
    val success: Boolean,
    val error: String? = null,
    val workerId: Int,
    val processingTimeMs: Long = 0
)

/**
 * Result of pipeline indexing operation
 */
data class IndexingPipelineResult(
    val totalProcessed: Int,
    val totalErrors: Int,
    val processingTimeMs: Long,
    val throughput: Double, // items/second
    val errorMessage: String? = null
)

/**
 * Pipeline configuration settings
 */
data class PipelineConfig(
    val channelBufferSize: Int = 100,
    val producerConcurrency: Int = 2,
    val consumerConcurrency: Int = 4,
    val batchSizeCode: Int = 30,
    val batchSizeText: Int = 40,
    val enableRealTimeProgress: Boolean = true,
    val fallbackToBatch: Boolean = true
)

/**
 * Real-time progress tracking for pipeline operations
 */
data class PipelineProgress(
    val stage: String,
    val processed: Int,
    val total: Int,
    val currentItem: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val throughputPerSecond: Double = 0.0
)

/**
 * Pipeline statistics for monitoring
 */
data class PipelineStatistics(
    val totalFiles: Int = 0,
    val processedFiles: Int = 0,
    val totalSymbols: Int = 0,
    val processedSymbols: Int = 0,
    val totalEmbeddings: Int = 0,
    val processedEmbeddings: Int = 0,
    val totalStorageItems: Int = 0,
    val processedStorageItems: Int = 0,
    val errors: MutableList<String> = mutableListOf(),
    val startTime: Long = System.currentTimeMillis(),
    val stages: MutableMap<String, StageStatistics> = mutableMapOf()
) {
    fun addError(error: String) {
        errors.add("${System.currentTimeMillis()}: $error")
    }
    
    fun getOverallThroughput(): Double {
        val totalTime = System.currentTimeMillis() - startTime
        return if (totalTime > 0) {
            (processedFiles + processedSymbols + processedEmbeddings + processedStorageItems).toDouble() / 
            (totalTime / 1000.0)
        } else 0.0
    }
}

/**
 * Statistics for individual pipeline stages
 */
data class StageStatistics(
    val stageName: String,
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long? = null,
    var processed: Int = 0,
    var errors: Int = 0,
    val workers: MutableMap<Int, WorkerStatistics> = mutableMapOf()
) {
    fun getDuration(): Long {
        return (endTime ?: System.currentTimeMillis()) - startTime
    }
    
    fun getThroughput(): Double {
        val duration = getDuration()
        return if (duration > 0) {
            (processed.toDouble() / (duration / 1000.0))
        } else 0.0
    }
    
    fun complete() {
        endTime = System.currentTimeMillis()
    }
}

/**
 * Statistics for individual workers within pipeline stages
 */
data class WorkerStatistics(
    val workerId: Int,
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long? = null,
    var processed: Int = 0,
    var errors: Int = 0,
    var currentItem: String? = null
) {
    fun getDuration(): Long {
        return (endTime ?: System.currentTimeMillis()) - startTime
    }
    
    fun complete() {
        endTime = System.currentTimeMillis()
        currentItem = null
    }
}

/**
 * Exception thrown during pipeline processing
 */
class PipelineProcessingException(
    message: String,
    val stage: String,
    val item: String? = null,
    val statistics: PipelineStatistics? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Wrapper for pipeline operations with error handling
 */
sealed class PipelineResult<out T> {
    data class Success<T>(val value: T) : PipelineResult<T>()
    data class Failure(val error: String, val exception: Throwable? = null) : PipelineResult<Nothing>()
    
    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }
    
    fun getOrElse(default: @UnsafeVariance T): @UnsafeVariance T = when (this) {
        is Success -> value
        is Failure -> default
    }
}

/**
 * Extension functions for pipeline operations
 */

/**
 * Convert JoernSymbol to appropriate pipeline tasks
 */
fun JoernSymbol.toPipelineTasks(analysisItem: JoernAnalysisItem): List<Any> {
    return when (this.type) {
        JoernSymbolType.METHOD -> listOf(
            CodeEmbeddingTask(analysisItem, this.code ?: ""),
            TextEmbeddingTask(analysisItem, "Method: ${this.name} in class ${this.parentClass}")
        )
        JoernSymbolType.CLASS -> listOf(
            ClassAnalysisTask(analysisItem, this)
        )
        else -> emptyList()
    }
}

/**
 * Create pipeline statistics tracker
 */
fun createPipelineStatistics(): PipelineStatistics {
    return PipelineStatistics().apply {
        stages["file_discovery"] = StageStatistics("file_discovery")
        stages["joern_analysis"] = StageStatistics("joern_analysis") 
        stages["embedding_processing"] = StageStatistics("embedding_processing")
        stages["vector_storage"] = StageStatistics("vector_storage")
        stages["results_collection"] = StageStatistics("results_collection")
    }
}