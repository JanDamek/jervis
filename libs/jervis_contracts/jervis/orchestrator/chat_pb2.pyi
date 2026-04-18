from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ChatRequest(_message.Message):
    __slots__ = ("ctx", "session_id", "message", "message_sequence", "user_id", "active_client_id", "active_project_id", "active_group_id", "active_client_name", "active_project_name", "active_group_name", "context_task_id", "timestamp", "max_openrouter_tier", "deadline_iso", "priority", "client_timezone", "attachments")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    USER_ID_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_CLIENT_NAME_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_PROJECT_NAME_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_GROUP_NAME_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    MAX_OPENROUTER_TIER_FIELD_NUMBER: _ClassVar[int]
    DEADLINE_ISO_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    CLIENT_TIMEZONE_FIELD_NUMBER: _ClassVar[int]
    ATTACHMENTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    message: str
    message_sequence: int
    user_id: str
    active_client_id: str
    active_project_id: str
    active_group_id: str
    active_client_name: str
    active_project_name: str
    active_group_name: str
    context_task_id: str
    timestamp: str
    max_openrouter_tier: str
    deadline_iso: str
    priority: str
    client_timezone: str
    attachments: _containers.RepeatedCompositeFieldContainer[Attachment]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ..., message: _Optional[str] = ..., message_sequence: _Optional[int] = ..., user_id: _Optional[str] = ..., active_client_id: _Optional[str] = ..., active_project_id: _Optional[str] = ..., active_group_id: _Optional[str] = ..., active_client_name: _Optional[str] = ..., active_project_name: _Optional[str] = ..., active_group_name: _Optional[str] = ..., context_task_id: _Optional[str] = ..., timestamp: _Optional[str] = ..., max_openrouter_tier: _Optional[str] = ..., deadline_iso: _Optional[str] = ..., priority: _Optional[str] = ..., client_timezone: _Optional[str] = ..., attachments: _Optional[_Iterable[_Union[Attachment, _Mapping]]] = ...) -> None: ...

class Attachment(_message.Message):
    __slots__ = ("filename", "mime_type", "size_bytes", "content_base64")
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    MIME_TYPE_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    CONTENT_BASE64_FIELD_NUMBER: _ClassVar[int]
    filename: str
    mime_type: str
    size_bytes: int
    content_base64: str
    def __init__(self, filename: _Optional[str] = ..., mime_type: _Optional[str] = ..., size_bytes: _Optional[int] = ..., content_base64: _Optional[str] = ...) -> None: ...

class ChatMessage(_message.Message):
    __slots__ = ("role", "content", "timestamp", "tool_call_id")
    ROLE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    TOOL_CALL_ID_FIELD_NUMBER: _ClassVar[int]
    role: str
    content: str
    timestamp: str
    tool_call_id: str
    def __init__(self, role: _Optional[str] = ..., content: _Optional[str] = ..., timestamp: _Optional[str] = ..., tool_call_id: _Optional[str] = ...) -> None: ...

class ChatEvent(_message.Message):
    __slots__ = ("type", "content", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    TYPE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    type: str
    content: str
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, type: _Optional[str] = ..., content: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class ApproveActionRequest(_message.Message):
    __slots__ = ("ctx", "session_id", "approved", "always", "action")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    APPROVED_FIELD_NUMBER: _ClassVar[int]
    ALWAYS_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    approved: bool
    always: bool
    action: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ..., approved: bool = ..., always: bool = ..., action: _Optional[str] = ...) -> None: ...

class ApproveActionAck(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class StopChatRequest(_message.Message):
    __slots__ = ("ctx", "session_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    session_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., session_id: _Optional[str] = ...) -> None: ...

class StopChatAck(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...
