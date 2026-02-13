"""Learning Agent -- technology evaluation, tutorials, and best practices.

Evaluates technologies, creates tutorials, finds best practices, and
provides learning resources. Sub-delegates to ResearchAgent for
gathering information from the web and knowledge base.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_WEB_SEARCH, TOOL_KB_SEARCH

logger = logging.getLogger(__name__)


_LEARNING_TOOLS: list[dict] = [
    TOOL_WEB_SEARCH,
    TOOL_KB_SEARCH,
]


class LearningAgent(BaseAgent):
    """Specialist agent for technology evaluation and learning.

    Evaluates technologies, creates tutorials, finds best practices,
    and provides learning resources. Sub-delegates to ResearchAgent
    for comprehensive information gathering from the web and KB.
    """

    name = "learning"
    description = (
        "Evaluates technologies, creates tutorials, finds best practices, "
        "and provides learning resources. Sub-delegates to ResearchAgent "
        "for comprehensive information gathering."
    )
    domains = [DomainType.LEARNING, DomainType.RESEARCH]
    tools = _LEARNING_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute learning and evaluation operations.

        Strategy:
        1. Sub-delegate to ResearchAgent for information gathering.
        2. Run agentic loop with web search and KB tools for analysis
           and synthesis of learning materials.
        """
        logger.info(
            "LearningAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        enriched_context = msg.context
        if self._needs_research(msg):
            research_output = await self._sub_delegate(
                target_agent_name="research",
                task_summary=(
                    "Gather information for technology evaluation / learning: "
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
            "You are the LearningAgent, a specialist in technology evaluation, "
            "tutorials, and best practices.\n\n"
            "Your capabilities:\n"
            "- Search the web for technology comparisons, tutorials, and docs\n"
            "- Search the knowledge base for internal tech decisions and standards\n"
            "- Evaluate frameworks, libraries, and tools\n"
            "- Create structured learning guides and tutorials\n\n"
            "Guidelines:\n"
            "- Compare technologies using objective criteria (performance, "
            "community, maturity, ecosystem)\n"
            "- Provide pros and cons for each option evaluated\n"
            "- Include practical code examples in tutorials\n"
            "- Reference official documentation and authoritative sources\n"
            "- Consider the project's existing tech stack for compatibility\n"
            "- Structure learning content from basic to advanced\n"
            "- Include prerequisites and setup instructions\n"
            "- Highlight common pitfalls and best practices\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=enriched_msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=12,
        )

    @staticmethod
    def _needs_research(msg: DelegationMessage) -> bool:
        """Heuristic: does this task need broader research?"""
        research_keywords = [
            "evaluate", "compare", "assess", "review",
            "alternative", "recommendation", "pros and cons",
            "benchmark", "tutorial", "learn", "best practice",
            "how to", "guide", "introduction",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in research_keywords)
