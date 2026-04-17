from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class MaintenanceBatchRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "group_id", "operations", "limit", "dry_run")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    OPERATIONS_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    DRY_RUN_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    group_id: str
    operations: _containers.RepeatedScalarFieldContainer[str]
    limit: int
    dry_run: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., operations: _Optional[_Iterable[str]] = ..., limit: _Optional[int] = ..., dry_run: bool = ...) -> None: ...

class RetagProjectRequest(_message.Message):
    __slots__ = ("ctx", "source_project_id", "target_project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SOURCE_PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    TARGET_PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    source_project_id: str
    target_project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., source_project_id: _Optional[str] = ..., target_project_id: _Optional[str] = ...) -> None: ...

class RetagGroupRequest(_message.Message):
    __slots__ = ("ctx", "project_id", "new_group_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    NEW_GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    project_id: str
    new_group_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., project_id: _Optional[str] = ..., new_group_id: _Optional[str] = ...) -> None: ...

class MaintenanceResult(_message.Message):
    __slots__ = ("status", "scanned", "modified", "deleted", "detail")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SCANNED_FIELD_NUMBER: _ClassVar[int]
    MODIFIED_FIELD_NUMBER: _ClassVar[int]
    DELETED_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    status: str
    scanned: int
    modified: int
    deleted: int
    detail: str
    def __init__(self, status: _Optional[str] = ..., scanned: _Optional[int] = ..., modified: _Optional[int] = ..., deleted: _Optional[int] = ..., detail: _Optional[str] = ...) -> None: ...
