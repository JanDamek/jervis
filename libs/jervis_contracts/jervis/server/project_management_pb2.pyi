from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ListClientsRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class Client(_message.Message):
    __slots__ = ("id", "name", "description", "archived", "default_language")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    ARCHIVED_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    description: str
    archived: bool
    default_language: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., description: _Optional[str] = ..., archived: bool = ..., default_language: _Optional[str] = ...) -> None: ...

class ClientList(_message.Message):
    __slots__ = ("items",)
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[Client]
    def __init__(self, items: _Optional[_Iterable[_Union[Client, _Mapping]]] = ...) -> None: ...

class CreateClientRequest(_message.Message):
    __slots__ = ("ctx", "name", "description")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    name: str
    description: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., name: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class CreateClientResponse(_message.Message):
    __slots__ = ("id", "name")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ...) -> None: ...

class ListProjectsRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class Project(_message.Message):
    __slots__ = ("id", "name", "client_id", "group_id", "description")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    GROUP_ID_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    client_id: str
    group_id: str
    description: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., client_id: _Optional[str] = ..., group_id: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class ProjectList(_message.Message):
    __slots__ = ("items",)
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[Project]
    def __init__(self, items: _Optional[_Iterable[_Union[Project, _Mapping]]] = ...) -> None: ...

class CreateProjectRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "name", "description")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    name: str
    description: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., name: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class CreateProjectResponse(_message.Message):
    __slots__ = ("id", "name", "client_id")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    client_id: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class UpdateProjectRequest(_message.Message):
    __slots__ = ("ctx", "project_id", "description", "git_remote_url", "connection_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    GIT_REMOTE_URL_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    project_id: str
    description: str
    git_remote_url: str
    connection_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., project_id: _Optional[str] = ..., description: _Optional[str] = ..., git_remote_url: _Optional[str] = ..., connection_id: _Optional[str] = ...) -> None: ...

class ListConnectionsRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class ConnectionSummary(_message.Message):
    __slots__ = ("id", "name", "provider", "state", "base_url", "capabilities")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    PROVIDER_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    BASE_URL_FIELD_NUMBER: _ClassVar[int]
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    provider: str
    state: str
    base_url: str
    capabilities: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., provider: _Optional[str] = ..., state: _Optional[str] = ..., base_url: _Optional[str] = ..., capabilities: _Optional[_Iterable[str]] = ...) -> None: ...

class ConnectionSummaryList(_message.Message):
    __slots__ = ("items",)
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[ConnectionSummary]
    def __init__(self, items: _Optional[_Iterable[_Union[ConnectionSummary, _Mapping]]] = ...) -> None: ...

class CreateConnectionRequest(_message.Message):
    __slots__ = ("ctx", "name", "provider", "protocol", "auth_type", "base_url", "is_cloud", "bearer_token", "username", "password", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    PROVIDER_FIELD_NUMBER: _ClassVar[int]
    PROTOCOL_FIELD_NUMBER: _ClassVar[int]
    AUTH_TYPE_FIELD_NUMBER: _ClassVar[int]
    BASE_URL_FIELD_NUMBER: _ClassVar[int]
    IS_CLOUD_FIELD_NUMBER: _ClassVar[int]
    BEARER_TOKEN_FIELD_NUMBER: _ClassVar[int]
    USERNAME_FIELD_NUMBER: _ClassVar[int]
    PASSWORD_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    name: str
    provider: str
    protocol: str
    auth_type: str
    base_url: str
    is_cloud: bool
    bearer_token: str
    username: str
    password: str
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., name: _Optional[str] = ..., provider: _Optional[str] = ..., protocol: _Optional[str] = ..., auth_type: _Optional[str] = ..., base_url: _Optional[str] = ..., is_cloud: bool = ..., bearer_token: _Optional[str] = ..., username: _Optional[str] = ..., password: _Optional[str] = ..., client_id: _Optional[str] = ...) -> None: ...

class CreateConnectionResponse(_message.Message):
    __slots__ = ("id", "name", "provider", "state")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    PROVIDER_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    provider: str
    state: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., provider: _Optional[str] = ..., state: _Optional[str] = ...) -> None: ...

class GetStackRecommendationsRequest(_message.Message):
    __slots__ = ("ctx", "requirements")
    CTX_FIELD_NUMBER: _ClassVar[int]
    REQUIREMENTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    requirements: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., requirements: _Optional[str] = ...) -> None: ...

class ProjectRecommendations(_message.Message):
    __slots__ = ("archetype", "platforms", "storage", "features", "scaffolding_instructions")
    ARCHETYPE_FIELD_NUMBER: _ClassVar[int]
    PLATFORMS_FIELD_NUMBER: _ClassVar[int]
    STORAGE_FIELD_NUMBER: _ClassVar[int]
    FEATURES_FIELD_NUMBER: _ClassVar[int]
    SCAFFOLDING_INSTRUCTIONS_FIELD_NUMBER: _ClassVar[int]
    archetype: StackArchetype
    platforms: _containers.RepeatedCompositeFieldContainer[PlatformRecommendation]
    storage: _containers.RepeatedCompositeFieldContainer[StorageRecommendation]
    features: _containers.RepeatedCompositeFieldContainer[FeatureRecommendation]
    scaffolding_instructions: str
    def __init__(self, archetype: _Optional[_Union[StackArchetype, _Mapping]] = ..., platforms: _Optional[_Iterable[_Union[PlatformRecommendation, _Mapping]]] = ..., storage: _Optional[_Iterable[_Union[StorageRecommendation, _Mapping]]] = ..., features: _Optional[_Iterable[_Union[FeatureRecommendation, _Mapping]]] = ..., scaffolding_instructions: _Optional[str] = ...) -> None: ...

class StackArchetype(_message.Message):
    __slots__ = ("type", "name", "description", "pros", "cons", "best_for")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    PROS_FIELD_NUMBER: _ClassVar[int]
    CONS_FIELD_NUMBER: _ClassVar[int]
    BEST_FOR_FIELD_NUMBER: _ClassVar[int]
    type: str
    name: str
    description: str
    pros: _containers.RepeatedScalarFieldContainer[str]
    cons: _containers.RepeatedScalarFieldContainer[str]
    best_for: str
    def __init__(self, type: _Optional[str] = ..., name: _Optional[str] = ..., description: _Optional[str] = ..., pros: _Optional[_Iterable[str]] = ..., cons: _Optional[_Iterable[str]] = ..., best_for: _Optional[str] = ...) -> None: ...

class PlatformRecommendation(_message.Message):
    __slots__ = ("platform", "recommended", "rationale", "alternatives")
    PLATFORM_FIELD_NUMBER: _ClassVar[int]
    RECOMMENDED_FIELD_NUMBER: _ClassVar[int]
    RATIONALE_FIELD_NUMBER: _ClassVar[int]
    ALTERNATIVES_FIELD_NUMBER: _ClassVar[int]
    platform: str
    recommended: bool
    rationale: str
    alternatives: _containers.RepeatedCompositeFieldContainer[Alternative]
    def __init__(self, platform: _Optional[str] = ..., recommended: bool = ..., rationale: _Optional[str] = ..., alternatives: _Optional[_Iterable[_Union[Alternative, _Mapping]]] = ...) -> None: ...

class StorageRecommendation(_message.Message):
    __slots__ = ("technology", "recommended", "use_case", "spring_dependency", "pros", "cons")
    TECHNOLOGY_FIELD_NUMBER: _ClassVar[int]
    RECOMMENDED_FIELD_NUMBER: _ClassVar[int]
    USE_CASE_FIELD_NUMBER: _ClassVar[int]
    SPRING_DEPENDENCY_FIELD_NUMBER: _ClassVar[int]
    PROS_FIELD_NUMBER: _ClassVar[int]
    CONS_FIELD_NUMBER: _ClassVar[int]
    technology: str
    recommended: bool
    use_case: str
    spring_dependency: str
    pros: _containers.RepeatedScalarFieldContainer[str]
    cons: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, technology: _Optional[str] = ..., recommended: bool = ..., use_case: _Optional[str] = ..., spring_dependency: _Optional[str] = ..., pros: _Optional[_Iterable[str]] = ..., cons: _Optional[_Iterable[str]] = ...) -> None: ...

class FeatureRecommendation(_message.Message):
    __slots__ = ("feature", "recommended", "options")
    FEATURE_FIELD_NUMBER: _ClassVar[int]
    RECOMMENDED_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    feature: str
    recommended: bool
    options: _containers.RepeatedCompositeFieldContainer[Alternative]
    def __init__(self, feature: _Optional[str] = ..., recommended: bool = ..., options: _Optional[_Iterable[_Union[Alternative, _Mapping]]] = ...) -> None: ...

class Alternative(_message.Message):
    __slots__ = ("name", "description")
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    name: str
    description: str
    def __init__(self, name: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...
