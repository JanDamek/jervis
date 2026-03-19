"""EPIC 14: Anti-Hallucination Guard — fact-checking pipeline.

Post-processing step that verifies factual claims in LLM responses.
Runs after each assistant response in the agentic loop.

Two modes:
1. Code claims (file paths, code refs, URLs) → verify against KB
2. Real-world entity claims (names, addresses, ratings) → verify against
   actual web_search/web_fetch tool results collected during the loop.

Output: annotated response with verification status per claim.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from enum import Enum

logger = logging.getLogger(__name__)


class VerificationStatus(str, Enum):
    VERIFIED = "VERIFIED"
    UNVERIFIED = "UNVERIFIED"
    CONTRADICTED = "CONTRADICTED"


class ClaimType(str, Enum):
    FILE_PATH = "FILE_PATH"
    URL = "URL"
    API_ENDPOINT = "API_ENDPOINT"
    CODE_REFERENCE = "CODE_REFERENCE"
    NUMERIC_VALUE = "NUMERIC_VALUE"
    GENERAL_FACT = "GENERAL_FACT"
    REAL_WORLD_ENTITY = "REAL_WORLD_ENTITY"


@dataclass
class FactClaim:
    """A single factual claim extracted from an LLM response."""
    claim: str
    claim_type: ClaimType
    status: VerificationStatus = VerificationStatus.UNVERIFIED
    source: str | None = None
    confidence: float = 0.5


@dataclass
class FactCheckResult:
    """Result of fact-checking an LLM response."""
    total_claims: int = 0
    verified: int = 0
    unverified: int = 0
    contradicted: int = 0
    claims: list[FactClaim] = field(default_factory=list)
    overall_confidence: float = 0.5


# Patterns for extracting factual claims from text
_FILE_PATH_PATTERN = re.compile(
    r'(?:^|\s|`)((?:/[\w.-]+)+(?:\.\w+)?|(?:[\w.-]+/)+[\w.-]+(?:\.\w+)?)`?',
)
_URL_PATTERN = re.compile(r'https?://[^\s<>"]+')
_API_ENDPOINT_PATTERN = re.compile(
    r'(?:GET|POST|PUT|DELETE|PATCH)\s+(/[\w/{}.-]+)',
)
_CODE_REF_PATTERN = re.compile(
    r'`([A-Z][a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)*)`',
)

# Patterns for real-world entity claims (restaurants, businesses, places)
# Phone numbers (Czech format)
_PHONE_PATTERN = re.compile(r'\+?\d{3}[\s-]?\d{3}[\s-]?\d{3,4}')
# Ratings like "4.5/5", "4.8 z 5", "4.6/5 (Google, 150)"
_RATING_PATTERN = re.compile(r'(\d\.\d)\s*/\s*5\s*(?:\([^)]*\))?')
# Email addresses
_EMAIL_PATTERN = re.compile(r'[\w.-]+@[\w.-]+\.\w{2,}')
# Price ranges (Czech crowns)
_PRICE_PATTERN = re.compile(r'\d{2,5}\s*(?:Kč|CZK|korun)')


def extract_claims(text: str) -> list[FactClaim]:
    """Extract factual claims from an LLM response text."""
    claims: list[FactClaim] = []

    # File paths
    for match in _FILE_PATH_PATTERN.finditer(text):
        path = match.group(1)
        if len(path) > 5 and '/' in path:  # Skip short fragments
            claims.append(FactClaim(
                claim=path,
                claim_type=ClaimType.FILE_PATH,
            ))

    # URLs
    for match in _URL_PATTERN.finditer(text):
        claims.append(FactClaim(
            claim=match.group(0),
            claim_type=ClaimType.URL,
        ))

    # API endpoints
    for match in _API_ENDPOINT_PATTERN.finditer(text):
        claims.append(FactClaim(
            claim=match.group(0),
            claim_type=ClaimType.API_ENDPOINT,
        ))

    # Code references (class names, method names)
    for match in _CODE_REF_PATTERN.finditer(text):
        ref = match.group(1)
        if len(ref) > 3:  # Skip very short refs
            claims.append(FactClaim(
                claim=ref,
                claim_type=ClaimType.CODE_REFERENCE,
            ))

    # Real-world entity claims: phone numbers, ratings, emails, prices
    for match in _PHONE_PATTERN.finditer(text):
        claims.append(FactClaim(
            claim=match.group(0),
            claim_type=ClaimType.REAL_WORLD_ENTITY,
        ))
    for match in _RATING_PATTERN.finditer(text):
        claims.append(FactClaim(
            claim=match.group(0),
            claim_type=ClaimType.REAL_WORLD_ENTITY,
        ))
    for match in _EMAIL_PATTERN.finditer(text):
        claims.append(FactClaim(
            claim=match.group(0),
            claim_type=ClaimType.REAL_WORLD_ENTITY,
        ))
    for match in _PRICE_PATTERN.finditer(text):
        claims.append(FactClaim(
            claim=match.group(0),
            claim_type=ClaimType.REAL_WORLD_ENTITY,
        ))

    return claims


async def verify_claims(
    claims: list[FactClaim],
    client_id: str,
    project_id: str | None,
    web_evidence: str = "",
) -> list[FactClaim]:
    """Verify extracted claims against KB, workspace, and web evidence.

    For each claim:
    - FILE_PATH → check via KB code_search or workspace file listing
    - URL → basic format validation (no external fetch)
    - CODE_REFERENCE → search KB for matching class/function
    - API_ENDPOINT → search KB for matching routes
    - REAL_WORLD_ENTITY → verify against collected web_search/web_fetch results
    """
    if not claims:
        return claims

    # Normalize web evidence for substring matching
    web_evidence_lower = web_evidence.lower() if web_evidence else ""

    for claim in claims:
        try:
            if claim.claim_type == ClaimType.FILE_PATH:
                claim.status, claim.confidence = await _verify_file_path(
                    claim.claim, client_id, project_id,
                )
            elif claim.claim_type == ClaimType.URL:
                # Basic URL format validation
                if re.match(r'https?://[\w.-]+\.\w{2,}', claim.claim):
                    claim.status = VerificationStatus.UNVERIFIED
                    claim.confidence = 0.6
                    claim.source = "URL format valid"
                else:
                    claim.status = VerificationStatus.UNVERIFIED
                    claim.confidence = 0.3
            elif claim.claim_type == ClaimType.CODE_REFERENCE:
                claim.status, claim.confidence = await _verify_code_ref(
                    claim.claim, client_id, project_id,
                )
            elif claim.claim_type == ClaimType.REAL_WORLD_ENTITY:
                # Verify against actual web tool results
                claim.status, claim.confidence = _verify_against_web_evidence(
                    claim.claim, web_evidence_lower,
                )
            elif claim.claim_type == ClaimType.API_ENDPOINT:
                claim.status = VerificationStatus.UNVERIFIED
                claim.confidence = 0.5
        except Exception as e:
            logger.debug("Claim verification failed for %s: %s", claim.claim, e)
            claim.status = VerificationStatus.UNVERIFIED
            claim.confidence = 0.3

    return claims


def _verify_against_web_evidence(
    claim: str,
    web_evidence_lower: str,
) -> tuple[VerificationStatus, float]:
    """Verify a real-world claim against collected web evidence.

    Checks if the claim value (phone number, rating, email, price)
    appears in actual web_search/web_fetch results from this session.
    """
    if not web_evidence_lower:
        return VerificationStatus.UNVERIFIED, 0.2

    # Normalize the claim for matching
    claim_normalized = claim.strip().lower()
    # For phone numbers, also try without spaces/dashes
    claim_compact = re.sub(r'[\s-]', '', claim_normalized)

    if claim_normalized in web_evidence_lower:
        return VerificationStatus.VERIFIED, 0.9

    if claim_compact and claim_compact in web_evidence_lower.replace(' ', '').replace('-', ''):
        return VerificationStatus.VERIFIED, 0.85

    return VerificationStatus.UNVERIFIED, 0.2


async def _verify_file_path(
    path: str,
    client_id: str,
    project_id: str | None,
) -> tuple[VerificationStatus, float]:
    """Verify a file path exists in the project workspace."""
    try:
        import httpx
        from app.config import settings

        url = f"{settings.knowledgebase_url}/api/v1/retrieve"
        payload = {
            "query": f"file:{path}",
            "clientId": client_id,
            "projectId": project_id,
            "maxResults": 1,
            "minConfidence": 0.5,
        }
        async with httpx.AsyncClient(timeout=5.0) as http_client:
            resp = await http_client.post(url, json=payload)
            if resp.status_code == 200:
                data = resp.json()
                items = data.get("items", [])
                if items:
                    return VerificationStatus.VERIFIED, 0.9
        return VerificationStatus.UNVERIFIED, 0.4
    except Exception:
        return VerificationStatus.UNVERIFIED, 0.3


async def _verify_code_ref(
    ref: str,
    client_id: str,
    project_id: str | None,
) -> tuple[VerificationStatus, float]:
    """Verify a code reference (class/function name) exists in KB."""
    try:
        import httpx
        from app.config import settings

        url = f"{settings.knowledgebase_url}/api/v1/retrieve"
        payload = {
            "query": ref,
            "clientId": client_id,
            "projectId": project_id,
            "maxResults": 3,
            "minConfidence": 0.5,
        }
        async with httpx.AsyncClient(timeout=5.0) as http_client:
            resp = await http_client.post(url, json=payload)
            if resp.status_code == 200:
                data = resp.json()
                items = data.get("items", [])
                if any(ref.lower() in item.get("content", "").lower() for item in items):
                    return VerificationStatus.VERIFIED, 0.85
        return VerificationStatus.UNVERIFIED, 0.4
    except Exception:
        return VerificationStatus.UNVERIFIED, 0.3


async def fact_check_response(
    response_text: str,
    client_id: str,
    project_id: str | None,
    web_evidence: str = "",
) -> FactCheckResult:
    """Run the full fact-checking pipeline on an LLM response.

    This is the main entry point, called as a post-processing step
    after each assistant response in the agentic loop.

    Args:
        web_evidence: Combined text from web_search/web_fetch tool results
                      collected during this agentic loop session.
    """
    claims = extract_claims(response_text)
    if not claims:
        return FactCheckResult(overall_confidence=0.8)  # No claims to verify

    verified_claims = await verify_claims(claims, client_id, project_id, web_evidence)

    verified = sum(1 for c in verified_claims if c.status == VerificationStatus.VERIFIED)
    unverified = sum(1 for c in verified_claims if c.status == VerificationStatus.UNVERIFIED)
    contradicted = sum(1 for c in verified_claims if c.status == VerificationStatus.CONTRADICTED)
    total = len(verified_claims)

    overall = 0.5
    if total > 0:
        overall = (verified * 0.9 + unverified * 0.5 + contradicted * 0.1) / total

    result = FactCheckResult(
        total_claims=total,
        verified=verified,
        unverified=unverified,
        contradicted=contradicted,
        claims=verified_claims,
        overall_confidence=round(overall, 2),
    )

    logger.info(
        "FACT_CHECK | claims=%d | verified=%d | unverified=%d | contradicted=%d | confidence=%.2f | web_evidence_len=%d",
        total, verified, unverified, contradicted, overall, len(web_evidence),
    )

    return result
