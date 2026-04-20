from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class StartRecordingRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "title", "meeting_type", "device_session_id", "task_id", "joined_by")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    MEETING_TYPE_FIELD_NUMBER: _ClassVar[int]
    DEVICE_SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    JOINED_BY_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    title: str
    meeting_type: str
    device_session_id: str
    task_id: str
    joined_by: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., title: _Optional[str] = ..., meeting_type: _Optional[str] = ..., device_session_id: _Optional[str] = ..., task_id: _Optional[str] = ..., joined_by: _Optional[str] = ...) -> None: ...

class StartRecordingResponse(_message.Message):
    __slots__ = ("id", "state")
    ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    id: str
    state: str
    def __init__(self, id: _Optional[str] = ..., state: _Optional[str] = ...) -> None: ...

class UploadChunkRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "chunk_index", "data", "mime_type", "is_last")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    CHUNK_INDEX_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    MIME_TYPE_FIELD_NUMBER: _ClassVar[int]
    IS_LAST_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    chunk_index: int
    data: str
    mime_type: str
    is_last: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., chunk_index: _Optional[int] = ..., data: _Optional[str] = ..., mime_type: _Optional[str] = ..., is_last: bool = ...) -> None: ...

class UploadChunkResponse(_message.Message):
    __slots__ = ("meeting_id", "chunk_count")
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    CHUNK_COUNT_FIELD_NUMBER: _ClassVar[int]
    meeting_id: str
    chunk_count: int
    def __init__(self, meeting_id: _Optional[str] = ..., chunk_count: _Optional[int] = ...) -> None: ...

class FinalizeRecordingRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "meeting_type", "duration_seconds", "title", "stop_reason")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    MEETING_TYPE_FIELD_NUMBER: _ClassVar[int]
    DURATION_SECONDS_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    STOP_REASON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    meeting_type: str
    duration_seconds: int
    title: str
    stop_reason: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., meeting_type: _Optional[str] = ..., duration_seconds: _Optional[int] = ..., title: _Optional[str] = ..., stop_reason: _Optional[str] = ...) -> None: ...

class FinalizeRecordingResponse(_message.Message):
    __slots__ = ("id", "state", "duration_seconds")
    ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    DURATION_SECONDS_FIELD_NUMBER: _ClassVar[int]
    id: str
    state: str
    duration_seconds: int
    def __init__(self, id: _Optional[str] = ..., state: _Optional[str] = ..., duration_seconds: _Optional[int] = ...) -> None: ...

class VideoChunkRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "chunk_index", "data")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    CHUNK_INDEX_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    chunk_index: int
    data: bytes
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., chunk_index: _Optional[int] = ..., data: _Optional[bytes] = ...) -> None: ...

class VideoChunkAck(_message.Message):
    __slots__ = ("meeting_id", "chunk_index", "deduped", "chunks_received", "bytes")
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    CHUNK_INDEX_FIELD_NUMBER: _ClassVar[int]
    DEDUPED_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_RECEIVED_FIELD_NUMBER: _ClassVar[int]
    BYTES_FIELD_NUMBER: _ClassVar[int]
    meeting_id: str
    chunk_index: int
    deduped: bool
    chunks_received: int
    bytes: int
    def __init__(self, meeting_id: _Optional[str] = ..., chunk_index: _Optional[int] = ..., deduped: bool = ..., chunks_received: _Optional[int] = ..., bytes: _Optional[int] = ...) -> None: ...

class FinalizeVideoRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "joined_by")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    JOINED_BY_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    joined_by: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., joined_by: _Optional[str] = ...) -> None: ...

class FinalizeVideoResponse(_message.Message):
    __slots__ = ("meeting_id", "state", "chunks_received", "webm_path", "retention_until")
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_RECEIVED_FIELD_NUMBER: _ClassVar[int]
    WEBM_PATH_FIELD_NUMBER: _ClassVar[int]
    RETENTION_UNTIL_FIELD_NUMBER: _ClassVar[int]
    meeting_id: str
    state: str
    chunks_received: int
    webm_path: str
    retention_until: str
    def __init__(self, meeting_id: _Optional[str] = ..., state: _Optional[str] = ..., chunks_received: _Optional[int] = ..., webm_path: _Optional[str] = ..., retention_until: _Optional[str] = ...) -> None: ...
