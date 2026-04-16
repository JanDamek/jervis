from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class LogTimeRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "date_iso", "hours", "description", "source", "billable", "billable_set")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    DATE_ISO_FIELD_NUMBER: _ClassVar[int]
    HOURS_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    BILLABLE_FIELD_NUMBER: _ClassVar[int]
    BILLABLE_SET_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    date_iso: str
    hours: float
    description: str
    source: str
    billable: bool
    billable_set: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., date_iso: _Optional[str] = ..., hours: _Optional[float] = ..., description: _Optional[str] = ..., source: _Optional[str] = ..., billable: bool = ..., billable_set: bool = ...) -> None: ...

class LogTimeResponse(_message.Message):
    __slots__ = ("id", "hours", "date_iso")
    ID_FIELD_NUMBER: _ClassVar[int]
    HOURS_FIELD_NUMBER: _ClassVar[int]
    DATE_ISO_FIELD_NUMBER: _ClassVar[int]
    id: str
    hours: float
    date_iso: str
    def __init__(self, id: _Optional[str] = ..., hours: _Optional[float] = ..., date_iso: _Optional[str] = ...) -> None: ...

class GetSummaryRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "from_iso", "to_iso")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    FROM_ISO_FIELD_NUMBER: _ClassVar[int]
    TO_ISO_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    from_iso: str
    to_iso: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., from_iso: _Optional[str] = ..., to_iso: _Optional[str] = ...) -> None: ...

class GetSummaryResponse(_message.Message):
    __slots__ = ("total_hours", "billable_hours", "by_client", "entry_count")
    class ByClientEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: float
        def __init__(self, key: _Optional[str] = ..., value: _Optional[float] = ...) -> None: ...
    TOTAL_HOURS_FIELD_NUMBER: _ClassVar[int]
    BILLABLE_HOURS_FIELD_NUMBER: _ClassVar[int]
    BY_CLIENT_FIELD_NUMBER: _ClassVar[int]
    ENTRY_COUNT_FIELD_NUMBER: _ClassVar[int]
    total_hours: float
    billable_hours: float
    by_client: _containers.ScalarMap[str, float]
    entry_count: int
    def __init__(self, total_hours: _Optional[float] = ..., billable_hours: _Optional[float] = ..., by_client: _Optional[_Mapping[str, float]] = ..., entry_count: _Optional[int] = ...) -> None: ...

class GetCapacityRequest(_message.Message):
    __slots__ = ("ctx",)
    CTX_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ...) -> None: ...

class GetCapacityResponse(_message.Message):
    __slots__ = ("total_hours_per_week", "committed", "actual_this_week", "available_hours")
    class ActualThisWeekEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: float
        def __init__(self, key: _Optional[str] = ..., value: _Optional[float] = ...) -> None: ...
    TOTAL_HOURS_PER_WEEK_FIELD_NUMBER: _ClassVar[int]
    COMMITTED_FIELD_NUMBER: _ClassVar[int]
    ACTUAL_THIS_WEEK_FIELD_NUMBER: _ClassVar[int]
    AVAILABLE_HOURS_FIELD_NUMBER: _ClassVar[int]
    total_hours_per_week: float
    committed: _containers.RepeatedCompositeFieldContainer[CommittedCapacity]
    actual_this_week: _containers.ScalarMap[str, float]
    available_hours: float
    def __init__(self, total_hours_per_week: _Optional[float] = ..., committed: _Optional[_Iterable[_Union[CommittedCapacity, _Mapping]]] = ..., actual_this_week: _Optional[_Mapping[str, float]] = ..., available_hours: _Optional[float] = ...) -> None: ...

class CommittedCapacity(_message.Message):
    __slots__ = ("client_id", "counterparty", "hours_per_week")
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_FIELD_NUMBER: _ClassVar[int]
    HOURS_PER_WEEK_FIELD_NUMBER: _ClassVar[int]
    client_id: str
    counterparty: str
    hours_per_week: float
    def __init__(self, client_id: _Optional[str] = ..., counterparty: _Optional[str] = ..., hours_per_week: _Optional[float] = ...) -> None: ...
