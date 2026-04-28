from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProposeTaskRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "title", "description", "reason", "proposed_by", "proposal_task_type", "scheduled_at_iso", "parent_task_id", "depends_on_task_ids")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    PROPOSED_BY_FIELD_NUMBER: _ClassVar[int]
    PROPOSAL_TASK_TYPE_FIELD_NUMBER: _ClassVar[int]
    SCHEDULED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    PARENT_TASK_ID_FIELD_NUMBER: _ClassVar[int]
    DEPENDS_ON_TASK_IDS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    title: str
    description: str
    reason: str
    proposed_by: str
    proposal_task_type: str
    scheduled_at_iso: str
    parent_task_id: str
    depends_on_task_ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., reason: _Optional[str] = ..., proposed_by: _Optional[str] = ..., proposal_task_type: _Optional[str] = ..., scheduled_at_iso: _Optional[str] = ..., parent_task_id: _Optional[str] = ..., depends_on_task_ids: _Optional[_Iterable[str]] = ...) -> None: ...

class ProposeTaskResponse(_message.Message):
    __slots__ = ("ok", "error", "task_id", "dedup_decision", "conflicting_task_id", "conflicting_title")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    DEDUP_DECISION_FIELD_NUMBER: _ClassVar[int]
    CONFLICTING_TASK_ID_FIELD_NUMBER: _ClassVar[int]
    CONFLICTING_TITLE_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    task_id: str
    dedup_decision: str
    conflicting_task_id: str
    conflicting_title: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., task_id: _Optional[str] = ..., dedup_decision: _Optional[str] = ..., conflicting_task_id: _Optional[str] = ..., conflicting_title: _Optional[str] = ...) -> None: ...

class UpdateProposedTaskRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "title", "description", "reason", "proposal_task_type", "scheduled_at_iso")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    PROPOSAL_TASK_TYPE_FIELD_NUMBER: _ClassVar[int]
    SCHEDULED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    title: str
    description: str
    reason: str
    proposal_task_type: str
    scheduled_at_iso: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., reason: _Optional[str] = ..., proposal_task_type: _Optional[str] = ..., scheduled_at_iso: _Optional[str] = ...) -> None: ...

class UpdateProposedTaskResponse(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class TaskIdRequest(_message.Message):
    __slots__ = ("ctx", "task_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ...) -> None: ...

class ProposalActionResponse(_message.Message):
    __slots__ = ("ok", "error", "proposal_stage")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    PROPOSAL_STAGE_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    proposal_stage: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., proposal_stage: _Optional[str] = ...) -> None: ...
