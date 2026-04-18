from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class MaintenanceBatchRequest(_message.Message):
    __slots__ = ("ctx", "maintenance_type", "client_id", "cursor", "batch_size")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MAINTENANCE_TYPE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    BATCH_SIZE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    maintenance_type: str
    client_id: str
    cursor: str
    batch_size: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., maintenance_type: _Optional[str] = ..., client_id: _Optional[str] = ..., cursor: _Optional[str] = ..., batch_size: _Optional[int] = ...) -> None: ...

class MaintenanceBatchResult(_message.Message):
    __slots__ = ("completed", "next_cursor", "processed", "findings", "fixed", "total_estimate")
    COMPLETED_FIELD_NUMBER: _ClassVar[int]
    NEXT_CURSOR_FIELD_NUMBER: _ClassVar[int]
    PROCESSED_FIELD_NUMBER: _ClassVar[int]
    FINDINGS_FIELD_NUMBER: _ClassVar[int]
    FIXED_FIELD_NUMBER: _ClassVar[int]
    TOTAL_ESTIMATE_FIELD_NUMBER: _ClassVar[int]
    completed: bool
    next_cursor: str
    processed: int
    findings: int
    fixed: int
    total_estimate: int
    def __init__(self, completed: bool = ..., next_cursor: _Optional[str] = ..., processed: _Optional[int] = ..., findings: _Optional[int] = ..., fixed: _Optional[int] = ..., total_estimate: _Optional[int] = ...) -> None: ...

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

class RetagResult(_message.Message):
    __slots__ = ("status", "graph_updated", "weaviate_updated")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    GRAPH_UPDATED_FIELD_NUMBER: _ClassVar[int]
    WEAVIATE_UPDATED_FIELD_NUMBER: _ClassVar[int]
    status: str
    graph_updated: int
    weaviate_updated: int
    def __init__(self, status: _Optional[str] = ..., graph_updated: _Optional[int] = ..., weaviate_updated: _Optional[int] = ...) -> None: ...
