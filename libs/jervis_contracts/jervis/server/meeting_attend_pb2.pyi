from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ListUpcomingRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "hours_ahead", "limit")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    HOURS_AHEAD_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    hours_ahead: int
    limit: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., hours_ahead: _Optional[int] = ..., limit: _Optional[int] = ...) -> None: ...

class ListUpcomingResponse(_message.Message):
    __slots__ = ("meetings",)
    MEETINGS_FIELD_NUMBER: _ClassVar[int]
    meetings: _containers.RepeatedCompositeFieldContainer[UpcomingMeeting]
    def __init__(self, meetings: _Optional[_Iterable[_Union[UpcomingMeeting, _Mapping]]] = ...) -> None: ...

class UpcomingMeeting(_message.Message):
    __slots__ = ("task_id", "title", "client_id", "project_id", "start_time_iso", "end_time_iso", "provider", "join_url", "organizer", "is_recurring")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    START_TIME_ISO_FIELD_NUMBER: _ClassVar[int]
    END_TIME_ISO_FIELD_NUMBER: _ClassVar[int]
    PROVIDER_FIELD_NUMBER: _ClassVar[int]
    JOIN_URL_FIELD_NUMBER: _ClassVar[int]
    ORGANIZER_FIELD_NUMBER: _ClassVar[int]
    IS_RECURRING_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    title: str
    client_id: str
    project_id: str
    start_time_iso: str
    end_time_iso: str
    provider: str
    join_url: str
    organizer: str
    is_recurring: bool
    def __init__(self, task_id: _Optional[str] = ..., title: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., start_time_iso: _Optional[str] = ..., end_time_iso: _Optional[str] = ..., provider: _Optional[str] = ..., join_url: _Optional[str] = ..., organizer: _Optional[str] = ..., is_recurring: bool = ...) -> None: ...

class AttendDecisionRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "reason")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    reason: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class AttendDecisionResponse(_message.Message):
    __slots__ = ("task_id", "status", "state", "reason")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    status: str
    state: str
    reason: str
    def __init__(self, task_id: _Optional[str] = ..., status: _Optional[str] = ..., state: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class PresenceRequest(_message.Message):
    __slots__ = ("ctx", "connection_id", "client_id", "present")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PRESENT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    connection_id: str
    client_id: str
    present: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., connection_id: _Optional[str] = ..., client_id: _Optional[str] = ..., present: bool = ...) -> None: ...

class PresenceResponse(_message.Message):
    __slots__ = ("ok", "present")
    OK_FIELD_NUMBER: _ClassVar[int]
    PRESENT_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    present: bool
    def __init__(self, ok: bool = ..., present: bool = ...) -> None: ...
