"""Financial Agent -- budgets, invoices, cost estimates, and financial queries.

Handles financial analysis tasks including budget lookups, invoice
processing, cost estimation, and financial reporting. Uses the
knowledge base for financial data and internal records.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH

logger = logging.getLogger(__name__)


TOOL_BUDGET_LOOKUP: dict = {
    "type": "function",
    "function": {
        "name": "budget_lookup",
        "description": (
            "Look up budget data, invoices, and cost records from the "
            "financial system. Supports querying by project, department, "
            "date range, category, and vendor."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search query for financial records.",
                },
                "record_type": {
                    "type": "string",
                    "enum": ["budget", "invoice", "expense", "forecast", "all"],
                    "description": "Type of financial record to look up (default all).",
                    "default": "all",
                },
                "project": {
                    "type": "string",
                    "description": "Filter by project name or ID (optional).",
                },
                "department": {
                    "type": "string",
                    "description": "Filter by department (optional).",
                },
                "date_from": {
                    "type": "string",
                    "description": "Start date in ISO 8601 format (optional).",
                },
                "date_to": {
                    "type": "string",
                    "description": "End date in ISO 8601 format (optional).",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results (default 20).",
                    "default": 20,
                },
            },
            "required": ["query"],
        },
    },
}


_FINANCIAL_TOOLS: list[dict] = [
    TOOL_BUDGET_LOOKUP,
    TOOL_KB_SEARCH,
]


class FinancialAgent(BaseAgent):
    """Specialist agent for financial analysis and reporting.

    Handles budget queries, invoice analysis, cost estimation, and
    financial reporting. Does not sub-delegate to other agents --
    all financial operations are handled directly.
    """

    name = "financial"
    description = (
        "Handles budget queries, invoice analysis, cost estimation, "
        "and financial reporting from internal records."
    )
    domains = [DomainType.FINANCIAL]
    tools = _FINANCIAL_TOOLS
    can_sub_delegate = False

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute financial analysis operations.

        Uses the agentic loop with financial and KB tools. Does not
        sub-delegate -- all operations are handled directly.
        """
        logger.info(
            "FinancialAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        system_prompt = (
            "You are the FinancialAgent, a specialist in financial analysis "
            "and reporting.\n\n"
            "Your capabilities:\n"
            "- Look up budget data, invoices, and cost records\n"
            "- Analyze expenses by project, department, or category\n"
            "- Generate cost estimates and financial summaries\n"
            "- Search the knowledge base for financial policies and history\n\n"
            "Guidelines:\n"
            "- Always specify currency and time period in results\n"
            "- Provide clear breakdowns of costs and budgets\n"
            "- Compare actuals vs. budgets when data is available\n"
            "- Flag any anomalies or budget overruns\n"
            "- Include totals and subtotals in financial summaries\n"
            "- Round monetary values to appropriate precision\n"
            "- Never expose sensitive financial account details\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=8,
        )
