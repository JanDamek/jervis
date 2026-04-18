from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class VisualResultRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "type", "description", "ocr_text", "preset_name", "timestamp_iso", "model")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    OCR_TEXT_FIELD_NUMBER: _ClassVar[int]
    PRESET_NAME_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_ISO_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    type: str
    description: str
    ocr_text: str
    preset_name: str
    timestamp_iso: str
    model: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., type: _Optional[str] = ..., description: _Optional[str] = ..., ocr_text: _Optional[str] = ..., preset_name: _Optional[str] = ..., timestamp_iso: _Optional[str] = ..., model: _Optional[str] = ...) -> None: ...

class VisualResultResponse(_message.Message):
    __slots__ = ("ok",)
    OK_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    def __init__(self, ok: bool = ...) -> None: ...

class ProxySnapshotRequest(_message.Message):
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

class ProxySnapshotResponse(_message.Message):
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

class ProxyPtzRequest(_message.Message):
    __slots__ = ("ctx", "preset")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PRESET_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    preset: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., preset: _Optional[str] = ...) -> None: ...

class ProxyPtzResponse(_message.Message):
    __slots__ = ("status", "preset", "error")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    PRESET_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    status: str
    preset: str
    error: str
    def __init__(self, status: _Optional[str] = ..., preset: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...
