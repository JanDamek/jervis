from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class RetrievalRequest(_message.Message):
    __slots__ = ("ctx", "query", "client_id", "project_id", "group_id", "as_of_iso", "min_confidence", "max_results", "expand_graph", "kinds")
    CTX_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    AS_OF_ISO_FIELD_NUMBER: _ClassVar[int]
    MIN_CONFIDENCE_FIELD_NUMBER: _ClassVar[int]
    MAX_RESULTS_FIELD_NUMBER: _ClassVar[int]
    EXPAND_GRAPH_FIELD_NUMBER: _ClassVar[int]
    KINDS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    query: str
    client_id: str
    project_id: str
    group_id: str
    as_of_iso: str
    min_confidence: float
    max_results: int
    expand_graph: bool
    kinds: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., query: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., as_of_iso: _Optional[str] = ..., min_confidence: _Optional[float] = ..., max_results: _Optional[int] = ..., expand_graph: bool = ..., kinds: _Optional[_Iterable[str]] = ...) -> None: ...

class EvidencePack(_message.Message):
    __slots__ = ("items",)
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[EvidenceItem]
    def __init__(self, items: _Optional[_Iterable[_Union[EvidenceItem, _Mapping]]] = ...) -> None: ...

class EvidenceItem(_message.Message):
    __slots__ = ("content", "score", "source_urn", "credibility", "branch_scope", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    SCORE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    CREDIBILITY_FIELD_NUMBER: _ClassVar[int]
    BRANCH_SCOPE_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    content: str
    score: float
    source_urn: str
    credibility: str
    branch_scope: str
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, content: _Optional[str] = ..., score: _Optional[float] = ..., source_urn: _Optional[str] = ..., credibility: _Optional[str] = ..., branch_scope: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class HybridRetrievalRequest(_message.Message):
    __slots__ = ("ctx", "query", "client_id", "project_id", "group_id", "max_results", "min_confidence", "expand_graph", "extract_entities", "use_rrf", "max_graph_hops", "max_seeds", "diversity_factor")
    CTX_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    MAX_RESULTS_FIELD_NUMBER: _ClassVar[int]
    MIN_CONFIDENCE_FIELD_NUMBER: _ClassVar[int]
    EXPAND_GRAPH_FIELD_NUMBER: _ClassVar[int]
    EXTRACT_ENTITIES_FIELD_NUMBER: _ClassVar[int]
    USE_RRF_FIELD_NUMBER: _ClassVar[int]
    MAX_GRAPH_HOPS_FIELD_NUMBER: _ClassVar[int]
    MAX_SEEDS_FIELD_NUMBER: _ClassVar[int]
    DIVERSITY_FACTOR_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    query: str
    client_id: str
    project_id: str
    group_id: str
    max_results: int
    min_confidence: float
    expand_graph: bool
    extract_entities: bool
    use_rrf: bool
    max_graph_hops: int
    max_seeds: int
    diversity_factor: float
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., query: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., max_results: _Optional[int] = ..., min_confidence: _Optional[float] = ..., expand_graph: bool = ..., extract_entities: bool = ..., use_rrf: bool = ..., max_graph_hops: _Optional[int] = ..., max_seeds: _Optional[int] = ..., diversity_factor: _Optional[float] = ...) -> None: ...

class HybridEvidencePack(_message.Message):
    __slots__ = ("items", "total_found", "query_entities", "seed_nodes")
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_FOUND_FIELD_NUMBER: _ClassVar[int]
    QUERY_ENTITIES_FIELD_NUMBER: _ClassVar[int]
    SEED_NODES_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[HybridEvidenceItem]
    total_found: int
    query_entities: _containers.RepeatedScalarFieldContainer[str]
    seed_nodes: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, items: _Optional[_Iterable[_Union[HybridEvidenceItem, _Mapping]]] = ..., total_found: _Optional[int] = ..., query_entities: _Optional[_Iterable[str]] = ..., seed_nodes: _Optional[_Iterable[str]] = ...) -> None: ...

class HybridEvidenceItem(_message.Message):
    __slots__ = ("content", "combined_score", "source_urn", "rag_score", "graph_score", "entity_score", "credibility_boost", "source", "credibility", "branch_scope", "branch_role", "graph_distance", "graph_refs", "matched_entity", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    COMBINED_SCORE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    RAG_SCORE_FIELD_NUMBER: _ClassVar[int]
    GRAPH_SCORE_FIELD_NUMBER: _ClassVar[int]
    ENTITY_SCORE_FIELD_NUMBER: _ClassVar[int]
    CREDIBILITY_BOOST_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    CREDIBILITY_FIELD_NUMBER: _ClassVar[int]
    BRANCH_SCOPE_FIELD_NUMBER: _ClassVar[int]
    BRANCH_ROLE_FIELD_NUMBER: _ClassVar[int]
    GRAPH_DISTANCE_FIELD_NUMBER: _ClassVar[int]
    GRAPH_REFS_FIELD_NUMBER: _ClassVar[int]
    MATCHED_ENTITY_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    content: str
    combined_score: float
    source_urn: str
    rag_score: float
    graph_score: float
    entity_score: float
    credibility_boost: float
    source: str
    credibility: str
    branch_scope: str
    branch_role: str
    graph_distance: int
    graph_refs: _containers.RepeatedScalarFieldContainer[str]
    matched_entity: str
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, content: _Optional[str] = ..., combined_score: _Optional[float] = ..., source_urn: _Optional[str] = ..., rag_score: _Optional[float] = ..., graph_score: _Optional[float] = ..., entity_score: _Optional[float] = ..., credibility_boost: _Optional[float] = ..., source: _Optional[str] = ..., credibility: _Optional[str] = ..., branch_scope: _Optional[str] = ..., branch_role: _Optional[str] = ..., graph_distance: _Optional[int] = ..., graph_refs: _Optional[_Iterable[str]] = ..., matched_entity: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class TraversalRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "group_id", "start_key", "spec")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    START_KEY_FIELD_NUMBER: _ClassVar[int]
    SPEC_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    group_id: str
    start_key: str
    spec: TraversalSpec
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., start_key: _Optional[str] = ..., spec: _Optional[_Union[TraversalSpec, _Mapping]] = ...) -> None: ...

class TraversalSpec(_message.Message):
    __slots__ = ("direction", "min_depth", "max_depth", "edge_collection")
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    MIN_DEPTH_FIELD_NUMBER: _ClassVar[int]
    MAX_DEPTH_FIELD_NUMBER: _ClassVar[int]
    EDGE_COLLECTION_FIELD_NUMBER: _ClassVar[int]
    direction: str
    min_depth: int
    max_depth: int
    edge_collection: str
    def __init__(self, direction: _Optional[str] = ..., min_depth: _Optional[int] = ..., max_depth: _Optional[int] = ..., edge_collection: _Optional[str] = ...) -> None: ...

class JoernAnalyzeResult(_message.Message):
    __slots__ = ("status", "output", "warnings", "exit_code")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    OUTPUT_FIELD_NUMBER: _ClassVar[int]
    WARNINGS_FIELD_NUMBER: _ClassVar[int]
    EXIT_CODE_FIELD_NUMBER: _ClassVar[int]
    status: str
    output: str
    warnings: str
    exit_code: int
    def __init__(self, status: _Optional[str] = ..., output: _Optional[str] = ..., warnings: _Optional[str] = ..., exit_code: _Optional[int] = ...) -> None: ...

class JoernScanRequest(_message.Message):
    __slots__ = ("ctx", "scan_type", "client_id", "project_id", "workspace_path")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SCAN_TYPE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    scan_type: str
    client_id: str
    project_id: str
    workspace_path: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., scan_type: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., workspace_path: _Optional[str] = ...) -> None: ...

class JoernScanResult(_message.Message):
    __slots__ = ("status", "scan_type", "output", "warnings", "exit_code")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SCAN_TYPE_FIELD_NUMBER: _ClassVar[int]
    OUTPUT_FIELD_NUMBER: _ClassVar[int]
    WARNINGS_FIELD_NUMBER: _ClassVar[int]
    EXIT_CODE_FIELD_NUMBER: _ClassVar[int]
    status: str
    scan_type: str
    output: str
    warnings: str
    exit_code: int
    def __init__(self, status: _Optional[str] = ..., scan_type: _Optional[str] = ..., output: _Optional[str] = ..., warnings: _Optional[str] = ..., exit_code: _Optional[int] = ...) -> None: ...

class ListByKindRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "kind", "max_results")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    MAX_RESULTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    kind: str
    max_results: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., kind: _Optional[str] = ..., max_results: _Optional[int] = ...) -> None: ...

class ChunkList(_message.Message):
    __slots__ = ("items",)
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[Chunk]
    def __init__(self, items: _Optional[_Iterable[_Union[Chunk, _Mapping]]] = ...) -> None: ...

class Chunk(_message.Message):
    __slots__ = ("id", "content", "source_urn", "kind", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    ID_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    id: str
    content: str
    source_urn: str
    kind: str
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, id: _Optional[str] = ..., content: _Optional[str] = ..., source_urn: _Optional[str] = ..., kind: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...
