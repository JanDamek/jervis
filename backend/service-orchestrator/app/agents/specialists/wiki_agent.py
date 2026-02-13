"""Wiki Agent -- CRUD operations on wiki pages.

Manages wiki pages in external systems (Confluence, GitHub Wiki, GitLab Wiki)
through the Kotlin server RPC bridge. Can sub-delegate to ResearchAgent
for gathering context before creating or updating wiki content.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH

logger = logging.getLogger(__name__)


TOOL_WIKI_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "wiki_search",
        "description": (
            "Search wiki pages across connected wiki systems (Confluence, "
            "GitHub Wiki, GitLab Wiki). Returns matching pages with titles, "
            "excerpts, and URLs."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search query -- free text or space-scoped search.",
                },
                "space": {
                    "type": "string",
                    "description": "Wiki space/project to search within (optional).",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results (default 10).",
                    "default": 10,
                },
            },
            "required": ["query"],
        },
    },
}


TOOL_WIKI_READ: dict = {
    "type": "function",
    "function": {
        "name": "wiki_read",
        "description": (
            "Read the full content of a wiki page by its ID or path. "
            "Returns the page body in Markdown, plus metadata (author, "
            "last modified, labels)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "page_id": {
                    "type": "string",
                    "description": "Page ID or full path (e.g. space/page-title).",
                },
            },
            "required": ["page_id"],
        },
    },
}


TOOL_WIKI_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "wiki_create",
        "description": (
            "Create a new wiki page in the specified space/project. "
            "Returns the created page ID and URL."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Page title.",
                },
                "content": {
                    "type": "string",
                    "description": "Page content in Markdown.",
                },
                "space": {
                    "type": "string",
                    "description": "Wiki space/project to create the page in.",
                },
                "parent_id": {
                    "type": "string",
                    "description": "Parent page ID for nested pages (optional).",
                },
                "labels": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Labels/tags to apply to the page.",
                },
            },
            "required": ["title", "content", "space"],
        },
    },
}


TOOL_WIKI_UPDATE: dict = {
    "type": "function",
    "function": {
        "name": "wiki_update",
        "description": (
            "Update an existing wiki page content or metadata. "
            "Supports partial updates (title, content, labels)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "page_id": {
                    "type": "string",
                    "description": "Page ID to update.",
                },
                "title": {
                    "type": "string",
                    "description": "New title (omit to keep current).",
                },
                "content": {
                    "type": "string",
                    "description": "New page content in Markdown (omit to keep current).",
                },
                "labels": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "New labels (replaces existing).",
                },
                "comment": {
                    "type": "string",
                    "description": "Edit comment / change summary.",
                },
            },
            "required": ["page_id"],
        },
    },
}


_WIKI_TOOLS: list[dict] = [
    TOOL_WIKI_SEARCH,
    TOOL_WIKI_READ,
    TOOL_WIKI_CREATE,
    TOOL_WIKI_UPDATE,
    TOOL_KB_SEARCH,
]


class WikiAgent(BaseAgent):
    """Specialist agent for wiki page management.

    Manages wiki pages across Confluence, GitHub Wiki, and GitLab Wiki.
    Can search, read, create, and update pages. Sub-delegates to
    ResearchAgent for gathering context from the knowledge base.
    """

    name = "wiki"
    description = (
        "Manages wiki pages in Confluence, GitHub Wiki, and GitLab Wiki. "
        "Can search, read, create, and update pages. "
        "Sub-delegates to ResearchAgent for context gathering."
    )
    domains = [DomainType.RESEARCH, DomainType.COMMUNICATION]
    tools = _WIKI_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute wiki operations.

        Strategy:
        1. If task requires research context, sub-delegate to ResearchAgent.
        2. Run agentic loop with wiki tools to fulfill the request.
        """
        logger.info(
            "WikiAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        enriched_context = msg.context
        if self._needs_research(msg):
            research_output = await self._sub_delegate(
                target_agent_name="research",
                task_summary=(
                    "Gather context for wiki operation: "
                    f"{msg.task_summary}"
                ),
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            if research_output.success and research_output.result:
                enriched_context = (
                    f"{msg.context}\n\n"
                    f"--- Research Context ---\n{research_output.result}"
                )

        enriched_msg = msg.model_copy(update={"context": enriched_context})

        system_prompt = (
            "You are the WikiAgent, a specialist in managing wiki pages in "
            "Confluence, GitHub Wiki, and GitLab Wiki.\n\n"
            "Your capabilities:\n"
            "- Search for existing wiki pages by text or space\n"
            "- Read full page content with metadata\n"
            "- Create new wiki pages with proper formatting\n"
            "- Update existing page content, titles, and labels\n"
            "- Search the knowledge base for additional context\n\n"
            "Guidelines:\n"
            "- Search for existing pages before creating duplicates\n"
            "- Use clear, well-structured Markdown formatting\n"
            "- Include appropriate labels for discoverability\n"
            "- Preserve existing content structure when updating\n"
            "- Add edit comments explaining changes\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=enriched_msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=10,
        )

    @staticmethod
    def _needs_research(msg: DelegationMessage) -> bool:
        """Heuristic: does this task need background research first?"""
        research_keywords = [
            "summarize", "document", "explain", "analyze",
            "architecture", "design", "decision", "context",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in research_keywords)

