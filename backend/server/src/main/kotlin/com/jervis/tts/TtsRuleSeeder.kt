package com.jervis.tts

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Idempotent seeding of default TTS rules.
 *
 * Runs on every `ApplicationReady`; for each default rule, inserts it only
 * if the same `(type, description)` (or `(type, acronym)` for acronyms)
 * doesn't already exist at GLOBAL scope. User edits to an existing rule
 * are preserved — we don't overwrite. Missing rules get topped up, which
 * lets us ship new seeds (or fix broken ones) by adding / renaming entries
 * without a separate migration.
 *
 * Rule taxonomy:
 *  - STRIP rules (deletion): Mongo ObjectId / UUID / long alnum blobs,
 *    plus markdown heading / bullet prefixes whose markers carry no
 *    content.
 *  - REPLACE rules (substitution with a word or `\1` backref): markdown
 *    bold / italic / inline code → inner text; URL / path / email →
 *    descriptor word.
 *  - ACRONYM rules (spell-out): BMS → bé-em-es, API → ej-pí-aj, etc.
 */
@Component
class TtsRuleSeeder(
    private val repo: TtsRuleRepository,
    private val service: TtsRuleService,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        runBlocking {
            var inserted = 0
            for (rule in defaultRules()) {
                val existing = when (rule.type) {
                    TtsRuleType.ACRONYM ->
                        rule.acronym?.let {
                            repo.findFirstByTypeAndAcronymAndScopeType(rule.type, it, rule.scope.type)
                        }
                    TtsRuleType.STRIP, TtsRuleType.REPLACE ->
                        rule.description?.let {
                            repo.findFirstByTypeAndDescriptionAndScopeType(rule.type, it, rule.scope.type)
                        }
                }
                if (existing == null) {
                    service.add(rule)
                    inserted++
                }
            }
            logger.info { "TTS_RULE_SEED: inserted $inserted new default rules (others already present)." }
        }
    }

    private fun defaultRules(): List<TtsRuleDocument> {
        val scope = TtsRuleScope(TtsRuleScopeType.GLOBAL)
        val any = "any"
        val cs = "cs"
        return listOf(
            // ── Strip (pure deletions) ────────────────────────────────
            strip(scope, any, "MongoObjectId", pattern = """\b[0-9a-fA-F]{24}\b""", stripParens = true),
            strip(
                scope, any, "UUID",
                pattern = """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""",
                stripParens = true,
            ),
            strip(
                scope, any, "Long alphanumeric blob (10+ chars mixing letters and digits)",
                pattern = """\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\d)[A-Za-z0-9]{10,}\b""",
                stripParens = true,
            ),
            strip(scope, any, "Markdown heading prefix (#)", pattern = """^#+\s*"""),
            strip(scope, any, "Markdown bullet dash at line start", pattern = """^\s*-\s+"""),
            // ── Replace (keep inner / substitute descriptor) ─────────
            replaceWithBackref(scope, any, "Markdown bold (**text**)", pattern = """\*\*([^*]+)\*\*"""),
            replaceWithBackref(scope, any, "Markdown italic (*text*)", pattern = """(?<!\*)\*([^*]+)\*(?!\*)"""),
            replaceWithBackref(scope, any, "Markdown inline code (`text`)", pattern = """`([^`]+)`"""),
            replace(scope, any, "URL", pattern = """https?://\S+""", replacement = "odkaz"),
            replace(scope, any, "File path (unix)", pattern = """(?:/[A-Za-z0-9._-]+){2,}""", replacement = "soubor"),
            replace(scope, any, "Email address", pattern = """\b[\w.+-]+@[\w-]+\.[\w.-]+\b""", replacement = "e-mail"),
            // Word separators — `/` and `|` glue words together in TTS,
            // surrounding them with spaces lets each side be tokenised
            // and possibly matched by an acronym rule. Runs AFTER URL /
            // path replaces so real paths are already gone.
            replace(scope, any, "Slash word separator", pattern = """\s*/\s*""", replacement = " / "),
            replace(scope, any, "Pipe word separator", pattern = """\s*\|\s*""", replacement = " | "),
            // Unicode punctuation the XTTS tokenizer crashes on — normalise
            // to ASCII equivalents. These must run as REPLACE (not STRIP)
            // so the surrounding text stays intact.
            replace(scope, any, "Non-breaking space (U+202F / U+00A0)", pattern = "[\\u00A0\\u202F]", replacement = " "),
            replace(scope, any, "Non-breaking hyphen (U+2011)", pattern = "\\u2011", replacement = "-"),
            replace(scope, any, "En dash (U+2013)", pattern = "\\u2013", replacement = "-"),
            replace(scope, any, "Em dash (U+2014)", pattern = "\\u2014", replacement = "-"),
            replace(scope, any, "Horizontal ellipsis (U+2026)", pattern = "\\u2026", replacement = "..."),
            replace(scope, any, "Curly quotes → ASCII", pattern = "[\\u2018\\u2019\\u201A\\u201B\\u2032]", replacement = "'"),
            replace(scope, any, "Curly double quotes → ASCII", pattern = "[\\u201C\\u201D\\u201E\\u201F\\u2033]", replacement = "\""),
            // ── Acronyms (Czech reading) ─────────────────────────────
            acronym(scope, cs, "BMS", "bé-em-es", listOf("bms", "Bms")),
            acronym(scope, cs, "SBO", "es-bé-ó", listOf("sbo", "Sbo")),
            acronym(scope, cs, "VD", "vé-dé"),
            acronym(scope, cs, "MMB", "em-em-bé", listOf("mmb", "Mmb")),
            acronym(scope, cs, "ČR", "čé-er"),
            acronym(scope, cs, "DPH", "dé-pé-há"),
            acronym(scope, cs, "IČO", "í-čé-ó"),
            acronym(scope, cs, "DIČ", "dé-í-čé"),
            acronym(scope, cs, "TUD", "té-ú-dé", listOf("tud", "Tud")),
            // ── Acronyms (English reading for common IT) ─────────────
            acronym(scope, any, "API", "ej-pí-aj"),
            acronym(scope, any, "HTTP", "ejč-tí-tí-pí"),
            acronym(scope, any, "URL", "jú-ár-el"),
            acronym(scope, any, "SQL", "es-kvé-el"),
            acronym(scope, any, "JSON", "džej-sn"),
            acronym(scope, any, "GPU", "dží-pí-jú"),
            acronym(scope, any, "CPU", "sí-pí-jú"),
            acronym(scope, any, "PDF", "pí-dý-ef"),
            acronym(scope, any, "AI", "ej-aj"),
            acronym(scope, any, "ML", "em-el"),
            acronym(scope, any, "RAM", "rem"),
            acronym(scope, any, "SSD", "es-es-dé"),
        )
    }

    private fun strip(
        scope: TtsRuleScope,
        language: String,
        description: String,
        pattern: String,
        stripParens: Boolean = false,
    ) = TtsRuleDocument(
        type = TtsRuleType.STRIP,
        language = language,
        scope = scope,
        pattern = pattern,
        description = description,
        stripWrappingParens = stripParens,
    )

    private fun replace(
        scope: TtsRuleScope,
        language: String,
        description: String,
        pattern: String,
        replacement: String,
    ) = TtsRuleDocument(
        type = TtsRuleType.REPLACE,
        language = language,
        scope = scope,
        pattern = pattern,
        description = description,
        replacement = replacement,
    )

    /** Replacement that keeps group 1 from the pattern — used for
     *  markdown wrappers where we want the inner text to survive. */
    private fun replaceWithBackref(
        scope: TtsRuleScope,
        language: String,
        description: String,
        pattern: String,
    ) = replace(scope, language, description, pattern, replacement = "\$1")

    private fun acronym(
        scope: TtsRuleScope,
        language: String,
        acronym: String,
        pronunciation: String,
        aliases: List<String> = emptyList(),
    ) = TtsRuleDocument(
        type = TtsRuleType.ACRONYM,
        language = language,
        scope = scope,
        acronym = acronym,
        pronunciation = pronunciation,
        aliases = aliases.takeIf { it.isNotEmpty() },
    )
}
