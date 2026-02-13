"""ResearchAgent -- knowledge and information gathering specialist.

Searches the Knowledge Base, web, codebase, and file system to
gather context and evidence for other agents and the orchestrator.
Has access to all search and browse tools across all domains.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType, ModelTier
from app.tools.definitions import ALL_RESPOND_TOOLS_FULL

logger = logging.getLogger(__name__)

RESEARCH_SYSTEM_PROMPT = """\
You are the ResearchAgent in the Jervis multi-agent orchestrator.

Your role is to gather comprehensive information and context for tasks. You have
full access to all search and browsing tools:

- KB search: Internal Knowledge Base (project docs, architecture, conventions)
- Web search: Internet search via SearXNG for external information
- Code search: Semantic code search across the codebase
- File browsing: Read files, list directories, find files by pattern
- Git tools: Repository history, diffs, blame information
- Repository info: Structure, tech stack, branches, recent commits
- Joern analysis: Security scans, complexity analysis, call graphs

Research workflow:
1. Understand what information is needed and why.
2. Start with KB search for internal context (always check internal docs first).
3. Use code search and file browsing for codebase-specific questions.
4. Use repository info tools to understand project structure.
5. Fall back to web search for external information (APIs, libraries, best practices).
6. Cross-reference findings from multiple sources.
7. Synthesize findings into a clear, structured report.

Output guidelines:
- Structure findings with clear sections and sources.
- Distinguish between facts (from KB/code) and inferences.
- Include relevant code snippets with file paths.
- Note confidence level for each finding.
- Flag any contradictions between sources.
- Keep output concise -- include only information relevant to the task.

You do NOT make code changes or execute commands. You only gather and report information.
"""


class ResearchAgent(BaseAgent):
    """Knowledge and information gathering specialist.

    Searches KB, web, codebase, and file system across all domains.
    Has the broadest tool access of any agent but does not make
    changes -- only gathers and synthesizes information.
    """

    name = "research"
    description = (
        "Research specialist -- searches KB, web, codebase, and file system "
        "to gather comprehensive context and evidence. Has full read-only "
        "access across all domains."
    )
    domains = list(DomainType)
    tools = ALL_RESPOND_TOOLS_FULL
    can_sub_delegate = False
    max_depth = 4

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute research task.

        Runs the agentic loop with full tool access to gather information.
        Uses a model tier appropriate for the context size -- larger context
        windows for comprehensive research tasks.
        """
        logger.info(
            "ResearchAgent executing task: %s (delegation=%s)",
            msg.task_summary[:80],
            msg.delegation_id,
        )

        # Broad research tasks benefit from larger context
        task_length = len(msg.task_summary) + len(msg.context)
        if task_length > 4000:
            model_tier = ModelTier.LOCAL_XLARGE
        else:
            model_tier = ModelTier.LOCAL_LARGE

        output = await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=RESEARCH_SYSTEM_PROMPT,
            max_iterations=15,
            model_tier=model_tier,
        )

        output.structured_data["agent_type"] = "research"
        if output.confidence < 0.7:
            output.needs_verification = True

        return output
