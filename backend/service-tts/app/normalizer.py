"""CPU-side text normalizer for XTTS.

One function: given raw text + scope + language, produce a list of short,
TTS-friendly lines for the XTTS worker to synthesize.

Pipeline (deterministic, in order):
  1. Apply all STRIP rules (regex + optional wrapping-parens removal)
  2. Apply all REPLACE rules (regex → replacement word)
  3. Apply all ACRONYM rules (case-insensitive word match → pronunciation)
  4. Convert remaining standalone numbers to words (`num2words`)
  5. Split on sentence terminators, then hard-split any piece over
     `max_chars` so XTTS tokenizer (186-char limit) never overflows.

Rules come from the server's `ttsRules` Mongo collection via
`rules_client.fetch_rules()`. Precedence PROJECT > CLIENT > GLOBAL is
already resolved server-side — we apply in the returned order so more
specific rules win.

No LLM anywhere on this path. Deterministic, ~1ms for typical inputs.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from typing import Iterable

from num2words import num2words

from jervis.server import tts_rules_pb2

logger = logging.getLogger("tts.normalizer")

# Soft limit — XTTS tokenizer hard-limit for Czech is 186 chars, going
# lower leaves margin for phonemizer expansion.
DEFAULT_MAX_CHARS = 170

_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?…])\s+")
_STANDALONE_NUMBER_RE = re.compile(r"\b\d+(?:[.,]\d+)?\b")
_MULTI_SPACE_RE = re.compile(r"[ \t]{2,}")
# Czech date `D. M. YYYY` or `D.M.YYYY` — converted to an ordinal phrase
# before the generic number expander sees the digits. Must tolerate
# arbitrary whitespace between components (including NBSPs already
# normalised to regular spaces earlier in the pipeline).
_CZECH_DATE_RE = re.compile(r"\b(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{4})\b")
# Same pattern without the year — "18. 3." in running Czech text is
# still a date reference and deserves the ordinal reading.
_CZECH_DATE_NO_YEAR_RE = re.compile(r"\b(\d{1,2})\.\s*(\d{1,2})\.(?!\s*\d)")

# Final safety pass — strip characters the XTTS Czech tokenizer crashes
# on. We keep Czech + basic Latin letters, digits, whitespace, and a
# minimal punctuation set (`.,!?:;-()"'`). Everything else (zero-width
# joins, control chars, box-drawing, emoji) is removed.
# Unicode ranges: U+0020–U+007E = printable ASCII, U+00C0–U+024F = Latin
# extended (covers Czech háčky + čárky), U+0300–U+036F = combining marks.
_TTS_SAFE_CHAR_RE = re.compile(
    r"[^"
    r"\u0020-\u007E"
    r"\u00C0-\u024F"
    r"\u0300-\u036F"
    r"]"
)


@dataclass
class NormalizedLine:
    """One output line for XTTS. `lang` is the `[CS] / [EN]` hint
    the XTTS worker uses to pick the right phonemizer."""

    text: str
    lang: str


def normalize(
    text: str,
    rules: Iterable[tts_rules_pb2.TtsRule],
    language: str = "cs",
    max_chars: int = DEFAULT_MAX_CHARS,
) -> list[NormalizedLine]:
    """Run the full pipeline. Returns short lines ready for XTTS.

    `rules` is the ordered output of
    `ServerTtsRulesService.GetForScope(...)`. We iterate once per rule
    type (strip first, then replace, then acronym) so their semantics
    compose cleanly regardless of how the list is ordered server-side.
    """
    if not text:
        return []

    strip_rules = [r for r in rules if r.type == tts_rules_pb2.STRIP]
    replace_rules = [r for r in rules if r.type == tts_rules_pb2.REPLACE]
    acronym_rules = [r for r in rules if r.type == tts_rules_pb2.ACRONYM]

    # Order matters:
    # 1) REPLACE first so markdown wrappers (`**text**` → `text`) don't
    #    leave hollow `****` for STRIP to miss, and unicode punctuation
    #    (NBSP, em/en dash) normalises to ASCII before anything else
    #    inspects word boundaries.
    # 2) STRIP next removes IDs / UUIDs that markdown REPLACE just
    #    exposed, plus markdown heading / bullet prefixes.
    # 3) ACRONYM last: acronyms already survive steps 1-2 and get
    #    spelled out on clean text.
    t = text
    for r in replace_rules:
        t = _apply_replace(t, r)
    for r in strip_rules:
        t = _apply_strip(t, r)
    for r in acronym_rules:
        t = _apply_acronym(t, r)
    t = _expand_numbers(t, language)
    # Final safety pass — drop any remaining codepoints outside basic
    # Latin + Czech diacritics. The XTTS Czech tokenizer crashes (CUDA
    # device-side assert) on zero-width joins, box-drawing chars, emoji
    # etc.; this keeps a single bad char from taking down the whole pod.
    t = _TTS_SAFE_CHAR_RE.sub("", t)
    t = _MULTI_SPACE_RE.sub(" ", t).strip()

    return _split_into_lines(t, language, max_chars)


def _apply_strip(text: str, rule: tts_rules_pb2.TtsRule) -> str:
    if not rule.pattern:
        return text
    try:
        inner = re.compile(rule.pattern)
    except re.error as e:
        logger.warning("STRIP rule '%s' has invalid regex: %s", rule.description, e)
        return text
    out = text
    if rule.strip_wrapping_parens:
        # Remove the entire parenthesized group that contains a match,
        # even if there's prose between `(` and the match. Typical input:
        # "klient Commerzbank (ID 68a33…)" → "klient Commerzbank".
        # `[^()]*?` keeps the match within a single paren depth so we
        # don't accidentally eat through nested parens.
        wrapped = re.compile(r"\s*\([^()]*?" + rule.pattern + r"[^()]*?\)\s*")
        out = wrapped.sub(" ", out)
    out = inner.sub("", out)
    return out


_JVM_BACKREF_RE = re.compile(r"\$(\d+)")


def _jvm_to_python_backref(replacement: str) -> str:
    """Kotlin / JVM regex uses `$1` for backreferences; Python uses `\\1`.
    Rules are authored against JVM syntax (that's what the server-side
    validator compiles), so we translate here before handing to `re.sub`."""
    return _JVM_BACKREF_RE.sub(r"\\\1", replacement)


def _apply_replace(text: str, rule: tts_rules_pb2.TtsRule) -> str:
    if not rule.pattern or rule.replacement is None:
        return text
    try:
        return re.compile(rule.pattern).sub(_jvm_to_python_backref(rule.replacement), text)
    except re.error as e:
        logger.warning("REPLACE rule '%s' has invalid regex: %s", rule.description, e)
        return text


def _apply_acronym(text: str, rule: tts_rules_pb2.TtsRule) -> str:
    if not rule.acronym or not rule.pronunciation:
        return text
    variants = [rule.acronym] + list(rule.aliases)
    # Dedup but preserve order.
    seen: set[str] = set()
    ordered: list[str] = []
    for v in variants:
        if v and v not in seen:
            seen.add(v)
            ordered.append(v)
    out = text
    for v in ordered:
        # `\b` around the variant catches word boundaries. Case-insensitive
        # so both BMS and bms hit the same rule.
        pattern = re.compile(r"\b" + re.escape(v) + r"\b", re.IGNORECASE)
        out = pattern.sub(rule.pronunciation, out)
    return out


def _expand_numbers(text: str, language: str) -> str:
    lang = _num2words_lang(language)
    # Expand Czech dates before the generic number pass — otherwise
    # "18. 3. 2026" ends up as three separate cardinals and the sentence
    # splitter cuts them at the periods.
    if lang == "cs":
        text = _CZECH_DATE_RE.sub(_expand_czech_date_full, text)
        text = _CZECH_DATE_NO_YEAR_RE.sub(_expand_czech_date_short, text)

    def repl(m: re.Match[str]) -> str:
        raw = m.group(0).replace(",", ".")
        try:
            n = float(raw) if "." in raw else int(raw)
            return num2words(n, lang=lang)
        except Exception:  # noqa: BLE001 — bad number, leave as-is
            return m.group(0)

    return _STANDALONE_NUMBER_RE.sub(repl, text)


def _czech_ordinal_genitive(n: int) -> str:
    """Czech ordinal in genitive case — what you say for `18.` in a date.

    num2words supports Czech cardinals only; ordinal genitive is a local
    derivation. Nominative ends in `-ý` / `-í` depending on the stem;
    both map cleanly to `-ého` / `-ího` for genitive masculine.
    """
    try:
        ordinal = num2words(n, lang="cs", to="ordinal")
    except NotImplementedError:
        # num2words may refuse ordinal for Czech; fall back to manual rules
        # for the common 1–31 date range.
        ordinal = _CZECH_ORDINAL_FALLBACK.get(n, num2words(n, lang="cs"))
    if ordinal.endswith("ý"):
        return ordinal[:-1] + "ého"
    if ordinal.endswith("í"):
        return ordinal[:-1] + "ího"
    return ordinal


# Fallback table for the ordinals that matter in date reading. Covers
# 1–31 (day) and 1–12 (month) in Czech nominative; the genitive form is
# derived by `_czech_ordinal_genitive`.
_CZECH_ORDINAL_FALLBACK: dict[int, str] = {
    1: "první", 2: "druhý", 3: "třetí", 4: "čtvrtý", 5: "pátý",
    6: "šestý", 7: "sedmý", 8: "osmý", 9: "devátý", 10: "desátý",
    11: "jedenáctý", 12: "dvanáctý", 13: "třináctý", 14: "čtrnáctý",
    15: "patnáctý", 16: "šestnáctý", 17: "sedmnáctý", 18: "osmnáctý",
    19: "devatenáctý", 20: "dvacátý", 21: "jednadvacátý",
    22: "dvaadvacátý", 23: "třiadvacátý", 24: "čtyřiadvacátý",
    25: "pětadvacátý", 26: "šestadvacátý", 27: "sedmadvacátý",
    28: "osmadvacátý", 29: "devětadvacátý", 30: "třicátý",
    31: "jedenatřicátý",
}


def _czech_ordinal_nominative(n: int) -> str:
    """Czech ordinal in nominative — what you say for the month in a
    date ('18. 3.' is read 'osmnáctého třetí', not 'osmnáctého třetího')."""
    try:
        return num2words(n, lang="cs", to="ordinal")
    except NotImplementedError:
        return _CZECH_ORDINAL_FALLBACK.get(n, num2words(n, lang="cs"))


def _expand_czech_date_full(m: re.Match[str]) -> str:
    """`18. 3. 2026` → `osmnáctého třetí dva tisíce dvacet šest`.

    Czech date convention: day goes genitive ('of the 18th'), month
    stays nominative ('third'), year is a regular cardinal."""
    day = int(m.group(1))
    month = int(m.group(2))
    year = int(m.group(3))
    return (
        f"{_czech_ordinal_genitive(day)} "
        f"{_czech_ordinal_nominative(month)} "
        f"{num2words(year, lang='cs')}"
    )


def _expand_czech_date_short(m: re.Match[str]) -> str:
    """`18. 3.` (no year) → `osmnáctého třetí`."""
    day = int(m.group(1))
    month = int(m.group(2))
    return f"{_czech_ordinal_genitive(day)} {_czech_ordinal_nominative(month)}"


def _num2words_lang(language: str) -> str:
    l = (language or "").lower()
    if l.startswith("cs"):
        return "cs"
    if l.startswith("en"):
        return "en"
    return "cs"  # default to Czech for the Jervis use-case


def _split_into_lines(text: str, language: str, max_chars: int) -> list[NormalizedLine]:
    lang_tag = "en" if (language or "").lower().startswith("en") else "cs"
    # First split on sentence terminators.
    sentences = [s.strip() for s in _SENTENCE_SPLIT_RE.split(text) if s.strip()]
    if not sentences:
        return []
    # Then hard-wrap anything still above the limit on the nearest word.
    out: list[NormalizedLine] = []
    for s in sentences:
        for chunk in _wrap_by_words(s, max_chars):
            if chunk.strip():
                out.append(NormalizedLine(text=chunk.strip(), lang=lang_tag))
    return out


def _wrap_by_words(text: str, max_chars: int) -> list[str]:
    if len(text) <= max_chars:
        return [text]
    words = text.split()
    chunks: list[str] = []
    current: list[str] = []
    current_len = 0
    for w in words:
        add = len(w) + (1 if current else 0)
        if current_len + add > max_chars and current:
            chunks.append(" ".join(current))
            current = [w]
            current_len = len(w)
        else:
            current.append(w)
            current_len += add
    if current:
        chunks.append(" ".join(current))
    return chunks
