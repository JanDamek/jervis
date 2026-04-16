from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class LeaveRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "reason")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    reason: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class LeaveResponse(_message.Message):
    __slots__ = ("meeting_id", "state", "reason")
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    meeting_id: str
    state: str
    reason: str
    def __init__(self, meeting_id: _Optional[str] = ..., state: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class StayRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "suppress_minutes")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    SUPPRESS_MINUTES_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    suppress_minutes: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., suppress_minutes: _Optional[int] = ...) -> None: ...

class StayResponse(_message.Message):
    __slots__ = ("meeting_id", "state", "suppress_minutes")
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    SUPPRESS_MINUTES_FIELD_NUMBER: _ClassVar[int]
    meeting_id: str
    state: str
    suppress_minutes: int
    def __init__(self, meeting_id: _Optional[str] = ..., state: _Optional[str] = ..., suppress_minutes: _Optional[int] = ...) -> None: ...
