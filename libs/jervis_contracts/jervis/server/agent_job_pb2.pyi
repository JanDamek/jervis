from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class AgentJobIdRequest(_message.Message):
    __slots__ = ("ctx", "agent_job_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    agent_job_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., agent_job_id: _Optional[str] = ...) -> None: ...

class DispatchAgentJobRequest(_message.Message):
    __slots__ = ("ctx", "flavor", "title", "description", "client_id", "project_id", "resource_id", "dispatched_by", "branch_name")
    CTX_FIELD_NUMBER: _ClassVar[int]
    FLAVOR_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_ID_FIELD_NUMBER: _ClassVar[int]
    DISPATCHED_BY_FIELD_NUMBER: _ClassVar[int]
    BRANCH_NAME_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    flavor: str
    title: str
    description: str
    client_id: str
    project_id: str
    resource_id: str
    dispatched_by: str
    branch_name: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., flavor: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., resource_id: _Optional[str] = ..., dispatched_by: _Optional[str] = ..., branch_name: _Optional[str] = ...) -> None: ...

class DispatchAgentJobResponse(_message.Message):
    __slots__ = ("ok", "error", "agent_job_id", "state", "kubernetes_job_name", "workspace_path", "branch")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    KUBERNETES_JOB_NAME_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    BRANCH_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    agent_job_id: str
    state: str
    kubernetes_job_name: str
    workspace_path: str
    branch: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., agent_job_id: _Optional[str] = ..., state: _Optional[str] = ..., kubernetes_job_name: _Optional[str] = ..., workspace_path: _Optional[str] = ..., branch: _Optional[str] = ...) -> None: ...

class GetAgentJobStatusResponse(_message.Message):
    __slots__ = ("ok", "error", "agent_job_id", "flavor", "state", "kubernetes_job_name", "kubernetes_job_phase", "client_id", "project_id", "resource_id", "workspace_path", "branch", "git_commit_sha", "title", "description", "dispatched_by", "result_summary", "artifacts", "ask_user_question", "error_message", "created_at", "started_at", "completed_at")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_ID_FIELD_NUMBER: _ClassVar[int]
    FLAVOR_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    KUBERNETES_JOB_NAME_FIELD_NUMBER: _ClassVar[int]
    KUBERNETES_JOB_PHASE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_ID_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    BRANCH_FIELD_NUMBER: _ClassVar[int]
    GIT_COMMIT_SHA_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    DISPATCHED_BY_FIELD_NUMBER: _ClassVar[int]
    RESULT_SUMMARY_FIELD_NUMBER: _ClassVar[int]
    ARTIFACTS_FIELD_NUMBER: _ClassVar[int]
    ASK_USER_QUESTION_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    STARTED_AT_FIELD_NUMBER: _ClassVar[int]
    COMPLETED_AT_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    agent_job_id: str
    flavor: str
    state: str
    kubernetes_job_name: str
    kubernetes_job_phase: str
    client_id: str
    project_id: str
    resource_id: str
    workspace_path: str
    branch: str
    git_commit_sha: str
    title: str
    description: str
    dispatched_by: str
    result_summary: str
    artifacts: _containers.RepeatedScalarFieldContainer[str]
    ask_user_question: str
    error_message: str
    created_at: str
    started_at: str
    completed_at: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., agent_job_id: _Optional[str] = ..., flavor: _Optional[str] = ..., state: _Optional[str] = ..., kubernetes_job_name: _Optional[str] = ..., kubernetes_job_phase: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., resource_id: _Optional[str] = ..., workspace_path: _Optional[str] = ..., branch: _Optional[str] = ..., git_commit_sha: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., dispatched_by: _Optional[str] = ..., result_summary: _Optional[str] = ..., artifacts: _Optional[_Iterable[str]] = ..., ask_user_question: _Optional[str] = ..., error_message: _Optional[str] = ..., created_at: _Optional[str] = ..., started_at: _Optional[str] = ..., completed_at: _Optional[str] = ...) -> None: ...

class AbortAgentJobRequest(_message.Message):
    __slots__ = ("ctx", "agent_job_id", "reason")
    CTX_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_ID_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    agent_job_id: str
    reason: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., agent_job_id: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class AbortAgentJobResponse(_message.Message):
    __slots__ = ("ok", "error", "state")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    state: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., state: _Optional[str] = ...) -> None: ...
