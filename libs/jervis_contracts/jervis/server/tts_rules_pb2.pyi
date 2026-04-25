from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class TtsRuleType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TTS_RULE_TYPE_UNSPECIFIED: _ClassVar[TtsRuleType]
    ACRONYM: _ClassVar[TtsRuleType]
    STRIP: _ClassVar[TtsRuleType]
    REPLACE: _ClassVar[TtsRuleType]

class TtsRuleScopeType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TTS_RULE_SCOPE_UNSPECIFIED: _ClassVar[TtsRuleScopeType]
    GLOBAL: _ClassVar[TtsRuleScopeType]
    CLIENT: _ClassVar[TtsRuleScopeType]
    PROJECT: _ClassVar[TtsRuleScopeType]
TTS_RULE_TYPE_UNSPECIFIED: TtsRuleType
ACRONYM: TtsRuleType
STRIP: TtsRuleType
REPLACE: TtsRuleType
TTS_RULE_SCOPE_UNSPECIFIED: TtsRuleScopeType
GLOBAL: TtsRuleScopeType
CLIENT: TtsRuleScopeType
PROJECT: TtsRuleScopeType

class GetForScopeRequest(_message.Message):
    __slots__ = ("ctx", "language", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    language: str
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., language: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class ListTtsRulesRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class DeleteTtsRuleRequest(_message.Message):
    __slots__ = ("ctx", "id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., id: _Optional[str] = ...) -> None: ...

class DeleteTtsRuleResponse(_message.Message):
    __slots__ = ("ok",)
    OK_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    def __init__(self, ok: bool = ...) -> None: ...

class PreviewRequest(_message.Message):
    __slots__ = ("ctx", "text", "language", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    text: str
    language: str
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., text: _Optional[str] = ..., language: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class PreviewResponse(_message.Message):
    __slots__ = ("output", "hits")
    OUTPUT_FIELD_NUMBER: _ClassVar[int]
    HITS_FIELD_NUMBER: _ClassVar[int]
    output: str
    hits: _containers.RepeatedCompositeFieldContainer[TtsRuleHit]
    def __init__(self, output: _Optional[str] = ..., hits: _Optional[_Iterable[_Union[TtsRuleHit, _Mapping]]] = ...) -> None: ...

class TtsRuleHit(_message.Message):
    __slots__ = ("rule_id", "type", "chars_removed")
    RULE_ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    CHARS_REMOVED_FIELD_NUMBER: _ClassVar[int]
    rule_id: str
    type: TtsRuleType
    chars_removed: int
    def __init__(self, rule_id: _Optional[str] = ..., type: _Optional[_Union[TtsRuleType, str]] = ..., chars_removed: _Optional[int] = ...) -> None: ...

class TtsRuleList(_message.Message):
    __slots__ = ("rules",)
    RULES_FIELD_NUMBER: _ClassVar[int]
    rules: _containers.RepeatedCompositeFieldContainer[TtsRule]
    def __init__(self, rules: _Optional[_Iterable[_Union[TtsRule, _Mapping]]] = ...) -> None: ...

class TtsRule(_message.Message):
    __slots__ = ("id", "type", "language", "scope", "acronym", "pronunciation", "aliases", "pattern", "description", "strip_wrapping_parens", "replacement")
    ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    ACRONYM_FIELD_NUMBER: _ClassVar[int]
    PRONUNCIATION_FIELD_NUMBER: _ClassVar[int]
    ALIASES_FIELD_NUMBER: _ClassVar[int]
    PATTERN_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    STRIP_WRAPPING_PARENS_FIELD_NUMBER: _ClassVar[int]
    REPLACEMENT_FIELD_NUMBER: _ClassVar[int]
    id: str
    type: TtsRuleType
    language: str
    scope: TtsRuleScope
    acronym: str
    pronunciation: str
    aliases: _containers.RepeatedScalarFieldContainer[str]
    pattern: str
    description: str
    strip_wrapping_parens: bool
    replacement: str
    def __init__(self, id: _Optional[str] = ..., type: _Optional[_Union[TtsRuleType, str]] = ..., language: _Optional[str] = ..., scope: _Optional[_Union[TtsRuleScope, _Mapping]] = ..., acronym: _Optional[str] = ..., pronunciation: _Optional[str] = ..., aliases: _Optional[_Iterable[str]] = ..., pattern: _Optional[str] = ..., description: _Optional[str] = ..., strip_wrapping_parens: bool = ..., replacement: _Optional[str] = ...) -> None: ...

class TtsRuleScope(_message.Message):
    __slots__ = ("type", "client_id", "project_id")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    type: TtsRuleScopeType
    client_id: str
    project_id: str
    def __init__(self, type: _Optional[_Union[TtsRuleScopeType, str]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...
