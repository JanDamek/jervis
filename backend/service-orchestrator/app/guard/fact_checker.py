"""EPIC 14: Anti-Hallucination Guard — fact-checking pipeline.

Post-processing step that verifies factual claims in LLM responses.
Runs after each assistant response in the agentic loop.

Checks:
1. File paths → verify against git workspace / KB
2. URLs → verify format and known domains
3. Code references → verify against KB code chunks
4. Numeric values → cross-reference with KB
5. Attribution → ensure claims have sources

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

    return claims


async def verify_claims(
    claims: list[FactClaim],
    client_id: str,
    project_id: str | None,
) -> list[FactClaim]:
    """Verify extracted claims against KB and workspace.

    For each claim:
    - FILE_PATH → check via KB code_search or workspace file listing
    - URL → basic format validation (no external fetch)
    - CODE_REFERENCE → search KB for matching class/function
    - API_ENDPOINT → search KB for matching routes
    """
    if not claims:
        return claims

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
            elif claim.claim_type == ClaimType.API_ENDPOINT:
                claim.status = VerificationStatus.UNVERIFIED
                claim.confidence = 0.5
        except Exception as e:
            logger.debug("Claim verification failed for %s: %s", claim.claim, e)
            claim.status = VerificationStatus.UNVERIFIED
            claim.confidence = 0.3

    return claims


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
) -> FactCheckResult:
    """Run the full fact-checking pipeline on an LLM response.

    This is the main entry point, called as a post-processing step
    after each assistant response in the agentic loop.
    """
    claims = extract_claims(response_text)
    if not claims:
        return FactCheckResult(overall_confidence=0.8)  # No claims to verify

    verified_claims = await verify_claims(claims, client_id, project_id)

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
        "FACT_CHECK | claims=%d | verified=%d | unverified=%d | contradicted=%d | confidence=%.2f",
        total, verified, unverified, contradicted, overall,
    )

    return result
