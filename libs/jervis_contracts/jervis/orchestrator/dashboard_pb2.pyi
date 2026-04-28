from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GetSessionSnapshotRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class SessionSnapshotResponse(_message.Message):
    __slots__ = ("ok", "error", "active_count", "cap", "paused", "sessions", "agent_job_holds", "recent_evictions")
    class AgentJobHoldsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_COUNT_FIELD_NUMBER: _ClassVar[int]
    CAP_FIELD_NUMBER: _ClassVar[int]
    PAUSED_FIELD_NUMBER: _ClassVar[int]
    SESSIONS_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_HOLDS_FIELD_NUMBER: _ClassVar[int]
    RECENT_EVICTIONS_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    active_count: int
    cap: int
    paused: bool
    sessions: _containers.RepeatedCompositeFieldContainer[ActiveSession]
    agent_job_holds: _containers.ScalarMap[str, str]
    recent_evictions: _containers.RepeatedCompositeFieldContainer[EvictionRecord]
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., active_count: _Optional[int] = ..., cap: _Optional[int] = ..., paused: bool = ..., sessions: _Optional[_Iterable[_Union[ActiveSession, _Mapping]]] = ..., agent_job_holds: _Optional[_Mapping[str, str]] = ..., recent_evictions: _Optional[_Iterable[_Union[EvictionRecord, _Mapping]]] = ...) -> None: ...

class ActiveSession(_message.Message):
    __slots__ = ("scope", "session_id", "client_id", "project_id", "cumulative_tokens", "idle_seconds", "compact_in_progress", "last_compact_age_seconds")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    CUMULATIVE_TOKENS_FIELD_NUMBER: _ClassVar[int]
    IDLE_SECONDS_FIELD_NUMBER: _ClassVar[int]
    COMPACT_IN_PROGRESS_FIELD_NUMBER: _ClassVar[int]
    LAST_COMPACT_AGE_SECONDS_FIELD_NUMBER: _ClassVar[int]
    scope: str
    session_id: str
    client_id: str
    project_id: str
    cumulative_tokens: int
    idle_seconds: int
    compact_in_progress: bool
    last_compact_age_seconds: int
    def __init__(self, scope: _Optional[str] = ..., session_id: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., cumulative_tokens: _Optional[int] = ..., idle_seconds: _Optional[int] = ..., compact_in_progress: bool = ..., last_compact_age_seconds: _Optional[int] = ...) -> None: ...

class EvictionRecord(_message.Message):
    __slots__ = ("scope", "reason", "ts")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    TS_FIELD_NUMBER: _ClassVar[int]
    scope: str
    reason: str
    ts: str
    def __init__(self, scope: _Optional[str] = ..., reason: _Optional[str] = ..., ts: _Optional[str] = ...) -> None: ...
