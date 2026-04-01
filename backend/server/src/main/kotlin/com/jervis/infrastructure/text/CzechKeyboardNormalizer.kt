package com.jervis.infrastructure.text

import org.springframework.stereotype.Component

/**
 * Detects and fixes Czech text typed on English (US QWERTY) keyboard layout.
 *
 * Approach: Complete physical key mapping between US QWERTY and CZ QWERTZ layouts.
 * Each physical key produces a different character depending on which layout is active.
 * When evidence of wrong layout is detected (digits inside words, missing diacritics),
 * the entire text is converted from EN→CZ layout mapping.
 *
 * Detection heuristic:
 * - Count digits (2-9,0) adjacent to letters (would be ě,š,č,ř,ž,ý,á,í,é on CZ)
 * - Count +letter caron sequences (+d→ď, +t→ť, +n→ň)
 * - If evidence found → apply full layout conversion including Y↔Z swap
 */
@Component
class CzechKeyboardNormalizer {

    // ── Physical key layout maps ────────────────────────────────────────
    // Maps: what EN keyboard produces → what CZ keyboard produces on the same physical key

    // Number row (unshifted): EN digits → CZ diacritics
    private val numberRow: Map<Char, Char> = mapOf(
        '1' to '+',   // CZ: + (dead key for háčky)
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

    // Mappable digits (2-9,0 produce CZ diacritics; 1 produces + which is a dead key)
    private val mappableDigits: Set<Char> = setOf('2', '3', '4', '5', '6', '7', '8', '9', '0')

    // Number row (shifted): EN shifted → CZ shifted
    private val numberRowShifted: Map<Char, Char> = mapOf(
        '!' to '1',
        '@' to '2',
        '#' to '3',
        '$' to '4',
        '%' to '5',
        '^' to '6',
        '&' to '7',
        '*' to '8',
        '(' to '9',
        ')' to '0',
    )

    // Punctuation keys: EN → CZ (unshifted)
    private val punctuation: Map<Char, Char> = mapOf(
        ';' to 'ů',   // Key right of L
        '[' to 'ú',   // Key right of P
        '=' to ')',   // Key right of 0 (dead acute on CZ)
    )

    // Punctuation keys: EN shifted → CZ shifted
    private val punctuationShifted: Map<Char, Char> = mapOf(
        ':' to '"',   // Shift+; on EN = Shift+ů on CZ
        '{' to '/',   // Shift+[ on EN
        '<' to '?',   // Shift+, on EN = ? on CZ
        '>' to ':',   // Shift+. on EN = : on CZ
    )

    // Y↔Z swap (QWERTY vs QWERTZ)
    // CZ uses QWERTZ layout — Z and Y are swapped compared to US QWERTY

    // Caron dead key sequences: +letter → háček letter
    private val caronMap: Map<Char, Char> = mapOf(
        'd' to 'ď',
        't' to 'ť',
        'n' to 'ň',
        'e' to 'ě',   // Alternative to number row ě
    )

    private val czechDiacritics: Set<Char> =
        "ěščřžýáíéďťňůúóĚŠČŘŽÝÁÍÉĎŤŇŮÚÓ".toSet()

    // ── Public API ──────────────────────────────────────────────────────

    fun convertIfMistyped(input: String): String {
        if (input.isEmpty()) return input

        // Detect layout evidence: digits inside words (would be diacritics on CZ layout)
        val evidence = countLayoutEvidence(input)
        if (evidence == 0) return input

        // Evidence found → apply full EN→CZ conversion
        return convert(input)
    }

    // ── Conversion ──────────────────────────────────────────────────────

    private fun convert(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]

            // 1. Caron dead key: +letter → háček
            if (ch == '+' && i + 1 < s.length) {
                val next = s[i + 1]
                val mapped = caronMap[next.lowercaseChar()]
                if (mapped != null) {
                    out.append(if (next.isUpperCase()) mapped.uppercaseChar() else mapped)
                    i += 2
                    continue
                }
            }

            // 2. Number row digits → diacritics (only in word context)
            if (ch in mappableDigits && isInWordContext(s, i)) {
                val mapped = numberRow[ch]!!
                val uppercase = inferUppercaseContext(s, i)
                out.append(if (uppercase) mapped.uppercaseChar() else mapped)
                i++
                continue
            }

            // 3. Shifted number row (!, @, #, etc.) → digits
            val shiftedNum = numberRowShifted[ch]
            if (shiftedNum != null) {
                out.append(shiftedNum)
                i++
                continue
            }

            // 4. Punctuation → CZ equivalents (in word context or at word boundary)
            val punctMapped = punctuation[ch]
            if (punctMapped != null && isInWordContext(s, i)) {
                val uppercase = inferUppercaseContext(s, i)
                out.append(if (uppercase) punctMapped.uppercaseChar() else punctMapped)
                i++
                continue
            }

            // 5. Shifted punctuation → CZ equivalents (anywhere, these are sentence-level)
            val shiftedPunct = punctuationShifted[ch]
            if (shiftedPunct != null) {
                out.append(shiftedPunct)
                i++
                continue
            }

            // 6. Y↔Z swap (QWERTY vs QWERTZ)
            when (ch) {
                'y' -> { out.append('z'); i++; continue }
                'Y' -> { out.append('Z'); i++; continue }
                'z' -> { out.append('y'); i++; continue }
                'Z' -> { out.append('Y'); i++; continue }
            }

            // 7. Pass through unchanged
            out.append(ch)
            i++
        }
        return out.toString()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun isInWordContext(s: String, idx: Int): Boolean {
        fun isWordChar(i: Int): Boolean {
            if (i < 0 || i >= s.length) return false
            val c = s[i]
            return c.isLetter() || c in mappableDigits
        }
        return isWordChar(idx - 1) || isWordChar(idx + 1)
    }

    private fun inferUppercaseContext(s: String, idx: Int): Boolean {
        val before = idx - 1
        if (before >= 0 && s[before].isLetter() && s[before].isUpperCase()) return true
        val after = idx + 1
        if (after < s.length && s[after].isLetter() && s[after].isUpperCase()) return true
        return false
    }

    private fun countLayoutEvidence(s: String): Int {
        var count = 0
        for (i in s.indices) {
            val ch = s[i]
            // Digit in word context → would be diacritic on CZ layout
            if (ch in mappableDigits && isInWordContext(s, i)) count++
            // +letter caron sequence
            if (ch == '+' && i + 1 < s.length && caronMap.containsKey(s[i + 1].lowercaseChar())) count++
        }
        return count
    }
}
