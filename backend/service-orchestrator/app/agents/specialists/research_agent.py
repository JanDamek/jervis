"""Research Agent -- KB, code, web, and filesystem search.

Leaf agent that gathers information from all available sources: Knowledge
Base, codebase, filesystem, and web. Never sub-delegates to other agents.
Used by other agents to enrich their context before acting.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import (
    TOOL_WEB_SEARCH,
    TOOL_KB_SEARCH,
    TOOL_CODE_SEARCH,
    TOOL_GET_INDEXED_ITEMS,
    TOOL_GET_KB_STATS,
    TOOL_LIST_PROJECT_FILES,
    TOOL_GET_REPOSITORY_INFO,
    TOOL_GET_REPOSITORY_STRUCTURE,
    TOOL_GET_TECHNOLOGY_STACK,
    TOOL_JOERN_QUICK_SCAN,
    TOOL_GIT_BRANCH_LIST,
    TOOL_GET_RECENT_COMMITS,
    TOOL_LIST_FILES,
    TOOL_READ_FILE,
    TOOL_FIND_FILES,
    TOOL_GREP_FILES,
    TOOL_FILE_INFO,
    TOOL_EXECUTE_COMMAND,
)

logger = logging.getLogger(__name__)


_RESEARCH_TOOLS: list[dict] = [
    TOOL_WEB_SEARCH,
    TOOL_KB_SEARCH,
    TOOL_CODE_SEARCH,
    TOOL_GET_INDEXED_ITEMS,
    TOOL_GET_KB_STATS,
    TOOL_LIST_PROJECT_FILES,
    TOOL_GET_REPOSITORY_INFO,
    TOOL_GET_REPOSITORY_STRUCTURE,
    TOOL_GET_TECHNOLOGY_STACK,
    TOOL_JOERN_QUICK_SCAN,
    TOOL_GIT_BRANCH_LIST,
    TOOL_GET_RECENT_COMMITS,
    TOOL_LIST_FILES,
    TOOL_READ_FILE,
    TOOL_FIND_FILES,
    TOOL_GREP_FILES,
    TOOL_FILE_INFO,
    TOOL_EXECUTE_COMMAND,
]


class ResearchAgent(BaseAgent):
    """Leaf agent for comprehensive information gathering.

    Searches the Knowledge Base, codebase, filesystem, and web to gather
    context for other agents. Never sub-delegates -- this is a terminal
    agent in the delegation graph.
    """

    name = "research"
    description = (
        "Gathers information from all available sources: Knowledge Base "
        "(semantic search, indexed items, stats), codebase (code search, "
        "grep, file reading), filesystem (listing, file info), repository "
        "(structure, branches, commits, tech stack), static analysis "
        "(Joern CPG), and web search. Leaf agent -- never sub-delegates."
    )
    domains = [DomainType.RESEARCH, DomainType.CODE]
    tools = _RESEARCH_TOOLS
    can_sub_delegate = False

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute research task across all available sources.

        Uses a multi-source research strategy:
        1. Check KB stats to understand available data.
        2. Search KB and codebase for relevant information.
        3. Read files and explore filesystem as needed.
        4. Search the web for external information if needed.
        5. Synthesize findings into a clear research report.
        """
        logger.info(
            "ResearchAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        system_prompt = (
            "You are the ResearchAgent, a specialist in gathering and "
            "synthesizing information from multiple sources.\n\n"
            "Your capabilities:\n"
            "- Search the Knowledge Base for project documentation, decisions, "
            "and indexed content (kb_search, get_indexed_items, get_kb_stats)\n"
            "- Search code semantically for patterns and implementations (code_search)\n"
            "- Search files by text pattern (grep_files) and name pattern (find_files)\n"
            "- Read source files for detailed content (read_file)\n"
            "- Explore project structure (list_project_files, list_files, file_info)\n"
            "- Understand repository layout (get_repository_structure, get_repository_info)\n"
            "- Check technology stack and branches (get_technology_stack, git_branch_list)\n"
            "- View recent commits (get_recent_commits)\n"
            "- Run static analysis scans (joern_quick_scan)\n"
            "- Search the web for external information (web_search)\n"
            "- Execute read-only commands for investigation (execute_command)\n\n"
            "Research strategy:\n"
            "1. Start with get_kb_stats to understand what data is available\n"
            "2. Use kb_search for high-level project context and documentation\n"
            "3. Use code_search for implementation patterns and examples\n"
            "4. Use grep_files for specific text patterns and references\n"
            "5. Use read_file to examine specific files in detail\n"
            "6. Use web_search only when internal sources lack the needed info\n"
            "7. Synthesize all findings into a clear, actionable report\n\n"
            "Guidelines:\n"
            "- Be thorough -- search multiple sources before concluding\n"
            "- Prioritize internal sources (KB, code) over web search\n"
            "- Include specific file paths and code references in findings\n"
            "- Distinguish facts from inferences in your report\n"
            "- Structure output with clear sections and key findings\n"
            "- Keep output concise -- focus on what's relevant to the task\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=15,
        )
