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
 *
 * PHILOSOPHY: Better to skip a link than to trigger ANY action.
 * Web scraping must be 100% PASSIVE - no confirmations, no tracking, no state changes.
 *
 * Uses multi-level pessimistic approach:
 * 1. Check if link already indexed (skip qualification)
 * 2. Check UNSAFE cache in MongoDB (skip LLM call)
 * 3. Pattern-based blacklist/whitelist (fast, no LLM)
 * 4. Domain-based classification
 * 5. LLM qualification for uncertain cases (pessimistic, with UNSAFE caching)
 *
 * Blocked categories (UNSAFE):
 * - Unsubscribe/subscribe links (critical!)
 * - Confirmation links (email, calendar, account)
 * - Tracking/monitoring/analytics links
 * - Authentication/session links
 * - Settings/preferences management
 * - Payment/checkout links
 * - Form submissions
 * - Any personalized or per-user URLs
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
    // Philosophy: Better to skip a link than to trigger ANY action
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
            // Subscribe/registration actions
            "subscribe",
            "sign-up",
            "signup",
            "register",
            "registration",
            "join-list",
            "newsletter-signup",
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
            "confirm-account",
            "activate-account",
            "validate-email",
            "verification",
            // Confirmation links (any confirmation is an action!)
            "confirm",
            "confirmation",
            "potvrzeni",
            "potvrzení",
            "potvrd",
            "/confirm/",
            "/confirmation/",
            "?confirm=",
            "&confirm=",
            // Calendar/Invite action links (CRITICAL - caused meetings to be cancelled!)
            // Accept variations
            "action=accept",
            "action=yes",
            "action=approve",
            "accept=yes",
            "accept=true",
            "accept-invite",
            "accept_invite",
            "acceptinvite",
            "accept-event",
            "accept-meeting",
            // Decline variations
            "action=decline",
            "action=no",
            "action=reject",
            "decline=yes",
            "decline=true",
            "decline-invite",
            "decline_invite",
            "declineinvite",
            "decline-event",
            "decline-meeting",
            // Tentative variations
            "action=tentative",
            "action=maybe",
            "tentative=yes",
            "tentative=true",
            "maybe=yes",
            // Response variations
            "respond=accept",
            "respond=decline",
            "respond=tentative",
            "respond=yes",
            "respond=no",
            "respond=maybe",
            "response=accept",
            "response=decline",
            "response=tentative",
            // Status variations
            "status=accepted",
            "status=declined",
            "status=tentative",
            "status=yes",
            "status=no",
            // RSVP variations
            "rsvp",
            "rsvp=yes",
            "rsvp=no",
            "rsvp=maybe",
            "rsvp=accept",
            "rsvp=decline",
            "rsvp=tentative",
            // Meeting/event response paths
            "meetingresponse",
            "meeting-response",
            "meeting_response",
            "eventresponse",
            "event-response",
            "event_response",
            "/calendar/response",
            "/calendar/action",
            "/calendar/rsvp",
            "/calendar/event",
            "/calendar/accept",
            "/calendar/decline",
            "calendar/rsvp",
            "calendar/render",
            "calendar/action",
            // Outlook/Office365 specific
            "event?action=",
            "meeting?action=",
            "event.ics",
            "calendar.ics",
            "/owa/calendar",
            "/calendar.html",
            // Google Calendar specific
            "calendar.google.com/calendar/event",
            "calendar.google.com/calendar/r",
            "calendar.google.com/calendar/render",
            "eventaction=",
            // iCal/ICS specific
            ".ics?",
            "/ical/",
            "/icalendar/",
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
            // Privacy/settings (any settings change is an action)
            "privacy-settings",
            "account-settings",
            "delete-account",
            "deactivate",
            "preferences",
            "settings",
            // Payment/order actions
            "checkout",
            "payment",
            "pay-now",
            "complete-order",
            "finalize-purchase",
            // Form submissions
            "submit",
            "?action=",
            "&action=",
            // One-time tokens (any URL with one-time token is likely an action)
            "one-time",
            "onetime",
            "ott=",
            "otp=",
            // Additional invite/action variants
            "invite-response",
            "meetingrequest",
            "meeting-request",
        )

    // Domains that are blacklisted
    // Philosophy: Block any domain that performs actions or tracks users
    private val blacklistDomains =
        listOf(
            // Email marketing platforms (unsubscribe, tracking)
            "list-manage.com", // Mailchimp
            "list-manage1.com",
            "list-manage2.com",
            "campaignmonitor.com",
            "unsubscribe.sendgrid.net",
            "preferences.sendgrid.net",
            "sendgrid.net",
            "mandrillapp.com",
            "mailgun.info",
            "click.pstmrk.it", // Postmark tracking
            "links.newsletter", // Generic newsletter links
            "mailchimp.com",
            "constantcontact.com",
            "getresponse.com",
            "aweber.com",
            "activecampaign.com",
            // Calendar/Invite and provider action domains (block to avoid RSVP or account actions)
            "mail.google.com",
            "calendar.google.com",
            "accounts.google.com",
            "outlook.office.com",
            "outlook.com",
            "outlook.office365.com",
            "login.microsoftonline.com",
            // Security/tracking wrappers (block; typically tracking/redirector)
            "safelinks.protection.outlook.com",
            "urldefense.proofpoint.com",
            "protect.mimecast.com",
            "lnks.gd",
            "bit.ly",
            "tinyurl.com",
            "ow.ly",
            "t.co", // Twitter redirect
            // Monitoring and analytics services (tracking with tokens)
            "clevermonitor.com",
            "clinks.clevermonitor.com",
            "analytics.google.com",
            "googletagmanager.com",
            "mixpanel.com",
            "segment.com",
            "amplitude.com",
            "hotjar.com",
            "mouseflow.com",
            "fullstory.com",
            "logrocket.com",
            "newrelic.com",
            "datadog.com",
            "sentry.io",
            // Click tracking services
            "click.email",
            "link.email",
            "track.email",
            "redirect.email",
            "go.email",
            // Form/survey platforms (submissions are actions)
            "forms.gle", // Google Forms
            "typeform.com",
            "surveymonkey.com",
            "qualtrics.com",
            "jotform.com",
            "wufoo.com",
            // Payment/checkout platforms
            "checkout.stripe.com",
            "paypal.com/checkout",
            "pay.google.com",
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
     *
     * TODO: RAG INTEGRATION FOR LINK SAFETY PATTERNS
     * ================================================
     * Current: Patterns loaded directly from MongoDB on every check
     * Goal: Use RAG for dynamic pattern discovery and contextual rules
     *
     * Phase 1: Pattern caching with RAG sync
     * - Cache patterns in memory at startup (currently loaded per-check)
     * - Listen to MongoDB changes (ChangeStream) and invalidate cache
     * - Trigger: When agent adds pattern via MCP tool → update cache + store in RAG
     *
     * Phase 2: RAG-based contextual rules
     * - Store pattern metadata in RAG with semantic search
     * - Query: "calendar response links", "unsubscribe patterns", etc.
     * - Agent can discover existing patterns before creating duplicates
     * - Store pattern effectiveness metrics (match rate, false positives)
     *
     * Phase 3: Dynamic rule generation
     * - When scraping finds new UNSAFE link → query RAG for similar patterns
     * - If similar pattern exists → reuse, else → ask LLM + store in RAG
     * - RAG stores: pattern, description, category, examples, metadata
     *
     * Implementation notes:
     * - Use WeaviateLinkSafetyCollection (new collection for link safety rules)
     * - Index patterns by: category, description, example URLs, effectiveness
     * - Agent MCP tool writes to both MongoDB + RAG atomically
     * - Cache invalidation on pattern add/disable/enable
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
    suspend fun qualifyLink(url: String, correlationId: String? = null): SafetyResult {
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
            return qualifyWithLlm(url, "Contains tracking parameters", correlationId)
        }

        // Level 7: Monitoring/analytics domains detection (before LLM)
        val monitoringKeywords = listOf("monitor", "analytics", "track", "click", "beacon", "metric")
        if (monitoringKeywords.any { keyword -> domain.contains(keyword, ignoreCase = true) }) {
            // If monitoring domain has token/key → definitely unsafe
            if (query.contains("token=", ignoreCase = true) || query.contains("key=", ignoreCase = true)) {
                return SafetyResult(
                    SafetyResult.Decision.UNSAFE,
                    "Monitoring/analytics service with authentication token",
                )
            }
        }

        // Level 8: Token/key parameters - likely one-time action links
        if (query.contains("token=", ignoreCase = true) ||
            query.contains("key=", ignoreCase = true) ||
            query.contains("code=", ignoreCase = true)) {
            return qualifyWithLlm(url, "Contains token/key/code parameter - likely action link", correlationId)
        }

        // Level 9: Any query parameter that looks like identifier/session
        if (query.matches(Regex(".*([a-f0-9]{32,}|[A-Za-z0-9_-]{20,}).*"))) {
            return qualifyWithLlm(url, "Contains long hash/identifier - potentially personalized", correlationId)
        }

        // Default: For whitelisted domains without red flags → safe
        // For unknown domains → ask LLM (pessimistic)
        return if (whitelistDomains.any { domain.contains(it, ignoreCase = true) }) {
            SafetyResult(
                SafetyResult.Decision.SAFE,
                "Whitelisted domain with no action patterns detected",
            )
        } else {
            qualifyWithLlm(url, "Unknown domain - needs verification", correlationId)
        }
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
        correlationId: String?,
    ): SafetyResult =
        try {
            val systemPrompt =
                """
                CRITICAL: Web scraping must be 100% PASSIVE. Philosophy: Better to skip than to trigger ANY action.
                IMPORTANT: Many meetings were CANCELLED by scraping accept/decline calendar links!

                You are PESSIMISTIC classifier. Default to UNSAFE unless CERTAIN it's safe.

                UNSAFE = ANY action or tracking (mark UNSAFE if uncertain):
                - ANY calendar/meeting response link (RSVP, accept, decline, tentative) - CRITICAL!
                - Any confirmation link (email, calendar, registration, subscription)
                - Any unsubscribe/subscribe/opt-in/opt-out link
                - Authentication (login, logout, password reset, email verification)
                - Settings/preferences changes
                - Form submissions or data collection
                - Payment/checkout pages
                - Monitoring/analytics/tracking services
                - Click tracking or redirect services
                - URLs with tokens/keys (likely one-time actions)
                - URL shorteners (unknown destination)
                - Any personalized/per-user URL
                - Marketing campaign links with tracking params
                - Account activation or verification
                - Newsletter signup/management

                SAFE = ONLY pure static content (must be certain):
                - Public documentation (no login required)
                - Blog articles (not personalized)
                - News articles
                - Product catalog pages (not checkout)
                - Public GitHub/GitLab repositories
                - Wikipedia articles
                - Stack Overflow questions
                - If page title describes safe content (documentation, article, tutorial)

                CRITICAL RULES:
                - If URL contains calendar/event/meeting/rsvp/accept/decline → UNSAFE (meetings were cancelled!)
                - If URL contains "confirm", "verify", "token", "activate", "click", "track" → UNSAFE
                - If domain suggests tracking/monitoring/analytics → UNSAFE
                - If page title suggests action (confirm, accept, decline, unsubscribe) → UNSAFE
                - If page title describes safe content (docs, article, tutorial) → consider SAFE
                - If uncertain or ambiguous → UNSAFE (pessimistic approach)
                - When in doubt → UNSAFE

                When UNSAFE, suggest regex: "reason. Suggested regex: /pattern/"

                JSON only:
                {"decision": "SAFE" or "UNSAFE", "reason": "brief. Suggested regex: /pattern/ (if UNSAFE)"}
                """.trimIndent()

            val userPrompt = "URL: $url\nContext: $context\n\nSafe to download?"

            val result = qualifierGateway.qualifyGeneric(
                systemPrompt,
                userPrompt,
                SafetyResult::class.java,
                correlationId = correlationId ?: org.bson.types.ObjectId.get().toHexString()
            )

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
                description = description,
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
