from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class WhatsappSessionEventRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "connection_id", "state", "vnc_url")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    VNC_URL_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    connection_id: str
    state: str
    vnc_url: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., connection_id: _Optional[str] = ..., state: _Optional[str] = ..., vnc_url: _Optional[str] = ...) -> None: ...

class WhatsappSessionEventResponse(_message.Message):
    __slots__ = ("ok",)
    OK_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    def __init__(self, ok: bool = ...) -> None: ...

class WhatsappCapabilitiesRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "connection_id", "available_capabilities", "chat_count")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    AVAILABLE_CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    CHAT_COUNT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    connection_id: str
    available_capabilities: _containers.RepeatedScalarFieldContainer[str]
    chat_count: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., connection_id: _Optional[str] = ..., available_capabilities: _Optional[_Iterable[str]] = ..., chat_count: _Optional[int] = ...) -> None: ...

class WhatsappCapabilitiesResponse(_message.Message):
    __slots__ = ("ok",)
    OK_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    def __init__(self, ok: bool = ...) -> None: ...
