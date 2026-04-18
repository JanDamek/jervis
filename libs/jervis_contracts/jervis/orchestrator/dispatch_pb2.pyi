from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class DispatchRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "payload_json")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_JSON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    payload_json: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., payload_json: _Optional[str] = ...) -> None: ...

class DispatchAck(_message.Message):
    __slots__ = ("status", "thread_id", "detail")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    THREAD_ID_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    status: str
    thread_id: str
    detail: str
    def __init__(self, status: _Optional[str] = ..., thread_id: _Optional[str] = ..., detail: _Optional[str] = ...) -> None: ...
