package com.jervis.service.indexing.pipeline

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.EmbeddingType
import com.jervis.domain.rag.LineRange
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Pipeline-based indexing service using Kotlin Channels for streaming processing.
 * Implements producer-consumer architecture with continuous flow processing.
 */
@Service
class IndexingPipelineService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val joernAnalysisService: JoernAnalysisService,
    private val llmGateway: LlmGateway,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val CHANNEL_BUFFER_SIZE = 100
        private const val PRODUCER_CONCURRENCY = 2
        private const val CONSUMER_CONCURRENCY = 4
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

            // Create channels for pipeline stages
            val fileChannel = Channel<Path>(CHANNEL_BUFFER_SIZE)
            val joernResultChannel = Channel<JoernAnalysisItem>(CHANNEL_BUFFER_SIZE)
            val embeddingChannel = Channel<EmbeddingPipelineItem>(CHANNEL_BUFFER_SIZE)
            val storageChannel = Channel<StoragePipelineItem>(CHANNEL_BUFFER_SIZE)

            // Launch all pipeline stages concurrently
            val pipeline =
                listOf(
                    // Stage 1: File Discovery Producer
                    async {
                        fileDiscoveryProducer(project, projectPath, fileChannel)
                    },
                    // Stage 2: Joern Analysis Consumer/Producer
                    async {
                        joernAnalysisProcessor(project, fileChannel, joernResultChannel)
                    },
                    // Stage 3: Parallel Embedding Processors (multiple consumers)
                    async {
                        embeddingPipelineProcessor(project, joernResultChannel, embeddingChannel)
                    },
                    // Stage 4: Vector Storage Consumer
                    async {
                        vectorStorageProcessor(project, embeddingChannel, storageChannel)
                    },
                    // Stage 5: Results Collector
                    async {
                        resultsCollector(storageChannel)
                    },
                )

            try {
                // Wait for pipeline completion
                val results = pipeline.awaitAll()

                val totalTime = System.currentTimeMillis() - overallStartTime
                logger.info { "PIPELINE_COMPLETE: Streaming indexation completed for project: ${project.name} in ${totalTime}ms" }

                aggregateResults(results, totalTime)
            } catch (e: Exception) {
                logger.error(e) { "PIPELINE_ERROR: Pipeline failed for project: ${project.name}" }

                // Cancel all pipeline stages
                pipeline.forEach { it.cancel() }

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
     * Producer Stage 1: Discover and stream files for processing
     */
    private suspend fun fileDiscoveryProducer(
        project: ProjectDocument,
        projectPath: Path,
        fileChannel: SendChannel<Path>,
    ) {
        try {
            logger.info { "PIPELINE_PRODUCER: Starting file discovery for ${project.name}" }
            val startTime = System.currentTimeMillis()
            var discoveredFiles = 0

            Files.walk(projectPath).use { stream ->
                val files = stream
                    .filter { it.isRegularFile() }
                    .filter { shouldProcessFile(it, project) }
                    .toList()
                
                for (filePath in files) {
                    logger.debug { "PIPELINE_PRODUCER: Discovered file: $filePath" }
                    fileChannel.send(filePath)
                    discoveredFiles++
                }
            }

            val totalTime = System.currentTimeMillis() - startTime
            logger.info { "PIPELINE_PRODUCER: File discovery completed - discovered $discoveredFiles files in ${totalTime}ms" }
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_PRODUCER_ERROR: File discovery failed" }
        } finally {
            fileChannel.close()
        }
    }

    /**
     * Consumer/Producer Stage 2: Process files through Joern analysis
     */
    private suspend fun joernAnalysisProcessor(
        project: ProjectDocument,
        fileChannel: ReceiveChannel<Path>,
        joernResultChannel: SendChannel<JoernAnalysisItem>,
    ) = coroutineScope {
        logger.info { "PIPELINE_JOERN: Starting Joern analysis processor" }
        val startTime = System.currentTimeMillis()
        var processedFiles = 0
        var generatedSymbols = 0

        try {
            // Launch multiple Joern workers for parallel processing
            val workers =
                (1..PRODUCER_CONCURRENCY).map { workerId ->
                    async {
                        var workerFiles = 0
                        var workerSymbols = 0

                        for (filePath in fileChannel) {
                            try {
                                logger.debug { "PIPELINE_JOERN_WORKER_$workerId: Processing file: $filePath" }

                                // Analyze file with Joern
                                val symbols = analyzeFileWithJoern(filePath)

                                // Stream results to next stage
                                for (symbol in symbols) {
                                    val analysisItem =
                                        JoernAnalysisItem(
                                            filePath = filePath,
                                            symbol = symbol,
                                            projectId = project.id,
                                            workerId = workerId,
                                            timestamp = System.currentTimeMillis(),
                                        )
                                    joernResultChannel.send(analysisItem)
                                    workerSymbols++
                                }

                                workerFiles++
                            } catch (e: Exception) {
                                logger.warn(e) { "PIPELINE_JOERN_ERROR: Failed to analyze file: $filePath" }
                            }
                        }

                        logger.debug { "PIPELINE_JOERN_WORKER_$workerId: Completed - files: $workerFiles, symbols: $workerSymbols" }
                        Pair(workerFiles, workerSymbols)
                    }
                }

            // Wait for all workers and collect statistics
            val workerResults = workers.awaitAll()
            processedFiles = workerResults.sumOf { it.first }
            generatedSymbols = workerResults.sumOf { it.second }

            val totalTime = System.currentTimeMillis() - startTime
            logger.info {
                "PIPELINE_JOERN: Analysis processor completed - " +
                    "files: $processedFiles, symbols: $generatedSymbols, time: ${totalTime}ms"
            }
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_JOERN_PROCESSOR_ERROR: Joern analysis failed" }
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
                        when (analysisItem.symbol.type) {
                            JoernSymbolType.METHOD -> {
                                // Send code for code embedding
                                codeEmbeddingChannel.send(
                                    CodeEmbeddingTask(analysisItem, analysisItem.symbol.code ?: ""),
                                )

                                // Send method for text summary embedding
                                textEmbeddingChannel.send(
                                    TextEmbeddingTask(analysisItem, generateMethodSummary(analysisItem)),
                                )
                                routedItems += 2
                            }

                            JoernSymbolType.CLASS -> {
                                classAnalysisChannel.send(
                                    ClassAnalysisTask(analysisItem, analysisItem.symbol),
                                )
                                routedItems++
                            }

                            else -> {
                                logger.debug { "PIPELINE_SPLITTER: Skipping unknown symbol type: ${analysisItem.symbol.type}" }
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
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_EMBEDDING_ERROR: Embedding processor failed" }
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

                    val pipelineItem = EmbeddingPipelineItem(
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

                    val pipelineItem = EmbeddingPipelineItem(
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
                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, classSummary)

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
        val startTime = System.currentTimeMillis()
        var totalProcessed = 0
        var totalErrors = 0

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

                                // Report success
                                val storageItem =
                                    StoragePipelineItem(
                                        analysisItem = item.analysisItem,
                                        success = true,
                                        workerId = workerId,
                                        processingTimeMs = System.currentTimeMillis() - item.analysisItem.timestamp,
                                    )

                                storageChannel.send(storageItem)
                                workerProcessed++
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
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_STORAGE_PROCESSOR_ERROR: Storage processing failed" }
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

    // Helper methods would be implemented here...
    private fun shouldProcessFile(
        filePath: Path,
        project: ProjectDocument,
    ): Boolean {
        // Implementation for file filtering logic
        val fileName = filePath.fileName.toString().lowercase()
        val extension = fileName.substringAfterLast('.', "")

        return extension in setOf("kt", "java", "js", "ts", "py", "scala") &&
            !fileName.contains("test") &&
            !filePath.toString().contains("/target/") &&
            !filePath.toString().contains("/node_modules/")
    }

    private suspend fun analyzeFileWithJoern(filePath: Path): List<JoernSymbol> {
        try {
            logger.debug { "PIPELINE_JOERN_ANALYSIS: Analyzing file with Joern: $filePath" }
            
            val fileName = filePath.name
            val extension = filePath.extension

            // Only process supported file types
            if (extension !in setOf("kt", "java", "js", "ts", "py", "scala")) {
                logger.debug { "PIPELINE_JOERN_ANALYSIS: Skipping unsupported file type: $extension" }
                return emptyList()
            }

            // Get project root (assuming filePath is within a project)
            val projectRoot = findProjectRoot(filePath)
            if (projectRoot == null) {
                logger.warn { "PIPELINE_JOERN_ANALYSIS: Could not find project root for file: $filePath" }
                return emptyList()
            }

            // Setup Joern directory
            val joernDir = joernAnalysisService.setupJoernDirectory(projectRoot)
            val cpgPath = joernDir.resolve("cpg.bin")
            
            logger.debug { "PIPELINE_JOERN_ANALYSIS: Using project root: $projectRoot, Joern dir: $joernDir" }

            // Ensure CPG exists for the project
            val cpgExists = joernAnalysisService.ensureCpgExists(projectRoot, cpgPath)
            if (!cpgExists) {
                logger.error { "PIPELINE_JOERN_ANALYSIS: Failed to create or find CPG for project: $projectRoot" }
                return emptyList()
            }

            logger.debug { "PIPELINE_JOERN_ANALYSIS: CPG verified at: $cpgPath" }

            // Generate Joern script for symbol extraction
            val extractionScript = createSymbolExtractionScript(cpgPath, filePath.toString())
            val scriptFile = joernDir.resolve("symbol_extraction_${filePath.hashCode()}.sc")

            Files.writeString(scriptFile, extractionScript)
            logger.debug { "PIPELINE_JOERN_ANALYSIS: Created symbol extraction script: $scriptFile" }

            // Execute Joern analysis
            val result = executeJoernScript(projectRoot, joernDir, scriptFile)
            
            // Clean up script file
            try {
                Files.deleteIfExists(scriptFile)
            } catch (e: Exception) {
                logger.debug(e) { "Could not delete script file: $scriptFile" }
            }

            if (result.isNotEmpty()) {
                val symbols = parseJoernSymbolResults(result)
                logger.debug { "PIPELINE_JOERN_ANALYSIS: Extracted ${symbols.size} symbols from file: $filePath" }
                return symbols
            } else {
                logger.warn { "PIPELINE_JOERN_ANALYSIS: No results from Joern script for file: $filePath" }
                return emptyList()
            }
            
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_JOERN_ANALYSIS: Error analyzing file with Joern: $filePath" }
            return emptyList()
        }
    }

    /**
     * Find project root directory from a file path
     */
    private fun findProjectRoot(filePath: Path): Path? {
        var current = filePath.parent
        while (current != null) {
            // Look for common project indicators
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("build.gradle.kts")) ||
                Files.exists(current.resolve("package.json")) ||
                Files.exists(current.resolve(".git"))
            ) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /**
     * Create Joern script for symbol extraction for a specific file
     */
    private fun createSymbolExtractionScript(cpgPath: Path, targetFilePath: String): String {
        val cpgPathString = cpgPath.toString()
        val normalizedFilePath = targetFilePath.replace("\\", "/")
        
        return buildString {
            appendLine("importCpg(\"$cpgPathString\")")
            appendLine()
            appendLine("// Filter symbols by specific file")
            appendLine("val targetFile = \"$normalizedFilePath\"")
            appendLine()
            appendLine("// Get classes from the target file")
            appendLine("val classData = cpg.typeDecl.filterNot(_.isExternal)")
            appendLine("  .filter(_.filename.contains(targetFile) || _.filename.endsWith(targetFile.split(\"/\").last))")
            appendLine("  .map { cls =>")
            appendLine("    val lineStart = cls.lineNumber.getOrElse(0)")
            appendLine("    s\"{\\\"type\\\":\\\"CLASS\\\",\\\"name\\\":\\\"\${cls.name}\\\",\\\"fullName\\\":\\\"\${cls.fullName}\\\",\\\"file\\\":\\\"\${cls.filename}\\\",\\\"lineStart\\\":\$lineStart,\\\"lineEnd\\\":\$lineStart,\\\"nodeId\\\":\\\"\${cls.id}\\\",\\\"namespace\\\":\\\"\\\"}\"")
            appendLine("  }.toList")
            appendLine()
            appendLine("// Get methods from the target file")
            appendLine("val methodData = cpg.method.filterNot(_.isExternal)")
            appendLine("  .filter(_.filename.contains(targetFile) || _.filename.endsWith(targetFile.split(\"/\").last))")
            appendLine("  .map { method =>")
            appendLine("    val lineStart = method.lineNumber.getOrElse(0)")
            appendLine("    val parentClass = method.typeDecl.name.headOption.getOrElse(\"\")")
            appendLine("    s\"{\\\"type\\\":\\\"METHOD\\\",\\\"name\\\":\\\"\${method.name}\\\",\\\"fullName\\\":\\\"\${method.fullName}\\\",\\\"signature\\\":\\\"\${method.signature}\\\",\\\"file\\\":\\\"\${method.filename}\\\",\\\"lineStart\\\":\$lineStart,\\\"lineEnd\\\":\$lineStart,\\\"nodeId\\\":\\\"\${method.id}\\\",\\\"parentClass\\\":\\\"\$parentClass\\\"}\"")
            appendLine("  }.toList")
            appendLine()
            appendLine("// Output file-specific data as JSON")
            appendLine("val allData = classData ++ methodData")
            appendLine("allData.foreach(println)")
        }
    }

    /**
     * Execute Joern script with error handling
     */
    private suspend fun executeJoernScript(
        projectPath: Path,
        joernDir: Path,
        scriptFile: Path
    ): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            logger.debug { "PIPELINE_JOERN: Executing script: $scriptFile" }

            val processBuilder = ProcessBuilder(
                "joern",
                "--script",
                scriptFile.toString()
            ).apply {
                directory(joernDir.toFile())
                environment().apply {
                    put("JAVA_OPTS", "-Xmx2g -Xms512m")
                    put("PATH", System.getenv("PATH"))
                }
            }

            val process = processBuilder.start()
            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)

            if (!finished) {
                process.destroyForcibly()
                logger.error { "PIPELINE_JOERN: Script execution timed out after 15 minutes" }
                return@withContext ""
            }

            val exitCode = process.exitValue()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }

            if (exitCode == 0) {
                logger.debug { "PIPELINE_JOERN: Script executed successfully" }
                return@withContext output
            } else {
                logger.error { "PIPELINE_JOERN: Script execution failed (exit=$exitCode): $error" }
                return@withContext ""
            }
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_JOERN: Error executing Joern script" }
            return@withContext ""
        }
    }

    /**
     * Parse Joern symbol results from script output
     */
    private suspend fun parseJoernSymbolResults(output: String): List<JoernSymbol> {
        val symbols = mutableListOf<JoernSymbol>()

        if (output.isBlank()) {
            logger.debug { "PIPELINE_JOERN: No output to parse" }
            return emptyList()
        }

        val lines = output.lines().filter { it.trim().isNotEmpty() }
        logger.debug { "PIPELINE_JOERN: Processing ${lines.size} lines from output" }

        for ((index, line) in lines.withIndex()) {
            try {
                val trimmedLine = line.trim()
                if (!trimmedLine.startsWith("{") || !trimmedLine.endsWith("}")) {
                    continue
                }

                val symbol = parseSymbolLine(trimmedLine)
                if (symbol != null) {
                    symbols.add(symbol)
                    logger.debug { "PIPELINE_JOERN: Parsed symbol: ${symbol.type} ${symbol.name}" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "PIPELINE_JOERN: Error parsing line $index: ${line.take(200)}" }
            }
        }

        logger.info { "PIPELINE_JOERN: Parsed ${symbols.size} symbols from output" }
        return symbols
    }

    /**
     * Parse individual symbol line from JSON output
     */
    private fun parseSymbolLine(line: String): JoernSymbol? {
        return try {
            val type = extractJsonValue(line, "type")
            val name = extractJsonValue(line, "name")
            val fullName = extractJsonValue(line, "fullName")
            val file = extractJsonValue(line, "file")
            val nodeId = extractJsonValue(line, "nodeId")
            val lineStartStr = extractJsonValue(line, "lineStart")
            val lineEndStr = extractJsonValue(line, "lineEnd")
            val signature = extractJsonValue(line, "signature")
            val parentClass = extractJsonValue(line, "parentClass")

            if (type.isEmpty() || name.isEmpty()) {
                return null
            }

            val symbolType = when (type.uppercase()) {
                "NAMESPACE" -> JoernSymbolType.NAMESPACE
                "CLASS" -> JoernSymbolType.CLASS
                "METHOD" -> JoernSymbolType.METHOD
                "FUNCTION" -> JoernSymbolType.FUNCTION
                else -> return null
            }

            val lineStart = lineStartStr.toIntOrNull() ?: 0
            val lineEnd = lineEndStr.toIntOrNull() ?: lineStart

            val language = when {
                file.endsWith(".kt") -> "kotlin"
                file.endsWith(".java") -> "java"
                file.endsWith(".scala") -> "scala"
                else -> "java"
            }

            JoernSymbol(
                type = symbolType,
                name = name,
                fullName = fullName,
                signature = if (signature.isNotEmpty()) signature else null,
                filePath = file,
                lineRange = LineRange(lineStart, lineEnd),
                code = null,
                joernNodeId = nodeId,
                language = language,
                relations = emptyList(),
                parentClass = if (parentClass.isNotEmpty()) parentClass else null,
                namespace = null
            )
        } catch (e: Exception) {
            logger.warn(e) { "PIPELINE_JOERN: Error parsing symbol JSON: ${line.take(200)}" }
            null
        }
    }

    /**
     * Extract JSON value using simple string parsing
     */
    private fun extractJsonValue(json: String, key: String): String {
        return try {
            val pattern = "\"$key\":\"([^\"]*)\""
            val regex = Regex(pattern)
            val matchResult = regex.find(json)
            matchResult?.groups?.get(1)?.value ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun generateMethodSummary(analysisItem: JoernAnalysisItem): String {
        // Generate method summary using LLM
        return try {
            val prompt =
                buildString {
                    appendLine("Generate a concise method summary:")
                    appendLine("Method: ${analysisItem.symbol.name}")
                    appendLine("Class: ${analysisItem.symbol.parentClass}")
                    appendLine("File: ${analysisItem.symbol.filePath}")
                    if (analysisItem.symbol.code?.isNotBlank() == true) {
                        appendLine("Code:")
                        appendLine(analysisItem.symbol.code)
                    }
                }

            llmGateway.callLlm(
                type = PromptTypeEnum.METHOD_SUMMARY,
                userPrompt = prompt,
                quick = true,
                responseSchema = "",
                mappingValue =
                    mapOf(
                        "methodName" to analysisItem.symbol.name,
                        "methodSignature" to "${analysisItem.symbol.name}()",
                        "parentClass" to (analysisItem.symbol.parentClass ?: "Unknown"),
                        "filePath" to analysisItem.symbol.filePath,
                        "code" to (analysisItem.symbol.code ?: ""),
                    ),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to generate method summary for ${analysisItem.symbol.name}" }
            "Method ${analysisItem.symbol.name} in class ${analysisItem.symbol.parentClass}"
        }
    }

    private suspend fun generateClassSummary(classSymbol: JoernSymbol): String {
        // Generate class summary using LLM
        return try {
            llmGateway.callLlm(
                type = PromptTypeEnum.CLASS_SUMMARY,
                userPrompt = "",
                quick = true,
                responseSchema = "",
                mappingValue =
                    mapOf(
                        "className" to classSymbol.name,
                        "filePath" to classSymbol.filePath,
                        "code" to (classSymbol.code ?: ""),
                        "relations" to "",
                    ),
            )
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
                    documentType =
                        if (item.embeddingType == ModelType.EMBEDDING_CODE) {
                            RagDocumentType.CODE
                        } else {
                            RagDocumentType.METHOD_DESCRIPTION
                        },
                    ragSourceType = RagSourceType.JOERN,
                    pageContent = item.content,
                    source = "file://${symbol.filePath}",
                    path = symbol.filePath,
                    language = symbol.language,
                    className = symbol.parentClass,
                    methodName = symbol.name,
                    symbolName = symbol.name,
                    lineRange = symbol.lineRange,
                    embeddingType =
                        when (item.embeddingType) {
                            ModelType.EMBEDDING_CODE -> EmbeddingType.EMBEDDING_CODE
                            ModelType.EMBEDDING_TEXT -> EmbeddingType.EMBEDDING_TEXT
                            else -> EmbeddingType.EMBEDDING_TEXT
                        },
                    joernNodeId = symbol.joernNodeId,
                    relations = symbol.relations,
                )
            }

            JoernSymbolType.CLASS -> {
                RagDocument(
                    projectId = project.id,
                    clientId = project.clientId,
                    documentType = RagDocumentType.CLASS_SUMMARY,
                    ragSourceType = RagSourceType.CLASS,
                    pageContent = item.content,
                    source = "class://${project.name}/${symbol.name}",
                    path = symbol.filePath,
                    language = symbol.language,
                    packageName = symbol.packageName,
                    className = symbol.name,
                    symbolName = symbol.name,
                    lineRange = symbol.lineRange,
                    embeddingType = EmbeddingType.EMBEDDING_TEXT,
                    joernNodeId = symbol.joernNodeId,
                    relations = symbol.relations,
                )
            }

            else -> {
                RagDocument(
                    projectId = project.id,
                    clientId = project.clientId,
                    documentType = RagDocumentType.CODE,
                    ragSourceType = RagSourceType.JOERN,
                    pageContent = item.content,
                    source = "file://${symbol.filePath}",
                    path = symbol.filePath,
                    language = symbol.language,
                    symbolName = symbol.name,
                    embeddingType =
                        when (item.embeddingType) {
                            ModelType.EMBEDDING_CODE -> EmbeddingType.EMBEDDING_CODE
                            ModelType.EMBEDDING_TEXT -> EmbeddingType.EMBEDDING_TEXT
                            else -> EmbeddingType.EMBEDDING_TEXT
                        },
                    joernNodeId = symbol.joernNodeId,
                    relations = symbol.relations,
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
}
