from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ListDiscoveredRequest(_message.Message):
    __slots__ = ("ctx", "connection_id", "resource_type")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_TYPE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    connection_id: str
    resource_type: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., connection_id: _Optional[str] = ..., resource_type: _Optional[str] = ...) -> None: ...

class ListDiscoveredResponse(_message.Message):
    __slots__ = ("resources",)
    RESOURCES_FIELD_NUMBER: _ClassVar[int]
    resources: _containers.RepeatedCompositeFieldContainer[DiscoveredResource]
    def __init__(self, resources: _Optional[_Iterable[_Union[DiscoveredResource, _Mapping]]] = ...) -> None: ...

class DiscoveredResource(_message.Message):
    __slots__ = ("external_id", "resource_type", "display_name", "description", "team_name", "active", "discovered_at_epoch", "last_seen_at_epoch")
    EXTERNAL_ID_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_TYPE_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    TEAM_NAME_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_FIELD_NUMBER: _ClassVar[int]
    DISCOVERED_AT_EPOCH_FIELD_NUMBER: _ClassVar[int]
    LAST_SEEN_AT_EPOCH_FIELD_NUMBER: _ClassVar[int]
    external_id: str
    resource_type: str
    display_name: str
    description: str
    team_name: str
    active: bool
    discovered_at_epoch: int
    last_seen_at_epoch: int
    def __init__(self, external_id: _Optional[str] = ..., resource_type: _Optional[str] = ..., display_name: _Optional[str] = ..., description: _Optional[str] = ..., team_name: _Optional[str] = ..., active: bool = ..., discovered_at_epoch: _Optional[int] = ..., last_seen_at_epoch: _Optional[int] = ...) -> None: ...

class LastActivityRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class LastActivityResponse(_message.Message):
    __slots__ = ("last_active_seconds",)
    LAST_ACTIVE_SECONDS_FIELD_NUMBER: _ClassVar[int]
    last_active_seconds: int
    def __init__(self, last_active_seconds: _Optional[int] = ...) -> None: ...
