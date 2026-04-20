"""LangGraph state schema for the Teams pod agent.

Three-layer state per docs/teams-pod-agent-langgraph.md §3:
  A. PodAgentState (here)          — LangGraph checkpoint, survives restart
  B. Runner in-memory runtime      — rebuilt on start (watcher, chunk queues)
  C. Mongo persistent stores       — o365_* + pod_agent_* collections

The fields below must stay JSON-serializable (MongoDBSaver requirement);
Playwright / Mongo client handles live in Layer B (ToolContext).
"""

from __future__ import annotations

from typing import Literal, TypedDict

from langgraph.graph import MessagesState


class ActiveMeeting(TypedDict, total=False):
    """Inline snapshot of the one (or zero) active meeting for this pod.
    Durable mirror of MeetingDocument — the Mongo doc is canonical, this
    keeps the hot subset on hand for agent reasoning without re-fetching.
    """
    meeting_id: str
    title: str | None
    joined_by: Literal["user", "agent"]
    scheduled_start_at: str | None
    scheduled_end_at: str | None
    meeting_stage_appeared_at: str
    max_participants_seen: int
    alone_since: str | None
    user_notify_sent_at: str | None
    last_speech_at: str | None
    recording_status: Literal["RECORDING", "FINALIZING"]
    chunks_uploaded: int
    chunks_pending: int
    last_chunk_acked_at: str | None
    last_user_response: dict | None


class PendingInstruction(TypedDict):
    """Server-pushed instruction waiting to be drained into a HumanMessage
    on the next outer-loop entry. Persisted so a restart does not drop
    an instruction that arrived moments before checkpoint."""
    id: str
    kind: str
    payload: dict
    received_at: str


class PodAgentState(MessagesState):
    """LangGraph state for one Teams pod agent (Layer A).

    Inherits `messages: list[BaseMessage]` from MessagesState with the
    `add_messages` reducer (keeps tool_call_id consistency across trim).
    """

    # Identity (stable for pod lifetime)
    client_id: str
    connection_id: str
    login_url: str
    capabilities: list[str]

    # Pod state machine (see product §9)
    pod_state: str

    # Login / relogin bookkeeping (MFA is Authenticator-only — user approves
    # on phone, nothing typed back; no mfa_code field in state).
    last_auth_request_at: str | None

    # Observation snapshot — last known, for reasoning without re-observe
    last_url: str
    last_app_state: str  # login|mfa|chat_list|conversation|meeting_stage|loading|unknown
    last_observation_at: str
    last_observation_kind: Literal["dom", "vlm", ""]

    # Per-context notify dedup (product §7)
    notified_contexts: list[str]

    # Active meeting (at most one per pod)
    active_meeting: ActiveMeeting | None

    # Pending server instructions — drained to HumanMessages on tick entry
    pending_instructions: list[PendingInstruction]

    # Legacy stuck-detection anchors (kept until D lands, then replaced)
    last_dom_signature: str | None
    stuck_count: int
