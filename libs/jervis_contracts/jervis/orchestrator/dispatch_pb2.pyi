from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class QualifyRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "client_id", "project_id", "group_id", "client_name", "project_name", "source_urn", "max_openrouter_tier", "deadline_iso", "priority", "summary", "entities", "suggested_actions", "urgency", "action_type", "estimated_complexity", "is_assigned_to_me", "has_future_deadline", "suggested_deadline", "has_attachments", "attachment_count", "attachments", "suggested_agent", "affected_files", "related_kb_nodes", "chat_topics", "content", "active_tasks", "mentions_jervis")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_NAME_FIELD_NUMBER: _ClassVar[int]
    PROJECT_NAME_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    MAX_OPENROUTER_TIER_FIELD_NUMBER: _ClassVar[int]
    DEADLINE_ISO_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    ENTITIES_FIELD_NUMBER: _ClassVar[int]
    SUGGESTED_ACTIONS_FIELD_NUMBER: _ClassVar[int]
    URGENCY_FIELD_NUMBER: _ClassVar[int]
    ACTION_TYPE_FIELD_NUMBER: _ClassVar[int]
    ESTIMATED_COMPLEXITY_FIELD_NUMBER: _ClassVar[int]
    IS_ASSIGNED_TO_ME_FIELD_NUMBER: _ClassVar[int]
    HAS_FUTURE_DEADLINE_FIELD_NUMBER: _ClassVar[int]
    SUGGESTED_DEADLINE_FIELD_NUMBER: _ClassVar[int]
    HAS_ATTACHMENTS_FIELD_NUMBER: _ClassVar[int]
    ATTACHMENT_COUNT_FIELD_NUMBER: _ClassVar[int]
    ATTACHMENTS_FIELD_NUMBER: _ClassVar[int]
    SUGGESTED_AGENT_FIELD_NUMBER: _ClassVar[int]
    AFFECTED_FILES_FIELD_NUMBER: _ClassVar[int]
    RELATED_KB_NODES_FIELD_NUMBER: _ClassVar[int]
    CHAT_TOPICS_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_TASKS_FIELD_NUMBER: _ClassVar[int]
    MENTIONS_JERVIS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    client_id: str
    project_id: str
    group_id: str
    client_name: str
    project_name: str
    source_urn: str
    max_openrouter_tier: str
    deadline_iso: str
    priority: str
    summary: str
    entities: _containers.RepeatedScalarFieldContainer[str]
    suggested_actions: _containers.RepeatedScalarFieldContainer[str]
    urgency: str
    action_type: str
    estimated_complexity: str
    is_assigned_to_me: bool
    has_future_deadline: bool
    suggested_deadline: str
    has_attachments: bool
    attachment_count: int
    attachments: _containers.RepeatedCompositeFieldContainer[QualifyAttachment]
    suggested_agent: str
    affected_files: _containers.RepeatedScalarFieldContainer[str]
    related_kb_nodes: _containers.RepeatedScalarFieldContainer[str]
    chat_topics: _containers.RepeatedCompositeFieldContainer[QualifyChatTopic]
    content: str
    active_tasks: _containers.RepeatedCompositeFieldContainer[QualifyActiveTask]
    mentions_jervis: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., client_name: _Optional[str] = ..., project_name: _Optional[str] = ..., source_urn: _Optional[str] = ..., max_openrouter_tier: _Optional[str] = ..., deadline_iso: _Optional[str] = ..., priority: _Optional[str] = ..., summary: _Optional[str] = ..., entities: _Optional[_Iterable[str]] = ..., suggested_actions: _Optional[_Iterable[str]] = ..., urgency: _Optional[str] = ..., action_type: _Optional[str] = ..., estimated_complexity: _Optional[str] = ..., is_assigned_to_me: bool = ..., has_future_deadline: bool = ..., suggested_deadline: _Optional[str] = ..., has_attachments: bool = ..., attachment_count: _Optional[int] = ..., attachments: _Optional[_Iterable[_Union[QualifyAttachment, _Mapping]]] = ..., suggested_agent: _Optional[str] = ..., affected_files: _Optional[_Iterable[str]] = ..., related_kb_nodes: _Optional[_Iterable[str]] = ..., chat_topics: _Optional[_Iterable[_Union[QualifyChatTopic, _Mapping]]] = ..., content: _Optional[str] = ..., active_tasks: _Optional[_Iterable[_Union[QualifyActiveTask, _Mapping]]] = ..., mentions_jervis: bool = ...) -> None: ...

class QualifyAttachment(_message.Message):
    __slots__ = ("filename", "content_type", "size", "index")
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    SIZE_FIELD_NUMBER: _ClassVar[int]
    INDEX_FIELD_NUMBER: _ClassVar[int]
    filename: str
    content_type: str
    size: int
    index: int
    def __init__(self, filename: _Optional[str] = ..., content_type: _Optional[str] = ..., size: _Optional[int] = ..., index: _Optional[int] = ...) -> None: ...

class QualifyChatTopic(_message.Message):
    __slots__ = ("role", "content")
    ROLE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    role: str
    content: str
    def __init__(self, role: _Optional[str] = ..., content: _Optional[str] = ...) -> None: ...

class QualifyActiveTask(_message.Message):
    __slots__ = ("task_id", "type", "state", "task_name", "topic_id")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    TASK_NAME_FIELD_NUMBER: _ClassVar[int]
    TOPIC_ID_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    type: str
    state: str
    task_name: str
    topic_id: str
    def __init__(self, task_id: _Optional[str] = ..., type: _Optional[str] = ..., state: _Optional[str] = ..., task_name: _Optional[str] = ..., topic_id: _Optional[str] = ...) -> None: ...

class OrchestrateRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "client_id", "project_id", "group_id", "client_name", "project_name", "group_name", "workspace_path", "query", "agent_preference", "task_name", "rules", "processing_mode", "max_openrouter_tier", "environment", "environment_id", "jervis_project_id", "chat_history", "qualifier_context", "source_urn", "deadline_iso", "priority", "capability", "tier", "min_model_size")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_NAME_FIELD_NUMBER: _ClassVar[int]
    PROJECT_NAME_FIELD_NUMBER: _ClassVar[int]
    GROUP_NAME_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_PATH_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    AGENT_PREFERENCE_FIELD_NUMBER: _ClassVar[int]
    TASK_NAME_FIELD_NUMBER: _ClassVar[int]
    RULES_FIELD_NUMBER: _ClassVar[int]
    PROCESSING_MODE_FIELD_NUMBER: _ClassVar[int]
    MAX_OPENROUTER_TIER_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    JERVIS_PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    CHAT_HISTORY_FIELD_NUMBER: _ClassVar[int]
    QUALIFIER_CONTEXT_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    DEADLINE_ISO_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    CAPABILITY_FIELD_NUMBER: _ClassVar[int]
    TIER_FIELD_NUMBER: _ClassVar[int]
    MIN_MODEL_SIZE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    client_id: str
    project_id: str
    group_id: str
    client_name: str
    project_name: str
    group_name: str
    workspace_path: str
    query: str
    agent_preference: str
    task_name: str
    rules: ProjectRules
    processing_mode: str
    max_openrouter_tier: str
    environment: EnvironmentContext
    environment_id: str
    jervis_project_id: str
    chat_history: ChatHistoryPayload
    qualifier_context: str
    source_urn: str
    deadline_iso: str
    priority: str
    capability: str
    tier: str
    min_model_size: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., group_id: _Optional[str] = ..., client_name: _Optional[str] = ..., project_name: _Optional[str] = ..., group_name: _Optional[str] = ..., workspace_path: _Optional[str] = ..., query: _Optional[str] = ..., agent_preference: _Optional[str] = ..., task_name: _Optional[str] = ..., rules: _Optional[_Union[ProjectRules, _Mapping]] = ..., processing_mode: _Optional[str] = ..., max_openrouter_tier: _Optional[str] = ..., environment: _Optional[_Union[EnvironmentContext, _Mapping]] = ..., environment_id: _Optional[str] = ..., jervis_project_id: _Optional[str] = ..., chat_history: _Optional[_Union[ChatHistoryPayload, _Mapping]] = ..., qualifier_context: _Optional[str] = ..., source_urn: _Optional[str] = ..., deadline_iso: _Optional[str] = ..., priority: _Optional[str] = ..., capability: _Optional[str] = ..., tier: _Optional[str] = ..., min_model_size: _Optional[int] = ...) -> None: ...

class ProjectRules(_message.Message):
    __slots__ = ("branch_naming", "commit_prefix", "require_review", "require_tests", "require_approval_commit", "require_approval_push", "allowed_branches", "forbidden_files", "max_changed_files", "auto_push", "auto_use_anthropic", "auto_use_openai", "auto_use_gemini", "max_openrouter_tier", "git_author_name", "git_author_email", "git_committer_name", "git_committer_email", "git_gpg_sign", "git_gpg_key_id", "git_message_pattern")
    BRANCH_NAMING_FIELD_NUMBER: _ClassVar[int]
    COMMIT_PREFIX_FIELD_NUMBER: _ClassVar[int]
    REQUIRE_REVIEW_FIELD_NUMBER: _ClassVar[int]
    REQUIRE_TESTS_FIELD_NUMBER: _ClassVar[int]
    REQUIRE_APPROVAL_COMMIT_FIELD_NUMBER: _ClassVar[int]
    REQUIRE_APPROVAL_PUSH_FIELD_NUMBER: _ClassVar[int]
    ALLOWED_BRANCHES_FIELD_NUMBER: _ClassVar[int]
    FORBIDDEN_FILES_FIELD_NUMBER: _ClassVar[int]
    MAX_CHANGED_FILES_FIELD_NUMBER: _ClassVar[int]
    AUTO_PUSH_FIELD_NUMBER: _ClassVar[int]
    AUTO_USE_ANTHROPIC_FIELD_NUMBER: _ClassVar[int]
    AUTO_USE_OPENAI_FIELD_NUMBER: _ClassVar[int]
    AUTO_USE_GEMINI_FIELD_NUMBER: _ClassVar[int]
    MAX_OPENROUTER_TIER_FIELD_NUMBER: _ClassVar[int]
    GIT_AUTHOR_NAME_FIELD_NUMBER: _ClassVar[int]
    GIT_AUTHOR_EMAIL_FIELD_NUMBER: _ClassVar[int]
    GIT_COMMITTER_NAME_FIELD_NUMBER: _ClassVar[int]
    GIT_COMMITTER_EMAIL_FIELD_NUMBER: _ClassVar[int]
    GIT_GPG_SIGN_FIELD_NUMBER: _ClassVar[int]
    GIT_GPG_KEY_ID_FIELD_NUMBER: _ClassVar[int]
    GIT_MESSAGE_PATTERN_FIELD_NUMBER: _ClassVar[int]
    branch_naming: str
    commit_prefix: str
    require_review: bool
    require_tests: bool
    require_approval_commit: bool
    require_approval_push: bool
    allowed_branches: _containers.RepeatedScalarFieldContainer[str]
    forbidden_files: _containers.RepeatedScalarFieldContainer[str]
    max_changed_files: int
    auto_push: bool
    auto_use_anthropic: bool
    auto_use_openai: bool
    auto_use_gemini: bool
    max_openrouter_tier: str
    git_author_name: str
    git_author_email: str
    git_committer_name: str
    git_committer_email: str
    git_gpg_sign: bool
    git_gpg_key_id: str
    git_message_pattern: str
    def __init__(self, branch_naming: _Optional[str] = ..., commit_prefix: _Optional[str] = ..., require_review: bool = ..., require_tests: bool = ..., require_approval_commit: bool = ..., require_approval_push: bool = ..., allowed_branches: _Optional[_Iterable[str]] = ..., forbidden_files: _Optional[_Iterable[str]] = ..., max_changed_files: _Optional[int] = ..., auto_push: bool = ..., auto_use_anthropic: bool = ..., auto_use_openai: bool = ..., auto_use_gemini: bool = ..., max_openrouter_tier: _Optional[str] = ..., git_author_name: _Optional[str] = ..., git_author_email: _Optional[str] = ..., git_committer_name: _Optional[str] = ..., git_committer_email: _Optional[str] = ..., git_gpg_sign: bool = ..., git_gpg_key_id: _Optional[str] = ..., git_message_pattern: _Optional[str] = ...) -> None: ...

class EnvironmentContext(_message.Message):
    __slots__ = ("id", "namespace", "tier", "state", "group_id", "agent_instructions", "components", "component_links")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    TIER_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    AGENT_INSTRUCTIONS_FIELD_NUMBER: _ClassVar[int]
    COMPONENTS_FIELD_NUMBER: _ClassVar[int]
    COMPONENT_LINKS_FIELD_NUMBER: _ClassVar[int]
    id: str
    namespace: str
    tier: str
    state: str
    group_id: str
    agent_instructions: str
    components: _containers.RepeatedCompositeFieldContainer[EnvironmentComponent]
    component_links: _containers.RepeatedCompositeFieldContainer[EnvironmentComponentLink]
    def __init__(self, id: _Optional[str] = ..., namespace: _Optional[str] = ..., tier: _Optional[str] = ..., state: _Optional[str] = ..., group_id: _Optional[str] = ..., agent_instructions: _Optional[str] = ..., components: _Optional[_Iterable[_Union[EnvironmentComponent, _Mapping]]] = ..., component_links: _Optional[_Iterable[_Union[EnvironmentComponentLink, _Mapping]]] = ...) -> None: ...

class EnvironmentComponent(_message.Message):
    __slots__ = ("id", "name", "type", "image", "project_id", "host", "ports", "env_vars", "auto_start", "start_order", "source_repo", "source_branch", "dockerfile_path", "component_state")
    class EnvVarsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    IMAGE_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    HOST_FIELD_NUMBER: _ClassVar[int]
    PORTS_FIELD_NUMBER: _ClassVar[int]
    ENV_VARS_FIELD_NUMBER: _ClassVar[int]
    AUTO_START_FIELD_NUMBER: _ClassVar[int]
    START_ORDER_FIELD_NUMBER: _ClassVar[int]
    SOURCE_REPO_FIELD_NUMBER: _ClassVar[int]
    SOURCE_BRANCH_FIELD_NUMBER: _ClassVar[int]
    DOCKERFILE_PATH_FIELD_NUMBER: _ClassVar[int]
    COMPONENT_STATE_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    type: str
    image: str
    project_id: str
    host: str
    ports: _containers.RepeatedCompositeFieldContainer[ComponentPort]
    env_vars: _containers.ScalarMap[str, str]
    auto_start: bool
    start_order: int
    source_repo: str
    source_branch: str
    dockerfile_path: str
    component_state: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., type: _Optional[str] = ..., image: _Optional[str] = ..., project_id: _Optional[str] = ..., host: _Optional[str] = ..., ports: _Optional[_Iterable[_Union[ComponentPort, _Mapping]]] = ..., env_vars: _Optional[_Mapping[str, str]] = ..., auto_start: bool = ..., start_order: _Optional[int] = ..., source_repo: _Optional[str] = ..., source_branch: _Optional[str] = ..., dockerfile_path: _Optional[str] = ..., component_state: _Optional[str] = ...) -> None: ...

class ComponentPort(_message.Message):
    __slots__ = ("container", "service", "name")
    CONTAINER_FIELD_NUMBER: _ClassVar[int]
    SERVICE_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    container: int
    service: int
    name: str
    def __init__(self, container: _Optional[int] = ..., service: _Optional[int] = ..., name: _Optional[str] = ...) -> None: ...

class EnvironmentComponentLink(_message.Message):
    __slots__ = ("source", "target", "description")
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    TARGET_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    source: str
    target: str
    description: str
    def __init__(self, source: _Optional[str] = ..., target: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class ChatHistoryPayload(_message.Message):
    __slots__ = ("recent_messages", "summary_blocks", "total_message_count")
    RECENT_MESSAGES_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_BLOCKS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_MESSAGE_COUNT_FIELD_NUMBER: _ClassVar[int]
    recent_messages: _containers.RepeatedCompositeFieldContainer[ChatHistoryMessage]
    summary_blocks: _containers.RepeatedCompositeFieldContainer[ChatSummaryBlock]
    total_message_count: int
    def __init__(self, recent_messages: _Optional[_Iterable[_Union[ChatHistoryMessage, _Mapping]]] = ..., summary_blocks: _Optional[_Iterable[_Union[ChatSummaryBlock, _Mapping]]] = ..., total_message_count: _Optional[int] = ...) -> None: ...

class ChatHistoryMessage(_message.Message):
    __slots__ = ("role", "content", "timestamp", "sequence")
    ROLE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    role: str
    content: str
    timestamp: str
    sequence: int
    def __init__(self, role: _Optional[str] = ..., content: _Optional[str] = ..., timestamp: _Optional[str] = ..., sequence: _Optional[int] = ...) -> None: ...

class ChatSummaryBlock(_message.Message):
    __slots__ = ("sequence_range", "summary", "key_decisions", "topics", "is_checkpoint", "checkpoint_reason")
    SEQUENCE_RANGE_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    KEY_DECISIONS_FIELD_NUMBER: _ClassVar[int]
    TOPICS_FIELD_NUMBER: _ClassVar[int]
    IS_CHECKPOINT_FIELD_NUMBER: _ClassVar[int]
    CHECKPOINT_REASON_FIELD_NUMBER: _ClassVar[int]
    sequence_range: str
    summary: str
    key_decisions: _containers.RepeatedScalarFieldContainer[str]
    topics: _containers.RepeatedScalarFieldContainer[str]
    is_checkpoint: bool
    checkpoint_reason: str
    def __init__(self, sequence_range: _Optional[str] = ..., summary: _Optional[str] = ..., key_decisions: _Optional[_Iterable[str]] = ..., topics: _Optional[_Iterable[str]] = ..., is_checkpoint: bool = ..., checkpoint_reason: _Optional[str] = ...) -> None: ...

class DispatchAck(_message.Message):
    __slots__ = ("status", "thread_id", "detail")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    THREAD_ID_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    status: str
    thread_id: str
    detail: str
    def __init__(self, status: _Optional[str] = ..., thread_id: _Optional[str] = ..., detail: _Optional[str] = ...) -> None: ...
