from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class DocumentState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DOCUMENT_STATE_UNSPECIFIED: _ClassVar[DocumentState]
    DOCUMENT_STATE_UPLOADED: _ClassVar[DocumentState]
    DOCUMENT_STATE_EXTRACTED: _ClassVar[DocumentState]
    DOCUMENT_STATE_INDEXED: _ClassVar[DocumentState]
    DOCUMENT_STATE_FAILED: _ClassVar[DocumentState]

class DocumentCategory(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DOCUMENT_CATEGORY_UNSPECIFIED: _ClassVar[DocumentCategory]
    DOCUMENT_CATEGORY_TECHNICAL: _ClassVar[DocumentCategory]
    DOCUMENT_CATEGORY_BUSINESS: _ClassVar[DocumentCategory]
    DOCUMENT_CATEGORY_LEGAL: _ClassVar[DocumentCategory]
    DOCUMENT_CATEGORY_PROCESS: _ClassVar[DocumentCategory]
    DOCUMENT_CATEGORY_MEETING_NOTES: _ClassVar[DocumentCategory]
    DOCUMENT_CATEGORY_REPORT: _ClassVar[DocumentCategory]
    DOCUMENT_CATEGORY_SPECIFICATION: _ClassVar[DocumentCategory]
    DOCUMENT_CATEGORY_OTHER: _ClassVar[DocumentCategory]
DOCUMENT_STATE_UNSPECIFIED: DocumentState
DOCUMENT_STATE_UPLOADED: DocumentState
DOCUMENT_STATE_EXTRACTED: DocumentState
DOCUMENT_STATE_INDEXED: DocumentState
DOCUMENT_STATE_FAILED: DocumentState
DOCUMENT_CATEGORY_UNSPECIFIED: DocumentCategory
DOCUMENT_CATEGORY_TECHNICAL: DocumentCategory
DOCUMENT_CATEGORY_BUSINESS: DocumentCategory
DOCUMENT_CATEGORY_LEGAL: DocumentCategory
DOCUMENT_CATEGORY_PROCESS: DocumentCategory
DOCUMENT_CATEGORY_MEETING_NOTES: DocumentCategory
DOCUMENT_CATEGORY_REPORT: DocumentCategory
DOCUMENT_CATEGORY_SPECIFICATION: DocumentCategory
DOCUMENT_CATEGORY_OTHER: DocumentCategory

class Document(_message.Message):
    __slots__ = ("id", "client_id", "project_id", "filename", "mime_type", "size_bytes", "storage_path", "state", "category", "title", "description", "tags", "extracted_text_preview", "page_count", "content_hash", "source_urn", "error_message", "rag_chunks", "uploaded_at_iso", "indexed_at_iso")
    ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    MIME_TYPE_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    STORAGE_PATH_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    TAGS_FIELD_NUMBER: _ClassVar[int]
    EXTRACTED_TEXT_PREVIEW_FIELD_NUMBER: _ClassVar[int]
    PAGE_COUNT_FIELD_NUMBER: _ClassVar[int]
    CONTENT_HASH_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    RAG_CHUNKS_FIELD_NUMBER: _ClassVar[int]
    UPLOADED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    INDEXED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    id: str
    client_id: str
    project_id: str
    filename: str
    mime_type: str
    size_bytes: int
    storage_path: str
    state: DocumentState
    category: DocumentCategory
    title: str
    description: str
    tags: _containers.RepeatedScalarFieldContainer[str]
    extracted_text_preview: str
    page_count: int
    content_hash: str
    source_urn: str
    error_message: str
    rag_chunks: _containers.RepeatedScalarFieldContainer[str]
    uploaded_at_iso: str
    indexed_at_iso: str
    def __init__(self, id: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., filename: _Optional[str] = ..., mime_type: _Optional[str] = ..., size_bytes: _Optional[int] = ..., storage_path: _Optional[str] = ..., state: _Optional[_Union[DocumentState, str]] = ..., category: _Optional[_Union[DocumentCategory, str]] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., tags: _Optional[_Iterable[str]] = ..., extracted_text_preview: _Optional[str] = ..., page_count: _Optional[int] = ..., content_hash: _Optional[str] = ..., source_urn: _Optional[str] = ..., error_message: _Optional[str] = ..., rag_chunks: _Optional[_Iterable[str]] = ..., uploaded_at_iso: _Optional[str] = ..., indexed_at_iso: _Optional[str] = ...) -> None: ...

class DocumentId(_message.Message):
    __slots__ = ("ctx", "id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., id: _Optional[str] = ...) -> None: ...

class DocumentList(_message.Message):
    __slots__ = ("items",)
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[Document]
    def __init__(self, items: _Optional[_Iterable[_Union[Document, _Mapping]]] = ...) -> None: ...

class DocumentAck(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class DocumentUploadRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "filename", "mime_type", "size_bytes", "storage_path", "title", "description", "category", "tags", "content_hash", "data", "blob_ref")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    MIME_TYPE_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    STORAGE_PATH_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    TAGS_FIELD_NUMBER: _ClassVar[int]
    CONTENT_HASH_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    BLOB_REF_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    filename: str
    mime_type: str
    size_bytes: int
    storage_path: str
    title: str
    description: str
    category: DocumentCategory
    tags: _containers.RepeatedScalarFieldContainer[str]
    content_hash: str
    data: bytes
    blob_ref: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., filename: _Optional[str] = ..., mime_type: _Optional[str] = ..., size_bytes: _Optional[int] = ..., storage_path: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., category: _Optional[_Union[DocumentCategory, str]] = ..., tags: _Optional[_Iterable[str]] = ..., content_hash: _Optional[str] = ..., data: _Optional[bytes] = ..., blob_ref: _Optional[str] = ...) -> None: ...

class DocumentRegisterRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "filename", "mime_type", "size_bytes", "storage_path", "title", "description", "category", "tags", "content_hash")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    MIME_TYPE_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    STORAGE_PATH_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    TAGS_FIELD_NUMBER: _ClassVar[int]
    CONTENT_HASH_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    filename: str
    mime_type: str
    size_bytes: int
    storage_path: str
    title: str
    description: str
    category: DocumentCategory
    tags: _containers.RepeatedScalarFieldContainer[str]
    content_hash: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., filename: _Optional[str] = ..., mime_type: _Optional[str] = ..., size_bytes: _Optional[int] = ..., storage_path: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., category: _Optional[_Union[DocumentCategory, str]] = ..., tags: _Optional[_Iterable[str]] = ..., content_hash: _Optional[str] = ...) -> None: ...

class DocumentListRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class DocumentUpdateRequest(_message.Message):
    __slots__ = ("ctx", "id", "title", "description", "category", "tags", "field_mask")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    TAGS_FIELD_NUMBER: _ClassVar[int]
    FIELD_MASK_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    id: str
    title: str
    description: str
    category: DocumentCategory
    tags: _containers.RepeatedScalarFieldContainer[str]
    field_mask: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., category: _Optional[_Union[DocumentCategory, str]] = ..., tags: _Optional[_Iterable[str]] = ..., field_mask: _Optional[_Iterable[str]] = ...) -> None: ...

class DocumentExtractRequest(_message.Message):
    __slots__ = ("ctx", "id", "force_reextract")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    FORCE_REEXTRACT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    id: str
    force_reextract: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., id: _Optional[str] = ..., force_reextract: bool = ...) -> None: ...

class DocumentExtractResult(_message.Message):
    __slots__ = ("status", "text", "page_count", "content_hash", "error")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    PAGE_COUNT_FIELD_NUMBER: _ClassVar[int]
    CONTENT_HASH_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    status: str
    text: str
    page_count: int
    content_hash: str
    error: str
    def __init__(self, status: _Optional[str] = ..., text: _Optional[str] = ..., page_count: _Optional[int] = ..., content_hash: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...
