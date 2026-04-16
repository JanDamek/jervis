from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CreateRepositoryRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "connection_id", "name", "description", "is_private")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    IS_PRIVATE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    connection_id: str
    name: str
    description: str
    is_private: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., connection_id: _Optional[str] = ..., name: _Optional[str] = ..., description: _Optional[str] = ..., is_private: bool = ...) -> None: ...

class CreateRepositoryResponse(_message.Message):
    __slots__ = ("body_json",)
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    body_json: str
    def __init__(self, body_json: _Optional[str] = ...) -> None: ...

class InitWorkspaceRequest(_message.Message):
    __slots__ = ("ctx", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., project_id: _Optional[str] = ...) -> None: ...

class InitWorkspaceResponse(_message.Message):
    __slots__ = ("ok", "project_id", "status", "workspace_path", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    project_id: str
    status: str
    workspace_path: str
    error: str
    def __init__(self, ok: bool = ..., project_id: _Optional[str] = ..., status: _Optional[str] = ..., workspace_path: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class WorkspaceStatusRequest(_message.Message):
    __slots__ = ("ctx", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., project_id: _Optional[str] = ...) -> None: ...

class WorkspaceStatusResponse(_message.Message):
    __slots__ = ("project_id", "status", "workspace_path", "error")
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    project_id: str
    status: str
    workspace_path: str
    error: str
    def __init__(self, project_id: _Optional[str] = ..., status: _Optional[str] = ..., workspace_path: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...
