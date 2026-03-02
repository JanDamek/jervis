"""Tool tier system for unified agent handler.

Three tiers of tools, with escalation support:
- CORE: KB, web, memory — for qualification and simple tasks
- EXTENDED: + task mgmt, meetings, coding dispatch, filtering — for background/research
- FULL: + switch_context, list_affairs, work plans, guidelines, environment, action logging — for chat
"""

from __future__ import annotations

from enum import Enum


class ToolTier(str, Enum):
    CORE = "core"
    EXTENDED = "extended"
    FULL = "full"


# ---------------------------------------------------------------------------
# Tool name sets per tier
# ---------------------------------------------------------------------------

_CORE_TOOL_NAMES: set[str] = {
    "kb_search",
    "kb_delete",
    "web_search",
    "store_knowledge",
    "memory_store",
    "memory_recall",
    "get_kb_stats",
    "get_indexed_items",
    "select_tier",
}

_EXTENDED_ONLY_NAMES: set[str] = {
    "dispatch_coding_agent",
    "create_background_task",
    "search_tasks",
    "respond_to_user_task",
    "list_recent_tasks",
    "get_task_status",
    "classify_meeting",
    "list_unclassified_meetings",
    "count_unclassified_meetings",
    "set_filter_rule",
    "list_filter_rules",
    "remove_filter_rule",
}

_FULL_ONLY_NAMES: set[str] = {
    "switch_context",
    "list_affairs",
    "create_work_plan",
    "update_work_plan",
    "get_work_plan",
    "list_work_plans",
    "get_guidelines",
    "update_guideline",
    "get_merged_guidelines",
    "log_action",
    "query_action_log",
    # Environment tools
    "environment_list",
    "environment_get",
    "environment_create",
    "environment_clone",
    "environment_add_component",
    "environment_configure",
    "environment_add_property_mapping",
    "environment_auto_suggest_mappings",
    "environment_deploy",
    "environment_stop",
    "environment_status",
    "environment_sync",
    "environment_delete",
    "environment_keep_running",
}

_EXTENDED_TOOL_NAMES: set[str] = _CORE_TOOL_NAMES | _EXTENDED_ONLY_NAMES
_FULL_TOOL_NAMES: set[str] = _EXTENDED_TOOL_NAMES | _FULL_ONLY_NAMES


def get_tool_names(tier: ToolTier) -> set[str]:
    """Return the set of tool names available at a given tier."""
    if tier == ToolTier.CORE:
        return _CORE_TOOL_NAMES.copy()
    elif tier == ToolTier.EXTENDED:
        return _EXTENDED_TOOL_NAMES.copy()
    else:
        return _FULL_TOOL_NAMES.copy()


def get_tools(tier: ToolTier, all_available_tools: list[dict]) -> list[dict]:
    """Filter a list of tool definitions to only include those matching the tier.

    Args:
        tier: The tool tier to filter for.
        all_available_tools: Full list of tool definitions (OpenAI function-calling format).

    Returns:
        Filtered list of tool definitions whose function name is in the tier's name set.
    """
    allowed = get_tool_names(tier)
    return [
        tool for tool in all_available_tools
        if tool.get("function", {}).get("name") in allowed
    ]


# ---------------------------------------------------------------------------
# Escalation tool definition
# ---------------------------------------------------------------------------

TOOL_SELECT_TIER: dict = {
    "type": "function",
    "function": {
        "name": "select_tier",
        "description": (
            "Vyber tier pro další LLM volání na základě složitosti. "
            "free = jednoduché dotazy, paid = složitější reasoning, "
            "premium = komplexní syntéza, gemini = dekompozice velkých celků."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "tier": {
                    "type": "string",
                    "enum": ["free", "paid", "premium", "gemini"],
                    "description": "Cílový tier pro další volání",
                },
                "reason": {
                    "type": "string",
                    "description": "Proč potřebuješ tento tier",
                },
            },
            "required": ["tier", "reason"],
        },
    },
}


TOOL_ESCALATE_TOOLS: dict = {
    "type": "function",
    "function": {
        "name": "escalate_tools",
        "description": "Požádej o rozšíření dostupných nástrojů pokud potřebuješ víc než máš. CORE→EXTENDED→FULL.",
        "parameters": {
            "type": "object",
            "properties": {
                "reason": {
                    "type": "string",
                    "description": "Proč potřebuješ víc nástrojů",
                },
                "needed": {
                    "type": "string",
                    "enum": ["extended", "full"],
                    "description": "Jaký tier potřebuješ",
                },
            },
            "required": ["reason", "needed"],
        },
    },
}
