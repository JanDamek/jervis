from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class TaskIdRequest(_message.Message):
    __slots__ = ("ctx", "task_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ...) -> None: ...

class TaskListResponse(_message.Message):
    __slots__ = ("items",)
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[TaskSummary]
    def __init__(self, items: _Optional[_Iterable[_Union[TaskSummary, _Mapping]]] = ...) -> None: ...

class TaskSummary(_message.Message):
    __slots__ = ("id", "title", "state", "content", "client_id", "project_id", "created_at", "processing_mode", "priority_score", "parent_task_id", "phase", "estimated_complexity", "agent_job_name", "orchestrator_thread_id", "agent_job_started_at", "source_urn", "agent_job_workspace_path", "agent_job_agent_type", "merge_request_url", "question", "question_context")
    ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    PROCESSING_MODE_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_SCORE_FIELD_NUMBER: _ClassVar[int]
    PARENT_TASK_ID_FIELD_NUMBER: _ClassVar[int]
    PHASE_FIELD_NUMBER: _ClassVar[int]
    ESTIMATED_COMPLEXITY_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_NAME_FIELD_NUMBER: _ClassVar[int]
    ORCHESTRATOR_THREAD_ID_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_STARTED_AT_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_AGENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    MERGE_REQUEST_URL_FIELD_NUMBER: _ClassVar[int]
    QUESTION_FIELD_NUMBER: _ClassVar[int]
    QUESTION_CONTEXT_FIELD_NUMBER: _ClassVar[int]
    id: str
    title: str
    state: str
    content: str
    client_id: str
    project_id: str
    created_at: str
    processing_mode: str
    priority_score: int
    parent_task_id: str
    phase: str
    estimated_complexity: str
    agent_job_name: str
    orchestrator_thread_id: str
    agent_job_started_at: str
    source_urn: str
    agent_job_workspace_path: str
    agent_job_agent_type: str
    merge_request_url: str
    question: str
    question_context: str
    def __init__(self, id: _Optional[str] = ..., title: _Optional[str] = ..., state: _Optional[str] = ..., content: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., created_at: _Optional[str] = ..., processing_mode: _Optional[str] = ..., priority_score: _Optional[int] = ..., parent_task_id: _Optional[str] = ..., phase: _Optional[str] = ..., estimated_complexity: _Optional[str] = ..., agent_job_name: _Optional[str] = ..., orchestrator_thread_id: _Optional[str] = ..., agent_job_started_at: _Optional[str] = ..., source_urn: _Optional[str] = ..., agent_job_workspace_path: _Optional[str] = ..., agent_job_agent_type: _Optional[str] = ..., merge_request_url: _Optional[str] = ..., question: _Optional[str] = ..., question_context: _Optional[str] = ...) -> None: ...

class SimpleTaskActionResponse(_message.Message):
    __slots__ = ("ok", "task_id", "state", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    task_id: str
    state: str
    error: str
    def __init__(self, ok: bool = ..., task_id: _Optional[str] = ..., state: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class CreateTaskRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "title", "description", "query", "scheduled_at", "cron_timezone", "follow_user_timezone", "scheduled_local_time", "created_by", "priority", "skip_indexing")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    SCHEDULED_AT_FIELD_NUMBER: _ClassVar[int]
    CRON_TIMEZONE_FIELD_NUMBER: _ClassVar[int]
    FOLLOW_USER_TIMEZONE_FIELD_NUMBER: _ClassVar[int]
    SCHEDULED_LOCAL_TIME_FIELD_NUMBER: _ClassVar[int]
    CREATED_BY_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    SKIP_INDEXING_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    title: str
    description: str
    query: str
    scheduled_at: str
    cron_timezone: str
    follow_user_timezone: bool
    scheduled_local_time: str
    created_by: str
    priority: int
    skip_indexing: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., query: _Optional[str] = ..., scheduled_at: _Optional[str] = ..., cron_timezone: _Optional[str] = ..., follow_user_timezone: bool = ..., scheduled_local_time: _Optional[str] = ..., created_by: _Optional[str] = ..., priority: _Optional[int] = ..., skip_indexing: bool = ...) -> None: ...

class CreateTaskResponse(_message.Message):
    __slots__ = ("task_id", "state", "name", "deduplicated", "reason")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DEDUPLICATED_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    state: str
    name: str
    deduplicated: bool
    reason: str
    def __init__(self, task_id: _Optional[str] = ..., state: _Optional[str] = ..., name: _Optional[str] = ..., deduplicated: bool = ..., reason: _Optional[str] = ...) -> None: ...

class RespondToTaskRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "response")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    RESPONSE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    response: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., response: _Optional[str] = ...) -> None: ...

class RespondToTaskResponse(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class GetTaskResponse(_message.Message):
    __slots__ = ("ok", "error", "id", "state", "agent_job_name", "agent_job_state", "agent_job_workspace_path", "agent_job_agent_type", "client_id", "project_id", "source_urn")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_NAME_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_STATE_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    AGENT_JOB_AGENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    id: str
    state: str
    agent_job_name: str
    agent_job_state: str
    agent_job_workspace_path: str
    agent_job_agent_type: str
    client_id: str
    project_id: str
    source_urn: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., id: _Optional[str] = ..., state: _Optional[str] = ..., agent_job_name: _Optional[str] = ..., agent_job_state: _Optional[str] = ..., agent_job_workspace_path: _Optional[str] = ..., agent_job_agent_type: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., source_urn: _Optional[str] = ...) -> None: ...

class GetTaskStatusResponse(_message.Message):
    __slots__ = ("ok", "error", "id", "title", "state", "content", "client_id", "project_id", "created_at", "processing_mode", "question", "error_message")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    PROCESSING_MODE_FIELD_NUMBER: _ClassVar[int]
    QUESTION_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    id: str
    title: str
    state: str
    content: str
    client_id: str
    project_id: str
    created_at: str
    processing_mode: str
    question: str
    error_message: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., id: _Optional[str] = ..., title: _Optional[str] = ..., state: _Optional[str] = ..., content: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., created_at: _Optional[str] = ..., processing_mode: _Optional[str] = ..., question: _Optional[str] = ..., error_message: _Optional[str] = ...) -> None: ...

class SearchTasksRequest(_message.Message):
    __slots__ = ("ctx", "query", "state", "limit")
    CTX_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    query: str
    state: str
    limit: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., query: _Optional[str] = ..., state: _Optional[str] = ..., limit: _Optional[int] = ...) -> None: ...

class RecentTasksRequest(_message.Message):
    __slots__ = ("ctx", "limit", "state", "since", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    SINCE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    limit: int
    state: str
    since: str
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., limit: _Optional[int] = ..., state: _Optional[str] = ..., since: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class GetQueueRequest(_message.Message):
    __slots__ = ("ctx", "mode", "client_id", "limit")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    mode: str
    client_id: str
    limit: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., mode: _Optional[str] = ..., client_id: _Optional[str] = ..., limit: _Optional[int] = ...) -> None: ...

class CreateWorkPlanRequest(_message.Message):
    __slots__ = ("ctx", "title", "client_id", "project_id", "phases")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    PHASES_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    title: str
    client_id: str
    project_id: str
    phases: _containers.RepeatedCompositeFieldContainer[WorkPlanPhase]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., title: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., phases: _Optional[_Iterable[_Union[WorkPlanPhase, _Mapping]]] = ...) -> None: ...

class WorkPlanPhase(_message.Message):
    __slots__ = ("name", "tasks")
    NAME_FIELD_NUMBER: _ClassVar[int]
    TASKS_FIELD_NUMBER: _ClassVar[int]
    name: str
    tasks: _containers.RepeatedCompositeFieldContainer[WorkPlanTask]
    def __init__(self, name: _Optional[str] = ..., tasks: _Optional[_Iterable[_Union[WorkPlanTask, _Mapping]]] = ...) -> None: ...

class WorkPlanTask(_message.Message):
    __slots__ = ("title", "description", "action_type", "depends_on")
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    ACTION_TYPE_FIELD_NUMBER: _ClassVar[int]
    DEPENDS_ON_FIELD_NUMBER: _ClassVar[int]
    title: str
    description: str
    action_type: str
    depends_on: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, title: _Optional[str] = ..., description: _Optional[str] = ..., action_type: _Optional[str] = ..., depends_on: _Optional[_Iterable[str]] = ...) -> None: ...

class CreateWorkPlanResponse(_message.Message):
    __slots__ = ("root_task_id", "phase_count", "child_count", "ok", "error")
    ROOT_TASK_ID_FIELD_NUMBER: _ClassVar[int]
    PHASE_COUNT_FIELD_NUMBER: _ClassVar[int]
    CHILD_COUNT_FIELD_NUMBER: _ClassVar[int]
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    root_task_id: str
    phase_count: int
    child_count: int
    ok: bool
    error: str
    def __init__(self, root_task_id: _Optional[str] = ..., phase_count: _Optional[int] = ..., child_count: _Optional[int] = ..., ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class TaskNoteRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "note")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    NOTE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    note: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., note: _Optional[str] = ...) -> None: ...

class SetPriorityRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "priority_score")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_SCORE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    priority_score: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., priority_score: _Optional[int] = ...) -> None: ...

class SetPriorityResponse(_message.Message):
    __slots__ = ("ok", "task_id", "priority_score", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_SCORE_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    task_id: str
    priority_score: int
    error: str
    def __init__(self, ok: bool = ..., task_id: _Optional[str] = ..., priority_score: _Optional[int] = ..., error: _Optional[str] = ...) -> None: ...

class PushNotificationRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "title", "body", "data")
    class DataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    BODY_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    title: str
    body: str
    data: _containers.ScalarMap[str, str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., title: _Optional[str] = ..., body: _Optional[str] = ..., data: _Optional[_Mapping[str, str]] = ...) -> None: ...

class PushNotificationResponse(_message.Message):
    __slots__ = ("ok", "fcm", "apns")
    OK_FIELD_NUMBER: _ClassVar[int]
    FCM_FIELD_NUMBER: _ClassVar[int]
    APNS_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    fcm: str
    apns: str
    def __init__(self, ok: bool = ..., fcm: _Optional[str] = ..., apns: _Optional[str] = ...) -> None: ...

class PushBackgroundResultRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "task_title", "summary", "success", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TASK_TITLE_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    task_title: str
    summary: str
    success: bool
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., task_title: _Optional[str] = ..., summary: _Optional[str] = ..., success: bool = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class PushBackgroundResultResponse(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class TasksByStateRequest(_message.Message):
    __slots__ = ("ctx", "state")
    CTX_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    state: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., state: _Optional[str] = ...) -> None: ...

class AgentDispatchedRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "job_name", "workspace_path", "agent_type")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    JOB_NAME_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    AGENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    job_name: str
    workspace_path: str
    agent_type: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., job_name: _Optional[str] = ..., workspace_path: _Optional[str] = ..., agent_type: _Optional[str] = ...) -> None: ...

class CreateBackgroundTaskRequest(_message.Message):
    __slots__ = ("ctx", "title", "description", "client_id", "project_id", "priority")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    title: str
    description: str
    client_id: str
    project_id: str
    priority: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., priority: _Optional[str] = ...) -> None: ...

class CreateBackgroundTaskResponse(_message.Message):
    __slots__ = ("task_id", "title")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    title: str
    def __init__(self, task_id: _Optional[str] = ..., title: _Optional[str] = ...) -> None: ...

class DispatchCodingAgentRequest(_message.Message):
    __slots__ = ("ctx", "task_description", "client_id", "project_id", "source_urn", "review_round", "merge_request_url", "agent_preference")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    REVIEW_ROUND_FIELD_NUMBER: _ClassVar[int]
    MERGE_REQUEST_URL_FIELD_NUMBER: _ClassVar[int]
    AGENT_PREFERENCE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_description: str
    client_id: str
    project_id: str
    source_urn: str
    review_round: int
    merge_request_url: str
    agent_preference: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_description: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., source_urn: _Optional[str] = ..., review_round: _Optional[int] = ..., merge_request_url: _Optional[str] = ..., agent_preference: _Optional[str] = ...) -> None: ...

class DispatchCodingAgentResponse(_message.Message):
    __slots__ = ("task_id", "dispatched", "error")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    DISPATCHED_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    dispatched: bool
    error: str
    def __init__(self, task_id: _Optional[str] = ..., dispatched: bool = ..., error: _Optional[str] = ...) -> None: ...

class ListUserTasksRequest(_message.Message):
    __slots__ = ("ctx", "query", "max_results")
    CTX_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    MAX_RESULTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    query: str
    max_results: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., query: _Optional[str] = ..., max_results: _Optional[int] = ...) -> None: ...

class UserTaskResponse(_message.Message):
    __slots__ = ("ok", "error", "id", "title", "state", "question", "context", "client_id")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    QUESTION_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    id: str
    title: str
    state: str
    question: str
    context: str
    client_id: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., id: _Optional[str] = ..., title: _Optional[str] = ..., state: _Optional[str] = ..., question: _Optional[str] = ..., context: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class RespondToUserTaskRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "response")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    RESPONSE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    response: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., response: _Optional[str] = ...) -> None: ...

class DismissUserTasksRequest(_message.Message):
    __slots__ = ("ctx", "task_ids")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_IDS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_ids: _Optional[_Iterable[str]] = ...) -> None: ...

class DismissUserTasksResponse(_message.Message):
    __slots__ = ("ok", "dismissed", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    DISMISSED_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    dismissed: int
    error: str
    def __init__(self, ok: bool = ..., dismissed: _Optional[int] = ..., error: _Optional[str] = ...) -> None: ...
