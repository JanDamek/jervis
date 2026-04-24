package com.jervis.infrastructure.text

import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import jakarta.annotation.PostConstruct

/**
 * Intelligent Czech text normalizer with two independent corrections:
 *
 * 1. **CZ-digit sequences**: Detects sequences of CZ-layout digit characters
 *    (ě=2, š=3, č=4, ř=5, ž=6, ý=7, á=8, í=9, é=0) and converts them to digits.
 *    Conversion requires (a) length ≥3, (b) standalone — not embedded in a word
 *    (no letter directly before/after), (c) the literal sequence is not a known
 *    Czech word. Without these guards, real words like "běží" would become "b269"
 *    and "šíří" would become "3959".
 *
 * 2. **Y↔Z word correction**: Per-word check using Czech dictionary (cs_CZ Hunspell).
 *    If a word is not in dictionary but the Y↔Z swapped version IS → correct it.
 *    Never does global Y↔Z swap — only per-word with dictionary validation.
 *
 * These are independent — text can have CZ-digit bank data AND normal Czech words.
 * The normalizer handles both without breaking either.
 */
@Component
class CzechKeyboardNormalizer {

    private val logger = KotlinLogging.logger {}

    // Czech dictionary (loaded from cs_words.txt.gz resource)
    private var czechWords: Set<String> = emptySet()

    // CZ keyboard number row: diacritic → digit
    private val czDigitMap: Map<Char, Char> = mapOf(
        '+' to '1',   // dead key position
        'ě' to '2',
        'š' to '3',
        'č' to '4',
        'ř' to '5',
        'ž' to '6',
        'ý' to '7',
        'á' to '8',
        'í' to '9',
        'é' to '0',
    )

    // Characters that can appear in CZ-layout numeric sequences
    private val czDigitChars: Set<Char> = czDigitMap.keys + setOf(' ', ',', '.', '-', '/', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0')

    @PostConstruct
    fun loadDictionary() {
        try {
            val resource = javaClass.classLoader.getResourceAsStream("cs_words.txt.gz")
            if (resource != null) {
                val words = mutableSetOf<String>()
                val reader = java.io.BufferedReader(InputStreamReader(GZIPInputStream(resource), Charsets.UTF_8))
                reader.use { r ->
                    r.lineSequence().forEach { line ->
                        val word = line.trim().lowercase()
                        if (word.isNotEmpty()) words.add(word)
                    }
                }
                czechWords = words
                logger.info { "Czech dictionary loaded: ${words.size} words" }
            } else {
                logger.warn { "Czech dictionary not found (cs_words.txt.gz)" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load Czech dictionary" }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    fun convertIfMistyped(input: String): String {
        if (input.isEmpty()) return input

        var result = input

        // Step 1: Convert CZ-digit sequences (bank statements, IDs, amounts)
        result = convertCzDigitSequences(result)

        // Step 2: Per-word Y↔Z correction with dictionary check
        if (czechWords.isNotEmpty()) {
            result = correctYZPerWord(result)
        }

        if (result != input) {
            logger.debug { "Normalized: '${input.take(80)}' → '${result.take(80)}'" }
        }
        return result
    }

    // ── CZ-digit sequence conversion ────────────────────────────────────

    /**
     * Find sequences of CZ-layout digit characters and convert to real digits.
     * A "numeric sequence" = 3+ chars that are all CZ-digit mappable.
     * Converts: "ěšé1řšžčžř" → "2301567890", "7 ééé,éé" → "7 000,00"
     */
    private fun convertCzDigitSequences(input: String): String {
        // Split by lines — bank statements are often line-by-line
        return input.lines().joinToString("\n") { line ->
            convertCzDigitsInLine(line)
        }
    }

    private fun convertCzDigitsInLine(line: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < line.length) {
            val seqEnd = findCzDigitSequenceEnd(line, i)
            val shouldConvert = seqEnd > i &&
                seqEnd - i >= 3 &&
                isStandaloneSequence(line, i, seqEnd) &&
                !isCzechWord(line, i, seqEnd)
            if (shouldConvert) {
                for (j in i until seqEnd) {
                    val ch = line[j]
                    result.append(czDigitMap[ch] ?: ch)
                }
                i = seqEnd
            } else {
                result.append(line[i])
                i++
            }
        }
        return result.toString()
    }

    /**
     * Sequence must not be embedded inside a word — prevents breaking words like
     * "běží" (b + ěží diacritic run) into "b269".
     */
    private fun isStandaloneSequence(s: String, start: Int, end: Int): Boolean {
        val beforeOk = start == 0 || !s[start - 1].isLetter()
        val afterOk = end >= s.length || !s[end].isLetter()
        return beforeOk && afterOk
    }

    /**
     * Dictionary guard — if the literal sequence is a known Czech word, keep it.
     * Prevents breaking standalone words like "šíří", "šéf" that happen to consist
     * entirely of CZ-digit diacritics.
     */
    private fun isCzechWord(s: String, start: Int, end: Int): Boolean {
        if (czechWords.isEmpty()) return false
        return s.substring(start, end).lowercase() in czechWords
    }

    /**
     * Find end of a CZ-digit sequence starting at pos.
     * Sequence must have at least 2 CZ-diacritic digits (ě,š,č,ř,ž,ý,á,í,é).
     */
    private fun findCzDigitSequenceEnd(s: String, start: Int): Int {
        var i = start
        var czDigitCount = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch in czDigitChars) {
                if (ch in czDigitMap) czDigitCount++
                i++
            } else {
                break
            }
        }
        // Only convert if there are enough CZ-diacritic digits (not just regular digits/spaces)
        return if (czDigitCount >= 2) i else start
    }

    // ── Y↔Z per-word correction ─────────────────────────────────────────

    /**
     * For each word: if it's not in Czech dictionary but the Y↔Z swapped version is → swap.
     * Only swaps Y↔Z within the word, nothing else.
     */
    private fun correctYZPerWord(input: String): String {
        val wordPattern = Regex("""[\p{L}]+""")
        return wordPattern.replace(input) { match ->
            val word = match.value
            correctWordYZ(word)
        }
    }

    private fun correctWordYZ(word: String): String {
        val lower = word.lowercase()

        // Already valid Czech word → don't touch
        if (lower in czechWords) return word

        // Try Y↔Z swap variants
        if ('y' !in lower && 'z' !in lower) return word // No Y or Z to swap

        val swapped = lower.map { ch ->
            when (ch) {
                'y' -> 'z'
                'z' -> 'y'
                else -> ch
            }
        }.joinToString("")

        if (swapped in czechWords) {
            // Swapped version is valid Czech — apply swap preserving case
            return word.mapIndexed { i, ch ->
                when {
                    ch == 'y' -> if (swapped[i] == 'z') 'z' else ch
                    ch == 'Y' -> if (swapped[i] == 'z') 'Z' else ch
                    ch == 'z' -> if (swapped[i] == 'y') 'y' else ch
                    ch == 'Z' -> if (swapped[i] == 'y') 'Y' else ch
                    else -> ch
                }
            }.joinToString("")
        }

        // Try partial swaps (only Y→Z or only Z→Y at each position)
        if (lower.count { it == 'y' || it == 'z' } <= 3) {
            val bestVariant = generateYZVariants(lower).firstOrNull { it in czechWords }
            if (bestVariant != null) {
                return word.mapIndexed { i, ch ->
                    val target = bestVariant[i]
                    when {
                        ch.lowercaseChar() != target -> if (ch.isUpperCase()) target.uppercaseChar() else target
                        else -> ch
                    }
                }.joinToString("")
            }
        }

        return word // No valid variant found
    }

    /**
     * Generate all Y↔Z variants of a word (up to 8 variants for 3 Y/Z positions).
     */
    private fun generateYZVariants(word: String): List<String> {
        val positions = word.indices.filter { word[it] == 'y' || word[it] == 'z' }
        if (positions.isEmpty() || positions.size > 3) return emptyList()

        val variants = mutableListOf<String>()
        val chars = word.toCharArray()
        for (mask in 1 until (1 shl positions.size)) {
            val variant = chars.copyOf()
            for ((bit, pos) in positions.withIndex()) {
                if (mask and (1 shl bit) != 0) {
                    variant[pos] = if (variant[pos] == 'y') 'z' else 'y'
                }
            }
            variants.add(String(variant))
        }
        return variants
    }
}
