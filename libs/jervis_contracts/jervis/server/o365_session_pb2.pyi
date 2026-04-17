from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SessionEventRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "connection_id", "state", "mfa_type", "mfa_message", "mfa_number", "vnc_url")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    MFA_TYPE_FIELD_NUMBER: _ClassVar[int]
    MFA_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    MFA_NUMBER_FIELD_NUMBER: _ClassVar[int]
    VNC_URL_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    connection_id: str
    state: str
    mfa_type: str
    mfa_message: str
    mfa_number: str
    vnc_url: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., connection_id: _Optional[str] = ..., state: _Optional[str] = ..., mfa_type: _Optional[str] = ..., mfa_message: _Optional[str] = ..., mfa_number: _Optional[str] = ..., vnc_url: _Optional[str] = ...) -> None: ...

class SessionEventResponse(_message.Message):
    __slots__ = ("status",)
    STATUS_FIELD_NUMBER: _ClassVar[int]
    status: str
    def __init__(self, status: _Optional[str] = ...) -> None: ...

class CapabilitiesDiscoveredRequest(_message.Message):
    __slots__ = ("ctx", "connection_id", "available_capabilities")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    AVAILABLE_CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    connection_id: str
    available_capabilities: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., connection_id: _Optional[str] = ..., available_capabilities: _Optional[_Iterable[str]] = ...) -> None: ...

class CapabilitiesDiscoveredResponse(_message.Message):
    __slots__ = ("ok", "capabilities", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    capabilities: int
    error: str
    def __init__(self, ok: bool = ..., capabilities: _Optional[int] = ..., error: _Optional[str] = ...) -> None: ...

class NotifyRequest(_message.Message):
    __slots__ = ("ctx", "connection_id", "kind", "message", "chat_id", "chat_name", "sender", "preview", "screenshot", "mfa_code", "meeting_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    CHAT_ID_FIELD_NUMBER: _ClassVar[int]
    CHAT_NAME_FIELD_NUMBER: _ClassVar[int]
    SENDER_FIELD_NUMBER: _ClassVar[int]
    PREVIEW_FIELD_NUMBER: _ClassVar[int]
    SCREENSHOT_FIELD_NUMBER: _ClassVar[int]
    MFA_CODE_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    connection_id: str
    kind: str
    message: str
    chat_id: str
    chat_name: str
    sender: str
    preview: str
    screenshot: str
    mfa_code: str
    meeting_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., connection_id: _Optional[str] = ..., kind: _Optional[str] = ..., message: _Optional[str] = ..., chat_id: _Optional[str] = ..., chat_name: _Optional[str] = ..., sender: _Optional[str] = ..., preview: _Optional[str] = ..., screenshot: _Optional[str] = ..., mfa_code: _Optional[str] = ..., meeting_id: _Optional[str] = ...) -> None: ...

class NotifyResponse(_message.Message):
    __slots__ = ("status", "kind", "priority")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    status: str
    kind: str
    priority: int
    def __init__(self, status: _Optional[str] = ..., kind: _Optional[str] = ..., priority: _Optional[int] = ...) -> None: ...
