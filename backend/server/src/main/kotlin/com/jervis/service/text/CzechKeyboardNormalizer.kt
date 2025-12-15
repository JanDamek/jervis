package com.jervis.service.text

import org.springframework.stereotype.Component

/**
 * Detects and fixes Czech text that was typed on a different keyboard layout (EN/US vs CZ QWERTY/QWERTZ).
 *
 * Scope:
 * - No spell-checking. Pure layout normalization using layout maps + heuristics.
 * - Handles typical cross-layout mistakes:
 *   - Number row digits used instead of ě,š,č,ř,ž,ý,á,í,é (CZ: 2..0)
 *   - Caron sequences typed as "+<letter>" (acting like a dead key): +d→ď, +t→ť, +n→ň, +e→ě
 *   - Keys around Enter on US layout used instead of CZ vowels:
 *       ';'→ů, ':'→Ů, '['→ú, '{'→Ú
 *   - Optional Y↔Z swap for QWERTY/QWERTZ mismatch (only when a layout issue is detected)
 *
 * Heuristics:
 * - Build several candidate conversions from layout maps and pick the best one by scoring.
 * - A conversion is accepted only if it clearly improves the text:
 *   reduces suspicious sequences (digits/plus/punct inside words) OR increases the count of CZ diacritics.
 * - Punctuation mapping (; : [ {) is considered only if we detect stronger layout evidence
 *   via digits or +caron sequences, to avoid corrupting code snippets like arr[i].
 */
@Component
class CzechKeyboardNormalizer {
    private val digitToCzLower: Map<Char, Char> = mapOf(
        '2' to 'ě',
        '3' to 'š',
        '4' to 'č',
        '5' to 'ř',
        '6' to 'ž',
        '7' to 'ý',
        '8' to 'á',
        '9' to 'í',
        '0' to 'é',
    )

    // US punctuation keys that correspond to CZ accented vowels on the same physical keys
    private val punctToCzLower: Map<Char, Char> = mapOf(
        ';' to 'ů', // CZ key to the right of L
        '[' to 'ú', // CZ key near P
    )

    private val punctToCzUpper: Map<Char, Char> = mapOf(
        ':' to 'Ů',
        '{' to 'Ú',
    )

    private val caronPlusMap: Map<Char, Char> = mapOf(
        'd' to 'ď',
        't' to 'ť',
        'n' to 'ň',
        // While ě normally comes from the '2' key on CZ layout, people sometimes type +e as a caron dead key sequence
        'e' to 'ě',
    )

    private val czechDiacritics: Set<Char> = (
        "ěščřžýáíéďťňůúóĚŠČŘŽÝÁÍÉĎŤŇŮÚÓ" +
            // Basic accented vowels set extended a bit; harmless for detection
            "äëöüÄËÖÜ"
        ).toSet()

    fun convertIfMistyped(input: String): String {
        if (input.isEmpty()) return input

        // First-level signals: digits and +caron sequences
        val suspDigitsPlusBefore = countSuspiciousDigitsPlus(input)
        val diacriticsBefore = countDiacritics(input)

        // We only consider punctuation mapping if we clearly see layout issues
        // from digits or +caron (prevents corrupting code snippets).
        val allowPunctMapping = suspDigitsPlusBefore > 0

        val candidates = mutableListOf<String>()

        // Base candidate: digits + plus (+ optional punct when allowed), no YZ swap
        candidates += convertInternal(
            input,
            enableYzSwap = false,
            enablePunctuation = allowPunctMapping,
        )

        // Variant with YZ swap
        candidates += convertInternal(
            input,
            enableYzSwap = (suspDigitsPlusBefore > 0),
            enablePunctuation = allowPunctMapping,
        )

        // If none mapping changed anything, exit early
        val anyChanged = candidates.any { it != input }
        if (!anyChanged) return input

        // Score candidates and pick the best improvement
        val beforeSuspiciousTotal = countSuspiciousTotal(input, allowPunctMapping)

        val scored = candidates.distinct().map { cand ->
            val afterSuspicious = countSuspiciousTotal(cand, allowPunctMapping)
            val diacriticsAfter = countDiacritics(cand)
            val edits = countEdits(input, cand)
            CandidateScore(
                text = cand,
                suspicious = afterSuspicious,
                diacritics = diacriticsAfter,
                edits = edits,
            )
        }

        val best = scored.minWith(compareBy<CandidateScore> {
            // Prefer fewer suspicious patterns
            it.suspicious
        }.thenBy {
            // Prefer more diacritics (negative to sort descending)
            -it.diacritics
        }.thenBy {
            // Prefer fewer edits (be conservative)
            it.edits
        })

        // Accept conversion if it clearly improves the text
        val improvesSuspicious = best.suspicious < beforeSuspiciousTotal
        val improvesDiacritics = best.diacritics > diacriticsBefore

        return if (beforeSuspiciousTotal > 0 && (improvesSuspicious || improvesDiacritics)) {
            best.text
        } else {
            input
        }
    }

    private fun convertInternal(
        s: String,
        enableYzSwap: Boolean,
        enablePunctuation: Boolean,
    ): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]

            // Handle +<letter> caron sequences
            if (ch == '+' && i + 1 < s.length) {
                val next = s[i + 1]
                val lower = next.lowercaseChar()
                val mapped = caronPlusMap[lower]
                if (mapped != null) {
                    out.append(if (next.isUpperCase()) mapped.uppercaseChar() else mapped)
                    i += 2
                    continue
                }
            }

            // Handle digit-to-CZ mapping when the digit sits inside a word
            val mappedDigit = digitToCzLower[ch]
            if (mappedDigit != null && isInWordContext(s, i)) {
                val uppercase = inferUppercaseContext(s, i)
                out.append(if (uppercase) mappedDigit.uppercaseChar() else mappedDigit)
                i++
                continue
            }

            // Optional punctuation-to-CZ mapping (only when allowed) for characters inside a word
            if (enablePunctuation && isInWordContext(s, i)) {
                val mappedLower = punctToCzLower[ch]
                if (mappedLower != null) {
                    val uppercase = inferUppercaseContext(s, i)
                    out.append(if (uppercase) mappedLower.uppercaseChar() else mappedLower)
                    i++
                    continue
                }
                val mappedUpper = punctToCzUpper[ch]
                if (mappedUpper != null) {
                    out.append(mappedUpper)
                    i++
                    continue
                }
            }

            // Optional Y<->Z swap only if layout issue was detected
            if (enableYzSwap) {
                when (ch) {
                    'y' -> { out.append('z'); i++; continue }
                    'Y' -> { out.append('Z'); i++; continue }
                    'z' -> { out.append('y'); i++; continue }
                    'Z' -> { out.append('Y'); i++; continue }
                }
            }

            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun isInWordContext(s: String, idx: Int): Boolean {
        val before = idx - 1
        val after = idx + 1
        val prevIsLetter = before >= 0 && s[before].isLetter()
        val nextIsLetter = after < s.length && s[after].isLetter()
        return prevIsLetter || nextIsLetter
    }

    private fun inferUppercaseContext(s: String, idx: Int): Boolean {
        // If neighbor letters are uppercase, assume uppercase
        val before = idx - 1
        if (before >= 0 && s[before].isLetter() && s[before].isUpperCase()) return true
        val after = idx + 1
        if (after < s.length && s[after].isLetter() && s[after].isUpperCase()) return true
        return false
    }

    private fun countDiacritics(s: String): Int = s.count { it in czechDiacritics }

    private fun countSuspiciousDigitsPlus(s: String): Int {
        var count = 0
        for (i in s.indices) {
            val ch = s[i]
            if (digitToCzLower.containsKey(ch) && isInWordContext(s, i)) count++
            if (ch == '+' && i + 1 < s.length) {
                val next = s[i + 1]
                if (caronPlusMap.containsKey(next.lowercaseChar())) count++
            }
        }
        return count
    }

    private fun countSuspiciousTotal(s: String, includePunctuation: Boolean): Int {
        var count = countSuspiciousDigitsPlus(s)
        if (includePunctuation) {
            for (i in s.indices) {
                if (!isInWordContext(s, i)) continue
                val ch = s[i]
                if (punctToCzLower.containsKey(ch) || punctToCzUpper.containsKey(ch)) count++
            }
        }
        return count
    }

    private fun countEdits(a: String, b: String): Int {
        // Simple edit metric: number of positions where chars differ (bounded by length)
        val n = minOf(a.length, b.length)
        var diff = 0
        for (i in 0 until n) if (a[i] != b[i]) diff++
        diff += kotlin.math.abs(a.length - b.length)
        return diff
    }

    private data class CandidateScore(
        val text: String,
        val suspicious: Int,
        val diacritics: Int,
        val edits: Int,
    )
}
