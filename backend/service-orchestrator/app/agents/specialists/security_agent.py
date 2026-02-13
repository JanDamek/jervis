"""Security Agent -- security analysis and vulnerability scanning.

Performs security analysis using Joern CPG static analysis, code search,
and knowledge base queries. Identifies vulnerabilities, security anti-patterns,
and provides remediation recommendations.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import (
    TOOL_JOERN_QUICK_SCAN,
    TOOL_KB_SEARCH,
    TOOL_CODE_SEARCH,
    TOOL_READ_FILE,
    TOOL_GREP_FILES,
)

logger = logging.getLogger(__name__)


_SECURITY_TOOLS: list[dict] = [
    TOOL_JOERN_QUICK_SCAN,
    TOOL_KB_SEARCH,
    TOOL_CODE_SEARCH,
    TOOL_READ_FILE,
    TOOL_GREP_FILES,
]


class SecurityAgent(BaseAgent):
    """Specialist agent for security analysis and vulnerability scanning.

    Uses Joern CPG analysis for static security scanning, combined with
    code search and KB queries for comprehensive security assessment.
    Can sub-delegate to ResearchAgent for additional context about
    known vulnerabilities and security best practices.
    """

    name = "security"
    description = (
        "Performs security analysis and vulnerability scanning using Joern "
        "CPG analysis, code search, and knowledge base queries. Identifies "
        "vulnerabilities and provides remediation recommendations."
    )
    domains = [DomainType.SECURITY, DomainType.CODE]
    tools = _SECURITY_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute security analysis.

        Strategy:
        1. If task needs broader context, sub-delegate to ResearchAgent.
        2. Run agentic loop with security tools (Joern, code search, grep).
        3. Produce a structured security report with findings and remediation.
        """
        logger.info(
            "SecurityAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        enriched_context = msg.context
        if self._needs_research(msg):
            research_output = await self._sub_delegate(
                target_agent_name="research",
                task_summary=(
                    "Gather security context and known vulnerabilities for: "
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
            "You are the SecurityAgent, a specialist in application security "
            "analysis and vulnerability detection.\n\n"
            "Your capabilities:\n"
            "- Run Joern CPG static analysis (security, dataflow, callgraph, complexity)\n"
            "- Search codebase for security-sensitive patterns\n"
            "- Read source files to analyze security implementations\n"
            "- Search for known vulnerability patterns with grep\n"
            "- Query the knowledge base for security policies and history\n\n"
            "Scan types available via joern_quick_scan:\n"
            "- security: SQL injection, command injection, hardcoded secrets\n"
            "- dataflow: HTTP input sources and sensitive sinks (taint analysis)\n"
            "- callgraph: Method fan-out, dead code detection\n"
            "- complexity: Cyclomatic complexity, long method detection\n\n"
            "Guidelines:\n"
            "- Start with a broad security scan, then drill into findings\n"
            "- Classify findings by severity (CRITICAL, HIGH, MEDIUM, LOW)\n"
            "- Provide specific remediation steps for each finding\n"
            "- Check for OWASP Top 10 vulnerabilities\n"
            "- Verify hardcoded secrets, SQL injection, XSS, CSRF\n"
            "- Analyze authentication and authorization patterns\n"
            "- Report findings in a structured format\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=enriched_msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=15,
        )

    @staticmethod
    def _needs_research(msg: DelegationMessage) -> bool:
        """Heuristic: does this task need broader security research?"""
        research_keywords = [
            "audit", "compliance", "penetration", "threat model",
            "vulnerability", "cve", "owasp", "security review",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in research_keywords)

