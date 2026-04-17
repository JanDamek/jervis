from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class EnvironmentResponse(_message.Message):
    __slots__ = ("body_json",)
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    body_json: str
    def __init__(self, body_json: _Optional[str] = ...) -> None: ...

class EnvironmentListResponse(_message.Message):
    __slots__ = ("items_json",)
    ITEMS_JSON_FIELD_NUMBER: _ClassVar[int]
    items_json: str
    def __init__(self, items_json: _Optional[str] = ...) -> None: ...

class EnvironmentStatusResponse(_message.Message):
    __slots__ = ("body_json",)
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    body_json: str
    def __init__(self, body_json: _Optional[str] = ...) -> None: ...

class DeleteEnvironmentResponse(_message.Message):
    __slots__ = ("ok",)
    OK_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    def __init__(self, ok: bool = ...) -> None: ...

class ListEnvironmentsRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class GetEnvironmentRequest(_message.Message):
    __slots__ = ("ctx", "environment_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    environment_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., environment_id: _Optional[str] = ...) -> None: ...

class EnvironmentIdRequest(_message.Message):
    __slots__ = ("ctx", "environment_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    environment_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., environment_id: _Optional[str] = ...) -> None: ...

class CreateEnvironmentRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "name", "namespace", "tier", "description", "agent_instructions", "storage_size_gi")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    TIER_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    AGENT_INSTRUCTIONS_FIELD_NUMBER: _ClassVar[int]
    STORAGE_SIZE_GI_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    name: str
    namespace: str
    tier: str
    description: str
    agent_instructions: str
    storage_size_gi: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., name: _Optional[str] = ..., namespace: _Optional[str] = ..., tier: _Optional[str] = ..., description: _Optional[str] = ..., agent_instructions: _Optional[str] = ..., storage_size_gi: _Optional[int] = ...) -> None: ...

class DeleteEnvironmentRequest(_message.Message):
    __slots__ = ("ctx", "environment_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    environment_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., environment_id: _Optional[str] = ...) -> None: ...

class AddComponentRequest(_message.Message):
    __slots__ = ("ctx", "environment_id", "name", "type", "image", "version", "env_vars", "source_repo", "source_branch", "dockerfile_path", "start_order", "start_order_auto")
    class EnvVarsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    IMAGE_FIELD_NUMBER: _ClassVar[int]
    VERSION_FIELD_NUMBER: _ClassVar[int]
    ENV_VARS_FIELD_NUMBER: _ClassVar[int]
    SOURCE_REPO_FIELD_NUMBER: _ClassVar[int]
    SOURCE_BRANCH_FIELD_NUMBER: _ClassVar[int]
    DOCKERFILE_PATH_FIELD_NUMBER: _ClassVar[int]
    START_ORDER_FIELD_NUMBER: _ClassVar[int]
    START_ORDER_AUTO_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    environment_id: str
    name: str
    type: str
    image: str
    version: str
    env_vars: _containers.ScalarMap[str, str]
    source_repo: str
    source_branch: str
    dockerfile_path: str
    start_order: int
    start_order_auto: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., environment_id: _Optional[str] = ..., name: _Optional[str] = ..., type: _Optional[str] = ..., image: _Optional[str] = ..., version: _Optional[str] = ..., env_vars: _Optional[_Mapping[str, str]] = ..., source_repo: _Optional[str] = ..., source_branch: _Optional[str] = ..., dockerfile_path: _Optional[str] = ..., start_order: _Optional[int] = ..., start_order_auto: bool = ...) -> None: ...

class ConfigureComponentRequest(_message.Message):
    __slots__ = ("ctx", "environment_id", "component_name", "image", "env_vars", "has_env_vars", "cpu_limit", "memory_limit", "source_repo", "source_branch", "dockerfile_path")
    class EnvVarsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    COMPONENT_NAME_FIELD_NUMBER: _ClassVar[int]
    IMAGE_FIELD_NUMBER: _ClassVar[int]
    ENV_VARS_FIELD_NUMBER: _ClassVar[int]
    HAS_ENV_VARS_FIELD_NUMBER: _ClassVar[int]
    CPU_LIMIT_FIELD_NUMBER: _ClassVar[int]
    MEMORY_LIMIT_FIELD_NUMBER: _ClassVar[int]
    SOURCE_REPO_FIELD_NUMBER: _ClassVar[int]
    SOURCE_BRANCH_FIELD_NUMBER: _ClassVar[int]
    DOCKERFILE_PATH_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    environment_id: str
    component_name: str
    image: str
    env_vars: _containers.ScalarMap[str, str]
    has_env_vars: bool
    cpu_limit: str
    memory_limit: str
    source_repo: str
    source_branch: str
    dockerfile_path: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., environment_id: _Optional[str] = ..., component_name: _Optional[str] = ..., image: _Optional[str] = ..., env_vars: _Optional[_Mapping[str, str]] = ..., has_env_vars: bool = ..., cpu_limit: _Optional[str] = ..., memory_limit: _Optional[str] = ..., source_repo: _Optional[str] = ..., source_branch: _Optional[str] = ..., dockerfile_path: _Optional[str] = ...) -> None: ...

class CloneEnvironmentRequest(_message.Message):
    __slots__ = ("ctx", "environment_id", "new_name", "new_namespace", "new_tier", "target_client_id", "target_group_id", "target_project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    NEW_NAME_FIELD_NUMBER: _ClassVar[int]
    NEW_NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    NEW_TIER_FIELD_NUMBER: _ClassVar[int]
    TARGET_CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    TARGET_GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    TARGET_PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    environment_id: str
    new_name: str
    new_namespace: str
    new_tier: str
    target_client_id: str
    target_group_id: str
    target_project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., environment_id: _Optional[str] = ..., new_name: _Optional[str] = ..., new_namespace: _Optional[str] = ..., new_tier: _Optional[str] = ..., target_client_id: _Optional[str] = ..., target_group_id: _Optional[str] = ..., target_project_id: _Optional[str] = ...) -> None: ...

class ReplicateEnvironmentRequest(_message.Message):
    __slots__ = ("ctx", "environment_id", "new_name", "new_namespace", "new_tier", "target_client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    NEW_NAME_FIELD_NUMBER: _ClassVar[int]
    NEW_NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    NEW_TIER_FIELD_NUMBER: _ClassVar[int]
    TARGET_CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    environment_id: str
    new_name: str
    new_namespace: str
    new_tier: str
    target_client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., environment_id: _Optional[str] = ..., new_name: _Optional[str] = ..., new_namespace: _Optional[str] = ..., new_tier: _Optional[str] = ..., target_client_id: _Optional[str] = ...) -> None: ...

class DiscoverNamespaceRequest(_message.Message):
    __slots__ = ("ctx", "namespace", "client_id", "name", "tier")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    TIER_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    namespace: str
    client_id: str
    name: str
    tier: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., namespace: _Optional[str] = ..., client_id: _Optional[str] = ..., name: _Optional[str] = ..., tier: _Optional[str] = ...) -> None: ...

class AddPropertyMappingRequest(_message.Message):
    __slots__ = ("ctx", "environment_id", "project_component_id", "property_name", "target_component_id", "value_template")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_COMPONENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROPERTY_NAME_FIELD_NUMBER: _ClassVar[int]
    TARGET_COMPONENT_ID_FIELD_NUMBER: _ClassVar[int]
    VALUE_TEMPLATE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    environment_id: str
    project_component_id: str
    property_name: str
    target_component_id: str
    value_template: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., environment_id: _Optional[str] = ..., project_component_id: _Optional[str] = ..., property_name: _Optional[str] = ..., target_component_id: _Optional[str] = ..., value_template: _Optional[str] = ...) -> None: ...

class AutoSuggestPropertyMappingsResponse(_message.Message):
    __slots__ = ("body_json", "mappings_added")
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    MAPPINGS_ADDED_FIELD_NUMBER: _ClassVar[int]
    body_json: str
    mappings_added: int
    def __init__(self, body_json: _Optional[str] = ..., mappings_added: _Optional[int] = ...) -> None: ...

class ListComponentTemplatesRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class ListComponentTemplatesResponse(_message.Message):
    __slots__ = ("items_json",)
    ITEMS_JSON_FIELD_NUMBER: _ClassVar[int]
    items_json: str
    def __init__(self, items_json: _Optional[str] = ...) -> None: ...
