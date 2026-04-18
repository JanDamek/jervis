from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GetTaskGraphRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class TaskGraphResponse(_message.Message):
    __slots__ = ("graph_json", "found")
    GRAPH_JSON_FIELD_NUMBER: _ClassVar[int]
    FOUND_FIELD_NUMBER: _ClassVar[int]
    graph_json: str
    found: bool
    def __init__(self, graph_json: _Optional[str] = ..., found: bool = ...) -> None: ...

class MaintenanceRunRequest(_message.Message):
    __slots__ = ("ctx", "phase", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PHASE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    phase: int
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., phase: _Optional[int] = ..., client_id: _Optional[str] = ...) -> None: ...

class MaintenanceRunResult(_message.Message):
    __slots__ = ("phase", "mem_removed", "thinking_evicted", "lqm_drained", "affairs_archived", "next_client_for_phase2", "client_id", "findings")
    PHASE_FIELD_NUMBER: _ClassVar[int]
    MEM_REMOVED_FIELD_NUMBER: _ClassVar[int]
    THINKING_EVICTED_FIELD_NUMBER: _ClassVar[int]
    LQM_DRAINED_FIELD_NUMBER: _ClassVar[int]
    AFFAIRS_ARCHIVED_FIELD_NUMBER: _ClassVar[int]
    NEXT_CLIENT_FOR_PHASE2_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    FINDINGS_FIELD_NUMBER: _ClassVar[int]
    phase: int
    mem_removed: int
    thinking_evicted: int
    lqm_drained: int
    affairs_archived: int
    next_client_for_phase2: str
    client_id: str
    findings: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, phase: _Optional[int] = ..., mem_removed: _Optional[int] = ..., thinking_evicted: _Optional[int] = ..., lqm_drained: _Optional[int] = ..., affairs_archived: _Optional[int] = ..., next_client_for_phase2: _Optional[str] = ..., client_id: _Optional[str] = ..., findings: _Optional[_Iterable[str]] = ...) -> None: ...
