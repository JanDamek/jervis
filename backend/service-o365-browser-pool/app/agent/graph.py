"""LangGraph StateGraph for the pod agent.

Classic ReAct pattern (`agent` node + `ToolNode` + `tools_condition` edge),
wrapped around `RouterChatModel` (LLM via jervis-ollama-router) and the
`@tool`-decorated functions in `app.agent.tools`.

State is `PodAgentState` (MessagesState + pod-level fields). Dependencies
(Page, TabManager, ScrapeStorage, MeetingRecorder) are resolved from a
ContextVar — see `app.agent.context`.
"""

from __future__ import annotations

import logging

from langchain_core.messages import AIMessage
from langgraph.graph import END, StateGraph
from langgraph.prebuilt import ToolNode, tools_condition

from app.agent.llm import RouterChatModel
from app.agent.state import PodAgentState
from app.agent.tools import ALL_TOOLS

logger = logging.getLogger("o365-browser-pool.graph")


def _agent_node_factory(connection_id: str):
    """Build the `agent` node bound to this connection's client_id for tier
    resolution. Returned coroutine is the LangGraph node body."""
    llm = RouterChatModel(
        client_id=connection_id,
        capability="chat",
        processing_mode="BACKGROUND",
    ).bind_tools(ALL_TOOLS)

    async def agent_node(state: PodAgentState) -> dict:
        reply = await llm.ainvoke(state["messages"])
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
