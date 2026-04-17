from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ListClientsRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class ListClientsResponse(_message.Message):
    __slots__ = ("items_json",)
    ITEMS_JSON_FIELD_NUMBER: _ClassVar[int]
    items_json: str
    def __init__(self, items_json: _Optional[str] = ...) -> None: ...

class CreateClientRequest(_message.Message):
    __slots__ = ("ctx", "name", "description")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    name: str
    description: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., name: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class CreateClientResponse(_message.Message):
    __slots__ = ("id", "name")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ...) -> None: ...

class ListProjectsRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class ListProjectsResponse(_message.Message):
    __slots__ = ("items_json",)
    ITEMS_JSON_FIELD_NUMBER: _ClassVar[int]
    items_json: str
    def __init__(self, items_json: _Optional[str] = ...) -> None: ...

class CreateProjectRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "name", "description")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    name: str
    description: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., name: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class CreateProjectResponse(_message.Message):
    __slots__ = ("id", "name", "client_id")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    client_id: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class UpdateProjectRequest(_message.Message):
    __slots__ = ("ctx", "project_id", "description", "git_remote_url", "connection_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    GIT_REMOTE_URL_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    project_id: str
    description: str
    git_remote_url: str
    connection_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., project_id: _Optional[str] = ..., description: _Optional[str] = ..., git_remote_url: _Optional[str] = ..., connection_id: _Optional[str] = ...) -> None: ...

class UpdateProjectResponse(_message.Message):
    __slots__ = ("id", "name", "body_json")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    body_json: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., body_json: _Optional[str] = ...) -> None: ...

class ListConnectionsRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class ListConnectionsResponse(_message.Message):
    __slots__ = ("items_json",)
    ITEMS_JSON_FIELD_NUMBER: _ClassVar[int]
    items_json: str
    def __init__(self, items_json: _Optional[str] = ...) -> None: ...

class CreateConnectionRequest(_message.Message):
    __slots__ = ("ctx", "name", "provider", "protocol", "auth_type", "base_url", "is_cloud", "bearer_token", "username", "password", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    PROVIDER_FIELD_NUMBER: _ClassVar[int]
    PROTOCOL_FIELD_NUMBER: _ClassVar[int]
    AUTH_TYPE_FIELD_NUMBER: _ClassVar[int]
    BASE_URL_FIELD_NUMBER: _ClassVar[int]
    IS_CLOUD_FIELD_NUMBER: _ClassVar[int]
    BEARER_TOKEN_FIELD_NUMBER: _ClassVar[int]
    USERNAME_FIELD_NUMBER: _ClassVar[int]
    PASSWORD_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    name: str
    provider: str
    protocol: str
    auth_type: str
    base_url: str
    is_cloud: bool
    bearer_token: str
    username: str
    password: str
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., name: _Optional[str] = ..., provider: _Optional[str] = ..., protocol: _Optional[str] = ..., auth_type: _Optional[str] = ..., base_url: _Optional[str] = ..., is_cloud: bool = ..., bearer_token: _Optional[str] = ..., username: _Optional[str] = ..., password: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class CreateConnectionResponse(_message.Message):
    __slots__ = ("id", "name", "provider", "state")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    PROVIDER_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    provider: str
    state: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., provider: _Optional[str] = ..., state: _Optional[str] = ...) -> None: ...

class GetStackRecommendationsRequest(_message.Message):
    __slots__ = ("ctx", "requirements")
    CTX_FIELD_NUMBER: _ClassVar[int]
    REQUIREMENTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    requirements: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., requirements: _Optional[str] = ...) -> None: ...

class GetStackRecommendationsResponse(_message.Message):
    __slots__ = ("body_json",)
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    body_json: str
    def __init__(self, body_json: _Optional[str] = ...) -> None: ...
