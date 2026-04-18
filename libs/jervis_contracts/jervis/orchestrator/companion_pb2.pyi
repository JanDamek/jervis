from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class AdhocRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "brief", "client_id", "project_id", "language", "context", "attachment_paths")
    class ContextEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    BRIEF_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    ATTACHMENT_PATHS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    brief: str
    client_id: str
    project_id: str
    language: str
    context: _containers.ScalarMap[str, str]
    attachment_paths: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., brief: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., language: _Optional[str] = ..., context: _Optional[_Mapping[str, str]] = ..., attachment_paths: _Optional[_Iterable[str]] = ...) -> None: ...

class AdhocAck(_message.Message):
    __slots__ = ("job_name", "workspace_path", "mode", "error")
    JOB_NAME_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    job_name: str
    workspace_path: str
    mode: str
    error: str
    def __init__(self, job_name: _Optional[str] = ..., workspace_path: _Optional[str] = ..., mode: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class AdhocStatusRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "workspace_path")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    workspace_path: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., workspace_path: _Optional[str] = ...) -> None: ...

class AdhocStatusResponse(_message.Message):
    __slots__ = ("task_id", "status", "result_json")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    RESULT_JSON_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    status: str
    result_json: str
    def __init__(self, task_id: _Optional[str] = ..., status: _Optional[str] = ..., result_json: _Optional[str] = ...) -> None: ...

class SessionStartRequest(_message.Message):
    __slots__ = ("ctx", "session_id", "brief", "client_id", "project_id", "language", "context", "attachment_paths")
    class ContextEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    BRIEF_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    ATTACHMENT_PATHS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    brief: str
    client_id: str
    project_id: str
    language: str
    context: _containers.ScalarMap[str, str]
    attachment_paths: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ..., brief: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., language: _Optional[str] = ..., context: _Optional[_Mapping[str, str]] = ..., attachment_paths: _Optional[_Iterable[str]] = ...) -> None: ...

class SessionStartResponse(_message.Message):
    __slots__ = ("job_name", "workspace_path", "session_id", "error")
    JOB_NAME_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    job_name: str
    workspace_path: str
    session_id: str
    error: str
    def __init__(self, job_name: _Optional[str] = ..., workspace_path: _Optional[str] = ..., session_id: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class SessionEventRequest(_message.Message):
    __slots__ = ("ctx", "session_id", "type", "content", "meta")
    class MetaEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    META_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    type: str
    content: str
    meta: _containers.ScalarMap[str, str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ..., type: _Optional[str] = ..., content: _Optional[str] = ..., meta: _Optional[_Mapping[str, str]] = ...) -> None: ...

class SessionEventAck(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class SessionRef(_message.Message):
    __slots__ = ("ctx", "session_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ...) -> None: ...

class SessionAck(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class StreamSessionRequest(_message.Message):
    __slots__ = ("ctx", "session_id", "max_age_seconds")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    MAX_AGE_SECONDS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    max_age_seconds: float
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ..., max_age_seconds: _Optional[float] = ...) -> None: ...

class OutboxEvent(_message.Message):
    __slots__ = ("ts", "type", "content", "final", "meta")
    class MetaEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    TS_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    FINAL_FIELD_NUMBER: _ClassVar[int]
    META_FIELD_NUMBER: _ClassVar[int]
    ts: str
    type: str
    content: str
    final: bool
    meta: _containers.ScalarMap[str, str]
    def __init__(self, ts: _Optional[str] = ..., type: _Optional[str] = ..., content: _Optional[str] = ..., final: bool = ..., meta: _Optional[_Mapping[str, str]] = ...) -> None: ...
