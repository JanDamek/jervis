from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GetTranscriptRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ...) -> None: ...

class GetTranscriptResponse(_message.Message):
    __slots__ = ("meeting_id", "title", "state", "transcript", "format", "error")
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    TRANSCRIPT_FIELD_NUMBER: _ClassVar[int]
    FORMAT_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    meeting_id: str
    title: str
    state: str
    transcript: str
    format: str
    error: str
    def __init__(self, meeting_id: _Optional[str] = ..., title: _Optional[str] = ..., state: _Optional[str] = ..., transcript: _Optional[str] = ..., format: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class ListMeetingsRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "state", "limit")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    state: str
    limit: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., state: _Optional[str] = ..., limit: _Optional[int] = ...) -> None: ...

class ListMeetingsResponse(_message.Message):
    __slots__ = ("meetings",)
    MEETINGS_FIELD_NUMBER: _ClassVar[int]
    meetings: _containers.RepeatedCompositeFieldContainer[MeetingSummary]
    def __init__(self, meetings: _Optional[_Iterable[_Union[MeetingSummary, _Mapping]]] = ...) -> None: ...

class MeetingSummary(_message.Message):
    __slots__ = ("id", "title", "state", "client_id", "project_id", "started_at_iso", "duration_seconds", "meeting_type")
    ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    STARTED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    DURATION_SECONDS_FIELD_NUMBER: _ClassVar[int]
    MEETING_TYPE_FIELD_NUMBER: _ClassVar[int]
    id: str
    title: str
    state: str
    client_id: str
    project_id: str
    started_at_iso: str
    duration_seconds: str
    meeting_type: str
    def __init__(self, id: _Optional[str] = ..., title: _Optional[str] = ..., state: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., started_at_iso: _Optional[str] = ..., duration_seconds: _Optional[str] = ..., meeting_type: _Optional[str] = ...) -> None: ...

class ListUnclassifiedRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class ListUnclassifiedResponse(_message.Message):
    __slots__ = ("meetings",)
    MEETINGS_FIELD_NUMBER: _ClassVar[int]
    meetings: _containers.RepeatedCompositeFieldContainer[UnclassifiedMeeting]
    def __init__(self, meetings: _Optional[_Iterable[_Union[UnclassifiedMeeting, _Mapping]]] = ...) -> None: ...

class UnclassifiedMeeting(_message.Message):
    __slots__ = ("id", "title", "started_at_iso", "duration_seconds")
    ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    STARTED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    DURATION_SECONDS_FIELD_NUMBER: _ClassVar[int]
    id: str
    title: str
    started_at_iso: str
    duration_seconds: str
    def __init__(self, id: _Optional[str] = ..., title: _Optional[str] = ..., started_at_iso: _Optional[str] = ..., duration_seconds: _Optional[str] = ...) -> None: ...

class ClassifyMeetingRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "client_id", "project_id", "title")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    client_id: str
    project_id: str
    title: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., title: _Optional[str] = ...) -> None: ...

class ClassifyMeetingResponse(_message.Message):
    __slots__ = ("ok", "meeting_id", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    meeting_id: str
    error: str
    def __init__(self, ok: bool = ..., meeting_id: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...
