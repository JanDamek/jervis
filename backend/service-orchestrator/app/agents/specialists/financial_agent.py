"""FinancialAgent -- Budget tracking, invoices, and cost estimation.

Handles financial tasks including budget lookups, invoice management,
cost analysis, expense tracking, and financial reporting.
"""

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH

# ---------------------------------------------------------------------------
# Inline tool definitions
# ---------------------------------------------------------------------------

TOOL_BUDGET_LOOKUP: dict = {
    "type": "function",
    "function": {
        "name": "budget_lookup",
        "description": (
            "Look up budget information for a project, department, or category. "
            "Returns current allocation, spend-to-date, and remaining balance."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "scope": {
                    "type": "string",
                    "enum": ["project", "department", "category", "client", "global"],
                    "description": "Scope of the budget lookup.",
                },
                "scope_id": {"type": "string", "description": "Identifier for the scope."},
                "period": {"type": "string", "description": "Budget period to query.", "default": "current"},
                "include_forecast": {"type": "boolean", "description": "Include projected spend forecast.", "default": False},
                "breakdown_by": {"type": "string", "enum": ["category", "month", "vendor", "none"], "description": "How to break down the budget.", "default": "none"},
                "currency": {"type": "string", "description": "Currency for output (ISO 4217).", "default": "CZK"},
            },
            "required": ["scope"],
        },
    },
}

SYSTEM_PROMPT = """\
You are the FinancialAgent -- a specialist for financial operations within
the Jervis assistant.

Your capabilities:
1. Look up budget allocations, spend-to-date, and remaining balances.
2. Track and categorise expenses across projects and departments.
3. Generate cost estimates for new initiatives or purchases.
4. Analyse invoices and flag discrepancies.
5. Produce financial summaries and reports.
6. Search the knowledge base for financial policies and historical data.

Guidelines:
- Always specify the currency (default CZK) in financial outputs.
- Present numbers in a clear, formatted manner with proper separators.
- When estimating costs, provide a range (optimistic / expected / pessimistic).
- Flag any budget overruns or approaching limits proactively.
- Cross-reference invoice amounts against budget allocations.
- Include relevant period context (monthly, quarterly, annual).

Always respond in the language detected from the user input.
Internal reasoning must be in English.
"""


class FinancialAgent(BaseAgent):
    """Financial specialist for budgets, invoices, and cost estimation."""

    name: str = "financial"
    domains: list[DomainType] = [DomainType.FINANCIAL]
    can_sub_delegate: bool = False

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Run the financial-specific agentic loop."""
        tools = [TOOL_KB_SEARCH, TOOL_BUDGET_LOOKUP]

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=SYSTEM_PROMPT,
            tools=tools,
            max_iterations=6,
            model_tier="standard",
        )
