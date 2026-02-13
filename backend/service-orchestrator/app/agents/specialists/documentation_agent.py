"""Documentation Agent -- generates and updates project documentation.

Analyzes code changes and produces or updates documentation files,
READMEs, API docs, and architecture diagrams. Uses file system tools
to read existing code and documentation, and the KB for context.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import (
    TOOL_READ_FILE,
    TOOL_FIND_FILES,
    TOOL_KB_SEARCH,
    TOOL_GREP_FILES,
)

logger = logging.getLogger(__name__)


TOOL_WRITE_FILE: dict = {
    "type": "function",
    "function": {
        "name": "write_file",
        "description": (
            "Write content to a file in the project workspace. Creates the "
            "file if it does not exist, or overwrites if it does. Use this "
            "to create or update documentation files."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Relative file path within the project workspace.",
                },
                "content": {
                    "type": "string",
                    "description": "Full file content to write.",
                },
                "create_dirs": {
                    "type": "boolean",
                    "description": "Create parent directories if they do not exist (default true).",
                    "default": true,
                },
            },
            "required": ["path", "content"],
        },
    },
}


_DOC_TOOLS: list[dict] = [
    TOOL_READ_FILE,
    TOOL_FIND_FILES,
    TOOL_KB_SEARCH,
    TOOL_GREP_FILES,
    TOOL_WRITE_FILE,
]


class DocumentationAgent(BaseAgent):
    """Specialist agent for generating and updating documentation.

    Analyzes source code, existing documentation, and knowledge base
    content to produce or update documentation. Can sub-delegate to
    ResearchAgent for additional context gathering.
    """

    name = "documentation"
    description = (
        "Generates and updates project documentation (READMEs, API docs, "
        "architecture docs, changelogs). Reads code and existing docs to "
        "produce accurate, well-structured documentation."
    )
    domains = [DomainType.CODE, DomainType.RESEARCH]
    tools = _DOC_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute documentation generation or update.

        Strategy:
        1. Sub-delegate to ResearchAgent for codebase/architecture context.
        2. Read existing documentation files to understand current state.
        3. Generate or update documentation via the agentic loop.
        """
        logger.info(
            "DocumentationAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        # Always gather research context for documentation tasks
        research_output = await self._sub_delegate(
            target_agent_name="research",
            task_summary=(
                "Gather codebase context for documentation: "
                f"{msg.task_summary}"
            ),
            context=msg.context,
            parent_msg=msg,
            state=state,
        )
        enriched_context = msg.context
        if research_output.success and research_output.result:
            enriched_context = (
                f"{msg.context}\n\n"
                f"--- Research Context ---\n{research_output.result}"
            )

        enriched_msg = msg.model_copy(update={"context": enriched_context})

        system_prompt = (
            "You are the DocumentationAgent, a specialist in generating and "
            "updating project documentation.\n\n"
            "Your capabilities:\n"
            "- Read source code files to understand implementation\n"
            "- Search for existing documentation patterns and files\n"
            "- Search the knowledge base for architecture and design context\n"
            "- Write new documentation files or update existing ones\n"
            "- Use grep to find relevant code patterns and references\n\n"
            "Guidelines:\n"
            "- Read existing docs first to match style and conventions\n"
            "- Use clear, well-structured Markdown formatting\n"
            "- Include code examples where appropriate\n"
            "- Keep documentation concise but comprehensive\n"
            "- Update table of contents and cross-references\n"
            "- Document public APIs with parameters and return types\n"
            "- Include usage examples for complex features\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=enriched_msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=15,
        )

