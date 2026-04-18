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

class VertexIdRequest(_message.Message):
    __slots__ = ("ctx", "vertex_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    VERTEX_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    vertex_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., vertex_id: _Optional[str] = ...) -> None: ...

class UpdateVertexRequest(_message.Message):
    __slots__ = ("ctx", "vertex_id", "fields_json")
    CTX_FIELD_NUMBER: _ClassVar[int]
    VERTEX_ID_FIELD_NUMBER: _ClassVar[int]
    FIELDS_JSON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    vertex_id: str
    fields_json: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., vertex_id: _Optional[str] = ..., fields_json: _Optional[str] = ...) -> None: ...

class CreateVertexRequest(_message.Message):
    __slots__ = ("ctx", "vertex_id", "title", "description", "vertex_type", "status", "parent_id", "depth", "client_id", "input_request")
    CTX_FIELD_NUMBER: _ClassVar[int]
    VERTEX_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    VERTEX_TYPE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    PARENT_ID_FIELD_NUMBER: _ClassVar[int]
    DEPTH_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    INPUT_REQUEST_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    vertex_id: str
    title: str
    description: str
    vertex_type: str
    status: str
    parent_id: str
    depth: int
    client_id: str
    input_request: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., vertex_id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., vertex_type: _Optional[str] = ..., status: _Optional[str] = ..., parent_id: _Optional[str] = ..., depth: _Optional[int] = ..., client_id: _Optional[str] = ..., input_request: _Optional[str] = ...) -> None: ...

class VertexMutationAck(_message.Message):
    __slots__ = ("ok", "vertex_id", "remaining_vertices", "vertex_json", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    VERTEX_ID_FIELD_NUMBER: _ClassVar[int]
    REMAINING_VERTICES_FIELD_NUMBER: _ClassVar[int]
    VERTEX_JSON_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    vertex_id: str
    remaining_vertices: int
    vertex_json: str
    error: str
    def __init__(self, ok: bool = ..., vertex_id: _Optional[str] = ..., remaining_vertices: _Optional[int] = ..., vertex_json: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class CleanupRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class CleanupResult(_message.Message):
    __slots__ = ("removed", "remaining_vertices")
    REMOVED_FIELD_NUMBER: _ClassVar[int]
    REMAINING_VERTICES_FIELD_NUMBER: _ClassVar[int]
    removed: int
    remaining_vertices: int
    def __init__(self, removed: _Optional[int] = ..., remaining_vertices: _Optional[int] = ...) -> None: ...

class PurgeStaleRequest(_message.Message):
    __slots__ = ("ctx", "max_age_hours")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MAX_AGE_HOURS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    max_age_hours: float
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., max_age_hours: _Optional[float] = ...) -> None: ...

class PurgeStaleResult(_message.Message):
    __slots__ = ("purged", "remaining", "detail")
    PURGED_FIELD_NUMBER: _ClassVar[int]
    REMAINING_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    purged: int
    remaining: int
    detail: str
    def __init__(self, purged: _Optional[int] = ..., remaining: _Optional[int] = ..., detail: _Optional[str] = ...) -> None: ...

class MemorySearchRequest(_message.Message):
    __slots__ = ("ctx", "query", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    query: str
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., query: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class MemorySearchResult(_message.Message):
    __slots__ = ("result_json",)
    RESULT_JSON_FIELD_NUMBER: _ClassVar[int]
    result_json: str
    def __init__(self, result_json: _Optional[str] = ...) -> None: ...
