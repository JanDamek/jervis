"""LangGraph StateGraph for the pod agent.

Classic ReAct pattern (`agent` node + `ToolNode` + `tools_condition` edge),
wrapped around `RouterChatModel` (LLM via jervis-ollama-router) and the
`@tool`-decorated functions in `app.agent.tools`.

State is `PodAgentState` (MessagesState + pod-level fields). Dependencies
(Page, TabManager, ScrapeStorage, MeetingRecorder) are resolved from a
ContextVar — see `app.agent.context`.

Context window management (per docs/teams-pod-agent-langgraph.md §3b):
every invocation is bounded by `trim_messages(max_tokens=12000,
strategy='last', allow_partial=False, start_on='human')`. Whole Human →
AI(tool_calls) → Tool → … → AI(final) triples drop from the head so
`tool_call_id` pairs stay consistent. Without this the raw message
history grows unbounded (observed: 258k tokens after 10h of meeting
scraping → cloud 262k limit overflow).
"""

from __future__ import annotations

import logging
import os

from langchain_core.messages import AIMessage, BaseMessage, SystemMessage
from langchain_core.messages.utils import trim_messages
from langgraph.graph import END, StateGraph
from langgraph.prebuilt import ToolNode, tools_condition

from app.agent.llm import RouterChatModel
from app.agent.state import PodAgentState
from app.agent.tools import ALL_TOOLS

logger = logging.getLogger("o365-browser-pool.graph")

CONTEXT_TRIM_TOKENS = int(os.getenv("O365_POOL_CONTEXT_TRIM_TOKENS", "12000"))


def _char_token_estimator(messages: list[BaseMessage]) -> int:
    """~4 chars per token. Works for any message type — `trim_messages`
    only needs a scalar budget function, not a real tokenizer."""
    total = 0
    for m in messages:
        content = m.content or ""
        if isinstance(content, str):
            total += len(content) // 4 + 4
        elif isinstance(content, list):
            # multimodal content parts
            for part in content:
                if isinstance(part, dict):
                    total += len(str(part.get("text", ""))) // 4 + 4
                else:
                    total += len(str(part)) // 4 + 4
        # tool_calls on AIMessage add ~30 tokens per call (name + args)
        tc = getattr(m, "tool_calls", None) or []
        total += len(tc) * 30
    return total


def _trim_for_llm(messages: list[BaseMessage]) -> list[BaseMessage]:
    """Bound context per invocation. Keeps the SystemMessage head (which
    carries current-state snapshot + learned patterns) and the tail of
    the conversation up to CONTEXT_TRIM_TOKENS."""
    if not messages:
        return messages

    system_head: list[BaseMessage] = []
    body: list[BaseMessage] = []
    for m in messages:
        if isinstance(m, SystemMessage) and not body:
            system_head.append(m)
        else:
            body.append(m)

    trimmed_body = trim_messages(
        body,
        max_tokens=CONTEXT_TRIM_TOKENS,
        token_counter=_char_token_estimator,
        strategy="last",
        include_system=False,
        allow_partial=False,
        start_on="human",
    )
    return system_head + trimmed_body


def _agent_node_factory(connection_id: str):
    """Build the `agent` node bound to this connection's client_id for tier
    resolution. Returned coroutine is the LangGraph node body."""
    llm = RouterChatModel(
        client_id=connection_id,
        capability="chat",
        processing_mode="BACKGROUND",
    ).bind_tools(ALL_TOOLS)

    async def agent_node(state: PodAgentState) -> dict:
        all_messages = state["messages"]
        trimmed = _trim_for_llm(all_messages)
        if len(trimmed) != len(all_messages):
            logger.info(
                "context_trim: %d → %d messages (budget=%d tokens)",
                len(all_messages), len(trimmed), CONTEXT_TRIM_TOKENS,
            )
        reply = await llm.ainvoke(trimmed)
        if not isinstance(reply, AIMessage):
            reply = AIMessage(content=str(reply))
        return {"messages": [reply]}

    return agent_node


def build_pod_graph(connection_id: str) -> StateGraph:
    """Compose the StateGraph for one pod (not compiled — caller compiles
    with a checkpointer)."""
    sg = StateGraph(PodAgentState)
    sg.add_node("agent", _agent_node_factory(connection_id))
    sg.add_node("tools", ToolNode(ALL_TOOLS))
    sg.set_entry_point("agent")
    sg.add_conditional_edges("agent", tools_condition, {
        "tools": "tools",
        END: END,
    })
    sg.add_edge("tools", "agent")
    return sg
