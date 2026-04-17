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
    __slots__ = ("items", "total")
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[QueueItem]
    total: int
    def __init__(self, items: _Optional[_Iterable[_Union[QueueItem, _Mapping]]] = ..., total: _Optional[int] = ...) -> None: ...

class QueueItem(_message.Message):
    __slots__ = ("id", "source_urn", "client_id", "project_id", "state", "retry_count", "submitted_at_iso", "started_at_iso", "finished_at_iso", "error")
    ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    RETRY_COUNT_FIELD_NUMBER: _ClassVar[int]
    SUBMITTED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    STARTED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    FINISHED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    id: str
    source_urn: str
    client_id: str
    project_id: str
    state: str
    retry_count: int
    submitted_at_iso: str
    started_at_iso: str
    finished_at_iso: str
    error: str
    def __init__(self, id: _Optional[str] = ..., source_urn: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., state: _Optional[str] = ..., retry_count: _Optional[int] = ..., submitted_at_iso: _Optional[str] = ..., started_at_iso: _Optional[str] = ..., finished_at_iso: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...
