from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Environment(_message.Message):
    __slots__ = ("id", "client_id", "group_id", "project_id", "name", "description", "tier", "namespace", "components", "component_links", "property_mappings", "agent_instructions", "state", "storage_size_gi", "yaml_manifests")
    class YamlManifestsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    TIER_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    COMPONENTS_FIELD_NUMBER: _ClassVar[int]
    COMPONENT_LINKS_FIELD_NUMBER: _ClassVar[int]
    PROPERTY_MAPPINGS_FIELD_NUMBER: _ClassVar[int]
    AGENT_INSTRUCTIONS_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    STORAGE_SIZE_GI_FIELD_NUMBER: _ClassVar[int]
    YAML_MANIFESTS_FIELD_NUMBER: _ClassVar[int]
    id: str
    client_id: str
    group_id: str
    project_id: str
    name: str
    description: str
    tier: str
    namespace: str
    components: _containers.RepeatedCompositeFieldContainer[EnvironmentComponent]
    component_links: _containers.RepeatedCompositeFieldContainer[ComponentLink]
    property_mappings: _containers.RepeatedCompositeFieldContainer[PropertyMapping]
    agent_instructions: str
    state: str
    storage_size_gi: int
    yaml_manifests: _containers.ScalarMap[str, str]
    def __init__(self, id: _Optional[str] = ..., client_id: _Optional[str] = ..., group_id: _Optional[str] = ..., project_id: _Optional[str] = ..., name: _Optional[str] = ..., description: _Optional[str] = ..., tier: _Optional[str] = ..., namespace: _Optional[str] = ..., components: _Optional[_Iterable[_Union[EnvironmentComponent, _Mapping]]] = ..., component_links: _Optional[_Iterable[_Union[ComponentLink, _Mapping]]] = ..., property_mappings: _Optional[_Iterable[_Union[PropertyMapping, _Mapping]]] = ..., agent_instructions: _Optional[str] = ..., state: _Optional[str] = ..., storage_size_gi: _Optional[int] = ..., yaml_manifests: _Optional[_Mapping[str, str]] = ...) -> None: ...

class EnvironmentComponent(_message.Message):
    __slots__ = ("id", "name", "type", "image", "project_id", "cpu_limit", "memory_limit", "ports", "env_vars", "auto_start", "start_order", "health_check_path", "volume_mount_path", "source_repo", "source_branch", "dockerfile_path", "deployment_yaml", "service_yaml", "config_map_data", "component_state")
    class EnvVarsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    class ConfigMapDataEntry(_message.Message):
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
    CPU_LIMIT_FIELD_NUMBER: _ClassVar[int]
    MEMORY_LIMIT_FIELD_NUMBER: _ClassVar[int]
    PORTS_FIELD_NUMBER: _ClassVar[int]
    ENV_VARS_FIELD_NUMBER: _ClassVar[int]
    AUTO_START_FIELD_NUMBER: _ClassVar[int]
    START_ORDER_FIELD_NUMBER: _ClassVar[int]
    HEALTH_CHECK_PATH_FIELD_NUMBER: _ClassVar[int]
    VOLUME_MOUNT_PATH_FIELD_NUMBER: _ClassVar[int]
    SOURCE_REPO_FIELD_NUMBER: _ClassVar[int]
    SOURCE_BRANCH_FIELD_NUMBER: _ClassVar[int]
    DOCKERFILE_PATH_FIELD_NUMBER: _ClassVar[int]
    DEPLOYMENT_YAML_FIELD_NUMBER: _ClassVar[int]
    SERVICE_YAML_FIELD_NUMBER: _ClassVar[int]
    CONFIG_MAP_DATA_FIELD_NUMBER: _ClassVar[int]
    COMPONENT_STATE_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    type: str
    image: str
    project_id: str
    cpu_limit: str
    memory_limit: str
    ports: _containers.RepeatedCompositeFieldContainer[PortMapping]
    env_vars: _containers.ScalarMap[str, str]
    auto_start: bool
    start_order: int
    health_check_path: str
    volume_mount_path: str
    source_repo: str
    source_branch: str
    dockerfile_path: str
    deployment_yaml: str
    service_yaml: str
    config_map_data: _containers.ScalarMap[str, str]
    component_state: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., type: _Optional[str] = ..., image: _Optional[str] = ..., project_id: _Optional[str] = ..., cpu_limit: _Optional[str] = ..., memory_limit: _Optional[str] = ..., ports: _Optional[_Iterable[_Union[PortMapping, _Mapping]]] = ..., env_vars: _Optional[_Mapping[str, str]] = ..., auto_start: bool = ..., start_order: _Optional[int] = ..., health_check_path: _Optional[str] = ..., volume_mount_path: _Optional[str] = ..., source_repo: _Optional[str] = ..., source_branch: _Optional[str] = ..., dockerfile_path: _Optional[str] = ..., deployment_yaml: _Optional[str] = ..., service_yaml: _Optional[str] = ..., config_map_data: _Optional[_Mapping[str, str]] = ..., component_state: _Optional[str] = ...) -> None: ...

class PortMapping(_message.Message):
    __slots__ = ("container_port", "service_port", "name")
    CONTAINER_PORT_FIELD_NUMBER: _ClassVar[int]
    SERVICE_PORT_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    container_port: int
    service_port: int
    name: str
    def __init__(self, container_port: _Optional[int] = ..., service_port: _Optional[int] = ..., name: _Optional[str] = ...) -> None: ...

class ComponentLink(_message.Message):
    __slots__ = ("source_component_id", "target_component_id", "description")
    SOURCE_COMPONENT_ID_FIELD_NUMBER: _ClassVar[int]
    TARGET_COMPONENT_ID_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    source_component_id: str
    target_component_id: str
    description: str
    def __init__(self, source_component_id: _Optional[str] = ..., target_component_id: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class PropertyMapping(_message.Message):
    __slots__ = ("project_component_id", "property_name", "target_component_id", "value_template", "resolved_value")
    PROJECT_COMPONENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROPERTY_NAME_FIELD_NUMBER: _ClassVar[int]
    TARGET_COMPONENT_ID_FIELD_NUMBER: _ClassVar[int]
    VALUE_TEMPLATE_FIELD_NUMBER: _ClassVar[int]
    RESOLVED_VALUE_FIELD_NUMBER: _ClassVar[int]
    project_component_id: str
    property_name: str
    target_component_id: str
    value_template: str
    resolved_value: str
    def __init__(self, project_component_id: _Optional[str] = ..., property_name: _Optional[str] = ..., target_component_id: _Optional[str] = ..., value_template: _Optional[str] = ..., resolved_value: _Optional[str] = ...) -> None: ...

class EnvironmentList(_message.Message):
    __slots__ = ("items",)
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[Environment]
    def __init__(self, items: _Optional[_Iterable[_Union[Environment, _Mapping]]] = ...) -> None: ...

class EnvironmentStatus(_message.Message):
    __slots__ = ("environment_id", "namespace", "state", "component_statuses")
    ENVIRONMENT_ID_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    COMPONENT_STATUSES_FIELD_NUMBER: _ClassVar[int]
    environment_id: str
    namespace: str
    state: str
    component_statuses: _containers.RepeatedCompositeFieldContainer[ComponentStatus]
    def __init__(self, environment_id: _Optional[str] = ..., namespace: _Optional[str] = ..., state: _Optional[str] = ..., component_statuses: _Optional[_Iterable[_Union[ComponentStatus, _Mapping]]] = ...) -> None: ...

class ComponentStatus(_message.Message):
    __slots__ = ("component_id", "name", "ready", "replicas", "available_replicas", "message")
    COMPONENT_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    READY_FIELD_NUMBER: _ClassVar[int]
    REPLICAS_FIELD_NUMBER: _ClassVar[int]
    AVAILABLE_REPLICAS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    component_id: str
    name: str
    ready: bool
    replicas: int
    available_replicas: int
    message: str
    def __init__(self, component_id: _Optional[str] = ..., name: _Optional[str] = ..., ready: bool = ..., replicas: _Optional[int] = ..., available_replicas: _Optional[int] = ..., message: _Optional[str] = ...) -> None: ...

class ComponentTemplate(_message.Message):
    __slots__ = ("type", "versions", "default_env_vars", "default_ports", "default_volume_mount_path", "property_mapping_templates")
    class DefaultEnvVarsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    TYPE_FIELD_NUMBER: _ClassVar[int]
    VERSIONS_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_ENV_VARS_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_PORTS_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_VOLUME_MOUNT_PATH_FIELD_NUMBER: _ClassVar[int]
    PROPERTY_MAPPING_TEMPLATES_FIELD_NUMBER: _ClassVar[int]
    type: str
    versions: _containers.RepeatedCompositeFieldContainer[ComponentVersion]
    default_env_vars: _containers.ScalarMap[str, str]
    default_ports: _containers.RepeatedCompositeFieldContainer[PortMapping]
    default_volume_mount_path: str
    property_mapping_templates: _containers.RepeatedCompositeFieldContainer[PropertyMappingTemplate]
    def __init__(self, type: _Optional[str] = ..., versions: _Optional[_Iterable[_Union[ComponentVersion, _Mapping]]] = ..., default_env_vars: _Optional[_Mapping[str, str]] = ..., default_ports: _Optional[_Iterable[_Union[PortMapping, _Mapping]]] = ..., default_volume_mount_path: _Optional[str] = ..., property_mapping_templates: _Optional[_Iterable[_Union[PropertyMappingTemplate, _Mapping]]] = ...) -> None: ...

class ComponentVersion(_message.Message):
    __slots__ = ("label", "image")
    LABEL_FIELD_NUMBER: _ClassVar[int]
    IMAGE_FIELD_NUMBER: _ClassVar[int]
    label: str
    image: str
    def __init__(self, label: _Optional[str] = ..., image: _Optional[str] = ...) -> None: ...

class PropertyMappingTemplate(_message.Message):
    __slots__ = ("env_var_name", "value_template", "description")
    ENV_VAR_NAME_FIELD_NUMBER: _ClassVar[int]
    VALUE_TEMPLATE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    env_var_name: str
    value_template: str
    description: str
    def __init__(self, env_var_name: _Optional[str] = ..., value_template: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class ComponentTemplateList(_message.Message):
    __slots__ = ("items",)
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[ComponentTemplate]
    def __init__(self, items: _Optional[_Iterable[_Union[ComponentTemplate, _Mapping]]] = ...) -> None: ...

class DeleteEnvironmentResponse(_message.Message):
    __slots__ = ("ok",)
    OK_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    def __init__(self, ok: bool = ...) -> None: ...

class AutoSuggestPropertyMappingsResponse(_message.Message):
    __slots__ = ("environment", "mappings_added")
    ENVIRONMENT_FIELD_NUMBER: _ClassVar[int]
    MAPPINGS_ADDED_FIELD_NUMBER: _ClassVar[int]
    environment: Environment
    mappings_added: int
    def __init__(self, environment: _Optional[_Union[Environment, _Mapping]] = ..., mappings_added: _Optional[int] = ...) -> None: ...

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

class ListComponentTemplatesRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...
