package com.jervis.dto.guard

import kotlinx.serialization.Serializable

/**
 * EPIC 14: Anti-Hallucination Guard DTOs.
 *
 * Supports fact-checking pipeline, source attribution,
 * contradiction detection, and confidence scoring.
 */

/**
 * Verification status of a factual claim.
 */
@Serializable
enum class FactVerificationStatus {
    /** Claim verified against KB or git workspace. */
    VERIFIED,
    /** Claim could not be verified but is plausible. */
    UNVERIFIED,
    /** Claim contradicts existing KB data. */
    CONTRADICTED,
}

/**
 * A single factual claim extracted from an LLM response.
 */
@Serializable
data class FactClaim(
    val claim: String,
    val type: FactClaimType,
    val status: FactVerificationStatus = FactVerificationStatus.UNVERIFIED,
    val source: String? = null,
    val confidence: Double = 0.5,
)

@Serializable
enum class FactClaimType {
    FILE_PATH,
    URL,
    API_ENDPOINT,
    CODE_REFERENCE,
    NUMERIC_VALUE,
    DATE,
    GENERAL_FACT,
}

/**
 * Source attribution for a response segment.
 */
@Serializable
data class SourceAttribution(
    val text: String,
    val sourceType: AttributionSourceType,
    val sourceReference: String,
    val confidence: Double = 0.8,
)

@Serializable
enum class AttributionSourceType {
    KB_CHUNK,
    GIT_FILE,
    WEB_SEARCH,
    CHAT_HISTORY,
    AGENT_ESTIMATE,
}

/**
 * Confidence score for an LLM response.
 */
@Serializable
data class ResponseConfidence(
    val overall: Double,
    val kbSupported: Double = 0.0,
    val factCount: Int = 0,
    val verifiedCount: Int = 0,
    val unverifiedCount: Int = 0,
    val contradictedCount: Int = 0,
)

/**
 * Contradiction detection result.
 */
@Serializable
data class ContradictionResult(
    val found: Boolean,
    val existingClaim: String? = null,
    val existingSource: String? = null,
    val newClaim: String? = null,
    val severity: String = "MEDIUM",
)
