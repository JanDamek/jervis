package com.jervis.service.rag

import com.jervis.entity.mongo.Anonymization
import com.jervis.entity.mongo.SecretsPolicy
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.indexer.MultiEmbeddingService
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.vectordb.ScoredDocument
import com.jervis.service.vectordb.VectorStorageService
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import mu.KotlinLogging

/**
 * Service for orchestrating the RAG (Retrieval-Augmented Generation) process.
 * This service coordinates the retrieval of relevant documents and the generation of responses.
 */
@Service
class RagOrchestrator(
    private val vectorStorageService: VectorStorageService,
    private val multiEmbeddingService: MultiEmbeddingService,
    private val llmCoordinator: LlmCoordinator,
    private val projectRepository: ProjectMongoRepository,
    private val clientRepository: ClientMongoRepository,
) {
    /**
     * Process a query using RAG with client-scoped primary retrieval and optional cross-client inspiration.
     */
    suspend fun processQuery(
        query: String,
        projectId: ObjectId,
        options: Map<String, Any> = emptyMap(),
    ): RagResponse {
        // Resolve project and client
        val project = projectRepository.findById(projectId.toString())
            ?: throw IllegalArgumentException("Project not found: $projectId")
        val clientId = project.clientId
            ?: throw IllegalStateException("Project ${project.id} has no clientId. Migration required.")
        val client = clientRepository.findById(clientId.toString())

        // Effective policies
        val inspirationPolicy = project.overrides.inspirationPolicy
            ?: client?.defaultInspirationPolicy
        val anonConfig: Anonymization? = project.overrides.anonymization ?: client?.defaultAnonymization
        val secretsPolicy: SecretsPolicy? = project.overrides.secretsPolicy ?: client?.defaultSecretsPolicy

        // Build embeddings for both collections
        val textEmb = multiEmbeddingService.generateTextEmbedding(query, forQuery = true)
        val codeEmb = multiEmbeddingService.generateCodeEmbedding(query, forQuery = true)
        val queryEmbeddings = mapOf(
            VectorStorageService.SEMANTIC_TEXT_COLLECTION to textEmb,
            VectorStorageService.SEMANTIC_CODE_COLLECTION to codeEmb,
        )

        // Primary search: same client (optionally same project)
        val primaryFilters = mutableMapOf<String, Any>("clientId" to clientId.toString())
        if (options["scope"] == "project") {
            primaryFilters["projectId"] = projectId.toString()
        }
        val primary = vectorStorageService.searchMultiCollection(
            query = query,
            queryEmbeddings = queryEmbeddings,
            filters = primaryFilters,
            limit = 12
        )

        // Secondary (cross-client inspiration) if allowed
        val secondary: List<ScoredDocument> = if (inspirationPolicy?.allowCrossClientInspiration == true) {
            val raw = vectorStorageService.searchMultiCollection(
                query = query,
                queryEmbeddings = queryEmbeddings,
                filters = emptyMap(),
                limit = 40
            )
            // Filter out current client and apply allow/deny lists
            val foreign = raw.filter { doc ->
                val docClient = (doc.payload["clientId"] as? String)?.takeIf { it.isNotBlank() }
                docClient != null && docClient != clientId.toString()
            }

            val slugCache = mutableMapOf<String, String>() // clientId -> slug
            suspend fun clientSlug(id: String): String? {
                val cached = slugCache[id]
                if (cached != null) return cached
                val slug = clientRepository.findById(id)?.slug ?: ""
                slugCache[id] = slug
                return slug
            }

            val allowedSlugs = inspirationPolicy.allowedClientSlugs
            val deniedSlugs = inspirationPolicy.disallowedClientSlugs

            // Enforce per-foreign-client cap
            val perClientCounters = mutableMapOf<String, Int>()
            val maxPerClient = inspirationPolicy.maxSnippetsPerForeignClient

            val filtered = mutableListOf<ScoredDocument>()
            for (doc in foreign) {
                val docClientId = doc.payload["clientId"] as? String ?: continue
                val slug = clientSlug(docClientId) ?: continue
                if (deniedSlugs.contains(slug)) continue
                if (allowedSlugs.isNotEmpty() && !allowedSlugs.contains(slug)) continue
                val used = perClientCounters.getOrDefault(docClientId, 0)
                if (used >= maxPerClient) continue
                perClientCounters[docClientId] = used + 1
                // Down-weight
                filtered += doc.copy(score = doc.score * 0.7f)
            }
            filtered
        } else emptyList()

        // Merge and take top
        val merged = (primary + secondary)
            .sortedByDescending { it.score }
            .take(15)

        // Build context with anonymization for inspiration-only or foreign-client snippets
        val useGuard = secondary.isNotEmpty()
        val contextBuilder = StringBuilder()
        val sources = mutableListOf<DocumentSource>()

        for (doc in merged) {
            val docClientId = (doc.payload["clientId"] as? String)?.ifBlank { null }
            val isForeign = docClientId != null && docClientId != clientId.toString()
            val inspirationOnly = (doc.payload["inspirationOnly"] as? Boolean) == true
            val label = if (isForeign || inspirationOnly) "INSPIRATION" else "PRIMARY"

            val contentRaw = buildString {
                val qname = doc.payload["qualifiedName"] as? String
                val path = doc.payload["path"] as? String
                val summary = doc.payload["summary"] as? String
                val code = doc.payload["codeExcerpt"] as? String
                if (!summary.isNullOrBlank()) append("Summary: \n$summary\n")
                if (!code.isNullOrBlank()) append("Code:\n$code\n")
                if (!qname.isNullOrBlank()) append("Symbol: $qname\n")
                if (!path.isNullOrBlank()) append("Path: $path\n")
            }.ifBlank { (doc.payload["doc"] as? String) ?: "" }

            val contentProcessed = if (isForeign || inspirationOnly) {
                val withRegex = applyRegexRules(contentRaw, anonConfig)
                val withSecrets = scrubSecrets(withRegex, secretsPolicy)
                withSecrets
            } else contentRaw

            if (contentProcessed.isNotBlank()) {
                contextBuilder.appendLine("[$label]")
                contextBuilder.appendLine(contentProcessed.trim())
                contextBuilder.appendLine()

                sources += DocumentSource(
                    content = contentProcessed.take(120) + "...",
                    metadata = mapOf(
                        "collection" to (doc.collection),
                        "clientId" to (doc.payload["clientId"] ?: ""),
                        "projectId" to (doc.payload["projectId"] ?: ""),
                        "qualifiedName" to (doc.payload["qualifiedName"] ?: ""),
                        "path" to (doc.payload["path"] ?: ""),
                        "contextSource" to label,
                    )
                )
            }
        }

        val contextText = contextBuilder.toString().trim()

        // Compose prompt
        val inspirationNotice = if (useGuard) {
            """
            Passages labeled as INSPIRATION are anonymized. Never output real class, module, package, domain, or email identifiers from those passages. Use generic placeholders.
            """.trimIndent()
        } else ""

        val prompt = """
            Please answer the following query based on the provided context.
            $inspirationNotice

            Query: $query

            Context:
            $contextText

            Provide a comprehensive and accurate answer based only on the information in the context.
        """.trimIndent()

        val llmResponse = llmCoordinator.processQueryBlocking(prompt, contextText)

        val finalAnswer = if (useGuard) applyRegexRules(scrubSecrets(llmResponse.answer, secretsPolicy), anonConfig) else llmResponse.answer

        return RagResponse(
            answer = finalAnswer,
            context = contextText,
            sources = sources,
            finishReason = llmResponse.finishReason,
            promptTokens = llmResponse.promptTokens,
            completionTokens = llmResponse.completionTokens,
            totalTokens = llmResponse.totalTokens,
        )
    }

    private fun applyRegexRules(text: String, anonymization: Anonymization?): String {
        if (anonymization == null || !anonymization.enabled) return text
        var result = text
        anonymization.rules.forEach { rule ->
            val parts = rule.split("->", limit = 2)
            if (parts.size == 2) {
                val pattern = parts[0].trim()
                val replacement = parts[1].trim()
                try { result = result.replace(Regex(pattern), replacement) } catch (_: Exception) {}
            }
        }
        // Generic hardening – replace ClassLikeIdentifiers as last safety net
        return result
            .replace(Regex("\\b[A-Z][A-Za-z0-9_]{2,}\\b"), "[IDENT]")
            .replace(Regex("([a-zA-Z_][\\w.]+)\\.([A-Z][\\w]+)"), "[SYMBOL]")
    }

    private fun scrubSecrets(text: String, policy: SecretsPolicy?): String {
        var result = text
        val patterns = policy?.bannedPatterns ?: listOf(
            "AKIA[0-9A-Z]{16}",
            "xoxb-[0-9a-zA-Z-]+",
            "(?i)api[_-]?key\\s*[:=]\\s*[A-Za-z0-9-_]{20,}"
        )
        patterns.forEach { p ->
            try { result = result.replace(Regex(p), "[SECRET]") } catch (_: Exception) {}
        }
        return result
    }
}

/**
 * Response from the RAG process
 */
data class RagResponse(
    val answer: String,
    val context: String,
    val sources: List<DocumentSource> = emptyList(),
    val finishReason: String = "stop",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

/**
 * Source document used in a RAG response
 */
data class DocumentSource(
    val content: String,
    val metadata: Map<String, Any>,
)
