from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class O365Request(_message.Message):
    __slots__ = ("ctx", "method", "path", "query", "body_json", "content_type")
    class QueryEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CTX_FIELD_NUMBER: _ClassVar[int]
    METHOD_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    method: str
    path: str
    query: _containers.ScalarMap[str, str]
    body_json: str
    content_type: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., method: _Optional[str] = ..., path: _Optional[str] = ..., query: _Optional[_Mapping[str, str]] = ..., body_json: _Optional[str] = ..., content_type: _Optional[str] = ...) -> None: ...

class O365Response(_message.Message):
    __slots__ = ("status_code", "body_json", "headers")
    class HeadersEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    STATUS_CODE_FIELD_NUMBER: _ClassVar[int]
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    HEADERS_FIELD_NUMBER: _ClassVar[int]
    status_code: int
    body_json: str
    headers: _containers.ScalarMap[str, str]
    def __init__(self, status_code: _Optional[int] = ..., body_json: _Optional[str] = ..., headers: _Optional[_Mapping[str, str]] = ...) -> None: ...

class O365BytesResponse(_message.Message):
    __slots__ = ("status_code", "body", "content_type", "filename", "headers")
    class HeadersEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    STATUS_CODE_FIELD_NUMBER: _ClassVar[int]
    BODY_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    HEADERS_FIELD_NUMBER: _ClassVar[int]
    status_code: int
    body: bytes
    content_type: str
    filename: str
    headers: _containers.ScalarMap[str, str]
    def __init__(self, status_code: _Optional[int] = ..., body: _Optional[bytes] = ..., content_type: _Optional[str] = ..., filename: _Optional[str] = ..., headers: _Optional[_Mapping[str, str]] = ...) -> None: ...

class GraphUser(_message.Message):
    __slots__ = ("id", "display_name")
    ID_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    id: str
    display_name: str
    def __init__(self, id: _Optional[str] = ..., display_name: _Optional[str] = ...) -> None: ...

class GraphApplication(_message.Message):
    __slots__ = ("id", "display_name")
    ID_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    id: str
    display_name: str
    def __init__(self, id: _Optional[str] = ..., display_name: _Optional[str] = ...) -> None: ...

class MessageBody(_message.Message):
    __slots__ = ("content_type", "content")
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    content_type: str
    content: str
    def __init__(self, content_type: _Optional[str] = ..., content: _Optional[str] = ...) -> None: ...

class MessageFrom(_message.Message):
    __slots__ = ("user", "application")
    USER_FIELD_NUMBER: _ClassVar[int]
    APPLICATION_FIELD_NUMBER: _ClassVar[int]
    user: GraphUser
    application: GraphApplication
    def __init__(self, user: _Optional[_Union[GraphUser, _Mapping]] = ..., application: _Optional[_Union[GraphApplication, _Mapping]] = ...) -> None: ...

class MessagePreview(_message.Message):
    __slots__ = ("id", "created_date_time", "body", "sender")
    ID_FIELD_NUMBER: _ClassVar[int]
    CREATED_DATE_TIME_FIELD_NUMBER: _ClassVar[int]
    BODY_FIELD_NUMBER: _ClassVar[int]
    SENDER_FIELD_NUMBER: _ClassVar[int]
    id: str
    created_date_time: str
    body: MessageBody
    sender: MessageFrom
    def __init__(self, id: _Optional[str] = ..., created_date_time: _Optional[str] = ..., body: _Optional[_Union[MessageBody, _Mapping]] = ..., sender: _Optional[_Union[MessageFrom, _Mapping]] = ...) -> None: ...

class ChatMessage(_message.Message):
    __slots__ = ("id", "created_date_time", "last_modified_date_time", "body", "sender", "message_type")
    ID_FIELD_NUMBER: _ClassVar[int]
    CREATED_DATE_TIME_FIELD_NUMBER: _ClassVar[int]
    LAST_MODIFIED_DATE_TIME_FIELD_NUMBER: _ClassVar[int]
    BODY_FIELD_NUMBER: _ClassVar[int]
    SENDER_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_TYPE_FIELD_NUMBER: _ClassVar[int]
    id: str
    created_date_time: str
    last_modified_date_time: str
    body: MessageBody
    sender: MessageFrom
    message_type: str
    def __init__(self, id: _Optional[str] = ..., created_date_time: _Optional[str] = ..., last_modified_date_time: _Optional[str] = ..., body: _Optional[_Union[MessageBody, _Mapping]] = ..., sender: _Optional[_Union[MessageFrom, _Mapping]] = ..., message_type: _Optional[str] = ...) -> None: ...

class ChatSummary(_message.Message):
    __slots__ = ("id", "topic", "chat_type", "created_date_time", "last_updated_date_time", "last_message_preview")
    ID_FIELD_NUMBER: _ClassVar[int]
    TOPIC_FIELD_NUMBER: _ClassVar[int]
    CHAT_TYPE_FIELD_NUMBER: _ClassVar[int]
    CREATED_DATE_TIME_FIELD_NUMBER: _ClassVar[int]
    LAST_UPDATED_DATE_TIME_FIELD_NUMBER: _ClassVar[int]
    LAST_MESSAGE_PREVIEW_FIELD_NUMBER: _ClassVar[int]
    id: str
    topic: str
    chat_type: str
    created_date_time: str
    last_updated_date_time: str
    last_message_preview: MessagePreview
    def __init__(self, id: _Optional[str] = ..., topic: _Optional[str] = ..., chat_type: _Optional[str] = ..., created_date_time: _Optional[str] = ..., last_updated_date_time: _Optional[str] = ..., last_message_preview: _Optional[_Union[MessagePreview, _Mapping]] = ...) -> None: ...

class ListChatsRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "top")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    TOP_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    top: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., top: _Optional[int] = ...) -> None: ...

class ListChatsResponse(_message.Message):
    __slots__ = ("chats",)
    CHATS_FIELD_NUMBER: _ClassVar[int]
    chats: _containers.RepeatedCompositeFieldContainer[ChatSummary]
    def __init__(self, chats: _Optional[_Iterable[_Union[ChatSummary, _Mapping]]] = ...) -> None: ...

class ReadChatRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "chat_id", "top")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    CHAT_ID_FIELD_NUMBER: _ClassVar[int]
    TOP_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    chat_id: str
    top: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., chat_id: _Optional[str] = ..., top: _Optional[int] = ...) -> None: ...

class ListChatMessagesResponse(_message.Message):
    __slots__ = ("messages",)
    MESSAGES_FIELD_NUMBER: _ClassVar[int]
    messages: _containers.RepeatedCompositeFieldContainer[ChatMessage]
    def __init__(self, messages: _Optional[_Iterable[_Union[ChatMessage, _Mapping]]] = ...) -> None: ...

class SendChatMessageRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "chat_id", "content", "content_type")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    CHAT_ID_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    chat_id: str
    content: str
    content_type: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., chat_id: _Optional[str] = ..., content: _Optional[str] = ..., content_type: _Optional[str] = ...) -> None: ...
