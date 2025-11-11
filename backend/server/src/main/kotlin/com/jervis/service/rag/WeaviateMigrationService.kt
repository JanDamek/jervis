package com.jervis.service.rag

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.WeaviateProperties
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.entity.WeaviateSchemaMetadata
import com.jervis.repository.mongo.*
import io.weaviate.client.WeaviateClient
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for managing Weaviate schema migrations.
 *
 * Responsibilities:
 * - Detect schema version changes
 * - Coordinate migration process
 * - Delete all vector-dependent data from both Weaviate and MongoDB
 * - Track migration history
 *
 * Migration triggers:
 * - Distance metric change (cosine, euclidean, dot)
 * - HNSW configuration change (ef, efConstruction, maxConnections)
 * - Embedding dimension change (text or code)
 * - Schema version update
 */
@Service
class WeaviateMigrationService(
    private val weaviateProperties: WeaviateProperties,
    private val modelsProperties: ModelsProperties,
    private val schemaMetadataRepo: WeaviateSchemaMetadataRepository,
    // Vector store tracking
    private val vectorStoreIndexRepo: VectorStoreIndexMongoRepository,
    // Confluence
    private val confluencePageRepo: ConfluencePageMongoRepository,
    private val confluenceAccountRepo: ConfluenceAccountMongoRepository,
    // Jira
    private val jiraIssueIndexRepo: JiraIssueIndexMongoRepository,
    private val jiraConnectionRepo: JiraConnectionMongoRepository,
    // Links
    private val indexedLinkRepo: IndexedLinkMongoRepository,
    private val unsafeLinkRepo: UnsafeLinkMongoRepository,
    private val unsafeLinkPatternRepo: UnsafeLinkPatternMongoRepository,
    // Conversations & Messages
    private val conversationThreadRepo: ConversationThreadMongoRepository,
    private val senderProfileRepo: SenderProfileMongoRepository,
    private val messageLinkRepo: MessageLinkMongoRepository,
    // Project evolution
    private val projectEvolutionRepo: ProjectEvolutionMongoRepository,
    // Logs
    private val errorLogRepo: ErrorLogMongoRepository,
    // Email accounts (for reset only)
    private val emailAccountRepo: EmailAccountMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val CURRENT_SCHEMA_VERSION = "2.0"
        private const val DISTANCE_METRIC = "cosine"
        private const val HNSW_EF = 128
        private const val HNSW_EF_CONSTRUCTION = 256
        private const val HNSW_MAX_CONNECTIONS = 64
        private const val HNSW_DYNAMIC_EF_MIN = 100
        private const val HNSW_DYNAMIC_EF_MAX = 500
        private const val HNSW_DYNAMIC_EF_FACTOR = 8
        private const val HNSW_FLAT_SEARCH_CUTOFF = 40000
    }

    /**
     * Check if migration is needed by comparing current config with stored metadata.
     */
    suspend fun isMigrationNeeded(): Boolean {
        val latest = schemaMetadataRepo.findFirstByOrderByMigratedAtDesc()

        if (latest == null) {
            logger.info { "No previous schema metadata found - migration needed for initial setup" }
            return true
        }

        val textDimension = getExpectedDimension(ModelTypeEnum.EMBEDDING_TEXT)
        val codeDimension = getExpectedDimension(ModelTypeEnum.EMBEDDING_CODE)

        val configChanged =
            latest.schemaVersion != CURRENT_SCHEMA_VERSION ||
                latest.distance != DISTANCE_METRIC ||
                latest.ef != HNSW_EF ||
                latest.efConstruction != HNSW_EF_CONSTRUCTION ||
                latest.maxConnections != HNSW_MAX_CONNECTIONS ||
                latest.textDimension != textDimension ||
                latest.codeDimension != codeDimension

        if (configChanged) {
            logger.info {
                "Schema configuration changed:\n" +
                    "  Version: ${latest.schemaVersion} → $CURRENT_SCHEMA_VERSION\n" +
                    "  Distance: ${latest.distance} → $DISTANCE_METRIC\n" +
                    "  EF: ${latest.ef} → $HNSW_EF\n" +
                    "  EF Construction: ${latest.efConstruction} → $HNSW_EF_CONSTRUCTION\n" +
                    "  Max Connections: ${latest.maxConnections} → $HNSW_MAX_CONNECTIONS\n" +
                    "  Text Dimension: ${latest.textDimension} → $textDimension\n" +
                    "  Code Dimension: ${latest.codeDimension} → $codeDimension"
            }
        }

        return configChanged
    }

    /**
     * Perform migration with countdown and logging.
     */
    suspend fun performMigration(weaviateClient: WeaviateClient) {
        val latest = schemaMetadataRepo.findFirstByOrderByMigratedAtDesc()

        logger.warn {
            "\n" +
            "⚠️  WEAVIATE SCHEMA MIGRATION REQUIRED\n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
            "Schema version changed: ${latest?.schemaVersion ?: "none"} → $CURRENT_SCHEMA_VERSION\n" +
            "\n" +
            "⚠️  WARNING: This will DELETE all data in:\n" +
            "  ✗ Weaviate: SemanticText collection\n" +
            "  ✗ Weaviate: SemanticCode collection\n" +
            "  ✗ MongoDB: vector_store_index\n" +
            "  ✗ MongoDB: confluence_pages\n" +
            "  ✗ MongoDB: jira_issue_index\n" +
            "  ✗ MongoDB: indexed_links\n" +
            "  ✗ MongoDB: conversation_threads\n" +
            "  ✗ MongoDB: sender_profiles\n" +
            "  ✗ MongoDB: message_links\n" +
            "  ✗ MongoDB: project_evolution\n" +
            "  ✗ MongoDB: unsafe_links\n" +
            "  ✗ MongoDB: unsafe_link_patterns\n" +
            "  ✗ MongoDB: error_logs\n" +
            "\n" +
            "✓ Safe data (will be preserved):\n" +
            "  ✓ MongoDB: clients, projects\n" +
            "  ✓ MongoDB: email_accounts, confluence_accounts, jira_connections\n" +
            "  ✓ Your source data (git repos, emails, etc.)\n" +
            "\n" +
            "📋 After migration:\n" +
            "  1. All data will need to be RE-INDEXED\n" +
            "  2. Email/Confluence/Jira will auto-reindex on next sync\n" +
            "  3. Git history requires manual reindex per project\n" +
            "\n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
        }

        if (weaviateProperties.autoMigrate.dryRun) {
            logger.warn { "DRY RUN MODE: Migration would be performed but no data will be deleted" }
            return
        }

        // Countdown
        if (weaviateProperties.autoMigrate.countdownSeconds > 0) {
            logger.warn { "⏳ Migration will start in ${weaviateProperties.autoMigrate.countdownSeconds} seconds..." }
            logger.warn { "   Press Ctrl+C to abort." }
            repeat(weaviateProperties.autoMigrate.countdownSeconds) { i ->
                logger.warn { "   ${weaviateProperties.autoMigrate.countdownSeconds - i}..." }
                delay(1000)
            }
        }

        logger.warn { "🚀 Starting migration..." }

        // 1. Delete Weaviate collections
        deleteWeaviateCollections(weaviateClient)

        // 2. Delete all vector-dependent MongoDB data
        deleteAllVectorDependentData()

        // 3. Save new schema metadata
        saveSchemaMetadata(latest?.schemaVersion)

        logger.warn {
            "\n" +
            "✅ WEAVIATE SCHEMA MIGRATION COMPLETE\n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
            "\n" +
            "Deleted:\n" +
            "  ✗ Weaviate SemanticText\n" +
            "  ✗ Weaviate SemanticCode\n" +
            "  ✗ All vector-dependent MongoDB collections\n" +
            "\n" +
            "Created:\n" +
            "  ✓ Schema metadata saved (v$CURRENT_SCHEMA_VERSION)\n" +
            "\n" +
            "⚠️  ACTION REQUIRED:\n" +
            "  Re-index all projects to restore RAG functionality.\n" +
            "  Email/Confluence/Jira will auto-reindex on next sync.\n" +
            "\n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
        }
    }

    /**
     * Delete Weaviate collections (SemanticText and SemanticCode).
     */
    private fun deleteWeaviateCollections(client: WeaviateClient) {
        logger.warn { "Deleting Weaviate collections..." }

        try {
            val deleteText = client.schema().classDeleter().withClassName("SemanticText").run()
            if (deleteText.hasErrors()) {
                logger.warn { "SemanticText collection doesn't exist or couldn't be deleted: ${deleteText.error.messages}" }
            } else {
                logger.warn { "  ✗ Deleted SemanticText collection" }
            }
        } catch (e: Exception) {
            logger.warn { "SemanticText collection doesn't exist: ${e.message}" }
        }

        try {
            val deleteCode = client.schema().classDeleter().withClassName("SemanticCode").run()
            if (deleteCode.hasErrors()) {
                logger.warn { "SemanticCode collection doesn't exist or couldn't be deleted: ${deleteCode.error.messages}" }
            } else {
                logger.warn { "  ✗ Deleted SemanticCode collection" }
            }
        } catch (e: Exception) {
            logger.warn { "SemanticCode collection doesn't exist: ${e.message}" }
        }
    }

    /**
     * Delete all vector-dependent MongoDB collections.
     */
    private suspend fun deleteAllVectorDependentData() {
        logger.warn { "Deleting all vector-dependent MongoDB collections..." }

        // 1. Vector metadata
        val vectorCount = vectorStoreIndexRepo.count()
        vectorStoreIndexRepo.deleteAll()
        logger.warn { "  ✗ Deleted vector_store_index ($vectorCount records)" }

        // 2. Confluence
        val confluenceCount = confluencePageRepo.count()
        confluencePageRepo.deleteAll()
        logger.warn { "  ✗ Deleted confluence_pages ($confluenceCount records)" }

        // Reset Confluence accounts sync status (lastPolledAt, lastSuccessfulSyncAt)
        confluenceAccountRepo.findAll().collect { account ->
            confluenceAccountRepo.save(account.copy(lastPolledAt = null, lastSuccessfulSyncAt = null))
        }
        logger.warn { "  ✓ Reset confluence_accounts sync status" }

        // 3. Jira
        val jiraCount = jiraIssueIndexRepo.count()
        jiraIssueIndexRepo.deleteAll()
        logger.warn { "  ✗ Deleted jira_issue_index ($jiraCount records)" }

        // Note: JiraConnectionDocument doesn't have sync tracking fields to reset
        logger.warn { "  ✓ Jira connections preserved (will re-index on next sync)" }

        // 4. Links
        val linksCount = indexedLinkRepo.count()
        indexedLinkRepo.deleteAll()
        logger.warn { "  ✗ Deleted indexed_links ($linksCount records)" }

        val unsafeLinksCount = unsafeLinkRepo.count()
        unsafeLinkRepo.deleteAll()
        logger.warn { "  ✗ Deleted unsafe_links ($unsafeLinksCount records)" }

        val patternsCount = unsafeLinkPatternRepo.count()
        unsafeLinkPatternRepo.deleteAll()
        logger.warn { "  ✗ Deleted unsafe_link_patterns ($patternsCount records)" }

        // 5. Conversations
        val conversationsCount = conversationThreadRepo.count()
        conversationThreadRepo.deleteAll()
        logger.warn { "  ✗ Deleted conversation_threads ($conversationsCount records)" }

        val sendersCount = senderProfileRepo.count()
        senderProfileRepo.deleteAll()
        logger.warn { "  ✗ Deleted sender_profiles ($sendersCount records)" }

        val messageLinksCount = messageLinkRepo.count()
        messageLinkRepo.deleteAll()
        logger.warn { "  ✗ Deleted message_links ($messageLinksCount records)" }

        // 6. Project evolution
        val evolutionCount = projectEvolutionRepo.count()
        projectEvolutionRepo.deleteAll()
        logger.warn { "  ✗ Deleted project_evolution ($evolutionCount records)" }

        // 7. Error logs
        val errorLogsCount = errorLogRepo.count()
        errorLogRepo.deleteAll()
        logger.warn { "  ✗ Deleted error_logs ($errorLogsCount records)" }

        // 8. Reset email accounts sync (lastPolledAt, highestSeenUid)
        emailAccountRepo.findAll().collect { account ->
            emailAccountRepo.save(account.copy(lastPolledAt = null, highestSeenUid = null))
        }
        logger.warn { "  ✓ Reset email_accounts sync status" }

        logger.warn { "✓ All vector-dependent data deleted" }
    }

    /**
     * Save new schema metadata to MongoDB.
     */
    private suspend fun saveSchemaMetadata(previousVersion: String?) {
        val textDimension = getExpectedDimension(ModelTypeEnum.EMBEDDING_TEXT)
        val codeDimension = getExpectedDimension(ModelTypeEnum.EMBEDDING_CODE)

        val metadata = WeaviateSchemaMetadata(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            distance = DISTANCE_METRIC,
            ef = HNSW_EF,
            efConstruction = HNSW_EF_CONSTRUCTION,
            maxConnections = HNSW_MAX_CONNECTIONS,
            dynamicEfMin = HNSW_DYNAMIC_EF_MIN,
            dynamicEfMax = HNSW_DYNAMIC_EF_MAX,
            dynamicEfFactor = HNSW_DYNAMIC_EF_FACTOR,
            flatSearchCutoff = HNSW_FLAT_SEARCH_CUTOFF,
            textDimension = textDimension,
            codeDimension = codeDimension,
            migratedAt = Instant.now(),
            previousVersion = previousVersion,
            migrationReason = "Schema configuration changed",
        )

        schemaMetadataRepo.save(metadata)
        logger.info { "Schema metadata saved: version=$CURRENT_SCHEMA_VERSION" }
    }

    /**
     * Get expected embedding dimension for model type.
     */
    private fun getExpectedDimension(type: ModelTypeEnum): Int {
        val modelList = modelsProperties.models[type] ?: emptyList()
        val firstModel =
            modelList.firstOrNull()
                ?: throw IllegalArgumentException("No models configured for type: $type")
        return firstModel.dimension
            ?: throw IllegalArgumentException("No dimension configured for model type: $type")
    }
}
