from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ChatRequest(_message.Message):
    __slots__ = ("ctx", "session_id", "payload_json")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_JSON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    payload_json: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ..., payload_json: _Optional[str] = ...) -> None: ...

class ChatEvent(_message.Message):
    __slots__ = ("type", "content", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    TYPE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    type: str
    content: str
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, type: _Optional[str] = ..., content: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class ApproveActionRequest(_message.Message):
    __slots__ = ("ctx", "session_id", "approved", "always", "action")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    APPROVED_FIELD_NUMBER: _ClassVar[int]
    ALWAYS_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    approved: bool
    always: bool
    action: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ..., approved: bool = ..., always: bool = ..., action: _Optional[str] = ...) -> None: ...

class ApproveActionAck(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class StopChatRequest(_message.Message):
    __slots__ = ("ctx", "session_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ...) -> None: ...

class StopChatAck(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...
