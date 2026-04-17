from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GetOpenRouterSettingsRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class GetOpenRouterSettingsResponse(_message.Message):
    __slots__ = ("body_json",)
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    body_json: str
    def __init__(self, body_json: _Optional[str] = ...) -> None: ...

class PersistModelStatsRequest(_message.Message):
    __slots__ = ("ctx", "stats_json")
    CTX_FIELD_NUMBER: _ClassVar[int]
    STATS_JSON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    stats_json: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., stats_json: _Optional[str] = ...) -> None: ...

class PersistModelStatsResponse(_message.Message):
    __slots__ = ("ok", "models")
    OK_FIELD_NUMBER: _ClassVar[int]
    MODELS_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    models: int
    def __init__(self, ok: bool = ..., models: _Optional[int] = ...) -> None: ...
