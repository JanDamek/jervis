from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ApprovalBroadcastRequest(_message.Message):
    __slots__ = ("ctx", "approval_id", "action", "tool", "preview", "client_id", "project_id", "session_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    APPROVAL_ID_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    TOOL_FIELD_NUMBER: _ClassVar[int]
    PREVIEW_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    approval_id: str
    action: str
    tool: str
    preview: str
    client_id: str
    project_id: str
    session_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., approval_id: _Optional[str] = ..., action: _Optional[str] = ..., tool: _Optional[str] = ..., preview: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., session_id: _Optional[str] = ...) -> None: ...

class ApprovalBroadcastResponse(_message.Message):
    __slots__ = ("status",)
    STATUS_FIELD_NUMBER: _ClassVar[int]
    status: str
    def __init__(self, status: _Optional[str] = ...) -> None: ...

class ApprovalResolvedRequest(_message.Message):
    __slots__ = ("ctx", "approval_id", "approved", "action", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    APPROVAL_ID_FIELD_NUMBER: _ClassVar[int]
    APPROVED_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    approval_id: str
    approved: bool
    action: str
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., approval_id: _Optional[str] = ..., approved: bool = ..., action: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class ApprovalResolvedResponse(_message.Message):
    __slots__ = ("status",)
    STATUS_FIELD_NUMBER: _ClassVar[int]
    status: str
    def __init__(self, status: _Optional[str] = ...) -> None: ...
