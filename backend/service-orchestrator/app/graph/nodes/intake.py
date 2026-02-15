"""Intake node — 4-category classification, branch detection, mandatory clarification.

Routes tasks to: ADVICE, SINGLE_TASK, EPIC, GENERATIVE.
Detects target branch from user query for branch-aware context.
"""

from __future__ import annotations

import json
import logging
import re

from app.graph.nodes._helpers import (
    llm_with_cloud_fallback,
    parse_json_response,
    detect_cloud_prompt,
    is_error_message,
)
from app.kb.prefetch import fetch_project_context, fetch_user_context
from app.models import TaskCategory, TaskAction, Complexity

logger = logging.getLogger(__name__)


async def transform_user_query_to_kb_queries(query: str, state: dict) -> list[str]:
    """Transform user query into 2-3 general English technical search queries for KB.
    
    The KB contains English technical documentation, code examples, and architecture
    decisions. User queries may be in other languages or too specific/personal.
    This function uses LLM to extract the core technical concepts and formulate
    general search terms that are likely to match KB content.
    
    Args:
        query: The original user query (any language).
        state: Current orchestrator state (for LLM call).
        
    Returns:
        List of 2-3 search query strings in English. Falls back to [query] if LLM fails.
    """
    system_prompt = (
        "You are a search query optimizer for a technical knowledge base.\n\n"
        "The knowledge base contains:\n"
        "- Code implementations and examples\n"
        "- Architecture decisions and design patterns\n"
        "- Coding conventions and style guides\n"
        "- Project structure and module documentation\n"
        "- Technical specifications and APIs\n\n"
        "Given a user's query (which may be in any language or phrased conversationally), "
        "extract 2-3 general English search queries that would find relevant technical content.\n\n"
        "Rules:\n"
        "1. Translate non-English queries to English technical terms\n"
        "2. Remove conversational phrases (\"show me\", \"can you\", \"ukaž mi\", etc.)\n"
        "3. Focus on technical concepts, not personal context\n"
        "4. Use general terms that match how documentation is written\n"
        "5. Include relevant technology/framework names if mentioned\n"
        "6. Keep queries short (3-7 words)\n\n"
        "Examples:\n"
        "- User: \"ukaž mi co najdeš v KB pro email jazyková škola\"\n"
        "  → [\"email system implementation\", \"language school application\", \"smtp email client\"]\n"
        "- User: \"how do I add authentication to my API?\"\n"
        "  → [\"API authentication\", \"JWT tokens\", \"OAuth implementation\"]\n"
        "- User: \"show me examples of React components\"\n"
        "  → [\"React components\", \"React examples\", \"frontend components\"]\n\n"
        "Respond with JSON:\n"
        "{\n"
        '  "queries": ["query1", "query2", "query3"]\n'
        "}\n"
    )
    
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"User query: {query}"},
    ]
    
    try:
        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="query_transformation",
            max_tokens=512,
        )
        content = response.choices[0].message.content
        parsed = parse_json_response(content)
        queries = parsed.get("queries", [])
        if queries and isinstance(queries, list) and len(queries) > 0:
            # Ensure all queries are strings and limit to 3
            queries = [str(q) for q in queries if q][:3]
            if queries:
                logger.info("Transformed user query to KB queries: %s", queries)
                return queries
    except Exception as e:
        logger.warning("Failed to transform query: %s", e)
    
    # Fallback: return original query as-is
    logger.info("Using original query for KB (transformation failed or empty)")
    return [query]

# --- Branch detection ---

# Explicit patterns: "on branch X", "branch: X", "na větvi X"
_BRANCH_EXPLICIT_RE = re.compile(
    r"(?:on\s+branch|branch[:\s]+|na\s+v[eě]tvi)\s+"
    r"([a-zA-Z0-9_./-]+)",
    re.IGNORECASE,
)

# Known branch prefixes: feature/*, fix/*, hotfix/*, release/*, bugfix/*
_BRANCH_PREFIX_RE = re.compile(
    r"\b((?:feature|fix|hotfix|release|bugfix|chore|refactor)/[a-zA-Z0-9_.-]+)\b",
)

# Well-known branch names
_KNOWN_BRANCHES = {"main", "master", "develop", "staging", "production", "dev"}


def _detect_branch_reference(query: str) -> str | None:
    """Detect branch reference from user query.

    Matches:
    1. Explicit patterns: "on branch feature/auth", "branch: main", "na větvi develop"
    2. Known branch prefixes: feature/*, fix/*, hotfix/*, release/*
    3. Well-known branch names when preceded by context words

    Returns:
        Branch name or None if no branch detected.
    """
    # 1. Explicit patterns
    m = _BRANCH_EXPLICIT_RE.search(query)
    if m:
        return m.group(1)

    # 2. Known branch prefixes (feature/*, fix/*, etc.)
    m = _BRANCH_PREFIX_RE.search(query)
    if m:
        return m.group(1)

    # 3. Well-known branch names with context ("in main", "from master")
    for branch in _KNOWN_BRANCHES:
        pattern = re.compile(
            rf"\b(?:in|from|on|do|z|v)\s+{re.escape(branch)}\b",
            re.IGNORECASE,
        )
        if pattern.search(query):
            return branch

    return None


async def intake(state: dict) -> dict:
    """Classify user query into 4 categories + detect branch.

    Steps:
    1. Detect target branch from query
    2. Fetch project context from KB (branch-aware)
    3. Build environment summary (if available)
    4. Detect cloud prompt keywords
    5. Build context: client/project names + KB context + recent chat
    6. LLM structured output for classification
    7. If goal_clear=false and clarification_questions exist → interrupt()
    """
    task = state.get("task")
    if not task:
        raise ValueError("Task data missing in state")

    query = task.get("query", "")
    client_id = task.get("client_id")
    project_id = task.get("project_id")
    chat_history = state.get("chat_history")

    # 1. Detect target branch
    target_branch = _detect_branch_reference(query)
    if target_branch:
        logger.info("Branch detected from query: %s", target_branch)

    # 2. Fetch project context from KB (branch-aware) + user context (learned facts)
    # Transform user query to general English technical search terms
    search_queries = await transform_user_query_to_kb_queries(query, state)

    project_context = await fetch_project_context(
        client_id, project_id, query, target_branch=target_branch, search_queries=search_queries,
    )

    # Fetch user-learned knowledge (preferences, domain, tech stack, etc.)
    user_context = await fetch_user_context(client_id, project_id)

    # 3. Build environment summary
    environment = state.get("environment")
    env_summary = ""
    if environment:
        env_summary = f"Environment: {json.dumps(environment, default=str)[:500]}"

    # 4. Detect cloud prompt
    allow_cloud_prompt = detect_cloud_prompt(query)

    # 5. Build context for LLM classification
    context_parts: list[str] = []

    # Task identity
    client_name = task.get("client_name") or ""
    project_name = task.get("project_name") or ""
    if client_name or project_name:
        context_parts.append(
            f"Client: {client_name}, Project: {project_name}"
        )

    if env_summary:
        context_parts.append(env_summary)

    if project_context:
        context_parts.append(f"Project context (from KB):\n{project_context[:2000]}")

    # Recent chat for continuity — FILTER OUT ERROR MESSAGES
    if chat_history:
        recent = chat_history.get("recent_messages", [])
        if recent:
            # Filter out error messages from recent history
            valid_messages = [
                m for m in recent[-5:]
                if not is_error_message(m.get("content", ""))
            ]
            if valid_messages:
                recent_text = "\n".join(
                    f"[{m.get('role', '?')}] {m.get('content', '')[:200]}"
                    for m in valid_messages
                )
                context_parts.append(f"Recent conversation:\n{recent_text}")

    context_block = "\n\n".join(context_parts)

    # 6. LLM classification
    messages = [
        {
            "role": "system",
            "content": (
                "You are an intent classifier for a software development assistant.\n\n"
                "Classify the user query into exactly one of these categories:\n"
                "- advice: user wants information, explanation, analysis, or guidance (NO code changes)\n"
                "- single_task: a single development task (code change, bug fix, feature, tracker update)\n"
                "- epic: multiple related tasks that form a larger initiative\n"
                "- generative: user wants to generate a design/plan/architecture from scratch\n\n"
                "For single_task, also determine the action type:\n"
                "- respond: just answer/analysis (no code, no tracker)\n"
                "- code: needs code changes\n"
                "- tracker_ops: create/update issues in issue tracker\n"
                "- mixed: combination of above\n\n"
                "Also assess:\n"
                "- complexity: simple|medium|complex|critical\n"
                "- goal_clear: whether the intent is clear enough to proceed (true/false)\n"
                "- external_refs: any ticket IDs, URLs, branch names mentioned\n"
                "- clarification_questions: if goal_clear=false, what questions to ask\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "category": "advice|single_task|epic|generative",\n'
                '  "action": "respond|code|tracker_ops|mixed",\n'
                '  "complexity": "simple|medium|complex|critical",\n'
                '  "goal_clear": true,\n'
                '  "external_refs": [],\n'
                '  "clarification_questions": []\n'
                "}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Query: {query}"
                + (f"\n\nContext:\n{context_block}" if context_block else "")
            ),
        },
    ]

    response = await llm_with_cloud_fallback(
        state=state, messages=messages, task_type="classification", max_tokens=2048,
    )
    content = response.choices[0].message.content
    parsed = parse_json_response(content)

    # Extract classification results
    category = parsed.get("category", "advice")
    action = parsed.get("action", "respond")
    complexity = parsed.get("complexity", "medium")
    goal_clear = parsed.get("goal_clear", True)
    external_refs = parsed.get("external_refs", [])
    clarification_questions = parsed.get("clarification_questions", [])

    # Validate enums
    try:
        TaskCategory(category)
    except ValueError:
        logger.warning("Invalid category '%s', defaulting to advice", category)
        category = "advice"

    if category == "single_task":
        try:
            TaskAction(action)
        except ValueError:
            logger.warning("Invalid action '%s', defaulting to respond", action)
            action = "respond"

    try:
        Complexity(complexity)
    except ValueError:
        complexity = "medium"

    logger.info(
        "INTAKE_RESULT | category=%s action=%s complexity=%s "
        "goal_clear=%s branch=%s refs=%s",
        category, action, complexity, goal_clear, target_branch, external_refs,
    )

    # 7. Check if clarification is needed
    needs_clarification = not goal_clear and bool(clarification_questions)

    return {
        "task_category": category,
        "task_action": action,
        "task_complexity": complexity,
        "external_refs": external_refs,
        "needs_clarification": needs_clarification,
        "clarification_questions": clarification_questions if needs_clarification else None,
        "project_context": project_context,
        "user_context": user_context,
        "allow_cloud_prompt": allow_cloud_prompt,
        "target_branch": target_branch,
        "kb_search_queries": search_queries,  # Store for downstream nodes
    }
