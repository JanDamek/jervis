from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class AgentJobEventsSubscribeRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class AgentJobStateChangedEvent(_message.Message):
    __slots__ = ("agent_job_id", "flavor", "state", "title", "client_id", "project_id", "resource_id", "git_branch", "git_commit_sha", "result_summary", "error_message", "artifacts", "transitioned_at", "started_at", "completed_at")
    AGENT_JOB_ID_FIELD_NUMBER: _ClassVar[int]
    FLAVOR_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_ID_FIELD_NUMBER: _ClassVar[int]
    GIT_BRANCH_FIELD_NUMBER: _ClassVar[int]
    GIT_COMMIT_SHA_FIELD_NUMBER: _ClassVar[int]
    RESULT_SUMMARY_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ARTIFACTS_FIELD_NUMBER: _ClassVar[int]
    TRANSITIONED_AT_FIELD_NUMBER: _ClassVar[int]
    STARTED_AT_FIELD_NUMBER: _ClassVar[int]
    COMPLETED_AT_FIELD_NUMBER: _ClassVar[int]
    agent_job_id: str
    flavor: str
    state: str
    title: str
    client_id: str
    project_id: str
    resource_id: str
    git_branch: str
    git_commit_sha: str
    result_summary: str
    error_message: str
    artifacts: _containers.RepeatedScalarFieldContainer[str]
    transitioned_at: str
    started_at: str
    completed_at: str
    def __init__(self, agent_job_id: _Optional[str] = ..., flavor: _Optional[str] = ..., state: _Optional[str] = ..., title: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., resource_id: _Optional[str] = ..., git_branch: _Optional[str] = ..., git_commit_sha: _Optional[str] = ..., result_summary: _Optional[str] = ..., error_message: _Optional[str] = ..., artifacts: _Optional[_Iterable[str]] = ..., transitioned_at: _Optional[str] = ..., started_at: _Optional[str] = ..., completed_at: _Optional[str] = ...) -> None: ...
