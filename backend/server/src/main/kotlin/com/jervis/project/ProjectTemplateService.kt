package com.jervis.project

import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Project technology advisor — returns structured recommendations
 * with pro/contra for each technology choice.
 *
 * Does NOT generate files. The SETUP vertex uses these recommendations
 * to ask the user (via ask_user tool) and then dispatches the coding
 * agent to scaffold the project based on confirmed choices.
 *
 * This keeps the LLM in control of actual code generation (which it
 * does better than hard-coded templates) while providing structured
 * decision points for user confirmation.
 */
@Service
class ProjectTemplateService {

    /**
     * Get technology recommendations based on project requirements.
     *
     * @param requirements Free-text description of what the user wants
     * @return Structured recommendations across all decision dimensions
     */
    fun getRecommendations(requirements: String): ProjectRecommendations {
        val platforms = detectPlatforms(requirements)
        val storage = detectStorage(requirements)
        val features = detectFeatures(requirements)

        return ProjectRecommendations(
            archetype = recommendArchetype(platforms, storage),
            platforms = platforms,
            storage = storage,
            features = features,
            scaffoldingInstructions = buildScaffoldingInstructions(platforms, storage, features),
        )
    }

    /**
     * List available archetypes with descriptions.
     */
    fun listArchetypes(): List<StackArchetype> = ARCHETYPES

    private fun detectPlatforms(req: String): List<PlatformRecommendation> {
        val lower = req.lowercase()
        val platforms = mutableListOf<PlatformRecommendation>()

        if ("android" in lower || "mobile" in lower || "telefon" in lower) {
            platforms.add(PlatformRecommendation(
                platform = "android",
                recommended = true,
                rationale = "Native Android via Kotlin Multiplatform — shared UI with Compose",
            ))
        }
        if ("ios" in lower || "iphone" in lower || "ipad" in lower || "apple" in lower || "mobile" in lower) {
            platforms.add(PlatformRecommendation(
                platform = "ios",
                recommended = true,
                rationale = "iOS via KMP + Compose Multiplatform — shared business logic and UI",
            ))
        }
        if ("web" in lower || "browser" in lower || "prohlížeč" in lower) {
            platforms.add(PlatformRecommendation(
                platform = "web",
                recommended = true,
                rationale = "Web via Kotlin/Wasm + Compose — same codebase, runs in browser",
                alternatives = listOf(
                    Alternative("React/Next.js frontend", "Better SEO, larger ecosystem, but separate codebase"),
                    Alternative("Kotlin/JS", "More mature than Wasm but less performant"),
                ),
            ))
        }
        if ("desktop" in lower || "pc" in lower || "windows" in lower || "mac" in lower || "linux" in lower) {
            platforms.add(PlatformRecommendation(
                platform = "desktop",
                recommended = true,
                rationale = "Desktop JVM via Compose Desktop — native performance, shared UI",
            ))
        }

        // If nothing detected, recommend all
        if (platforms.isEmpty()) {
            platforms.addAll(listOf(
                PlatformRecommendation("android", true, "Default KMP target"),
                PlatformRecommendation("ios", true, "Default KMP target"),
                PlatformRecommendation("desktop", true, "Default KMP target"),
                PlatformRecommendation("web", false,
                    "Kotlin/Wasm is experimental — consider adding later",
                    listOf(Alternative("Add later", "Wait for Wasm stability")),
                ),
            ))
        }

        return platforms
    }

    private fun detectStorage(req: String): List<StorageRecommendation> {
        val lower = req.lowercase()
        val storage = mutableListOf<StorageRecommendation>()

        if ("mongo" in lower || "nosql" in lower || "dokument" in lower || "document" in lower) {
            storage.add(StorageRecommendation(
                technology = "MongoDB",
                recommended = true,
                useCase = "Flexible document storage — good for catalogs, user preferences, notes",
                springDependency = "spring-boot-starter-data-mongodb",
                pros = listOf("Schema-flexible", "Easy JSON storage", "Good for nested data"),
                cons = listOf("No ACID transactions across collections", "No joins"),
            ))
        }
        if ("postgre" in lower || "sql" in lower || "relační" in lower || "relational" in lower) {
            storage.add(StorageRecommendation(
                technology = "PostgreSQL",
                recommended = true,
                useCase = "Relational data — users, borrowing records, relationships between entities",
                springDependency = "spring-boot-starter-data-jpa + postgresql",
                pros = listOf("ACID transactions", "Complex queries/joins", "Mature ecosystem"),
                cons = listOf("Rigid schema", "Migrations needed for changes"),
            ))
        }
        if ("redis" in lower || "cache" in lower || "keš" in lower) {
            storage.add(StorageRecommendation(
                technology = "Redis",
                recommended = false,
                useCase = "Caching layer — session storage, rate limiting",
                springDependency = "spring-boot-starter-data-redis",
                pros = listOf("Extremely fast", "Good for sessions/caching"),
                cons = listOf("Not a primary database", "Data loss on restart without persistence"),
            ))
        }

        // If nothing detected, recommend sensible defaults
        if (storage.isEmpty()) {
            storage.addAll(listOf(
                StorageRecommendation(
                    "PostgreSQL", true,
                    "Primary relational storage for structured data",
                    "spring-boot-starter-data-jpa + postgresql",
                    listOf("ACID", "Joins", "Mature"), listOf("Schema migrations"),
                ),
            ))
        }

        return storage
    }

    private fun detectFeatures(req: String): List<FeatureRecommendation> {
        val lower = req.lowercase()
        val features = mutableListOf<FeatureRecommendation>()

        if ("uživatel" in lower || "user" in lower || "login" in lower || "přihlášení" in lower || "auth" in lower) {
            features.add(FeatureRecommendation(
                feature = "Authentication",
                recommended = true,
                options = listOf(
                    Alternative("Spring Security + JWT", "Standard, stateless, good for API"),
                    Alternative("Spring Security + OAuth2", "Federated login (Google, GitHub)"),
                    Alternative("Session-based", "Simple, but doesn't scale horizontally"),
                ),
            ))
        }
        if ("kniha" in lower || "book" in lower || "library" in lower || "knihovna" in lower ||
            "katalog" in lower || "catalog" in lower
        ) {
            features.add(FeatureRecommendation(
                feature = "External data sources",
                recommended = true,
                options = listOf(
                    Alternative("Google Books API", "Free, large catalog, ISBN lookup"),
                    Alternative("Open Library API", "Open data, community-driven"),
                    Alternative("ČBDB (databázeknih.cz)", "Czech book database — scraping or API if available"),
                    Alternative("Goodreads (unofficial)", "Reviews and ratings, limited API"),
                ),
            ))
        }
        if ("api" in lower || "rest" in lower || "endpoint" in lower) {
            features.add(FeatureRecommendation(
                feature = "API Style",
                recommended = true,
                options = listOf(
                    Alternative("REST + JSON", "Standard, well-tooled, easy to debug"),
                    Alternative("gRPC / kRPC", "Type-safe, fast, good for KMP clients"),
                    Alternative("GraphQL", "Flexible queries, but more complex setup"),
                ),
            ))
        }

        return features
    }

    private fun recommendArchetype(
        platforms: List<PlatformRecommendation>,
        storage: List<StorageRecommendation>,
    ): StackArchetype {
        val hasMultiplePlatforms = platforms.count { it.recommended } > 1
        val hasBackendStorage = storage.isNotEmpty()

        return when {
            hasMultiplePlatforms && hasBackendStorage -> ARCHETYPES.first { it.type == "kmp-spring" }
            hasMultiplePlatforms -> ARCHETYPES.first { it.type == "kmp" }
            hasBackendStorage -> ARCHETYPES.first { it.type == "spring-boot" }
            else -> ARCHETYPES.first { it.type == "kmp-spring" }
        }
    }

    private fun buildScaffoldingInstructions(
        platforms: List<PlatformRecommendation>,
        storage: List<StorageRecommendation>,
        features: List<FeatureRecommendation>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("After user confirms choices, dispatch coding agent with these instructions:")
        sb.appendLine()
        sb.appendLine("## Platforms")
        for (p in platforms.filter { it.recommended }) {
            sb.appendLine("- ${p.platform}: ${p.rationale}")
        }
        sb.appendLine()
        sb.appendLine("## Storage")
        for (s in storage.filter { it.recommended }) {
            sb.appendLine("- ${s.technology}: ${s.springDependency}")
        }
        sb.appendLine()
        sb.appendLine("## Key instructions for coding agent")
        sb.appendLine("- Use Kotlin 2.3.0, Compose 1.9.3, Spring Boot 3.4.x")
        sb.appendLine("- KMP targets: ${platforms.filter { it.recommended }.joinToString { it.platform }}")
        sb.appendLine("- Create proper Gradle multi-module structure")
        sb.appendLine("- Include .gitignore, README.md with setup instructions")
        sb.appendLine("- Do NOT hardcode credentials — use environment variables / application.yml")

        if (features.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Features to scaffold")
            for (f in features) {
                sb.appendLine("- ${f.feature}")
            }
        }

        return sb.toString()
    }

    companion object {
        private val ARCHETYPES = listOf(
            StackArchetype(
                type = "kmp",
                name = "Kotlin Multiplatform + Compose",
                description = "Shared UI and business logic across Desktop, Android, iOS, Web (Wasm)",
                pros = listOf("Single codebase", "Shared UI with Compose", "Native performance"),
                cons = listOf("Compose for iOS/Web is newer", "Smaller ecosystem than native"),
                bestFor = "Apps with shared UI across all platforms",
            ),
            StackArchetype(
                type = "spring-boot",
                name = "Spring Boot (Kotlin) Backend",
                description = "Production backend with REST API, MongoDB, PostgreSQL, and Spring ecosystem",
                pros = listOf("Mature ecosystem", "Auto-configuration", "Spring Data for both Mongo and JPA"),
                cons = listOf("JVM memory overhead", "Startup time"),
                bestFor = "Backend services, APIs, data processing",
            ),
            StackArchetype(
                type = "kmp-spring",
                name = "KMP + Spring Boot (Full-stack)",
                description = "Multi-module: KMP Compose frontend + Spring Boot backend",
                pros = listOf("Full-stack Kotlin", "Shared DTOs", "Type-safe API layer"),
                cons = listOf("Complex build setup", "Two Gradle configurations to maintain"),
                bestFor = "Full-stack apps where you control both client and server",
            ),
        )
    }
}

// --- Data classes ---

@Serializable
data class StackArchetype(
    val type: String,
    val name: String,
    val description: String,
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val bestFor: String = "",
)

@Serializable
data class PlatformRecommendation(
    val platform: String,
    val recommended: Boolean,
    val rationale: String,
    val alternatives: List<Alternative> = emptyList(),
)

@Serializable
data class StorageRecommendation(
    val technology: String,
    val recommended: Boolean,
    val useCase: String,
    val springDependency: String,
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
)

@Serializable
data class FeatureRecommendation(
    val feature: String,
    val recommended: Boolean,
    val options: List<Alternative> = emptyList(),
)

@Serializable
data class Alternative(
    val name: String,
    val description: String,
)

@Serializable
data class ProjectRecommendations(
    val archetype: StackArchetype,
    val platforms: List<PlatformRecommendation>,
    val storage: List<StorageRecommendation>,
    val features: List<FeatureRecommendation>,
    val scaffoldingInstructions: String,
)
