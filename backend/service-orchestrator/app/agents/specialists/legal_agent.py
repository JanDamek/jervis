"""Legal Agent -- contract analysis, compliance, NDA, and T&C review.

Analyzes legal documents including contracts, NDAs, terms and conditions,
and compliance requirements. Sub-delegates to ResearchAgent for gathering
regulatory context and precedent information.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH

logger = logging.getLogger(__name__)


TOOL_DOCUMENT_ANALYZE: dict = {
    "type": "function",
    "function": {
        "name": "document_analyze",
        "description": (
            "Analyze a document for legal aspects, risks, and obligations. "
            "Extracts key clauses, identifies potential risks, highlights "
            "obligations and deadlines, and flags unusual or concerning terms."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "content": {
                    "type": "string",
                    "description": "Document content to analyze (plain text or Markdown).",
                },
                "document_type": {
                    "type": "string",
                    "enum": [
                        "contract", "nda", "terms_and_conditions",
                        "privacy_policy", "sla", "license", "other",
                    ],
                    "description": "Type of legal document for context-aware analysis.",
                },
                "focus_areas": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": (
                        "Specific areas to focus on (e.g. liability, "
                        "termination, confidentiality, intellectual property)."
                    ),
                },
                "jurisdiction": {
                    "type": "string",
                    "description": "Applicable jurisdiction (e.g. EU, US, CZ) for compliance context.",
                },
            },
            "required": ["content", "document_type"],
        },
    },
}


_LEGAL_TOOLS: list[dict] = [
    TOOL_DOCUMENT_ANALYZE,
    TOOL_KB_SEARCH,
]


class LegalAgent(BaseAgent):
    """Specialist agent for legal document analysis and compliance.

    Analyzes contracts, NDAs, terms and conditions, and compliance
    requirements. Identifies risks, obligations, and concerning
    clauses. Sub-delegates to ResearchAgent for gathering regulatory
    context and precedent information.
    """

    name = "legal"
    description = (
        "Analyzes contracts, NDAs, terms & conditions, and compliance "
        "requirements. Identifies risks, obligations, and key clauses. "
        "Sub-delegates to ResearchAgent for regulatory context."
    )
    domains = [DomainType.LEGAL]
    tools = _LEGAL_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute legal analysis operations.

        Strategy:
        1. Sub-delegate to ResearchAgent for regulatory context.
        2. Run agentic loop with document analysis and KB tools.
        """
        logger.info(
            "LegalAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        enriched_context = msg.context
        if self._needs_research(msg):
            research_output = await self._sub_delegate(
                target_agent_name="research",
                task_summary=(
                    "Gather legal and regulatory context for: "
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
            "You are the LegalAgent, a specialist in legal document analysis "
            "and compliance review.\n\n"
            "Your capabilities:\n"
            "- Analyze contracts, NDAs, T&Cs, SLAs, and licenses\n"
            "- Identify risks, obligations, and deadlines\n"
            "- Flag unusual, concerning, or one-sided clauses\n"
            "- Check compliance with relevant regulations\n"
            "- Search the knowledge base for internal policies and precedents\n\n"
            "Guidelines:\n"
            "- Always identify the document type and applicable jurisdiction\n"
            "- Highlight critical clauses (termination, liability, IP, confidentiality)\n"
            "- Rate risks as LOW, MEDIUM, HIGH, or CRITICAL\n"
            "- Provide specific recommendations for each finding\n"
            "- Note missing standard clauses that should be present\n"
            "- Flag any ambiguous or undefined terms\n"
            "- DISCLAIMER: This is AI analysis, not legal advice. "
            "Recommend professional legal review for significant decisions.\n"
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
        """Heuristic: does this task need regulatory/legal research?"""
        research_keywords = [
            "compliance", "regulation", "gdpr", "ccpa", "hipaa",
            "precedent", "case law", "standard", "framework",
            "compare", "benchmark", "industry practice",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in research_keywords)
