"""EPIC 4: Universal Approval Gate — centralized approval decision for all write actions.

Every "write" action in the orchestrator passes through the ApprovalGate before execution.
The gate evaluates:
1. Guidelines approval rules (per-action auto-approve settings)
2. Risk level of the action
3. Confidence score from the agent

Decisions:
- AUTO_APPROVED: Action proceeds without user confirmation
- NEEDS_APPROVAL: Action requires user approval (interrupt)
- DENIED: Action is rejected based on guidelines

Usage in orchestrator:
    decision = await approval_gate.evaluate(
        action="GIT_COMMIT",
        payload={"branch": "main", "files": [...]},
        risk_level="MEDIUM",
        confidence=0.85,
        client_id="c1",
        project_id="p1",
    )
    if decision == ApprovalDecision.AUTO_APPROVED:
        # proceed
    elif decision == ApprovalDecision.NEEDS_APPROVAL:
        # interrupt and wait for user
    else:
        # action denied
"""

from __future__ import annotations

import logging
from enum import Enum
from typing import Any

logger = logging.getLogger(__name__)


class ApprovalDecision(str, Enum):
    AUTO_APPROVED = "AUTO_APPROVED"
    NEEDS_APPROVAL = "NEEDS_APPROVAL"
    DENIED = "DENIED"


class ApprovalAction(str, Enum):
    """All action types that can go through the approval gate."""
    GIT_COMMIT = "GIT_COMMIT"
    GIT_PUSH = "GIT_PUSH"
    GIT_CREATE_BRANCH = "GIT_CREATE_BRANCH"
    EMAIL_SEND = "EMAIL_SEND"
    EMAIL_REPLY = "EMAIL_REPLY"
    PR_CREATE = "PR_CREATE"
    PR_COMMENT = "PR_COMMENT"
    PR_MERGE = "PR_MERGE"
    CHAT_REPLY = "CHAT_REPLY"
    KB_DELETE = "KB_DELETE"
    KB_STORE = "KB_STORE"
    DEPLOY = "DEPLOY"
    CODING_DISPATCH = "CODING_DISPATCH"


# Mapping from ApprovalAction to guidelines approval rule field name
_ACTION_TO_RULE_FIELD: dict[ApprovalAction, str] = {
    ApprovalAction.GIT_COMMIT: "autoApproveCommit",
    ApprovalAction.GIT_PUSH: "autoApprovePush",
    ApprovalAction.EMAIL_SEND: "autoApproveEmail",
    ApprovalAction.EMAIL_REPLY: "autoApproveEmail",
    ApprovalAction.PR_CREATE: "autoApprovePrComment",
    ApprovalAction.PR_COMMENT: "autoApprovePrComment",
    ApprovalAction.PR_MERGE: "autoApprovePush",  # PR merge ≈ push
    ApprovalAction.CHAT_REPLY: "autoApproveChatReply",
    ApprovalAction.CODING_DISPATCH: "autoApproveCodingDispatch",
    # KB and Deploy have no specific rules — always require approval
}

# Risk level ordering
_RISK_LEVELS = {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}


class ApprovalGate:
    """Centralized approval gate for all write actions.

    Evaluates guidelines approval rules, risk level, and confidence
    to determine whether an action can proceed automatically.
    """

    async def evaluate(
        self,
        action: str | ApprovalAction,
        payload: dict[str, Any],
        risk_level: str = "MEDIUM",
        confidence: float = 0.5,
        client_id: str | None = None,
        project_id: str | None = None,
    ) -> ApprovalDecision:
        """Evaluate whether an action should be auto-approved, needs approval, or denied.

        Args:
            action: The action type (ApprovalAction enum name or string).
            payload: Action-specific data (diff, email body, etc.).
            risk_level: Assessed risk: LOW, MEDIUM, HIGH, CRITICAL.
            confidence: Agent's confidence in the action (0.0–1.0).
            client_id: Client context for guidelines lookup.
            project_id: Project context for guidelines lookup.

        Returns:
            ApprovalDecision: AUTO_APPROVED, NEEDS_APPROVAL, or DENIED.
        """
        try:
            action_enum = ApprovalAction(action) if isinstance(action, str) else action
        except ValueError:
            logger.warning("Unknown approval action: %s → NEEDS_APPROVAL", action)
            return ApprovalDecision.NEEDS_APPROVAL

        # CRITICAL risk → always needs approval
        if risk_level == "CRITICAL":
            logger.info(
                "APPROVAL_GATE: action=%s risk=CRITICAL → NEEDS_APPROVAL",
                action_enum.value,
            )
            return ApprovalDecision.NEEDS_APPROVAL

        # DEPLOY → always needs approval
        if action_enum == ApprovalAction.DEPLOY:
            return ApprovalDecision.NEEDS_APPROVAL

        # KB_DELETE → always needs approval
        if action_enum == ApprovalAction.KB_DELETE:
            return ApprovalDecision.NEEDS_APPROVAL

        # Load guidelines
        guidelines = await self._load_guidelines(client_id, project_id)
        approval_rules = guidelines.get("approval", {})

        # Find the matching rule
        rule_field = _ACTION_TO_RULE_FIELD.get(action_enum)
        if not rule_field:
            # No rule configured → needs approval
            return ApprovalDecision.NEEDS_APPROVAL

        rule = approval_rules.get(rule_field, {})
        if not isinstance(rule, dict):
            return ApprovalDecision.NEEDS_APPROVAL

        # Check if auto-approve is enabled for this action
        if not rule.get("enabled", False):
            logger.debug(
                "APPROVAL_GATE: action=%s auto_approve=disabled → NEEDS_APPROVAL",
                action_enum.value,
            )
            return ApprovalDecision.NEEDS_APPROVAL

        # Check risk level condition
        max_risk = rule.get("whenRiskLevelBelow")
        if max_risk:
            action_risk = _RISK_LEVELS.get(risk_level, 2)
            threshold_risk = _RISK_LEVELS.get(max_risk, 2)
            if action_risk >= threshold_risk:
                logger.debug(
                    "APPROVAL_GATE: action=%s risk=%s >= threshold=%s → NEEDS_APPROVAL",
                    action_enum.value, risk_level, max_risk,
                )
                return ApprovalDecision.NEEDS_APPROVAL

        # Check confidence condition
        min_confidence = rule.get("whenConfidenceAbove")
        if min_confidence is not None:
            if confidence <= min_confidence:
                logger.debug(
                    "APPROVAL_GATE: action=%s confidence=%.2f <= threshold=%.2f → NEEDS_APPROVAL",
                    action_enum.value, confidence, min_confidence,
                )
                return ApprovalDecision.NEEDS_APPROVAL

        logger.info(
            "APPROVAL_GATE: action=%s risk=%s confidence=%.2f → AUTO_APPROVED",
            action_enum.value, risk_level, confidence,
        )
        return ApprovalDecision.AUTO_APPROVED

    async def _load_guidelines(
        self, client_id: str | None, project_id: str | None,
    ) -> dict:
        """Load merged guidelines for the given context."""
        try:
            from app.context.guidelines_resolver import resolve_guidelines
            return await resolve_guidelines(client_id, project_id)
        except Exception as e:
            logger.warning("Failed to load guidelines for approval gate: %s", e)
            return {}


# Singleton instance
approval_gate = ApprovalGate()
