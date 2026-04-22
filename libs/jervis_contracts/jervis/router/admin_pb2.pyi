from jervis.common import types_pb2 as _types_pb2
from jervis.common import enums_pb2 as _enums_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class MaxContextRequest(_message.Message):
    __slots__ = ("ctx", "max_tier")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MAX_TIER_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    max_tier: _enums_pb2.TierCap
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., max_tier: _Optional[_Union[_enums_pb2.TierCap, str]] = ...) -> None: ...

class MaxContextResponse(_message.Message):
    __slots__ = ("max_context_tokens",)
    MAX_CONTEXT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    max_context_tokens: int
    def __init__(self, max_context_tokens: _Optional[int] = ...) -> None: ...

class ReportModelErrorRequest(_message.Message):
    __slots__ = ("ctx", "model_id", "error_message")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    model_id: str
    error_message: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., model_id: _Optional[str] = ..., error_message: _Optional[str] = ...) -> None: ...

class ReportModelErrorResponse(_message.Message):
    __slots__ = ("model_id", "disabled", "error_count", "just_disabled")
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    DISABLED_FIELD_NUMBER: _ClassVar[int]
    ERROR_COUNT_FIELD_NUMBER: _ClassVar[int]
    JUST_DISABLED_FIELD_NUMBER: _ClassVar[int]
    model_id: str
    disabled: bool
    error_count: int
    just_disabled: bool
    def __init__(self, model_id: _Optional[str] = ..., disabled: bool = ..., error_count: _Optional[int] = ..., just_disabled: bool = ...) -> None: ...

class ReportModelSuccessRequest(_message.Message):
    __slots__ = ("ctx", "model_id", "duration_s", "input_tokens", "output_tokens")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    DURATION_S_FIELD_NUMBER: _ClassVar[int]
    INPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    OUTPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    model_id: str
    duration_s: float
    input_tokens: int
    output_tokens: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., model_id: _Optional[str] = ..., duration_s: _Optional[float] = ..., input_tokens: _Optional[int] = ..., output_tokens: _Optional[int] = ...) -> None: ...

class ReportModelSuccessResponse(_message.Message):
    __slots__ = ("model_id", "reset")
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    RESET_FIELD_NUMBER: _ClassVar[int]
    model_id: str
    reset: bool
    def __init__(self, model_id: _Optional[str] = ..., reset: bool = ...) -> None: ...

class ListModelErrorsRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class ListModelErrorsResponse(_message.Message):
    __slots__ = ("errors",)
    ERRORS_FIELD_NUMBER: _ClassVar[int]
    errors: _containers.RepeatedCompositeFieldContainer[ModelErrorInfo]
    def __init__(self, errors: _Optional[_Iterable[_Union[ModelErrorInfo, _Mapping]]] = ...) -> None: ...

class ModelErrorInfo(_message.Message):
    __slots__ = ("model_id", "count", "disabled", "entries")
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    DISABLED_FIELD_NUMBER: _ClassVar[int]
    ENTRIES_FIELD_NUMBER: _ClassVar[int]
    model_id: str
    count: int
    disabled: bool
    entries: _containers.RepeatedCompositeFieldContainer[ModelErrorEntry]
    def __init__(self, model_id: _Optional[str] = ..., count: _Optional[int] = ..., disabled: bool = ..., entries: _Optional[_Iterable[_Union[ModelErrorEntry, _Mapping]]] = ...) -> None: ...

class ModelErrorEntry(_message.Message):
    __slots__ = ("message", "timestamp")
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    message: str
    timestamp: float
    def __init__(self, message: _Optional[str] = ..., timestamp: _Optional[float] = ...) -> None: ...

class ListModelStatsRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class ListModelStatsResponse(_message.Message):
    __slots__ = ("stats",)
    STATS_FIELD_NUMBER: _ClassVar[int]
    stats: _containers.RepeatedCompositeFieldContainer[ModelStatInfo]
    def __init__(self, stats: _Optional[_Iterable[_Union[ModelStatInfo, _Mapping]]] = ...) -> None: ...

class ModelStatInfo(_message.Message):
    __slots__ = ("model_id", "call_count", "avg_response_s", "total_time_s", "total_input_tokens", "total_output_tokens", "tokens_per_s", "last_call")
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    CALL_COUNT_FIELD_NUMBER: _ClassVar[int]
    AVG_RESPONSE_S_FIELD_NUMBER: _ClassVar[int]
    TOTAL_TIME_S_FIELD_NUMBER: _ClassVar[int]
    TOTAL_INPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_OUTPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    TOKENS_PER_S_FIELD_NUMBER: _ClassVar[int]
    LAST_CALL_FIELD_NUMBER: _ClassVar[int]
    model_id: str
    call_count: int
    avg_response_s: float
    total_time_s: float
    total_input_tokens: int
    total_output_tokens: int
    tokens_per_s: float
    last_call: float
    def __init__(self, model_id: _Optional[str] = ..., call_count: _Optional[int] = ..., avg_response_s: _Optional[float] = ..., total_time_s: _Optional[float] = ..., total_input_tokens: _Optional[int] = ..., total_output_tokens: _Optional[int] = ..., tokens_per_s: _Optional[float] = ..., last_call: _Optional[float] = ...) -> None: ...

class ResetModelErrorRequest(_message.Message):
    __slots__ = ("ctx", "model_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    model_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., model_id: _Optional[str] = ...) -> None: ...

class ResetModelErrorResponse(_message.Message):
    __slots__ = ("model_id", "re_enabled")
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    RE_ENABLED_FIELD_NUMBER: _ClassVar[int]
    model_id: str
    re_enabled: bool
    def __init__(self, model_id: _Optional[str] = ..., re_enabled: bool = ...) -> None: ...

class TestModelRequest(_message.Message):
    __slots__ = ("ctx", "model_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    model_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., model_id: _Optional[str] = ...) -> None: ...

class TestModelResponse(_message.Message):
    __slots__ = ("ok", "model_id", "response_ms", "response_preview", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    RESPONSE_MS_FIELD_NUMBER: _ClassVar[int]
    RESPONSE_PREVIEW_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    model_id: str
    response_ms: int
    response_preview: str
    error: str
    def __init__(self, ok: bool = ..., model_id: _Optional[str] = ..., response_ms: _Optional[int] = ..., response_preview: _Optional[str] = ..., error: _Optional[str] = ...) -> None: ...

class RateLimitsRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class RateLimitsResponse(_message.Message):
    __slots__ = ("queues",)
    QUEUES_FIELD_NUMBER: _ClassVar[int]
    queues: _containers.RepeatedCompositeFieldContainer[QueueRateLimit]
    def __init__(self, queues: _Optional[_Iterable[_Union[QueueRateLimit, _Mapping]]] = ...) -> None: ...

class QueueRateLimit(_message.Message):
    __slots__ = ("queue_name", "limit", "remaining", "reset_time")
    QUEUE_NAME_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    REMAINING_FIELD_NUMBER: _ClassVar[int]
    RESET_TIME_FIELD_NUMBER: _ClassVar[int]
    queue_name: str
    limit: int
    remaining: int
    reset_time: float
    def __init__(self, queue_name: _Optional[str] = ..., limit: _Optional[int] = ..., remaining: _Optional[int] = ..., reset_time: _Optional[float] = ...) -> None: ...

class InvalidateClientTierRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class InvalidateClientTierResponse(_message.Message):
    __slots__ = ("invalidated",)
    INVALIDATED_FIELD_NUMBER: _ClassVar[int]
    invalidated: str
    def __init__(self, invalidated: _Optional[str] = ...) -> None: ...

class WhisperNotifyRequest(_message.Message):
    __slots__ = ("ctx", "preempt_timeout_s")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PREEMPT_TIMEOUT_S_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    preempt_timeout_s: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., preempt_timeout_s: _Optional[int] = ...) -> None: ...

class WhisperNotifyResponse(_message.Message):
    __slots__ = ("granted", "preempted_count", "unloaded_models")
    GRANTED_FIELD_NUMBER: _ClassVar[int]
    PREEMPTED_COUNT_FIELD_NUMBER: _ClassVar[int]
    UNLOADED_MODELS_FIELD_NUMBER: _ClassVar[int]
    granted: bool
    preempted_count: int
    unloaded_models: int
    def __init__(self, granted: bool = ..., preempted_count: _Optional[int] = ..., unloaded_models: _Optional[int] = ...) -> None: ...

class WhisperDoneRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class WhisperDoneResponse(_message.Message):
    __slots__ = ("released",)
    RELEASED_FIELD_NUMBER: _ClassVar[int]
    released: bool
    def __init__(self, released: bool = ...) -> None: ...

class TtsNotifyRequest(_message.Message):
    __slots__ = ("ctx", "preempt_timeout_s")
    CTX_FIELD_NUMBER: _ClassVar[int]
    PREEMPT_TIMEOUT_S_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    preempt_timeout_s: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., preempt_timeout_s: _Optional[int] = ...) -> None: ...

class TtsNotifyResponse(_message.Message):
    __slots__ = ("granted", "preempted_count", "unloaded_models")
    GRANTED_FIELD_NUMBER: _ClassVar[int]
    PREEMPTED_COUNT_FIELD_NUMBER: _ClassVar[int]
    UNLOADED_MODELS_FIELD_NUMBER: _ClassVar[int]
    granted: bool
    preempted_count: int
    unloaded_models: int
    def __init__(self, granted: bool = ..., preempted_count: _Optional[int] = ..., unloaded_models: _Optional[int] = ...) -> None: ...

class TtsDoneRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class TtsDoneResponse(_message.Message):
    __slots__ = ("released",)
    RELEASED_FIELD_NUMBER: _ClassVar[int]
    released: bool
    def __init__(self, released: bool = ...) -> None: ...
