from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CreateFilterRuleRequest(_message.Message):
    __slots__ = ("ctx", "source_type", "condition_type", "condition_value", "action", "description", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SOURCE_TYPE_FIELD_NUMBER: _ClassVar[int]
    CONDITION_TYPE_FIELD_NUMBER: _ClassVar[int]
    CONDITION_VALUE_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    source_type: str
    condition_type: str
    condition_value: str
    action: str
    description: str
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., source_type: _Optional[str] = ..., condition_type: _Optional[str] = ..., condition_value: _Optional[str] = ..., action: _Optional[str] = ..., description: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class ListFilterRulesRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class RemoveFilterRuleRequest(_message.Message):
    __slots__ = ("ctx", "rule_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    RULE_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    rule_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., rule_id: _Optional[str] = ...) -> None: ...

class FilterRule(_message.Message):
    __slots__ = ("id", "scope", "source_type", "condition_type", "condition_value", "action", "description", "created_at", "created_by", "enabled")
    ID_FIELD_NUMBER: _ClassVar[int]
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_TYPE_FIELD_NUMBER: _ClassVar[int]
    CONDITION_TYPE_FIELD_NUMBER: _ClassVar[int]
    CONDITION_VALUE_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    CREATED_BY_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    id: str
    scope: str
    source_type: str
    condition_type: str
    condition_value: str
    action: str
    description: str
    created_at: str
    created_by: str
    enabled: bool
    def __init__(self, id: _Optional[str] = ..., scope: _Optional[str] = ..., source_type: _Optional[str] = ..., condition_type: _Optional[str] = ..., condition_value: _Optional[str] = ..., action: _Optional[str] = ..., description: _Optional[str] = ..., created_at: _Optional[str] = ..., created_by: _Optional[str] = ..., enabled: bool = ...) -> None: ...

class FilterRuleList(_message.Message):
    __slots__ = ("rules",)
    RULES_FIELD_NUMBER: _ClassVar[int]
    rules: _containers.RepeatedCompositeFieldContainer[FilterRule]
    def __init__(self, rules: _Optional[_Iterable[_Union[FilterRule, _Mapping]]] = ...) -> None: ...

class RemoveFilterRuleResponse(_message.Message):
    __slots__ = ("removed",)
    REMOVED_FIELD_NUMBER: _ClassVar[int]
    removed: bool
    def __init__(self, removed: bool = ...) -> None: ...
