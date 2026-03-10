"""Chat message routing — determines where in Paměťová mapa the message goes.

Routes chat messages to the correct action:
- new_vertex: create a new REQUEST vertex and execute
- resume_vertex: resume an existing RUNNING/BLOCKED vertex
- answer_ask_user: answer a blocked ASK_USER vertex
- direct_response: fast LLM response without tools (greetings etc.)
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass

from app.agent.models import AgentGraph, VertexStatus, VertexType

logger = logging.getLogger(__name__)

# Greeting patterns (Czech + English)
_GREETING_RE = re.compile(
    r"^(ahoj|čau|čus|hej|zdar|nazdar|dobr[ýé]\s*(ráno|den|odpoledne|večer)|"
    r"hi|hello|hey|good\s*(morning|afternoon|evening)|"
    r"díky|děkuj[iu]|dík|thanks?|thank you|"
    r"ok[ay]?|jasn[ée]|super|fajn|skvěl[ýé]|paráda)[\s!.?]*$",
    re.IGNORECASE,
)


@dataclass
class ChatRoute:
    """Result of routing a chat message to a vertex action."""

    action: str  # "new_vertex" | "resume_vertex" | "answer_ask_user" | "direct_response"
    vertex_id: str | None = None
    parent_id: str | None = None
    reason: str = ""


def _vertex_belongs_to_client(graph: AgentGraph, vertex: "GraphVertex", client_id: str) -> bool:
    """Walk parent chain to check if vertex belongs to client (legacy fallback)."""
    from app.agent.models import GraphVertex as GV
    current = vertex
    for _ in range(5):
        if not current.parent_id:
            return False
        parent = graph.vertices.get(current.parent_id)
        if not parent:
            return False
        if parent.vertex_type == VertexType.CLIENT:
            return parent.input_request == client_id
        current = parent
    return False


def route_chat_message(
    message: str,
    memory_map: AgentGraph | None,
    context_task_id: str | None = None,
    client_id: str | None = None,
    project_id: str | None = None,
) -> ChatRoute:
    """Route a chat message to the correct action in Paměťová mapa.

    Routing priority:
    1. context_task_id → find ASK_USER vertex → answer_ask_user
    2. Greeting pattern → direct_response
    3. RUNNING/BLOCKED vertex for scope → resume_vertex
    4. Default → new_vertex
    """

    # 1. Answer a specific task's ASK_USER vertex
    if context_task_id and memory_map:
        for vid, v in memory_map.vertices.items():
            if (
                v.vertex_type == VertexType.ASK_USER
                and v.status == VertexStatus.BLOCKED
                and context_task_id in (v.local_context or "")
            ):
                return ChatRoute(
                    action="answer_ask_user",
                    vertex_id=vid,
                    reason=f"Answering ASK_USER for task {context_task_id}",
                )

    # 2. Greeting fast-path
    if _GREETING_RE.match(message.strip()):
        return ChatRoute(
            action="direct_response",
            reason="Greeting detected",
        )

    # 3. Resume existing RUNNING/BLOCKED REQUEST vertex for this scope
    # CLIENT ISOLATION: Only resume vertices belonging to the current client
    if memory_map and client_id:
        for vid, v in memory_map.vertices.items():
            if (
                v.vertex_type == VertexType.REQUEST
                and v.status in (VertexStatus.RUNNING, VertexStatus.BLOCKED)
                and (v.client_id == client_id or (not v.client_id and _vertex_belongs_to_client(memory_map, v, client_id)))
            ):
                return ChatRoute(
                    action="resume_vertex",
                    vertex_id=vid,
                    reason=f"Resuming active REQUEST vertex {vid}",
                )

    # 4. Default — create new REQUEST vertex
    return ChatRoute(
        action="new_vertex",
        reason="New request",
    )
