"""Fact-check post-processing helper for chat handlers.

Wraps fact_checker.fact_check_response() with error handling and
metadata formatting. Called from handler_agentic.py and handler.py
at every response finalization point.

EPIC 14-S1: Wires the existing fact-checker into the agentic loop.
EPIC 14-S3: Active fact-checking — verifies real-world claims against
            actual web_search/web_fetch tool results.
"""
from __future__ import annotations

import logging

from app.guard.fact_checker import FactCheckResult, fact_check_response

logger = logging.getLogger(__name__)


async def run_fact_check(
    response_text: str,
    client_id: str | None,
    project_id: str | None,
    web_evidence: str = "",
) -> FactCheckResult | None:
    """Run fact-check with error handling. Returns None on failure.

    Args:
        web_evidence: Combined text from web_search/web_fetch results
                      collected during this loop. Passed to fact-checker
                      for real-world entity verification.
    """
    if not client_id:
        return None
    try:
        return await fact_check_response(
            response_text=response_text,
            client_id=client_id,
            project_id=project_id,
            web_evidence=web_evidence,
        )
    except Exception as e:
        logger.warning("Fact-check failed (non-fatal): %s", e)
        return None


def fact_check_metadata(result: FactCheckResult | None) -> dict:
    """Build metadata dict from a FactCheckResult (empty dict if None)."""
    if result is None:
        return {}
    return {
        "fact_check_confidence": result.overall_confidence,
        "fact_check_claims": result.total_claims,
        "fact_check_verified": result.verified,
    }


def confidence_badge(result: FactCheckResult | None) -> dict:
    """Build confidence badge for SSE 'done' event metadata."""
    if result is None or result.total_claims == 0:
        return {}
    status = (
        "high" if result.overall_confidence >= 0.8
        else "medium" if result.overall_confidence >= 0.5
        else "low"
    )
    return {
        "confidence_badge": {
            "value": result.overall_confidence,
            "status": status,
            "summary": f"{result.verified}/{result.total_claims} verified",
        },
    }
