from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SpeakRequest(_message.Message):
    __slots__ = ("ctx", "text", "voice", "speed", "language")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    VOICE_FIELD_NUMBER: _ClassVar[int]
    SPEED_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    text: str
    voice: str
    speed: float
    language: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., text: _Optional[str] = ..., voice: _Optional[str] = ..., speed: _Optional[float] = ..., language: _Optional[str] = ...) -> None: ...

class SpeakResponse(_message.Message):
    __slots__ = ("wav", "duration_ms", "text_length")
    WAV_FIELD_NUMBER: _ClassVar[int]
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    TEXT_LENGTH_FIELD_NUMBER: _ClassVar[int]
    wav: bytes
    duration_ms: int
    text_length: int
    def __init__(self, wav: _Optional[bytes] = ..., duration_ms: _Optional[int] = ..., text_length: _Optional[int] = ...) -> None: ...

class AudioChunk(_message.Message):
    __slots__ = ("data", "is_last")
    DATA_FIELD_NUMBER: _ClassVar[int]
    IS_LAST_FIELD_NUMBER: _ClassVar[int]
    data: bytes
    is_last: bool
    def __init__(self, data: _Optional[bytes] = ..., is_last: bool = ...) -> None: ...
