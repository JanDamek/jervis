"""Draft Work Plan — iterative plan building in chat.

Stores plan data in the active Affair's key_facts["__plan_draft__"].
The LLM calls update_work_plan_draft to create/update the plan,
and finalize_work_plan to convert it into real background tasks.
"""

from __future__ import annotations

import json
import logging

from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)


# --- Models ---


class DraftPlanTask(BaseModel):
    """A single task within a plan phase."""

    title: str
    description: str
    action_type: str = "CODE"  # DECIDE / RESEARCH / DESIGN / CODE / REVIEW / TEST / CLARIFY / ESTIMATE
    status: str = "draft"  # draft / confirmed / removed
    depends_on: list[str] = Field(default_factory=list)  # task titles


class DraftPlanPhase(BaseModel):
    """A phase containing multiple tasks."""

    name: str
    tasks: list[DraftPlanTask]


class DraftPlan(BaseModel):
    """Draft work plan stored in affair key_facts."""

    title: str
    phases: list[DraftPlanPhase]
    gaps: list[str] = Field(default_factory=list)  # open questions
    status: str = "drafting"  # drafting / ready / executing
    version: int = 0


# --- Keys in affair.key_facts ---

PLAN_DRAFT_KEY = "__plan_draft__"
PLAN_VERSION_KEY = "__plan_version__"


# --- Rendering ---


_ACTION_TYPE_LABELS: dict[str, str] = {
    "DECIDE": "Rozhodnutí",
    "RESEARCH": "Průzkum",
    "DESIGN": "Návrh",
    "CODE": "Kód",
    "REVIEW": "Review",
    "TEST": "Testování",
    "CLARIFY": "Upřesnění",
    "ESTIMATE": "Odhad",
}

_STATUS_ICONS: dict[str, str] = {
    "draft": "[ ]",
    "confirmed": "[x]",
    "removed": "[~]",
}


def render_plan_markdown(plan: DraftPlan) -> str:
    """Render a DraftPlan as structured markdown for display in chat."""
    lines: list[str] = []

    status_label = {
        "drafting": "Rozpracováno",
        "ready": "Ke schválení",
        "executing": "Spuštěno",
    }.get(plan.status, plan.status)

    lines.append(f"## {plan.title} (v{plan.version})")
    lines.append(f"**Stav:** {status_label}")
    lines.append("")

    for phase in plan.phases:
        lines.append(f"### {phase.name}")
        for task in phase.tasks:
            if task.status == "removed":
                continue
            icon = _STATUS_ICONS.get(task.status, "[ ]")
            action = _ACTION_TYPE_LABELS.get(task.action_type, task.action_type)
            dep_text = ""
            if task.depends_on:
                dep_text = f" ← {', '.join(task.depends_on)}"
            lines.append(f"- {icon} **{task.title}** ({action}){dep_text}")
            if task.description:
                lines.append(f"  {task.description}")
        lines.append("")

    if plan.gaps:
        lines.append("### Otevřené otázky")
        for i, gap in enumerate(plan.gaps, 1):
            lines.append(f"{i}. {gap}")
        lines.append("")

    return "\n".join(lines)


# --- Serialization helpers ---


def serialize_plan(plan: DraftPlan) -> str:
    """Serialize plan to JSON string for storage in affair key_facts."""
    return plan.model_dump_json()


def deserialize_plan(json_str: str) -> DraftPlan:
    """Deserialize plan from affair key_facts JSON string."""
    return DraftPlan.model_validate_json(json_str)
