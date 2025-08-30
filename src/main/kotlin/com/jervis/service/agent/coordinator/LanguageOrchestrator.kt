package com.jervis.service.agent.coordinator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jervis.domain.model.ModelType
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.gateway.LlmGateway
import org.springframework.stereotype.Service

@Service
class LanguageOrchestrator(
    private val llmGateway: LlmGateway,
) {
    private val mapper = jacksonObjectMapper()

    data class ScopeDetectionResult(
        val client: String?,
        val project: String?,
        val englishText: String,
        val originalLanguage: String,
        val reason: String? = null,
    )

    suspend fun detectScopeAndTranslate(
        text: String,
        clientHint: String?,
        projectHint: String?,
        clients: List<ClientDocument>,
        projects: List<ProjectDocument>,
    ): ScopeDetectionResult {
        val catalog = buildCatalog(clients, projects)
        val prompt = buildDetectionPrompt(catalog, text, clientHint, projectHint)
        val answer = llmGateway.callLlm(type = ModelType.TRANSLATION, userPrompt = prompt).answer.trim()
        val parsed = safeParse(answer)
        return ScopeDetectionResult(
            client = parsed.client ?: clientHint,
            project = parsed.project ?: projectHint,
            englishText = parsed.englishText ?: "",
            originalLanguage = parsed.originalLanguage ?: "",
            reason = parsed.reason,
        )
    }

    private fun buildCatalog(
        clients: List<ClientDocument>,
        projects: List<ProjectDocument>,
    ): List<ClientEntry> {
        val byClient = projects.groupBy { it.clientId }
        return clients.map { c ->
            val projs = byClient[c.id].orEmpty()
            ClientEntry(
                name = c.name,
                projects = projs.map { ProjectEntry(name = it.name, description = it.description.orEmpty()) },
                description = c.description.orEmpty(),
            )
        }
    }

    private fun buildDetectionPrompt(
        catalog: List<ClientEntry>,
        text: String,
        clientHint: String?,
        projectHint: String?,
    ): String {
        val preselectedClient = clientHint?.trim()?.takeIf { it.isNotBlank() }
        val preselectedProject = projectHint?.trim()?.takeIf { it.isNotBlank() }

        val baseCatalog =
            preselectedClient
                ?.let { clientName ->
                    catalog.filter { it.name.equals(clientName, ignoreCase = true) }.ifEmpty { catalog }
                }
                ?: catalog
        val limitedCatalog =
            preselectedProject?.let { pn ->
                baseCatalog.map { c ->
                    val filtered = c.projects.filter { it.name.equals(pn, ignoreCase = true) }
                    c.copy(projects = if (filtered.isEmpty()) c.projects else filtered)
                }
            } ?: baseCatalog

        val allowedClients = limitedCatalog.map { it.name }
        val clientsList = allowedClients.joinToString(", ")
        val projectsByClient = limitedCatalog.associate { it.name to it.projects.map { p -> p.name } }
        val projectsLines =
            projectsByClient.entries.joinToString("\n") { (cname, projs) ->
                val values = if (projs.isEmpty()) "(none)" else projs.joinToString(", ")
                "- $cname: $values"
            }
        val clientsStr =
            limitedCatalog.joinToString("\n") { client ->
                if (client.projects.isEmpty()) {
                    "(none)"
                } else {
                    client.projects.joinToString(
                        "; ",
                    ) { p -> "${p.name}: ${p.description}" }
                }
                "- Client: ${client.name} (${client.description}) | Projects: $projectsLines"
            }

        val instruction =
            """
You are a strict classifier. Choose the most relevant client and one of its projects for the user's request AND provide the English translation.
Respond ONLY with compact JSON with keys: client, project, englishText, originalLanguage, reason. No comments or extra text. Include a short reason explaining the choice based on the user request and catalog.
Constraints:
- client MUST be exactly one of: [$clientsList] (or empty if none fits).
- project MUST be exactly one of the listed projects for the chosen client (or empty if none fits).
- Do not invent names outside the catalog.
Catalog:
$clientsStr

Allowed projects by client:
$projectsLines

User request:
$text

JSON:
""".trim()
        return instruction
    }

    private fun safeParse(answer: String): DetectionResult =
        try {
            mapper.readValue<DetectionResult>(answer)
        } catch (_: Exception) {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start >= 0 && end > start) mapper.readValue(answer.substring(start, end + 1)) else DetectionResult()
        }

    /**
     * General text generation entry. Thin wrapper around LlmGateway.
     */
    suspend fun generate(
        type: ModelType,
        userPrompt: String,
        systemPrompt: String? = null,
        outputLanguage: String? = null,
        quick: Boolean = false,
    ): String =
        llmGateway
            .callLlm(
                type = type,
                userPrompt = userPrompt,
                systemPrompt = systemPrompt,
                outputLanguage = outputLanguage,
                quick = quick,
            ).answer
            .trim()

    /**
     * Translate provided text to the target language (ISO-639-1). Always attempts translation; returns original on failure/blank.
     */
    suspend fun translate(
        text: String,
        targetLang: String,
        quick: Boolean = false,
    ): String {
        val lang = targetLang.lowercase()
        val prompt = "Translate the following text to the target language. Return only the translation.\n$text"
        return llmGateway
            .callLlm(
                type = ModelType.TRANSLATION,
                userPrompt = prompt,
                outputLanguage = lang,
                quick = quick,
            ).answer
            .trim().ifBlank { text }
    }
}

private data class ClientEntry(
    val name: String,
    val projects: List<ProjectEntry>,
    val description: String,
)

private data class ProjectEntry(
    val name: String,
    val description: String,
)

private data class DetectionResult(
    val client: String? = null,
    val project: String? = null,
    val englishText: String? = null,
    val originalLanguage: String? = null,
    val reason: String? = null,
)
