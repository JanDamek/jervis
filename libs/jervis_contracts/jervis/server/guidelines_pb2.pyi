from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GuidelinesScope(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    GUIDELINES_SCOPE_UNSPECIFIED: _ClassVar[GuidelinesScope]
    GUIDELINES_SCOPE_GLOBAL: _ClassVar[GuidelinesScope]
    GUIDELINES_SCOPE_CLIENT: _ClassVar[GuidelinesScope]
    GUIDELINES_SCOPE_PROJECT: _ClassVar[GuidelinesScope]
GUIDELINES_SCOPE_UNSPECIFIED: GuidelinesScope
GUIDELINES_SCOPE_GLOBAL: GuidelinesScope
GUIDELINES_SCOPE_CLIENT: GuidelinesScope
GUIDELINES_SCOPE_PROJECT: GuidelinesScope

class GetMergedRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class GetRequest(_message.Message):
    __slots__ = ("ctx", "scope", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    scope: GuidelinesScope
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., scope: _Optional[_Union[GuidelinesScope, str]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class SetRequest(_message.Message):
    __slots__ = ("ctx", "update_json")
    CTX_FIELD_NUMBER: _ClassVar[int]
    UPDATE_JSON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    update_json: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., update_json: _Optional[str] = ...) -> None: ...

class GuidelinesPayload(_message.Message):
    __slots__ = ("body_json",)
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    body_json: str
    def __init__(self, body_json: _Optional[str] = ...) -> None: ...
