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

class HealthRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class HealthResponse(_message.Message):
    __slots__ = ("status", "service")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SERVICE_FIELD_NUMBER: _ClassVar[int]
    status: str
    service: str
    def __init__(self, status: _Optional[str] = ..., service: _Optional[str] = ...) -> None: ...

class SessionStatus(_message.Message):
    __slots__ = ("client_id", "state", "has_token", "last_activity", "last_token_extract", "novnc_url", "mfa_type", "mfa_message", "mfa_number")
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    HAS_TOKEN_FIELD_NUMBER: _ClassVar[int]
    LAST_ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    LAST_TOKEN_EXTRACT_FIELD_NUMBER: _ClassVar[int]
    NOVNC_URL_FIELD_NUMBER: _ClassVar[int]
    MFA_TYPE_FIELD_NUMBER: _ClassVar[int]
    MFA_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    MFA_NUMBER_FIELD_NUMBER: _ClassVar[int]
    client_id: str
    state: str
    has_token: bool
    last_activity: str
    last_token_extract: str
    novnc_url: str
    mfa_type: str
    mfa_message: str
    mfa_number: str
    def __init__(self, client_id: _Optional[str] = ..., state: _Optional[str] = ..., has_token: bool = ..., last_activity: _Optional[str] = ..., last_token_extract: _Optional[str] = ..., novnc_url: _Optional[str] = ..., mfa_type: _Optional[str] = ..., mfa_message: _Optional[str] = ..., mfa_number: _Optional[str] = ...) -> None: ...

class InitSessionRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "login_url", "user_agent", "capabilities", "username", "password")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    LOGIN_URL_FIELD_NUMBER: _ClassVar[int]
    USER_AGENT_FIELD_NUMBER: _ClassVar[int]
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    USERNAME_FIELD_NUMBER: _ClassVar[int]
    PASSWORD_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    login_url: str
    user_agent: str
    capabilities: _containers.RepeatedScalarFieldContainer[str]
    username: str
    password: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., login_url: _Optional[str] = ..., user_agent: _Optional[str] = ..., capabilities: _Optional[_Iterable[str]] = ..., username: _Optional[str] = ..., password: _Optional[str] = ...) -> None: ...

class InitSessionResponse(_message.Message):
    __slots__ = ("client_id", "state", "novnc_url", "message", "error")
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    NOVNC_URL_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    client_id: str
    state: str
    novnc_url: str
    message: str
    error: str
    def __init__(self, client_id: _Optional[str] = ..., state: _Optional[str] = ..., novnc_url: _Optional[str] = ..., message: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class SubmitMfaRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "code")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    CODE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    code: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., code: _Optional[str] = ...) -> None: ...

class VncTokenResponse(_message.Message):
    __slots__ = ("token", "client_id")
    TOKEN_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    token: str
    client_id: str
    def __init__(self, token: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class InstructionRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "instruction")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    INSTRUCTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    instruction: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., instruction: _Optional[str] = ...) -> None: ...

class InstructionResponse(_message.Message):
    __slots__ = ("status", "client_id", "error")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    status: str
    client_id: str
    error: str
    def __init__(self, status: _Optional[str] = ..., client_id: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...
