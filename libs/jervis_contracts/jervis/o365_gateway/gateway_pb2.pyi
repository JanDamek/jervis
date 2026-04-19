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

class Team(_message.Message):
    __slots__ = ("id", "display_name", "description")
    ID_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    id: str
    display_name: str
    description: str
    def __init__(self, id: _Optional[str] = ..., display_name: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class Channel(_message.Message):
    __slots__ = ("id", "display_name", "description", "membership_type")
    ID_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    MEMBERSHIP_TYPE_FIELD_NUMBER: _ClassVar[int]
    id: str
    display_name: str
    description: str
    membership_type: str
    def __init__(self, id: _Optional[str] = ..., display_name: _Optional[str] = ..., description: _Optional[str] = ..., membership_type: _Optional[str] = ...) -> None: ...

class ListTeamsRequest(_message.Message):
    __slots__ = ("ctx", "client_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ...) -> None: ...

class ListTeamsResponse(_message.Message):
    __slots__ = ("teams",)
    TEAMS_FIELD_NUMBER: _ClassVar[int]
    teams: _containers.RepeatedCompositeFieldContainer[Team]
    def __init__(self, teams: _Optional[_Iterable[_Union[Team, _Mapping]]] = ...) -> None: ...

class ListChannelsRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "team_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    TEAM_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    team_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., team_id: _Optional[str] = ...) -> None: ...

class ListChannelsResponse(_message.Message):
    __slots__ = ("channels",)
    CHANNELS_FIELD_NUMBER: _ClassVar[int]
    channels: _containers.RepeatedCompositeFieldContainer[Channel]
    def __init__(self, channels: _Optional[_Iterable[_Union[Channel, _Mapping]]] = ...) -> None: ...

class ReadChannelRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "team_id", "channel_id", "top")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    TEAM_ID_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_ID_FIELD_NUMBER: _ClassVar[int]
    TOP_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    team_id: str
    channel_id: str
    top: int
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., team_id: _Optional[str] = ..., channel_id: _Optional[str] = ..., top: _Optional[int] = ...) -> None: ...

class ListChannelMessagesResponse(_message.Message):
    __slots__ = ("messages",)
    MESSAGES_FIELD_NUMBER: _ClassVar[int]
    messages: _containers.RepeatedCompositeFieldContainer[ChatMessage]
    def __init__(self, messages: _Optional[_Iterable[_Union[ChatMessage, _Mapping]]] = ...) -> None: ...

class SendChannelMessageRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "team_id", "channel_id", "content", "content_type")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    TEAM_ID_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_ID_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    team_id: str
    channel_id: str
    content: str
    content_type: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., team_id: _Optional[str] = ..., channel_id: _Optional[str] = ..., content: _Optional[str] = ..., content_type: _Optional[str] = ...) -> None: ...

class EmailAddress(_message.Message):
    __slots__ = ("name", "address")
    NAME_FIELD_NUMBER: _ClassVar[int]
    ADDRESS_FIELD_NUMBER: _ClassVar[int]
    name: str
    address: str
    def __init__(self, name: _Optional[str] = ..., address: _Optional[str] = ...) -> None: ...

class Recipient(_message.Message):
    __slots__ = ("email_address",)
    EMAIL_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    email_address: EmailAddress
    def __init__(self, email_address: _Optional[_Union[EmailAddress, _Mapping]] = ...) -> None: ...

class MailBody(_message.Message):
    __slots__ = ("content_type", "content")
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    content_type: str
    content: str
    def __init__(self, content_type: _Optional[str] = ..., content: _Optional[str] = ...) -> None: ...

class MailSender(_message.Message):
    __slots__ = ("email_address",)
    EMAIL_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    email_address: EmailAddress
    def __init__(self, email_address: _Optional[_Union[EmailAddress, _Mapping]] = ...) -> None: ...

class MailMessage(_message.Message):
    __slots__ = ("id", "subject", "body_preview", "body", "sender", "to_recipients", "cc_recipients", "received_date_time", "sent_date_time", "is_read", "is_draft", "has_attachments", "importance", "conversation_id")
    ID_FIELD_NUMBER: _ClassVar[int]
    SUBJECT_FIELD_NUMBER: _ClassVar[int]
    BODY_PREVIEW_FIELD_NUMBER: _ClassVar[int]
    BODY_FIELD_NUMBER: _ClassVar[int]
    SENDER_FIELD_NUMBER: _ClassVar[int]
    TO_RECIPIENTS_FIELD_NUMBER: _ClassVar[int]
    CC_RECIPIENTS_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_DATE_TIME_FIELD_NUMBER: _ClassVar[int]
    SENT_DATE_TIME_FIELD_NUMBER: _ClassVar[int]
    IS_READ_FIELD_NUMBER: _ClassVar[int]
    IS_DRAFT_FIELD_NUMBER: _ClassVar[int]
    HAS_ATTACHMENTS_FIELD_NUMBER: _ClassVar[int]
    IMPORTANCE_FIELD_NUMBER: _ClassVar[int]
    CONVERSATION_ID_FIELD_NUMBER: _ClassVar[int]
    id: str
    subject: str
    body_preview: str
    body: MailBody
    sender: MailSender
    to_recipients: _containers.RepeatedCompositeFieldContainer[Recipient]
    cc_recipients: _containers.RepeatedCompositeFieldContainer[Recipient]
    received_date_time: str
    sent_date_time: str
    is_read: bool
    is_draft: bool
    has_attachments: bool
    importance: str
    conversation_id: str
    def __init__(self, id: _Optional[str] = ..., subject: _Optional[str] = ..., body_preview: _Optional[str] = ..., body: _Optional[_Union[MailBody, _Mapping]] = ..., sender: _Optional[_Union[MailSender, _Mapping]] = ..., to_recipients: _Optional[_Iterable[_Union[Recipient, _Mapping]]] = ..., cc_recipients: _Optional[_Iterable[_Union[Recipient, _Mapping]]] = ..., received_date_time: _Optional[str] = ..., sent_date_time: _Optional[str] = ..., is_read: bool = ..., is_draft: bool = ..., has_attachments: bool = ..., importance: _Optional[str] = ..., conversation_id: _Optional[str] = ...) -> None: ...

class ListMailRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "top", "folder", "filter")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    TOP_FIELD_NUMBER: _ClassVar[int]
    FOLDER_FIELD_NUMBER: _ClassVar[int]
    FILTER_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    top: int
    folder: str
    filter: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., top: _Optional[int] = ..., folder: _Optional[str] = ..., filter: _Optional[str] = ...) -> None: ...

class ListMailResponse(_message.Message):
    __slots__ = ("messages",)
    MESSAGES_FIELD_NUMBER: _ClassVar[int]
    messages: _containers.RepeatedCompositeFieldContainer[MailMessage]
    def __init__(self, messages: _Optional[_Iterable[_Union[MailMessage, _Mapping]]] = ...) -> None: ...

class ReadMailRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "message_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    message_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., message_id: _Optional[str] = ...) -> None: ...

class SendMailRpcRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "subject", "body", "to_recipients", "cc_recipients", "save_to_sent_items")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    SUBJECT_FIELD_NUMBER: _ClassVar[int]
    BODY_FIELD_NUMBER: _ClassVar[int]
    TO_RECIPIENTS_FIELD_NUMBER: _ClassVar[int]
    CC_RECIPIENTS_FIELD_NUMBER: _ClassVar[int]
    SAVE_TO_SENT_ITEMS_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    subject: str
    body: MailBody
    to_recipients: _containers.RepeatedCompositeFieldContainer[Recipient]
    cc_recipients: _containers.RepeatedCompositeFieldContainer[Recipient]
    save_to_sent_items: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., subject: _Optional[str] = ..., body: _Optional[_Union[MailBody, _Mapping]] = ..., to_recipients: _Optional[_Iterable[_Union[Recipient, _Mapping]]] = ..., cc_recipients: _Optional[_Iterable[_Union[Recipient, _Mapping]]] = ..., save_to_sent_items: bool = ...) -> None: ...

class SendMailAck(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: str
    def __init__(self, result: _Optional[str] = ...) -> None: ...
