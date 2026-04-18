from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SnapshotRequest(_message.Message):
    __slots__ = ("ctx", "mode", "preset", "custom_prompt")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    PRESET_FIELD_NUMBER: _ClassVar[int]
    CUSTOM_PROMPT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    mode: str
    preset: str
    custom_prompt: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., mode: _Optional[str] = ..., preset: _Optional[str] = ..., custom_prompt: _Optional[str] = ...) -> None: ...

class SnapshotResponse(_message.Message):
    __slots__ = ("description", "ocr_text", "mode", "model", "frame_size_bytes", "timestamp", "preset", "error")
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    OCR_TEXT_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    FRAME_SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    PRESET_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    description: str
    ocr_text: str
    mode: str
    model: str
    frame_size_bytes: int
    timestamp: str
    preset: str
    error: str
    def __init__(self, description: _Optional[str] = ..., ocr_text: _Optional[str] = ..., mode: _Optional[str] = ..., model: _Optional[str] = ..., frame_size_bytes: _Optional[int] = ..., timestamp: _Optional[str] = ..., preset: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class PtzGotoRequest(_message.Message):
    __slots__ = ("ctx", "preset")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PRESET_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    preset: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., preset: _Optional[str] = ...) -> None: ...

class PtzGotoResponse(_message.Message):
    __slots__ = ("status", "preset", "error")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    PRESET_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    status: str
    preset: str
    error: str
    def __init__(self, status: _Optional[str] = ..., preset: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class PtzPresetsRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class PtzPresetsResponse(_message.Message):
    __slots__ = ("presets",)
    PRESETS_FIELD_NUMBER: _ClassVar[int]
    presets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, presets: _Optional[_Iterable[str]] = ...) -> None: ...
