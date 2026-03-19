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
        issues.append("telefonní čísla")

    # Check for ratings
    if _RATING_PATTERN.search(response_text):
        issues.append("hodnocení")

    # Check for prices
    if _PRICE_PATTERN.search(response_text):
        issues.append("ceny")

    # Check for addresses
    if _ADDRESS_PATTERN.search(response_text):
        issues.append("adresy")

    if not issues:
        return None

    result = ", ".join(issues)
    logger.info("HALLUCINATION_GUARD | detected unverified claims: %s", result)
    return result
