from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GpuIdleRequest(_message.Message):
    __slots__ = ("ctx", "idle_seconds")
    CTX_FIELD_NUMBER: _ClassVar[int]
    IDLE_SECONDS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    idle_seconds: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., idle_seconds: _Optional[int] = ...) -> None: ...

class GpuIdleResponse(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...
