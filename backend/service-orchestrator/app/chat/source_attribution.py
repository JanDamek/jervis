"""EPIC 14-S2: Source Attribution — tracks KB sources used in responses.

Intercepts kb_search tool results during the agentic loop, extracts
source URNs with scores, and attaches them to response metadata.

The UI can display "[source: wiki/architecture.md]" next to relevant parts.

Integration points:
- Called from handler_agentic.py after each kb_search tool execution
- extract_sources() parses KB search results for sourceUrn + score
- attribution_metadata() builds metadata dict for save_assistant_message
"""

from __future__ import annotations

import logging
import re

logger = logging.getLogger(__name__)

# Regex to extract source and score from KB search result format
_SOURCE_PATTERN = re.compile(
    r"## Result \d+ \(score: ([\d.]+), kind: (\w*)\)\nSource: (.+?)(?:\n|$)"
)


def extract_sources(tool_result: str) -> list[dict]:
    """Extract source attributions from a kb_search tool result.

    Parses the formatted KB search output to find sourceUrn entries
    with their relevance scores.

    Returns:
        List of {sourceUrn, score, kind} dicts sorted by score desc.
    """
    sources = []
    for match in _SOURCE_PATTERN.finditer(tool_result):
        score_str, kind, source_urn = match.groups()
        try:
            score = float(score_str)
        except ValueError:
            score = 0.0

        sources.append({
            "sourceUrn": source_urn.strip(),
            "score": score,
            "kind": kind or "unknown",
            "sourceType": _classify_source_type(source_urn.strip()),
        })

    # Sort by score descending
    sources.sort(key=lambda s: s["score"], reverse=True)
    return sources


def _classify_source_type(source_urn: str) -> str:
    """Classify source URN into AttributionSourceType values."""
    if source_urn.startswith("git://") or source_urn.startswith("git::"):
        return "GIT_FILE"
    if source_urn.startswith("http://") or source_urn.startswith("https://"):
        return "WEB_SEARCH"
    if source_urn.startswith("chat::") or source_urn.startswith("conversation:"):
        return "CHAT_HISTORY"
    # Default: KB chunk (jira::, confluence::, email::, user_knowledge::, etc.)
    return "KB_CHUNK"


class SourceTracker:
    """Tracks KB sources used during an agentic loop execution.

    Create one per chat request. Call add_tool_result() after each
    kb_search execution. Call build_metadata() at finalization.
    """

    def __init__(self) -> None:
        self._sources: list[dict] = []
        self._seen_urns: set[str] = set()

    def add_tool_result(self, tool_name: str, result: str) -> None:
        """Process a tool result and extract source attributions.

        Only processes kb_search results (code_search kept for backward compat).
        """
        if tool_name not in ("kb_search", "code_search"):
            return

        sources = extract_sources(result)
        for source in sources:
            urn = source["sourceUrn"]
            if urn not in self._seen_urns:
                self._seen_urns.add(urn)
                self._sources.append(source)

    @property
    def sources(self) -> list[dict]:
        """Get deduplicated sources sorted by score."""
        return sorted(self._sources, key=lambda s: s["score"], reverse=True)

    def build_metadata(self) -> dict:
        """Build metadata dict for save_assistant_message.

        Returns empty dict if no sources tracked.
        """
        if not self._sources:
            return {}

        top_sources = self.sources[:5]  # Top 5 most relevant

        return {
            "source_count": str(len(self._sources)),
            "sources": ",".join(s["sourceUrn"] for s in top_sources),
            "source_types": ",".join(
                sorted(set(s["sourceType"] for s in top_sources))
            ),
        }

    def build_done_metadata(self) -> dict:
        """Build structured metadata for SSE 'done' event.

        Returns empty dict if no sources tracked.
        """
        if not self._sources:
            return {}

        top_sources = self.sources[:5]

        return {
            "source_attributions": [
                {
                    "sourceUrn": s["sourceUrn"],
                    "score": round(s["score"], 2),
                    "sourceType": s["sourceType"],
                    "kind": s["kind"],
                }
                for s in top_sources
            ],
        }
