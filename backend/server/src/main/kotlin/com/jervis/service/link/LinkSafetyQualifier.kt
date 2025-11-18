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
    init {
        // Cleanup bad patterns on startup
        kotlinx.coroutines.runBlocking {
            cleanupBadPatterns()
        }
    }

    /**
     * Remove overly broad or incorrect patterns that cause false positives.
     */
    private suspend fun cleanupBadPatterns() {
        val badPatterns = listOf(
            // Original bad patterns
            "ms|kn|r|b",
            "app",
            "analytics|tracking|monitoring",
            "analytics",
            "tracking",
            "monitoring",
            "email",
            // New bad patterns from LLM false positives
            "bublinkov|f%C3%B3lie|r%C3%A1na|balen%C3%AD", // Czech words in Pixabay URLs
            "appserve/mkt/p", // Google app serve (legitimate)
            "confirm|verify|token|click|track", // Too broad
            "confirm|verify|token|activate|click|track", // Too broad
            "tracking|monitoring|analytics", // Duplicate, too broad
            "platci", // Czech word for "payers" - legitimate VZP.cz
            "tracking|monitoring|analytics/i", // Too broad with regex flag
            "http:///", // Invalid pattern
            "k=[a-f0-9]{32}", // Generic hash parameter - too broad
            "kf", // Too short - matches alicdn.com paths
            "chudy", // Czech name/word
            "tracking|monitoring", // Too broad
            "mapy", // Czech word for "maps" - blocks mapy.cz
            "/mc/", // Marketing campaign - too broad
            "/tracking|monitoring/", // Duplicate
            "photo", // Too broad - blocks Google Photos
            "det=", // Query parameter - too broad
            "unsubscribe|opt-out/, /action=(accept|decline)/, /rsvp=(yes|no)", // Malformed
            "/action=(yes|no)/", // Too broad
            "/action=(accept|decline)|/unsubscribe|/opt-out|/verify|/activate|/click|/track|/rsvp=(yes|no)/",
            "edit", // Too broad - blocks Google Docs edit URLs
            "/action=(accept|decline)|/unsubscribe|/opt-out|/verify|/confirm|/track|/ms|/kn|/r|/b|/[a-z]/",
            "action=(accept|decline)|/unsubscribe|/opt-out", // Missing leading /
            "unsubscribe|action=(accept|decline)", // Too broad without context
            "unsubscribe|action=(accept|decline)|rsvp=(yes|no)", // Too broad
            "/cdn|app|unsubscribe|action=(accept|decline)/", // Too broad
            "/action=(accept|decline)|/rsvp=(yes|no)/", // Too broad
        )

        badPatterns.forEach { pattern ->
            try {
                val deleted = unsafeLinkPatternRepository.deleteByPattern(pattern)
                if (deleted > 0) {
                    logger.warn { "Removed bad pattern '$pattern' (deleted $deleted document(s))" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to remove bad pattern '$pattern'" }
            }
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

        // Level 7: Domain-based monitoring/analytics detection REMOVED
        // Reason: Too broad - blocks legitimate domains (e.g., haier.cz blocked because "ai" in name)
        // Instead: rely on explicit blacklist domains and query parameter checks

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

                You are a BALANCED classifier. Mark SAFE when URL is clearly static content. Mark UNSAFE only for actual action links.

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
                - Any personalized/per-user URL (e.g., email= parameter with specific email address)
                - Account activation or verification
                - Newsletter signup/management

                NOTE: Analytics parameters (utm_source, utm_medium, etc.) do NOT make content unsafe - they're just tracking.
                Static assets from /email/ paths (images, CSS) are SAFE - they're not actions.

                SAFE = Static content without actions (default for most URLs):
                - Public documentation (no login required)
                - Blog articles, news articles, forum discussions
                - Product catalog pages, e-commerce product pages (not checkout)
                - Public GitHub/GitLab repositories, code examples
                - Wikipedia articles, educational content
                - Stack Overflow questions, community forums
                - Business/internal applications (Jira, Slack, internal dashboards)
                - Company websites and internal domains
                - Google Docs /edit URLs (read-only by default)
                - Google Photos, image galleries
                - Maps, search results, tracking packages
                - PDF documents, image files (even in /email/ paths)
                - News with utm_* parameters (tracking doesn't affect content)
                - Czech websites (.cz domains are generally safe)
                - Banking information pages (not login/transactions)

                ASSUME SAFE unless URL explicitly contains action keywords in the PATH or QUERY.

                CRITICAL RULES FOR UNSAFE:
                - URL PATH contains: /unsubscribe, /opt-out, /confirm, /verify, /activate → UNSAFE
                - URL QUERY contains: action=accept, action=decline, rsvp=yes, email=specific@email.com → UNSAFE
                - URL contains: /calendar/event?action=, /meeting/accept, /invite/rsvp → UNSAFE
                - Domain is KNOWN tracking service (analytics.google.com, mixpanel.com) → UNSAFE

                IMPORTANT: Do NOT mark UNSAFE based on:
                - Domain name containing common words (monitor, analytics, track, mapy, photo, edit, cdn, app)
                - File paths (/email/image.jpg, /cdn/file.js, /app/dashboard)
                - Query parameters without action (det=, k=, utm_source=)
                - Czech words or names (platci, chudy, mapy)
                - Generic URL patterns that don't indicate actions

                When in doubt about unknown domains → mark SAFE (most websites are informational).

                FALSE POSITIVES TO AVOID:
                - "edit" in docs.google.com/document/.../edit is SAFE (read-only view)
                - "app" in URL path (e.g., example.com/app/dashboard) is SAFE - it's application path
                - utm_source, utm_medium, utm_campaign parameters are SAFE - just analytics tracking
                - /email/*.png or /email/*.jpg asset URLs are SAFE - static images, not actions
                - Only "action=accept", "email=specific@email.com" are UNSAFE

                When UNSAFE, suggest VERY SPECIFIC regex that matches ONLY the action part:
                - Good examples:
                  * /\/unsubscribe/ (path contains /unsubscribe)
                  * /action=(accept|decline)/ (query param action with specific values)
                  * /\/calendar\/event\?action=/ (specific calendar action URL)
                  * /email=[^&]+@[^&]+/ (email parameter with actual email address)

                - BAD examples (DO NOT USE):
                  * /confirm/ (too broad - matches "confirmation" in any context)
                  * /edit/ (too broad - matches Google Docs edit)
                  * /email/ (too broad - matches /email/image.jpg)
                  * /tracking/ (too broad - matches domain names)
                  * /[a-z]/ (absurdly broad - matches everything)
                  * Czech words like /mapy/, /platci/, /chudy/
                  * Single words without context: /photo/, /cdn/, /app/

                NEVER suggest patterns based on:
                - Domain names or subdomains
                - Common words that appear in paths
                - Language-specific words (Czech, English)
                - File types or CDN paths

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
