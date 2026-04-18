from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GuidelinesScope(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    GUIDELINES_SCOPE_UNSPECIFIED: _ClassVar[GuidelinesScope]
    GUIDELINES_SCOPE_GLOBAL: _ClassVar[GuidelinesScope]
    GUIDELINES_SCOPE_CLIENT: _ClassVar[GuidelinesScope]
    GUIDELINES_SCOPE_PROJECT: _ClassVar[GuidelinesScope]
GUIDELINES_SCOPE_UNSPECIFIED: GuidelinesScope
GUIDELINES_SCOPE_GLOBAL: GuidelinesScope
GUIDELINES_SCOPE_CLIENT: GuidelinesScope
GUIDELINES_SCOPE_PROJECT: GuidelinesScope

class GetMergedRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class GetRequest(_message.Message):
    __slots__ = ("ctx", "scope", "client_id", "project_id")
    CTX_FIELD_NUMBER: _ClassVar[int]
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    scope: GuidelinesScope
    client_id: str
    project_id: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., scope: _Optional[_Union[GuidelinesScope, str]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ...) -> None: ...

class SetRequest(_message.Message):
    __slots__ = ("ctx", "update")
    CTX_FIELD_NUMBER: _ClassVar[int]
    UPDATE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    update: GuidelinesUpdateRequest
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., update: _Optional[_Union[GuidelinesUpdateRequest, _Mapping]] = ...) -> None: ...

class PatternRule(_message.Message):
    __slots__ = ("pattern", "description", "severity", "file_glob")
    PATTERN_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    SEVERITY_FIELD_NUMBER: _ClassVar[int]
    FILE_GLOB_FIELD_NUMBER: _ClassVar[int]
    pattern: str
    description: str
    severity: str
    file_glob: str
    def __init__(self, pattern: _Optional[str] = ..., description: _Optional[str] = ..., severity: _Optional[str] = ..., file_glob: _Optional[str] = ...) -> None: ...

class LanguageRules(_message.Message):
    __slots__ = ("naming_convention", "forbidden_imports", "required_imports", "max_file_lines")
    NAMING_CONVENTION_FIELD_NUMBER: _ClassVar[int]
    FORBIDDEN_IMPORTS_FIELD_NUMBER: _ClassVar[int]
    REQUIRED_IMPORTS_FIELD_NUMBER: _ClassVar[int]
    MAX_FILE_LINES_FIELD_NUMBER: _ClassVar[int]
    naming_convention: str
    forbidden_imports: _containers.RepeatedScalarFieldContainer[str]
    required_imports: _containers.RepeatedScalarFieldContainer[str]
    max_file_lines: int
    def __init__(self, naming_convention: _Optional[str] = ..., forbidden_imports: _Optional[_Iterable[str]] = ..., required_imports: _Optional[_Iterable[str]] = ..., max_file_lines: _Optional[int] = ...) -> None: ...

class CodingGuidelines(_message.Message):
    __slots__ = ("forbidden_patterns", "required_patterns", "max_file_lines", "max_function_lines", "naming_conventions", "language_specific", "principles")
    class NamingConventionsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    class LanguageSpecificEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: LanguageRules
        def __init__(self, key: _Optional[str] = ..., value: _Optional[_Union[LanguageRules, _Mapping]] = ...) -> None: ...
    FORBIDDEN_PATTERNS_FIELD_NUMBER: _ClassVar[int]
    REQUIRED_PATTERNS_FIELD_NUMBER: _ClassVar[int]
    MAX_FILE_LINES_FIELD_NUMBER: _ClassVar[int]
    MAX_FUNCTION_LINES_FIELD_NUMBER: _ClassVar[int]
    NAMING_CONVENTIONS_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_SPECIFIC_FIELD_NUMBER: _ClassVar[int]
    PRINCIPLES_FIELD_NUMBER: _ClassVar[int]
    forbidden_patterns: _containers.RepeatedCompositeFieldContainer[PatternRule]
    required_patterns: _containers.RepeatedCompositeFieldContainer[PatternRule]
    max_file_lines: int
    max_function_lines: int
    naming_conventions: _containers.ScalarMap[str, str]
    language_specific: _containers.MessageMap[str, LanguageRules]
    principles: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, forbidden_patterns: _Optional[_Iterable[_Union[PatternRule, _Mapping]]] = ..., required_patterns: _Optional[_Iterable[_Union[PatternRule, _Mapping]]] = ..., max_file_lines: _Optional[int] = ..., max_function_lines: _Optional[int] = ..., naming_conventions: _Optional[_Mapping[str, str]] = ..., language_specific: _Optional[_Mapping[str, LanguageRules]] = ..., principles: _Optional[_Iterable[str]] = ...) -> None: ...

class GitGuidelines(_message.Message):
    __slots__ = ("commit_message_template", "commit_message_validators", "branch_name_template", "require_jira_reference", "squash_on_merge", "protected_branches")
    COMMIT_MESSAGE_TEMPLATE_FIELD_NUMBER: _ClassVar[int]
    COMMIT_MESSAGE_VALIDATORS_FIELD_NUMBER: _ClassVar[int]
    BRANCH_NAME_TEMPLATE_FIELD_NUMBER: _ClassVar[int]
    REQUIRE_JIRA_REFERENCE_FIELD_NUMBER: _ClassVar[int]
    SQUASH_ON_MERGE_FIELD_NUMBER: _ClassVar[int]
    PROTECTED_BRANCHES_FIELD_NUMBER: _ClassVar[int]
    commit_message_template: str
    commit_message_validators: _containers.RepeatedScalarFieldContainer[str]
    branch_name_template: str
    require_jira_reference: bool
    squash_on_merge: bool
    protected_branches: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, commit_message_template: _Optional[str] = ..., commit_message_validators: _Optional[_Iterable[str]] = ..., branch_name_template: _Optional[str] = ..., require_jira_reference: bool = ..., squash_on_merge: bool = ..., protected_branches: _Optional[_Iterable[str]] = ...) -> None: ...

class ReviewChecklistItem(_message.Message):
    __slots__ = ("id", "label", "severity", "enabled")
    ID_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    SEVERITY_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    id: str
    label: str
    severity: str
    enabled: bool
    def __init__(self, id: _Optional[str] = ..., label: _Optional[str] = ..., severity: _Optional[str] = ..., enabled: bool = ...) -> None: ...

class ReviewGuidelines(_message.Message):
    __slots__ = ("must_have_tests", "must_pass_lint", "max_changed_files", "max_changed_lines", "forbidden_file_changes", "focus_areas", "checklist_items", "language_review_rules")
    class LanguageReviewRulesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: LanguageReviewRules
        def __init__(self, key: _Optional[str] = ..., value: _Optional[_Union[LanguageReviewRules, _Mapping]] = ...) -> None: ...
    MUST_HAVE_TESTS_FIELD_NUMBER: _ClassVar[int]
    MUST_PASS_LINT_FIELD_NUMBER: _ClassVar[int]
    MAX_CHANGED_FILES_FIELD_NUMBER: _ClassVar[int]
    MAX_CHANGED_LINES_FIELD_NUMBER: _ClassVar[int]
    FORBIDDEN_FILE_CHANGES_FIELD_NUMBER: _ClassVar[int]
    FOCUS_AREAS_FIELD_NUMBER: _ClassVar[int]
    CHECKLIST_ITEMS_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_REVIEW_RULES_FIELD_NUMBER: _ClassVar[int]
    must_have_tests: bool
    must_pass_lint: bool
    max_changed_files: int
    max_changed_lines: int
    forbidden_file_changes: _containers.RepeatedScalarFieldContainer[str]
    focus_areas: _containers.RepeatedScalarFieldContainer[str]
    checklist_items: _containers.RepeatedCompositeFieldContainer[ReviewChecklistItem]
    language_review_rules: _containers.MessageMap[str, LanguageReviewRules]
    def __init__(self, must_have_tests: bool = ..., must_pass_lint: bool = ..., max_changed_files: _Optional[int] = ..., max_changed_lines: _Optional[int] = ..., forbidden_file_changes: _Optional[_Iterable[str]] = ..., focus_areas: _Optional[_Iterable[str]] = ..., checklist_items: _Optional[_Iterable[_Union[ReviewChecklistItem, _Mapping]]] = ..., language_review_rules: _Optional[_Mapping[str, LanguageReviewRules]] = ...) -> None: ...

class LanguageReviewRules(_message.Message):
    __slots__ = ("rules",)
    RULES_FIELD_NUMBER: _ClassVar[int]
    rules: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, rules: _Optional[_Iterable[str]] = ...) -> None: ...

class CommunicationGuidelines(_message.Message):
    __slots__ = ("email_response_language", "email_signature", "comment_style", "jira_comment_language", "formality_level", "custom_rules")
    EMAIL_RESPONSE_LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    EMAIL_SIGNATURE_FIELD_NUMBER: _ClassVar[int]
    COMMENT_STYLE_FIELD_NUMBER: _ClassVar[int]
    JIRA_COMMENT_LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    FORMALITY_LEVEL_FIELD_NUMBER: _ClassVar[int]
    CUSTOM_RULES_FIELD_NUMBER: _ClassVar[int]
    email_response_language: str
    email_signature: str
    comment_style: str
    jira_comment_language: str
    formality_level: str
    custom_rules: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, email_response_language: _Optional[str] = ..., email_signature: _Optional[str] = ..., comment_style: _Optional[str] = ..., jira_comment_language: _Optional[str] = ..., formality_level: _Optional[str] = ..., custom_rules: _Optional[_Iterable[str]] = ...) -> None: ...

class ApprovalRule(_message.Message):
    __slots__ = ("enabled", "when_risk_level_below", "when_confidence_above")
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    WHEN_RISK_LEVEL_BELOW_FIELD_NUMBER: _ClassVar[int]
    WHEN_CONFIDENCE_ABOVE_FIELD_NUMBER: _ClassVar[int]
    enabled: bool
    when_risk_level_below: str
    when_confidence_above: float
    def __init__(self, enabled: bool = ..., when_risk_level_below: _Optional[str] = ..., when_confidence_above: _Optional[float] = ...) -> None: ...

class ApprovalGuidelines(_message.Message):
    __slots__ = ("auto_approve_commit", "auto_approve_push", "auto_approve_email", "auto_approve_pr_comment", "auto_approve_chat_reply", "auto_approve_coding_dispatch")
    AUTO_APPROVE_COMMIT_FIELD_NUMBER: _ClassVar[int]
    AUTO_APPROVE_PUSH_FIELD_NUMBER: _ClassVar[int]
    AUTO_APPROVE_EMAIL_FIELD_NUMBER: _ClassVar[int]
    AUTO_APPROVE_PR_COMMENT_FIELD_NUMBER: _ClassVar[int]
    AUTO_APPROVE_CHAT_REPLY_FIELD_NUMBER: _ClassVar[int]
    AUTO_APPROVE_CODING_DISPATCH_FIELD_NUMBER: _ClassVar[int]
    auto_approve_commit: ApprovalRule
    auto_approve_push: ApprovalRule
    auto_approve_email: ApprovalRule
    auto_approve_pr_comment: ApprovalRule
    auto_approve_chat_reply: ApprovalRule
    auto_approve_coding_dispatch: ApprovalRule
    def __init__(self, auto_approve_commit: _Optional[_Union[ApprovalRule, _Mapping]] = ..., auto_approve_push: _Optional[_Union[ApprovalRule, _Mapping]] = ..., auto_approve_email: _Optional[_Union[ApprovalRule, _Mapping]] = ..., auto_approve_pr_comment: _Optional[_Union[ApprovalRule, _Mapping]] = ..., auto_approve_chat_reply: _Optional[_Union[ApprovalRule, _Mapping]] = ..., auto_approve_coding_dispatch: _Optional[_Union[ApprovalRule, _Mapping]] = ...) -> None: ...

class GeneralGuidelines(_message.Message):
    __slots__ = ("custom_rules", "notes")
    CUSTOM_RULES_FIELD_NUMBER: _ClassVar[int]
    NOTES_FIELD_NUMBER: _ClassVar[int]
    custom_rules: _containers.RepeatedScalarFieldContainer[str]
    notes: str
    def __init__(self, custom_rules: _Optional[_Iterable[str]] = ..., notes: _Optional[str] = ...) -> None: ...

class GuidelinesDocument(_message.Message):
    __slots__ = ("id", "scope", "client_id", "project_id", "coding", "git", "review", "communication", "approval", "general")
    ID_FIELD_NUMBER: _ClassVar[int]
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    CODING_FIELD_NUMBER: _ClassVar[int]
    GIT_FIELD_NUMBER: _ClassVar[int]
    REVIEW_FIELD_NUMBER: _ClassVar[int]
    COMMUNICATION_FIELD_NUMBER: _ClassVar[int]
    APPROVAL_FIELD_NUMBER: _ClassVar[int]
    GENERAL_FIELD_NUMBER: _ClassVar[int]
    id: str
    scope: str
    client_id: str
    project_id: str
    coding: CodingGuidelines
    git: GitGuidelines
    review: ReviewGuidelines
    communication: CommunicationGuidelines
    approval: ApprovalGuidelines
    general: GeneralGuidelines
    def __init__(self, id: _Optional[str] = ..., scope: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., coding: _Optional[_Union[CodingGuidelines, _Mapping]] = ..., git: _Optional[_Union[GitGuidelines, _Mapping]] = ..., review: _Optional[_Union[ReviewGuidelines, _Mapping]] = ..., communication: _Optional[_Union[CommunicationGuidelines, _Mapping]] = ..., approval: _Optional[_Union[ApprovalGuidelines, _Mapping]] = ..., general: _Optional[_Union[GeneralGuidelines, _Mapping]] = ...) -> None: ...

class MergedGuidelines(_message.Message):
    __slots__ = ("coding", "git", "review", "communication", "approval", "general", "effective_scopes")
    class EffectiveScopesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CODING_FIELD_NUMBER: _ClassVar[int]
    GIT_FIELD_NUMBER: _ClassVar[int]
    REVIEW_FIELD_NUMBER: _ClassVar[int]
    COMMUNICATION_FIELD_NUMBER: _ClassVar[int]
    APPROVAL_FIELD_NUMBER: _ClassVar[int]
    GENERAL_FIELD_NUMBER: _ClassVar[int]
    EFFECTIVE_SCOPES_FIELD_NUMBER: _ClassVar[int]
    coding: CodingGuidelines
    git: GitGuidelines
    review: ReviewGuidelines
    communication: CommunicationGuidelines
    approval: ApprovalGuidelines
    general: GeneralGuidelines
    effective_scopes: _containers.ScalarMap[str, str]
    def __init__(self, coding: _Optional[_Union[CodingGuidelines, _Mapping]] = ..., git: _Optional[_Union[GitGuidelines, _Mapping]] = ..., review: _Optional[_Union[ReviewGuidelines, _Mapping]] = ..., communication: _Optional[_Union[CommunicationGuidelines, _Mapping]] = ..., approval: _Optional[_Union[ApprovalGuidelines, _Mapping]] = ..., general: _Optional[_Union[GeneralGuidelines, _Mapping]] = ..., effective_scopes: _Optional[_Mapping[str, str]] = ...) -> None: ...

class GuidelinesUpdateRequest(_message.Message):
    __slots__ = ("scope", "client_id", "project_id", "coding", "git", "review", "communication", "approval", "general")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    CODING_FIELD_NUMBER: _ClassVar[int]
    GIT_FIELD_NUMBER: _ClassVar[int]
    REVIEW_FIELD_NUMBER: _ClassVar[int]
    COMMUNICATION_FIELD_NUMBER: _ClassVar[int]
    APPROVAL_FIELD_NUMBER: _ClassVar[int]
    GENERAL_FIELD_NUMBER: _ClassVar[int]
    scope: str
    client_id: str
    project_id: str
    coding: CodingGuidelines
    git: GitGuidelines
    review: ReviewGuidelines
    communication: CommunicationGuidelines
    approval: ApprovalGuidelines
    general: GeneralGuidelines
    def __init__(self, scope: _Optional[str] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., coding: _Optional[_Union[CodingGuidelines, _Mapping]] = ..., git: _Optional[_Union[GitGuidelines, _Mapping]] = ..., review: _Optional[_Union[ReviewGuidelines, _Mapping]] = ..., communication: _Optional[_Union[CommunicationGuidelines, _Mapping]] = ..., approval: _Optional[_Union[ApprovalGuidelines, _Mapping]] = ..., general: _Optional[_Union[GeneralGuidelines, _Mapping]] = ...) -> None: ...
