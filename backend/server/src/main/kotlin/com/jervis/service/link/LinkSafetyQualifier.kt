package com.jervis.service.link

import com.jervis.entity.UnsafeLinkDocument
import com.jervis.entity.UnsafeLinkPatternDocument
import com.jervis.repository.mongo.IndexedLinkMongoRepository
import com.jervis.repository.mongo.UnsafeLinkMongoRepository
import com.jervis.repository.mongo.UnsafeLinkPatternMongoRepository
import com.jervis.service.gateway.QualifierLlmGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Link safety qualifier that determines whether a link should be indexed.
 * Uses multi-level approach:
 * 1. Check if link already indexed (skip qualification)
 * 2. Check UNSAFE cache in MongoDB (skip LLM call)
 * 3. Pattern-based blacklist/whitelist (fast, no LLM)
 * 4. Domain-based classification
 * 5. LLM qualification for uncertain cases (with UNSAFE caching)
 *
 * Purpose: Prevent indexing of:
 * - Unsubscribe links (critical!)
 * - Tracking links
 * - Authentication/session links
 * - Privacy/settings management links
 */
@Service
class LinkSafetyQualifier(
    private val qualifierGateway: QualifierLlmGateway,
    private val unsafeLinkRepository: UnsafeLinkMongoRepository,
    private val indexedLinkRepository: IndexedLinkMongoRepository,
    private val unsafeLinkPatternRepository: UnsafeLinkPatternMongoRepository,
) {
    @kotlinx.serialization.Serializable
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class SafetyResult(
        val decision: Decision,
        val reason: String,
        @com.fasterxml.jackson.annotation.JsonProperty("suggested_regex")
        val suggestedRegex: String? = null,
    ) {
        enum class Decision {
            SAFE, // Index this link
            UNSAFE, // Do not index (unsubscribe, tracking, etc.)
            UNCERTAIN, // Could not determine
        }
    }

    // Image tracking patterns (tracking pixels, beacons)
    private val imageTrackingPatterns =
        listOf(
            "pixel.gif",
            "pixel.png",
            "tracker.gif",
            "tracker.png",
            "beacon.png",
            "beacon.gif",
            "1x1.gif",
            "1x1.png",
            "track.gif",
            "track.png",
            "open.gif",
            "open.png",
            "view.gif",
            "view.png",
            "/pixel/",
            "/track/",
            "/open/",
            "/view/",
            "/beacon/",
            "/img/pixel",
            "/img/track",
            "/img/open",
        )

    // Critical patterns that must NEVER be indexed
    private val blacklistPatterns =
        listOf(
            // Unsubscribe patterns (most critical!)
            "unsubscribe",
            "opt-out",
            "optout",
            "opt_out",
            "remove-me",
            "removeme",
            "remove_me",
            "email-preferences",
            "email_preferences",
            "emailpreferences",
            "manage-subscription",
            "manage_subscription",
            "subscription-preferences",
            "list-unsubscribe",
            "mailinglist",
            "mailing-list",
            // Authentication/session links
            "logout",
            "signout",
            "sign-out",
            "signin",
            "sign-in",
            "login",
            "auth/callback",
            "oauth/callback",
            "reset-password",
            "verify-email",
            "confirm-email",
            "activate-account",
            // Calendar/Invite action links (must not trigger RSVP or auto-actions)
            "action=accept",
            "action=decline",
            "action=tentative",
            "event?action=accept",
            "event?action=decline",
            "event?action=tentative",
            "rsvp",
            "meetingresponse",
            "meeting-response",
            "respond=accept",
            "respond=decline",
            "respond=tentative",
            "/calendar/response",
            "/calendar/action",
            // Action/tracking codes (Czech: kód, kod)
            "/kod/",
            "/code/",
            "/token/",
            "/activate/",
            "/verify/",
            "/action/",
            "/click/",
            "/track/",
            // Tracking parameters
            "utm_source",
            "utm_medium",
            "utm_campaign",
            "email_id",
            "subscriber_id",
            "tracking_id",
            "click_id",
            // Privacy/settings
            "privacy-settings",
            "account-settings",
            "delete-account",
            "deactivate",
            // Additional invite/action variants
            "invite-response",
            "meetingrequest",
            "meeting-request",
            "accept-invite",
            "accept_invite",
            "acceptinvite",
            "decline-invite",
            "decline_invite",
            "declineinvite",
            "calendar/rsvp",
            "/calendar/event",
            "calendar/render",
            "accept=yes",
            "decline=yes",
            "status=accepted",
            "status=declined",
        )

    // Domains that are blacklisted
    private val blacklistDomains =
        listOf(
            "list-manage.com", // Mailchimp
            "list-manage1.com",
            "list-manage2.com",
            "campaignmonitor.com",
            "unsubscribe.sendgrid.net",
            "preferences.sendgrid.net",
            "mandrillapp.com",
            "mailgun.info",
            "click.pstmrk.it", // Postmark tracking
            "links.newsletter", // Generic newsletter links
            // Calendar/Invite and provider action domains (block to avoid RSVP or account actions)
            "mail.google.com",
            "calendar.google.com",
            "accounts.google.com",
            "outlook.office.com",
            "outlook.com",
            // Security/tracking wrappers (block; typically tracking/redirector)
            "safelinks.protection.outlook.com",
            "urldefense.proofpoint.com",
            "protect.mimecast.com",
            "lnks.gd",
        )

    // Known safe domains (documentation, knowledge bases, e-commerce)
    private val whitelistDomains =
        listOf(
            // Development & Documentation
            "github.com",
            "gitlab.com",
            "stackoverflow.com",
            "stackexchange.com",
            "wikipedia.org",
            "docs.oracle.com",
            "developer.mozilla.org",
            "kotlinlang.org",
            "spring.io",
            "jetbrains.com/help",
            "arxiv.org",
            "medium.com",
            "dev.to",
            "reddit.com",
            // E-commerce & Shopping (Czech/International)
            "alza.cz",
            "mall.cz",
            "czc.cz",
            "amazon.com",
            "ebay.com",
            "aliexpress.com",
            // News & Media
            "nytimes.com",
            "bbc.com",
            "cnn.com",
            "irozhlas.cz",
            "idnes.cz",
            "novinky.cz",
            // Social Media (profile/post pages safe, not action links)
            "twitter.com",
            "linkedin.com",
            "facebook.com",
            "instagram.com",
        )

    /**
     * Get learned regex patterns from MongoDB as Flow.
     * Non-blocking, stateless pattern retrieval.
     */
    private fun getLearnedPatterns(): Flow<Regex> =
        unsafeLinkPatternRepository
            .findByEnabledTrue()
            .mapNotNull { doc ->
                try {
                    Regex(doc.pattern)
                } catch (e: Exception) {
                    logger.warn { "Invalid regex pattern in DB: ${doc.pattern}" }
                    null
                }
            }

    /**
     * Determine if a link is safe to index.
     * Optimized workflow:
     * 1. Check if already indexed → SAFE (skip all checks)
     * 2. Check UNSAFE cache → UNSAFE (skip LLM)
     * 3. Check learned regex patterns → UNSAFE (skip LLM)
     * 4. Pattern/domain matching
     * 5. LLM qualification (cache result if UNSAFE + save regex)
     */
    suspend fun qualifyLink(url: String): SafetyResult {
        // Level 0: Check if link already indexed (most efficient - skip everything)
        indexedLinkRepository.findByUrl(url)?.let { indexed ->
            logger.debug { "Link already indexed, skipping qualification: $url" }
            return SafetyResult(
                SafetyResult.Decision.SAFE,
                "Already indexed at ${indexed.lastIndexedAt}",
            )
        }

        // Level 1: Check UNSAFE cache (faster than LLM)
        unsafeLinkRepository.findByUrl(url)?.let { cached ->
            logger.debug { "Link found in UNSAFE cache: $url" }
            return SafetyResult(
                SafetyResult.Decision.UNSAFE,
                "Cached: ${cached.reason}",
            )
        }

        // Level 1.5: Check learned regex patterns (from previous LLM classifications)
        getLearnedPatterns()
            .firstOrNull { regex -> regex.containsMatchIn(url) }
            ?.let { matchedRegex ->
                logger.debug { "Link matches learned pattern: $url" }
                return SafetyResult(
                    SafetyResult.Decision.UNSAFE,
                    "Matches learned pattern: ${matchedRegex.pattern}",
                )
            }

        val uri = URI.create(url)
        val normalizedUrl = url.lowercase()
        val domain = uri.host?.lowercase() ?: ""
        val path = uri.path?.lowercase() ?: ""

        // Level 2: Image tracking detection (tracking pixels, beacons)
        // Check if URL is an image AND matches tracking patterns
        if (path.matches(Regex(".*\\.(gif|png|jpg|jpeg)$"))) {
            imageTrackingPatterns
                .firstOrNull { pattern ->
                    normalizedUrl.contains(pattern, ignoreCase = true)
                }?.let { pattern ->
                    return SafetyResult(
                        SafetyResult.Decision.UNSAFE,
                        "Image tracking pixel detected: $pattern",
                    )
                }
        }

        // Level 3: Blacklist patterns (most important - never index these!)
        blacklistPatterns
            .firstOrNull { pattern ->
                normalizedUrl.contains(pattern, ignoreCase = true)
            }?.let { pattern ->
                return SafetyResult(
                    SafetyResult.Decision.UNSAFE,
                    "Blacklisted pattern: $pattern",
                )
            }

        // Level 4: Blacklist domains
        blacklistDomains
            .firstOrNull { blacklistDomain ->
                domain.contains(blacklistDomain, ignoreCase = true)
            }?.let { blacklistDomain ->
                return SafetyResult(
                    SafetyResult.Decision.UNSAFE,
                    "Blacklisted domain: $blacklistDomain",
                )
            }

        // Level 5: Whitelist domains (safe, no need for LLM)
        whitelistDomains
            .firstOrNull { whitelistDomain ->
                domain.contains(whitelistDomain, ignoreCase = true)
            }?.let { whitelistDomain ->
                return SafetyResult(
                    SafetyResult.Decision.SAFE,
                    "Whitelisted domain: $whitelistDomain",
                )
            }

        // Level 6: Check for tracking parameters
        val query = uri.query ?: ""
        if (query.contains("utm_") ||
            query.contains("email_id") ||
            query.contains("subscriber") ||
            query.contains("tracking")
        ) {
            return qualifyWithLlm(url, "Contains tracking parameters")
        }

        // Level 7: Token/key parameters - use LLM only for authentication-looking patterns
        if (url.contains("?token=") || url.contains("&key=") || url.contains("?key=")) {
            return qualifyWithLlm(url, "Contains token/key parameter")
        }

        // Default: treat as safe if no red flags
        return SafetyResult(
            SafetyResult.Decision.SAFE,
            "No action patterns detected - safe to download",
        )
    }

    /**
     * Use small LLM to determine if link is safe to index.
     * Only called for uncertain cases.
     * When marking UNSAFE, model suggests regex pattern for automatic classification.
     * UNSAFE results are cached in MongoDB to avoid future LLM calls.
     */
    private suspend fun qualifyWithLlm(
        url: String,
        context: String,
    ): SafetyResult =
        try {
            val systemPrompt =
                """
                You are last resort classifier for edge cases. Common patterns already filtered.

                UNSAFE = performs ACTION (state change):
                - Changes user settings/preferences
                - Confirms/activates something
                - Authentication/session related

                SAFE = static content (default):
                - Any informational page
                - Product/article/blog page
                - Documentation

                When UNSAFE, suggest regex: "reason. Suggested regex: /pattern/"

                JSON only:
                {"decision": "SAFE" or "UNSAFE", "reason": "brief. Suggested regex: /pattern/ (if UNSAFE)"}
                """.trimIndent()

            val userPrompt = "URL: $url\nContext: $context\n\nSafe to download?"

            val result = qualifierGateway.qualifyGeneric(systemPrompt, userPrompt, SafetyResult::class.java)

            logger.debug { "LLM qualification for $url: ${result.decision} - ${result.reason}" }

            // Cache UNSAFE results to avoid future LLM calls
            if (result.decision == SafetyResult.Decision.UNSAFE) {
                val reasonWithRegex =
                    if (!result.suggestedRegex.isNullOrBlank() && !result.reason.contains("Suggested regex:")) {
                        result.reason + " Suggested regex: /" + result.suggestedRegex + "/"
                    } else {
                        result.reason
                    }
                cacheUnsafeLink(url, reasonWithRegex)
            }

            result
        } catch (e: Exception) {
            logger.warn { "LLM qualification failed for $url: ${e.message}" }
            // If LLM fails and no blacklist match, assume SAFE (conservative for fetching)
            SafetyResult(
                SafetyResult.Decision.SAFE,
                "LLM failed but no blacklist match - safe to fetch: ${e.message}",
            )
        }

    /**
     * Cache UNSAFE link classification to MongoDB.
     * Extracts suggested regex pattern from reason and stores it as a reusable rule.
     */
    private suspend fun cacheUnsafeLink(
        url: String,
        reason: String,
    ) {
        val regexPattern = extractRegexPattern(reason)

        val unsafeLinkDoc =
            UnsafeLinkDocument(
                url = url,
                reason = reason,
                suggestedRegex = regexPattern,
                createdAt = Instant.now(),
            )

        unsafeLinkRepository.save(unsafeLinkDoc)

        regexPattern?.let { pattern ->
            saveLearnedPattern(pattern, reason, url)
        }

        logger.info { "Cached UNSAFE link: $url${regexPattern?.let { " (learned: $it)" } ?: ""}" }
    }

    /**
     * Extract regex pattern from LLM reason string.
     */
    private fun extractRegexPattern(reason: String): String? =
        if (reason.contains("Suggested regex:", ignoreCase = true)) {
            val regexStart = reason.indexOf("Suggested regex:", ignoreCase = true)
            reason
                .substring(regexStart + "Suggested regex:".length)
                .trim()
                .split("\n", ".", ";")
                .firstOrNull()
                ?.trim()
                ?.removePrefix("/")
                ?.removeSuffix("/")
                ?.trim()
        } else {
            null
        }

    /**
     * Save learned regex pattern to MongoDB if it doesn't exist yet.
     * This allows the pattern to be reused for future link classifications.
     */
    private suspend fun saveLearnedPattern(
        pattern: String,
        description: String,
        exampleUrl: String,
    ) {
        // Validate regex
        runCatching { Regex(pattern) }.getOrElse { e ->
            logger.warn(e) { "Invalid regex pattern: $pattern" }
            return
        }

        // Check if exists
        unsafeLinkPatternRepository.findByPattern(pattern)?.let {
            logger.debug { "Pattern already exists: $pattern" }
            return
        }

        // Save new pattern
        val patternDoc =
            UnsafeLinkPatternDocument(
                pattern = pattern,
                description = description.take(200),
                exampleUrl = exampleUrl,
                matchCount = 1,
                createdAt = Instant.now(),
                lastMatchedAt = Instant.now(),
                enabled = true,
            )

        unsafeLinkPatternRepository.save(patternDoc)

        logger.info { "Saved learned pattern: $pattern (example: $exampleUrl)" }
    }

    /**
     * Batch qualify multiple links.
     * Returns only safe links as Flow for streaming processing.
     */
    fun filterSafeLinks(urls: List<String>): Flow<String> =
        urls
            .asFlow()
            .mapNotNull { url ->
                val result = qualifyLink(url)
                when (result.decision) {
                    SafetyResult.Decision.SAFE -> {
                        logger.debug { "Link safe: $url (${result.reason})" }
                        url
                    }

                    SafetyResult.Decision.UNSAFE -> {
                        logger.info { "Link blocked: $url (${result.reason})" }
                        null
                    }

                    SafetyResult.Decision.UNCERTAIN -> {
                        logger.warn { "Link uncertain: $url (${result.reason}) - treating as unsafe" }
                        null
                    }
                }
            }
}
