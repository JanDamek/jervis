"""Goals — what the agent is trying to accomplish.

Each goal knows:
- its priority (lower = sooner)
- whether the current observation satisfies it
- what context to pass to the Reasoner when this goal is active
- when it next becomes due (for periodic goals)

The PodAgent loop picks the highest-priority pending goal each tick and asks
the Reasoner for the next action with that goal's context.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Optional

from app.agent.observer import Observation

logger = logging.getLogger("o365-browser-pool.agent.goals")


# Lower number = higher priority
PRIORITY_INSTRUCTION = 0    # JERVIS-pushed instruction
PRIORITY_RECOVERY = 10      # Re-establish authenticated app
PRIORITY_AUTH = 20          # Initial login
PRIORITY_NEW_MESSAGE = 30   # Unread badge → fetch immediately
PRIORITY_SCRAPE = 50        # Periodic tab scrape
PRIORITY_MONITOR = 80       # Idle keep-alive checks


@dataclass
class GoalContext:
    """What the Reasoner needs from this goal."""
    description: str
    extra_context: Optional[str] = None


@dataclass
class Goal:
    """Base goal. Subclasses override is_satisfied() and context()."""
    priority: int = PRIORITY_MONITOR
    name: str = "goal"

    def is_satisfied(self, obs: Observation) -> bool:  # pragma: no cover - abstract
        return True

    def is_due(self, now: float) -> bool:
        return True

    def context(self, obs: Observation) -> GoalContext:
        return GoalContext(description=self.name)

    def on_action_done(self, action_type: str, result_success: bool) -> None:
        """Hook for goal-specific bookkeeping after an action."""
        pass


@dataclass
class AuthGoal(Goal):
    """Be inside an app shell (Teams/Outlook/Calendar)."""
    priority: int = PRIORITY_AUTH
    name: str = "be_authenticated"
    target_app: str = "teams"  # which app shell counts as success
    target_url: str = "https://teams.microsoft.com"

    def is_satisfied(self, obs: Observation) -> bool:
        # Satisfied iff we're inside *any* known app shell — at startup we
        # may land on whichever Microsoft surface the cookies remember.
        return bool(obs.app_shells)

    def context(self, obs: Observation) -> GoalContext:
        if obs.app_shells:
            return GoalContext(description=f"Already inside app shells: {obs.app_shells}. Goal is satisfied.")
        # If we have no app shell and no useful DOM at all, navigate first.
        if obs.is_loading:
            return GoalContext(description="Page is loading — wait.")
        return GoalContext(
            description=(
                "Sign into Microsoft 365 (target app: Teams). "
                "Walk through whatever screens appear: account picker, "
                "email entry, password, MFA approval (number-match in "
                "Authenticator), MFA code, consent, 'Stay signed in', "
                "monitoring notice. The goal is reached when an app shell "
                "(Teams/Outlook/Calendar) becomes visible."
            ),
            extra_context=(
                "If the page is empty/about:blank, navigate to "
                f"{self.target_url}. If you already see an app shell, "
                "respond done."
            ),
        )


@dataclass
class InstructionGoal(Goal):
    """One-off instruction pushed by JERVIS."""
    priority: int = PRIORITY_INSTRUCTION
    name: str = "execute_instruction"
    instruction: str = ""
    completed: bool = False

    def is_satisfied(self, obs: Observation) -> bool:
        return self.completed

    def context(self, obs: Observation) -> GoalContext:
        return GoalContext(
            description=f"Execute this instruction: {self.instruction}",
            extra_context=(
                "When the instruction is fully done, respond with action=done. "
                "When you cannot proceed, respond with action=ask_user or error."
            ),
        )

    def on_action_done(self, action_type: str, result_success: bool) -> None:
        if action_type in ("done", "error", "ask_user"):
            self.completed = True


@dataclass
class PeriodicScrapeGoal(Goal):
    """Scrape a known tab on a fixed interval. The Reasoner is NOT involved —
    PodAgent calls the scraper directly when this goal becomes due."""
    priority: int = PRIORITY_SCRAPE
    name: str = "scrape_tab"
    tab: str = "chat"           # "chat" | "email" | "calendar"
    interval_s: int = 300       # default 5 min
    last_run: float = 0.0
    in_progress: bool = False

    def is_satisfied(self, obs: Observation) -> bool:
        # Satisfied when not due
        return not self.is_due(time.time())

    def is_due(self, now: float) -> bool:
        return (now - self.last_run) >= self.interval_s

    def mark_done(self) -> None:
        self.last_run = time.time()
        self.in_progress = False

    def context(self, obs: Observation) -> GoalContext:
        return GoalContext(
            description=f"Periodic scrape of '{self.tab}' tab is due",
        )


@dataclass
class NewMessageGoal(Goal):
    """When unread badge appears in Teams chat list, fetch the new message
    immediately rather than waiting for the periodic scrape."""
    priority: int = PRIORITY_NEW_MESSAGE
    name: str = "fetch_new_message"
    pending: bool = False
    chat_label: str = ""

    def is_satisfied(self, obs: Observation) -> bool:
        return not self.pending

    def context(self, obs: Observation) -> GoalContext:
        return GoalContext(
            description=f"New unread message detected in chat: {self.chat_label}. "
                        f"Open it and read the latest message.",
        )

    def trigger(self, chat_label: str) -> None:
        self.pending = True
        self.chat_label = chat_label

    def mark_done(self) -> None:
        self.pending = False
        self.chat_label = ""
