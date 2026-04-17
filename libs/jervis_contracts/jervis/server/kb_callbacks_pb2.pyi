from jervis.common import types_pb2 as _types_pb2
from jervis.server import orchestrator_progress_pb2 as _orchestrator_progress_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class KbProgressRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "client_id", "step", "message", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    STEP_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    client_id: str
    step: str
    message: str
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ..., step: _Optional[str] = ..., message: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class KbDoneRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "client_id", "status", "error", "result")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    RESULT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    client_id: str
    status: str
    error: str
    result: KbCompletionResult
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ..., status: _Optional[str] = ..., error: _Optional[str] = ..., result: _Optional[_Union[KbCompletionResult, _Mapping]] = ...) -> None: ...

class KbCompletionResult(_message.Message):
    __slots__ = ("status", "chunks_count", "nodes_created", "edges_created", "attachments_processed", "attachments_failed", "summary", "entities", "has_actionable_content", "suggested_actions", "has_future_deadline", "suggested_deadline", "is_assigned_to_me", "urgency", "action_type", "estimated_complexity", "suggested_agent", "affected_files", "related_kb_nodes")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_COUNT_FIELD_NUMBER: _ClassVar[int]
    NODES_CREATED_FIELD_NUMBER: _ClassVar[int]
    EDGES_CREATED_FIELD_NUMBER: _ClassVar[int]
    ATTACHMENTS_PROCESSED_FIELD_NUMBER: _ClassVar[int]
    ATTACHMENTS_FAILED_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    ENTITIES_FIELD_NUMBER: _ClassVar[int]
    HAS_ACTIONABLE_CONTENT_FIELD_NUMBER: _ClassVar[int]
    SUGGESTED_ACTIONS_FIELD_NUMBER: _ClassVar[int]
    HAS_FUTURE_DEADLINE_FIELD_NUMBER: _ClassVar[int]
    SUGGESTED_DEADLINE_FIELD_NUMBER: _ClassVar[int]
    IS_ASSIGNED_TO_ME_FIELD_NUMBER: _ClassVar[int]
    URGENCY_FIELD_NUMBER: _ClassVar[int]
    ACTION_TYPE_FIELD_NUMBER: _ClassVar[int]
    ESTIMATED_COMPLEXITY_FIELD_NUMBER: _ClassVar[int]
    SUGGESTED_AGENT_FIELD_NUMBER: _ClassVar[int]
    AFFECTED_FILES_FIELD_NUMBER: _ClassVar[int]
    RELATED_KB_NODES_FIELD_NUMBER: _ClassVar[int]
    status: str
    chunks_count: int
    nodes_created: int
    edges_created: int
    attachments_processed: int
    attachments_failed: int
    summary: str
    entities: _containers.RepeatedScalarFieldContainer[str]
    has_actionable_content: bool
    suggested_actions: _containers.RepeatedScalarFieldContainer[str]
    has_future_deadline: bool
    suggested_deadline: str
    is_assigned_to_me: bool
    urgency: str
    action_type: str
    estimated_complexity: str
    suggested_agent: str
    affected_files: _containers.RepeatedScalarFieldContainer[str]
    related_kb_nodes: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, status: _Optional[str] = ..., chunks_count: _Optional[int] = ..., nodes_created: _Optional[int] = ..., edges_created: _Optional[int] = ..., attachments_processed: _Optional[int] = ..., attachments_failed: _Optional[int] = ..., summary: _Optional[str] = ..., entities: _Optional[_Iterable[str]] = ..., has_actionable_content: bool = ..., suggested_actions: _Optional[_Iterable[str]] = ..., has_future_deadline: bool = ..., suggested_deadline: _Optional[str] = ..., is_assigned_to_me: bool = ..., urgency: _Optional[str] = ..., action_type: _Optional[str] = ..., estimated_complexity: _Optional[str] = ..., suggested_agent: _Optional[str] = ..., affected_files: _Optional[_Iterable[str]] = ..., related_kb_nodes: _Optional[_Iterable[str]] = ...) -> None: ...
