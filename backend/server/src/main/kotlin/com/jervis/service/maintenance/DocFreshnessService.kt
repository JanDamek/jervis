package com.jervis.service.maintenance

import com.jervis.dto.maintenance.DocFreshnessResult
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * EPIC 7-S5: Documentation Freshness Service.
 *
 * Compares documentation update timestamps against related code change
 * timestamps to detect stale documentation.
 *
 * Strategy:
 * 1. Query KB for documentation chunks (wiki pages, READMEs, API docs)
 * 2. For each doc chunk, find related code chunks via KB graph relations
 * 3. Compare timestamps — flag docs where code changed significantly after doc
 * 4. Generate freshness report with staleness score
 */
@Service
class DocFreshnessService {
    private val logger = KotlinLogging.logger {}

    // Docs stale after this many days since related code changed
    private val staleThresholdDays = 30L
    // Docs critically stale after this many days
    private val criticalThresholdDays = 90L

    /**
     * Check documentation freshness against related code changes.
     *
     * @param docEntries Documentation KB entries with timestamps
     * @param codeChangeMap Map of code path → last change timestamp
     * @return List of stale documentation findings
     */
    fun checkFreshness(
        docEntries: List<DocEntry>,
        codeChangeMap: Map<String, String>,
    ): List<DocFreshnessResult> {
        val results = mutableListOf<DocFreshnessResult>()
        val now = Instant.now()

        for (doc in docEntries) {
            val docUpdateInstant = parseTimestamp(doc.lastUpdated) ?: continue

            // Find related code paths (from KB graph or path matching)
            val relatedCodePaths = findRelatedCodePaths(doc, codeChangeMap.keys.toList())
            if (relatedCodePaths.isEmpty()) continue

            // Check if any related code was updated after the documentation
            val latestCodeChange = relatedCodePaths
                .mapNotNull { path -> codeChangeMap[path]?.let { parseTimestamp(it) } }
                .maxOrNull() ?: continue

            if (latestCodeChange.isAfter(docUpdateInstant)) {
                val staleDays = Duration.between(docUpdateInstant, now).toDays().toInt()
                val daysSinceCodeChange = Duration.between(docUpdateInstant, latestCodeChange).toDays().toInt()

                if (daysSinceCodeChange >= staleThresholdDays) {
                    results.add(
                        DocFreshnessResult(
                            docPath = doc.path,
                            lastDocUpdate = doc.lastUpdated,
                            lastCodeUpdate = latestCodeChange.toString(),
                            staleDays = staleDays,
                            affectedCodePaths = relatedCodePaths,
                        ),
                    )
                }
            }
        }

        val critical = results.count { it.staleDays >= criticalThresholdDays }
        logger.info {
            "DOC_FRESHNESS | docs=${docEntries.size} | stale=${results.size} | critical=$critical"
        }
        return results.sortedByDescending { it.staleDays }
    }

    /**
     * Classify freshness result severity.
     */
    fun classifySeverity(result: DocFreshnessResult): FreshnessSeverity {
        return when {
            result.staleDays >= criticalThresholdDays -> FreshnessSeverity.CRITICAL
            result.staleDays >= staleThresholdDays * 2 -> FreshnessSeverity.WARNING
            result.staleDays >= staleThresholdDays -> FreshnessSeverity.INFO
            else -> FreshnessSeverity.OK
        }
    }

    /**
     * Generate a summary report for freshness findings.
     */
    fun generateReport(findings: List<DocFreshnessResult>): String {
        if (findings.isEmpty()) return "All documentation is up-to-date."

        val sb = StringBuilder()
        sb.appendLine("## Documentation Freshness Report")
        sb.appendLine()
        sb.appendLine("Found ${findings.size} stale documentation entries:")
        sb.appendLine()

        for ((i, finding) in findings.withIndex()) {
            val severity = classifySeverity(finding)
            val icon = when (severity) {
                FreshnessSeverity.CRITICAL -> "!!"
                FreshnessSeverity.WARNING -> "!"
                FreshnessSeverity.INFO -> "~"
                FreshnessSeverity.OK -> "OK"
            }
            sb.appendLine("${i + 1}. [$icon] ${finding.docPath} — stale ${finding.staleDays} days")
            sb.appendLine("   Last doc update: ${finding.lastDocUpdate}")
            sb.appendLine("   Last code update: ${finding.lastCodeUpdate}")
            if (finding.affectedCodePaths.isNotEmpty()) {
                sb.appendLine("   Affected code: ${finding.affectedCodePaths.joinToString(", ")}")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Find code paths related to a documentation entry.
     * Uses path-based heuristics and KB graph relations.
     */
    private fun findRelatedCodePaths(
        doc: DocEntry,
        allCodePaths: List<String>,
    ): List<String> {
        val related = mutableListOf<String>()

        // Heuristic 1: Same directory prefix
        val docDir = doc.path.substringBeforeLast("/", "")
        if (docDir.isNotEmpty()) {
            related.addAll(
                allCodePaths.filter { it.startsWith(docDir) && !it.endsWith(".md") },
            )
        }

        // Heuristic 2: KB graph relations (from doc.relatedPaths)
        related.addAll(doc.relatedCodePaths.filter { it in allCodePaths })

        return related.distinct().take(10)
    }

    private fun parseTimestamp(ts: String): Instant? {
        return try {
            Instant.parse(ts)
        } catch (_: Exception) {
            null
        }
    }

    data class DocEntry(
        val path: String,
        val lastUpdated: String,
        val content: String = "",
        val relatedCodePaths: List<String> = emptyList(),
    )

    enum class FreshnessSeverity {
        OK, INFO, WARNING, CRITICAL,
    }
}
