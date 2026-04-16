from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ClientsProjectsRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class ClientsProjectsResponse(_message.Message):
    __slots__ = ("clients",)
    CLIENTS_FIELD_NUMBER: _ClassVar[int]
    clients: _containers.RepeatedCompositeFieldContainer[ClientWithProjects]
    def __init__(self, clients: _Optional[_Iterable[_Union[ClientWithProjects, _Mapping]]] = ...) -> None: ...

class ClientWithProjects(_message.Message):
    __slots__ = ("id", "name", "projects")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    PROJECTS_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    projects: _containers.RepeatedCompositeFieldContainer[ProjectLite]
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., projects: _Optional[_Iterable[_Union[ProjectLite, _Mapping]]] = ...) -> None: ...

class ProjectLite(_message.Message):
    __slots__ = ("id", "name")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ...) -> None: ...

class PendingUserTasksRequest(_message.Message):
    __slots__ = ("ctx", "limit")
    CTX_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    limit: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., limit: _Optional[int] = ...) -> None: ...

class PendingUserTasksResponse(_message.Message):
    __slots__ = ("count", "tasks")
    COUNT_FIELD_NUMBER: _ClassVar[int]
    TASKS_FIELD_NUMBER: _ClassVar[int]
    count: int
    tasks: _containers.RepeatedCompositeFieldContainer[PendingUserTask]
    def __init__(self, count: _Optional[int] = ..., tasks: _Optional[_Iterable[_Union[PendingUserTask, _Mapping]]] = ...) -> None: ...

class PendingUserTask(_message.Message):
    __slots__ = ("id", "title", "question", "client_id", "project_id")
    ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    QUESTION_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    id: str
    title: str
    question: str
    client_id: str
    project_id: str
    def __init__(self, id: _Optional[str] = ..., title: _Optional[str] = ..., question: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class UnclassifiedCountRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class UnclassifiedCountResponse(_message.Message):
    __slots__ = ("count",)
    COUNT_FIELD_NUMBER: _ClassVar[int]
    count: int
    def __init__(self, count: _Optional[int] = ...) -> None: ...

class UserTimezoneRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class UserTimezoneResponse(_message.Message):
    __slots__ = ("timezone",)
    TIMEZONE_FIELD_NUMBER: _ClassVar[int]
    timezone: str
    def __init__(self, timezone: _Optional[str] = ...) -> None: ...
