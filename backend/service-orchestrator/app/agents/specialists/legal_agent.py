"""LegalAgent -- Contract analysis, NDA review, and compliance checking.

Provides legal document analysis, clause extraction, risk assessment,
and compliance verification. Sub-delegates to ResearchAgent for legal
precedent and regulatory research.
"""

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH

# ---------------------------------------------------------------------------
# Inline tool definitions
# ---------------------------------------------------------------------------

TOOL_DOCUMENT_ANALYZE: dict = {
    "type": "function",
    "function": {
        "name": "document_analyze",
        "description": (
            "Analyse a legal document (contract, NDA, agreement, policy). "
            "Extracts key clauses, identifies risks, and provides a structured summary."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "document_id": {"type": "string", "description": "ID of the document in the knowledge base."},
                "document_text": {"type": "string", "description": "Raw text of the document (when document_id is not available)."},
                "analysis_type": {
                    "type": "string",
                    "enum": ["full_review", "risk_assessment", "clause_extraction", "compliance_check", "comparison"],
                    "description": "Type of analysis to perform.",
                },
                "focus_areas": {"type": "array", "items": {"type": "string"}, "description": "Specific areas to focus on."},
                "compare_with_document_id": {"type": "string", "description": "ID of a second document for comparison."},
                "jurisdiction": {"type": "string", "description": "Legal jurisdiction for compliance context."},
            },
            "required": ["analysis_type"],
        },
    },
}

SYSTEM_PROMPT = """\
You are the LegalAgent -- a specialist for legal document analysis and
compliance within the Jervis assistant.

Your capabilities:
1. Analyse contracts, NDAs, agreements, and legal documents.
2. Extract and summarise key clauses (liability, termination, IP, etc.).
3. Perform risk assessments highlighting potential issues.
4. Check compliance against known regulations and policies.
5. Compare documents to identify differences and deviations.
6. Look up internal knowledge base for company policies and templates.

Guidelines:
- Always include a clear DISCLAIMER that your output is not legal advice
  and should be reviewed by a qualified legal professional.
- Highlight high-risk clauses prominently.
- Structure analysis with clear sections: Summary, Key Terms, Risks, Recommendations.
- For precedent research, sub-delegate to ResearchAgent.
- When comparing documents, present differences in a tabular format.
- Flag any missing standard clauses that should typically be present.

Always respond in the language detected from the user input.
Internal reasoning must be in English.
"""


class LegalAgent(BaseAgent):
    """Legal specialist for contract analysis, NDA review, and compliance."""

    name: str = "legal"
    domains: list[DomainType] = [DomainType.LEGAL]
    can_sub_delegate: bool = True

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Analyse legal tasks, performing document review or sub-delegating research."""
        task_lower = msg.task_summary.lower()

        # Sub-delegate pure research tasks to ResearchAgent
        research_keywords = (
            "precedent", "case law", "regulation", "legislative",
            "regulatory research",
        )
        if any(kw in task_lower for kw in research_keywords):
            return await self._sub_delegate(
                target_agent_name="research",
                task_summary=f"Legal research: {msg.task_summary}",
                context=msg.context,
                parent_msg=msg,
                state=state,
            )

        # Direct legal analysis via agentic loop
        tools = [TOOL_KB_SEARCH, TOOL_DOCUMENT_ANALYZE]

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=SYSTEM_PROMPT,
            tools=tools,
            max_iterations=8,
            model_tier="standard",
        )
