from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CorrectionRequest(_message.Message):
    __slots__ = ("ctx", "body_json")
    CTX_FIELD_NUMBER: _ClassVar[int]
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    body_json: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., body_json: _Optional[str] = ...) -> None: ...

class CorrectionResponse(_message.Message):
    __slots__ = ("status", "body_json")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    status: int
    body_json: str
    def __init__(self, status: _Optional[int] = ..., body_json: _Optional[str] = ...) -> None: ...
