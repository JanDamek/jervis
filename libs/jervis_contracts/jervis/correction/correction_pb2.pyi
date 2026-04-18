from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CorrectionSegment(_message.Message):
    __slots__ = ("i", "start_sec", "end_sec", "text", "speaker")
    I_FIELD_NUMBER: _ClassVar[int]
    START_SEC_FIELD_NUMBER: _ClassVar[int]
    END_SEC_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SPEAKER_FIELD_NUMBER: _ClassVar[int]
    i: int
    start_sec: float
    end_sec: float
    text: str
    speaker: str
    def __init__(self, i: _Optional[int] = ..., start_sec: _Optional[float] = ..., end_sec: _Optional[float] = ..., text: _Optional[str] = ..., speaker: _Optional[str] = ...) -> None: ...

class CorrectionQuestion(_message.Message):
    __slots__ = ("id", "i", "original", "question", "options", "context")
    ID_FIELD_NUMBER: _ClassVar[int]
    I_FIELD_NUMBER: _ClassVar[int]
    ORIGINAL_FIELD_NUMBER: _ClassVar[int]
    QUESTION_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    id: str
    i: int
    original: str
    question: str
    options: _containers.RepeatedScalarFieldContainer[str]
    context: str
    def __init__(self, id: _Optional[str] = ..., i: _Optional[int] = ..., original: _Optional[str] = ..., question: _Optional[str] = ..., options: _Optional[_Iterable[str]] = ..., context: _Optional[str] = ...) -> None: ...

class CorrectionRule(_message.Message):
    __slots__ = ("original", "corrected", "category", "context")
    ORIGINAL_FIELD_NUMBER: _ClassVar[int]
    CORRECTED_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    original: str
    corrected: str
    category: str
    context: str
    def __init__(self, original: _Optional[str] = ..., corrected: _Optional[str] = ..., category: _Optional[str] = ..., context: _Optional[str] = ...) -> None: ...

class SubmitCorrectionRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "original", "corrected", "category", "context")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ORIGINAL_FIELD_NUMBER: _ClassVar[int]
    CORRECTED_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    original: str
    corrected: str
    category: str
    context: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., original: _Optional[str] = ..., corrected: _Optional[str] = ..., category: _Optional[str] = ..., context: _Optional[str] = ...) -> None: ...

class SubmitCorrectionResponse(_message.Message):
    __slots__ = ("correction_id", "source_urn", "status", "error")
    CORRECTION_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    correction_id: str
    source_urn: str
    status: str
    error: str
    def __init__(self, correction_id: _Optional[str] = ..., source_urn: _Optional[str] = ..., status: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class CorrectTranscriptRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "meeting_id", "segments", "chunk_size", "speaker_hints")
    class SpeakerHintsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    SEGMENTS_FIELD_NUMBER: _ClassVar[int]
    CHUNK_SIZE_FIELD_NUMBER: _ClassVar[int]
    SPEAKER_HINTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    meeting_id: str
    segments: _containers.RepeatedCompositeFieldContainer[CorrectionSegment]
    chunk_size: int
    speaker_hints: _containers.ScalarMap[str, str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., meeting_id: _Optional[str] = ..., segments: _Optional[_Iterable[_Union[CorrectionSegment, _Mapping]]] = ..., chunk_size: _Optional[int] = ..., speaker_hints: _Optional[_Mapping[str, str]] = ...) -> None: ...

class CorrectResult(_message.Message):
    __slots__ = ("segments", "questions", "status")
    SEGMENTS_FIELD_NUMBER: _ClassVar[int]
    QUESTIONS_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    segments: _containers.RepeatedCompositeFieldContainer[CorrectionSegment]
    questions: _containers.RepeatedCompositeFieldContainer[CorrectionQuestion]
    status: str
    def __init__(self, segments: _Optional[_Iterable[_Union[CorrectionSegment, _Mapping]]] = ..., questions: _Optional[_Iterable[_Union[CorrectionQuestion, _Mapping]]] = ..., status: _Optional[str] = ...) -> None: ...

class ListCorrectionsRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "max_results")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    MAX_RESULTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    max_results: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., max_results: _Optional[int] = ...) -> None: ...

class CorrectionChunkMeta(_message.Message):
    __slots__ = ("original", "corrected", "category", "context", "correction_id")
    ORIGINAL_FIELD_NUMBER: _ClassVar[int]
    CORRECTED_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    CORRECTION_ID_FIELD_NUMBER: _ClassVar[int]
    original: str
    corrected: str
    category: str
    context: str
    correction_id: str
    def __init__(self, original: _Optional[str] = ..., corrected: _Optional[str] = ..., category: _Optional[str] = ..., context: _Optional[str] = ..., correction_id: _Optional[str] = ...) -> None: ...

class CorrectionChunk(_message.Message):
    __slots__ = ("content", "source_urn", "metadata")
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    content: str
    source_urn: str
    metadata: CorrectionChunkMeta
    def __init__(self, content: _Optional[str] = ..., source_urn: _Optional[str] = ..., metadata: _Optional[_Union[CorrectionChunkMeta, _Mapping]] = ...) -> None: ...

class ListCorrectionsResponse(_message.Message):
    __slots__ = ("corrections",)
    CORRECTIONS_FIELD_NUMBER: _ClassVar[int]
    corrections: _containers.RepeatedCompositeFieldContainer[CorrectionChunk]
    def __init__(self, corrections: _Optional[_Iterable[_Union[CorrectionChunk, _Mapping]]] = ...) -> None: ...

class AnswerCorrectionsRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "answers")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ANSWERS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    answers: _containers.RepeatedCompositeFieldContainer[CorrectionRule]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., answers: _Optional[_Iterable[_Union[CorrectionRule, _Mapping]]] = ...) -> None: ...

class AnswerCorrectionsResponse(_message.Message):
    __slots__ = ("status", "rules_created", "error")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    RULES_CREATED_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    status: str
    rules_created: int
    error: str
    def __init__(self, status: _Optional[str] = ..., rules_created: _Optional[int] = ..., error: _Optional[str] = ...) -> None: ...

class CorrectWithInstructionRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "segments", "instruction")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    SEGMENTS_FIELD_NUMBER: _ClassVar[int]
    INSTRUCTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    segments: _containers.RepeatedCompositeFieldContainer[CorrectionSegment]
    instruction: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., segments: _Optional[_Iterable[_Union[CorrectionSegment, _Mapping]]] = ..., instruction: _Optional[str] = ...) -> None: ...

class InstructRuleResult(_message.Message):
    __slots__ = ("correction_id", "source_urn", "status")
    CORRECTION_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    correction_id: str
    source_urn: str
    status: str
    def __init__(self, correction_id: _Optional[str] = ..., source_urn: _Optional[str] = ..., status: _Optional[str] = ...) -> None: ...

class CorrectWithInstructionResponse(_message.Message):
    __slots__ = ("segments", "new_rules", "status", "summary")
    SEGMENTS_FIELD_NUMBER: _ClassVar[int]
    NEW_RULES_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SUMMARY_FIELD_NUMBER: _ClassVar[int]
    segments: _containers.RepeatedCompositeFieldContainer[CorrectionSegment]
    new_rules: _containers.RepeatedCompositeFieldContainer[InstructRuleResult]
    status: str
    summary: str
    def __init__(self, segments: _Optional[_Iterable[_Union[CorrectionSegment, _Mapping]]] = ..., new_rules: _Optional[_Iterable[_Union[InstructRuleResult, _Mapping]]] = ..., status: _Optional[str] = ..., summary: _Optional[str] = ...) -> None: ...

class CorrectTargetedRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "meeting_id", "segments", "retranscribed_indices", "user_corrected_indices")
    class UserCorrectedIndicesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    MEETING_ID_FIELD_NUMBER: _ClassVar[int]
    SEGMENTS_FIELD_NUMBER: _ClassVar[int]
    RETRANSCRIBED_INDICES_FIELD_NUMBER: _ClassVar[int]
    USER_CORRECTED_INDICES_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    meeting_id: str
    segments: _containers.RepeatedCompositeFieldContainer[CorrectionSegment]
    retranscribed_indices: _containers.RepeatedScalarFieldContainer[int]
    user_corrected_indices: _containers.ScalarMap[str, str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., meeting_id: _Optional[str] = ..., segments: _Optional[_Iterable[_Union[CorrectionSegment, _Mapping]]] = ..., retranscribed_indices: _Optional[_Iterable[int]] = ..., user_corrected_indices: _Optional[_Mapping[str, str]] = ...) -> None: ...

class DeleteCorrectionRequest(_message.Message):
    __slots__ = ("ctx", "source_urn")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    source_urn: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., source_urn: _Optional[str] = ...) -> None: ...

class DeleteCorrectionResponse(_message.Message):
    __slots__ = ("status", "chunks_deleted", "nodes_cleaned", "edges_cleaned", "nodes_deleted", "edges_deleted", "error")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_DELETED_FIELD_NUMBER: _ClassVar[int]
    NODES_CLEANED_FIELD_NUMBER: _ClassVar[int]
    EDGES_CLEANED_FIELD_NUMBER: _ClassVar[int]
    NODES_DELETED_FIELD_NUMBER: _ClassVar[int]
    EDGES_DELETED_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    status: str
    chunks_deleted: int
    nodes_cleaned: int
    edges_cleaned: int
    nodes_deleted: int
    edges_deleted: int
    error: str
    def __init__(self, status: _Optional[str] = ..., chunks_deleted: _Optional[int] = ..., nodes_cleaned: _Optional[int] = ..., edges_cleaned: _Optional[int] = ..., nodes_deleted: _Optional[int] = ..., edges_deleted: _Optional[int] = ..., error: _Optional[str] = ...) -> None: ...
