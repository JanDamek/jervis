package com.jervis.service.agent.coordinator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jervis.domain.model.ModelType
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.gateway.LlmGateway
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class LanguageOrchestrator(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}
    private val mapper = jacksonObjectMapper()

    suspend fun generate(
        type: ModelType,
        systemPrompt: String? = null,
        userPrompt: String,
        quick: Boolean = false,
    ): String {
        val requestLang = detectLanguage(userPrompt, quick)
        val englishPrompt = translateToEnglish(userPrompt, requestLang, quick)
        logger.debug { "LanguageOrchestrator: detected=$requestLang" }
        return llmGateway
            .callLlm(
                type = type,
                systemPrompt = systemPrompt,
                userPrompt = englishPrompt,
                outputLanguage = requestLang,
                quick = quick,
            ).answer
    }

    suspend fun detectLanguage(
        text: String,
        quick: Boolean = false,
    ): String {
        val prompt = "Return only ISO-639-1 code for language of the following text (no comments, no spaces):\n$text"
        val res = llmGateway.callLlm(type = ModelType.TRANSLATION, userPrompt = prompt, quick = quick).answer
        val code = res.trim().take(5).lowercase()
        return when {
            code.startsWith("en") -> "en"
            code.length == 2 -> code
            else -> "en"
        }
    }

    suspend fun translateToEnglish(
        text: String,
        lang: String,
        quick: Boolean = false,
    ): String {
        if (lang == "en") return text
        val prompt = "Translate the following text to English. Return only the translation.\n$text"
        return llmGateway.callLlm(type = ModelType.TRANSLATION, userPrompt = prompt, quick = quick).answer.trim()
    }

    suspend fun translateToLanguage(
        text: String,
        targetLang: String,
        quick: Boolean = false,
    ): String {
        if (targetLang == "en") return translateToEnglish(text, "cs", quick) // force translation path if needed
        val prompt = "Translate the following text to ${'$'}targetLang (ISO-639-1). Return only the translation.\n$text"
        return llmGateway.callLlm(type = ModelType.TRANSLATION, userPrompt = prompt, quick = quick).answer.trim()
    }

    data class ScopeDetectionResult(
        val client: String?,
        val project: String?,
        val englishText: String?,
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
            englishText = parsed.english_text,
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
                client.projects.joinToString("; ") { p -> "${'$'}{p.name}: ${'$'}{p.description}" }
                "- Client: ${'$'}{client.name} (${'$'}{client.description}) | Projects: ${'$'}projs"
            }

        val instruction =
            """
You are a strict classifier. Choose the most relevant client and one of its projects for the user's request AND provide the English translation.
Respond ONLY with compact JSON with keys: client, project, english_text. No comments or extra text.
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
    val english_text: String? = null,
)
