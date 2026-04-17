from jervis.common import types_pb2 as _types_pb2
from jervis.knowledgebase import retrieve_pb2 as _retrieve_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GraphNode(_message.Message):
    __slots__ = ("id", "key", "label", "properties")
    class PropertiesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    ID_FIELD_NUMBER: _ClassVar[int]
    KEY_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    PROPERTIES_FIELD_NUMBER: _ClassVar[int]
    id: str
    key: str
    label: str
    properties: _containers.ScalarMap[str, str]
    def __init__(self, id: _Optional[str] = ..., key: _Optional[str] = ..., label: _Optional[str] = ..., properties: _Optional[_Mapping[str, str]] = ...) -> None: ...

class GraphNodeList(_message.Message):
    __slots__ = ("nodes",)
    NODES_FIELD_NUMBER: _ClassVar[int]
    nodes: _containers.RepeatedCompositeFieldContainer[GraphNode]
    def __init__(self, nodes: _Optional[_Iterable[_Union[GraphNode, _Mapping]]] = ...) -> None: ...

class GetNodeRequest(_message.Message):
    __slots__ = ("ctx", "node_key", "client_id", "project_id", "group_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NODE_KEY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    node_key: str
    client_id: str
    project_id: str
    group_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., node_key: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ...) -> None: ...

class SearchNodesRequest(_message.Message):
    __slots__ = ("ctx", "query", "client_id", "project_id", "group_id", "max_results", "node_type")
    CTX_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    MAX_RESULTS_FIELD_NUMBER: _ClassVar[int]
    NODE_TYPE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    query: str
    client_id: str
    project_id: str
    group_id: str
    max_results: int
    node_type: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., query: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., max_results: _Optional[int] = ..., node_type: _Optional[str] = ...) -> None: ...

class ListQueryEntitiesRequest(_message.Message):
    __slots__ = ("ctx", "query", "client_id", "project_id", "group_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    query: str
    client_id: str
    project_id: str
    group_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., query: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ...) -> None: ...

class EntityList(_message.Message):
    __slots__ = ("entities",)
    ENTITIES_FIELD_NUMBER: _ClassVar[int]
    entities: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, entities: _Optional[_Iterable[str]] = ...) -> None: ...

class ResolveAliasRequest(_message.Message):
    __slots__ = ("ctx", "alias", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ALIAS_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    alias: str
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., alias: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class AliasResolveResult(_message.Message):
    __slots__ = ("found", "canonical_key", "canonical_label")
    FOUND_FIELD_NUMBER: _ClassVar[int]
    CANONICAL_KEY_FIELD_NUMBER: _ClassVar[int]
    CANONICAL_LABEL_FIELD_NUMBER: _ClassVar[int]
    found: bool
    canonical_key: str
    canonical_label: str
    def __init__(self, found: bool = ..., canonical_key: _Optional[str] = ..., canonical_label: _Optional[str] = ...) -> None: ...

class ListAliasesRequest(_message.Message):
    __slots__ = ("ctx", "canonical_key")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CANONICAL_KEY_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    canonical_key: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., canonical_key: _Optional[str] = ...) -> None: ...

class AliasList(_message.Message):
    __slots__ = ("aliases",)
    ALIASES_FIELD_NUMBER: _ClassVar[int]
    aliases: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, aliases: _Optional[_Iterable[str]] = ...) -> None: ...

class AliasStatsRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class AliasStats(_message.Message):
    __slots__ = ("total_aliases", "total_canonicals", "by_type")
    class ByTypeEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: int
        def __init__(self, key: _Optional[str] = ..., value: _Optional[int] = ...) -> None: ...
    TOTAL_ALIASES_FIELD_NUMBER: _ClassVar[int]
    TOTAL_CANONICALS_FIELD_NUMBER: _ClassVar[int]
    BY_TYPE_FIELD_NUMBER: _ClassVar[int]
    total_aliases: int
    total_canonicals: int
    by_type: _containers.ScalarMap[str, int]
    def __init__(self, total_aliases: _Optional[int] = ..., total_canonicals: _Optional[int] = ..., by_type: _Optional[_Mapping[str, int]] = ...) -> None: ...

class RegisterAliasRequest(_message.Message):
    __slots__ = ("ctx", "alias", "canonical_key", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ALIAS_FIELD_NUMBER: _ClassVar[int]
    CANONICAL_KEY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    alias: str
    canonical_key: str
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., alias: _Optional[str] = ..., canonical_key: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class MergeAliasRequest(_message.Message):
    __slots__ = ("ctx", "from_key", "into_key", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    FROM_KEY_FIELD_NUMBER: _ClassVar[int]
    INTO_KEY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    from_key: str
    into_key: str
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., from_key: _Optional[str] = ..., into_key: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class AliasAck(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class ThoughtTraversalRequest(_message.Message):
    __slots__ = ("ctx", "query", "client_id", "project_id", "group_id", "max_results", "floor", "max_depth", "entry_top_k")
    CTX_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    MAX_RESULTS_FIELD_NUMBER: _ClassVar[int]
    FLOOR_FIELD_NUMBER: _ClassVar[int]
    MAX_DEPTH_FIELD_NUMBER: _ClassVar[int]
    ENTRY_TOP_K_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    query: str
    client_id: str
    project_id: str
    group_id: str
    max_results: int
    floor: float
    max_depth: int
    entry_top_k: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., query: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., max_results: _Optional[int] = ..., floor: _Optional[float] = ..., max_depth: _Optional[int] = ..., entry_top_k: _Optional[int] = ...) -> None: ...

class ThoughtTraversalResult(_message.Message):
    __slots__ = ("thoughts", "knowledge", "activated_thought_ids", "activated_edge_ids")
    THOUGHTS_FIELD_NUMBER: _ClassVar[int]
    KNOWLEDGE_FIELD_NUMBER: _ClassVar[int]
    ACTIVATED_THOUGHT_IDS_FIELD_NUMBER: _ClassVar[int]
    ACTIVATED_EDGE_IDS_FIELD_NUMBER: _ClassVar[int]
    thoughts: _containers.RepeatedCompositeFieldContainer[ThoughtEntry]
    knowledge: _containers.RepeatedCompositeFieldContainer[ThoughtEntry]
    activated_thought_ids: _containers.RepeatedScalarFieldContainer[str]
    activated_edge_ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, thoughts: _Optional[_Iterable[_Union[ThoughtEntry, _Mapping]]] = ..., knowledge: _Optional[_Iterable[_Union[ThoughtEntry, _Mapping]]] = ..., activated_thought_ids: _Optional[_Iterable[str]] = ..., activated_edge_ids: _Optional[_Iterable[str]] = ...) -> None: ...

class ThoughtEntry(_message.Message):
    __slots__ = ("id", "label", "summary", "node_type", "activation", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    ID_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    NODE_TYPE_FIELD_NUMBER: _ClassVar[int]
    ACTIVATION_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    id: str
    label: str
    summary: str
    node_type: str
    activation: float
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, id: _Optional[str] = ..., label: _Optional[str] = ..., summary: _Optional[str] = ..., node_type: _Optional[str] = ..., activation: _Optional[float] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class ThoughtReinforceRequest(_message.Message):
    __slots__ = ("ctx", "thought_keys", "edge_keys")
    CTX_FIELD_NUMBER: _ClassVar[int]
    THOUGHT_KEYS_FIELD_NUMBER: _ClassVar[int]
    EDGE_KEYS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    thought_keys: _containers.RepeatedScalarFieldContainer[str]
    edge_keys: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., thought_keys: _Optional[_Iterable[str]] = ..., edge_keys: _Optional[_Iterable[str]] = ...) -> None: ...

class ThoughtCreateRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "group_id", "thoughts")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    THOUGHTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    group_id: str
    thoughts: _containers.RepeatedCompositeFieldContainer[ThoughtSeed]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., thoughts: _Optional[_Iterable[_Union[ThoughtSeed, _Mapping]]] = ...) -> None: ...

class ThoughtSeed(_message.Message):
    __slots__ = ("label", "summary", "thought_type", "related_entities")
    LABEL_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    THOUGHT_TYPE_FIELD_NUMBER: _ClassVar[int]
    RELATED_ENTITIES_FIELD_NUMBER: _ClassVar[int]
    label: str
    summary: str
    thought_type: str
    related_entities: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, label: _Optional[str] = ..., summary: _Optional[str] = ..., thought_type: _Optional[str] = ..., related_entities: _Optional[_Iterable[str]] = ...) -> None: ...

class ThoughtBootstrapRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "group_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    group_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ...) -> None: ...

class ThoughtMaintenanceRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "mode")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    mode: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., mode: _Optional[str] = ...) -> None: ...

class ThoughtAck(_message.Message):
    __slots__ = ("ok", "detail")
    OK_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    detail: str
    def __init__(self, ok: bool = ..., detail: _Optional[str] = ...) -> None: ...

class ThoughtStatsRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class ThoughtStatsResult(_message.Message):
    __slots__ = ("total_thoughts", "active_thoughts", "total_edges", "avg_activation")
    TOTAL_THOUGHTS_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_THOUGHTS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_EDGES_FIELD_NUMBER: _ClassVar[int]
    AVG_ACTIVATION_FIELD_NUMBER: _ClassVar[int]
    total_thoughts: int
    active_thoughts: int
    total_edges: int
    avg_activation: float
    def __init__(self, total_thoughts: _Optional[int] = ..., active_thoughts: _Optional[int] = ..., total_edges: _Optional[int] = ..., avg_activation: _Optional[float] = ...) -> None: ...
