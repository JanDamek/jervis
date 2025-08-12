package com.jervis.module.indexer

import com.jervis.domain.dependency.Dependency
import com.jervis.domain.dependency.DependencyType
import com.jervis.domain.git.CommitInfo
import com.jervis.domain.llm.LlmResponse
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.gitwatcher.GitClient
import com.jervis.service.indexer.ChunkingService
import com.jervis.service.indexer.DependencyAnalyzer
import com.jervis.service.indexer.DependencyIndexer
import com.jervis.service.indexer.EmbeddingService
import com.jervis.service.indexer.IndexerService
import com.jervis.service.indexer.ProjectIndexer
import com.jervis.service.indexer.WorkspaceManager
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.llm.ModelRouterService
import com.jervis.service.vectordb.VectorDbService
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ProjectIndexerTest {
    @Mock
    private lateinit var indexerService: IndexerService

    @Mock
    private lateinit var embeddingService: EmbeddingService

    @Mock
    private lateinit var chunkingService: ChunkingService

    @Mock
    private lateinit var vectorDbService: VectorDbService

    @Mock
    private lateinit var gitClient: GitClient

    @Mock
    private lateinit var dependencyAnalyzer: DependencyAnalyzer

    @Mock
    private lateinit var dependencyIndexer: DependencyIndexer

    @Mock
    private lateinit var workspaceManager: WorkspaceManager

    @Mock
    private lateinit var llmCoordinator: LlmCoordinator

    @Mock
    private lateinit var modelRouterService: ModelRouterService

    private lateinit var projectIndexer: ProjectIndexer

    @BeforeEach
    fun setUp() {
        projectIndexer =
            ProjectIndexer(
                indexerService,
                embeddingService,
                chunkingService,
                vectorDbService,
                gitClient,
                dependencyIndexer,
                workspaceManager,
                llmCoordinator,
                modelRouterService,
            )
    }

    @Test
    fun `test indexProject with successful execution`() {
        runBlocking {
            // Arrange
            val project =
                ProjectDocument(
                    id = ObjectId(),
                    name = "Test Project",
                    path = "/path/to/project",
                    description = "Test project for indexing",
                )

            val workspacePath = Paths.get("/path/to/workspace")
            val dependencies =
                listOf(
                    Dependency(
                        sourceClass = "com.example.ClassA",
                        targetClass = "com.example.ClassB",
                        type = DependencyType.IMPORT,
                        sourceFile = "ClassA.java",
                    ),
                )

            // Mock behavior
            `when`(workspaceManager.setupWorkspace(project)).thenReturn(workspacePath)

            // Mock Git commit history
            val commitInfo =
                CommitInfo(
                    id = "abc123",
                    authorName = "Test Author",
                    authorEmail = "test@example.com",
                    time = Instant.now(),
                    message = "Test commit",
                    changedFiles = listOf("file1.kt", "file2.kt"),
                )
            `when`(gitClient.getCommitHistory(project.path)).thenReturn(listOf(commitInfo))

            // Mock LLM response for commit analysis
            val llmResponse =
                LlmResponse(
                    answer = "This commit adds new features and fixes bugs.",
                    model = "test-model",
                    promptTokens = 10,
                    completionTokens = 10,
                    totalTokens = 20,
                )
            `when`(llmCoordinator.processQueryBlocking(anyString(), anyString())).thenReturn(llmResponse)

            `when`(dependencyAnalyzer.analyzeDependencies(project)).thenReturn(dependencies)
            `when`(embeddingService.generateEmbedding(anyString())).thenReturn(listOf(0.1f, 0.2f, 0.3f))

            // Act
            val result = projectIndexer.indexProject(project)

            // Assert
            assert(result.success)
            assert(result.dependenciesAnalyzed == dependencies.size)

            // Verify interactions
            verify(workspaceManager).setupWorkspace(project)
            verify(gitClient).getLastCommitInfo(project.path)
            verify(indexerService).indexProject(project)
            verify(dependencyAnalyzer).analyzeDependencies(project)
            verify(vectorDbService, atLeastOnce()).storeDocument(any(), any())
        }
    }

    @Test
    fun `test indexProject with exception`() {
        runBlocking {
            // Arrange
            val project =
                ProjectDocument(
                    id = ObjectId(),
                    name = "Test Project",
                    path = "/path/to/project",
                    description = "Test project for indexing",
                )

            // Mock behavior to throw exception
            `when`(workspaceManager.setupWorkspace(project)).thenThrow(RuntimeException("Test exception"))

            // Act
            val result = projectIndexer.indexProject(project)

            // Assert
            assert(!result.success)
            assert(result.errorMessage == "Test exception")
        }
    }

    @Test
    fun `test generateClassSummaries with successful execution`() {
        runBlocking {
            // Arrange
            val project =
                ProjectDocument(
                    id = ObjectId(),
                    name = "Test Project",
                    path = "/path/to/project",
                    description = "Test project for indexing",
                )

            // Create a test directory structure with code files
            val tempDir = Files.createTempDirectory("test-project")
            val kotlinFile = Files.createFile(tempDir.resolve("TestClass.kt"))

            // Write test content to the file
            val kotlinContent =
                """
                package com.example

                /**
                 * This is a test class
                 */
                class TestClass {
                    fun testMethod() {
                        println("Hello, world!")
                    }
                }
                """.trimIndent()
            Files.write(kotlinFile, kotlinContent.toByteArray())

            // Update project path to use the temp directory
            val testProject = project.copy(path = tempDir.toString())

            // Mock chunking service to return code chunks
            val codeChunk =
                ChunkingService.CodeChunk(
                    content = kotlinContent,
                    type = "class",
                    name = "TestClass",
                    startLine = 6,
                    endLine = 10,
                    parentName = null,
                )
            `when`(chunkingService.chunkKotlinCode(kotlinContent)).thenReturn(listOf(codeChunk))

            // Mock LLM response for class analysis
            val llmResponse =
                LlmResponse(
                    answer = "TestClass is a simple class that prints a greeting message.",
                    model = "test-model",
                    promptTokens = 10,
                    completionTokens = 10,
                    totalTokens = 20,
                )
            `when`(llmCoordinator.processQueryBlocking(anyString(), anyString())).thenReturn(llmResponse)

            // Mock embedding service
            `when`(embeddingService.generateEmbedding(anyString())).thenReturn(listOf(0.1f, 0.2f, 0.3f))

            try {
                // Act - call the method via reflection since it's private
                val method =
                    ProjectIndexer::class.java.getDeclaredMethod("generateClassSummaries", ProjectDocument::class.java)
                method.isAccessible = true
                val result = method.invoke(projectIndexer, testProject) as Int

                // Assert
                assert(result == 1) // One class processed

                // Verify interactions
                verify(chunkingService).chunkKotlinCode(kotlinContent)
                verify(llmCoordinator).processQuery(anyString(), anyString())
                verify(embeddingService).generateEmbedding(anyString())
                verify(vectorDbService).storeDocumentSuspend(any(), any())
            } finally {
                // Clean up
                Files.deleteIfExists(kotlinFile)
                Files.deleteIfExists(tempDir)
            }
        }
    }

    @Test
    fun `test processGitHistory with semantic analysis`() {
        runBlocking {
            // Arrange
            val project =
                ProjectDocument(
                    id = ObjectId(),
                    name = "Test Project",
                    path = "/path/to/project",
                    description = "Test project for indexing",
                )

            // Create a test directory structure with a .git directory
            val tempDir = Files.createTempDirectory("test-project")
            val gitDir = Files.createDirectory(tempDir.resolve(".git"))

            // Update project path to use the temp directory
            val testProject = project.copy(path = tempDir.toString())

            // Mock Git commit history
            val commitInfo1 =
                CommitInfo(
                    id = "abc123",
                    authorName = "Test Author 1",
                    authorEmail = "test1@example.com",
                    time = Instant.now().minusSeconds(3600), // 1 hour ago
                    message = "Initial commit",
                    changedFiles = listOf("file1.kt", "file2.kt"),
                )

            val commitInfo2 =
                CommitInfo(
                    id = "def456",
                    authorName = "Test Author 2",
                    authorEmail = "test2@example.com",
                    time = Instant.now(),
                    message = "Fix bug in login flow",
                    changedFiles = listOf("auth/LoginService.kt"),
                )

            `when`(gitClient.getCommitHistory(tempDir.toString())).thenReturn(listOf(commitInfo1, commitInfo2))

            // Mock LLM responses for commit analysis
            val llmResponse1 =
                LlmResponse(
                    answer = "Initial project setup with core files.",
                    model = "test-model",
                    promptTokens = 10,
                    completionTokens = 10,
                    totalTokens = 20,
                )

            val llmResponse2 =
                LlmResponse(
                    answer = "Fixed authentication issue in the login service.",
                    model = "test-model",
                    promptTokens = 10,
                    completionTokens = 10,
                    totalTokens = 20,
                )

            // Use a counter to return different responses for different calls
            var callCount = 0
            `when`(llmCoordinator.processQuery(anyString(), anyString())).thenAnswer {
                callCount++
                if (callCount == 1) llmResponse1 else llmResponse2
            }

            // Mock embedding service
            `when`(embeddingService.generateEmbedding(anyString())).thenReturn(listOf(0.1f, 0.2f, 0.3f))

            try {
                // Act - call the method via reflection since it's private
                val method =
                    ProjectIndexer::class.java.getDeclaredMethod("processGitHistory", ProjectDocument::class.java)
                method.isAccessible = true
                val result = method.invoke(projectIndexer, testProject) as Int

                // Assert
                assert(result == 2) // Two commits processed

                // Verify interactions
                verify(gitClient).getCommitHistory(tempDir.toString())
                verify(llmCoordinator, times(2)).processQuery(anyString(), anyString())
                verify(embeddingService, times(2)).generateEmbedding(anyString())
                verify(vectorDbService, times(2)).storeDocumentSuspend(any(), any())
            } finally {
                // Clean up
                Files.deleteIfExists(gitDir)
                Files.deleteIfExists(tempDir)
            }
        }
    }
}
