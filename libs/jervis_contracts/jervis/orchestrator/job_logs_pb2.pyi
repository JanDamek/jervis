from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JobLogsRequest(_message.Message):
    __slots__ = ("ctx", "task_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ...) -> None: ...

class JobLogEvent(_message.Message):
    __slots__ = ("type", "content", "tool")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    TOOL_FIELD_NUMBER: _ClassVar[int]
    type: str
    content: str
    tool: str
    def __init__(self, type: _Optional[str] = ..., content: _Optional[str] = ..., tool: _Optional[str] = ...) -> None: ...
