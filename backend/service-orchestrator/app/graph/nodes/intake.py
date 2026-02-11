"""Intake node — 4-category classification, mandatory clarification.

Routes tasks to: ADVICE, SINGLE_TASK, EPIC, GENERATIVE.
"""

from __future__ import annotations

import json
import logging
from app.graph.nodes._helpers import (
    llm_with_cloud_fallback,
    parse_json_response,
    _detect_cloud_prompt,
)
from app.kb.prefetch import fetch_project_context
from app.models import TaskCategory, TaskAction, Complexity

logger = logging.getLogger(__name__)


async def intake(state: dict) -> dict:
    """Classify user query into 4 categories.

    Steps:
    1. Fetch project context from KB
    2. Build environment summary (if available)
    3. Detect cloud prompt keywords
    4. Build context: client/project names + KB context + recent chat
    5. LLM structured output for classification
    6. If goal_clear=false and clarification_questions exist → interrupt()
    """
    task = state.get("task")
    if not task:
        raise ValueError("Task data missing in state")

    query = task.get("query", "")
    client_id = task.get("client_id")
    project_id = task.get("project_id")
    chat_history = state.get("chat_history")

    # 1. Fetch project context from KB
    project_context = await fetch_project_context(client_id, project_id)

    # 2. Build environment summary
    environment = state.get("environment")
    env_summary = ""
    if environment:
        env_summary = f"Environment: {json.dumps(environment, default=str)[:500]}"

    # 3. Detect cloud prompt
