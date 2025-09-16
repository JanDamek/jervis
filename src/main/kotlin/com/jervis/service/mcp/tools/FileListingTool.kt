package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

@Service
class FileListingTool(
    private val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: String = "file-listing"
    override val description: String
        get() = promptRepository.getMcpToolDescription(PromptTypeEnum.FILE_LISTING)

    @Serializable
    data class FileListingParams(
        val includeTests: Boolean = true,
        val includeResources: Boolean = true,
        val maxDepth: Int = 10,
        val includeHidden: Boolean = false,
        val categorizeByPurpose: Boolean = true,
        val analyzeCapabilities: Boolean = true,
    )

    @Serializable
    data class FileInfo(
        val path: String,
        val name: String,
        val extension: String?,
        val size: Long,
        val type: FileType,
        val category: FileCategory,
        val purpose: String?,
        val capabilities: List<String> = emptyList(),
        val relatedTo: List<String> = emptyList(),
    )

    @Serializable
    data class DirectoryInfo(
        val path: String,
        val name: String,
        val purpose: String?,
        val fileCount: Int,
        val subDirectories: List<String> = emptyList(),
    )

    @Serializable
    data class ProjectStructure(
        val rootPath: String,
        val directories: List<DirectoryInfo>,
        val files: List<FileInfo>,
        val summary: ProjectSummary,
    )

    @Serializable
    data class ProjectSummary(
        val totalFiles: Int,
        val totalDirectories: Int,
        val mainLanguages: List<String>,
        val frameworks: List<String>,
        val keyComponents: List<String>,
        val entryPoints: List<String>,
        val configurationFiles: List<String>,
    )

    enum class FileType {
        SOURCE_CODE,
        TEST,
        CONFIGURATION,
        RESOURCE,
        DOCUMENTATION,
        BUILD_SCRIPT,
        UNKNOWN,
    }

    enum class FileCategory {
        CONTROLLER,
        SERVICE,
        REPOSITORY,
        ENTITY,
        DTO,
        CONFIGURATION,
        TEST,
        UTILITY,
        MAIN_CLASS,
        INTERFACE,
        ENUM,
        BUILD,
        RESOURCE,
        DOCUMENTATION,
        UNKNOWN,
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            logger.debug { "FILE_LISTING_START: Executing file listing for task='$taskDescription', context=${context.id}" }

            val params = parseTaskDescription(taskDescription)
            val projectPath = determineProjectPath(context)

            logger.debug { "FILE_LISTING_PROJECT_PATH: Using project path: $projectPath" }

            try {
                val projectStructure = analyzeProjectStructure(projectPath, params)
                val formattedOutput = formatProjectStructure(projectStructure, taskDescription)

                logger.debug {
                    "FILE_LISTING_SUCCESS: Found ${projectStructure.files.size} files in ${projectStructure.directories.size} directories"
                }

                ToolResult.ok(formattedOutput)
            } catch (e: Exception) {
                logger.error(e) { "FILE_LISTING_ERROR: Failed to analyze project structure" }
                ToolResult.error("Failed to analyze project structure: ${e.message}")
            }
        }

    private fun parseTaskDescription(taskDescription: String): FileListingParams {
        // Parse parameters from task description or use defaults
        return FileListingParams(
            includeTests = !taskDescription.contains("exclude tests", ignoreCase = true),
            includeResources = !taskDescription.contains("exclude resources", ignoreCase = true),
            maxDepth = extractMaxDepth(taskDescription),
            includeHidden = taskDescription.contains("include hidden", ignoreCase = true),
            categorizeByPurpose = !taskDescription.contains("no categorization", ignoreCase = true),
            analyzeCapabilities = !taskDescription.contains("no analysis", ignoreCase = true),
        )
    }

    private fun extractMaxDepth(taskDescription: String): Int {
        val depthRegex = """depth\s*[=:]\s*(\d+)""".toRegex(RegexOption.IGNORE_CASE)
        return depthRegex
            .find(taskDescription)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 10
    }

    private fun determineProjectPath(context: TaskContext): Path {
        val projectPath = context.projectDocument.path

        return when {
            projectPath.isBlank() -> {
                logger.warn { "FILE_LISTING_EMPTY_PATH: Project path is empty, falling back to current directory" }
                Path.of(System.getProperty("user.dir"))
            }

            else -> {
                val path = Path.of(projectPath)
                if (Files.exists(path) && Files.isDirectory(path)) {
                    logger.debug { "FILE_LISTING_PROJECT_PATH: Using project path: $projectPath" }
                    path
                } else {
                    logger.warn {
                        "FILE_LISTING_INVALID_PATH: Project path '$projectPath' does not exist or is not a directory, falling back to current directory"
                    }
                    Path.of(System.getProperty("user.dir"))
                }
            }
        }
    }

    private suspend fun analyzeProjectStructure(
        projectPath: Path,
        params: FileListingParams,
    ): ProjectStructure {
        logger.debug { "FILE_LISTING_ANALYZE: Analyzing project structure at $projectPath" }

        val directories = mutableListOf<DirectoryInfo>()
        val files = mutableListOf<FileInfo>()

        analyzeDirectory(projectPath, projectPath, directories, files, params, 0)

        val summary = createProjectSummary(directories, files, projectPath)

        return ProjectStructure(
            rootPath = projectPath.toString(),
            directories = directories,
            files = files,
            summary = summary,
        )
    }

    private fun analyzeDirectory(
        currentPath: Path,
        rootPath: Path,
        directories: MutableList<DirectoryInfo>,
        files: MutableList<FileInfo>,
        params: FileListingParams,
        currentDepth: Int,
    ) {
        if (currentDepth > params.maxDepth) return

        try {
            val entries =
                Files.list(currentPath).use { stream ->
                    stream
                        .filter { path ->
                            when {
                                !params.includeHidden && path.name.startsWith(".") -> false
                                !params.includeTests && isTestDirectory(path) -> false
                                !params.includeResources && isResourceDirectory(path) -> false
                                else -> true
                            }
                        }.toList()
                }

            // Process directories
            val subDirs = entries.filter { it.isDirectory() }
            if (subDirs.isNotEmpty()) {
                val dirInfo =
                    DirectoryInfo(
                        path = rootPath.relativize(currentPath).toString(),
                        name = currentPath.name,
                        purpose = analyzeDirectoryPurpose(currentPath, subDirs, entries.filter { it.isRegularFile() }),
                        fileCount = entries.filter { it.isRegularFile() }.size,
                        subDirectories = subDirs.map { it.name },
                    )
                directories.add(dirInfo)

                // Recursively analyze subdirectories
                subDirs.forEach { subDir ->
                    analyzeDirectory(subDir, rootPath, directories, files, params, currentDepth + 1)
                }
            }

            // Process files
            entries.filter { it.isRegularFile() }.forEach { filePath ->
                val fileInfo = analyzeFile(filePath, rootPath, params)
                files.add(fileInfo)
            }
        } catch (e: Exception) {
            logger.warn { "FILE_LISTING_DIR_ERROR: Error analyzing directory $currentPath: ${e.message}" }
        }
    }

    private fun analyzeFile(
        filePath: Path,
        rootPath: Path,
        params: FileListingParams,
    ): FileInfo {
        val relativePath = rootPath.relativize(filePath).toString()
        val extension = filePath.extension.lowercase()
        val fileType = determineFileType(filePath)
        val category = if (params.categorizeByPurpose) categorizeFile(filePath) else FileCategory.UNKNOWN
        val purpose = if (params.categorizeByPurpose) analyzeFilePurpose(filePath, category) else null
        val capabilities =
            if (params.analyzeCapabilities) analyzeFileCapabilities(filePath, fileType, category) else emptyList()

        return FileInfo(
            path = relativePath,
            name = filePath.name,
            extension = if (extension.isNotBlank()) extension else null,
            size =
                try {
                    Files.size(filePath)
                } catch (e: Exception) {
                    0L
                },
            type = fileType,
            category = category,
            purpose = purpose,
            capabilities = capabilities,
            relatedTo = findRelatedFiles(filePath, fileType, category),
        )
    }

    private fun determineFileType(filePath: Path): FileType {
        val extension = filePath.extension.lowercase()
        val pathString = filePath.toString().lowercase()

        return when {
            pathString.contains("test") -> FileType.TEST
            extension in listOf("kt", "java", "js", "ts", "py", "cpp", "c", "h") -> FileType.SOURCE_CODE
            extension in listOf("yml", "yaml", "properties", "json", "xml", "toml") -> FileType.CONFIGURATION
            extension in listOf("gradle", "pom") || filePath.name in
                listOf(
                    "pom.xml",
                    "build.gradle.kts",
                    "package.json",
                )
            -> FileType.BUILD_SCRIPT

            extension in listOf("md", "txt", "rst", "adoc") -> FileType.DOCUMENTATION
            extension in listOf("css", "html", "png", "jpg", "svg", "ico") -> FileType.RESOURCE
            else -> FileType.UNKNOWN
        }
    }

    private fun categorizeFile(filePath: Path): FileCategory {
        val fileName = filePath.name.lowercase()
        val pathString = filePath.toString().lowercase()

        return when {
            fileName.contains("controller") -> FileCategory.CONTROLLER
            fileName.contains("service") -> FileCategory.SERVICE
            fileName.contains("repository") || fileName.contains("dao") -> FileCategory.REPOSITORY
            fileName.contains("entity") || fileName.contains("model") -> FileCategory.ENTITY
            fileName.contains("dto") || fileName.contains("request") || fileName.contains("response") -> FileCategory.DTO
            fileName.contains("config") || fileName.contains("configuration") -> FileCategory.CONFIGURATION
            pathString.contains("test") -> FileCategory.TEST
            fileName.contains("util") || fileName.contains("helper") -> FileCategory.UTILITY
            fileName.contains("application") || fileName.contains("main") -> FileCategory.MAIN_CLASS
            fileName.endsWith("interface.kt") || fileName.endsWith("interface.java") -> FileCategory.INTERFACE
            fileName.contains("enum") -> FileCategory.ENUM
            fileName in listOf("pom.xml", "build.gradle.kts", "package.json") -> FileCategory.BUILD
            filePath.extension in listOf("md", "txt", "rst") -> FileCategory.DOCUMENTATION
            filePath.extension in listOf("css", "html", "png", "jpg", "svg") -> FileCategory.RESOURCE
            else -> FileCategory.UNKNOWN
        }
    }

    private fun analyzeFilePurpose(
        filePath: Path,
        category: FileCategory,
    ): String =
        when (category) {
            FileCategory.CONTROLLER -> "Handles HTTP requests and responses"
            FileCategory.SERVICE -> "Contains business logic and operations"
            FileCategory.REPOSITORY -> "Manages data access and persistence"
            FileCategory.ENTITY -> "Represents data model or database entity"
            FileCategory.DTO -> "Data transfer object for API communication"
            FileCategory.CONFIGURATION -> "Application or framework configuration"
            FileCategory.TEST -> "Contains unit or integration tests"
            FileCategory.UTILITY -> "Provides utility functions and helpers"
            FileCategory.MAIN_CLASS -> "Application entry point"
            FileCategory.INTERFACE -> "Defines contract or abstraction"
            FileCategory.ENUM -> "Defines enumeration of constants"
            FileCategory.BUILD -> "Build configuration and dependencies"
            FileCategory.DOCUMENTATION -> "Project documentation"
            FileCategory.RESOURCE -> "Static resources (CSS, images, templates)"
            FileCategory.UNKNOWN -> "Unknown or miscellaneous file"
        }

    private fun analyzeFileCapabilities(
        filePath: Path,
        fileType: FileType,
        category: FileCategory,
    ): List<String> {
        val capabilities = mutableListOf<String>()

        when (fileType) {
            FileType.SOURCE_CODE -> {
                capabilities.addAll(analyzeSourceCodeCapabilities(filePath, category))
            }

            FileType.CONFIGURATION -> {
                capabilities.addAll(listOf("Configuration management", "Environment settings"))
            }

            FileType.BUILD_SCRIPT -> {
                capabilities.addAll(listOf("Dependency management", "Build automation"))
            }

            FileType.TEST -> {
                capabilities.addAll(listOf("Test execution", "Quality assurance"))
            }

            else -> {
                // Add generic capabilities based on category
            }
        }

        return capabilities
    }

    private fun analyzeSourceCodeCapabilities(
        filePath: Path,
        category: FileCategory,
    ): List<String> =
        when (category) {
            FileCategory.CONTROLLER ->
                listOf(
                    "HTTP endpoint handling",
                    "Request/response processing",
                    "API implementation",
                )

            FileCategory.SERVICE -> listOf("Business logic execution", "Data processing", "Service orchestration")
            FileCategory.REPOSITORY -> listOf("Database operations", "Data persistence", "Query execution")
            FileCategory.ENTITY -> listOf("Data modeling", "Object mapping", "State management")
            FileCategory.DTO -> listOf("Data serialization", "API data structure", "Validation")
            FileCategory.CONFIGURATION -> listOf("Application setup", "Bean configuration", "Framework configuration")
            FileCategory.UTILITY -> listOf("Helper functions", "Common operations", "Utility methods")
            FileCategory.MAIN_CLASS -> listOf("Application startup", "Dependency injection", "System initialization")
            else -> emptyList()
        }

    private fun findRelatedFiles(
        filePath: Path,
        fileType: FileType,
        category: FileCategory,
    ): List<String> {
        // This would analyze file relationships based on imports, references, etc.
        // For now, return empty list - this could be enhanced with actual code analysis
        return emptyList()
    }

    private fun analyzeDirectoryPurpose(
        dirPath: Path,
        subDirs: List<Path>,
        files: List<Path>,
    ): String {
        val dirName = dirPath.name.lowercase()

        return when {
            dirName == "controller" || dirName.contains("controller") -> "HTTP Controllers"
            dirName == "service" || dirName.contains("service") -> "Business Services"
            dirName == "repository" || dirName.contains("repository") -> "Data Access Layer"
            dirName == "entity" || dirName == "model" || dirName.contains("entity") -> "Data Models"
            dirName == "dto" || dirName.contains("dto") -> "Data Transfer Objects"
            dirName == "config" || dirName.contains("config") -> "Configuration Classes"
            dirName == "util" || dirName.contains("util") -> "Utility Classes"
            dirName.contains("test") -> "Test Classes"
            dirName == "resources" -> "Application Resources"
            dirName == "main" -> "Main Source Code"
            dirName == "kotlin" || dirName == "java" -> "Source Code"
            files.any { it.name.contains("Controller") } -> "Controllers Directory"
            files.any { it.name.contains("Service") } -> "Services Directory"
            files.any { it.name.contains("Repository") } -> "Repositories Directory"
            else -> "General Purpose Directory"
        }
    }

    private fun isTestDirectory(path: Path): Boolean = path.toString().lowercase().contains("test")

    private fun isResourceDirectory(path: Path): Boolean = path.name.lowercase() in listOf("resources", "assets", "static", "public")

    private fun createProjectSummary(
        directories: List<DirectoryInfo>,
        files: List<FileInfo>,
        projectPath: Path,
    ): ProjectSummary {
        val languages =
            files
                .mapNotNull { it.extension }
                .groupingBy { it }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .take(5)
                .map { it.first }

        val frameworks = detectFrameworks(files)
        val keyComponents = identifyKeyComponents(files, directories)
        val entryPoints = findEntryPoints(files)
        val configFiles = files.filter { it.type == FileType.CONFIGURATION }.map { it.path }

        return ProjectSummary(
            totalFiles = files.size,
            totalDirectories = directories.size,
            mainLanguages = languages,
            frameworks = frameworks,
            keyComponents = keyComponents,
            entryPoints = entryPoints,
            configurationFiles = configFiles,
        )
    }

    private fun detectFrameworks(files: List<FileInfo>): List<String> {
        val frameworks = mutableSetOf<String>()

        files.forEach { file ->
            when {
                file.name == "pom.xml" -> frameworks.add("Maven")
                file.name.endsWith("build.gradle.kts") -> frameworks.add("Gradle")
                file.name == "package.json" -> frameworks.add("Node.js")
                file.path.contains("spring", ignoreCase = true) -> frameworks.add("Spring Boot")
                file.extension == "kt" -> frameworks.add("Kotlin")
                file.extension == "java" -> frameworks.add("Java")
            }
        }

        return frameworks.toList()
    }

    private fun identifyKeyComponents(
        files: List<FileInfo>,
        directories: List<DirectoryInfo>,
    ): List<String> {
        val components = mutableSetOf<String>()

        files.forEach { file ->
            when (file.category) {
                FileCategory.CONTROLLER -> components.add("REST Controllers")
                FileCategory.SERVICE -> components.add("Business Services")
                FileCategory.REPOSITORY -> components.add("Data Repositories")
                FileCategory.ENTITY -> components.add("Data Models")
                FileCategory.CONFIGURATION -> components.add("Configuration")
                else -> {}
            }
        }

        return components.toList()
    }

    private fun findEntryPoints(files: List<FileInfo>): List<String> =
        files
            .filter { it.category == FileCategory.MAIN_CLASS || it.name.contains("Application") }
            .map { it.path }

    private fun formatProjectStructure(
        structure: ProjectStructure,
        taskDescription: String,
    ): String =
        buildString {
            append("=== PROJECT FILE STRUCTURE ANALYSIS ===\n\n")

            // Project Summary
            append("PROJECT SUMMARY:\n")
            append("- Total Files: ${structure.summary.totalFiles}\n")
            append("- Total Directories: ${structure.summary.totalDirectories}\n")
            append("- Main Languages: ${structure.summary.mainLanguages.joinToString(", ")}\n")
            append("- Frameworks: ${structure.summary.frameworks.joinToString(", ")}\n")
            append("- Key Components: ${structure.summary.keyComponents.joinToString(", ")}\n")
            append("\n")

            // Entry Points
            if (structure.summary.entryPoints.isNotEmpty()) {
                append("APPLICATION ENTRY POINTS:\n")
                structure.summary.entryPoints.forEach { entryPoint ->
                    append("- $entryPoint\n")
                }
                append("\n")
            }

            // File Categories Summary
            val categoryGroups = structure.files.groupBy { it.category }
            append("FILES BY CATEGORY:\n")
            categoryGroups.forEach { (category, files) ->
                append("- ${category.name}: ${files.size} files\n")
                files.take(3).forEach { file ->
                    append("  * ${file.path} - ${file.purpose ?: "Unknown purpose"}\n")
                }
                if (files.size > 3) {
                    append("  ... and ${files.size - 3} more\n")
                }
            }
            append("\n")

            // Key Directories
            append("KEY DIRECTORIES:\n")
            structure.directories
                .filter { it.fileCount > 0 }
                .sortedByDescending { it.fileCount }
                .take(10)
                .forEach { dir ->
                    append("- ${dir.path}/ - ${dir.purpose} (${dir.fileCount} files)\n")
                }
            append("\n")

            // Configuration Files
            if (structure.summary.configurationFiles.isNotEmpty()) {
                append("CONFIGURATION FILES:\n")
                structure.summary.configurationFiles.forEach { configFile ->
                    append("- $configFile\n")
                }
                append("\n")
            }

            // Problem-Solving Recommendations
            append("=== FILE-BASED PROBLEM SOLVING RECOMMENDATIONS ===\n")
            append(generateProblemSolvingRecommendations(structure, taskDescription))

            append("\nFILE LISTING COMPLETE - USE THIS INFORMATION AS PRIMARY SOURCE FOR PLANNING")
        }

    private fun generateProblemSolvingRecommendations(
        structure: ProjectStructure,
        taskDescription: String,
    ): String =
        buildString {
            val lowerTask = taskDescription.lowercase()

            when {
                lowerTask.contains("security") || lowerTask.contains("authentication") -> {
                    append("SECURITY-RELATED FILES:\n")
                    val securityFiles =
                        structure.files.filter {
                            it.path.contains("security", ignoreCase = true) ||
                                it.path.contains("auth", ignoreCase = true) ||
                                it.capabilities.any { cap -> cap.contains("security", ignoreCase = true) }
                        }
                    if (securityFiles.isNotEmpty()) {
                        securityFiles.forEach { file ->
                            append("- ${file.path} - ${file.purpose}\n")
                        }
                    } else {
                        append("- No explicit security files found. Check controllers and configuration files.\n")
                    }
                }

                lowerTask.contains("controller") || lowerTask.contains("endpoint") || lowerTask.contains("api") -> {
                    append("API/CONTROLLER FILES:\n")
                    val controllerFiles = structure.files.filter { it.category == FileCategory.CONTROLLER }
                    controllerFiles.forEach { file ->
                        append("- ${file.path} - ${file.purpose}\n")
                    }
                }

                lowerTask.contains("database") || lowerTask.contains("data") || lowerTask.contains("repository") -> {
                    append("DATA-RELATED FILES:\n")
                    val dataFiles =
                        structure.files.filter {
                            it.category in listOf(FileCategory.REPOSITORY, FileCategory.ENTITY)
                        }
                    dataFiles.forEach { file ->
                        append("- ${file.path} - ${file.purpose}\n")
                    }
                }

                lowerTask.contains("service") || lowerTask.contains("business") || lowerTask.contains("logic") -> {
                    append("SERVICE/BUSINESS LOGIC FILES:\n")
                    val serviceFiles = structure.files.filter { it.category == FileCategory.SERVICE }
                    serviceFiles.forEach { file ->
                        append("- ${file.path} - ${file.purpose}\n")
                    }
                }

                lowerTask.contains("config") || lowerTask.contains("setup") -> {
                    append("CONFIGURATION FILES:\n")
                    val configFiles =
                        structure.files.filter {
                            it.category == FileCategory.CONFIGURATION || it.type == FileType.CONFIGURATION
                        }
                    configFiles.forEach { file ->
                        append("- ${file.path} - ${file.purpose}\n")
                    }
                }

                else -> {
                    append("MOST RELEVANT FILES FOR GENERAL ANALYSIS:\n")
                    val relevantFiles =
                        structure.files
                            .filter {
                                it.category in
                                    listOf(
                                        FileCategory.MAIN_CLASS,
                                        FileCategory.CONTROLLER,
                                        FileCategory.SERVICE,
                                        FileCategory.CONFIGURATION,
                                    )
                            }.take(10)
                    relevantFiles.forEach { file ->
                        append("- ${file.path} - ${file.purpose}\n")
                    }
                }
            }
        }
}
