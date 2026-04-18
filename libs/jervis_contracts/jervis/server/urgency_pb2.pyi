from jervis.common import types_pb2 as _types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GetUrgencyConfigRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class UpdateUrgencyConfigRequest(_message.Message):
    __slots__ = ("ctx", "config")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CONFIG_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    config: UrgencyConfig
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., config: _Optional[_Union[UrgencyConfig, _Mapping]] = ...) -> None: ...

class GetUserPresenceRequest(_message.Message):
    __slots__ = ("ctx", "user_id", "platform")
    CTX_FIELD_NUMBER: _ClassVar[int]
    USER_ID_FIELD_NUMBER: _ClassVar[int]
    PLATFORM_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    user_id: str
    platform: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., user_id: _Optional[str] = ..., platform: _Optional[str] = ...) -> None: ...

class BumpDeadlineRequest(_message.Message):
    __slots__ = ("ctx", "task_id", "deadline_iso", "reason")
    CTX_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    DEADLINE_ISO_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    task_id: str
    deadline_iso: str
    reason: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., task_id: _Optional[str] = ..., deadline_iso: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class UrgencyConfig(_message.Message):
    __slots__ = ("client_id", "default_deadline_minutes", "fast_path_deadline_minutes", "presence_factor", "presence_ttl_seconds", "classifier_budget_per_hour_per_sender", "approaching_deadline_threshold_pct")
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_DEADLINE_MINUTES_FIELD_NUMBER: _ClassVar[int]
    FAST_PATH_DEADLINE_MINUTES_FIELD_NUMBER: _ClassVar[int]
    PRESENCE_FACTOR_FIELD_NUMBER: _ClassVar[int]
    PRESENCE_TTL_SECONDS_FIELD_NUMBER: _ClassVar[int]
    CLASSIFIER_BUDGET_PER_HOUR_PER_SENDER_FIELD_NUMBER: _ClassVar[int]
    APPROACHING_DEADLINE_THRESHOLD_PCT_FIELD_NUMBER: _ClassVar[int]
    client_id: str
    default_deadline_minutes: int
    fast_path_deadline_minutes: FastPathDeadlines
    presence_factor: PresenceFactor
    presence_ttl_seconds: int
    classifier_budget_per_hour_per_sender: int
    approaching_deadline_threshold_pct: float
    def __init__(self, client_id: _Optional[str] = ..., default_deadline_minutes: _Optional[int] = ..., fast_path_deadline_minutes: _Optional[_Union[FastPathDeadlines, _Mapping]] = ..., presence_factor: _Optional[_Union[PresenceFactor, _Mapping]] = ..., presence_ttl_seconds: _Optional[int] = ..., classifier_budget_per_hour_per_sender: _Optional[int] = ..., approaching_deadline_threshold_pct: _Optional[float] = ...) -> None: ...

class FastPathDeadlines(_message.Message):
    __slots__ = ("direct_message", "channel_mention", "reply_my_thread_active", "reply_my_thread_stale")
    DIRECT_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_MENTION_FIELD_NUMBER: _ClassVar[int]
    REPLY_MY_THREAD_ACTIVE_FIELD_NUMBER: _ClassVar[int]
    REPLY_MY_THREAD_STALE_FIELD_NUMBER: _ClassVar[int]
    direct_message: int
    channel_mention: int
    reply_my_thread_active: int
    reply_my_thread_stale: int
    def __init__(self, direct_message: _Optional[int] = ..., channel_mention: _Optional[int] = ..., reply_my_thread_active: _Optional[int] = ..., reply_my_thread_stale: _Optional[int] = ...) -> None: ...

class PresenceFactor(_message.Message):
    __slots__ = ("active", "away_recent", "away_old", "offline", "unknown")
    ACTIVE_FIELD_NUMBER: _ClassVar[int]
    AWAY_RECENT_FIELD_NUMBER: _ClassVar[int]
    AWAY_OLD_FIELD_NUMBER: _ClassVar[int]
    OFFLINE_FIELD_NUMBER: _ClassVar[int]
    UNKNOWN_FIELD_NUMBER: _ClassVar[int]
    active: float
    away_recent: float
    away_old: float
    offline: float
    unknown: float
    def __init__(self, active: _Optional[float] = ..., away_recent: _Optional[float] = ..., away_old: _Optional[float] = ..., offline: _Optional[float] = ..., unknown: _Optional[float] = ...) -> None: ...

class UserPresence(_message.Message):
    __slots__ = ("user_id", "platform", "presence", "last_active_at_iso")
    USER_ID_FIELD_NUMBER: _ClassVar[int]
    PLATFORM_FIELD_NUMBER: _ClassVar[int]
    PRESENCE_FIELD_NUMBER: _ClassVar[int]
    LAST_ACTIVE_AT_ISO_FIELD_NUMBER: _ClassVar[int]
    user_id: str
    platform: str
    presence: str
    last_active_at_iso: str
    def __init__(self, user_id: _Optional[str] = ..., platform: _Optional[str] = ..., presence: _Optional[str] = ..., last_active_at_iso: _Optional[str] = ...) -> None: ...

class BumpDeadlineResponse(_message.Message):
    __slots__ = ("ok", "error")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ...) -> None: ...
