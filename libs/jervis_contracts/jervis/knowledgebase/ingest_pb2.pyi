from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class IngestRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "group_id", "source_urn", "kind", "content", "metadata", "observed_at_iso", "max_tier", "credibility", "branch_scope", "branch_role")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    OBSERVED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    MAX_TIER_FIELD_NUMBER: _ClassVar[int]
    CREDIBILITY_FIELD_NUMBER: _ClassVar[int]
    BRANCH_SCOPE_FIELD_NUMBER: _ClassVar[int]
    BRANCH_ROLE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    group_id: str
    source_urn: str
    kind: str
    content: str
    metadata: _containers.ScalarMap[str, str]
    observed_at_iso: str
    max_tier: str
    credibility: str
    branch_scope: str
    branch_role: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., source_urn: _Optional[str] = ..., kind: _Optional[str] = ..., content: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ..., observed_at_iso: _Optional[str] = ..., max_tier: _Optional[str] = ..., credibility: _Optional[str] = ..., branch_scope: _Optional[str] = ..., branch_role: _Optional[str] = ...) -> None: ...

class IngestResult(_message.Message):
    __slots__ = ("status", "chunks_count", "nodes_created", "edges_created", "chunk_ids", "entity_keys")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_COUNT_FIELD_NUMBER: _ClassVar[int]
    NODES_CREATED_FIELD_NUMBER: _ClassVar[int]
    EDGES_CREATED_FIELD_NUMBER: _ClassVar[int]
    CHUNK_IDS_FIELD_NUMBER: _ClassVar[int]
    ENTITY_KEYS_FIELD_NUMBER: _ClassVar[int]
    status: str
    chunks_count: int
    nodes_created: int
    edges_created: int
    chunk_ids: _containers.RepeatedScalarFieldContainer[str]
    entity_keys: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, status: _Optional[str] = ..., chunks_count: _Optional[int] = ..., nodes_created: _Optional[int] = ..., edges_created: _Optional[int] = ..., chunk_ids: _Optional[_Iterable[str]] = ..., entity_keys: _Optional[_Iterable[str]] = ...) -> None: ...

class IngestQueueAck(_message.Message):
    __slots__ = ("ok", "queue_id")
    OK_FIELD_NUMBER: _ClassVar[int]
    QUEUE_ID_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    queue_id: str
    def __init__(self, ok: bool = ..., queue_id: _Optional[str] = ...) -> None: ...

class IngestFileRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "group_id", "source_urn", "filename", "content_type", "data", "blob_ref", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    BLOB_REF_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    group_id: str
    source_urn: str
    filename: str
    content_type: str
    data: bytes
    blob_ref: str
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., source_urn: _Optional[str] = ..., filename: _Optional[str] = ..., content_type: _Optional[str] = ..., data: _Optional[bytes] = ..., blob_ref: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class FullIngestRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "group_id", "source_urn", "source_type", "subject", "content", "metadata", "observed_at_iso", "max_tier", "credibility", "branch_scope", "branch_role", "attachments")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    SOURCE_TYPE_FIELD_NUMBER: _ClassVar[int]
    SUBJECT_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    OBSERVED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    MAX_TIER_FIELD_NUMBER: _ClassVar[int]
    CREDIBILITY_FIELD_NUMBER: _ClassVar[int]
    BRANCH_SCOPE_FIELD_NUMBER: _ClassVar[int]
    BRANCH_ROLE_FIELD_NUMBER: _ClassVar[int]
    ATTACHMENTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    group_id: str
    source_urn: str
    source_type: str
    subject: str
    content: str
    metadata: _containers.ScalarMap[str, str]
    observed_at_iso: str
    max_tier: str
    credibility: str
    branch_scope: str
    branch_role: str
    attachments: _containers.RepeatedCompositeFieldContainer[FullIngestAttachment]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., source_urn: _Optional[str] = ..., source_type: _Optional[str] = ..., subject: _Optional[str] = ..., content: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ..., observed_at_iso: _Optional[str] = ..., max_tier: _Optional[str] = ..., credibility: _Optional[str] = ..., branch_scope: _Optional[str] = ..., branch_role: _Optional[str] = ..., attachments: _Optional[_Iterable[_Union[FullIngestAttachment, _Mapping]]] = ...) -> None: ...

class FullIngestAttachment(_message.Message):
    __slots__ = ("filename", "content_type", "data", "blob_ref")
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    BLOB_REF_FIELD_NUMBER: _ClassVar[int]
    filename: str
    content_type: str
    data: bytes
    blob_ref: str
    def __init__(self, filename: _Optional[str] = ..., content_type: _Optional[str] = ..., data: _Optional[bytes] = ..., blob_ref: _Optional[str] = ...) -> None: ...

class FullIngestResult(_message.Message):
    __slots__ = ("status", "chunks_count", "nodes_created", "edges_created", "attachments_processed", "attachments_failed", "summary", "entities", "has_actionable_content", "suggested_actions", "has_future_deadline", "suggested_deadline", "is_assigned_to_me", "urgency", "error")
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
    ERROR_FIELD_NUMBER: _ClassVar[int]
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
    error: str
    def __init__(self, status: _Optional[str] = ..., chunks_count: _Optional[int] = ..., nodes_created: _Optional[int] = ..., edges_created: _Optional[int] = ..., attachments_processed: _Optional[int] = ..., attachments_failed: _Optional[int] = ..., summary: _Optional[str] = ..., entities: _Optional[_Iterable[str]] = ..., has_actionable_content: bool = ..., suggested_actions: _Optional[_Iterable[str]] = ..., has_future_deadline: bool = ..., suggested_deadline: _Optional[str] = ..., is_assigned_to_me: bool = ..., urgency: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class AsyncFullIngestRequest(_message.Message):
    __slots__ = ("ctx", "request", "task_id", "client_id", "priority", "max_tier")
    CTX_FIELD_NUMBER: _ClassVar[int]
    REQUEST_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    MAX_TIER_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    request: FullIngestRequest
    task_id: str
    client_id: str
    priority: int
    max_tier: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., request: _Optional[_Union[FullIngestRequest, _Mapping]] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ..., priority: _Optional[int] = ..., max_tier: _Optional[str] = ...) -> None: ...

class AsyncIngestAck(_message.Message):
    __slots__ = ("accepted", "detail")
    ACCEPTED_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    accepted: bool
    detail: str
    def __init__(self, accepted: bool = ..., detail: _Optional[str] = ...) -> None: ...

class GitStructureIngestRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "repository_identifier", "branch", "default_branch", "branches", "files", "classes", "file_contents", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    REPOSITORY_IDENTIFIER_FIELD_NUMBER: _ClassVar[int]
    BRANCH_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_BRANCH_FIELD_NUMBER: _ClassVar[int]
    BRANCHES_FIELD_NUMBER: _ClassVar[int]
    FILES_FIELD_NUMBER: _ClassVar[int]
    CLASSES_FIELD_NUMBER: _ClassVar[int]
    FILE_CONTENTS_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    repository_identifier: str
    branch: str
    default_branch: str
    branches: _containers.RepeatedCompositeFieldContainer[GitBranchInfo]
    files: _containers.RepeatedCompositeFieldContainer[GitFileInfo]
    classes: _containers.RepeatedCompositeFieldContainer[GitClassInfo]
    file_contents: _containers.RepeatedCompositeFieldContainer[GitFileContent]
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., repository_identifier: _Optional[str] = ..., branch: _Optional[str] = ..., default_branch: _Optional[str] = ..., branches: _Optional[_Iterable[_Union[GitBranchInfo, _Mapping]]] = ..., files: _Optional[_Iterable[_Union[GitFileInfo, _Mapping]]] = ..., classes: _Optional[_Iterable[_Union[GitClassInfo, _Mapping]]] = ..., file_contents: _Optional[_Iterable[_Union[GitFileContent, _Mapping]]] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class GitFileInfo(_message.Message):
    __slots__ = ("path", "extension", "language", "size_bytes")
    PATH_FIELD_NUMBER: _ClassVar[int]
    EXTENSION_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    path: str
    extension: str
    language: str
    size_bytes: int
    def __init__(self, path: _Optional[str] = ..., extension: _Optional[str] = ..., language: _Optional[str] = ..., size_bytes: _Optional[int] = ...) -> None: ...

class GitBranchInfo(_message.Message):
    __slots__ = ("name", "is_default", "status", "last_commit_hash")
    NAME_FIELD_NUMBER: _ClassVar[int]
    IS_DEFAULT_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    LAST_COMMIT_HASH_FIELD_NUMBER: _ClassVar[int]
    name: str
    is_default: bool
    status: str
    last_commit_hash: str
    def __init__(self, name: _Optional[str] = ..., is_default: bool = ..., status: _Optional[str] = ..., last_commit_hash: _Optional[str] = ...) -> None: ...

class GitClassInfo(_message.Message):
    __slots__ = ("name", "qualified_name", "file_path", "visibility", "is_interface", "methods")
    NAME_FIELD_NUMBER: _ClassVar[int]
    QUALIFIED_NAME_FIELD_NUMBER: _ClassVar[int]
    FILE_PATH_FIELD_NUMBER: _ClassVar[int]
    VISIBILITY_FIELD_NUMBER: _ClassVar[int]
    IS_INTERFACE_FIELD_NUMBER: _ClassVar[int]
    METHODS_FIELD_NUMBER: _ClassVar[int]
    name: str
    qualified_name: str
    file_path: str
    visibility: str
    is_interface: bool
    methods: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, name: _Optional[str] = ..., qualified_name: _Optional[str] = ..., file_path: _Optional[str] = ..., visibility: _Optional[str] = ..., is_interface: bool = ..., methods: _Optional[_Iterable[str]] = ...) -> None: ...

class GitFileContent(_message.Message):
    __slots__ = ("path", "content")
    PATH_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    path: str
    content: str
    def __init__(self, path: _Optional[str] = ..., content: _Optional[str] = ...) -> None: ...

class GitStructureIngestResult(_message.Message):
    __slots__ = ("status", "nodes_created", "edges_created", "nodes_updated", "repository_key", "branch_key", "files_indexed", "classes_indexed", "methods_indexed")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    NODES_CREATED_FIELD_NUMBER: _ClassVar[int]
    EDGES_CREATED_FIELD_NUMBER: _ClassVar[int]
    NODES_UPDATED_FIELD_NUMBER: _ClassVar[int]
    REPOSITORY_KEY_FIELD_NUMBER: _ClassVar[int]
    BRANCH_KEY_FIELD_NUMBER: _ClassVar[int]
    FILES_INDEXED_FIELD_NUMBER: _ClassVar[int]
    CLASSES_INDEXED_FIELD_NUMBER: _ClassVar[int]
    METHODS_INDEXED_FIELD_NUMBER: _ClassVar[int]
    status: str
    nodes_created: int
    edges_created: int
    nodes_updated: int
    repository_key: str
    branch_key: str
    files_indexed: int
    classes_indexed: int
    methods_indexed: int
    def __init__(self, status: _Optional[str] = ..., nodes_created: _Optional[int] = ..., edges_created: _Optional[int] = ..., nodes_updated: _Optional[int] = ..., repository_key: _Optional[str] = ..., branch_key: _Optional[str] = ..., files_indexed: _Optional[int] = ..., classes_indexed: _Optional[int] = ..., methods_indexed: _Optional[int] = ...) -> None: ...

class GitCommitIngestRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "repository_identifier", "branch", "commits", "diff_content")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    REPOSITORY_IDENTIFIER_FIELD_NUMBER: _ClassVar[int]
    BRANCH_FIELD_NUMBER: _ClassVar[int]
    COMMITS_FIELD_NUMBER: _ClassVar[int]
    DIFF_CONTENT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    repository_identifier: str
    branch: str
    commits: _containers.RepeatedCompositeFieldContainer[GitCommitInfo]
    diff_content: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., repository_identifier: _Optional[str] = ..., branch: _Optional[str] = ..., commits: _Optional[_Iterable[_Union[GitCommitInfo, _Mapping]]] = ..., diff_content: _Optional[str] = ...) -> None: ...

class GitCommitInfo(_message.Message):
    __slots__ = ("hash", "message", "author", "date", "branch", "parent_hash", "files_modified", "files_created", "files_deleted")
    HASH_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    AUTHOR_FIELD_NUMBER: _ClassVar[int]
    DATE_FIELD_NUMBER: _ClassVar[int]
    BRANCH_FIELD_NUMBER: _ClassVar[int]
    PARENT_HASH_FIELD_NUMBER: _ClassVar[int]
    FILES_MODIFIED_FIELD_NUMBER: _ClassVar[int]
    FILES_CREATED_FIELD_NUMBER: _ClassVar[int]
    FILES_DELETED_FIELD_NUMBER: _ClassVar[int]
    hash: str
    message: str
    author: str
    date: str
    branch: str
    parent_hash: str
    files_modified: _containers.RepeatedScalarFieldContainer[str]
    files_created: _containers.RepeatedScalarFieldContainer[str]
    files_deleted: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, hash: _Optional[str] = ..., message: _Optional[str] = ..., author: _Optional[str] = ..., date: _Optional[str] = ..., branch: _Optional[str] = ..., parent_hash: _Optional[str] = ..., files_modified: _Optional[_Iterable[str]] = ..., files_created: _Optional[_Iterable[str]] = ..., files_deleted: _Optional[_Iterable[str]] = ...) -> None: ...

class GitCommitIngestResult(_message.Message):
    __slots__ = ("status", "commits_ingested", "nodes_created", "edges_created", "rag_chunks")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    COMMITS_INGESTED_FIELD_NUMBER: _ClassVar[int]
    NODES_CREATED_FIELD_NUMBER: _ClassVar[int]
    EDGES_CREATED_FIELD_NUMBER: _ClassVar[int]
    RAG_CHUNKS_FIELD_NUMBER: _ClassVar[int]
    status: str
    commits_ingested: int
    nodes_created: int
    edges_created: int
    rag_chunks: int
    def __init__(self, status: _Optional[str] = ..., commits_ingested: _Optional[int] = ..., nodes_created: _Optional[int] = ..., edges_created: _Optional[int] = ..., rag_chunks: _Optional[int] = ...) -> None: ...

class CpgIngestRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "branch", "workspace_path")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    BRANCH_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    branch: str
    workspace_path: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., branch: _Optional[str] = ..., workspace_path: _Optional[str] = ...) -> None: ...

class CpgIngestResult(_message.Message):
    __slots__ = ("status", "methods_enriched", "extends_edges", "calls_edges", "uses_type_edges")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    METHODS_ENRICHED_FIELD_NUMBER: _ClassVar[int]
    EXTENDS_EDGES_FIELD_NUMBER: _ClassVar[int]
    CALLS_EDGES_FIELD_NUMBER: _ClassVar[int]
    USES_TYPE_EDGES_FIELD_NUMBER: _ClassVar[int]
    status: str
    methods_enriched: int
    extends_edges: int
    calls_edges: int
    uses_type_edges: int
    def __init__(self, status: _Optional[str] = ..., methods_enriched: _Optional[int] = ..., extends_edges: _Optional[int] = ..., calls_edges: _Optional[int] = ..., uses_type_edges: _Optional[int] = ...) -> None: ...

class CrawlRequest(_message.Message):
    __slots__ = ("ctx", "url", "max_depth", "allow_external_domains", "client_id", "project_id", "group_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    URL_FIELD_NUMBER: _ClassVar[int]
    MAX_DEPTH_FIELD_NUMBER: _ClassVar[int]
    ALLOW_EXTERNAL_DOMAINS_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    url: str
    max_depth: int
    allow_external_domains: bool
    client_id: str
    project_id: str
    group_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., url: _Optional[str] = ..., max_depth: _Optional[int] = ..., allow_external_domains: bool = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ...) -> None: ...

class PurgeRequest(_message.Message):
    __slots__ = ("ctx", "source_urn", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    source_urn: str
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., source_urn: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class PurgeResult(_message.Message):
    __slots__ = ("status", "chunks_deleted", "nodes_cleaned", "edges_cleaned", "nodes_deleted", "edges_deleted")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_DELETED_FIELD_NUMBER: _ClassVar[int]
    NODES_CLEANED_FIELD_NUMBER: _ClassVar[int]
    EDGES_CLEANED_FIELD_NUMBER: _ClassVar[int]
    NODES_DELETED_FIELD_NUMBER: _ClassVar[int]
    EDGES_DELETED_FIELD_NUMBER: _ClassVar[int]
    status: str
    chunks_deleted: int
    nodes_cleaned: int
    edges_cleaned: int
    nodes_deleted: int
    edges_deleted: int
    def __init__(self, status: _Optional[str] = ..., chunks_deleted: _Optional[int] = ..., nodes_cleaned: _Optional[int] = ..., edges_cleaned: _Optional[int] = ..., nodes_deleted: _Optional[int] = ..., edges_deleted: _Optional[int] = ...) -> None: ...
