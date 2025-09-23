package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.EmbeddingType
import com.jervis.domain.rag.LineRange
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.domain.rag.SymbolRelation
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.indexing.pipeline.IndexingPipelineService
import com.jervis.service.indexing.dto.ClassSummaryResponse
import com.jervis.service.indexing.dto.MethodSummaryResponse
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingProgress
import com.jervis.service.indexing.monitoring.IndexingStepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * Service for Joern-based method-level chunking and analysis according to the specification.
 * Implements:
 * - CPG generation and analysis
 * - Namespace -> Class -> Method extraction
 * - Method chunking (300-400 lines max)
 * - Class summary generation
 * - Relation extraction (call graph, inheritance, imports)
 */
@Service
class JoernChunkingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    private val historicalVersioningService: HistoricalVersioningService,
    private val indexingMonitorService: IndexingMonitorService,
    private val joernAnalysisService: JoernAnalysisService,
    private val indexingPipelineService: IndexingPipelineService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Data class to hold chunk processing metadata for batch processing
     */
    private data class ChunkMetadata(
        val index: Int,
        val chunk: JoernSymbol,
        val chunkId: String?,
        val methodSummary: String? = null
    )

    /**
     * Result of Joern chunking analysis
     */
    data class JoernChunkingResult(
        val processedMethods: Int,
        val processedClasses: Int,
        val generatedChunks: Int,
        val skippedItems: Int,
        val errorItems: Int,
    )

    /**
     * Intermediate representation of extracted symbols from Joern
     */
    data class JoernSymbol(
        val type: SymbolType,
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
    )

    enum class SymbolType {
        NAMESPACE,
        CLASS,
        METHOD,
        FUNCTION,
    }

    /**
     * Estimate token count for text/code content
     * Uses a simple approximation: roughly 4 characters per token for code
     */
    private fun estimateTokenCount(text: String): Int = (text.length / 4.0).toInt()

    /**
     * Main entry point for Joern-based chunking analysis with pipeline option
     */
    suspend fun performJoernChunking(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
        usePipeline: Boolean = false // Add pipeline option
    ): JoernChunkingResult {
        return if (usePipeline && shouldUsePipeline(project)) {
            performJoernChunkingWithPipeline(project, projectPath, joernDir)
        } else {
            performJoernChunkingWithBatch(project, projectPath, joernDir)
        }
    }

    /**
     * Determine if pipeline processing should be used based on project characteristics
     */
    private fun shouldUsePipeline(project: ProjectDocument): Boolean {
        // Enable pipeline for larger projects or when explicitly configured
        val indexingRules = project.indexingRules
        
        return when {
            // Check if pipeline is explicitly enabled in project settings
            indexingRules.usePipelineIndexing == true -> true
            
            // Default: use pipeline for larger projects (more than 100 files estimated)
            // This is a heuristic - in practice you might want more sophisticated logic
            else -> false
        }
    }

    /**
     * Pipeline-based Joern chunking using streaming processing
     */
    private suspend fun performJoernChunkingWithPipeline(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
    ): JoernChunkingResult {
        logger.info { "PIPELINE_JOERN: Starting pipeline-based Joern chunking for project: ${project.name}" }
        
        return try {
            // Use IndexingPipelineService for streaming processing
            val pipelineResult = indexingPipelineService.indexProjectWithPipeline(project, projectPath)
            
            logger.info { 
                "PIPELINE_JOERN: Pipeline processing completed - " +
                "processed: ${pipelineResult.totalProcessed}, errors: ${pipelineResult.totalErrors}, " +
                "time: ${pipelineResult.processingTimeMs}ms, throughput: ${"%.2f".format(pipelineResult.throughput)} items/sec"
            }
            
            // Convert pipeline result to JoernChunkingResult format
            JoernChunkingResult(
                processedMethods = pipelineResult.totalProcessed / 2, // Estimate methods (roughly half of processed items)
                processedClasses = pipelineResult.totalProcessed / 4,  // Estimate classes (roughly quarter of processed items)
                generatedChunks = pipelineResult.totalProcessed,
                skippedItems = 0,
                errorItems = pipelineResult.totalErrors
            )
        } catch (e: Exception) {
            logger.error(e) { "PIPELINE_JOERN: Pipeline processing failed, falling back to batch" }
            
            // Fallback to batch processing on pipeline failure
            performJoernChunkingWithBatch(project, projectPath, joernDir)
        }
    }

    /**
     * Original batch-based Joern chunking analysis (renamed for clarity)
     */
    private suspend fun performJoernChunkingWithBatch(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
    ): JoernChunkingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting Joern chunking analysis for project: ${project.name}" }

            // Report start of Joern analysis
            indexingMonitorService.updateStepProgress(
                project.id,
                "joern_analysis",
                IndexingStepStatus.RUNNING,
                message = "Starting Joern code analysis and method chunking...",
            )

            try {
                // Get current git commit hash
                val gitCommitHash = historicalVersioningService.getCurrentGitCommitHash(projectPath)

                // Step 1: Generate CPG and extract symbols
                indexingMonitorService.updateStepProgress(
                    project.id,
                    "joern_analysis",
                    IndexingStepStatus.RUNNING,
                    message = "Generating Joern CPG and extracting symbols...",
                )

                val symbols = extractSymbolsFromJoern(projectPath, joernDir)
                logger.info { "Extracted ${symbols.size} symbols from Joern CPG" }

                indexingMonitorService.updateStepProgress(
                    project.id,
                    "joern_analysis",
                    IndexingStepStatus.RUNNING,
                    message = "Extracted ${symbols.size} symbols from Joern CPG",
                )

                // Step 2: Group symbols by type
                val namespaces = symbols.filter { it.type == SymbolType.NAMESPACE }
                val classes = symbols.filter { it.type == SymbolType.CLASS }
                val methods = symbols.filter { it.type == SymbolType.METHOD || it.type == SymbolType.FUNCTION }

                logger.info { "Found ${namespaces.size} namespaces, ${classes.size} classes, ${methods.size} methods" }

                // Step 3: Process in parallel
                indexingMonitorService.updateStepProgress(
                    project.id,
                    "joern_analysis",
                    IndexingStepStatus.RUNNING,
                    progress = IndexingProgress(0, classes.size + methods.size),
                    message = "Processing ${classes.size} classes and ${methods.size} methods...",
                )

                val results =
                    listOf(
                        async { processClasses(project, classes, gitCommitHash) },
                        async { processMethods(project, methods, gitCommitHash) },
                    ).awaitAll()

                val totalProcessed = results.sumOf { it.processedMethods + it.processedClasses }
                val totalChunks = results.sumOf { it.generatedChunks }
                val totalSkipped = results.sumOf { it.skippedItems }
                val totalErrors = results.sumOf { it.errorItems }

                val result =
                    JoernChunkingResult(
                        processedMethods = results.sumOf { it.processedMethods },
                        processedClasses = results.sumOf { it.processedClasses },
                        generatedChunks = totalChunks,
                        skippedItems = totalSkipped,
                        errorItems = totalErrors,
                    )

                // Report completion
                indexingMonitorService.updateStepProgress(
                    project.id,
                    "joern_analysis",
                    IndexingStepStatus.COMPLETED,
                    progress = IndexingProgress(totalProcessed, totalProcessed),
                    message = "Joern analysis completed: ${result.processedClasses} classes, ${result.processedMethods} methods, ${result.generatedChunks} chunks generated",
                )

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during Joern chunking analysis for project: ${project.name}" }

                // Report failure
                indexingMonitorService.updateStepProgress(
                    project.id,
                    "joern_analysis",
                    IndexingStepStatus.FAILED,
                    errorMessage = "Joern chunking analysis failed: ${e.message}",
                )

                JoernChunkingResult(0, 0, 0, 0, 1)
            }
        }

    /**
     * Extract symbols from Joern CPG
     */
    private suspend fun extractSymbolsFromJoern(
        projectPath: Path,
        joernDir: Path,
    ): List<JoernSymbol> {
        val symbols = mutableListOf<JoernSymbol>()

        try {
            logger.info { "Starting symbol extraction from Joern CPG for project: ${projectPath.fileName}" }

            // Ensure CPG exists first
            val cpgPath = joernDir.resolve("cpg.bin")
            logger.debug { "Checking for existing CPG at: $cpgPath" }

            val cpgExists = joernAnalysisService.ensureCpgExists(projectPath, cpgPath)

            if (!cpgExists) {
                logger.error { "Failed to create or find CPG for project: $projectPath" }
                return emptyList()
            }

            logger.info { "CPG verified at: $cpgPath (size: ${Files.size(cpgPath)} bytes)" }

            // Generate Joern script for symbol extraction
            logger.debug { "Generating symbol extraction script" }
            val extractionScript = createSymbolExtractionScript(cpgPath)
            val scriptFile = joernDir.resolve("symbol_extraction.sc")

            withContext(Dispatchers.IO) {
                Files.writeString(scriptFile, extractionScript)
            }

            logger.info { "Created symbol extraction script at: $scriptFile" }
            logger.debug { "Script content (${extractionScript.length} chars):\n$extractionScript" }

            // Execute Joern analysis using proper process execution
            logger.info { "Executing Joern symbol extraction script..." }
            val result = executeJoernScript(projectPath, joernDir, scriptFile)

            if (result.isNotEmpty()) {
                logger.info { "Received ${result.length} characters of output from Joern script" }
                val lines = result.lines().size
                val jsonLines = result.lines().count { it.trim().startsWith("{") && it.trim().endsWith("}") }
                logger.info { "Output contains $lines total lines, $jsonLines appear to be JSON" }

                symbols.addAll(parseJoernSymbolResults(result))
                logger.info { "Successfully extracted ${symbols.size} symbols from Joern CPG" }

                // Log summary by type
                val namespaceCount = symbols.count { it.type == SymbolType.NAMESPACE }
                val classCount = symbols.count { it.type == SymbolType.CLASS }
                val methodCount = symbols.count { it.type == SymbolType.METHOD }
                val functionCount = symbols.count { it.type == SymbolType.FUNCTION }
                logger.info {
                    "Symbol breakdown: $namespaceCount namespaces, $classCount classes, $methodCount methods, $functionCount functions"
                }
            } else {
                logger.error { "No symbol extraction results received from Joern script execution" }

                // Check if script execution failed by looking for error files
                val errorFiles =
                    Files
                        .list(joernDir)
                        .filter { it.fileName.toString().contains("error") }
                        .toList()

                if (errorFiles.isNotEmpty()) {
                    logger.error { "Found ${errorFiles.size} error files in joern directory:" }
                    errorFiles.forEach { errorFile ->
                        logger.error { "Error file: $errorFile" }
                        try {
                            val errorContent = Files.readString(errorFile).take(500)
                            logger.error { "Error content: $errorContent..." }
                        } catch (e: Exception) {
                            logger.error(e) { "Could not read error file: $errorFile" }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error extracting symbols from Joern CPG for project: ${projectPath.fileName}" }
        }

        return symbols
    }

    /**
     * Process classes: generate summaries and index them
     */
    private suspend fun processClasses(
        project: ProjectDocument,
        classes: List<JoernSymbol>,
        gitCommitHash: String?,
    ): JoernChunkingResult {
        var processedClasses = 0
        var generatedChunks = 0
        var skippedItems = 0
        var errorItems = 0

        for (classSymbol in classes) {
            try {
                // Generate class summary using LLM
                val classSummary = generateClassSummary(project, classSymbol)

                if (classSummary.isNotBlank()) {
                    // Create RagDocument for class summary
                    val ragDocument =
                        RagDocument(
                            projectId = project.id,
                            clientId = project.clientId,
                            documentType = RagDocumentType.CLASS_SUMMARY,
                            ragSourceType = RagSourceType.JOERN,
                            pageContent = classSummary,
                            source = "file://${classSymbol.filePath}",
                            path = classSymbol.filePath,
                            language = classSymbol.language,
                            className = classSymbol.name,
                            symbolName = classSymbol.name,
                            lineRange = classSymbol.lineRange,
                            embeddingType = EmbeddingType.EMBEDDING_TEXT,
                            joernNodeId = classSymbol.joernNodeId,
                            relations = classSymbol.relations,
                            gitCommitHash = gitCommitHash,
                        )

                    // Generate and store embedding
                    val embedding =
                        embeddingGateway.callEmbedding(
                            ModelType.EMBEDDING_TEXT,
                            classSummary,
                        )

                    vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
                    generatedChunks++
                }

                processedClasses++
            } catch (e: Exception) {
                logger.error(e) { "Error processing class: ${classSymbol.name}" }
                errorItems++
            }
        }

        return JoernChunkingResult(0, processedClasses, generatedChunks, skippedItems, errorItems)
    }

    /**
     * Process methods: chunk if necessary and index them
     */
    private suspend fun processMethods(
        project: ProjectDocument,
        methods: List<JoernSymbol>,
        gitCommitHash: String?,
    ): JoernChunkingResult {
        var processedMethods = 0
        var generatedChunks = 0
        var skippedItems = 0
        var errorItems = 0

        for (methodSymbol in methods) {
            try {
                val methodCode = methodSymbol.code ?: continue
                val tokenCount = estimateTokenCount(methodCode)

                if (tokenCount <= 512) {
                    // Method is small enough, process as single chunk
                    val chunks = listOf(methodSymbol)
                    generatedChunks += processMethodChunks(project, chunks, null, gitCommitHash)
                } else {
                    // Method is too large, split into chunks
                    val chunks = splitMethodIntoChunks(methodSymbol)
                    generatedChunks += processMethodChunks(project, chunks, methodSymbol.name, gitCommitHash)
                }

                processedMethods++
            } catch (e: Exception) {
                logger.error(e) { "Error processing method: ${methodSymbol.name}" }
                errorItems++
            }
        }

        return JoernChunkingResult(processedMethods, 0, generatedChunks, skippedItems, errorItems)
    }

    /**
     * Process method chunks and create RagDocuments using individual embedding calls
     */
    private suspend fun processMethodChunks(
        project: ProjectDocument,
        chunks: List<JoernSymbol>,
        parentMethodName: String?,
        gitCommitHash: String?,
    ): Int {
        if (chunks.isEmpty()) {
            return 0
        }

        var generatedChunks = 0

        for ((index, chunk) in chunks.withIndex()) {
            try {
                val chunkId = if (parentMethodName != null) "${parentMethodName}_chunk_$index" else null

                // Generate method-level code embedding
                val codeEmbedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_CODE, chunk.code ?: "")

                // Create RagDocument for method code
                val codeDocument = RagDocument(
                    projectId = project.id,
                    clientId = project.clientId,
                    documentType = RagDocumentType.CODE,
                    ragSourceType = RagSourceType.JOERN,
                    pageContent = chunk.code ?: "",
                    source = "file://${chunk.filePath}",
                    path = chunk.filePath,
                    language = chunk.language,
                    className = chunk.parentClass,
                    methodName = chunk.name,
                    symbolName = chunk.name,
                    lineRange = chunk.lineRange,
                    embeddingType = EmbeddingType.EMBEDDING_CODE,
                    joernNodeId = chunk.joernNodeId,
                    relations = chunk.relations,
                    chunkId = chunkId,
                    gitCommitHash = gitCommitHash,
                )

                vectorStorage.store(ModelType.EMBEDDING_CODE, codeDocument, codeEmbedding)

                // Generate sentence-level summary
                val methodSummary = generateMethodSummary(project, chunk)
                if (methodSummary.isNotBlank()) {
                    val textEmbedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, methodSummary)

                    val summaryDocument = RagDocument(
                        projectId = project.id,
                        clientId = project.clientId,
                        documentType = RagDocumentType.METHOD_DESCRIPTION,
                        ragSourceType = RagSourceType.JOERN,
                        pageContent = methodSummary,
                        source = "file://${chunk.filePath}",
                        path = chunk.filePath,
                        language = chunk.language,
                        className = chunk.parentClass,
                        methodName = chunk.name,
                        symbolName = chunk.name,
                        lineRange = chunk.lineRange,
                        embeddingType = EmbeddingType.EMBEDDING_TEXT,
                        joernNodeId = chunk.joernNodeId,
                        relations = chunk.relations,
                        chunkId = chunkId,
                        gitCommitHash = gitCommitHash,
                    )

                    vectorStorage.store(ModelType.EMBEDDING_TEXT, summaryDocument, textEmbedding)
                }

                generatedChunks++
            } catch (e: Exception) {
                logger.error(e) { "Error processing method chunk: ${chunk.name}" }
            }
        }

        return generatedChunks
    }


    /**
     * Split large method into smaller chunks based on token limits (max 512 tokens per chunk)
     */
    private suspend fun splitMethodIntoChunks(method: JoernSymbol): List<JoernSymbol> {
        // This would use Joern to identify Block and ControlStructure nodes
        // For now, implementing a simple token-based splitting as a fallback
        val code = method.code ?: return listOf(method)
        val lines = code.lines()
        val chunks = mutableListOf<JoernSymbol>()

        var currentStart = method.lineRange.start
        var currentChunkLines = mutableListOf<String>()
        val maxTokens = 450 // Leave some margin below 512 tokens

        for (i in lines.indices) {
            currentChunkLines.add(lines[i])
            val currentChunkCode = currentChunkLines.joinToString("\n")
            val currentTokens = estimateTokenCount(currentChunkCode)

            if (currentTokens >= maxTokens || i == lines.lastIndex) {
                val chunkEnd = currentStart + currentChunkLines.size - 1

                chunks.add(
                    method.copy(
                        lineRange = LineRange(currentStart, chunkEnd),
                        code = currentChunkCode,
                        joernNodeId = "${method.joernNodeId}_chunk_${chunks.size}",
                    ),
                )

                currentStart = chunkEnd + 1
                currentChunkLines.clear()
            }
        }

        return chunks.ifEmpty { listOf(method) }
    }

    /**
     * Generate class summary using LLM
     */
    private suspend fun generateClassSummary(
        project: ProjectDocument,
        classSymbol: JoernSymbol,
    ): String =
        try {
            val response =
                llmGateway.callLlm(
                    type = PromptTypeEnum.CLASS_SUMMARY,
                    userPrompt = "",
                    quick = false,
                    responseSchema = ClassSummaryResponse(),
                    mappingValue =
                        mapOf(
                            "className" to classSymbol.name,
                            "filePath" to classSymbol.filePath,
                            "code" to (classSymbol.code ?: "No code available"),
                            "relations" to classSymbol.relations.joinToString { "${it.type}: ${it.targetSymbol}" },
                        ),
                )

            response.summary
        } catch (e: Exception) {
            logger.error(e) { "Error generating class summary for: ${classSymbol.name}" }
            ""
        }

    /**
     * Generate method summary (sentence-level)
     */
    private suspend fun generateMethodSummary(
        project: ProjectDocument,
        methodSymbol: JoernSymbol,
    ): String =
        try {
            val response =
                llmGateway.callLlm(
                    type = PromptTypeEnum.METHOD_SUMMARY,
                    userPrompt = "",
                    quick = false,
                    responseSchema = MethodSummaryResponse(),
                    mappingValue =
                        mapOf(
                            "methodName" to methodSymbol.name,
                            "methodSignature" to (methodSymbol.signature ?: methodSymbol.name),
                            "parentClass" to (methodSymbol.parentClass ?: "Unknown"),
                            "filePath" to methodSymbol.filePath,
                            "code" to (methodSymbol.code ?: "No code available"),
                        ),
                )

            response.summary
        } catch (e: Exception) {
            logger.error(e) { "Error generating method summary for: ${methodSymbol.name}" }
            ""
        }

    /**
     * Create Joern script for symbol extraction
     */
    private fun createSymbolExtractionScript(cpgPath: Path): String {
        val cpgPathString = cpgPath.toString()
        return buildString {
            appendLine("importCpg(\"$cpgPathString\")")
            appendLine()
            appendLine("// Get all namespaces - simplified approach that works")
            appendLine("val namespaceData = cpg.namespace.map { ns =>")
            appendLine(
                "  s\"{\\\"type\\\":\\\"NAMESPACE\\\",\\\"name\\\":\\\"\${ns.name}\\\",\\\"fullName\\\":\\\"\${ns.name}\\\",\\\"file\\\":\\\"\\\",\\\"nodeId\\\":\\\"\${ns.id}\\\"}\"",
            )
            appendLine("}.toList")
            appendLine()
            appendLine("// Get all classes - using working API calls")
            appendLine("val classData = cpg.typeDecl.filterNot(_.isExternal).map { cls =>")
            appendLine("  val lineStart = cls.lineNumber.getOrElse(0)")
            appendLine(
                "  s\"{\\\"type\\\":\\\"CLASS\\\",\\\"name\\\":\\\"\${cls.name}\\\",\\\"fullName\\\":\\\"\${cls.fullName}\\\",\\\"file\\\":\\\"\${cls.filename}\\\",\\\"lineStart\\\":\$lineStart,\\\"lineEnd\\\":\$lineStart,\\\"nodeId\\\":\\\"\${cls.id}\\\",\\\"inherits\\\":[],\\\"namespace\\\":\\\"\\\"}\"",
            )
            appendLine("}.toList")
            appendLine()
            appendLine("// Get all methods - using working API calls only")
            appendLine("val methodData = cpg.method.filterNot(_.isExternal).map { method =>")
            appendLine("  val lineStart = method.lineNumber.getOrElse(0)")
            appendLine("  val parentClass = method.typeDecl.name.headOption.getOrElse(\"\")")
            appendLine(
                "  s\"{\\\"type\\\":\\\"METHOD\\\",\\\"name\\\":\\\"\${method.name}\\\",\\\"fullName\\\":\\\"\${method.fullName}\\\",\\\"signature\\\":\\\"\${method.signature}\\\",\\\"file\\\":\\\"\${method.filename}\\\",\\\"lineStart\\\":\$lineStart,\\\"lineEnd\\\":\$lineStart,\\\"nodeId\\\":\\\"\${method.id}\\\",\\\"parentClass\\\":\\\"\$parentClass\\\",\\\"calls\\\":[],\\\"calledBy\\\":[],\\\"code\\\":\\\"\\\"}\"",
            )
            appendLine("}.toList")
            appendLine()
            appendLine("// Output all data as JSON")
            appendLine("val allData = namespaceData ++ classData ++ methodData")
            appendLine("allData.foreach(println)")
        }
    }

    /**
     * Execute Joern script with retry logic and robust error handling
     */
    private suspend fun executeJoernScript(
        projectPath: Path,
        joernDir: Path,
        scriptFile: Path,
        maxRetries: Int = 3,
    ): String =
        withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            var attempt = 0

            while (attempt < maxRetries) {
                attempt++

                try {
                    logger.debug { "Executing Joern script (attempt $attempt/$maxRetries)" }

                    val processBuilder =
                        ProcessBuilder(
                            "joern",
                            "--script",
                            scriptFile.toString(),
                        ).apply {
                            directory(joernDir.toFile())
                            environment().apply {
                                // Ensure Joern has proper environment
                                put("JAVA_OPTS", "-Xmx4g -Xms1g") // Ensure adequate memory
                                put("PATH", System.getenv("PATH")) // Preserve PATH
                            }
                        }

                    val process = processBuilder.start()

                    // Use timeout to avoid hanging - adaptive timeout based on project size
                    val timeoutMinutes =
                        when {
                            Files.exists(projectPath) && estimateProjectSize(projectPath) > 100000 -> 45L
                            else -> 30L
                        }

                    val finished = process.waitFor(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)

                    if (!finished) {
                        process.destroyForcibly()
                        val errorMsg =
                            "Joern script execution timed out after $timeoutMinutes minutes (attempt $attempt)"
                        logger.error { errorMsg }

                        if (attempt < maxRetries) {
                            logger.info { "Retrying Joern execution in 10 seconds..." }
                            kotlinx.coroutines.delay(10000) // Wait before retry
                            continue
                        } else {
                            return@withContext ""
                        }
                    }

                    val exitCode = process.exitValue()
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    val error = process.errorStream.bufferedReader().use { it.readText() }

                    if (exitCode == 0) {
                        logger.debug { "Joern script executed successfully on attempt $attempt" }
                        return@withContext output
                    } else {
                        val errorMsg = "Joern script execution failed (exit=$exitCode) on attempt $attempt"
                        logger.warn { errorMsg }

                        // Log detailed error information
                        val errorFile = joernDir.resolve("symbol_extraction_error_attempt_$attempt.txt")
                        Files.writeString(
                            errorFile,
                            "Attempt: $attempt/$maxRetries\nExit code: $exitCode\nStderr:\n$error\n\nStdout:\n$output",
                        )

                        if (attempt < maxRetries) {
                            logger.info { "Retrying Joern execution in 15 seconds... (error details: $errorFile)" }
                            kotlinx.coroutines.delay(15000) // Wait longer for exit code failures
                            continue
                        } else {
                            logger.error { "All Joern execution attempts failed. Final error details: $errorFile" }
                            return@withContext ""
                        }
                    }
                } catch (e: Exception) {
                    lastException = e
                    logger.warn(e) { "Error executing Joern script on attempt $attempt" }

                    if (attempt < maxRetries) {
                        logger.info { "Retrying Joern execution in 20 seconds due to exception..." }
                        kotlinx.coroutines.delay(20000) // Wait longest for exceptions
                    } else {
                        logger.error(e) { "All Joern execution attempts failed due to exceptions" }
                        return@withContext ""
                    }
                }
            }

            // This should never be reached, but just in case
            logger.error(lastException) { "Exhausted all retry attempts for Joern execution" }
            return@withContext ""
        }

    /**
     * Estimate project size for adaptive timeout
     */
    private suspend fun estimateProjectSize(projectPath: Path): Long =
        withContext(Dispatchers.IO) {
            try {
                Files
                    .walk(projectPath)
                    .filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }
                    .mapToLong { Files.size(it) }
                    .sum()
            } catch (e: Exception) {
                logger.debug(e) { "Could not estimate project size, using default" }
                50000L // Default estimate
            }
        }

    /**
     * Parse Joern symbol results from script output
     */
    private suspend fun parseJoernSymbolResults(output: String): List<JoernSymbol> {
        val symbols = mutableListOf<JoernSymbol>()

        logger.debug { "Parsing Joern symbol results from ${output.length} characters of output" }

        // Log the raw output for debugging
        if (output.isBlank()) {
            logger.warn { "Joern script returned empty output - no symbols to parse" }
            return emptyList()
        }

        // Save output to file for debugging
        try {
            val debugFile =
                java.nio.file.Paths
                    .get(System.getProperty("java.io.tmpdir"), "joern_symbol_output.txt")
            Files
                .writeString(debugFile, output)
            logger.debug { "Raw Joern output saved to: $debugFile" }
        } catch (e: Exception) {
            logger.debug(e) { "Could not save debug output" }
        }

        val lines = output.lines().filter { it.trim().isNotEmpty() }
        logger.debug { "Processing ${lines.size} non-empty lines from Joern output" }

        for ((index, line) in lines.withIndex()) {
            try {
                // Skip non-JSON lines (warnings, info messages, etc.)
                val trimmedLine = line.trim()
                if (!trimmedLine.startsWith("{") || !trimmedLine.endsWith("}")) {
                    logger.debug { "Skipping non-JSON line $index: ${trimmedLine.take(100)}..." }
                    continue
                }

                val symbol = parseSymbolLine(trimmedLine)
                if (symbol != null) {
                    symbols.add(symbol)
                    logger.debug { "Successfully parsed symbol: ${symbol.type} ${symbol.name} from ${symbol.filePath}" }
                } else {
                    logger.debug { "Failed to parse symbol from line $index: ${trimmedLine.take(200)}..." }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error parsing symbol line $index: ${line.take(200)}..." }
            }
        }

        logger.info {
            "Parsed ${symbols.size} symbols from Joern output (${
                symbols.count {
                    it.type == SymbolType.CLASS
                }
            } classes, ${symbols.count { it.type == SymbolType.METHOD }} methods)"
        }
        return symbols
    }

    /**
     * Parse individual symbol line from JSON output
     */
    private suspend fun parseSymbolLine(line: String): JoernSymbol? {
        return try {
            // Parse JSON line - basic implementation using string parsing
            if (!line.trim().startsWith("{") || !line.trim().endsWith("}")) {
                logger.debug { "Skipping non-JSON line: ${line.take(100)}..." }
                return null
            }

            val json = line.trim()
            logger.debug { "Parsing JSON: ${json.take(200)}..." }

            val type = extractJsonValue(json, "type")
            val name = extractJsonValue(json, "name")
            val fullName = extractJsonValue(json, "fullName")
            val file = extractJsonValue(json, "file")
            val nodeId = extractJsonValue(json, "nodeId")
            val lineStartStr = extractJsonValue(json, "lineStart")
            val lineEndStr = extractJsonValue(json, "lineEnd")
            val signature = extractJsonValue(json, "signature")
            val parentClass = extractJsonValue(json, "parentClass")

            logger.debug { "Extracted values - type: '$type', name: '$name', file: '$file'" }

            // Validate required fields
            if (type.isEmpty() || name.isEmpty()) {
                logger.debug { "Missing required fields - type: '$type', name: '$name'" }
                return null
            }

            val symbolType =
                when (type.uppercase()) {
                    "NAMESPACE" -> SymbolType.NAMESPACE
                    "CLASS" -> SymbolType.CLASS
                    "METHOD" -> SymbolType.METHOD
                    "FUNCTION" -> SymbolType.FUNCTION
                    else -> {
                        logger.debug { "Unknown symbol type: '$type'" }
                        return null
                    }
                }

            val lineStart = lineStartStr.toIntOrNull() ?: 0
            val lineEnd = lineEndStr.toIntOrNull() ?: lineStart

            // Determine language from file extension
            val language =
                when {
                    file.endsWith(".kt") -> "kotlin"
                    file.endsWith(".java") -> "java"
                    file.endsWith(".scala") -> "scala"
                    else -> "java" // Default
                }

            val symbol =
                JoernSymbol(
                    type = symbolType,
                    name = name,
                    fullName = fullName,
                    signature = if (signature.isNotEmpty()) signature else null,
                    filePath = file,
                    lineRange = LineRange(lineStart, lineEnd),
                    code = null, // We don't extract code in this simplified version
                    joernNodeId = nodeId,
                    language = language,
                    relations = emptyList(),
                    parentClass = if (parentClass.isNotEmpty()) parentClass else null,
                    namespace = null, // We don't extract namespace in this version
                )

            logger.debug { "Successfully created symbol: ${symbol.type} '${symbol.name}' in ${symbol.filePath}" }
            return symbol
        } catch (e: Exception) {
            logger.warn(e) { "Error parsing symbol JSON: ${line.take(200)}..." }
            return null
        }
    }

    /**
     * Simple JSON value extractor for basic parsing
     */
    private fun extractJsonValue(
        json: String,
        key: String,
    ): String =
        try {
            val pattern = "\"$key\":\"([^\"]*)\""
            val regex = Regex(pattern)
            val matchResult = regex.find(json)
            matchResult?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
}
