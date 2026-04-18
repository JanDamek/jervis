from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class O365Request(_message.Message):
    __slots__ = ("ctx", "method", "path", "query", "body_json", "content_type")
    class QueryEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    METHOD_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    method: str
    path: str
    query: _containers.ScalarMap[str, str]
    body_json: str
    content_type: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., method: _Optional[str] = ..., path: _Optional[str] = ..., query: _Optional[_Mapping[str, str]] = ..., body_json: _Optional[str] = ..., content_type: _Optional[str] = ...) -> None: ...

class O365Response(_message.Message):
    __slots__ = ("status_code", "body_json", "headers")
    class HeadersEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    STATUS_CODE_FIELD_NUMBER: _ClassVar[int]
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    HEADERS_FIELD_NUMBER: _ClassVar[int]
    status_code: int
    body_json: str
    headers: _containers.ScalarMap[str, str]
    def __init__(self, status_code: _Optional[int] = ..., body_json: _Optional[str] = ..., headers: _Optional[_Mapping[str, str]] = ...) -> None: ...

class O365BytesResponse(_message.Message):
    __slots__ = ("status_code", "body", "content_type", "filename", "headers")
    class HeadersEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    STATUS_CODE_FIELD_NUMBER: _ClassVar[int]
    BODY_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    HEADERS_FIELD_NUMBER: _ClassVar[int]
    status_code: int
    body: bytes
    content_type: str
    filename: str
    headers: _containers.ScalarMap[str, str]
    def __init__(self, status_code: _Optional[int] = ..., body: _Optional[bytes] = ..., content_type: _Optional[str] = ..., filename: _Optional[str] = ..., headers: _Optional[_Mapping[str, str]] = ...) -> None: ...
