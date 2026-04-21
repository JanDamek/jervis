"""LangGraph StateGraph for the pod agent.

Classic ReAct pattern (`agent` node + `ToolNode` + `tools_condition` edge),
wrapped around `RouterChatModel` (LLM via jervis-ollama-router) and the
`@tool`-decorated functions in `app.agent.tools`.

State is `PodAgentState` (MessagesState + pod-level fields). Dependencies
(Page, TabManager, ScrapeStorage, MeetingRecorder) are resolved from a
ContextVar — see `app.agent.context`.

Context window management — last N messages only.

The agent only needs the **latest turn** to know where to continue;
the full history lives in MongoDB (LangGraph `MongoDBSaver` checkpoint)
and can be queried on demand via a tool. We send the SystemMessage
head + the last `CONTEXT_KEEP_LAST_N` messages (default 10) to the
LLM. Whole Human → AI(tool_calls) → Tool → AI(final) triples are
preserved — we walk from the tail and stop on a Human boundary so
`tool_call_id` pairs are never split.

Observed 2026-04-21 on mazlušek: without any trim, accumulated state
hit 258k tokens after 10h of scraping and overflowed the 262k cloud
context. A count-based trim with N=10 is the minimum that lets the
agent see its last-emitted action.
"""

from __future__ import annotations

import logging
import os

from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from langgraph.graph import END, StateGraph
from langgraph.prebuilt import ToolNode, tools_condition

from app.agent.llm import RouterChatModel
from app.agent.state import PodAgentState
from app.agent.tools import ALL_TOOLS

logger = logging.getLogger("o365-browser-pool.graph")

CONTEXT_KEEP_LAST_N = int(os.getenv("O365_POOL_CONTEXT_KEEP_LAST_N", "10"))


def _trim_for_llm(messages: list[BaseMessage]) -> list[BaseMessage]:
    """Keep the SystemMessage head + last N messages, snapped to a
    Human/System boundary so no orphaned ToolMessage / tool_call_id
    appears at the start of the body (OpenAI rejects that).

    Everything older lives in the MongoDB checkpoint; the agent can
    query it via the history tool when it needs context beyond the
    window.
    """
    if not messages:
        return messages

    system_head: list[BaseMessage] = []
    body: list[BaseMessage] = []
    for m in messages:
        if isinstance(m, SystemMessage) and not body:
            system_head.append(m)
        else:
            body.append(m)

    if len(body) <= CONTEXT_KEEP_LAST_N:
        return system_head + body

    # Take the last N — then walk forward until we find a valid start
    # (a HumanMessage or SystemMessage). This avoids starting on an
    # orphaned AI(tool_calls) or ToolMessage whose pair got dropped.
    tail = body[-CONTEXT_KEEP_LAST_N:]
    # Snap to the first Human in the tail — everything before it within
    # the tail is an incomplete prior triple; drop it.
    for i, m in enumerate(tail):
        if isinstance(m, (HumanMessage, SystemMessage)):
            tail = tail[i:]
            break
    else:
        # No Human in tail (all AI/Tool) — unusual; include the last
        # Human from body so the LLM has an anchor.
        for j in range(len(body) - CONTEXT_KEEP_LAST_N - 1, -1, -1):
            if isinstance(body[j], HumanMessage):
                tail = body[j:]
                break
    return system_head + tail


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
                "context_trim: %d → %d messages (keep_last_n=%d)",
                len(all_messages), len(trimmed), CONTEXT_KEEP_LAST_N,
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
