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

class SnapshotRequest(_message.Message):
    __slots__ = ("ctx", "request_json")
    CTX_FIELD_NUMBER: _ClassVar[int]
    REQUEST_JSON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    request_json: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., request_json: _Optional[str] = ...) -> None: ...

class PtzRequest(_message.Message):
    __slots__ = ("ctx", "request_json")
    CTX_FIELD_NUMBER: _ClassVar[int]
    REQUEST_JSON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    request_json: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., request_json: _Optional[str] = ...) -> None: ...

class RawJsonResponse(_message.Message):
    __slots__ = ("body_json", "status")
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    body_json: str
    status: int
    def __init__(self, body_json: _Optional[str] = ..., status: _Optional[int] = ...) -> None: ...
