from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class QueueListRequest(_message.Message):
    __slots__ = ("ctx", "limit")
    CTX_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    limit: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., limit: _Optional[int] = ...) -> None: ...

class QueueList(_message.Message):
    __slots__ = ("items", "stats")
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    STATS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[QueueItem]
    stats: QueueStats
    def __init__(self, items: _Optional[_Iterable[_Union[QueueItem, _Mapping]]] = ..., stats: _Optional[_Union[QueueStats, _Mapping]] = ...) -> None: ...

class QueueItem(_message.Message):
    __slots__ = ("task_id", "source_urn", "client_id", "project_id", "kind", "created_at", "status", "attempts", "priority", "error", "last_attempt_at", "worker_id", "progress_current", "progress_total")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    ATTEMPTS_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    LAST_ATTEMPT_AT_FIELD_NUMBER: _ClassVar[int]
    WORKER_ID_FIELD_NUMBER: _ClassVar[int]
    PROGRESS_CURRENT_FIELD_NUMBER: _ClassVar[int]
    PROGRESS_TOTAL_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    source_urn: str
    client_id: str
    project_id: str
    kind: str
    created_at: str
    status: str
    attempts: int
    priority: int
    error: str
    last_attempt_at: str
    worker_id: str
    progress_current: int
    progress_total: int
    def __init__(self, task_id: _Optional[str] = ..., source_urn: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., kind: _Optional[str] = ..., created_at: _Optional[str] = ..., status: _Optional[str] = ..., attempts: _Optional[int] = ..., priority: _Optional[int] = ..., error: _Optional[str] = ..., last_attempt_at: _Optional[str] = ..., worker_id: _Optional[str] = ..., progress_current: _Optional[int] = ..., progress_total: _Optional[int] = ...) -> None: ...

class QueueStats(_message.Message):
    __slots__ = ("total", "pending", "in_progress", "failed")
    TOTAL_FIELD_NUMBER: _ClassVar[int]
    PENDING_FIELD_NUMBER: _ClassVar[int]
    IN_PROGRESS_FIELD_NUMBER: _ClassVar[int]
    FAILED_FIELD_NUMBER: _ClassVar[int]
    total: int
    pending: int
    in_progress: int
    failed: int
    def __init__(self, total: _Optional[int] = ..., pending: _Optional[int] = ..., in_progress: _Optional[int] = ..., failed: _Optional[int] = ...) -> None: ...
