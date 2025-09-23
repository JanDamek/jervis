package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.indexing.dto.JoernAnalysisResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Service for performing extensive Joern analysis and indexing all possible analysis results.
 * This meets requirement: "Po dokončení JOERN analýzy, se provedou veškeré analitické volání na výsledke, které JOERN nabízí"
 * "Každá možná analýza, bude křížem, vše co půjde, popsat do RAG TEXT naprosto vše co bude možné pro projekt"
 */
@Service
class ExtensiveJoernAnalysisService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    private val indexingMonitorService: com.jervis.service.indexing.monitoring.IndexingMonitorService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of process execution including outputs and timeout status
     */
    data class ProcessRunResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /**
     * Run a process with streaming output and timeout handling
     */
    private suspend fun runProcessStreaming(
        displayName: String,
        command: List<String>,
        workingDir: Path?,
        redirectErrorStream: Boolean = false,
    ): ProcessRunResult =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(command)
            workingDir?.let { pb.directory(it.toFile()) }
            pb.redirectErrorStream(redirectErrorStream)

            logger.debug {
                "[PROC] Starting: $displayName | cmd=${
                    command.joinToString(
                        " ",
                    )
                } | dir=${workingDir?.pathString ?: "(default)"}"
            }

            val process = pb.start()
            process.pid()
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()

            val stdoutThread =
                Thread({
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            stdoutBuf.appendLine(line)
                            logger.debug { "[$displayName][stdout] $line" }
                        }
                    }
                }, "$displayName-stdout").apply { isDaemon = true }

            val stderrThread =
                if (!redirectErrorStream) {
                    Thread({
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                stderrBuf.appendLine(line)
                                logger.error { "[$displayName][stderr] $line" }
                            }
                        }
                    }, "$displayName-stderr").apply { isDaemon = true }
                } else {
                    null
                }

            stdoutThread.start()
            stderrThread?.start()

            process.waitFor()

            // wait for streams to be read (short join)
            runCatching { stdoutThread.join(1000) }
            runCatching { stderrThread?.join(1000) }

            val exit = process.exitValue()
            logger.debug { "[PROC] Finished: $displayName | exit=$exit" }

            return@withContext ProcessRunResult(
                exitCode = exit,
                stdout = stdoutBuf.toString(),
                stderr = stderrBuf.toString(),
            )
        }

    data class JoernAnalysisResult(
        val processedAnalyses: Int,
        val skippedAnalyses: Int,
        val errorAnalyses: Int,
    )

    /**
     * Perform extensive Joern analysis and index all results
     */
    suspend fun performExtensiveJoernAnalysis(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
    ): JoernAnalysisResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Starting extensive Joern analysis for project: ${project.name}" }

                var processedAnalyses = 0
                var skippedAnalyses = 0
                var errorAnalyses = 0

                // List of all comprehensive Joern analyses to perform
                val analyses =
                    listOf(
                        JoernAnalysisConfig(
                            "security-vulnerabilities",
                            "Security Vulnerability Analysis",
                            createSecurityAnalysisScript(),
                            "Comprehensive security vulnerability analysis including SQL injection, XSS, CSRF, and other common vulnerabilities",
                        ),
                        JoernAnalysisConfig(
                            "code-quality-metrics",
                            "Code Quality Metrics Analysis",
                            createCodeQualityScript(),
                            "Code quality metrics including cyclomatic complexity, method length, class coupling, and maintainability indices",
                        ),
                        JoernAnalysisConfig(
                            "dependency-analysis",
                            "Dependency and Import Analysis",
                            createDependencyAnalysisScript(),
                            "Comprehensive analysis of project dependencies, imports, and external library usage patterns",
                        ),
                        JoernAnalysisConfig(
                            "architecture-analysis",
                            "Architecture and Design Pattern Analysis",
                            createArchitectureAnalysisScript(),
                            "Analysis of architectural patterns, design patterns, and structural relationships between components",
                        ),
                        JoernAnalysisConfig(
                            "data-flow-analysis",
                            "Data Flow and Control Flow Analysis",
                            createDataFlowAnalysisScript(),
                            "Comprehensive data flow analysis tracking how data moves through the application and identifying potential issues",
                        ),
                        JoernAnalysisConfig(
                            "api-surface-analysis",
                            "API Surface and Interface Analysis",
                            createApiSurfaceAnalysisScript(),
                            "Analysis of public APIs, interfaces, and exposed functionality including REST endpoints and service interfaces",
                        ),
                        JoernAnalysisConfig(
                            "performance-analysis",
                            "Performance and Resource Usage Analysis",
                            createPerformanceAnalysisScript(),
                            "Analysis of performance hotspots, resource usage patterns, and potential optimization opportunities",
                        ),
                        JoernAnalysisConfig(
                            "configuration-analysis",
                            "Configuration and Environment Analysis",
                            createConfigurationAnalysisScript(),
                            "Analysis of configuration files, environment variables, and deployment-related settings",
                        ),
                        JoernAnalysisConfig(
                            "testing-analysis",
                            "Test Coverage and Quality Analysis",
                            createTestingAnalysisScript(),
                            "Analysis of test coverage, test quality, and testing patterns used in the project",
                        ),
                        JoernAnalysisConfig(
                            "business-logic-analysis",
                            "Business Logic and Domain Analysis",
                            createBusinessLogicAnalysisScript(),
                            "Analysis of business logic, domain models, and core functionality implementation",
                        ),
                    )

                // Execute each analysis
                for ((index, analysisConfig) in analyses.withIndex()) {
                    try {
                        indexingMonitorService.addStepLog(
                            project.id,
                            "joern_analysis",
                            "Starting ${analysisConfig.displayName} (${index + 1}/${analyses.size})",
                        )

                        val success = performJoernAnalysis(project, projectPath, joernDir, analysisConfig)

                        if (success) {
                            processedAnalyses++
                            indexingMonitorService.addStepLog(
                                project.id,
                                "joern_analysis",
                                "✓ Completed ${analysisConfig.displayName}",
                            )
                        } else {
                            errorAnalyses++
                            indexingMonitorService.addStepLog(
                                project.id,
                                "joern_analysis",
                                "✗ Failed ${analysisConfig.displayName}",
                            )
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to perform Joern analysis: ${analysisConfig.name}" }
                        errorAnalyses++
                        indexingMonitorService.addStepLog(
                            project.id,
                            "joern_analysis",
                            "✗ Error in ${analysisConfig.displayName}: ${e.message}",
                        )
                    }
                }

                val result = JoernAnalysisResult(processedAnalyses, skippedAnalyses, errorAnalyses)
                logger.info {
                    "Extensive Joern analysis completed for project: ${project.name} - " +
                        "Processed: $processedAnalyses, Skipped: $skippedAnalyses, Errors: $errorAnalyses"
                }

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during extensive Joern analysis for project: ${project.name}" }
                JoernAnalysisResult(0, 0, 1)
            }
        }

    /**
     * Perform a single Joern analysis and index its results
     */
    private suspend fun performJoernAnalysis(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
        analysisConfig: JoernAnalysisConfig,
    ): Boolean {
        try {
            logger.debug { "Performing Joern analysis: ${analysisConfig.displayName}" }

            // Ensure CPG exists before creating script
            val cpgPath = joernDir.resolve("cpg.bin")
            if (!Files
                    .exists(cpgPath)
            ) {
                logger.warn { "CPG file does not exist at: $cpgPath for analysis: ${analysisConfig.name}" }
                return false
            }

            // Create analysis script file with proper CPG loading
            val scriptFile = joernDir.resolve("${analysisConfig.name}.sc")
            val fullScriptContent =
                buildString {
                    appendLine("// Load the CPG first")
                    appendLine("importCpg(\"${cpgPath.pathString}\")")
                    appendLine()
                    appendLine("// Wait for CPG to be fully loaded")
                    appendLine("Thread.sleep(1000)")
                    appendLine()
                    appendLine("// Execute the analysis")
                    appendLine(analysisConfig.scriptContent)
                }
            Files.writeString(scriptFile, fullScriptContent)

            // Execute Joern analysis using robust process execution
            val res =
                runProcessStreaming(
                    displayName = "joern --script ${scriptFile.fileName}",
                    command = listOf("joern", "--script", scriptFile.pathString),
                    workingDir = joernDir,
                )

            return runCatching {
                if (res.exitCode == 0) {
                    // Process results from stdout
                    if (res.stdout.isNotBlank()) {
                        // Save results to file for reference
                        val outputFile = joernDir.resolve("${analysisConfig.name}-results.json")
                        Files.writeString(outputFile, res.stdout)

                        logger.debug { "Joern analysis '${analysisConfig.name}' completed successfully" }
                        indexJoernAnalysisResults(project, analysisConfig, res.stdout.trim())
                    } else {
                        logger.debug { "No results generated for analysis: ${analysisConfig.name}" }
                        false
                    }
                } else {
                    val errorFile = joernDir.resolve("${analysisConfig.name}_error.txt")
                    Files.writeString(
                        errorFile,
                        "Exit code: ${res.exitCode}\nStderr:\n${res.stderr}\n\nStdout:\n${res.stdout}",
                    )
                    logger.warn {
                        "Joern analysis '${analysisConfig.name}' failed (exit=${res.exitCode}, details: ${errorFile.pathString}"
                    }
                    false
                }
            }.also {
                // Clean up script file after execution
                runCatching { Files.deleteIfExists(scriptFile) }
            }.getOrDefault(false)
        } catch (e: Exception) {
            logger.error(e) { "Failed to perform Joern analysis: ${analysisConfig.name}" }
            return false
        }
    }

    /**
     * Index Joern analysis results as comprehensive TEXT descriptions
     */
    private suspend fun indexJoernAnalysisResults(
        project: ProjectDocument,
        analysisConfig: JoernAnalysisConfig,
        results: String,
    ): Boolean {
        try {
            // Generate comprehensive LLM-based description of the analysis results
            val analysisDescription = generateAnalysisDescription(project, analysisConfig, results)

            // Create embedding for the analysis description
            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, analysisDescription)

            // Create RAG document for the analysis
            val ragDocument =
                RagDocument(
                    projectId = project.id,
                    clientId = project.clientId,
                    documentType = RagDocumentType.JOERN_ANALYSIS,
                    ragSourceType = RagSourceType.ANALYSIS,
                    pageContent = analysisDescription,
                    source = "joern-analysis://${project.name}/${analysisConfig.name}",
                    path = "analysis/${analysisConfig.name}",
                    module = "joern-extensive-analysis",
                    language = "analysis-report",
                )

            // Store in TEXT vector store for semantic search
            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)

            logger.debug { "Successfully indexed Joern analysis: ${analysisConfig.name}" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to index Joern analysis results: ${analysisConfig.name}" }
            return false
        }
    }

    /**
     * Generate comprehensive LLM-based description of analysis results
     */
    private suspend fun generateAnalysisDescription(
        project: ProjectDocument,
        analysisConfig: JoernAnalysisConfig,
        results: String,
    ): String {
        val userPrompt =
            buildString {
                appendLine("Analyze these Joern static analysis results and provide a comprehensive description:")
                appendLine()
                appendLine("Analysis Type: ${analysisConfig.displayName}")
                appendLine("Project: ${project.name}")
                appendLine("Description: ${analysisConfig.description}")
                appendLine()
                appendLine("Analysis Results:")
                appendLine("```json")
                appendLine(results)
                appendLine("```")
            }

        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.EXTENSIVE_JOERN_ANALYSIS,
                userPrompt = userPrompt,
                quick = false,
                responseSchema = JoernAnalysisResponse(""),
            )

        return buildString {
            appendLine("Joern Analysis Report: ${analysisConfig.displayName}")
            appendLine("=".repeat(80))
            appendLine("Project: ${project.name}")
            appendLine("Analysis Type: ${analysisConfig.name}")
            appendLine("Generated: ${java.time.Instant.now()}")
            appendLine()
            appendLine("Analysis Description:")
            appendLine(analysisConfig.description)
            appendLine()
            appendLine("Comprehensive Analysis:")
            appendLine(llmResponse.response)
            appendLine()
            appendLine("Raw Analysis Results:")
            appendLine("```json")
            val resultsPreview =
                if (results.length > 3000) {
                    results.take(3000) + "\n... (results truncated, full analysis above)"
                } else {
                    results
                }
            appendLine(resultsPreview)
            appendLine("```")
            appendLine()
            appendLine("---")
            appendLine("Generated by: Extensive Joern Analysis System")
            appendLine("Analysis Engine: Joern Static Analysis Platform")
            appendLine("Enhanced with: LLM-based interpretation and recommendations")
            appendLine("Project: ${project.name}")
            appendLine("Indexed for: RAG Text Search, Security Assessment, Architecture Analysis")
        }
    }

    // Analysis script generators
    private fun createSecurityAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val securityAnalysis = Map(
            "sqlInjectionRisk" -> cpg.method.name(".*exec.*|.*query.*|.*prepare.*").l.map(_.fullName),
            "xssRisk" -> cpg.method.name(".*write.*|.*print.*|.*send.*").l.map(_.fullName),
            "pathTraversalRisk" -> cpg.method.name(".*File.*|.*Path.*|.*open.*").l.map(_.fullName),
            "hardcodedCredentials" -> cpg.literal.code(".*password.*|.*secret.*|.*key.*|.*token.*").l,
            "insecureRandom" -> cpg.method.name(".*Random.*|.*random.*").l.map(_.fullName),
            "weakCryptography" -> cpg.method.name(".*MD5.*|.*SHA1.*|.*DES.*").l.map(_.fullName),
            "totalMethods" -> cpg.method.l.size,
            "totalLiterals" -> cpg.literal.l.size
        )
        
        securityAnalysis.toJson
        """.trimIndent()

    private fun createCodeQualityScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val qualityMetrics = Map(
            "totalMethods" -> cpg.method.l.size,
            "totalClasses" -> cpg.typeDecl.l.size,
            "methodsWithManyControlStructures" -> cpg.method.l.filter(_.ast.isControlStructure.l.size > 10).size,
            "methodsWithManyAstNodes" -> cpg.method.l.filter(_.ast.l.size > 100).size,
            "publicMethods" -> cpg.method.isPublic.l.size,
            "privateMethods" -> cpg.method.isPrivate.l.size,
            "totalComments" -> cpg.comment.l.size,
            "methodsBySignature" -> cpg.method.groupBy(_.signature).mapValues(_.size),
            "totalImports" -> cpg.imports.l.size,
            "methodsWithManyParameters" -> cpg.method.l.filter(_.parameter.l.size > 5).size,
            "totalNamespaces" -> cpg.namespace.l.size
        )
        
        qualityMetrics.toJson
        """.trimIndent()

    private fun createDependencyAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val dependencyInfo = Map(
            "totalDependencies" -> cpg.dependency.l.size,
            "dependencyNames" -> cpg.dependency.l.map(_.name),
            "imports" -> cpg.imports.l.map(_.importedEntity),
            "internalTypes" -> cpg.typeDecl.internal.l.map(_.fullName),
            "externalTypes" -> cpg.typeDecl.external.l.map(_.fullName),
            "totalCalls" -> cpg.call.l.size,
            "callsByName" -> cpg.call.groupBy(_.name).mapValues(_.size),
            "packageStructure" -> cpg.namespace.l.map(_.name).distinct,
            "totalNamespaces" -> cpg.namespace.l.size,
            "fileCount" -> cpg.file.l.size
        )
        
        dependencyInfo.toJson
        """.trimIndent()

    private fun createArchitectureAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val architectureAnalysis = Map(
            "namespaces" -> cpg.namespace.l.map(_.name),
            "packageStructure" -> cpg.namespace.l.map(_.name).groupBy(_.split("\\.").headOption.getOrElse("default")),
            "designPatterns" -> Map(
                "singletonCandidates" -> cpg.method.name("getInstance").l.map(_.fullName),
                "factoryCandidates" -> cpg.method.name(".*create.*|.*build.*").l.map(_.fullName),
                "builderCandidates" -> cpg.typeDecl.fullName(".*Builder.*").l.map(_.fullName),
                "observerCandidates" -> cpg.method.name(".*notify.*|.*update.*|.*observe.*").l.map(_.fullName)
            ),
            "typeDeclarations" -> cpg.typeDecl.l.map(_.fullName),
            "methodDistribution" -> cpg.typeDecl.l.map(t => 
                t.fullName -> t.method.l.size
            ).toMap,
            "totalTypes" -> cpg.typeDecl.l.size,
            "totalMethods" -> cpg.method.l.size
        )
        
        architectureAnalysis.toJson
        """.trimIndent()

    private fun createDataFlowAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val dataFlowAnalysis = Map(
            "methodsWithStringParams" -> cpg.method.l.filter(_.parameter.typeFullName(".*String.*").nonEmpty).map(_.fullName),
            "databaseMethods" -> cpg.method.name(".*execute.*|.*query.*|.*update.*").l.map(_.fullName),
            "fileMethods" -> cpg.method.name(".*write.*|.*save.*|.*store.*").l.map(_.fullName),
            "networkMethods" -> cpg.method.name(".*send.*|.*post.*|.*get.*|.*request.*").l.map(_.fullName),
            "sensitiveIdentifiers" -> cpg.identifier.name(".*password.*|.*secret.*|.*token.*|.*key.*").l.map(_.code),
            "validationMethods" -> cpg.method.name(".*valid.*|.*check.*|.*verify.*").l.map(_.fullName),
            "sanitizationMethods" -> cpg.method.name(".*clean.*|.*sanitize.*|.*escape.*").l.map(_.fullName),
            "totalIdentifiers" -> cpg.identifier.l.size,
            "totalParameters" -> cpg.parameter.l.size
        )
        
        dataFlowAnalysis.toJson
        """.trimIndent()

    private fun createApiSurfaceAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val apiAnalysis = Map(
            "publicMethods" -> cpg.method.isPublic.l.map(m => Map(
                "name" -> m.fullName,
                "parameterCount" -> m.parameter.l.size,
                "returnType" -> m.methodReturn.typeFullName
            )),
            "annotatedMethods" -> cpg.method.l.filter(_.ast.isAnnotation.nonEmpty).map(m => Map(
                "method" -> m.fullName,
                "annotationCount" -> m.ast.isAnnotation.l.size
            )),
            "totalAnnotations" -> cpg.annotation.l.size,
            "annotationNames" -> cpg.annotation.l.map(_.name).distinct,
            "publicTypeDecls" -> cpg.typeDecl.isPublic.l.map(_.fullName),
            "publicMembers" -> cpg.member.isPublic.l.map(m => Map(
                "name" -> m.name,
                "type" -> m.typeFullName
            )),
            "totalPublicMethods" -> cpg.method.isPublic.l.size,
            "totalPublicTypes" -> cpg.typeDecl.isPublic.l.size
        )
        
        apiAnalysis.toJson
        """.trimIndent()

    private fun createPerformanceAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val performanceAnalysis = Map(
            "totalControlStructures" -> cpg.controlStructure.l.size,
            "controlStructuresByType" -> cpg.controlStructure.groupBy(_.controlStructureType).mapValues(_.size),
            "methodsWithLoops" -> cpg.method.l.filter(_.ast.isControlStructure.nonEmpty).map(_.fullName),
            "databaseOperations" -> cpg.call.name(".*query.*|.*execute.*|.*find.*").l.map(_.name),
            "fileOperations" -> cpg.call.name(".*read.*|.*write.*|.*open.*").l.map(_.name),
            "networkOperations" -> cpg.call.name(".*connect.*|.*send.*|.*receive.*").l.map(_.name),
            "memoryOperations" -> cpg.call.name(".*new.*|.*create.*|.*allocate.*").l.map(_.name),
            "synchronizationCalls" -> cpg.call.name(".*synchronized.*|.*lock.*|.*wait.*").l.map(_.code),
            "totalCalls" -> cpg.call.l.size,
            "callsByMethod" -> cpg.method.l.map(m => m.fullName -> m.ast.isCall.l.size).toMap
        )
        
        performanceAnalysis.toJson
        """.trimIndent()

    private fun createConfigurationAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val configAnalysis = Map(
            "configurationFiles" -> cpg.file.name(".*\\.properties|.*\\.yml|.*\\.yaml|.*\\.conf|.*\\.ini").l.map(_.name),
            "environmentCalls" -> cpg.call.name(".*getenv.*|.*getProperty.*").l.map(_.name),
            "stringLiterals" -> cpg.literal.typeFullName("java.lang.String").l.filter(_.code.length > 10).map(_.code),
            "annotatedTypes" -> cpg.typeDecl.l.filter(_.ast.isAnnotation.name(".*Configuration.*|.*Component.*").nonEmpty).map(_.fullName),
            "profileMethods" -> cpg.method.l.filter(_.ast.isAnnotation.name(".*Profile.*").nonEmpty).map(m => Map(
                "method" -> m.fullName,
                "annotationCount" -> m.ast.isAnnotation.l.size
            )),
            "annotatedMembers" -> cpg.member.l.filter(_.ast.isAnnotation.name(".*Value.*|.*ConfigurationProperties.*").nonEmpty).map(m => Map(
                "field" -> m.name,
                "annotationCount" -> m.ast.isAnnotation.l.size
            )),
            "totalFiles" -> cpg.file.l.size,
            "totalLiterals" -> cpg.literal.l.size
        )
        
        configAnalysis.toJson
        """.trimIndent()

    private fun createTestingAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val testAnalysis = Map(
            "testMethods" -> cpg.method.l.filter(_.ast.isAnnotation.name(".*Test.*").nonEmpty).map(_.fullName),
            "testClasses" -> cpg.typeDecl.l.filter(_.method.ast.isAnnotation.name(".*Test.*").nonEmpty).map(_.fullName),
            "mockCalls" -> cpg.call.name(".*mock.*|.*spy.*|.*stub.*").l.map(_.name),
            "assertionCalls" -> cpg.call.name(".*assert.*|.*verify.*|.*expect.*").l.map(_.name),
            "testCoverage" -> Map(
                "totalMethods" -> cpg.method.l.size,
                "methodsWithTestAnnotations" -> cpg.method.l.filter(_.ast.isAnnotation.name(".*Test.*").nonEmpty).size
            ),
            "integrationTestClasses" -> cpg.typeDecl.l.filter(_.ast.isAnnotation.name(".*IntegrationTest.*|.*SpringBootTest.*").nonEmpty).map(_.fullName),
            "setupMethods" -> cpg.method.l.filter(_.ast.isAnnotation.name(".*Before.*|.*Setup.*").nonEmpty).map(_.fullName),
            "totalAnnotations" -> cpg.annotation.l.size,
            "testAnnotations" -> cpg.annotation.name(".*Test.*|.*Before.*|.*After.*").l.size
        )
        
        testAnalysis.toJson
        """.trimIndent()

    private fun createBusinessLogicAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val businessLogicAnalysis = Map(
            "serviceClasses" -> cpg.typeDecl.l.filter(_.ast.isAnnotation.name(".*Service.*").nonEmpty).map(_.fullName),
            "repositoryClasses" -> cpg.typeDecl.l.filter(_.ast.isAnnotation.name(".*Repository.*").nonEmpty).map(_.fullName),
            "controllerClasses" -> cpg.typeDecl.l.filter(_.ast.isAnnotation.name(".*Controller.*|.*RestController.*").nonEmpty).map(_.fullName),
            "entityClasses" -> cpg.typeDecl.l.filter(_.ast.isAnnotation.name(".*Entity.*|.*Document.*").nonEmpty).map(_.fullName),
            "transactionalMethods" -> cpg.method.l.filter(_.ast.isAnnotation.name(".*Transactional.*").nonEmpty).map(m => Map(
                "method" -> m.fullName,
                "controlStructureCount" -> m.ast.isControlStructure.l.size,
                "parameterCount" -> m.parameter.l.size
            )),
            "validationMethods" -> cpg.method.name(".*valid.*|.*check.*|.*verify.*").l.map(_.fullName),
            "exceptionTypes" -> cpg.typeDecl.fullName(".*Exception.*").l.map(_.fullName),
            "domainEventCalls" -> cpg.call.name(".*publish.*|.*emit.*|.*send.*").l.map(_.name),
            "totalBusinessAnnotations" -> cpg.annotation.name(".*Service.*|.*Repository.*|.*Controller.*|.*Entity.*").l.size,
            "methodComplexityDistribution" -> cpg.method.l.map(m => m.fullName -> m.ast.l.size).toMap
        )
        
        businessLogicAnalysis.toJson
        """.trimIndent()

    data class JoernAnalysisConfig(
        val name: String,
        val displayName: String,
        val scriptContent: String,
        val description: String,
    )
}
