"""Pod state machine — autonomous browser pod lifecycle.

The pod controls its own state. Server only reads.
State changes are pushed to the Kotlin server via callback.
"""

from __future__ import annotations

import logging
from enum import Enum

logger = logging.getLogger("o365-browser-pool.state")


class PodState(str, Enum):
    STARTING = "STARTING"
    AUTHENTICATING = "AUTHENTICATING"
    AWAITING_MFA = "AWAITING_MFA"
    ACTIVE = "ACTIVE"
    RECOVERING = "RECOVERING"
    ERROR = "ERROR"
    EXECUTING_INSTRUCTION = "EXECUTING_INSTRUCTION"


# Valid state transitions
_TRANSITIONS: dict[PodState, set[PodState]] = {
    PodState.STARTING: {PodState.AUTHENTICATING, PodState.ACTIVE, PodState.ERROR},
    PodState.AUTHENTICATING: {PodState.AWAITING_MFA, PodState.ACTIVE, PodState.ERROR},
    PodState.AWAITING_MFA: {PodState.ACTIVE, PodState.ERROR, PodState.AUTHENTICATING},
    PodState.ACTIVE: {PodState.RECOVERING, PodState.ERROR},
    PodState.RECOVERING: {PodState.AUTHENTICATING, PodState.ERROR},
    PodState.ERROR: {PodState.EXECUTING_INSTRUCTION, PodState.AUTHENTICATING},
    PodState.EXECUTING_INSTRUCTION: {PodState.ACTIVE, PodState.ERROR, PodState.AUTHENTICATING},
}


class PodStateManager:
    """Manages pod state and notifies server on changes."""

    def __init__(self, client_id: str, connection_id: str) -> None:
        self.client_id = client_id
        self.connection_id = connection_id
        self._state = PodState.STARTING
        self._error_reason: str | None = None
        self._error_screenshot_path: str | None = None
        self._error_vlm_description: str | None = None
        self._mfa_type: str | None = None
        self._mfa_message: str | None = None
        self._mfa_number: str | None = None

    @property
    def state(self) -> PodState:
        return self._state

    @property
    def error_reason(self) -> str | None:
        return self._error_reason

    async def transition(self, new_state: PodState, **kwargs) -> bool:
        """Transition to new state. Returns True if transition was valid.

        kwargs:
            reason: str — error/recovery reason
            screenshot_path: str — screenshot for ERROR state
            vlm_description: str — VLM description for ERROR state
            mfa_type: str — MFA type for AWAITING_MFA
            mfa_message: str — MFA message for user
            mfa_number: str — number to approve in Authenticator
        """
        if new_state not in _TRANSITIONS.get(self._state, set()):
            logger.warning(
                "Invalid state transition: %s → %s (allowed: %s)",
                self._state, new_state, _TRANSITIONS.get(self._state, set()),
            )
            return False

        old_state = self._state
        self._state = new_state

        # Store error info
        if new_state == PodState.ERROR:
            self._error_reason = kwargs.get("reason", "Unknown error")
            self._error_screenshot_path = kwargs.get("screenshot_path")
            self._error_vlm_description = kwargs.get("vlm_description")
        elif new_state != PodState.EXECUTING_INSTRUCTION:
            self._error_reason = None
            self._error_screenshot_path = None
            self._error_vlm_description = None

        # Store MFA info
        if new_state == PodState.AWAITING_MFA:
            self._mfa_type = kwargs.get("mfa_type")
            self._mfa_message = kwargs.get("mfa_message")
            self._mfa_number = kwargs.get("mfa_number")
        else:
            self._mfa_type = None
            self._mfa_message = None
            self._mfa_number = None

        logger.info("STATE: %s → %s (reason=%s)", old_state, new_state, kwargs.get("reason", "-"))

        # Notify server
        await self._notify_server(new_state, **kwargs)
        return True

    async def _notify_server(self, state: PodState, **kwargs) -> None:
        """Push state change to Kotlin server."""
        from app.kotlin_callback import notify_session_state

        # Map pod states to server-understood states
        server_state = {
            PodState.STARTING: "STARTING",
            PodState.AUTHENTICATING: "AUTHENTICATING",
            PodState.AWAITING_MFA: "AWAITING_MFA",
            PodState.ACTIVE: "ACTIVE",
            PodState.RECOVERING: "RECOVERING",
            PodState.ERROR: "ERROR",
            PodState.EXECUTING_INSTRUCTION: "EXECUTING_INSTRUCTION",
        }.get(state, state.value)

        await notify_session_state(
            client_id=self.client_id,
            connection_id=self.connection_id,
            state=server_state,
            mfa_type=kwargs.get("mfa_type"),
            mfa_message=kwargs.get("mfa_message"),
            mfa_number=kwargs.get("mfa_number"),
        )

    def to_dict(self) -> dict:
        """Current state as dict for API responses."""
        return {
            "state": self._state.value,
            "error_reason": self._error_reason,
            "error_screenshot_path": self._error_screenshot_path,
            "error_vlm_description": self._error_vlm_description,
            "mfa_type": self._mfa_type,
            "mfa_message": self._mfa_message,
            "mfa_number": self._mfa_number,
        }


# Shared registry — 1 pod typically has 1 client, but the registry lets
# main.py self-restore and routes/session.py share the same PodStateManager
# instance per client_id.
_managers: dict[str, PodStateManager] = {}


def get_or_create_state_manager(client_id: str, connection_id: str) -> PodStateManager:
    sm = _managers.get(client_id)
    if sm is None:
        sm = PodStateManager(client_id, connection_id)
        _managers[client_id] = sm
    return sm


def get_state_manager(client_id: str) -> PodStateManager | None:
    return _managers.get(client_id)
