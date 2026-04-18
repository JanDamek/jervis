from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class TranscribeRequest(_message.Message):
    __slots__ = ("ctx", "audio", "filename", "options_json", "blob_ref")
    CTX_FIELD_NUMBER: _ClassVar[int]
    AUDIO_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_JSON_FIELD_NUMBER: _ClassVar[int]
    BLOB_REF_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    audio: bytes
    filename: str
    options_json: str
    blob_ref: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., audio: _Optional[bytes] = ..., filename: _Optional[str] = ..., options_json: _Optional[str] = ..., blob_ref: _Optional[str] = ...) -> None: ...

class TranscribeEvent(_message.Message):
    __slots__ = ("event", "data_json")
    EVENT_FIELD_NUMBER: _ClassVar[int]
    DATA_JSON_FIELD_NUMBER: _ClassVar[int]
    event: str
    data_json: str
    def __init__(self, event: _Optional[str] = ..., data_json: _Optional[str] = ...) -> None: ...

class HealthRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class HealthResponse(_message.Message):
    __slots__ = ("ok", "status", "model_loaded", "detail")
    OK_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    MODEL_LOADED_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    status: str
    model_loaded: bool
    detail: str
    def __init__(self, ok: bool = ..., status: _Optional[str] = ..., model_loaded: bool = ..., detail: _Optional[str] = ...) -> None: ...

class GpuReleaseRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class GpuReleaseResponse(_message.Message):
    __slots__ = ("released",)
    RELEASED_FIELD_NUMBER: _ClassVar[int]
    released: bool
    def __init__(self, released: bool = ...) -> None: ...
