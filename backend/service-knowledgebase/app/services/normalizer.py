"""
Entity Normalization and Canonicalization Module

This module provides functions for normalizing entity references to ensure
consistent graph keys across different sources and naming conventions.

Normalization stages:
1. Format normalization - consistent case, whitespace, special chars
2. Canonicalization - remove redundant prefixes, standardize formats
3. Alias resolution - map aliases to canonical keys via registry
"""

import re
from typing import Optional


def normalize_graph_ref(raw: str) -> str:
    """
    Normalize a graph reference to canonical form.

    This is the main entry point for normalization. It handles:
    - Namespace:value format (e.g., "jira:TASK-123")
    - Plain values (e.g., "John Smith")

    Examples:
        "User:John  Smith" → "user:john_smith"
        "JIRA:TASK-123" → "jira:task-123"
        "order:order_530798957" → "order:530798957"
        "file:src/main/Service.kt" → "file:src_main_service.kt"
        "John Smith" → "john_smith"

    Args:
        raw: The raw entity reference

    Returns:
        Normalized graph reference key
    """
    if not raw or not raw.strip():
        return ""

    raw = raw.strip()

    # Check if it has namespace:value format
    if ":" in raw:
        # Find first colon (namespace separator)
        colon_idx = raw.index(":")
        namespace = raw[:colon_idx].strip()
        value = raw[colon_idx + 1:].strip()

        # Normalize namespace
        namespace = _normalize_namespace(namespace)

        # Normalize value
        value = _normalize_value(value)

        # Canonicalize (remove redundant namespace prefix in value)
        value = _canonicalize_value(namespace, value)

        if not value:
            return namespace

        return f"{namespace}:{value}"
    else:
        # Plain value without namespace
        return _normalize_value(raw)


def _normalize_namespace(namespace: str) -> str:
    """
    Normalize namespace part.

    - Lowercase
    - Remove special characters except underscore
    """
    namespace = namespace.lower().strip()
    namespace = re.sub(r'[^a-z0-9_]', '', namespace)
    return namespace


def _normalize_value(value: str) -> str:
    """
    Normalize value part.

    - Lowercase
    - Replace whitespace with underscore
    - Replace path separators with underscore
    - Replace @ and . with underscore (for emails)
    - Remove other special characters except underscore and dash
    - Collapse multiple underscores
    """
    if not value:
        return ""

    value = value.lower().strip()

    # Replace common separators with underscore
    value = value.replace(" ", "_")
    value = value.replace("/", "_")
    value = value.replace("\\", "_")
    value = value.replace(".", "_")
    value = value.replace("@", "_")

    # Keep only alphanumeric, underscore, and dash
    value = re.sub(r'[^a-z0-9_\-]', '', value)

    # Collapse multiple underscores
    value = re.sub(r'_+', '_', value)

    # Remove leading/trailing underscores
    value = value.strip('_')

    return value


def _canonicalize_value(namespace: str, value: str) -> str:
    """
    Canonicalize value by removing redundant namespace prefix.

    Examples:
        namespace="order", value="order_530798957" → "530798957"
        namespace="product", value="product_lego" → "lego"
        namespace="user", value="user_john" → "john"
        namespace="jira", value="task_123" → "task_123" (no change, different prefix)
    """
    if not value:
        return value

    # Check if value starts with namespace + underscore
    prefix = f"{namespace}_"
    if value.startswith(prefix):
        remainder = value[len(prefix):]
        if remainder:  # Don't remove if nothing left
            return remainder

    return value


def extract_namespace(ref: str) -> Optional[str]:
    """
    Extract namespace from a graph reference.

    Args:
        ref: Graph reference (e.g., "jira:TASK-123")

    Returns:
        Namespace or None if no namespace
    """
    if ":" in ref:
        return ref.split(":")[0].lower()
    return None


def extract_value(ref: str) -> str:
    """
    Extract value from a graph reference.

    Args:
        ref: Graph reference (e.g., "jira:TASK-123")

    Returns:
        Value part (or full string if no namespace)
    """
    if ":" in ref:
        return ref.split(":", 1)[1]
    return ref


# Common entity type mappings for LLM extraction normalization
ENTITY_TYPE_ALIASES = {
    # People
    "person": "user",
    "developer": "user",
    "engineer": "user",
    "author": "user",
    "assignee": "user",
    "reporter": "user",
    "member": "user",

    # Issues
    "ticket": "jira",
    "issue": "jira",
    "bug": "jira",
    "task": "jira",
    "story": "jira",

    # Code
    "source": "file",
    "code": "file",
    "module": "file",
    "script": "file",

    # Documentation
    "page": "confluence",
    "doc": "confluence",
    "document": "confluence",
    "wiki": "confluence",

    # Git
    "revision": "commit",
    "changeset": "commit",

    # Infrastructure
    "service": "service",
    "component": "component",
    "product": "product",
    "feature": "feature",
    "version": "version",
    "environment": "environment",
    "configuration": "configuration",
    "server": "server",
    "database": "database",
    "api": "api",
    "endpoint": "endpoint",
    "deployment": "deployment",
    "namespace": "namespace",
    "container": "container",
    "pod": "pod",

    # Concepts
    "concept": "concept",
    "topic": "concept",
    "idea": "concept",
    "pattern": "pattern",
    "technology": "technology",
    "framework": "technology",
    "library": "technology",
    "tool": "technology",
    "language": "technology",
    "protocol": "technology",

    # Business
    "client": "client",
    "customer": "client",
    "company": "organization",
    "team": "organization",
    "organization": "organization",
    "project": "project",
    "meeting": "meeting",
    "event": "event",
    "deadline": "event",
}


# Relationship type aliases — canonicalize LLM-generated relation names
# to a consistent set of relation types.
RELATION_TYPE_ALIASES = {
    # Dependency
    "depends_on": "depends_on",
    "depends on": "depends_on",
    "dependency": "depends_on",
    "requires": "depends_on",
    "prerequisite": "depends_on",
    "needed_by": "depends_on",
    "relies_on": "depends_on",

    # Usage
    "uses": "uses",
    "utilizes": "uses",
    "employs": "uses",
    "calls": "uses",
    "invokes": "uses",
    "references": "uses",

    # Containment
    "contains": "contains",
    "has": "contains",
    "includes": "contains",
    "comprises": "contains",
    "consists_of": "contains",

    # Membership / ownership
    "belongs_to": "belongs_to",
    "part_of": "belongs_to",
    "member_of": "belongs_to",
    "owned_by": "belongs_to",
    "managed_by": "belongs_to",

    # Creation / assignment
    "created_by": "created_by",
    "authored_by": "created_by",
    "written_by": "created_by",
    "developed_by": "created_by",
    "assigned_to": "assigned_to",
    "responsible_for": "assigned_to",

    # Communication
    "communicates_with": "communicates_with",
    "sends_to": "communicates_with",
    "notifies": "communicates_with",
    "reports_to": "communicates_with",

    # Modification
    "modifies": "modifies",
    "changes": "modifies",
    "updates": "modifies",
    "edits": "modifies",
    "affects": "modifies",

    # Inheritance / extension
    "extends": "extends",
    "inherits": "extends",
    "implements": "implements",
    "overrides": "extends",

    # Relation
    "related_to": "related_to",
    "associated_with": "related_to",
    "connected_to": "related_to",
    "linked_to": "related_to",

    # Deployment / running
    "deployed_to": "deployed_to",
    "runs_on": "deployed_to",
    "hosted_on": "deployed_to",
    "installed_on": "deployed_to",

    # Resolution / fixing
    "fixes": "fixes",
    "resolves": "fixes",
    "addresses": "fixes",
    "patches": "fixes",

    # Blocking
    "blocks": "blocks",
    "blocked_by": "blocks",
    "prevents": "blocks",
}


def normalize_relation_type(raw_relation: str) -> str:
    """
    Normalize a relationship type from LLM extraction.

    Maps common aliases to canonical relation types.
    Normalizes whitespace, case, and separators.

    Args:
        raw_relation: Raw relation string from LLM

    Returns:
        Normalized relation type
    """
    if not raw_relation:
        return "related_to"

    # Lowercase, strip, replace spaces/dashes with underscore
    normalized = raw_relation.lower().strip()
    normalized = normalized.replace(" ", "_").replace("-", "_")
    # Collapse multiple underscores
    normalized = re.sub(r'_+', '_', normalized).strip('_')

    return RELATION_TYPE_ALIASES.get(normalized, normalized)


def normalize_entity_type(raw_type: str) -> str:
    """
    Normalize entity type from LLM extraction.

    Maps common aliases to standard types.

    Args:
        raw_type: Raw entity type from LLM

    Returns:
        Normalized entity type
    """
    if not raw_type:
        return "entity"

    normalized = raw_type.lower().strip()
    return ENTITY_TYPE_ALIASES.get(normalized, normalized)


def build_graph_key(entity_type: str, identifier: str) -> str:
    """
    Build a normalized graph key from entity type and identifier.

    Args:
        entity_type: Entity type (e.g., "jira", "user", "file")
        identifier: Entity identifier (e.g., "TASK-123", "John Smith")

    Returns:
        Normalized graph key (e.g., "jira:task-123", "user:john_smith")
    """
    namespace = normalize_entity_type(entity_type)
    value = _normalize_value(identifier)
    return f"{namespace}:{value}" if value else namespace
