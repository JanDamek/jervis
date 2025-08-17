package com.jervis.module.indexer

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
import com.jervis.service.vectordb.VectorStorageService
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class ProjectIndexerTest {
    @Mock
    private lateinit var indexerService: IndexerService

    @Mock
    private lateinit var embeddingService: EmbeddingService

    @Mock
    private lateinit var chunkingService: ChunkingService

    @Mock
    private lateinit var vectorDbService: VectorStorageService

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
}
