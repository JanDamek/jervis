from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from typing import ClassVar as _ClassVar

DESCRIPTOR: _descriptor.FileDescriptor

class Capability(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CAPABILITY_UNSPECIFIED: _ClassVar[Capability]
    CAPABILITY_CHAT: _ClassVar[Capability]
    CAPABILITY_THINKING: _ClassVar[Capability]
    CAPABILITY_CODING: _ClassVar[Capability]
    CAPABILITY_EMBEDDING: _ClassVar[Capability]
    CAPABILITY_VISUAL: _ClassVar[Capability]
    CAPABILITY_EXTRACTION: _ClassVar[Capability]
    CAPABILITY_TRANSLATION: _ClassVar[Capability]

class Priority(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PRIORITY_UNSPECIFIED: _ClassVar[Priority]
    PRIORITY_BACKGROUND: _ClassVar[Priority]
    PRIORITY_FOREGROUND: _ClassVar[Priority]
    PRIORITY_CRITICAL: _ClassVar[Priority]

class TierCap(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TIER_CAP_UNSPECIFIED: _ClassVar[TierCap]
    TIER_CAP_NONE: _ClassVar[TierCap]
    TIER_CAP_T1: _ClassVar[TierCap]
    TIER_CAP_T2: _ClassVar[TierCap]

class SourceType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SOURCE_TYPE_UNSPECIFIED: _ClassVar[SourceType]
    SOURCE_TYPE_CHAT: _ClassVar[SourceType]
    SOURCE_TYPE_MEETING: _ClassVar[SourceType]
    SOURCE_TYPE_EMAIL: _ClassVar[SourceType]
    SOURCE_TYPE_DOCUMENT: _ClassVar[SourceType]
    SOURCE_TYPE_CODE: _ClassVar[SourceType]
    SOURCE_TYPE_COMMIT: _ClassVar[SourceType]
    SOURCE_TYPE_ISSUE: _ClassVar[SourceType]
    SOURCE_TYPE_MESSAGE: _ClassVar[SourceType]
    SOURCE_TYPE_AGENT_NOTE: _ClassVar[SourceType]
    SOURCE_TYPE_DECISION: _ClassVar[SourceType]
    SOURCE_TYPE_EXTRACTION: _ClassVar[SourceType]

class SourceCredibility(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SOURCE_CREDIBILITY_UNSPECIFIED: _ClassVar[SourceCredibility]
    SOURCE_CREDIBILITY_LOW: _ClassVar[SourceCredibility]
    SOURCE_CREDIBILITY_MEDIUM: _ClassVar[SourceCredibility]
    SOURCE_CREDIBILITY_HIGH: _ClassVar[SourceCredibility]
    SOURCE_CREDIBILITY_VERIFIED: _ClassVar[SourceCredibility]

class TaskState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TASK_STATE_UNSPECIFIED: _ClassVar[TaskState]
    TASK_STATE_NEW: _ClassVar[TaskState]
    TASK_STATE_INDEXING: _ClassVar[TaskState]
    TASK_STATE_QUEUED: _ClassVar[TaskState]
    TASK_STATE_PROCESSING: _ClassVar[TaskState]
    TASK_STATE_CODING: _ClassVar[TaskState]
    TASK_STATE_USER_TASK: _ClassVar[TaskState]
    TASK_STATE_BLOCKED: _ClassVar[TaskState]
    TASK_STATE_DONE: _ClassVar[TaskState]
    TASK_STATE_ERROR: _ClassVar[TaskState]

class GraphType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    GRAPH_TYPE_UNSPECIFIED: _ClassVar[GraphType]
    GRAPH_TYPE_ENTITY: _ClassVar[GraphType]
    GRAPH_TYPE_FACT: _ClassVar[GraphType]
    GRAPH_TYPE_CONCEPT: _ClassVar[GraphType]
    GRAPH_TYPE_CODE: _ClassVar[GraphType]
    GRAPH_TYPE_DOCUMENT: _ClassVar[GraphType]
    GRAPH_TYPE_THOUGHT: _ClassVar[GraphType]
CAPABILITY_UNSPECIFIED: Capability
CAPABILITY_CHAT: Capability
CAPABILITY_THINKING: Capability
CAPABILITY_CODING: Capability
CAPABILITY_EMBEDDING: Capability
CAPABILITY_VISUAL: Capability
CAPABILITY_EXTRACTION: Capability
CAPABILITY_TRANSLATION: Capability
PRIORITY_UNSPECIFIED: Priority
PRIORITY_BACKGROUND: Priority
PRIORITY_FOREGROUND: Priority
PRIORITY_CRITICAL: Priority
TIER_CAP_UNSPECIFIED: TierCap
TIER_CAP_NONE: TierCap
TIER_CAP_T1: TierCap
TIER_CAP_T2: TierCap
SOURCE_TYPE_UNSPECIFIED: SourceType
SOURCE_TYPE_CHAT: SourceType
SOURCE_TYPE_MEETING: SourceType
SOURCE_TYPE_EMAIL: SourceType
SOURCE_TYPE_DOCUMENT: SourceType
SOURCE_TYPE_CODE: SourceType
SOURCE_TYPE_COMMIT: SourceType
SOURCE_TYPE_ISSUE: SourceType
SOURCE_TYPE_MESSAGE: SourceType
SOURCE_TYPE_AGENT_NOTE: SourceType
SOURCE_TYPE_DECISION: SourceType
SOURCE_TYPE_EXTRACTION: SourceType
SOURCE_CREDIBILITY_UNSPECIFIED: SourceCredibility
SOURCE_CREDIBILITY_LOW: SourceCredibility
SOURCE_CREDIBILITY_MEDIUM: SourceCredibility
SOURCE_CREDIBILITY_HIGH: SourceCredibility
SOURCE_CREDIBILITY_VERIFIED: SourceCredibility
TASK_STATE_UNSPECIFIED: TaskState
TASK_STATE_NEW: TaskState
TASK_STATE_INDEXING: TaskState
TASK_STATE_QUEUED: TaskState
TASK_STATE_PROCESSING: TaskState
TASK_STATE_CODING: TaskState
TASK_STATE_USER_TASK: TaskState
TASK_STATE_BLOCKED: TaskState
TASK_STATE_DONE: TaskState
TASK_STATE_ERROR: TaskState
GRAPH_TYPE_UNSPECIFIED: GraphType
GRAPH_TYPE_ENTITY: GraphType
GRAPH_TYPE_FACT: GraphType
GRAPH_TYPE_CONCEPT: GraphType
GRAPH_TYPE_CODE: GraphType
GRAPH_TYPE_DOCUMENT: GraphType
GRAPH_TYPE_THOUGHT: GraphType
