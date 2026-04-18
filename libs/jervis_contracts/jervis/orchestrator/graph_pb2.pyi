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
    __slots__ = ("graph", "found")
    GRAPH_FIELD_NUMBER: _ClassVar[int]
    FOUND_FIELD_NUMBER: _ClassVar[int]
    graph: AgentGraph
    found: bool
    def __init__(self, graph: _Optional[_Union[AgentGraph, _Mapping]] = ..., found: bool = ...) -> None: ...

class AgentGraph(_message.Message):
    __slots__ = ("id", "task_id", "client_id", "project_id", "status", "graph_type", "root_vertex_id", "synthesis_vertex_id", "vertices", "edges", "created_at", "completed_at", "total_token_count", "total_llm_calls", "hidden")
    class VerticesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: GraphVertex
        def __init__(self, key: _Optional[str] = ..., value: _Optional[_Union[GraphVertex, _Mapping]] = ...) -> None: ...
    ID_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    GRAPH_TYPE_FIELD_NUMBER: _ClassVar[int]
    ROOT_VERTEX_ID_FIELD_NUMBER: _ClassVar[int]
    SYNTHESIS_VERTEX_ID_FIELD_NUMBER: _ClassVar[int]
    VERTICES_FIELD_NUMBER: _ClassVar[int]
    EDGES_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    COMPLETED_AT_FIELD_NUMBER: _ClassVar[int]
    TOTAL_TOKEN_COUNT_FIELD_NUMBER: _ClassVar[int]
    TOTAL_LLM_CALLS_FIELD_NUMBER: _ClassVar[int]
    HIDDEN_FIELD_NUMBER: _ClassVar[int]
    id: str
    task_id: str
    client_id: str
    project_id: str
    status: str
    graph_type: str
    root_vertex_id: str
    synthesis_vertex_id: str
    vertices: _containers.MessageMap[str, GraphVertex]
    edges: _containers.RepeatedCompositeFieldContainer[GraphEdge]
    created_at: str
    completed_at: str
    total_token_count: int
    total_llm_calls: int
    hidden: bool
    def __init__(self, id: _Optional[str] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., status: _Optional[str] = ..., graph_type: _Optional[str] = ..., root_vertex_id: _Optional[str] = ..., synthesis_vertex_id: _Optional[str] = ..., vertices: _Optional[_Mapping[str, GraphVertex]] = ..., edges: _Optional[_Iterable[_Union[GraphEdge, _Mapping]]] = ..., created_at: _Optional[str] = ..., completed_at: _Optional[str] = ..., total_token_count: _Optional[int] = ..., total_llm_calls: _Optional[int] = ..., hidden: bool = ...) -> None: ...

class GraphVertex(_message.Message):
    __slots__ = ("id", "title", "description", "vertex_type", "status", "agent_name", "input_request", "result", "result_summary", "local_context", "parent_id", "depth", "tools_used", "token_count", "llm_calls", "started_at", "completed_at", "error", "client_id")
    ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    VERTEX_TYPE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    AGENT_NAME_FIELD_NUMBER: _ClassVar[int]
    INPUT_REQUEST_FIELD_NUMBER: _ClassVar[int]
    RESULT_FIELD_NUMBER: _ClassVar[int]
    RESULT_SUMMARY_FIELD_NUMBER: _ClassVar[int]
    LOCAL_CONTEXT_FIELD_NUMBER: _ClassVar[int]
    PARENT_ID_FIELD_NUMBER: _ClassVar[int]
    DEPTH_FIELD_NUMBER: _ClassVar[int]
    TOOLS_USED_FIELD_NUMBER: _ClassVar[int]
    TOKEN_COUNT_FIELD_NUMBER: _ClassVar[int]
    LLM_CALLS_FIELD_NUMBER: _ClassVar[int]
    STARTED_AT_FIELD_NUMBER: _ClassVar[int]
    COMPLETED_AT_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    id: str
    title: str
    description: str
    vertex_type: str
    status: str
    agent_name: str
    input_request: str
    result: str
    result_summary: str
    local_context: str
    parent_id: str
    depth: int
    tools_used: _containers.RepeatedScalarFieldContainer[str]
    token_count: int
    llm_calls: int
    started_at: str
    completed_at: str
    error: str
    client_id: str
    def __init__(self, id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., vertex_type: _Optional[str] = ..., status: _Optional[str] = ..., agent_name: _Optional[str] = ..., input_request: _Optional[str] = ..., result: _Optional[str] = ..., result_summary: _Optional[str] = ..., local_context: _Optional[str] = ..., parent_id: _Optional[str] = ..., depth: _Optional[int] = ..., tools_used: _Optional[_Iterable[str]] = ..., token_count: _Optional[int] = ..., llm_calls: _Optional[int] = ..., started_at: _Optional[str] = ..., completed_at: _Optional[str] = ..., error: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class GraphEdge(_message.Message):
    __slots__ = ("id", "source_id", "target_id", "edge_type", "payload")
    ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_ID_FIELD_NUMBER: _ClassVar[int]
    TARGET_ID_FIELD_NUMBER: _ClassVar[int]
    EDGE_TYPE_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    id: str
    source_id: str
    target_id: str
    edge_type: str
    payload: EdgePayload
    def __init__(self, id: _Optional[str] = ..., source_id: _Optional[str] = ..., target_id: _Optional[str] = ..., edge_type: _Optional[str] = ..., payload: _Optional[_Union[EdgePayload, _Mapping]] = ...) -> None: ...

class EdgePayload(_message.Message):
    __slots__ = ("source_vertex_id", "source_vertex_title", "summary", "context")
    SOURCE_VERTEX_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_VERTEX_TITLE_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    source_vertex_id: str
    source_vertex_title: str
    summary: str
    context: str
    def __init__(self, source_vertex_id: _Optional[str] = ..., source_vertex_title: _Optional[str] = ..., summary: _Optional[str] = ..., context: _Optional[str] = ...) -> None: ...

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
