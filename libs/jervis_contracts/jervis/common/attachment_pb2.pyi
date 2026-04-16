from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class AttachmentRef(_message.Message):
    __slots__ = ("blob_ref", "filename", "mime", "size_bytes", "storage_path")
    BLOB_REF_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    MIME_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    STORAGE_PATH_FIELD_NUMBER: _ClassVar[int]
    blob_ref: str
    filename: str
    mime: str
    size_bytes: int
    storage_path: str
    def __init__(self, blob_ref: _Optional[str] = ..., filename: _Optional[str] = ..., mime: _Optional[str] = ..., size_bytes: _Optional[int] = ..., storage_path: _Optional[str] = ...) -> None: ...
