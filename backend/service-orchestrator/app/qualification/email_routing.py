"""Routes emails to correct client/project based on sender, content, and KB conventions."""

from __future__ import annotations

import logging

from app.tools.executor import _execute_kb_search

logger = logging.getLogger(__name__)

# Well-known domain mappings (bootstrap — KB conventions override these)
_DOMAIN_HINTS: dict[str, dict] = {
    "guru.com": {"client_hint": "freelance"},
    "toptal.com": {"client_hint": "freelance"},
    "upwork.com": {"client_hint": "freelance"},
    "github.com": {"client_hint": None, "type": "development"},
    "gitlab.com": {"client_hint": None, "type": "development"},
    "bitbucket.org": {"client_hint": None, "type": "development"},
}


async def route_email(
    sender_email: str,
    sender_name: str,
    subject: str,
    content_preview: str,
    thread_id: str | None,
    default_client_id: str,
    default_project_id: str | None,
) -> dict:
    """
    Determine the correct client/project scope for an incoming email.

    Returns: {
        "clientId": str,
        "projectId": str | None,
        "confidence": float,
        "reason": str,
    }
    """
    # 1. Check thread inheritance — if reply, use parent's scope
    if thread_id:
        thread_result = await _execute_kb_search(
            query=f"email thread {thread_id}",
            max_results=1,
            client_id="",  # Search globally
            project_id=None,
            processing_mode="FOREGROUND",
        )
        if thread_result and "clientId" in thread_result:
            logger.info("EMAIL_ROUTE: thread inheritance thread=%s", thread_id)
            # TODO: Parse clientId/projectId from thread KB result
            # and return early with high confidence

    # 2. Sender domain matching
    domain = sender_email.split("@")[-1].lower() if "@" in sender_email else ""

    # Check KB for sender conventions
    sender_convention = await _execute_kb_search(
        query=f"convention email routing sender {sender_email} domain {domain}",
        max_results=1,
        client_id="",
        project_id=None,
        processing_mode="FOREGROUND",
    )

    if sender_convention and "No results" not in sender_convention:
        logger.info("EMAIL_ROUTE: KB convention match for sender=%s", sender_email)
        # TODO: Parse actual clientId/projectId from KB result
        return {
            "clientId": default_client_id,
            "projectId": default_project_id,
            "confidence": 0.8,
            "reason": f"KB convention for {domain}",
        }

    # 3. Domain hint
    if domain in _DOMAIN_HINTS:
        hint = _DOMAIN_HINTS[domain]
        logger.info(
            "EMAIL_ROUTE: domain hint match domain=%s hint=%s",
            domain,
            hint,
        )
        return {
            "clientId": default_client_id,
            "projectId": default_project_id,
            "confidence": 0.5,
            "reason": f"Domain hint: {domain} → {hint}",
        }

    # 4. Content-based routing (keywords)
    content_lower = (subject + " " + content_preview).lower()
    _FINANCIAL_KEYWORDS = ("faktura", "invoice", "platba", "payment")

    if any(kw in content_lower for kw in _FINANCIAL_KEYWORDS):
        # Financial — try to match company name
        _company_search = await _execute_kb_search(
            query=f"company {sender_name} invoice",
            max_results=1,
            client_id="",
            project_id=None,
            processing_mode="FOREGROUND",
        )
        # TODO: Refine clientId based on company search results

    # 5. Default — use connection's default scope
    logger.info("EMAIL_ROUTE: default routing for sender=%s", sender_email)
    return {
        "clientId": default_client_id,
        "projectId": default_project_id,
        "confidence": 0.3,
        "reason": "Default (no matching convention)",
    }
