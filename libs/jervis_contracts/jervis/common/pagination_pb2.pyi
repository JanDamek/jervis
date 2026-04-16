from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class PageRequest(_message.Message):
    __slots__ = ("limit", "cursor")
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    limit: int
    cursor: str
    def __init__(self, limit: _Optional[int] = ..., cursor: _Optional[str] = ...) -> None: ...

class PageCursor(_message.Message):
    __slots__ = ("next_cursor", "has_more")
    NEXT_CURSOR_FIELD_NUMBER: _ClassVar[int]
    HAS_MORE_FIELD_NUMBER: _ClassVar[int]
    next_cursor: str
    has_more: bool
    def __init__(self, next_cursor: _Optional[str] = ..., has_more: bool = ...) -> None: ...
