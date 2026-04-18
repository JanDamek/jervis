from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GetOpenRouterSettingsRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class PersistModelStatsRequest(_message.Message):
    __slots__ = ("ctx", "stats")
    CTX_FIELD_NUMBER: _ClassVar[int]
    STATS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    stats: _containers.RepeatedCompositeFieldContainer[ModelStatsEntry]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., stats: _Optional[_Iterable[_Union[ModelStatsEntry, _Mapping]]] = ...) -> None: ...

class PersistModelStatsResponse(_message.Message):
    __slots__ = ("ok", "models")
    OK_FIELD_NUMBER: _ClassVar[int]
    MODELS_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    models: int
    def __init__(self, ok: bool = ..., models: _Optional[int] = ...) -> None: ...

class OpenRouterSettings(_message.Message):
    __slots__ = ("api_key", "api_base_url", "enabled", "filters", "models", "monthly_budget_usd", "fallback_strategy", "model_queues")
    API_KEY_FIELD_NUMBER: _ClassVar[int]
    API_BASE_URL_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    FILTERS_FIELD_NUMBER: _ClassVar[int]
    MODELS_FIELD_NUMBER: _ClassVar[int]
    MONTHLY_BUDGET_USD_FIELD_NUMBER: _ClassVar[int]
    FALLBACK_STRATEGY_FIELD_NUMBER: _ClassVar[int]
    MODEL_QUEUES_FIELD_NUMBER: _ClassVar[int]
    api_key: str
    api_base_url: str
    enabled: bool
    filters: OpenRouterFilters
    models: _containers.RepeatedCompositeFieldContainer[OpenRouterModelEntry]
    monthly_budget_usd: float
    fallback_strategy: str
    model_queues: _containers.RepeatedCompositeFieldContainer[ModelQueue]
    def __init__(self, api_key: _Optional[str] = ..., api_base_url: _Optional[str] = ..., enabled: bool = ..., filters: _Optional[_Union[OpenRouterFilters, _Mapping]] = ..., models: _Optional[_Iterable[_Union[OpenRouterModelEntry, _Mapping]]] = ..., monthly_budget_usd: _Optional[float] = ..., fallback_strategy: _Optional[str] = ..., model_queues: _Optional[_Iterable[_Union[ModelQueue, _Mapping]]] = ...) -> None: ...

class OpenRouterFilters(_message.Message):
    __slots__ = ("allowed_providers", "blocked_providers", "min_context_length", "max_input_price_per_million", "max_output_price_per_million", "require_tool_support", "require_streaming", "model_name_filter")
    ALLOWED_PROVIDERS_FIELD_NUMBER: _ClassVar[int]
    BLOCKED_PROVIDERS_FIELD_NUMBER: _ClassVar[int]
    MIN_CONTEXT_LENGTH_FIELD_NUMBER: _ClassVar[int]
    MAX_INPUT_PRICE_PER_MILLION_FIELD_NUMBER: _ClassVar[int]
    MAX_OUTPUT_PRICE_PER_MILLION_FIELD_NUMBER: _ClassVar[int]
    REQUIRE_TOOL_SUPPORT_FIELD_NUMBER: _ClassVar[int]
    REQUIRE_STREAMING_FIELD_NUMBER: _ClassVar[int]
    MODEL_NAME_FILTER_FIELD_NUMBER: _ClassVar[int]
    allowed_providers: _containers.RepeatedScalarFieldContainer[str]
    blocked_providers: _containers.RepeatedScalarFieldContainer[str]
    min_context_length: int
    max_input_price_per_million: float
    max_output_price_per_million: float
    require_tool_support: bool
    require_streaming: bool
    model_name_filter: str
    def __init__(self, allowed_providers: _Optional[_Iterable[str]] = ..., blocked_providers: _Optional[_Iterable[str]] = ..., min_context_length: _Optional[int] = ..., max_input_price_per_million: _Optional[float] = ..., max_output_price_per_million: _Optional[float] = ..., require_tool_support: bool = ..., require_streaming: bool = ..., model_name_filter: _Optional[str] = ...) -> None: ...

class OpenRouterModelEntry(_message.Message):
    __slots__ = ("model_id", "display_name", "enabled", "max_context_tokens", "input_price_per_million", "output_price_per_million", "preferred_for", "max_output_tokens", "free")
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    MAX_CONTEXT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    INPUT_PRICE_PER_MILLION_FIELD_NUMBER: _ClassVar[int]
    OUTPUT_PRICE_PER_MILLION_FIELD_NUMBER: _ClassVar[int]
    PREFERRED_FOR_FIELD_NUMBER: _ClassVar[int]
    MAX_OUTPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    FREE_FIELD_NUMBER: _ClassVar[int]
    model_id: str
    display_name: str
    enabled: bool
    max_context_tokens: int
    input_price_per_million: float
    output_price_per_million: float
    preferred_for: _containers.RepeatedScalarFieldContainer[str]
    max_output_tokens: int
    free: bool
    def __init__(self, model_id: _Optional[str] = ..., display_name: _Optional[str] = ..., enabled: bool = ..., max_context_tokens: _Optional[int] = ..., input_price_per_million: _Optional[float] = ..., output_price_per_million: _Optional[float] = ..., preferred_for: _Optional[_Iterable[str]] = ..., max_output_tokens: _Optional[int] = ..., free: bool = ...) -> None: ...

class ModelQueue(_message.Message):
    __slots__ = ("name", "models", "enabled")
    NAME_FIELD_NUMBER: _ClassVar[int]
    MODELS_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    name: str
    models: _containers.RepeatedCompositeFieldContainer[QueueModelEntry]
    enabled: bool
    def __init__(self, name: _Optional[str] = ..., models: _Optional[_Iterable[_Union[QueueModelEntry, _Mapping]]] = ..., enabled: bool = ...) -> None: ...

class QueueModelEntry(_message.Message):
    __slots__ = ("model_id", "is_local", "max_context_tokens", "enabled", "label", "capabilities", "input_price_per_million", "output_price_per_million", "supports_tools", "provider", "stats")
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    IS_LOCAL_FIELD_NUMBER: _ClassVar[int]
    MAX_CONTEXT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    INPUT_PRICE_PER_MILLION_FIELD_NUMBER: _ClassVar[int]
    OUTPUT_PRICE_PER_MILLION_FIELD_NUMBER: _ClassVar[int]
    SUPPORTS_TOOLS_FIELD_NUMBER: _ClassVar[int]
    PROVIDER_FIELD_NUMBER: _ClassVar[int]
    STATS_FIELD_NUMBER: _ClassVar[int]
    model_id: str
    is_local: bool
    max_context_tokens: int
    enabled: bool
    label: str
    capabilities: _containers.RepeatedScalarFieldContainer[str]
    input_price_per_million: float
    output_price_per_million: float
    supports_tools: bool
    provider: str
    stats: ModelCallStats
    def __init__(self, model_id: _Optional[str] = ..., is_local: bool = ..., max_context_tokens: _Optional[int] = ..., enabled: bool = ..., label: _Optional[str] = ..., capabilities: _Optional[_Iterable[str]] = ..., input_price_per_million: _Optional[float] = ..., output_price_per_million: _Optional[float] = ..., supports_tools: bool = ..., provider: _Optional[str] = ..., stats: _Optional[_Union[ModelCallStats, _Mapping]] = ...) -> None: ...

class ModelCallStats(_message.Message):
    __slots__ = ("call_count", "total_time_s", "total_input_tokens", "total_output_tokens", "tokens_per_s", "last_call")
    CALL_COUNT_FIELD_NUMBER: _ClassVar[int]
    TOTAL_TIME_S_FIELD_NUMBER: _ClassVar[int]
    TOTAL_INPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_OUTPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    TOKENS_PER_S_FIELD_NUMBER: _ClassVar[int]
    LAST_CALL_FIELD_NUMBER: _ClassVar[int]
    call_count: int
    total_time_s: float
    total_input_tokens: int
    total_output_tokens: int
    tokens_per_s: float
    last_call: float
    def __init__(self, call_count: _Optional[int] = ..., total_time_s: _Optional[float] = ..., total_input_tokens: _Optional[int] = ..., total_output_tokens: _Optional[int] = ..., tokens_per_s: _Optional[float] = ..., last_call: _Optional[float] = ...) -> None: ...

class ModelStatsEntry(_message.Message):
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
