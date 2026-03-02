"""Prompt builder — combines core + category prompt."""

from __future__ import annotations

from app.chat.models import ChatCategory
from app.chat.prompts.core import build_core_prompt
from app.chat.prompts.direct import DIRECT_PROMPT
from app.chat.prompts.research import RESEARCH_PROMPT
from app.chat.prompts.task_mgmt import TASK_MGMT_PROMPT
from app.chat.prompts.complex import COMPLEX_PROMPT
from app.chat.prompts.memory import MEMORY_PROMPT
from app.chat.system_prompt import RuntimeContext

_CATEGORY_PROMPTS: dict[ChatCategory, str] = {
    ChatCategory.DIRECT: DIRECT_PROMPT,
    ChatCategory.RESEARCH: RESEARCH_PROMPT,
    ChatCategory.TASK_MGMT: TASK_MGMT_PROMPT,
    ChatCategory.COMPLEX: COMPLEX_PROMPT,
    ChatCategory.MEMORY: MEMORY_PROMPT,
}


def build_routed_prompt(
    category: ChatCategory,
    active_client_id: str | None = None,
    active_project_id: str | None = None,
    active_client_name: str | None = None,
    active_project_name: str | None = None,
    runtime_context: RuntimeContext | None = None,
) -> str:
    """Build a focused system prompt: core identity + category-specific rules.

    Much shorter than the monolithic prompt (~60-80 lines vs ~160 lines).
    """
    core = build_core_prompt(
        active_client_id=active_client_id,
        active_project_id=active_project_id,
        active_client_name=active_client_name,
        active_project_name=active_project_name,
        runtime_context=runtime_context,
    )
    category_prompt = _CATEGORY_PROMPTS.get(category, RESEARCH_PROMPT)
    return core + category_prompt
