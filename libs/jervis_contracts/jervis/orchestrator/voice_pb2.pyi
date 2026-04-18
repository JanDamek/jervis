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
    __slots__ = ("responding", "token", "response", "stored", "done", "error")
    RESPONDING_FIELD_NUMBER: _ClassVar[int]
    TOKEN_FIELD_NUMBER: _ClassVar[int]
    RESPONSE_FIELD_NUMBER: _ClassVar[int]
    STORED_FIELD_NUMBER: _ClassVar[int]
    DONE_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    responding: Responding
    token: Token
    response: Response
    stored: Stored
    done: Done
    error: ErrorPayload
    def __init__(self, responding: _Optional[_Union[Responding, _Mapping]] = ..., token: _Optional[_Union[Token, _Mapping]] = ..., response: _Optional[_Union[Response, _Mapping]] = ..., stored: _Optional[_Union[Stored, _Mapping]] = ..., done: _Optional[_Union[Done, _Mapping]] = ..., error: _Optional[_Union[ErrorPayload, _Mapping]] = ...) -> None: ...

class Responding(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Token(_message.Message):
    __slots__ = ("text",)
    TEXT_FIELD_NUMBER: _ClassVar[int]
    text: str
    def __init__(self, text: _Optional[str] = ...) -> None: ...

class Response(_message.Message):
    __slots__ = ("text", "complete")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    COMPLETE_FIELD_NUMBER: _ClassVar[int]
    text: str
    complete: bool
    def __init__(self, text: _Optional[str] = ..., complete: bool = ...) -> None: ...

class Stored(_message.Message):
    __slots__ = ("kind", "summary")
    KIND_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    kind: str
    summary: str
    def __init__(self, kind: _Optional[str] = ..., summary: _Optional[str] = ...) -> None: ...

class Done(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ErrorPayload(_message.Message):
    __slots__ = ("text",)
    TEXT_FIELD_NUMBER: _ClassVar[int]
    text: str
    def __init__(self, text: _Optional[str] = ...) -> None: ...

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
