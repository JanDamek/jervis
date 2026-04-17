from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
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
    __slots__ = ("ok", "error", "data_json")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    DATA_JSON_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    data_json: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., data_json: _Optional[str] = ...) -> None: ...

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
    __slots__ = ("ok", "error", "data_json")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    DATA_JSON_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    data_json: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., data_json: _Optional[str] = ...) -> None: ...

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
    __slots__ = ("ok", "error", "data_json")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    DATA_JSON_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    data_json: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., data_json: _Optional[str] = ...) -> None: ...
