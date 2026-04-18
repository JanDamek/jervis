from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ListNamespaceResourcesRequest(_message.Message):
    __slots__ = ("ctx", "namespace", "type")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    namespace: str
    type: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., namespace: _Optional[str] = ..., type: _Optional[str] = ...) -> None: ...

class ListNamespaceResourcesResponse(_message.Message):
    __slots__ = ("ok", "error", "data")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    data: K8sResourceList
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., data: _Optional[_Union[K8sResourceList, _Mapping]] = ...) -> None: ...

class K8sResourceList(_message.Message):
    __slots__ = ("pods", "deployments", "services")
    PODS_FIELD_NUMBER: _ClassVar[int]
    DEPLOYMENTS_FIELD_NUMBER: _ClassVar[int]
    SERVICES_FIELD_NUMBER: _ClassVar[int]
    pods: _containers.RepeatedCompositeFieldContainer[K8sPod]
    deployments: _containers.RepeatedCompositeFieldContainer[K8sDeployment]
    services: _containers.RepeatedCompositeFieldContainer[K8sService]
    def __init__(self, pods: _Optional[_Iterable[_Union[K8sPod, _Mapping]]] = ..., deployments: _Optional[_Iterable[_Union[K8sDeployment, _Mapping]]] = ..., services: _Optional[_Iterable[_Union[K8sService, _Mapping]]] = ...) -> None: ...

class K8sPod(_message.Message):
    __slots__ = ("name", "phase", "ready", "restart_count", "created_at")
    NAME_FIELD_NUMBER: _ClassVar[int]
    PHASE_FIELD_NUMBER: _ClassVar[int]
    READY_FIELD_NUMBER: _ClassVar[int]
    RESTART_COUNT_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    name: str
    phase: str
    ready: bool
    restart_count: int
    created_at: str
    def __init__(self, name: _Optional[str] = ..., phase: _Optional[str] = ..., ready: bool = ..., restart_count: _Optional[int] = ..., created_at: _Optional[str] = ...) -> None: ...

class K8sDeployment(_message.Message):
    __slots__ = ("name", "replicas", "available_replicas", "ready", "image", "created_at")
    NAME_FIELD_NUMBER: _ClassVar[int]
    REPLICAS_FIELD_NUMBER: _ClassVar[int]
    AVAILABLE_REPLICAS_FIELD_NUMBER: _ClassVar[int]
    READY_FIELD_NUMBER: _ClassVar[int]
    IMAGE_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    name: str
    replicas: int
    available_replicas: int
    ready: bool
    image: str
    created_at: str
    def __init__(self, name: _Optional[str] = ..., replicas: _Optional[int] = ..., available_replicas: _Optional[int] = ..., ready: bool = ..., image: _Optional[str] = ..., created_at: _Optional[str] = ...) -> None: ...

class K8sService(_message.Message):
    __slots__ = ("name", "type", "cluster_ip", "ports", "created_at")
    NAME_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    CLUSTER_IP_FIELD_NUMBER: _ClassVar[int]
    PORTS_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    name: str
    type: str
    cluster_ip: str
    ports: _containers.RepeatedScalarFieldContainer[str]
    created_at: str
    def __init__(self, name: _Optional[str] = ..., type: _Optional[str] = ..., cluster_ip: _Optional[str] = ..., ports: _Optional[_Iterable[str]] = ..., created_at: _Optional[str] = ...) -> None: ...

class GetPodLogsRequest(_message.Message):
    __slots__ = ("ctx", "namespace", "pod_name", "tail_lines")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    POD_NAME_FIELD_NUMBER: _ClassVar[int]
    TAIL_LINES_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    namespace: str
    pod_name: str
    tail_lines: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., namespace: _Optional[str] = ..., pod_name: _Optional[str] = ..., tail_lines: _Optional[int] = ...) -> None: ...

class GetPodLogsResponse(_message.Message):
    __slots__ = ("ok", "error", "logs")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    LOGS_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    logs: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., logs: _Optional[str] = ...) -> None: ...

class GetDeploymentStatusRequest(_message.Message):
    __slots__ = ("ctx", "namespace", "deployment_name")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    DEPLOYMENT_NAME_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    namespace: str
    deployment_name: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., namespace: _Optional[str] = ..., deployment_name: _Optional[str] = ...) -> None: ...

class GetDeploymentStatusResponse(_message.Message):
    __slots__ = ("ok", "error", "data")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    data: K8sDeploymentDetail
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., data: _Optional[_Union[K8sDeploymentDetail, _Mapping]] = ...) -> None: ...

class K8sDeploymentDetail(_message.Message):
    __slots__ = ("name", "namespace", "replicas", "available_replicas", "ready", "image", "created_at", "conditions", "events")
    NAME_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    REPLICAS_FIELD_NUMBER: _ClassVar[int]
    AVAILABLE_REPLICAS_FIELD_NUMBER: _ClassVar[int]
    READY_FIELD_NUMBER: _ClassVar[int]
    IMAGE_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    CONDITIONS_FIELD_NUMBER: _ClassVar[int]
    EVENTS_FIELD_NUMBER: _ClassVar[int]
    name: str
    namespace: str
    replicas: int
    available_replicas: int
    ready: bool
    image: str
    created_at: str
    conditions: _containers.RepeatedCompositeFieldContainer[K8sCondition]
    events: _containers.RepeatedCompositeFieldContainer[K8sEvent]
    def __init__(self, name: _Optional[str] = ..., namespace: _Optional[str] = ..., replicas: _Optional[int] = ..., available_replicas: _Optional[int] = ..., ready: bool = ..., image: _Optional[str] = ..., created_at: _Optional[str] = ..., conditions: _Optional[_Iterable[_Union[K8sCondition, _Mapping]]] = ..., events: _Optional[_Iterable[_Union[K8sEvent, _Mapping]]] = ...) -> None: ...

class K8sCondition(_message.Message):
    __slots__ = ("type", "status", "reason", "message", "last_transition_time")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    LAST_TRANSITION_TIME_FIELD_NUMBER: _ClassVar[int]
    type: str
    status: str
    reason: str
    message: str
    last_transition_time: str
    def __init__(self, type: _Optional[str] = ..., status: _Optional[str] = ..., reason: _Optional[str] = ..., message: _Optional[str] = ..., last_transition_time: _Optional[str] = ...) -> None: ...

class K8sEvent(_message.Message):
    __slots__ = ("type", "reason", "message", "time")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    TIME_FIELD_NUMBER: _ClassVar[int]
    type: str
    reason: str
    message: str
    time: str
    def __init__(self, type: _Optional[str] = ..., reason: _Optional[str] = ..., message: _Optional[str] = ..., time: _Optional[str] = ...) -> None: ...

class ScaleDeploymentRequest(_message.Message):
    __slots__ = ("ctx", "namespace", "deployment_name", "replicas")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    DEPLOYMENT_NAME_FIELD_NUMBER: _ClassVar[int]
    REPLICAS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    namespace: str
    deployment_name: str
    replicas: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., namespace: _Optional[str] = ..., deployment_name: _Optional[str] = ..., replicas: _Optional[int] = ...) -> None: ...

class ScaleDeploymentResponse(_message.Message):
    __slots__ = ("ok", "error", "message")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    message: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., message: _Optional[str] = ...) -> None: ...

class RestartDeploymentRequest(_message.Message):
    __slots__ = ("ctx", "namespace", "deployment_name")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    DEPLOYMENT_NAME_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    namespace: str
    deployment_name: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., namespace: _Optional[str] = ..., deployment_name: _Optional[str] = ...) -> None: ...

class RestartDeploymentResponse(_message.Message):
    __slots__ = ("ok", "error", "message")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    message: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., message: _Optional[str] = ...) -> None: ...

class GetNamespaceStatusRequest(_message.Message):
    __slots__ = ("ctx", "namespace")
    CTX_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    namespace: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., namespace: _Optional[str] = ...) -> None: ...

class GetNamespaceStatusResponse(_message.Message):
    __slots__ = ("ok", "error", "data")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    data: K8sNamespaceStatus
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., data: _Optional[_Union[K8sNamespaceStatus, _Mapping]] = ...) -> None: ...

class K8sNamespaceStatus(_message.Message):
    __slots__ = ("namespace", "healthy", "total_pods", "running_pods", "crashing_pods", "total_deployments", "ready_deployments", "total_services")
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    HEALTHY_FIELD_NUMBER: _ClassVar[int]
    TOTAL_PODS_FIELD_NUMBER: _ClassVar[int]
    RUNNING_PODS_FIELD_NUMBER: _ClassVar[int]
    CRASHING_PODS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_DEPLOYMENTS_FIELD_NUMBER: _ClassVar[int]
    READY_DEPLOYMENTS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_SERVICES_FIELD_NUMBER: _ClassVar[int]
    namespace: str
    healthy: bool
    total_pods: int
    running_pods: int
    crashing_pods: _containers.RepeatedScalarFieldContainer[str]
    total_deployments: int
    ready_deployments: int
    total_services: int
    def __init__(self, namespace: _Optional[str] = ..., healthy: bool = ..., total_pods: _Optional[int] = ..., running_pods: _Optional[int] = ..., crashing_pods: _Optional[_Iterable[str]] = ..., total_deployments: _Optional[int] = ..., ready_deployments: _Optional[int] = ..., total_services: _Optional[int] = ...) -> None: ...
