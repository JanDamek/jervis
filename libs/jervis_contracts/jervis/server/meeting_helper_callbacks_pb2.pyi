from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class HelperPushRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "type", "text", "context", "from_lang", "to_lang", "timestamp")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    FROM_LANG_FIELD_NUMBER: _ClassVar[int]
    TO_LANG_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    type: str
    text: str
    context: str
    from_lang: str
    to_lang: str
    timestamp: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., type: _Optional[str] = ..., text: _Optional[str] = ..., context: _Optional[str] = ..., from_lang: _Optional[str] = ..., to_lang: _Optional[str] = ..., timestamp: _Optional[str] = ...) -> None: ...

class HelperPushAck(_message.Message):
    __slots__ = ("status", "error")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    status: str
    error: str
    def __init__(self, status: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...
