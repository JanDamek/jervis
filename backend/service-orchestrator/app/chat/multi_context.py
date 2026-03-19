"""Multi-context detection for chat messages.

Scans user messages for mentions of known clients/projects/groups
and generates structured hints when multiple contexts are detected.

This enables the orchestrator to correctly handle messages like:
  "V nUFO dnes nic akutního ale v BMS musíme dořešit flow ve scriptech"
→ Detects nUFO (project) + BMS (project), generates scope hints for each.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class ContextMention:
    """A detected mention of a client/project/group in the user message."""
    type: str           # "client" | "project" | "group"
    name: str           # Display name (as matched)
    id: str             # Database ID
    client_id: str      # Parent client ID
    client_name: str    # Parent client name
    span: tuple[int, int] = (0, 0)  # Character span in original message


@dataclass
class MultiContextResult:
    """Result of multi-context detection."""
    mentions: list[ContextMention] = field(default_factory=list)
    has_multiple_contexts: bool = False
    hint_message: str = ""


def detect_multi_context(
    message: str,
    clients_projects: list[dict],
    active_client_id: str | None = None,
    active_project_id: str | None = None,
) -> MultiContextResult:
    """Detect mentions of known clients/projects/groups in a user message.

    Args:
        message: User's message text
        clients_projects: List of client dicts from RuntimeContext
            Each: {"id": str, "name": str, "projects": [{"id": str, "name": str, "groupId": str, "groupName": str}]}
        active_client_id: Currently active client in UI
        active_project_id: Currently active project in UI

    Returns:
        MultiContextResult with detected mentions and optional hint
    """
    if not message or not clients_projects:
        return MultiContextResult()

    msg_lower = message.lower()
    mentions: list[ContextMention] = []
    seen_ids: set[str] = set()

    for client in clients_projects:
        client_id = client.get("id", "")
        client_name = client.get("name", "")

        # Check client name mention
        if client_name and _name_mentioned(msg_lower, client_name):
            if client_id not in seen_ids:
                mentions.append(ContextMention(
                    type="client",
                    name=client_name,
                    id=client_id,
                    client_id=client_id,
                    client_name=client_name,
                ))
                seen_ids.add(client_id)

        # Check project mentions
        for project in client.get("projects", []):
            proj_id = project.get("id", "")
            proj_name = project.get("name", "")
            group_id = project.get("groupId", "")
            group_name = project.get("groupName", "")

            if proj_name and _name_mentioned(msg_lower, proj_name):
                if proj_id not in seen_ids:
                    mentions.append(ContextMention(
                        type="project",
                        name=proj_name,
                        id=proj_id,
                        client_id=client_id,
                        client_name=client_name,
                    ))
                    seen_ids.add(proj_id)

            # Check group mention
            if group_name and group_id and _name_mentioned(msg_lower, group_name):
                gkey = f"g:{group_id}"
                if gkey not in seen_ids:
                    mentions.append(ContextMention(
                        type="group",
                        name=group_name,
                        id=group_id,
                        client_id=client_id,
                        client_name=client_name,
                    ))
                    seen_ids.add(gkey)

    if not mentions:
        return MultiContextResult()

    # Determine if we have multiple distinct contexts
    # (different from active + at least 2 unique scopes)
    unique_scopes = set()
    for m in mentions:
        if m.type == "project":
            unique_scopes.add(f"p:{m.id}")
        elif m.type == "group":
            unique_scopes.add(f"g:{m.id}")
        else:
            unique_scopes.add(f"c:{m.id}")

    has_multiple = len(unique_scopes) >= 2

    # Build hint message for LLM
    hint = ""
    if has_multiple:
        hint = _build_hint(mentions, active_client_id, active_project_id)
        logger.info(
            "Multi-context detected: %d mentions across %d scopes: %s",
            len(mentions), len(unique_scopes),
            ", ".join(m.name for m in mentions),
        )

    return MultiContextResult(
        mentions=mentions,
        has_multiple_contexts=has_multiple,
        hint_message=hint,
    )


def _name_mentioned(msg_lower: str, name: str) -> bool:
    """Check if a name is mentioned in the message.

    Handles:
    - Case-insensitive exact match
    - Without diacritics (Czech users often skip them)
    - Common abbreviations and variations
    """
    name_lower = name.lower()
    name_no_diac = _strip_diacritics(name_lower)

    # Direct match
    if name_lower in msg_lower:
        return True

    # Match without diacritics
    msg_no_diac = _strip_diacritics(msg_lower)
    if name_no_diac in msg_no_diac:
        return True

    # Word boundary match for short names (avoid false positives)
    if len(name_lower) <= 3:
        # For very short names, require word boundary
        pattern = r'\b' + re.escape(name_no_diac) + r'\b'
        if re.search(pattern, msg_no_diac):
            return True
        return False

    return False


_DIACRITICS_MAP = str.maketrans(
    "áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ",
    "acdeeinorstuuyzACDEEINORSTUUYZ",
)


def _strip_diacritics(text: str) -> str:
    """Strip Czech diacritics from text."""
    return text.translate(_DIACRITICS_MAP)


def _build_hint(
    mentions: list[ContextMention],
    active_client_id: str | None,
    active_project_id: str | None,
) -> str:
    """Build a structured hint for the LLM about detected multi-context message."""
    lines = [
        "[MULTI-CONTEXT] Tato zpráva zmiňuje VÍCE klientů/projektů. "
        "Zpracuj KAŽDÝ kontext SAMOSTATNĚ:",
    ]

    for i, m in enumerate(mentions, 1):
        scope_desc = f"{m.client_name}"
        if m.type == "project":
            scope_desc += f" / {m.name}"
        elif m.type == "group":
            scope_desc += f" / skupina {m.name}"

        is_active = (
            (m.type == "project" and m.id == active_project_id) or
            (m.type == "client" and m.id == active_client_id and not active_project_id)
        )

        if is_active:
            lines.append(f"  {i}. **{scope_desc}** (aktuální scope) — zpracuj přímo")
        else:
            lines.append(
                f"  {i}. **{scope_desc}** — přepni přes switch_context "
                f"(client_id={m.client_id}, project_id={m.id if m.type == 'project' else ''}), "
                f"zpracuj relevantní část zprávy, pak se vrať"
            )

    lines.append(
        "\nPro KAŽDÝ kontext: ulož relevantní fakta přes memory_store se správným scope. "
        "Po zpracování všech kontextů se vrať na původní scope."
    )

    return "\n".join(lines)
