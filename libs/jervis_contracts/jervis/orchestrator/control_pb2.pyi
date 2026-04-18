from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class HealthRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class HealthResponse(_message.Message):
    __slots__ = ("status", "service", "active_tasks")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SERVICE_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_TASKS_FIELD_NUMBER: _ClassVar[int]
    status: str
    service: str
    active_tasks: int
    def __init__(self, status: _Optional[str] = ..., service: _Optional[str] = ..., active_tasks: _Optional[int] = ...) -> None: ...

class StatusRequest(_message.Message):
    __slots__ = ("ctx", "thread_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    THREAD_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    thread_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., thread_id: _Optional[str] = ...) -> None: ...

class StatusResponse(_message.Message):
    __slots__ = ("status", "thread_id", "interrupt_action", "interrupt_description", "error", "summary", "branch", "artifacts", "keep_environment_running")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    THREAD_ID_FIELD_NUMBER: _ClassVar[int]
    INTERRUPT_ACTION_FIELD_NUMBER: _ClassVar[int]
    INTERRUPT_DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    BRANCH_FIELD_NUMBER: _ClassVar[int]
    ARTIFACTS_FIELD_NUMBER: _ClassVar[int]
    KEEP_ENVIRONMENT_RUNNING_FIELD_NUMBER: _ClassVar[int]
    status: str
    thread_id: str
    interrupt_action: str
    interrupt_description: str
    error: str
    summary: str
    branch: str
    artifacts: _containers.RepeatedScalarFieldContainer[str]
    keep_environment_running: bool
    def __init__(self, status: _Optional[str] = ..., thread_id: _Optional[str] = ..., interrupt_action: _Optional[str] = ..., interrupt_description: _Optional[str] = ..., error: _Optional[str] = ..., summary: _Optional[str] = ..., branch: _Optional[str] = ..., artifacts: _Optional[_Iterable[str]] = ..., keep_environment_running: bool = ...) -> None: ...

class ApproveRequest(_message.Message):
    __slots__ = ("ctx", "thread_id", "approved", "reason", "modification", "chat_history_json")
    CTX_FIELD_NUMBER: _ClassVar[int]
    THREAD_ID_FIELD_NUMBER: _ClassVar[int]
    APPROVED_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    MODIFICATION_FIELD_NUMBER: _ClassVar[int]
    CHAT_HISTORY_JSON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    thread_id: str
    approved: bool
    reason: str
    modification: str
    chat_history_json: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., thread_id: _Optional[str] = ..., approved: bool = ..., reason: _Optional[str] = ..., modification: _Optional[str] = ..., chat_history_json: _Optional[str] = ...) -> None: ...

class ApproveAck(_message.Message):
    __slots__ = ("status",)
    STATUS_FIELD_NUMBER: _ClassVar[int]
    status: str
    def __init__(self, status: _Optional[str] = ...) -> None: ...

class ThreadRequest(_message.Message):
    __slots__ = ("ctx", "thread_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    THREAD_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    thread_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., thread_id: _Optional[str] = ...) -> None: ...

class CancelAck(_message.Message):
    __slots__ = ("cancelled",)
    CANCELLED_FIELD_NUMBER: _ClassVar[int]
    cancelled: bool
    def __init__(self, cancelled: bool = ...) -> None: ...

class InterruptAck(_message.Message):
    __slots__ = ("interrupted", "detail")
    INTERRUPTED_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    interrupted: bool
    detail: str
    def __init__(self, interrupted: bool = ..., detail: _Optional[str] = ...) -> None: ...
