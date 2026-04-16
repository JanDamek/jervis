"""Pre-processing hallucination guard for chat responses.

Detects when a model responds with URLs or real-world entity claims
WITHOUT having used any verification tools (web_search, web_fetch, kb_search).
In such cases, forces a retry so the model actually verifies its claims.

This catches the common failure mode where free/cheap models ignore
the system prompt and hallucinate URLs, addresses, phone numbers, etc.
from their training data instead of using tools.
"""

from __future__ import annotations

import re
import logging

logger = logging.getLogger(__name__)

# Patterns that indicate real-world claims needing verification
_URL_PATTERN = re.compile(r'https?://[^\s<>"]+')
_PHONE_PATTERN = re.compile(r'\+?\d{3}[\s-]?\d{3}[\s-]?\d{3,4}')
_RATING_PATTERN = re.compile(r'\d\.\d\s*/\s*5')
_PRICE_PATTERN = re.compile(r'\d{2,5}\s*(?:Kč|CZK|korun|€|\$)')
_ADDRESS_PATTERN = re.compile(
    r'\d+[/\d]*\s*,?\s*\d{3}\s?\d{2}\s+\w+',  # Czech address: street 123, 110 00 Praha
)


# Patterns detecting "I will do X" promises without actually doing it
_PROMISE_PATTERN = re.compile(
    r'(?:začínám|prověřuji|ověřuji|hledám|vyhledávám|udělám|provedu|zkontroluju'
    r'|I will|I\'ll|let me|starting|beginning|searching|verifying'
    r'|web_search|web_fetch)',
    re.IGNORECASE,
)


# Raw ChatML / tokenizer template tokens. If any of these leaks into the
# final response, the model's chat template was truncated / corrupted
# (typically: prompt size close to num_ctx limit → Ollama trims the prefix →
# the role-markers get lost → model emits a raw template token as "answer").
_TEMPLATE_LEAK_PATTERN = re.compile(
    r'<\|im_start\|>|<\|im_end\|>|<\|endoftext\|>|<\|im_sep\|>|'
    r'<\|begin_of_text\|>|<\|end_of_text\|>|<\|eot_id\|>',
)


def detects_template_leak(response_text: str) -> bool:
    """Return True if the response contains raw chat-template tokens.

    These tokens never belong in a user-facing answer — their presence
    means the model is broken for this prompt (context overflow, misuse
    of tools, bad tokenizer config). The caller should skip this model
    and retry.
    """
    if not response_text:
        return False
    return bool(_TEMPLATE_LEAK_PATTERN.search(response_text))


def needs_verification_retry(response_text: str) -> str | None:
    """Check if a response contains unverified real-world claims.

    Returns a description of what needs verification, or None if the
    response is safe to deliver without tool verification.
    """
    if not response_text:
        return None

    issues: list[str] = []

    # Check for URLs (most common hallucination)
    urls = _URL_PATTERN.findall(response_text)
    if urls:
        # Filter out common safe URLs (Jervis internal, documentation, etc.)
        external_urls = [
            u for u in urls
            if not any(safe in u for safe in [
                "localhost", "127.0.0.1", "jervis", "github.com/jervis",
                "docs.google.com", "anthropic.com",
            ])
        ]
        if external_urls:
            issues.append(f"URL ({len(external_urls)})")

    # Check for phone numbers
    if _PHONE_PATTERN.search(response_text):
        issues.append("phone numbers")

    # Check for ratings
    if _RATING_PATTERN.search(response_text):
        issues.append("ratings")

    # Check for prices
    if _PRICE_PATTERN.search(response_text):
        issues.append("prices")

    # Check for addresses
    if _ADDRESS_PATTERN.search(response_text):
        issues.append("addresses")

    if not issues:
        return None

    result = ", ".join(issues)
    logger.info("HALLUCINATION_GUARD | detected unverified claims: %s", result)
    return result


def is_empty_promise(response_text: str) -> bool:
    """Detect when model promises to do something but didn't actually call any tools.

    Catches responses like "I'll search now", "Starting verification",
    "Začínám s ověřením" — where the model describes future actions
    instead of performing them via tool calls.
    """
    if not response_text or len(response_text) > 500:
        return False  # Long responses are likely real answers

    return bool(_PROMISE_PATTERN.search(response_text))


# Patterns detecting model claiming it can't access the web (but it CAN via tools)
_NO_ACCESS_PATTERN = re.compile(
    r'(?:nemám\s+(?:přístup|možnost|k\s+dispozici)|'
    r'nemohu\s+(?:ověřit|vyhled|prohled|přistup)|'
    r'nemůžu\s+(?:ověřit|vyhled|prohled|přistup)|'
    r'nemám\s+přímý\s+přístup|'
    r'aktuálně\s+nemám|'
    r'bez\s+zaručených\s+odkazů|'
    r'I\s+(?:don.t|cannot|can.t)\s+(?:access|browse|search)|'
    r'I\s+do\s+not\s+have\s+access)',
    re.IGNORECASE,
)


def detects_language_mismatch(user_text: str, response_text: str) -> bool:
    """Detect when model responds in wrong language.

    If user writes in Czech (contains Czech diacritics or common Czech words)
    but model responds primarily in English, this is a quality failure.
    Triggers retry with next model.
    """
    if not user_text or not response_text or len(response_text) < 100:
        return False

    # Check if user writes in Czech (diacritics or common Czech words)
    czech_chars = sum(1 for c in user_text if c in "áčďéěíňóřšťúůýž")
    czech_words = sum(1 for w in ["co", "se", "na", "mi", "je", "jak", "kde", "posledním", "projektu", "řešilo"] if w in user_text.lower())
    user_is_czech = czech_chars >= 2 or czech_words >= 2

    if not user_is_czech:
        return False

    # Check if response is primarily English (first 500 chars)
    sample = response_text[:500].lower()
    english_markers = ["the ", " is ", " was ", " were ", " are ", " has ", " have ",
                       " this ", " that ", " with ", " from ", " about ", " which ",
                       "following", "discussed", "meeting", "regarding"]
    english_score = sum(1 for m in english_markers if m in sample)

    if english_score >= 4:
        logger.info("HALLUCINATION_GUARD | language mismatch: user=Czech, response=English (score=%d)", english_score)
        return True
    return False


def claims_no_web_access(response_text: str) -> bool:
    """Detect when model falsely claims it cannot access the web.

    The model HAS web_search and web_fetch tools but some models
    (especially free/cheap ones) ignore tools and claim they can't
    access the internet. This is a tool-use failure, not a real limitation.
    """
    if not response_text:
        return False
    if _NO_ACCESS_PATTERN.search(response_text):
        logger.info("HALLUCINATION_GUARD | model falsely claims no web access")
        return True
    return False
