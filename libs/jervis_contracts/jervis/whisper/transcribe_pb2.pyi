from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class TranscribeRequest(_message.Message):
    __slots__ = ("ctx", "audio", "filename", "options", "blob_ref")
    CTX_FIELD_NUMBER: _ClassVar[int]
    AUDIO_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    BLOB_REF_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    audio: bytes
    filename: str
    options: TranscribeOptions
    blob_ref: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., audio: _Optional[bytes] = ..., filename: _Optional[str] = ..., options: _Optional[_Union[TranscribeOptions, _Mapping]] = ..., blob_ref: _Optional[str] = ...) -> None: ...

class TranscribeOptions(_message.Message):
    __slots__ = ("task", "model", "language", "beam_size", "vad_filter", "word_timestamps", "initial_prompt", "condition_on_previous_text", "no_speech_threshold", "extraction_ranges", "diarize")
    TASK_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    BEAM_SIZE_FIELD_NUMBER: _ClassVar[int]
    VAD_FILTER_FIELD_NUMBER: _ClassVar[int]
    WORD_TIMESTAMPS_FIELD_NUMBER: _ClassVar[int]
    INITIAL_PROMPT_FIELD_NUMBER: _ClassVar[int]
    CONDITION_ON_PREVIOUS_TEXT_FIELD_NUMBER: _ClassVar[int]
    NO_SPEECH_THRESHOLD_FIELD_NUMBER: _ClassVar[int]
    EXTRACTION_RANGES_FIELD_NUMBER: _ClassVar[int]
    DIARIZE_FIELD_NUMBER: _ClassVar[int]
    task: str
    model: str
    language: str
    beam_size: int
    vad_filter: bool
    word_timestamps: bool
    initial_prompt: str
    condition_on_previous_text: bool
    no_speech_threshold: float
    extraction_ranges: _containers.RepeatedCompositeFieldContainer[ExtractionRange]
    diarize: bool
    def __init__(self, task: _Optional[str] = ..., model: _Optional[str] = ..., language: _Optional[str] = ..., beam_size: _Optional[int] = ..., vad_filter: bool = ..., word_timestamps: bool = ..., initial_prompt: _Optional[str] = ..., condition_on_previous_text: bool = ..., no_speech_threshold: _Optional[float] = ..., extraction_ranges: _Optional[_Iterable[_Union[ExtractionRange, _Mapping]]] = ..., diarize: bool = ...) -> None: ...

class ExtractionRange(_message.Message):
    __slots__ = ("start_sec", "end_sec", "segment_index")
    START_SEC_FIELD_NUMBER: _ClassVar[int]
    END_SEC_FIELD_NUMBER: _ClassVar[int]
    SEGMENT_INDEX_FIELD_NUMBER: _ClassVar[int]
    start_sec: float
    end_sec: float
    segment_index: int
    def __init__(self, start_sec: _Optional[float] = ..., end_sec: _Optional[float] = ..., segment_index: _Optional[int] = ...) -> None: ...

class TranscribeEvent(_message.Message):
    __slots__ = ("progress", "result", "error")
    PROGRESS_FIELD_NUMBER: _ClassVar[int]
    RESULT_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    progress: ProgressEvent
    result: ResultEvent
    error: ErrorEvent
    def __init__(self, progress: _Optional[_Union[ProgressEvent, _Mapping]] = ..., result: _Optional[_Union[ResultEvent, _Mapping]] = ..., error: _Optional[_Union[ErrorEvent, _Mapping]] = ...) -> None: ...

class ProgressEvent(_message.Message):
    __slots__ = ("percent", "segments_done", "elapsed_seconds", "last_segment_text")
    PERCENT_FIELD_NUMBER: _ClassVar[int]
    SEGMENTS_DONE_FIELD_NUMBER: _ClassVar[int]
    ELAPSED_SECONDS_FIELD_NUMBER: _ClassVar[int]
    LAST_SEGMENT_TEXT_FIELD_NUMBER: _ClassVar[int]
    percent: float
    segments_done: int
    elapsed_seconds: float
    last_segment_text: str
    def __init__(self, percent: _Optional[float] = ..., segments_done: _Optional[int] = ..., elapsed_seconds: _Optional[float] = ..., last_segment_text: _Optional[str] = ...) -> None: ...

class ResultEvent(_message.Message):
    __slots__ = ("text", "language", "language_probability", "duration", "segments", "speakers", "speaker_embeddings", "text_by_segment")
    class TextBySegmentEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: int
        value: str
        def __init__(self, key: _Optional[int] = ..., value: _Optional[str] = ...) -> None: ...
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_PROBABILITY_FIELD_NUMBER: _ClassVar[int]
    DURATION_FIELD_NUMBER: _ClassVar[int]
    SEGMENTS_FIELD_NUMBER: _ClassVar[int]
    SPEAKERS_FIELD_NUMBER: _ClassVar[int]
    SPEAKER_EMBEDDINGS_FIELD_NUMBER: _ClassVar[int]
    TEXT_BY_SEGMENT_FIELD_NUMBER: _ClassVar[int]
    text: str
    language: str
    language_probability: float
    duration: float
    segments: _containers.RepeatedCompositeFieldContainer[TranscribeSegment]
    speakers: _containers.RepeatedScalarFieldContainer[str]
    speaker_embeddings: _containers.RepeatedCompositeFieldContainer[SpeakerEmbedding]
    text_by_segment: _containers.ScalarMap[int, str]
    def __init__(self, text: _Optional[str] = ..., language: _Optional[str] = ..., language_probability: _Optional[float] = ..., duration: _Optional[float] = ..., segments: _Optional[_Iterable[_Union[TranscribeSegment, _Mapping]]] = ..., speakers: _Optional[_Iterable[str]] = ..., speaker_embeddings: _Optional[_Iterable[_Union[SpeakerEmbedding, _Mapping]]] = ..., text_by_segment: _Optional[_Mapping[int, str]] = ...) -> None: ...

class TranscribeSegment(_message.Message):
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

class SpeakerEmbedding(_message.Message):
    __slots__ = ("label", "values")
    LABEL_FIELD_NUMBER: _ClassVar[int]
    VALUES_FIELD_NUMBER: _ClassVar[int]
    label: str
    values: _containers.RepeatedScalarFieldContainer[float]
    def __init__(self, label: _Optional[str] = ..., values: _Optional[_Iterable[float]] = ...) -> None: ...

class ErrorEvent(_message.Message):
    __slots__ = ("text", "error")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    text: str
    error: str
    def __init__(self, text: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class HealthRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class HealthResponse(_message.Message):
    __slots__ = ("ok", "status", "model_loaded", "detail")
    OK_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    MODEL_LOADED_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    status: str
    model_loaded: bool
    detail: str
    def __init__(self, ok: bool = ..., status: _Optional[str] = ..., model_loaded: bool = ..., detail: _Optional[str] = ...) -> None: ...

class GpuReleaseRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class GpuReleaseResponse(_message.Message):
    __slots__ = ("released",)
    RELEASED_FIELD_NUMBER: _ClassVar[int]
    released: bool
    def __init__(self, released: bool = ...) -> None: ...
