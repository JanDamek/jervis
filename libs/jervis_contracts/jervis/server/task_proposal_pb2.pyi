from jervis.common import types_pb2 as _types_pb2
from jervis.server import task_api_pb2 as _task_api_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class InsertProposalRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "title", "description", "reason", "proposed_by", "proposal_task_type", "scheduled_at_iso", "parent_task_id", "depends_on_task_ids", "title_embedding", "description_embedding")
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
    TITLE_EMBEDDING_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_EMBEDDING_FIELD_NUMBER: _ClassVar[int]
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
    title_embedding: _containers.RepeatedScalarFieldContainer[float]
    description_embedding: _containers.RepeatedScalarFieldContainer[float]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., reason: _Optional[str] = ..., proposed_by: _Optional[str] = ..., proposal_task_type: _Optional[str] = ..., scheduled_at_iso: _Optional[str] = ..., parent_task_id: _Optional[str] = ..., depends_on_task_ids: _Optional[_Iterable[str]] = ..., title_embedding: _Optional[_Iterable[float]] = ..., description_embedding: _Optional[_Iterable[float]] = ...) -> None: ...

class InsertProposalResponse(_message.Message):
    __slots__ = ("ok", "error", "task_id")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    task_id: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., task_id: _Optional[str] = ...) -> None: ...

class UpdateProposalRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "title", "description", "reason", "proposal_task_type", "scheduled_at_iso", "title_embedding", "description_embedding")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    PROPOSAL_TASK_TYPE_FIELD_NUMBER: _ClassVar[int]
    SCHEDULED_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    TITLE_EMBEDDING_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_EMBEDDING_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    title: str
    description: str
    reason: str
    proposal_task_type: str
    scheduled_at_iso: str
    title_embedding: _containers.RepeatedScalarFieldContainer[float]
    description_embedding: _containers.RepeatedScalarFieldContainer[float]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., reason: _Optional[str] = ..., proposal_task_type: _Optional[str] = ..., scheduled_at_iso: _Optional[str] = ..., title_embedding: _Optional[_Iterable[float]] = ..., description_embedding: _Optional[_Iterable[float]] = ...) -> None: ...

class UpdateProposalResponse(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...

class RejectTaskRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "reason")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    reason: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class ProposalActionResponse(_message.Message):
    __slots__ = ("ok", "error", "proposal_stage")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    PROPOSAL_STAGE_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    proposal_stage: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., proposal_stage: _Optional[str] = ...) -> None: ...

class DedupRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "proposed_by")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    PROPOSED_BY_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    proposed_by: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., proposed_by: _Optional[str] = ...) -> None: ...

class DedupResponse(_message.Message):
    __slots__ = ("ok", "error", "candidates")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    CANDIDATES_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    candidates: _containers.RepeatedCompositeFieldContainer[DedupCandidate]
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., candidates: _Optional[_Iterable[_Union[DedupCandidate, _Mapping]]] = ...) -> None: ...

class DedupCandidate(_message.Message):
    __slots__ = ("task_id", "title", "description", "client_id", "project_id", "title_embedding", "description_embedding", "proposal_stage")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_EMBEDDING_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_EMBEDDING_FIELD_NUMBER: _ClassVar[int]
    PROPOSAL_STAGE_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    title: str
    description: str
    client_id: str
    project_id: str
    title_embedding: _containers.RepeatedScalarFieldContainer[float]
    description_embedding: _containers.RepeatedScalarFieldContainer[float]
    proposal_stage: str
    def __init__(self, task_id: _Optional[str] = ..., title: _Optional[str] = ..., description: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., title_embedding: _Optional[_Iterable[float]] = ..., description_embedding: _Optional[_Iterable[float]] = ..., proposal_stage: _Optional[str] = ...) -> None: ...
