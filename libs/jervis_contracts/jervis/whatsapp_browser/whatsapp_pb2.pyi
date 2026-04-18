from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SessionRef(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class SessionStatus(_message.Message):
    __slots__ = ("client_id", "state", "last_activity", "novnc_url", "message")
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    LAST_ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    NOVNC_URL_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    client_id: str
    state: str
    last_activity: str
    novnc_url: str
    message: str
    def __init__(self, client_id: _Optional[str] = ..., state: _Optional[str] = ..., last_activity: _Optional[str] = ..., novnc_url: _Optional[str] = ..., message: _Optional[str] = ...) -> None: ...

class InitSessionRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "login_url", "user_agent", "capabilities", "phone_number")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    LOGIN_URL_FIELD_NUMBER: _ClassVar[int]
    USER_AGENT_FIELD_NUMBER: _ClassVar[int]
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    PHONE_NUMBER_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    login_url: str
    user_agent: str
    capabilities: _containers.RepeatedScalarFieldContainer[str]
    phone_number: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., login_url: _Optional[str] = ..., user_agent: _Optional[str] = ..., capabilities: _Optional[_Iterable[str]] = ..., phone_number: _Optional[str] = ...) -> None: ...

class InitSessionResponse(_message.Message):
    __slots__ = ("client_id", "state", "novnc_url", "message")
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    NOVNC_URL_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    client_id: str
    state: str
    novnc_url: str
    message: str
    def __init__(self, client_id: _Optional[str] = ..., state: _Optional[str] = ..., novnc_url: _Optional[str] = ..., message: _Optional[str] = ...) -> None: ...

class TriggerScrapeRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "max_tier", "processing_mode")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    MAX_TIER_FIELD_NUMBER: _ClassVar[int]
    PROCESSING_MODE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    max_tier: str
    processing_mode: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., max_tier: _Optional[str] = ..., processing_mode: _Optional[str] = ...) -> None: ...

class TriggerScrapeResponse(_message.Message):
    __slots__ = ("status",)
    STATUS_FIELD_NUMBER: _ClassVar[int]
    status: str
    def __init__(self, status: _Optional[str] = ...) -> None: ...

class LatestScrapeResponse(_message.Message):
    __slots__ = ("status", "message", "last_scraped_at", "chat_count")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    LAST_SCRAPED_AT_FIELD_NUMBER: _ClassVar[int]
    CHAT_COUNT_FIELD_NUMBER: _ClassVar[int]
    status: str
    message: str
    last_scraped_at: str
    chat_count: int
    def __init__(self, status: _Optional[str] = ..., message: _Optional[str] = ..., last_scraped_at: _Optional[str] = ..., chat_count: _Optional[int] = ...) -> None: ...

class VncTokenResponse(_message.Message):
    __slots__ = ("token", "client_id")
    TOKEN_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    token: str
    client_id: str
    def __init__(self, token: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...
