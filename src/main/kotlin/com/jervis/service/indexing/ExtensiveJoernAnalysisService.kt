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
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path

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
    private val promptRepository: PromptRepository,
) {
    private val logger = KotlinLogging.logger {}

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
                for (analysisConfig in analyses) {
                    try {
                        val success = performJoernAnalysis(project, projectPath, joernDir, analysisConfig)
                        if (success) {
                            processedAnalyses++
                        } else {
                            errorAnalyses++
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to perform Joern analysis: ${analysisConfig.name}" }
                        errorAnalyses++
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

            // Create analysis script file
            val scriptFile = joernDir.resolve("${analysisConfig.name}.sc").toFile()
            scriptFile.writeText(analysisConfig.scriptContent)

            // Create output file path
            val outputFile = joernDir.resolve("${analysisConfig.name}-results.json").toFile()

            // Execute Joern analysis
            val processBuilder =
                ProcessBuilder(
                    "joern",
                    "--script",
                    scriptFile.absolutePath,
                    "--param",
                    "outFile=\"${outputFile.absolutePath}\"",
                ).apply {
                    directory(projectPath.toFile())
                    redirectErrorStream(true)
                }

            val process = processBuilder.start()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val errorOutput = process.inputStream.bufferedReader().use { it.readText() }
                logger.warn { "Joern analysis '${analysisConfig.name}' failed with exit code $exitCode: $errorOutput" }
                return false
            }

            // Read and process results
            if (outputFile.exists() && outputFile.length() > 0) {
                val results = outputFile.readText()
                return indexJoernAnalysisResults(project, analysisConfig, results)
            } else {
                logger.debug { "No results generated for analysis: ${analysisConfig.name}" }
                return false
            }
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
                    projectId = project.id!!,
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
                "",
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
            appendLine(llmResponse)
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
        import io.joern.suites.findings._
        
        val findings = List(
            // SQL Injection vulnerabilities
            cpg.method(".*exec.*|.*query.*|.*prepare.*").where(_.parameter.evalType(".*String.*")).l,
            // XSS vulnerabilities  
            cpg.method(".*write.*|.*print.*|.*send.*").where(_.parameter.evalType(".*String.*")).l,
            // Path traversal vulnerabilities
            cpg.method(".*File.*|.*Path.*|.*open.*").where(_.parameter.evalType(".*String.*")).l,
            // Hardcoded credentials
            cpg.literal.code(".*password.*|.*secret.*|.*key.*|.*token.*").l,
            // Insecure random usage
            cpg.method(".*Random.*|.*Math.random.*").l,
            // Insecure cryptography
            cpg.method(".*MD5.*|.*SHA1.*|.*DES.*").l
        ).flatten
        
        findings.toJson
        """.trimIndent()

    private fun createCodeQualityScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val qualityMetrics = Map(
            "totalMethods" -> cpg.method.l.size,
            "totalClasses" -> cpg.typeDecl.l.size,
            "complexMethods" -> cpg.method.where(_.controlStructure.l.size > 10).l.size,
            "largeMethods" -> cpg.method.where(_.ast.l.size > 100).l.size,
            "publicMethods" -> cpg.method.isPublic.l.size,
            "privateMethods" -> cpg.method.isPrivate.l.size,
            "methodsWithoutDocs" -> cpg.method.where(_.comment.isEmpty).l.size,
            "duplicatedCodeBlocks" -> cpg.method.groupBy(_.signature).filter(_._2.size > 1).size,
            "unusedImports" -> cpg.imports.where(_.importedEntity.referencingIdentifiers.isEmpty).l.size,
            "longParameterLists" -> cpg.method.where(_.parameter.l.size > 5).l.size
        )
        
        qualityMetrics.toJson
        """.trimIndent()

    private fun createDependencyAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val dependencyInfo = Map(
            "externalDependencies" -> cpg.dependency.l.map(_.name),
            "imports" -> cpg.imports.l.map(_.importedEntity),
            "internalTypes" -> cpg.typeDecl.internal.l.map(_.fullName),
            "externalTypes" -> cpg.typeDecl.external.l.map(_.fullName),
            "libraryUsage" -> cpg.call.where(_.typeDecl.external).groupBy(_.typeDecl.fullName).mapValues(_.size),
            "packageStructure" -> cpg.namespace.l.map(_.name).distinct,
            "crossPackageReferences" -> cpg.call.where(_.typeDecl.namespace.name != _.method.typeDecl.namespace.name).l.size
        )
        
        dependencyInfo.toJson
        """.trimIndent()

    private fun createArchitectureAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val architectureAnalysis = Map(
            "layerStructure" -> cpg.namespace.l.map(_.name).groupBy(_.split("\\.").take(3).mkString(".")),
            "designPatterns" -> Map(
                "singletons" -> cpg.typeDecl.where(_.method.name("getInstance")).l.map(_.fullName),
                "factories" -> cpg.typeDecl.where(_.method.name(".*create.*|.*build.*")).l.map(_.fullName),
                "builders" -> cpg.typeDecl.where(_.method.name("build").typeDecl.fullName.endsWith("Builder")).l.map(_.fullName),
                "observers" -> cpg.typeDecl.where(_.method.name(".*notify.*|.*update.*|.*observe.*")).l.map(_.fullName)
            ),
            "componentCoupling" -> cpg.typeDecl.l.map(t => 
                t.fullName -> t.referencingIdentifiers.typeDecl.fullName.l.distinct.size
            ).toMap,
            "interfaceImplementations" -> cpg.typeDecl.where(_.isInterface).l.map(i =>
                i.fullName -> i.derivedTypeDecl.fullName.l
            ).toMap
        )
        
        architectureAnalysis.toJson
        """.trimIndent()

    private fun createDataFlowAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        import io.shiftleft.dataflowengineoss.language._
        
        val dataFlowAnalysis = Map(
            "userInputSources" -> cpg.method.where(_.parameter.evalType(".*String.*")).l.map(_.fullName),
            "databaseSinks" -> cpg.method(".*execute.*|.*query.*|.*update.*").l.map(_.fullName),
            "fileSinks" -> cpg.method(".*write.*|.*save.*|.*store.*").l.map(_.fullName),
            "networkSinks" -> cpg.method(".*send.*|.*post.*|.*get.*|.*request.*").l.map(_.fullName),
            "sensitiveDataFlow" -> cpg.identifier.name(".*password.*|.*secret.*|.*token.*|.*key.*").l.map(_.code),
            "validationMethods" -> cpg.method.where(_.name(".*valid.*|.*check.*|.*verify.*")).l.map(_.fullName),
            "sanitizationMethods" -> cpg.method.where(_.name(".*clean.*|.*sanitize.*|.*escape.*")).l.map(_.fullName)
        )
        
        dataFlowAnalysis.toJson
        """.trimIndent()

    private fun createApiSurfaceAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val apiAnalysis = Map(
            "publicMethods" -> cpg.method.isPublic.l.map(m => Map(
                "name" -> m.fullName,
                "parameters" -> m.parameter.l.map(_.typeFullName),
                "returnType" -> m.methodReturn.typeFullName
            )),
            "restEndpoints" -> cpg.annotation.where(_.name(".*Mapping|.*Path")).l.map(a => Map(
                "annotation" -> a.name,
                "value" -> a.argumentValue.l.mkString(","),
                "method" -> a.method.fullName
            )),
            "publicInterfaces" -> cpg.typeDecl.isInterface.isPublic.l.map(_.fullName),
            "exposedFields" -> cpg.member.isPublic.l.map(m => Map(
                "name" -> m.name,
                "type" -> m.typeFullName,
                "class" -> m.typeDecl.fullName
            )),
            "annotatedMethods" -> cpg.method.where(_.annotation).l.map(m => Map(
                "method" -> m.fullName,
                "annotations" -> m.annotation.name.l
            ))
        )
        
        apiAnalysis.toJson
        """.trimIndent()

    private fun createPerformanceAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val performanceAnalysis = Map(
            "loopComplexity" -> cpg.controlStructure.where(_.controlStructureType("FOR|WHILE|DO")).groupBy(_.method.fullName).mapValues(_.size),
            "recursiveMethods" -> cpg.method.where(_.ast.isCall.name(_.method.name)).l.map(_.fullName),
            "databaseOperations" -> cpg.call.where(_.name(".*query.*|.*execute.*|.*find.*")).groupBy(_.method.fullName).mapValues(_.size),
            "fileOperations" -> cpg.call.where(_.name(".*read.*|.*write.*|.*open.*")).groupBy(_.method.fullName).mapValues(_.size),
            "networkOperations" -> cpg.call.where(_.name(".*connect.*|.*send.*|.*receive.*")).groupBy(_.method.fullName).mapValues(_.size),
            "memoryIntensiveOperations" -> cpg.call.where(_.name(".*new.*|.*create.*|.*allocate.*")).groupBy(_.method.fullName).mapValues(_.size),
            "synchronizationPoints" -> cpg.call.where(_.name(".*synchronized.*|.*lock.*|.*wait.*")).l.map(_.code)
        )
        
        performanceAnalysis.toJson
        """.trimIndent()

    private fun createConfigurationAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val configAnalysis = Map(
            "configurationFiles" -> cpg.file.where(_.name(".*\\.properties|.*\\.yml|.*\\.yaml|.*\\.conf|.*\\.ini")).l.map(_.name),
            "environmentVariables" -> cpg.call.where(_.name(".*getenv.*|.*getProperty.*")).l.map(_.argument.code.l),
            "hardcodedValues" -> cpg.literal.where(_.typeFullName("java.lang.String")).code.l.filter(_.length > 10),
            "configurationClasses" -> cpg.typeDecl.where(_.annotation.name(".*Configuration.*|.*Component.*")).l.map(_.fullName),
            "profileSpecificCode" -> cpg.method.where(_.annotation.name(".*Profile.*")).l.map(m => Map(
                "method" -> m.fullName,
                "profiles" -> m.annotation.where(_.name(".*Profile.*")).argumentValue.l
            )),
            "externalizableProperties" -> cpg.member.where(_.annotation.name(".*Value.*|.*ConfigurationProperties.*")).l.map(m => Map(
                "field" -> m.name,
                "annotation" -> m.annotation.name.l,
                "defaultValue" -> m.annotation.argumentValue.l
            ))
        )
        
        configAnalysis.toJson
        """.trimIndent()

    private fun createTestingAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val testAnalysis = Map(
            "testMethods" -> cpg.method.where(_.annotation.name(".*Test.*")).l.map(_.fullName),
            "testClasses" -> cpg.typeDecl.where(_.method.annotation.name(".*Test.*")).l.map(_.fullName),
            "mockUsage" -> cpg.call.where(_.name(".*mock.*|.*spy.*|.*stub.*")).groupBy(_.method.fullName).mapValues(_.size),
            "assertionUsage" -> cpg.call.where(_.name(".*assert.*|.*verify.*|.*expect.*")).groupBy(_.method.fullName).mapValues(_.size),
            "testCoverage" -> Map(
                "totalMethods" -> cpg.method.l.size,
                "testedMethods" -> cpg.method.where(_.name.l.exists(cpg.method.where(_.annotation.name(".*Test.*")).ast.isCall.name(_))).l.size
            ),
            "integrationTests" -> cpg.typeDecl.where(_.annotation.name(".*IntegrationTest.*|.*SpringBootTest.*")).l.map(_.fullName),
            "testDataSetup" -> cpg.method.where(_.annotation.name(".*Before.*|.*Setup.*")).l.map(_.fullName)
        )
        
        testAnalysis.toJson
        """.trimIndent()

    private fun createBusinessLogicAnalysisScript(): String =
        """
        import io.shiftleft.codepropertygraph.generated._
        
        val businessLogicAnalysis = Map(
            "serviceClasses" -> cpg.typeDecl.where(_.annotation.name(".*Service.*")).l.map(_.fullName),
            "repositoryClasses" -> cpg.typeDecl.where(_.annotation.name(".*Repository.*")).l.map(_.fullName),
            "controllerClasses" -> cpg.typeDecl.where(_.annotation.name(".*Controller.*|.*RestController.*")).l.map(_.fullName),
            "entityClasses" -> cpg.typeDecl.where(_.annotation.name(".*Entity.*|.*Document.*")).l.map(_.fullName),
            "businessMethods" -> cpg.method.where(_.annotation.name(".*Transactional.*|.*Service.*")).l.map(m => Map(
                "method" -> m.fullName,
                "complexity" -> m.controlStructure.l.size,
                "parameters" -> m.parameter.l.size
            )),
            "validationLogic" -> cpg.method.where(_.name(".*valid.*|.*check.*|.*verify.*")).l.map(_.fullName),
            "businessExceptions" -> cpg.typeDecl.where(_.inheritsFromTypeFullName(".*Exception")).l.map(_.fullName),
            "domainEvents" -> cpg.call.where(_.name(".*publish.*|.*emit.*|.*send.*")).groupBy(_.method.fullName).mapValues(_.size)
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
