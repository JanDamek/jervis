package com.jervis.service.maintenance

import com.jervis.dto.maintenance.ConsistencyIssueType
import com.jervis.dto.maintenance.KbConsistencyFinding
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * EPIC 7-S3: KB Consistency Checker Service.
 *
 * Checks the Knowledge Base for duplicate chunks, contradictory information,
 * and stale data. Runs as an idle task to keep KB clean and trustworthy.
 *
 * Detection strategies:
 * 1. Duplicate detection — high cosine similarity between chunks with different URNs
 * 2. Contradiction detection — semantic similarity + negation/conflict patterns
 * 3. Staleness detection — KB chunk timestamps vs. source update timestamps
 */
@Service
class KbConsistencyCheckerService {
    private val logger = KotlinLogging.logger {}

    // Similarity threshold above which chunks are considered duplicates
    private val duplicateThreshold = 0.92
    // Similarity threshold for contradiction candidates
    private val contradictionCandidateThreshold = 0.75

    // Patterns that indicate contradiction between two similar chunks
    private val contradictionPatterns = listOf(
        Regex("""(?i)\bnot\b.*\b(support|allow|enable|use)"""),
        Regex("""(?i)\b(deprecated|removed|replaced)\b"""),
        Regex("""(?i)\b(should not|must not|cannot|no longer)\b"""),
        Regex("""(?i)\b(instead of|rather than|replaced by)\b"""),
    )

    /**
     * Check a batch of KB chunks for consistency issues.
     *
     * @param chunks List of KB chunk data (urn, content, embedding similarity scores)
     * @return List of findings (duplicates, contradictions, stale info)
     */
    fun checkConsistency(chunks: List<KbChunkData>): List<KbConsistencyFinding> {
        val findings = mutableListOf<KbConsistencyFinding>()

        // Phase 1: Detect duplicates via similarity scores
        findings.addAll(detectDuplicates(chunks))

        // Phase 2: Detect contradictions via content analysis
        findings.addAll(detectContradictions(chunks))

        // Phase 3: Detect stale information
        findings.addAll(detectStaleInfo(chunks))

        logger.info {
            "KB_CONSISTENCY | chunks=${chunks.size} | findings=${findings.size} " +
                "(dup=${findings.count { it.type == ConsistencyIssueType.DUPLICATE_CHUNK }}, " +
                "contra=${findings.count { it.type == ConsistencyIssueType.CONTRADICTORY_INFO }}, " +
                "stale=${findings.count { it.type == ConsistencyIssueType.STALE_INFORMATION }})"
        }
        return findings
    }

    private fun detectDuplicates(chunks: List<KbChunkData>): List<KbConsistencyFinding> {
        val findings = mutableListOf<KbConsistencyFinding>()
        val seen = mutableSetOf<String>()

        for (i in chunks.indices) {
            for (j in i + 1 until chunks.size) {
                val key = "${chunks[i].sourceUrn}:${chunks[j].sourceUrn}"
                if (key in seen) continue
                seen.add(key)

                if (chunks[i].sourceUrn == chunks[j].sourceUrn) continue

                val similarity = computeTextSimilarity(chunks[i].content, chunks[j].content)
                if (similarity >= duplicateThreshold) {
                    findings.add(
                        KbConsistencyFinding(
                            type = ConsistencyIssueType.DUPLICATE_CHUNK,
                            sourceUrn1 = chunks[i].sourceUrn,
                            sourceUrn2 = chunks[j].sourceUrn,
                            description = "Chunks are ${(similarity * 100).toInt()}% similar — likely duplicates.",
                            suggestedAction = "Merge or remove the older duplicate (keep ${chunks[i].sourceUrn}).",
                        ),
                    )
                }
            }
        }
        return findings
    }

    private fun detectContradictions(chunks: List<KbChunkData>): List<KbConsistencyFinding> {
        val findings = mutableListOf<KbConsistencyFinding>()

        for (i in chunks.indices) {
            for (j in i + 1 until chunks.size) {
                if (chunks[i].sourceUrn == chunks[j].sourceUrn) continue

                val similarity = computeTextSimilarity(chunks[i].content, chunks[j].content)
                if (similarity < contradictionCandidateThreshold) continue

                // Check for contradiction patterns in one but not the other
                val hasConflict = contradictionPatterns.any { pattern ->
                    val matchesA = pattern.containsMatchIn(chunks[i].content)
                    val matchesB = pattern.containsMatchIn(chunks[j].content)
                    matchesA != matchesB
                }

                if (hasConflict) {
                    findings.add(
                        KbConsistencyFinding(
                            type = ConsistencyIssueType.CONTRADICTORY_INFO,
                            sourceUrn1 = chunks[i].sourceUrn,
                            sourceUrn2 = chunks[j].sourceUrn,
                            description = "Chunks discuss similar topic but contain contradictory statements.",
                            suggestedAction = "Review both chunks and keep the most recent/accurate one.",
                        ),
                    )
                }
            }
        }
        return findings
    }

    private fun detectStaleInfo(chunks: List<KbChunkData>): List<KbConsistencyFinding> {
        val findings = mutableListOf<KbConsistencyFinding>()
        val now = java.time.Instant.now()
        val staleThresholdDays = 90L

        for (chunk in chunks) {
            val ingestedAt = chunk.ingestedAt ?: continue
            try {
                val ingestInstant = java.time.Instant.parse(ingestedAt)
                val daysSinceIngest = java.time.Duration.between(ingestInstant, now).toDays()
                if (daysSinceIngest > staleThresholdDays) {
                    findings.add(
                        KbConsistencyFinding(
                            type = ConsistencyIssueType.STALE_INFORMATION,
                            sourceUrn1 = chunk.sourceUrn,
                            description = "Chunk ingested ${daysSinceIngest} days ago — may be outdated.",
                            suggestedAction = "Re-index from source or verify content is still accurate.",
                        ),
                    )
                }
            } catch (_: Exception) {
                // Skip unparseable timestamps
            }
        }
        return findings
    }

    /**
     * Simple text similarity using Jaccard coefficient on word n-grams.
     * Real implementation would use embedding cosine similarity from Weaviate.
     */
    private fun computeTextSimilarity(text1: String, text2: String): Double {
        val words1 = text1.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val words2 = text2.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (words1.isEmpty() || words2.isEmpty()) return 0.0

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    data class KbChunkData(
        val sourceUrn: String,
        val content: String,
        val ingestedAt: String? = null,
        val category: String? = null,
    )
}
