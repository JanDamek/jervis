"""Data models for the orchestrator service."""

from __future__ import annotations

from enum import Enum
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
    """LLM tier hierarchy.

    Local tiers (Ollama) — default, free, always used when sufficient.
    Cloud tiers — only when cloud providers explicitly enabled in project rules.
    """

    LOCAL_FAST = "local_fast"           # Ollama, 8k ctx — decompose, simple plan
    LOCAL_STANDARD = "local_standard"   # Ollama, 32k ctx — standard tasks
    LOCAL_LARGE = "local_large"         # Ollama, 48k ctx — GPU VRAM limit (fast)
    LOCAL_XLARGE = "local_xlarge"       # Ollama, 128k ctx — CPU RAM (slower but works)
    LOCAL_XXLARGE = "local_xxlarge"     # Ollama, 256k ctx — qwen3 max context
    CLOUD_REASONING = "cloud_reasoning"     # Anthropic — critical architecture/design
    CLOUD_CODING = "cloud_coding"           # Anthropic — critical code changes
    CLOUD_PREMIUM = "cloud_premium"         # Anthropic Opus — last resort, critical
    CLOUD_LARGE_CONTEXT = "cloud_large_context"  # Gemini — ultra-large context (1M tokens)


class TaskCategory(str, Enum):
    """What kind of task the orchestrator is handling."""
    ADVICE = "advice"              # Answer/analysis (LLM + KB), no coding
    SINGLE_TASK = "single_task"    # Single issue/task — may or may not involve coding
    EPIC = "epic"                  # Multi-issue epic — batch execution in waves
    GENERATIVE = "generative"      # Design + generate epics/tasks + execute


class TaskAction(str, Enum):
    """What SINGLE_TASK needs to resolve it."""
    RESPOND = "respond"            # Answer/analysis (LLM + KB)
    CODE = "code"                  # Coding agent needed
    TRACKER_OPS = "tracker_ops"    # Create/update issues in tracker
    MIXED = "mixed"                # Combination of above


class StepType(str, Enum):
    """Type of execution step."""
    RESPOND = "respond"            # Analytical — LLM + KB directly
    CODE = "code"                  # Coding agent via K8s Job
    TRACKER = "tracker"            # Tracker operations via Kotlin API


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
    auto_use_anthropic: bool = False
    auto_use_openai: bool = False
    auto_use_gemini: bool = False


# --- Task Models ---


class CodingTask(BaseModel):
    """Input task for the orchestrator."""

    id: str
    client_id: str
    project_id: str | None = None
    client_name: str | None = None
    project_name: str | None = None
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
    """One step in the execution plan."""

    index: int
    instructions: str
    step_type: StepType = StepType.CODE
    agent_type: AgentType = AgentType.CLAUDE
    files: list[str] = Field(default_factory=list)
    tracker_operations: list[dict] = Field(default_factory=list)  # For TRACKER steps


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


# --- Clarification ---


class ClarificationQuestion(BaseModel):
    """A question the orchestrator needs answered before proceeding."""

    id: str  # e.g. "q1"
    question: str
    options: list[str] = Field(default_factory=list)  # Suggested answers (empty = freeform)
    required: bool = True


# --- Evidence Pack ---


class EvidencePack(BaseModel):
    """Collected evidence for task processing."""

    kb_results: list[dict] = Field(default_factory=list)
    tracker_artifacts: list[dict] = Field(default_factory=list)
    chat_history_summary: str = ""
    external_refs: list[str] = Field(default_factory=list)
    facts: list[str] = Field(default_factory=list)
    unknowns: list[str] = Field(default_factory=list)


# --- Cross-goal Context ---


class GoalSummary(BaseModel):
    """Summary of a completed goal for cross-goal context."""

    goal_id: str
    title: str
    summary: str
    changed_files: list[str] = Field(default_factory=list)
    key_decisions: list[str] = Field(default_factory=list)


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


# --- Chat History ---


class ChatHistoryMessage(BaseModel):
    """Single verbatim message from recent conversation history."""

    role: str
    content: str
    timestamp: str
    sequence: int


class ChatSummaryBlock(BaseModel):
    """Compressed summary of an older block of messages."""

    sequence_range: str  # "1-20"
    summary: str
    key_decisions: list[str] = Field(default_factory=list)
    topics: list[str] = Field(default_factory=list)
    is_checkpoint: bool = False
    checkpoint_reason: str | None = None


class ChatHistoryPayload(BaseModel):
    """Full chat history context for orchestrator."""

    recent_messages: list[ChatHistoryMessage] = Field(default_factory=list)
    summary_blocks: list[ChatSummaryBlock] = Field(default_factory=list)
    total_message_count: int = 0


# --- API Request/Response ---


class OrchestrateRequest(BaseModel):
    """Request from Kotlin server to start orchestration."""

    task_id: str
    client_id: str
    project_id: str | None = None
    client_name: str | None = None
    project_name: str | None = None
    workspace_path: str
    query: str
    agent_preference: str = "auto"
    rules: ProjectRules = Field(default_factory=ProjectRules)
    environment: dict | None = None  # Resolved environment context from server
    jervis_project_id: str | None = None  # JERVIS internal project for planning
    chat_history: ChatHistoryPayload | None = None  # Conversation context


class OrchestrateResponse(BaseModel):
    """Final response after orchestration completes."""

    task_id: str
    success: bool
    summary: str
    branch: str | None = None
    artifacts: list[str] = Field(default_factory=list)
    step_results: list[StepResult] = Field(default_factory=list)
    thread_id: str | None = None


# --- Multi-Agent Delegation System ---


class DomainType(str, Enum):
    """Domain classification for multi-agent routing."""

    CODE = "code"
    DEVOPS = "devops"
    PROJECT_MANAGEMENT = "project_management"
    COMMUNICATION = "communication"
    LEGAL = "legal"
    FINANCIAL = "financial"
    ADMINISTRATIVE = "administrative"
    PERSONAL = "personal"
    SECURITY = "security"
    RESEARCH = "research"
    LEARNING = "learning"


class DelegationStatus(str, Enum):
    """Status of a delegation within the execution plan."""

    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    INTERRUPTED = "interrupted"


class DelegationMessage(BaseModel):
    """Input message for agent delegation.

    Immutable contract between orchestrator and agent.
    Internal chain runs in English; response_language controls final output.
    """

    delegation_id: str
    parent_delegation_id: str | None = None
    depth: int = 0                          # 0=orchestrator, 1-4=sub-agents
    agent_name: str
    task_summary: str                       # What the agent should do (ENGLISH)
    context: str = ""                       # Token-budgeted context
    constraints: list[str] = Field(default_factory=list)
    expected_output: str = ""
    response_language: str = "en"           # ISO 639-1 for final response
    # Data isolation
    client_id: str = ""
    project_id: str | None = None
    group_id: str | None = None


class AgentOutput(BaseModel):
    """Output from an agent execution.

    Returned to orchestrator (or parent agent) after delegation.
    """

    delegation_id: str
    agent_name: str
    success: bool
    result: str = ""                        # Main output (text, summary)
    structured_data: dict = Field(default_factory=dict)
    artifacts: list[str] = Field(default_factory=list)
    changed_files: list[str] = Field(default_factory=list)
    sub_delegations: list[str] = Field(default_factory=list)
    confidence: float = 1.0                 # 0.0-1.0
    needs_verification: bool = False        # Request KB cross-check


class DelegationState(BaseModel):
    """State of a single delegation within the execution plan."""

    delegation_id: str
    agent_name: str
    status: DelegationStatus = DelegationStatus.PENDING
    result_summary: str | None = None
    sub_delegation_ids: list[str] = Field(default_factory=list)
    checkpoint_data: dict | None = None


class ExecutionPlan(BaseModel):
    """DAG of delegations produced by plan_delegations node."""

    delegations: list[DelegationMessage] = Field(default_factory=list)
    parallel_groups: list[list[str]] = Field(default_factory=list)
    domain: DomainType = DomainType.CODE


# --- Session Memory ---


class SessionEntry(BaseModel):
    """One entry in session memory (per-client/project short-term cache)."""

    timestamp: str
    source: str                             # "chat" | "background" | "orchestrator_decision"
    summary: str                            # Max 200 chars
    details: dict | None = None
    task_id: str | None = None


class SessionMemoryPayload(BaseModel):
    """Session memory for a client/project pair."""

    client_id: str
    project_id: str | None = None
    entries: list[SessionEntry] = Field(default_factory=list)


# --- Procedural Memory ---


class ProcedureStep(BaseModel):
    """One step in a learned procedure."""

    agent: str
    action: str
    parameters: dict = Field(default_factory=dict)


class ProcedureNode(BaseModel):
    """Learned workflow procedure stored in KB (ArangoDB).

    Orchestrator looks up procedures by trigger_pattern before planning.
    """

    trigger_pattern: str                    # e.g. "email_with_question", "task_completion"
    procedure_steps: list[ProcedureStep] = Field(default_factory=list)
    success_rate: float = 0.0
    last_used: str | None = None
    usage_count: int = 0
    source: str = "learned"                 # "learned" | "user_defined"
    client_id: str = ""


# --- Agent Capability ---


class AgentCapability(BaseModel):
    """Describes what an agent can do (for registry and LLM planning)."""

    name: str
    description: str
    domains: list[DomainType] = Field(default_factory=list)
    can_sub_delegate: bool = True
    max_depth: int = 4
    tool_names: list[str] = Field(default_factory=list)


# --- Delegation Metrics ---


class DelegationMetrics(BaseModel):
    """Metrics collected for a single delegation execution."""

    delegation_id: str
    agent_name: str
    start_time: str | None = None
    end_time: str | None = None
    token_count: int = 0
    llm_calls: int = 0
    sub_delegation_count: int = 0
    success: bool = False
