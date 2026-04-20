from google.protobuf import struct_pb2 as _struct_pb2
from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ChatRequest(_message.Message):
    __slots__ = ("ctx", "model_hint", "messages", "tools", "options")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MODEL_HINT_FIELD_NUMBER: _ClassVar[int]
    MESSAGES_FIELD_NUMBER: _ClassVar[int]
    TOOLS_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    model_hint: str
    messages: _containers.RepeatedCompositeFieldContainer[ChatMessage]
    tools: _containers.RepeatedCompositeFieldContainer[Tool]
    options: ChatOptions
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., model_hint: _Optional[str] = ..., messages: _Optional[_Iterable[_Union[ChatMessage, _Mapping]]] = ..., tools: _Optional[_Iterable[_Union[Tool, _Mapping]]] = ..., options: _Optional[_Union[ChatOptions, _Mapping]] = ...) -> None: ...

class ChatMessage(_message.Message):
    __slots__ = ("role", "content", "tool_calls", "tool_call_id", "name", "images")
    ROLE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    TOOL_CALLS_FIELD_NUMBER: _ClassVar[int]
    TOOL_CALL_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    IMAGES_FIELD_NUMBER: _ClassVar[int]
    role: str
    content: str
    tool_calls: _containers.RepeatedCompositeFieldContainer[ToolCall]
    tool_call_id: str
    name: str
    images: _containers.RepeatedScalarFieldContainer[bytes]
    def __init__(self, role: _Optional[str] = ..., content: _Optional[str] = ..., tool_calls: _Optional[_Iterable[_Union[ToolCall, _Mapping]]] = ..., tool_call_id: _Optional[str] = ..., name: _Optional[str] = ..., images: _Optional[_Iterable[bytes]] = ...) -> None: ...

class ToolCall(_message.Message):
    __slots__ = ("id", "name", "args")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    ARGS_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    args: _struct_pb2.Struct
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., args: _Optional[_Union[_struct_pb2.Struct, _Mapping]] = ...) -> None: ...

class Tool(_message.Message):
    __slots__ = ("name", "description", "parameters")
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    PARAMETERS_FIELD_NUMBER: _ClassVar[int]
    name: str
    description: str
    parameters: _struct_pb2.Struct
    def __init__(self, name: _Optional[str] = ..., description: _Optional[str] = ..., parameters: _Optional[_Union[_struct_pb2.Struct, _Mapping]] = ...) -> None: ...

class ChatOptions(_message.Message):
    __slots__ = ("temperature", "num_predict", "num_ctx", "top_p")
    TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    NUM_PREDICT_FIELD_NUMBER: _ClassVar[int]
    NUM_CTX_FIELD_NUMBER: _ClassVar[int]
    TOP_P_FIELD_NUMBER: _ClassVar[int]
    temperature: float
    num_predict: int
    num_ctx: int
    top_p: float
    def __init__(self, temperature: _Optional[float] = ..., num_predict: _Optional[int] = ..., num_ctx: _Optional[int] = ..., top_p: _Optional[float] = ...) -> None: ...

class ChatChunk(_message.Message):
    __slots__ = ("content_delta", "thinking_delta", "tool_calls", "done", "finish_reason", "prompt_tokens", "completion_tokens", "model_used")
    CONTENT_DELTA_FIELD_NUMBER: _ClassVar[int]
    THINKING_DELTA_FIELD_NUMBER: _ClassVar[int]
    TOOL_CALLS_FIELD_NUMBER: _ClassVar[int]
    DONE_FIELD_NUMBER: _ClassVar[int]
    FINISH_REASON_FIELD_NUMBER: _ClassVar[int]
    PROMPT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    COMPLETION_TOKENS_FIELD_NUMBER: _ClassVar[int]
    MODEL_USED_FIELD_NUMBER: _ClassVar[int]
    content_delta: str
    thinking_delta: str
    tool_calls: _containers.RepeatedCompositeFieldContainer[ToolCall]
    done: bool
    finish_reason: str
    prompt_tokens: int
    completion_tokens: int
    model_used: str
    def __init__(self, content_delta: _Optional[str] = ..., thinking_delta: _Optional[str] = ..., tool_calls: _Optional[_Iterable[_Union[ToolCall, _Mapping]]] = ..., done: bool = ..., finish_reason: _Optional[str] = ..., prompt_tokens: _Optional[int] = ..., completion_tokens: _Optional[int] = ..., model_used: _Optional[str] = ...) -> None: ...

class GenerateRequest(_message.Message):
    __slots__ = ("ctx", "model_hint", "prompt", "images", "options")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MODEL_HINT_FIELD_NUMBER: _ClassVar[int]
    PROMPT_FIELD_NUMBER: _ClassVar[int]
    IMAGES_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    model_hint: str
    prompt: str
    images: _containers.RepeatedScalarFieldContainer[bytes]
    options: ChatOptions
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., model_hint: _Optional[str] = ..., prompt: _Optional[str] = ..., images: _Optional[_Iterable[bytes]] = ..., options: _Optional[_Union[ChatOptions, _Mapping]] = ...) -> None: ...

class GenerateChunk(_message.Message):
    __slots__ = ("response_delta", "done", "prompt_tokens", "completion_tokens", "model_used", "finish_reason")
    RESPONSE_DELTA_FIELD_NUMBER: _ClassVar[int]
    DONE_FIELD_NUMBER: _ClassVar[int]
    PROMPT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    COMPLETION_TOKENS_FIELD_NUMBER: _ClassVar[int]
    MODEL_USED_FIELD_NUMBER: _ClassVar[int]
    FINISH_REASON_FIELD_NUMBER: _ClassVar[int]
    response_delta: str
    done: bool
    prompt_tokens: int
    completion_tokens: int
    model_used: str
    finish_reason: str
    def __init__(self, response_delta: _Optional[str] = ..., done: bool = ..., prompt_tokens: _Optional[int] = ..., completion_tokens: _Optional[int] = ..., model_used: _Optional[str] = ..., finish_reason: _Optional[str] = ...) -> None: ...

class EmbedRequest(_message.Message):
    __slots__ = ("ctx", "model_hint", "inputs")
    CTX_FIELD_NUMBER: _ClassVar[int]
    MODEL_HINT_FIELD_NUMBER: _ClassVar[int]
    INPUTS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    model_hint: str
    inputs: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., model_hint: _Optional[str] = ..., inputs: _Optional[_Iterable[str]] = ...) -> None: ...

class EmbedResponse(_message.Message):
    __slots__ = ("embeddings", "model_used")
    EMBEDDINGS_FIELD_NUMBER: _ClassVar[int]
    MODEL_USED_FIELD_NUMBER: _ClassVar[int]
    embeddings: _containers.RepeatedCompositeFieldContainer[Embedding]
    model_used: str
    def __init__(self, embeddings: _Optional[_Iterable[_Union[Embedding, _Mapping]]] = ..., model_used: _Optional[str] = ...) -> None: ...

class Embedding(_message.Message):
    __slots__ = ("vector",)
    VECTOR_FIELD_NUMBER: _ClassVar[int]
    vector: _containers.RepeatedScalarFieldContainer[float]
    def __init__(self, vector: _Optional[_Iterable[float]] = ...) -> None: ...
