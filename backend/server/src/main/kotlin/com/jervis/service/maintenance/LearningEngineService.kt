package com.jervis.service.maintenance

import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * EPIC 7-S4: Learning Engine Service.
 *
 * Proactively searches for best practices, patterns, and solutions
 * relevant to each project's technology stack and recent activities.
 *
 * Runs as an idle task. Results are ingested into KB for RAG retrieval.
 *
 * Strategy:
 * 1. Extract tech stack from project KB (languages, frameworks, tools)
 * 2. Generate search queries for best practices and common issues
 * 3. Fetch results via web search (SearXNG)
 * 4. Filter and score relevance
 * 5. Ingest high-quality results into KB as "learned" chunks
 */
@Service
class LearningEngineService {
    private val logger = KotlinLogging.logger {}

    /**
     * Generate search queries based on project technology stack.
     *
     * @param techStack List of technologies detected in the project
     * @param recentIssues Recent issues/topics from bug tracker (for targeted learning)
     * @return List of search queries to execute
     */
    fun generateSearchQueries(
        techStack: List<String>,
        recentIssues: List<String> = emptyList(),
    ): List<LearningQuery> {
        val queries = mutableListOf<LearningQuery>()

        // Best practices queries per technology
        for (tech in techStack.take(5)) {
            queries.add(
                LearningQuery(
                    query = "$tech best practices 2024 2025",
                    category = "best_practices",
                    technology = tech,
                ),
            )
            queries.add(
                LearningQuery(
                    query = "$tech common pitfalls security vulnerabilities",
                    category = "security",
                    technology = tech,
                ),
            )
        }

        // Targeted learning from recent issues
        for (issue in recentIssues.take(3)) {
            val keywords = extractKeywords(issue)
            if (keywords.isNotEmpty()) {
                queries.add(
                    LearningQuery(
                        query = "${keywords.joinToString(" ")} solution best practice",
                        category = "issue_resolution",
                        technology = techStack.firstOrNull() ?: "general",
                    ),
                )
            }
        }

        logger.info {
            "LEARNING_ENGINE | techStack=${techStack.size} | issues=${recentIssues.size} | queries=${queries.size}"
        }
        return queries
    }

    /**
     * Score and filter search results for relevance and quality.
     *
     * @param results Raw search results
     * @param techStack Project tech stack for relevance scoring
     * @return Filtered results suitable for KB ingestion
     */
    fun filterAndScoreResults(
        results: List<SearchResult>,
        techStack: List<String>,
    ): List<ScoredResult> {
        return results
            .map { result ->
                val score = scoreResult(result, techStack)
                ScoredResult(result = result, relevanceScore = score)
            }
            .filter { it.relevanceScore >= 0.4 }
            .sortedByDescending { it.relevanceScore }
            .take(10)
    }

    /**
     * Detect technology stack from KB code chunks.
     *
     * @param codeChunks KB code chunks to analyze
     * @return List of detected technologies
     */
    fun detectTechStack(codeChunks: List<String>): List<String> {
        val techIndicators = mapOf(
            "Kotlin" to listOf("fun ", "val ", "var ", "data class", "suspend fun", "kotlinx"),
            "Java" to listOf("public class", "private void", "import java.", "@Override"),
            "TypeScript" to listOf("interface ", "const ", "export ", ": string", ": number"),
            "JavaScript" to listOf("function ", "const ", "require(", "module.exports"),
            "Python" to listOf("def ", "import ", "class ", "self.", "__init__"),
            "React" to listOf("useState", "useEffect", "JSX.", "React.", "jsx"),
            "Spring Boot" to listOf("@RestController", "@Service", "@Repository", "@SpringBootApplication"),
            "Docker" to listOf("FROM ", "RUN ", "EXPOSE", "ENTRYPOINT", "Dockerfile"),
            "Kubernetes" to listOf("apiVersion:", "kind: Deployment", "kind: Service", "kubectl"),
            "PostgreSQL" to listOf("CREATE TABLE", "SELECT ", "INSERT INTO", "postgresql"),
            "MongoDB" to listOf("MongoRepository", "db.collection", "mongoose", "mongodb"),
        )

        val detected = mutableSetOf<String>()
        val combinedContent = codeChunks.joinToString("\n").take(50000) // Limit processing

        for ((tech, indicators) in techIndicators) {
            val matchCount = indicators.count { indicator ->
                combinedContent.contains(indicator, ignoreCase = false)
            }
            if (matchCount >= 2) {
                detected.add(tech)
            }
        }

        return detected.toList()
    }

    private fun scoreResult(result: SearchResult, techStack: List<String>): Double {
        var score = 0.3 // Base relevance

        // Boost for matching technology mentions
        for (tech in techStack) {
            if (result.title.contains(tech, ignoreCase = true) ||
                result.snippet.contains(tech, ignoreCase = true)
            ) {
                score += 0.2
            }
        }

        // Boost for authoritative domains
        val authoritativeDomains = listOf(
            "stackoverflow.com", "github.com", "dev.to", "medium.com",
            "docs.spring.io", "kotlinlang.org", "reactjs.org", "docker.com",
        )
        if (authoritativeDomains.any { result.url.contains(it) }) {
            score += 0.15
        }

        // Penalize very old content
        if (result.snippet.contains(Regex("""20[12][0-9]"""))) {
            score -= 0.1
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "can", "shall", "to", "of",
            "in", "for", "on", "with", "at", "by", "from", "as", "into",
            "and", "or", "but", "not", "no", "this", "that", "it", "its",
        )
        return text.split(Regex("\\s+"))
            .map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
            .filter { it.length > 3 && it !in stopWords }
            .take(5)
    }

    data class LearningQuery(
        val query: String,
        val category: String,
        val technology: String,
    )

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
    )

    data class ScoredResult(
        val result: SearchResult,
        val relevanceScore: Double,
    )
}
