from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class StartHelperRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "device_id", "source_lang", "target_lang")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_LANG_FIELD_NUMBER: _ClassVar[int]
    TARGET_LANG_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    device_id: str
    source_lang: str
    target_lang: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., device_id: _Optional[str] = ..., source_lang: _Optional[str] = ..., target_lang: _Optional[str] = ...) -> None: ...

class StartHelperResponse(_message.Message):
    __slots__ = ("status", "meeting_id", "device_id", "source_lang", "target_lang", "error")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_LANG_FIELD_NUMBER: _ClassVar[int]
    TARGET_LANG_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    status: str
    meeting_id: str
    device_id: str
    source_lang: str
    target_lang: str
    error: str
    def __init__(self, status: _Optional[str] = ..., meeting_id: _Optional[str] = ..., device_id: _Optional[str] = ..., source_lang: _Optional[str] = ..., target_lang: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class StopHelperRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ...) -> None: ...

class StopHelperResponse(_message.Message):
    __slots__ = ("status", "meeting_id")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    status: str
    meeting_id: str
    def __init__(self, status: _Optional[str] = ..., meeting_id: _Optional[str] = ...) -> None: ...

class HelperChunkRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id", "text", "speaker")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SPEAKER_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    text: str
    speaker: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ..., text: _Optional[str] = ..., speaker: _Optional[str] = ...) -> None: ...

class HelperChunkResponse(_message.Message):
    __slots__ = ("status", "messages_pushed", "error")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    MESSAGES_PUSHED_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    status: str
    messages_pushed: int
    error: str
    def __init__(self, status: _Optional[str] = ..., messages_pushed: _Optional[int] = ..., error: _Optional[str] = ...) -> None: ...

class HelperStatusRequest(_message.Message):
    __slots__ = ("ctx", "meeting_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    meeting_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., meeting_id: _Optional[str] = ...) -> None: ...

class HelperStatusResponse(_message.Message):
    __slots__ = ("active", "meeting_id", "device_id", "source_lang", "target_lang", "context_size")
    ACTIVE_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_LANG_FIELD_NUMBER: _ClassVar[int]
    TARGET_LANG_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_SIZE_FIELD_NUMBER: _ClassVar[int]
    active: bool
    meeting_id: str
    device_id: str
    source_lang: str
    target_lang: str
    context_size: int
    def __init__(self, active: bool = ..., meeting_id: _Optional[str] = ..., device_id: _Optional[str] = ..., source_lang: _Optional[str] = ..., target_lang: _Optional[str] = ..., context_size: _Optional[int] = ...) -> None: ...
