from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class AckResponse(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class OrchestratorProgressRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "client_id", "node", "message", "percent", "goal_index", "total_goals", "step_index", "total_steps")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    NODE_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    PERCENT_FIELD_NUMBER: _ClassVar[int]
    GOAL_INDEX_FIELD_NUMBER: _ClassVar[int]
    TOTAL_GOALS_FIELD_NUMBER: _ClassVar[int]
    STEP_INDEX_FIELD_NUMBER: _ClassVar[int]
    TOTAL_STEPS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    client_id: str
    node: str
    message: str
    percent: float
    goal_index: int
    total_goals: int
    step_index: int
    total_steps: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ..., node: _Optional[str] = ..., message: _Optional[str] = ..., percent: _Optional[float] = ..., goal_index: _Optional[int] = ..., total_goals: _Optional[int] = ..., step_index: _Optional[int] = ..., total_steps: _Optional[int] = ...) -> None: ...

class OrchestratorStatusRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "client_id", "thread_id", "status", "summary", "error", "interrupt_action", "interrupt_description", "branch", "artifacts", "keep_environment_running")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    THREAD_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    INTERRUPT_ACTION_FIELD_NUMBER: _ClassVar[int]
    INTERRUPT_DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    BRANCH_FIELD_NUMBER: _ClassVar[int]
    ARTIFACTS_FIELD_NUMBER: _ClassVar[int]
    KEEP_ENVIRONMENT_RUNNING_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    client_id: str
    thread_id: str
    status: str
    summary: str
    error: str
    interrupt_action: str
    interrupt_description: str
    branch: str
    artifacts: _containers.RepeatedScalarFieldContainer[str]
    keep_environment_running: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ..., thread_id: _Optional[str] = ..., status: _Optional[str] = ..., summary: _Optional[str] = ..., error: _Optional[str] = ..., interrupt_action: _Optional[str] = ..., interrupt_description: _Optional[str] = ..., branch: _Optional[str] = ..., artifacts: _Optional[_Iterable[str]] = ..., keep_environment_running: bool = ...) -> None: ...

class QualificationDoneRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "client_id", "decision", "priority_score", "reason", "alert_message", "target_task_id", "context_summary", "suggested_approach", "action_type", "estimated_complexity", "pending_user_question", "user_question_context", "sub_tasks")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    DECISION_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_SCORE_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    ALERT_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    TARGET_TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_SUMMARY_FIELD_NUMBER: _ClassVar[int]
    SUGGESTED_APPROACH_FIELD_NUMBER: _ClassVar[int]
    ACTION_TYPE_FIELD_NUMBER: _ClassVar[int]
    ESTIMATED_COMPLEXITY_FIELD_NUMBER: _ClassVar[int]
    PENDING_USER_QUESTION_FIELD_NUMBER: _ClassVar[int]
    USER_QUESTION_CONTEXT_FIELD_NUMBER: _ClassVar[int]
    SUB_TASKS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    client_id: str
    decision: str
    priority_score: int
    reason: str
    alert_message: str
    target_task_id: str
    context_summary: str
    suggested_approach: str
    action_type: str
    estimated_complexity: str
    pending_user_question: str
    user_question_context: str
    sub_tasks: _containers.RepeatedCompositeFieldContainer[SubTaskSpec]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ..., decision: _Optional[str] = ..., priority_score: _Optional[int] = ..., reason: _Optional[str] = ..., alert_message: _Optional[str] = ..., target_task_id: _Optional[str] = ..., context_summary: _Optional[str] = ..., suggested_approach: _Optional[str] = ..., action_type: _Optional[str] = ..., estimated_complexity: _Optional[str] = ..., pending_user_question: _Optional[str] = ..., user_question_context: _Optional[str] = ..., sub_tasks: _Optional[_Iterable[_Union[SubTaskSpec, _Mapping]]] = ...) -> None: ...

class SubTaskSpec(_message.Message):
    __slots__ = ("task_name", "content", "phase", "order_in_phase")
    TASK_NAME_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    PHASE_FIELD_NUMBER: _ClassVar[int]
    ORDER_IN_PHASE_FIELD_NUMBER: _ClassVar[int]
    task_name: str
    content: str
    phase: str
    order_in_phase: int
    def __init__(self, task_name: _Optional[str] = ..., content: _Optional[str] = ..., phase: _Optional[str] = ..., order_in_phase: _Optional[int] = ...) -> None: ...

class MemoryGraphChangedRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class ThinkingGraphUpdateRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "task_title", "graph_id", "status", "message", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TASK_TITLE_FIELD_NUMBER: _ClassVar[int]
    GRAPH_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    task_title: str
    graph_id: str
    status: str
    message: str
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., task_title: _Optional[str] = ..., graph_id: _Optional[str] = ..., status: _Optional[str] = ..., message: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class CorrectionProgressRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "client_id", "percent", "chunks_done", "total_chunks", "message", "tokens_generated")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PERCENT_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_DONE_FIELD_NUMBER: _ClassVar[int]
    TOTAL_CHUNKS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    TOKENS_GENERATED_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    client_id: str
    percent: float
    chunks_done: int
    total_chunks: int
    message: str
    tokens_generated: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., client_id: _Optional[str] = ..., percent: _Optional[float] = ..., chunks_done: _Optional[int] = ..., total_chunks: _Optional[int] = ..., message: _Optional[str] = ..., tokens_generated: _Optional[int] = ...) -> None: ...
