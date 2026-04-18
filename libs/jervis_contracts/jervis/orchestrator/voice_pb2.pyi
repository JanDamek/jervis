from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class VoiceProcessRequest(_message.Message):
    __slots__ = ("ctx", "text", "source", "client_id", "project_id", "group_id", "tts", "meeting_id", "live_assist", "chunk_index", "is_final")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    TTS_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    LIVE_ASSIST_FIELD_NUMBER: _ClassVar[int]
    CHUNK_INDEX_FIELD_NUMBER: _ClassVar[int]
    IS_FINAL_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    text: str
    source: str
    client_id: str
    project_id: str
    group_id: str
    tts: bool
    meeting_id: str
    live_assist: bool
    chunk_index: int
    is_final: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., text: _Optional[str] = ..., source: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., tts: bool = ..., meeting_id: _Optional[str] = ..., live_assist: bool = ..., chunk_index: _Optional[int] = ..., is_final: bool = ...) -> None: ...

class VoiceStreamEvent(_message.Message):
    __slots__ = ("event", "data_json")
    EVENT_FIELD_NUMBER: _ClassVar[int]
    DATA_JSON_FIELD_NUMBER: _ClassVar[int]
    event: str
    data_json: str
    def __init__(self, event: _Optional[str] = ..., data_json: _Optional[str] = ...) -> None: ...

class VoiceHintRequest(_message.Message):
    __slots__ = ("ctx", "text", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    text: str
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., text: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class VoiceHintResponse(_message.Message):
    __slots__ = ("hint",)
    HINT_FIELD_NUMBER: _ClassVar[int]
    hint: str
    def __init__(self, hint: _Optional[str] = ...) -> None: ...
