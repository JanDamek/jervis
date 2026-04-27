"""Per-invocation tool context via contextvars.

Tools read live dependencies (Playwright BrowserContext, TabRegistry,
ScrapeStorage, MeetingRecorder, credentials) from a ContextVar. This keeps
the LangGraph state JSON-serializable for the MongoDB checkpointer — the
heavy objects stay in RAM only.

The runner sets the ContextVar before invoking the graph; tools read it
via `get_pod_context()`.
"""

from __future__ import annotations

import contextvars
from dataclasses import dataclass, field

from playwright.async_api import BrowserContext, Page

from app.pod_state import PodStateManager
from app.scrape_storage import ScrapeStorage
from app.tab_manager import TabRegistry


@dataclass
class ToolContext:
    """Runtime dependencies for tool implementations."""
    client_id: str
    connection_id: str
    browser_context: BrowserContext
    state_manager: PodStateManager
    tab_registry: TabRegistry
    storage: ScrapeStorage
    credentials: dict[str, str] = field(default_factory=dict)
    meeting_recorder: object | None = None
    watcher: object | None = None
    capabilities: list[str] = field(default_factory=list)
    last_dom_delta_ts: float = 0.0
    last_dom_signature: str = ""
    last_app_state: str = "unknown"
    last_observation_at: str = ""
    last_observation_kind: str = ""  # "dom" | "vlm" | ""
    last_auth_request_at: str = ""   # ISO; 60-min relogin cooldown per §18
    notified_contexts: set[str] = field(default_factory=set)
    # Login Consent Semaphore (product §17 + §18):
    # populated when this pod is currently holding the global login lock.
    # Cleared on release. notify_user(kind='mfa') passes this token via
    # `mfa_lock_token` so the server knows the request is authorized.
    login_consent_request_id: str = ""
    login_consent_token: str = ""

    def resolve_tab(self, name: str) -> Page | None:
        """Return the page for a named tab. When `name` is empty, returns the
        first open page in the context — convenient default for agents that
        haven't yet registered tab names."""
        if name:
            return self.tab_registry.get(self.client_id, name)
        for page in self.browser_context.pages:
            if not page.is_closed():
                return page
        return None


_current_ctx: contextvars.ContextVar[ToolContext | None] = contextvars.ContextVar(
    "pod_tool_context", default=None,
)


def set_pod_context(ctx: ToolContext) -> contextvars.Token:
    return _current_ctx.set(ctx)


def reset_pod_context(token: contextvars.Token) -> None:
    _current_ctx.reset(token)


def get_pod_context() -> ToolContext:
    ctx = _current_ctx.get()
    if ctx is None:
        raise RuntimeError("pod_tool_context not set — tool called outside PodAgent runner")
    return ctx
