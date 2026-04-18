"""EPIC 14-S3: Contradiction Detector for KB write path.

Pre-write check that searches existing KB chunks for content that contradicts
the new knowledge being stored. Prevents conflicting information from
accumulating in the Knowledge Base.

Called before KB ingest to:
1. Search for semantically similar existing chunks
2. Detect potential contradictions via pattern analysis
3. Return warnings (soft block) or conflicts (hard block) to the caller

This ensures KB integrity and prevents hallucination amplification
(wrong facts stored in KB → wrong RAG context → more hallucinations).
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from enum import Enum

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class ConflictSeverity(str, Enum):
    """Severity of detected contradiction."""
    NONE = "NONE"
    WARNING = "WARNING"  # Potential conflict — proceed with annotation
    CONFLICT = "CONFLICT"  # Clear contradiction — suggest resolution


@dataclass
class ContradictionResult:
    """Result of contradiction detection."""
    severity: ConflictSeverity = ConflictSeverity.NONE
    conflicts: list[ConflictDetail] = field(default_factory=list)
    message: str = ""


@dataclass
class ConflictDetail:
    """A single detected contradiction."""
    existing_urn: str
    existing_content: str
    new_content: str
    reason: str
    confidence: float = 0.5


# Patterns indicating contradictory statements
_NEGATION_PAIRS = [
    (re.compile(r"\bis\b", re.IGNORECASE), re.compile(r"\bis\s+not\b", re.IGNORECASE)),
    (re.compile(r"\bshould\b", re.IGNORECASE), re.compile(r"\bshould\s+not\b", re.IGNORECASE)),
    (re.compile(r"\bcan\b", re.IGNORECASE), re.compile(r"\bcannot\b|\bcan\s*not\b", re.IGNORECASE)),
    (re.compile(r"\bsupported\b", re.IGNORECASE), re.compile(r"\bnot\s+supported\b|\bunsupported\b", re.IGNORECASE)),
    (re.compile(r"\benabled\b", re.IGNORECASE), re.compile(r"\bdisabled\b|\bnot\s+enabled\b", re.IGNORECASE)),
    (re.compile(r"\btrue\b", re.IGNORECASE), re.compile(r"\bfalse\b", re.IGNORECASE)),
    (re.compile(r"\byes\b", re.IGNORECASE), re.compile(r"\bno\b", re.IGNORECASE)),
    (re.compile(r"\buse\b", re.IGNORECASE), re.compile(r"\bdo\s+not\s+use\b|\bavoid\b", re.IGNORECASE)),
]

_REPLACEMENT_PATTERN = re.compile(
    r"(?:deprecated|removed|replaced|no longer|instead of|rather than|obsolete|superseded)",
    re.IGNORECASE,
)


async def check_contradictions(
    subject: str,
    content: str,
    client_id: str,
    project_id: str | None,
) -> ContradictionResult:
    """Check if new knowledge contradicts existing KB content.

    Searches KB for semantically similar chunks and analyzes for contradictions.
    Called before store_knowledge writes to KB.

    Args:
        subject: Subject of the new knowledge
        content: Content of the new knowledge
        client_id: Client context
        project_id: Project context

    Returns:
        ContradictionResult with severity and details
    """
    if not content.strip() or not client_id:
        return ContradictionResult()

    try:
        # Search KB for similar content
        existing_chunks = await _search_similar_chunks(subject, content, client_id, project_id)
        if not existing_chunks:
            return ContradictionResult()

        conflicts: list[ConflictDetail] = []

        for chunk in existing_chunks:
            existing_content = chunk.get("content", "")
            existing_urn = chunk.get("sourceUrn", "unknown")

            # Check for contradictions
            contradiction = _detect_contradiction(content, existing_content)
            if contradiction:
                conflicts.append(ConflictDetail(
                    existing_urn=existing_urn,
                    existing_content=existing_content[:200],
                    new_content=content[:200],
                    reason=contradiction,
                    confidence=0.7,
                ))

        if not conflicts:
            return ContradictionResult()

        severity = ConflictSeverity.CONFLICT if len(conflicts) >= 2 else ConflictSeverity.WARNING

        conflict_msgs = [
            f"- {c.reason} (existing: {c.existing_urn})" for c in conflicts[:3]
        ]
        message = (
            f"Detected {len(conflicts)} potential contradiction(s) with existing KB content:\n"
            + "\n".join(conflict_msgs)
        )

        logger.info(
            "CONTRADICTION_CHECK | subject=%s | conflicts=%d | severity=%s",
            subject, len(conflicts), severity.value,
        )

        return ContradictionResult(
            severity=severity,
            conflicts=conflicts,
            message=message,
        )

    except Exception as e:
        logger.debug("Contradiction check failed: %s", e)
        return ContradictionResult()  # Fail open — don't block writes on check failure


async def _search_similar_chunks(
    subject: str,
    content: str,
    client_id: str,
    project_id: str | None,
) -> list[dict]:
    """Search KB for semantically similar chunks."""
    from jervis_contracts import kb_client

    query = f"{subject} {content[:200]}"
    try:
        return await kb_client.retrieve(
            caller="orchestrator.guard.contradiction_detector",
            query=query,
            client_id=client_id,
            project_id=project_id or "",
            max_results=5,
            min_confidence=0.6,
            timeout=5.0,
        )
    except Exception:
        return []


def _detect_contradiction(new_content: str, existing_content: str) -> str | None:
    """Detect if new content contradicts existing content.

    Returns a reason string if contradiction detected, None otherwise.
    """
    # Check negation pairs — if one text has the positive and the other has the negative
    for positive_pattern, negative_pattern in _NEGATION_PAIRS:
        new_has_positive = positive_pattern.search(new_content) is not None
        new_has_negative = negative_pattern.search(new_content) is not None
        existing_has_positive = positive_pattern.search(existing_content) is not None
        existing_has_negative = negative_pattern.search(existing_content) is not None

        if (new_has_positive and existing_has_negative and not new_has_negative):
            neg_match = negative_pattern.search(existing_content)
            return f"New content affirms what existing content negates ('{neg_match.group() if neg_match else ''}')."

        if (new_has_negative and existing_has_positive and not existing_has_negative):
            neg_match = negative_pattern.search(new_content)
            return f"New content negates what existing content affirms ('{neg_match.group() if neg_match else ''}')."

    # Check for replacement/deprecation patterns in new content
    if _REPLACEMENT_PATTERN.search(new_content):
        return "New content mentions deprecation/replacement — may supersede existing information."

    return None
