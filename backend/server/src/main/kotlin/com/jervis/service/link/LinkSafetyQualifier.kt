package com.jervis.service.link

import com.jervis.repository.IndexedLinkMongoRepository
import com.jervis.repository.UnsafeLinkMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.mapNotNull
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI

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
    private val unsafeLinkRepository: UnsafeLinkMongoRepository,
    private val indexedLinkRepository: IndexedLinkMongoRepository,
) {
    private val learnedPatternsCache =
        java.util.concurrent.atomic
            .AtomicReference<List<Regex>?>(null)

    init {
        kotlinx.coroutines.runBlocking {
            refreshPatternsCache()
        }
    }

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
            // Personalized tracking parameters (user-specific IDs that make URL unique per user)
            // NOTE: utm_* parameters are NOT included - they're analytics only, content is still accessible
            "email=", // Personalized email parameter (e.g., newsletter unsubscribe links)
            "email_id=",
            "subscriber_id=",
            "tracking_id=",
            "click_id=",
            "user_id=",
            "recipient=",
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
            "mountfield.cz",
            "hornbach.cz",
            "obi.cz",
            "baumax.cz",
            "datart.cz",
            "electroworld.cz",
            "okay.cz",
            "tsbohemia.cz",
            "heureka.cz",
            "zbozi.cz",
            "amazon.com",
            "ebay.com",
            "aliexpress.com",
            // Appliance manufacturers
            "haier.cz",
            "haier.com",
            "electrolux.cz",
            "electrolux.com",
            "whirlpool.cz",
            "whirlpool.com",
            "bosch-home.com",
            "siemens-home.bsh-group.com",
            "lg.com",
            "samsung.com",
            "miele.cz",
            "miele.com",
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
            // Internal/Company domains
            "tepsivo.com",
            "tepsivo-sdb-internal.com",
            "tepsivo.atlassian.net",
            "tepsivo.slack.com",
            // Google services (docs are safe to read even with /edit URLs)
            "docs.google.com",
            "photos.google.com",
            "drive.google.com",
            // Czech services
            "mapy.cz",
            "seznam.cz",
            "stream.cz",
            "vzp.cz",
            "csob.cz",
            "kb.cz",
            "mbank.cz",
            "zasilkovna.cz",
            "studentagency.cz",
            "regiojet.cz",
            "slevomat.cz",
            "mfdnes.cz",
            "laacr.cz",
            // Community sites
            "community.acer.com",
            "zonglovani.info",
            // Image CDNs
            "pixabay.com",
            "alicdn.com",
            "emlcdn.net",
        )

    /**
     * Refresh in-memory cache of learned patterns from MongoDB.
     * Called on startup and when new patterns are added.
     */
    private suspend fun refreshPatternsCache() {
        val patternsList = mutableListOf<Regex>()

        unsafeLinkRepository
            .findAll()
            .collect { doc ->
                try {
                    patternsList.add(Regex(doc.pattern))
                } catch (e: Exception) {
                    logger.warn { "Invalid regex pattern in DB: ${doc.pattern}" }
                }
            }

        learnedPatternsCache.set(patternsList)
        logger.info { "Loaded ${patternsList.size} learned patterns into cache" }
    }

    /**
     * Get learned regex patterns from in-memory cache (fast).
     * Falls back to MongoDB if cache is not initialized.
     *
     * TODO: RAG INTEGRATION FOR LINK SAFETY PATTERNS
     * ================================================
     * Current: Patterns cached in memory, loaded from MongoDB on startup
     * Goal: Use RAG for dynamic pattern discovery and contextual rules
     *
     * Phase 1: Pattern caching with RAG sync ✅ DONE (in-memory cache)
     * - Cache patterns in memory at startup
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
    private suspend fun getLearnedPatterns(): List<Regex> {
        // Return cached patterns if available (fast path)
        learnedPatternsCache.get()?.let { return it }

        // Fallback: load from MongoDB and cache (slow path, should rarely happen)
        logger.warn { "Patterns cache not initialized, loading from MongoDB" }
        refreshPatternsCache()
        return learnedPatternsCache.get() ?: emptyList()
    }

    /**
     * Determine if a link is safe to index using FAST HEURISTICS ONLY.
     *
     * NEW ARCHITECTURE (aligned with rules.md):
     * - NO synchronous LLM calls (non-blocking)
     * - Uses only: cache, patterns, blacklists, whitelists
     * - Returns UNCERTAIN for ambiguous cases → creates pending task
     * - Pending task uses centralized TaskQualificationService (CPU-only)
     *
     * Optimized workflow:
     * 1. Check if already indexed → SAFE
     * 2. Check UNSAFE cache → UNSAFE
     * 3. Check learned regex patterns → UNSAFE
     * 4. Pattern/domain matching (blacklist/whitelist)
     * 5. Heuristic checks (query params, tokens)
     * 6. If unclear → UNCERTAIN (no LLM call!)
     *
     * @param url The URL to qualify
     * @param contextBefore Text before the link (kept for future use, not used in heuristics)
     * @param contextAfter Text after the link (kept for future use, not used in heuristics)
     * @param correlationId Optional correlation ID for tracking
     */
    suspend fun qualifyLink(
        url: String,
        contextBefore: String? = null,
        contextAfter: String? = null,
        correlationId: String? = null,
    ): SafetyResult {
        // Level -1: Reject mailto: links immediately (should never reach here)
        if (url.startsWith("mailto:", ignoreCase = true)) {
            return SafetyResult(
                SafetyResult.Decision.UNSAFE,
                "mailto: links are not indexable (email addresses, not web content)",
            )
        }
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
        // Uses in-memory cache for performance (no MongoDB query per link)
        val learnedPatterns = getLearnedPatterns()
        learnedPatterns.firstOrNull { regex -> regex.containsMatchIn(url) }?.let { matchedRegex ->
            logger.debug { "Link matches learned pattern: $url" }
            return SafetyResult(
                SafetyResult.Decision.UNSAFE,
                "Matches learned pattern: ${matchedRegex.pattern}",
            )
        }

        val uri =
            try {
                URI.create(url)
            } catch (e: IllegalArgumentException) {
                logger.debug { "Invalid URL syntax: $url - ${e.message}" }
                return SafetyResult(
                    SafetyResult.Decision.UNSAFE,
                    "Invalid URL syntax (contains illegal characters): ${e.message}",
                )
            }
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
            return SafetyResult(
                SafetyResult.Decision.UNCERTAIN,
                "Contains tracking parameters - needs agent review",
            )
        }

        // Level 7: Domain-based monitoring/analytics detection REMOVED
        // Reason: Too broad - blocks legitimate domains (e.g., haier.cz blocked because "ai" in name)
        // Instead: rely on explicit blacklist domains and query parameter checks

        // Level 8: Token/key parameters - likely one-time action links
        if (query.contains("token=", ignoreCase = true) ||
            query.contains("key=", ignoreCase = true) ||
            query.contains("code=", ignoreCase = true)
        ) {
            return SafetyResult(
                SafetyResult.Decision.UNCERTAIN,
                "Contains token/key/code parameter - likely action link, needs review",
            )
        }

        // Level 9: Any query parameter that looks like identifier/session
        if (query.matches(Regex(".*([a-f0-9]{32,}|[A-Za-z0-9_-]{20,}).*"))) {
            return SafetyResult(
                SafetyResult.Decision.UNCERTAIN,
                "Contains long hash/identifier - potentially personalized, needs review",
            )
        }

        // Default: For whitelisted domains without red flags → safe
        // For unknown domains → UNCERTAIN (no LLM call, create pending task)
        return if (whitelistDomains.any { domain.contains(it, ignoreCase = true) }) {
            SafetyResult(
                SafetyResult.Decision.SAFE,
                "Whitelisted domain with no action patterns detected",
            )
        } else {
            SafetyResult(
                SafetyResult.Decision.UNCERTAIN,
                "Unknown domain - needs agent verification",
            )
        }
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
