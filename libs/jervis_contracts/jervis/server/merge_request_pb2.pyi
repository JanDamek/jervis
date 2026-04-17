from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ResolveReviewLanguageRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class ResolveReviewLanguageResponse(_message.Message):
    __slots__ = ("language",)
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    language: str
    def __init__(self, language: _Optional[str] = ...) -> None: ...

class CreateMergeRequestRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "branch", "target_branch", "title", "description")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    BRANCH_FIELD_NUMBER: _ClassVar[int]
    TARGET_BRANCH_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    branch: str
    target_branch: str
    title: str
    description: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., branch: _Optional[str] = ..., target_branch: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class CreateMergeRequestResponse(_message.Message):
    __slots__ = ("ok", "url", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    URL_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    url: str
    error: str
    def __init__(self, ok: bool = ..., url: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class GetMergeRequestDiffRequest(_message.Message):
    __slots__ = ("ctx", "task_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ...) -> None: ...

class GetMergeRequestDiffResponse(_message.Message):
    __slots__ = ("ok", "diffs", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    DIFFS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    diffs: _containers.RepeatedCompositeFieldContainer[DiffEntry]
    error: str
    def __init__(self, ok: bool = ..., diffs: _Optional[_Iterable[_Union[DiffEntry, _Mapping]]] = ..., error: _Optional[str] = ...) -> None: ...

class DiffEntry(_message.Message):
    __slots__ = ("old_path", "new_path", "new_file", "deleted_file", "renamed_file", "diff")
    OLD_PATH_FIELD_NUMBER: _ClassVar[int]
    NEW_PATH_FIELD_NUMBER: _ClassVar[int]
    NEW_FILE_FIELD_NUMBER: _ClassVar[int]
    DELETED_FILE_FIELD_NUMBER: _ClassVar[int]
    RENAMED_FILE_FIELD_NUMBER: _ClassVar[int]
    DIFF_FIELD_NUMBER: _ClassVar[int]
    old_path: str
    new_path: str
    new_file: bool
    deleted_file: bool
    renamed_file: bool
    diff: str
    def __init__(self, old_path: _Optional[str] = ..., new_path: _Optional[str] = ..., new_file: bool = ..., deleted_file: bool = ..., renamed_file: bool = ..., diff: _Optional[str] = ...) -> None: ...

class PostMrCommentRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "comment", "merge_request_url")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    COMMENT_FIELD_NUMBER: _ClassVar[int]
    MERGE_REQUEST_URL_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    comment: str
    merge_request_url: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., comment: _Optional[str] = ..., merge_request_url: _Optional[str] = ...) -> None: ...

class PostMrCommentResponse(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class PostMrInlineCommentsRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "summary", "verdict", "merge_request_url", "comments")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    VERDICT_FIELD_NUMBER: _ClassVar[int]
    MERGE_REQUEST_URL_FIELD_NUMBER: _ClassVar[int]
    COMMENTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    summary: str
    verdict: str
    merge_request_url: str
    comments: _containers.RepeatedCompositeFieldContainer[InlineComment]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., summary: _Optional[str] = ..., verdict: _Optional[str] = ..., merge_request_url: _Optional[str] = ..., comments: _Optional[_Iterable[_Union[InlineComment, _Mapping]]] = ...) -> None: ...

class InlineComment(_message.Message):
    __slots__ = ("file", "line", "body")
    FILE_FIELD_NUMBER: _ClassVar[int]
    LINE_FIELD_NUMBER: _ClassVar[int]
    BODY_FIELD_NUMBER: _ClassVar[int]
    file: str
    line: int
    body: str
    def __init__(self, file: _Optional[str] = ..., line: _Optional[int] = ..., body: _Optional[str] = ...) -> None: ...

class PostMrInlineCommentsResponse(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...
