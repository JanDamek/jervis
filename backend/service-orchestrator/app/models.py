"""Data models for the orchestrator service."""

from __future__ import annotations

from enum import Enum
from typing import Annotated

from pydantic import BaseModel, Field


# --- Enums ---


class AgentType(str, Enum):
    AIDER = "aider"
    OPENHANDS = "openhands"
    CLAUDE = "claude"
    JUNIE = "junie"


class Complexity(str, Enum):
    SIMPLE = "simple"
    MEDIUM = "medium"
    COMPLEX = "complex"
    CRITICAL = "critical"


class ModelTier(str, Enum):
    LOCAL_FAST = "local_fast"
    LOCAL_STANDARD = "local_standard"
    LOCAL_LARGE = "local_large"
    CLOUD_REASONING = "cloud_reasoning"
    CLOUD_CODING = "cloud_coding"
    CLOUD_PREMIUM = "cloud_premium"


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


# --- Project Rules ---


class ProjectRules(BaseModel):
    """Rules loaded from client/project settings in DB."""

    branch_naming: str = "task/{taskId}"
    commit_prefix: str = "task({taskId}):"
    require_review: bool = False
    require_tests: bool = False
    require_approval_commit: bool = True
    require_approval_push: bool = True
    allowed_branches: list[str] = Field(default_factory=lambda: ["task/*", "fix/*"])
    forbidden_files: list[str] = Field(default_factory=lambda: ["*.env", "secrets/*"])
    max_changed_files: int = 20
    auto_push: bool = False


# --- Task Models ---


class CodingTask(BaseModel):
    """Input task for the orchestrator."""

    id: str
    client_id: str
    project_id: str | None = None
    workspace_path: str
    query: str
    agent_preference: str = "auto"


class Goal(BaseModel):
    """One goal decomposed from user query."""

    id: str
    title: str
    description: str
    complexity: Complexity = Complexity.MEDIUM
    dependencies: list[str] = Field(default_factory=list)


class CodingStep(BaseModel):
    """One step in the execution plan for a coding agent."""

    index: int
    instructions: str
    agent_type: AgentType
    files: list[str] = Field(default_factory=list)


class StepResult(BaseModel):
    """Result of one coding step."""

    step_index: int
    success: bool
    summary: str
    agent_type: str
    changed_files: list[str] = Field(default_factory=list)


class Evaluation(BaseModel):
    """Result of orchestrator evaluating agent output."""

    acceptable: bool
    checks: list[str] = Field(default_factory=list)
    diff: str = ""


# --- Approval ---


class ApprovalRequest(BaseModel):
    """Request for user approval before risky action."""

    action_type: str  # "commit" | "push" | "code_change"
    description: str
    details: dict = Field(default_factory=dict)
    risk_level: RiskLevel = RiskLevel.MEDIUM
    reversible: bool = True


class ApprovalResponse(BaseModel):
    """User response to approval request."""

    approved: bool
    modification: str | None = None
    reason: str | None = None


# --- API Request/Response ---


class OrchestrateRequest(BaseModel):
    """Request from Kotlin server to start orchestration."""

    task_id: str
    client_id: str
    project_id: str | None = None
    workspace_path: str
    query: str
    agent_preference: str = "auto"
    rules: ProjectRules = Field(default_factory=ProjectRules)


class OrchestrateResponse(BaseModel):
    """Final response after orchestration completes."""

    task_id: str
    success: bool
    summary: str
    branch: str | None = None
    artifacts: list[str] = Field(default_factory=list)
    step_results: list[StepResult] = Field(default_factory=list)


# --- LangGraph State ---


class OrchestratorState(dict):
    """TypedDict-compatible state for LangGraph.

    Fields:
        task: CodingTask
        rules: ProjectRules
        goals: list[Goal]
        current_goal_index: int
        steps: list[CodingStep]
        current_step_index: int
        step_results: list[StepResult]
        branch: str | None
        final_result: str | None
        artifacts: list[str]
        error: str | None
    """

    pass
