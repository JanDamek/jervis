from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ApproveReloginRequest(_message.Message):
    __slots__ = ("ctx", "connection_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    connection_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., connection_id: _Optional[str] = ...) -> None: ...

class ApproveReloginResponse(_message.Message):
    __slots__ = ("connection_id", "state", "pod_status", "error")
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    POD_STATUS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    connection_id: str
    state: str
    pod_status: int
    error: str
    def __init__(self, connection_id: _Optional[str] = ..., state: _Optional[str] = ..., pod_status: _Optional[int] = ..., error: _Optional[str] = ...) -> None: ...
