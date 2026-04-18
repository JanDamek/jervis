from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ExtractRequest(_message.Message):
    __slots__ = ("ctx", "content", "filename", "mime_type", "max_tier")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    MIME_TYPE_FIELD_NUMBER: _ClassVar[int]
    MAX_TIER_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    content: bytes
    filename: str
    mime_type: str
    max_tier: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., content: _Optional[bytes] = ..., filename: _Optional[str] = ..., mime_type: _Optional[str] = ..., max_tier: _Optional[str] = ...) -> None: ...

class ExtractedPage(_message.Message):
    __slots__ = ("page_number", "text")
    PAGE_NUMBER_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    page_number: int
    text: str
    def __init__(self, page_number: _Optional[int] = ..., text: _Optional[str] = ...) -> None: ...

class ExtractResponse(_message.Message):
    __slots__ = ("text", "method", "metadata", "pages")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    TEXT_FIELD_NUMBER: _ClassVar[int]
    METHOD_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    PAGES_FIELD_NUMBER: _ClassVar[int]
    text: str
    method: str
    metadata: _containers.ScalarMap[str, str]
    pages: _containers.RepeatedCompositeFieldContainer[ExtractedPage]
    def __init__(self, text: _Optional[str] = ..., method: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ..., pages: _Optional[_Iterable[_Union[ExtractedPage, _Mapping]]] = ...) -> None: ...

class HealthRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class HealthResponse(_message.Message):
    __slots__ = ("status", "service")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SERVICE_FIELD_NUMBER: _ClassVar[int]
    status: str
    service: str
    def __init__(self, status: _Optional[str] = ..., service: _Optional[str] = ...) -> None: ...
