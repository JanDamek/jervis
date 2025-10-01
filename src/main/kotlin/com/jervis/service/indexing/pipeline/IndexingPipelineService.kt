package com.jervis.service.indexing.pipeline

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.domain.rag.SymbolType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.analysis.JoernResultParser
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.TokenEstimationService
import com.jervis.service.gateway.processing.dto.LlmResponseWrapper
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingProgress
import com.jervis.service.indexing.monitoring.IndexingStepStatus
import com.jervis.service.indexing.monitoring.IndexingStepType
import com.jervis.service.rag.RagIndexingStatusService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Pipeline-based indexing service using Kotlin Channels for streaming processing.
 * Implements producer-consumer architecture with continuous flow processing.
 */
@Service
class IndexingPipelineService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val joernAnalysisService: JoernAnalysisService,
    private val joernResultParser: JoernResultParser,
    private val llmGateway: LlmGateway,
    private val indexingMonitorService: IndexingMonitorService,
    private val ragIndexingStatusService: RagIndexingStatusService,
    private val tokenEstimationService: TokenEstimationService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val CHANNEL_BUFFER_SIZE = 100
        private const val CONSUMER_CONCURRENCY = 4
        private const val MAX_LLM_SUMMARY_TOKENS = 300
    }

    /**
     * Main pipeline orchestrator for project indexing
     */
    suspend fun indexProjectWithPipeline(
        project: ProjectDocument,
        projectPath: Path,
    ): IndexingPipelineResult =
        coroutineScope {
            logger.info { "PIPELINE_START: Starting streaming indexation for project: ${project.name}" }
            val overallStartTime = System.currentTimeMillis()

            // Report pipeline start UNDER EXISTING STEP: code_files
            indexingMonitorService.updateStepProgress(
                project.id,
                IndexingStepType.CODE_FILES,
                IndexingStepStatus.RUNNING,
                IndexingProgress(0, 4), // joern, embedding, storage, collect
                "Initializing code files indexing pipeline",
                logs = listOf("Pipeline indexing started for code files"),
            )

            // Create channels for pipeline stages
            val joernResultChannel = Channel<JoernAnalysisItem>(CHANNEL_BUFFER_SIZE)
            val embeddingChannel = Channel<EmbeddingPipelineItem>(CHANNEL_BUFFER_SIZE)
            val storageChannel = Channel<StoragePipelineItem>(CHANNEL_BUFFER_SIZE)

            // Launch all pipeline stages concurrently
            val pipeline =
                listOf(
                    // Stage 1: Language-based Joern Analysis Producer
                    async {
                        languageBasedJoernProducer(project, projectPath, joernResultChannel)
                    },
                    // Stage 2: Parallel Embedding Processors (multiple consumers)
                    async {
                        embeddingPipelineProcessor(project, joernResultChannel, embeddingChannel)
                    },
                    // Stage 3: Vector Storage Consumer
                    async {
                        vectorStorageProcessor(project, embeddingChannel, storageChannel)
                    },
                    // Stage 4: Results Collector
                    async {
                        resultsCollector(storageChannel)
                    },
                )

            try {
                // Update general pipeline progress under code_files
                indexingMonitorService.updateStepProgress(
                    project.id,
                    IndexingStepType.CODE_FILES,
                    IndexingStepStatus.RUNNING,
                    IndexingProgress(1, 4),
                    "Executing pipeline stages (joern, embeddings, storage)",
                    logs = listOf("Pipeline stages launched and running"),
                )

                // Wait for pipeline completion
                val results = pipeline.awaitAll()

                val totalTime = System.currentTimeMillis() - overallStartTime
                logger.info { "PIPELINE_COMPLETE: Streaming indexation completed for project: ${project.name} in ${totalTime}ms" }

                // Report completion under code_files
                indexingMonitorService.updateStepProgress(
                    project.id,
                    IndexingStepType.CODE_FILES,
                    IndexingStepStatus.COMPLETED,
                    IndexingProgress(4, 4),
                    "Code files indexing pipeline completed successfully",
                    logs = listOf("Pipeline completed in ${totalTime}ms"),
                )

                val result = aggregateResults(results, totalTime)

                // Clear file content cache to free memory after successful completion
                joernResultParser.clearCache()
                logger.debug { "File content cache cleared after successful pipeline completion" }

                result
            } catch (e: Exception) {
                logger.error(e) { "PIPELINE_ERROR: Pipeline failed for project: ${project.name}" }

                // Report failure to monitoring under code_files
                indexingMonitorService.updateStepProgress(
                    project.id,
                    IndexingStepType.CODE_FILES,
                    IndexingStepStatus.FAILED,
                    IndexingProgress(0, 4),
                    errorMessage = "Code files pipeline failed: ${e.message}",
                    logs = listOf("Pipeline execution failed", "Error: ${e.message}"),
                )

                // Cancel all pipeline stages
                pipeline.forEach { it.cancel() }

                // Clear file content cache to free memory after pipeline failure
                joernResultParser.clearCache()
                logger.debug { "File content cache cleared after pipeline failure" }

                IndexingPipelineResult(
                    totalProcessed = 0,
                    totalErrors = 1,
                    processingTimeMs = System.currentTimeMillis() - overallStartTime,
                    throughput = 0.0,
                    errorMessage = "Pipeline failed: ${e.message}",
                )
            }
        }

    /**
     * Producer Stage 1: Language-based Joern analysis using per-language CPGs
     */
    private suspend fun languageBasedJoernProducer(
        project: ProjectDocument,
        projectPath: Path,
        joernResultChannel: SendChannel<JoernAnalysisItem>,
    ) {
        try {
            logger.info { "PIPELINE_JOERN: Starting language-based Joern analysis for ${project.name}" }

            indexingMonitorService.addStepLog(
                project.id,
                IndexingStepType.CODE_FILES,
                "Language-based Joern analysis started",
            )

            val startTime = System.currentTimeMillis()
            var processedSymbols = 0

            // Use the new language-based indexing approach
            joernAnalysisService.indexProjectWithJoern(projectPath).collect { symbol ->
                val analysisItem =
                    JoernAnalysisItem(
                        filePath = Path.of(symbol.filePath),
                        symbol = symbol,
                        projectId = project.id,
                        workerId = 0, // Single worker for language-based approach
                        timestamp = System.currentTimeMillis(),
                    )

                joernResultChannel.send(analysisItem)
                processedSymbols++

                if (processedSymbols % 100 == 0) {
                    logger.debug { "PIPELINE_JOERN: Processed $processedSymbols symbols" }
                    // Proactively maintain cache to prevent memory buildup
                    joernResultParser.maintainCache()
                }
            }

            val totalTime = System.currentTimeMillis() - startTime
            logger.info {
                "PIPELINE_JOERN: Language-based Joern analysis completed - processed $processedSymbols symbols in ${totalTime}ms"
            }

            indexingMonitorService.addStepLog(
                project.id,
                IndexingStepType.CODE_FILES,
                "Language-based Joern analysis completed: $processedSymbols symbols in ${totalTime}ms",
            )
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_JOERN_ERROR: Language-based Joern analysis failed" }

            indexingMonitorService.updateStepProgress(
                project.id,
                IndexingStepType.CODE_FILES,
                IndexingStepStatus.FAILED,
                IndexingProgress(0, 4),
                errorMessage = "Language-based Joern analysis failed: ${e.message}",
                logs = listOf("Joern analysis stage failed", "Error: ${e.message}"),
            )
            throw e
        } finally {
            joernResultChannel.close()
        }
    }

    /**
     * Consumer/Producer Stage 3: Multi-stream embedding processing with fork pattern
     */
    private suspend fun embeddingPipelineProcessor(
        project: ProjectDocument,
        joernResultChannel: ReceiveChannel<JoernAnalysisItem>,
        embeddingChannel: SendChannel<EmbeddingPipelineItem>,
    ) = coroutineScope {
        logger.info { "PIPELINE_EMBEDDING: Starting embedding pipeline processor" }

        // Log under code_files instead of unknown step
        indexingMonitorService.addStepLog(
            project.id,
            IndexingStepType.CODE_FILES,
            "Embedding pipeline started",
        )

        val startTime = System.currentTimeMillis()

        // Create specialized channels for different embedding types
        val codeEmbeddingChannel = Channel<CodeEmbeddingTask>(CHANNEL_BUFFER_SIZE)
        val textEmbeddingChannel = Channel<TextEmbeddingTask>(CHANNEL_BUFFER_SIZE)
        val classAnalysisChannel = Channel<ClassAnalysisTask>(CHANNEL_BUFFER_SIZE)

        try {
            // Splitter: Route Joern results to appropriate processors
            val splitter =
                async {
                    var routedItems = 0
                    for (analysisItem in joernResultChannel) {
                        // Check if content should be processed based on hash
                        val shouldProcess = shouldProcessSymbol(project, analysisItem)

                        if (!shouldProcess) {
                            logger.debug {
                                "PIPELINE_SPLITTER: Skipping ${analysisItem.symbol.name} - content unchanged"
                            }
                            continue
                        }

                        when (analysisItem.symbol.type) {
                            JoernSymbolType.METHOD -> {
                                var itemsAdded = 0

                                // Send code for code embedding ONLY if code is not empty
                                val code = analysisItem.symbol.code
                                if (code?.isNotBlank() == true) {
                                    codeEmbeddingChannel.send(
                                        CodeEmbeddingTask(analysisItem, code),
                                    )
                                    itemsAdded++
                                } else {
                                    logger.debug {
                                        "PIPELINE_SPLITTER: Skipping code embedding for ${analysisItem.symbol.name} - no code available"
                                    }
                                }

                                // Send method for text summary embedding only if beneficial
                                if (shouldGenerateTextSummary(analysisItem.symbol.type)) {
                                    generateMethodSummary(analysisItem)?.let {
                                        textEmbeddingChannel.send(
                                            TextEmbeddingTask(analysisItem, it),
                                        )
                                        itemsAdded++
                                    }
                                }

                                routedItems += itemsAdded
                            }

                            JoernSymbolType.CLASS -> {
                                if (shouldGenerateTextSummary(analysisItem.symbol.type)) {
                                    classAnalysisChannel.send(
                                        ClassAnalysisTask(analysisItem, analysisItem.symbol),
                                    )
                                    routedItems++
                                }
                            }

                            JoernSymbolType.VARIABLE, JoernSymbolType.FIELD, JoernSymbolType.PARAMETER -> {
                                // For simple symbols, only send code embedding, skip LLM text summary
                                val code = analysisItem.symbol.code
                                if (code?.isNotBlank() == true) {
                                    codeEmbeddingChannel.send(
                                        CodeEmbeddingTask(analysisItem, code),
                                    )
                                    routedItems++
                                    logger.debug {
                                        "PIPELINE_SPLITTER: Processing ${analysisItem.symbol.type} ${analysisItem.symbol.name} for code embedding only"
                                    }
                                }
                            }

                            else -> {
                                logger.debug { "PIPELINE_SPLITTER: Skipping symbol type: ${analysisItem.symbol.type}" }
                            }
                        }
                    }

                    logger.debug { "PIPELINE_SPLITTER: Routed $routedItems items to processing channels" }

                    codeEmbeddingChannel.close()
                    textEmbeddingChannel.close()
                    classAnalysisChannel.close()
                }

            // Parallel embedding processors
            val processors =
                listOf(
                    // Code embedding processor
                    async {
                        codeEmbeddingProcessor(codeEmbeddingChannel, embeddingChannel, ModelType.EMBEDDING_CODE)
                    },
                    // Text embedding processor
                    async {
                        textEmbeddingProcessor(textEmbeddingChannel, embeddingChannel, ModelType.EMBEDDING_TEXT)
                    },
                    // Class analysis processor
                    async {
                        classAnalysisProcessor(classAnalysisChannel, embeddingChannel)
                    },
                )

            // Wait for splitter and all processors
            listOf(splitter, *processors.toTypedArray()).awaitAll()

            val totalTime = System.currentTimeMillis() - startTime
            logger.info { "PIPELINE_EMBEDDING: Embedding pipeline processor completed in ${totalTime}ms" }

            indexingMonitorService.addStepLog(
                project.id,
                IndexingStepType.CODE_FILES,
                "Embedding pipeline completed in ${totalTime}ms",
            )
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_EMBEDDING_ERROR: Embedding processor failed" }

            indexingMonitorService.updateStepProgress(
                project.id,
                IndexingStepType.CODE_FILES,
                IndexingStepStatus.FAILED,
                IndexingProgress(0, 5),
                errorMessage = "Embedding pipeline failed: ${e.message}",
                logs = listOf("Embedding pipeline stage failed", "Error: ${e.message}"),
            )
        } finally {
            embeddingChannel.close()
        }
    }

    /**
     * Code embedding processor with true streaming (1 item at a time)
     */
    private suspend fun codeEmbeddingProcessor(
        inputChannel: ReceiveChannel<CodeEmbeddingTask>,
        outputChannel: SendChannel<EmbeddingPipelineItem>,
        modelType: ModelType,
    ) {
        var processedItems = 0

        try {
            for (task in inputChannel) {
                try {
                    val startTime = System.currentTimeMillis()
                    val embedding = embeddingGateway.callEmbedding(modelType, task.content)
                    val processingTime = System.currentTimeMillis() - startTime

                    val pipelineItem =
                        EmbeddingPipelineItem(
                            analysisItem = task.analysisItem,
                            content = task.content,
                            embedding = embedding,
                            embeddingType = modelType,
                            processingTimeMs = processingTime,
                        )

                    outputChannel.send(pipelineItem)
                    processedItems++
                } catch (e: Exception) {
                    logger.warn(e) { "PIPELINE_CODE_PROCESSOR_ERROR: Failed to process code embedding task" }
                }
            }

            logger.info { "PIPELINE_CODE_PROCESSOR: Completed - processed $processedItems code items" }
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_CODE_PROCESSOR_ERROR: Code processing failed" }
        }
    }

    /**
     * Text embedding processor with true streaming (1 item at a time)
     */
    private suspend fun textEmbeddingProcessor(
        inputChannel: ReceiveChannel<TextEmbeddingTask>,
        outputChannel: SendChannel<EmbeddingPipelineItem>,
        modelType: ModelType,
    ) {
        var processedItems = 0

        try {
            for (task in inputChannel) {
                try {
                    val startTime = System.currentTimeMillis()
                    val embedding = embeddingGateway.callEmbedding(modelType, task.content)
                    val processingTime = System.currentTimeMillis() - startTime

                    val pipelineItem =
                        EmbeddingPipelineItem(
                            analysisItem = task.analysisItem,
                            content = task.content,
                            embedding = embedding,
                            embeddingType = modelType,
                            processingTimeMs = processingTime,
                        )

                    outputChannel.send(pipelineItem)
                    processedItems++
                } catch (e: Exception) {
                    logger.warn(e) { "PIPELINE_TEXT_PROCESSOR_ERROR: Failed to process text embedding task" }
                }
            }

            logger.info { "PIPELINE_TEXT_PROCESSOR: Completed - processed $processedItems text items" }
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_TEXT_PROCESSOR_ERROR: Text processing failed" }
        }
    }

    /**
     * Class analysis processor
     */
    private suspend fun classAnalysisProcessor(
        inputChannel: ReceiveChannel<ClassAnalysisTask>,
        outputChannel: SendChannel<EmbeddingPipelineItem>,
    ) {
        var processedClasses = 0

        try {
            for (classTask in inputChannel) {
                // Process class analysis and generate embedding
                val classSummary = generateClassSummary(classTask.classSymbol)
                classSummary?.let {
                    val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, it)

                    val pipelineItem =
                        EmbeddingPipelineItem(
                            analysisItem = classTask.analysisItem,
                            content = classSummary,
                            embedding = embedding,
                            embeddingType = ModelType.EMBEDDING_TEXT,
                            processingTimeMs = System.currentTimeMillis() - classTask.analysisItem.timestamp,
                        )

                    outputChannel.send(pipelineItem)
                    processedClasses++
                }
            }

            logger.info { "PIPELINE_CLASS_PROCESSOR: Completed - processed $processedClasses classes" }
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_CLASS_PROCESSOR_ERROR: Class processing failed" }
        }
    }

    /**
     * Consumer Stage 4: Stream items to vector storage
     */
    private suspend fun vectorStorageProcessor(
        project: ProjectDocument,
        embeddingChannel: ReceiveChannel<EmbeddingPipelineItem>,
        storageChannel: SendChannel<StoragePipelineItem>,
    ) = coroutineScope {
        logger.info { "PIPELINE_STORAGE: Starting vector storage processor" }

        // Log under code_files
        indexingMonitorService.addStepLog(
            project.id,
            IndexingStepType.CODE_FILES,
            "Vector storage stage started",
        )

        val startTime = System.currentTimeMillis()
        var totalProcessed: Int
        var totalErrors: Int

        try {
            // Launch multiple storage workers for parallel writes
            val workers =
                (1..CONSUMER_CONCURRENCY).map { workerId ->
                    async {
                        var workerProcessed = 0
                        var workerErrors = 0

                        for (item in embeddingChannel) {
                            try {
                                logger.debug { "PIPELINE_STORAGE_WORKER_$workerId: Storing item: ${item.analysisItem.symbol.name}" }

                                // Create RagDocument
                                val ragDocument = createRagDocument(project, item)

                                // Store to vector database
                                vectorStorage.store(item.embeddingType, ragDocument, item.embedding)

                                // Report success (to collector) and log target
                                val storageItem =
                                    StoragePipelineItem(
                                        analysisItem = item.analysisItem,
                                        success = true,
                                        workerId = workerId,
                                        processingTimeMs = System.currentTimeMillis() - item.analysisItem.timestamp,
                                    )

                                storageChannel.send(storageItem)
                                workerProcessed++

                                // EXTRA: log where item went
                                indexingMonitorService.addStepLog(
                                    project.id,
                                    IndexingStepType.CODE_FILES,
                                    "Stored embedding for ${symbolSafeName(item.analysisItem.symbol)} " +
                                        "to vector DB (${item.embeddingType})",
                                )
                            } catch (e: Exception) {
                                logger.error(e) { "PIPELINE_STORAGE_ERROR: Failed to store item" }
                                storageChannel.send(
                                    StoragePipelineItem(
                                        analysisItem = item.analysisItem,
                                        success = false,
                                        error = e.message,
                                        workerId = workerId,
                                    ),
                                )
                                workerErrors++
                            }
                        }

                        logger.debug { "PIPELINE_STORAGE_WORKER_$workerId: Completed - processed: $workerProcessed, errors: $workerErrors" }
                        Pair(workerProcessed, workerErrors)
                    }
                }

            // Wait for all workers and collect statistics
            val workerResults = workers.awaitAll()
            totalProcessed = workerResults.sumOf { it.first }
            totalErrors = workerResults.sumOf { it.second }

            val totalTime = System.currentTimeMillis() - startTime
            logger.info {
                "PIPELINE_STORAGE: Vector storage processor completed - " +
                    "processed: $totalProcessed, errors: $totalErrors, time: ${totalTime}ms"
            }

            indexingMonitorService.addStepLog(
                project.id,
                IndexingStepType.CODE_FILES,
                "Vector storage completed: processed=$totalProcessed, errors=$totalErrors, time=${totalTime}ms",
            )
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_STORAGE_PROCESSOR_ERROR: Storage processing failed" }

            indexingMonitorService.updateStepProgress(
                project.id,
                IndexingStepType.CODE_FILES,
                IndexingStepStatus.FAILED,
                IndexingProgress(0, 5),
                errorMessage = "Vector storage failed: ${e.message}",
                logs = listOf("Vector storage stage failed", "Error: ${e.message}"),
            )
        } finally {
            storageChannel.close()
        }
    }

    /**
     * Stage 5: Collect and summarize results
     */
    private suspend fun resultsCollector(storageChannel: ReceiveChannel<StoragePipelineItem>): IndexingPipelineResult {
        var totalProcessed = 0
        var totalErrors = 0
        val startTime = System.currentTimeMillis()

        try {
            for (item in storageChannel) {
                if (item.success) {
                    totalProcessed++
                } else {
                    totalErrors++
                    logger.debug { "PIPELINE_RESULT_ERROR: ${item.error}" }
                }
            }

            val totalTime = System.currentTimeMillis() - startTime
            val throughput =
                if (totalTime > 0) {
                    (totalProcessed + totalErrors).toDouble() / (totalTime / 1000.0)
                } else {
                    0.0
                }

            logger.info {
                "PIPELINE_RESULTS: Collection completed - " +
                    "processed: $totalProcessed, errors: $totalErrors, " +
                    "throughput: ${"%.2f".format(throughput)} items/sec"
            }

            return IndexingPipelineResult(
                totalProcessed = totalProcessed,
                totalErrors = totalErrors,
                processingTimeMs = totalTime,
                throughput = throughput,
            )
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_RESULTS_ERROR: Results collection failed" }
            return IndexingPipelineResult(
                totalProcessed = totalProcessed,
                totalErrors = totalErrors + 1,
                processingTimeMs = System.currentTimeMillis() - startTime,
                throughput = 0.0,
                errorMessage = "Results collection failed: ${e.message}",
            )
        }
    }

    /**
     * Create Joern script for symbol extraction for a specific file using template
     */

    private suspend fun generateMethodSummary(analysisItem: JoernAnalysisItem): String? {
        // Generate method summary using LLM with token limit and chunking
        return try {
            return analysisItem.symbol.code?.let {
                val llmResponse =
                    llmGateway
                        .callLlm(
                            type = PromptTypeEnum.METHOD_SUMMARY,
                            responseSchema = LlmResponseWrapper(),
                            quick = false,
                            mappingValue =
                                mapOf(
                                    "methodName" to analysisItem.symbol.name,
                                    "methodSignature" to "${analysisItem.symbol.name}()",
                                    "parentClass" to (analysisItem.symbol.parentClass ?: "Unknown"),
                                    "filePath" to analysisItem.symbol.filePath,
                                    "code" to it,
                                ),
                        ).response

                // Check token limit and chunk if necessary
                tokenEstimationService.processLlmOutputWithTokenLimit(
                    llmResponse,
                    "method ${analysisItem.symbol.name}",
                    MAX_LLM_SUMMARY_TOKENS,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to generate method summary for ${analysisItem.symbol.name}" }
            "Method ${analysisItem.symbol.name} in class ${analysisItem.symbol.parentClass}"
        }
    }

    private suspend fun generateClassSummary(classSymbol: JoernSymbol): String? {
        // Generate class summary using LLM with token limit and chunking
        return try {
            classSymbol.code?.let {
                val llmResponse =
                    llmGateway
                        .callLlm(
                            type = PromptTypeEnum.CLASS_SUMMARY,
                            responseSchema = LlmResponseWrapper(),
                            quick = false,
                            mappingValue =
                                mapOf(
                                    "className" to classSymbol.name,
                                    "filePath" to classSymbol.filePath,
                                    "code" to it,
                                    "relations" to "",
                                ),
                        ).response

                // Check token limit and chunk if necessary
                tokenEstimationService.processLlmOutputWithTokenLimit(
                    llmResponse,
                    "class ${classSymbol.name}",
                    MAX_LLM_SUMMARY_TOKENS,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to generate class summary for ${classSymbol.name}" }
            "Class ${classSymbol.name} in file ${classSymbol.filePath}"
        }
    }

    private fun createRagDocument(
        project: ProjectDocument,
        item: EmbeddingPipelineItem,
    ): RagDocument {
        val symbol = item.analysisItem.symbol

        return when (symbol.type) {
            JoernSymbolType.METHOD -> {
                RagDocument(
                    projectId = project.id,
                    clientId = project.clientId,
                    ragSourceType = RagSourceType.JOERN,
                    summary = item.content,
                    path = symbol.filePath,
                    language = symbol.language,
                    className = symbol.parentClass,
                    methodName = symbol.name,
                    symbolName = symbol.name,
                    lineStart = symbol.lineStart,
                    lineEnd = symbol.lineEnd,
                    joernNodeId = symbol.nodeId,
                    symbolType = SymbolType.METHOD,
                )
            }

            JoernSymbolType.CLASS -> {
                RagDocument(
                    projectId = project.id,
                    clientId = project.clientId,
                    ragSourceType = RagSourceType.JOERN,
                    summary = item.content,
                    path = symbol.filePath,
                    language = symbol.language,
                    parentClass = symbol.parentClass,
                    className = symbol.name,
                    symbolName = symbol.name,
                    lineStart = symbol.lineStart,
                    joernNodeId = symbol.nodeId,
                    symbolType = SymbolType.CLASS,
                )
            }

            else -> {
                RagDocument(
                    projectId = project.id,
                    clientId = project.clientId,
                    ragSourceType = RagSourceType.JOERN,
                    summary = item.content,
                    path = symbol.filePath,
                    language = symbol.language,
                    symbolName = symbol.name,
                    joernNodeId = symbol.nodeId,
                )
            }
        }
    }

    private fun aggregateResults(
        results: List<Any>,
        totalTime: Long,
    ): IndexingPipelineResult {
        // Aggregate pipeline results from all stages
        var totalProcessed = 0
        var totalErrors = 0

        results.forEach { result ->
            when (result) {
                is IndexingPipelineResult -> {
                    totalProcessed += result.totalProcessed
                    totalErrors += result.totalErrors
                }

                is Pair<*, *> -> {
                    // Handle worker results (processed, errors)
                    val processed = result.first as? Int ?: 0
                    val errors = result.second as? Int ?: 0
                    totalProcessed += processed
                    totalErrors += errors
                }
            }
        }

        val throughput =
            if (totalTime > 0) {
                (totalProcessed.toDouble() / (totalTime / 1000.0))
            } else {
                0.0
            }

        return IndexingPipelineResult(
            totalProcessed = totalProcessed,
            totalErrors = totalErrors,
            processingTimeMs = totalTime,
            throughput = throughput,
        )
    }

    private fun symbolSafeName(symbol: JoernSymbol): String =
        buildString {
            append(symbol.name)
            symbol.parentClass?.let { append(" (class $it)") }
        }

    /**
     * Check if symbol should be processed based on content hash to avoid redundant work
     */
    private suspend fun shouldProcessSymbol(
        project: ProjectDocument,
        analysisItem: JoernAnalysisItem,
    ): Boolean =
        try {
            val symbol = analysisItem.symbol
            val symbolContent = symbol.code?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()

            // Create a unique identifier for this symbol within the file
            val symbolPath = "${symbol.filePath}#${symbol.type}:${symbol.name}:${symbol.lineStart}-${symbol.lineEnd}"

            // Use current git commit hash if available, otherwise use "unknown"
            val gitCommitHash = "current" // TODO: Get actual git commit hash from context

            ragIndexingStatusService.shouldIndexFile(
                projectId = project.id,
                filePath = symbolPath,
                gitCommitHash = gitCommitHash,
                fileContent = symbolContent,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to check if symbol should be processed: ${analysisItem.symbol.name}" }
            // If we can't determine, process it to be safe
            true
        }

    /**
     * Determine if LLM text summary should be generated for this symbol type
     * Variables and fields typically don't need LLM summaries for code embeddings
     */
    private fun shouldGenerateTextSummary(symbolType: JoernSymbolType): Boolean =
        when (symbolType) {
            JoernSymbolType.METHOD, JoernSymbolType.FUNCTION -> true
            JoernSymbolType.CLASS -> true
            JoernSymbolType.VARIABLE, JoernSymbolType.FIELD, JoernSymbolType.PARAMETER -> false
            JoernSymbolType.CALL, JoernSymbolType.IMPORT -> false
            JoernSymbolType.FILE, JoernSymbolType.PACKAGE, JoernSymbolType.MODULE -> false
        }
}
