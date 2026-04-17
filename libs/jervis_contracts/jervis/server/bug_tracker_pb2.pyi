from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CreateIssueRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "title", "description", "labels", "issue_type", "priority")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    LABELS_FIELD_NUMBER: _ClassVar[int]
    ISSUE_TYPE_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    title: str
    description: str
    labels: _containers.RepeatedScalarFieldContainer[str]
    issue_type: str
    priority: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., labels: _Optional[_Iterable[str]] = ..., issue_type: _Optional[str] = ..., priority: _Optional[str] = ...) -> None: ...

class AddIssueCommentRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "issue_key", "comment")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ISSUE_KEY_FIELD_NUMBER: _ClassVar[int]
    COMMENT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    issue_key: str
    comment: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., issue_key: _Optional[str] = ..., comment: _Optional[str] = ...) -> None: ...

class UpdateIssueRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "issue_key", "title", "description", "state", "labels", "has_labels")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ISSUE_KEY_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    LABELS_FIELD_NUMBER: _ClassVar[int]
    HAS_LABELS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    issue_key: str
    title: str
    description: str
    state: str
    labels: _containers.RepeatedScalarFieldContainer[str]
    has_labels: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., issue_key: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., state: _Optional[str] = ..., labels: _Optional[_Iterable[str]] = ..., has_labels: bool = ...) -> None: ...

class ListIssuesRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class ListIssuesResponse(_message.Message):
    __slots__ = ("ok", "issues", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ISSUES_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    issues: _containers.RepeatedCompositeFieldContainer[IssueListItem]
    error: str
    def __init__(self, ok: bool = ..., issues: _Optional[_Iterable[_Union[IssueListItem, _Mapping]]] = ..., error: _Optional[str] = ...) -> None: ...

class IssueListItem(_message.Message):
    __slots__ = ("key", "title", "state", "url", "created", "updated")
    KEY_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    URL_FIELD_NUMBER: _ClassVar[int]
    CREATED_FIELD_NUMBER: _ClassVar[int]
    UPDATED_FIELD_NUMBER: _ClassVar[int]
    key: str
    title: str
    state: str
    url: str
    created: str
    updated: str
    def __init__(self, key: _Optional[str] = ..., title: _Optional[str] = ..., state: _Optional[str] = ..., url: _Optional[str] = ..., created: _Optional[str] = ..., updated: _Optional[str] = ...) -> None: ...

class IssueResponse(_message.Message):
    __slots__ = ("ok", "key", "url", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    KEY_FIELD_NUMBER: _ClassVar[int]
    URL_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    key: str
    url: str
    error: str
    def __init__(self, ok: bool = ..., key: _Optional[str] = ..., url: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class CommentResponse(_message.Message):
    __slots__ = ("ok", "url", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    URL_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    url: str
    error: str
    def __init__(self, ok: bool = ..., url: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...
