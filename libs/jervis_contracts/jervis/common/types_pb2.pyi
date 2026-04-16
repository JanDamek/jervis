from jervis.common import enums_pb2 as _enums_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Scope(_message.Message):
    __slots__ = ("client_id", "project_id", "group_id")
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    client_id: str
    project_id: str
    group_id: str
    def __init__(self, client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ...) -> None: ...

class RequestContext(_message.Message):
    __slots__ = ("scope", "priority", "capability", "intent", "deadline_iso", "max_tier", "request_id", "task_id", "issued_at_unix_ms", "trace")
    class TraceEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    CAPABILITY_FIELD_NUMBER: _ClassVar[int]
    INTENT_FIELD_NUMBER: _ClassVar[int]
    DEADLINE_ISO_FIELD_NUMBER: _ClassVar[int]
    MAX_TIER_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    ISSUED_AT_UNIX_MS_FIELD_NUMBER: _ClassVar[int]
    TRACE_FIELD_NUMBER: _ClassVar[int]
    scope: Scope
    priority: _enums_pb2.Priority
    capability: _enums_pb2.Capability
    intent: str
    deadline_iso: str
    max_tier: _enums_pb2.TierCap
    request_id: str
    task_id: str
    issued_at_unix_ms: int
    trace: _containers.ScalarMap[str, str]
    def __init__(self, scope: _Optional[_Union[Scope, _Mapping]] = ..., priority: _Optional[_Union[_enums_pb2.Priority, str]] = ..., capability: _Optional[_Union[_enums_pb2.Capability, str]] = ..., intent: _Optional[str] = ..., deadline_iso: _Optional[str] = ..., max_tier: _Optional[_Union[_enums_pb2.TierCap, str]] = ..., request_id: _Optional[str] = ..., task_id: _Optional[str] = ..., issued_at_unix_ms: _Optional[int] = ..., trace: _Optional[_Mapping[str, str]] = ...) -> None: ...

class Urn(_message.Message):
    __slots__ = ("scheme", "authority", "path")
    SCHEME_FIELD_NUMBER: _ClassVar[int]
    AUTHORITY_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    scheme: str
    authority: str
    path: str
    def __init__(self, scheme: _Optional[str] = ..., authority: _Optional[str] = ..., path: _Optional[str] = ...) -> None: ...

class Timestamp(_message.Message):
    __slots__ = ("unix_ms", "iso")
    UNIX_MS_FIELD_NUMBER: _ClassVar[int]
    ISO_FIELD_NUMBER: _ClassVar[int]
    unix_ms: int
    iso: str
    def __init__(self, unix_ms: _Optional[int] = ..., iso: _Optional[str] = ...) -> None: ...
