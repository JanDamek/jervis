"""LearningAgent -- Technology evaluation, tutorials, and best practices.

Handles learning-oriented tasks including technology research, tutorial
creation, best practices compilation, skill assessment, and knowledge
synthesis. Sub-delegates to ResearchAgent for deep research tasks.
"""

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH, TOOL_WEB_SEARCH

SYSTEM_PROMPT = """\
You are the LearningAgent -- a specialist for technology evaluation, learning,
and best practices research within the Jervis assistant.

Your capabilities:
1. Evaluate technologies, frameworks, and tools with structured comparisons.
2. Create tutorials, how-to guides, and step-by-step instructions.
3. Research and compile best practices for specific domains.
4. Synthesise knowledge from multiple sources into actionable summaries.
5. Assess skill gaps and recommend learning paths.
6. Search the web for current information and the KB for internal knowledge.

Guidelines:
- For deep, multi-source research tasks, sub-delegate to ResearchAgent.
- When evaluating technologies, use a consistent comparison framework:
  Pros, Cons, Use Cases, Maturity, Community, Cost.
- Tutorials should include prerequisites, step-by-step instructions, and
  common pitfalls to avoid.
- Always cite sources and indicate the recency of information.
- Distinguish between opinions, best practices, and hard requirements.
- Present complex topics in layers: executive summary first, then details.

Always respond in the language detected from the user input.
Internal reasoning must be in English.
"""


class LearningAgent(BaseAgent):
    """Learning specialist for technology evaluation, tutorials, and best practices."""

    name: str = "learning"
    domains: list[DomainType] = [DomainType.LEARNING, DomainType.RESEARCH]
    can_sub_delegate: bool = True

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Handle learning tasks, sub-delegating deep research to ResearchAgent."""
        task_lower = msg.task_summary.lower()

        # Sub-delegate deep research tasks to ResearchAgent
        research_keywords = (
            "deep research", "comprehensive research", "literature review",
            "state of the art", "survey",
            "market analysis", "competitive analysis",
        )
        if any(kw in task_lower for kw in research_keywords):
            return await self._sub_delegate(
                target_agent_name="research",
                task_summary=f"Learning research: {msg.task_summary}",
                context=msg.context,
                parent_msg=msg,
                state=state,
            )

        # Standard learning tasks via agentic loop
        tools = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH]

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=SYSTEM_PROMPT,
            tools=tools,
            max_iterations=8,
            model_tier="standard",
        )
