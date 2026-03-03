"""ArangoDB-backed project entity graph for impact analysis.

Tracks ALL entities that Jervis manages as a graph in ArangoDB — not just code.
Entities include code artifacts, documents, meetings, people, test plans, etc.
Code structure is imported from Joern CPG (already in KB via KnowledgeNodes).

- **Artifacts** (vertex collection): any entity (class, meeting, document, person, etc.)
- **Dependencies** (edge collection): structural/organizational relationships
- **TaskTouches** (edge collection): which TaskGraph vertex touches which entity

**Impact analysis** — the core value:
When a vertex completes with a change (e.g. renames a class, reschedules a meeting),
we traverse dependencies to find ALL affected entities → check which planned
TaskGraph vertices touch those entities → create new validation/fix vertices
or mark existing ones as needing re-execution.

This is the mechanism that makes it impossible for a class rename in vertex A
to silently break vertex B's work, or for a rescheduled meeting to not propagate
to dependent training sessions. The graph KNOWS about the dependency.

For code artifacts, `kb_node_key` links to existing KnowledgeNodes in ArangoDB
(populated by Joern CPG ingestion), avoiding duplication.

Collections:
- `graph_artifacts`      — vertex collection (entities of all kinds)
- `artifact_deps`        — edge collection (structural/organizational dependencies)
- `task_artifact_links`  — edge collection (which task vertex touches which entity)
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field

from app.config import settings

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------


class ArtifactKind(str, Enum):
    """What kind of entity this is in the project/task graph.

    NOT limited to code — Jervis is an assistant/PM, not just a coding agent.
    Entities can be code artifacts, documents, meetings, people, test plans, etc.
    Code structure is imported from Joern CPG (already in KB) and linked here.
    """

    # --- Code artifacts (imported from Joern CPG / KB) ---
    MODULE = "module"           # Top-level module / package
    FILE = "file"               # Source file
    CLASS = "class"             # Class / interface / object
    FUNCTION = "function"       # Function / method
    API_ENDPOINT = "api"        # REST/gRPC endpoint
    DATABASE_TABLE = "db_table" # DB collection / table
    CONFIG = "config"           # Configuration file / property
    TEST = "test"               # Test class / test file
    DEPENDENCY = "dependency"   # External dependency (library, package)
    SCHEMA = "schema"           # Data schema / DTO / model
    COMPONENT = "component"     # UI component
    SERVICE = "service"         # Service class / microservice

    # --- Documentation / deliverables ---
    DOCUMENT = "document"       # Documentation file / page
    SPEC = "spec"               # Specification / requirements
    TEST_PLAN = "test_plan"     # Test plan / test scenario collection
    TEST_SCENARIO = "test_scenario"  # Individual test scenario
    RELEASE_NOTE = "release_note"    # Release notes
    REPORT = "report"           # Report / analysis

    # --- Organization / people ---
    PERSON = "person"           # Team member / stakeholder
    TEAM = "team"               # Team / department
    ROLE = "role"               # Role / responsibility

    # --- Project management ---
    MILESTONE = "milestone"     # Project milestone / deadline
    TASK = "task"               # Project task / work item (Jira, etc.)
    MEETING = "meeting"         # Meeting / event
    DECISION = "decision"       # Architectural / project decision
    TRAINING = "training"       # Training session / material
    BUDGET = "budget"           # Budget item

    # --- Infrastructure ---
    ENVIRONMENT = "environment" # Deployment environment (dev, staging, prod)
    PIPELINE = "pipeline"       # CI/CD pipeline
    RESOURCE = "resource"       # K8s resource / infrastructure component


class DepKind(str, Enum):
    """Structural dependency/relationship between entities."""

    # --- Code dependencies ---
    IMPORTS = "imports"             # A imports B
    EXTENDS = "extends"            # A extends/inherits B
    IMPLEMENTS = "implements"      # A implements B (interface)
    CALLS = "calls"                # A calls B (function/method)
    USES_TYPE = "uses_type"        # A uses B as a type
    CONTAINS = "contains"          # A contains B (package→class, meeting→agenda)
    TESTS = "tests"                # A tests B (test→code)
    CONFIGURES = "configures"      # A configures B (config→service)
    DEPENDS_ON = "depends_on"      # A depends on B (generic)
    EXPOSES = "exposes"            # A exposes B (module→API)
    PERSISTS = "persists"          # A persists to B (entity→table)

    # --- Organization / PM ---
    RESPONSIBLE_FOR = "responsible_for"  # Person responsible for artifact
    ASSIGNED_TO = "assigned_to"          # Task assigned to person
    PARTICIPATES = "participates"        # Person participates in meeting
    REVIEWS = "reviews"                  # Person reviews artifact
    APPROVES = "approves"                # Person approves artifact
    BLOCKS = "blocks"                    # A blocks B (task dependency)
    PRECEDES = "precedes"                # A must happen before B (temporal)
    PRODUCES = "produces"                # A produces B (task→document)
    CONSUMES = "consumes"                # A consumes/reads B (task→spec)
    DEPLOYS_TO = "deploys_to"            # A deploys to B (service→environment)

    # --- Generic ---
    REFERENCES = "references"            # A references B
    RELATED_TO = "related_to"            # A is related to B (weak link)


class TouchKind(str, Enum):
    """How a task vertex touches an entity."""

    CREATES = "creates"             # Task creates this entity
    MODIFIES = "modifies"           # Task modifies this entity
    RENAMES = "renames"             # Task renames this entity
    DELETES = "deletes"             # Task deletes this entity
    READS = "reads"                 # Task reads/inspects this entity
    TESTS = "tests"                 # Task tests/validates this entity
    REVIEWS = "reviews"             # Task reviews this entity
    SCHEDULES = "schedules"         # Task schedules this entity (meeting, training)
    DELEGATES = "delegates"         # Task delegates this to another agent/person
    DELIVERS = "delivers"           # Task delivers this entity (document, report)


# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------


class Artifact(BaseModel):
    """An entity (code artifact, document, meeting, person, etc.) in the project graph.

    Generic enough to model anything Jervis manages — from Kotlin classes
    (imported via Joern CPG from KB) to vacation plans and training sessions.
    """

    key: str                            # Unique key (e.g. "com.jervis.service.FooService" or "meeting/2026-Q1-review")
    kind: ArtifactKind
    label: str                          # Human-readable name
    file_path: str = ""                 # File path (if applicable, for code artifacts)
    module: str = ""                    # Module/package/area this belongs to
    client_id: str = ""
    project_id: str = ""
    kb_node_key: str = ""               # Link to existing KnowledgeNodes _key in KB (Joern CPG, etc.)
    metadata: dict = Field(default_factory=dict)  # Extra info (language, date, participants, etc.)


class ArtifactDep(BaseModel):
    """Structural dependency between two artifacts."""

    from_key: str                       # Source artifact key
    to_key: str                         # Target artifact key
    dep_kind: DepKind
    metadata: dict = Field(default_factory=dict)


class TaskArtifactLink(BaseModel):
    """Link between a TaskGraph vertex and an artifact it touches."""

    vertex_id: str                      # TaskGraph vertex ID
    artifact_key: str                   # Artifact key
    touch_kind: TouchKind
    task_graph_id: str = ""             # Which TaskGraph this belongs to
    detail: str = ""                    # What exactly changed


# ---------------------------------------------------------------------------
# ArangoDB Artifact Graph Store
# ---------------------------------------------------------------------------

# ArangoDB key max 254 bytes — truncate and add hash for long keys
_MAX_KEY_LEN = 200


def _safe_key(raw: str) -> str:
    """Create a safe ArangoDB _key from a raw string."""
    # Replace characters invalid in ArangoDB keys
    safe = raw.replace("/", "__").replace(" ", "_").replace(".", "_")
    if len(safe) <= _MAX_KEY_LEN:
        return safe
    # Truncate and add hash suffix
    h = hashlib.sha256(raw.encode()).hexdigest()[:12]
    return safe[:_MAX_KEY_LEN - 13] + "_" + h


class ArtifactGraphStore:
    """ArangoDB-backed store for the project artifact graph.

    Provides:
    - CRUD for artifacts and dependencies
    - Impact analysis: given a changed artifact, find all affected artifacts
    - Task linking: track which task vertices touch which artifacts
    - Conflict detection: find if two task vertices modify the same artifact
    """

    ARTIFACTS_COLLECTION = "graph_artifacts"
    DEPS_COLLECTION = "artifact_deps"
    LINKS_COLLECTION = "task_artifact_links"

    def __init__(self):
        self._db = None

    # Retry backoff schedule (seconds): 5s → 15s → 30s → 60s → 5min cap
    _BACKOFF_SCHEDULE = [5, 15, 30, 60, 300]

    async def init(self) -> None:
        """Initialize ArangoDB connection and ensure collections exist.

        Retries with exponential backoff (5s → 15s → 30s → 60s → 5min cap)
        until ArangoDB becomes reachable.  Matches the project-wide resilience
        pattern used by workspace recovery and task dispatch.
        """
        def _connect():
            from arango import ArangoClient
            client = ArangoClient(hosts=settings.arango_url)
            db = client.db(
                settings.arango_db,
                username=settings.arango_user,
                password=settings.arango_password,
            )

            # Ensure vertex collection
            if not db.has_collection(self.ARTIFACTS_COLLECTION):
                db.create_collection(self.ARTIFACTS_COLLECTION)
                logger.info("Created collection: %s", self.ARTIFACTS_COLLECTION)

            # Ensure document collection for task-artifact links
            if not db.has_collection(self.LINKS_COLLECTION):
                db.create_collection(self.LINKS_COLLECTION)
                logger.info("Created collection: %s", self.LINKS_COLLECTION)

            # Ensure edge collection for artifact dependencies
            if not db.has_collection(self.DEPS_COLLECTION):
                db.create_collection(self.DEPS_COLLECTION, edge=True)
                logger.info("Created edge collection: %s", self.DEPS_COLLECTION)

            # Ensure indexes
            artifacts = db.collection(self.ARTIFACTS_COLLECTION)
            artifacts.add_persistent_index(fields=["client_id", "project_id"], name="idx_tenant")
            artifacts.add_persistent_index(fields=["kind"], name="idx_kind")
            artifacts.add_persistent_index(fields=["module"], name="idx_module")

            deps = db.collection(self.DEPS_COLLECTION)
            deps.add_persistent_index(fields=["dep_kind"], name="idx_dep_kind")

            links = db.collection(self.LINKS_COLLECTION)
            links.add_persistent_index(fields=["task_graph_id"], name="idx_task_graph")
            links.add_persistent_index(fields=["vertex_id"], name="idx_vertex")
            links.add_persistent_index(fields=["artifact_key"], name="idx_artifact_key")

            return db

        attempt = 0
        while True:
            try:
                self._db = await asyncio.to_thread(_connect)
                logger.info("ArtifactGraphStore initialized (ArangoDB)")
                return
            except Exception:
                backoff = self._BACKOFF_SCHEDULE[min(attempt, len(self._BACKOFF_SCHEDULE) - 1)]
                attempt += 1
                logger.warning(
                    "ArtifactGraphStore: ArangoDB unreachable (attempt %d), "
                    "retrying in %ds…",
                    attempt, backoff,
                    exc_info=True,
                )
                await asyncio.sleep(backoff)

    @property
    def db(self):
        if self._db is None:
            raise RuntimeError("ArtifactGraphStore not initialized — call init() first")
        return self._db

    # -------------------------------------------------------------------
    # Artifact CRUD
    # -------------------------------------------------------------------

    async def upsert_artifact(self, artifact: Artifact) -> str:
        """Insert or update an artifact. Returns the _key."""
        key = _safe_key(artifact.key)
        doc = {
            "_key": key,
            "raw_key": artifact.key,
            "kind": artifact.kind.value,
            "label": artifact.label,
            "file_path": artifact.file_path,
            "module": artifact.module,
            "client_id": artifact.client_id,
            "project_id": artifact.project_id,
            "metadata": artifact.metadata,
        }

        def _upsert():
            col = self.db.collection(self.ARTIFACTS_COLLECTION)
            if col.has(key):
                col.update(doc)
            else:
                col.insert(doc)
            return key

        return await asyncio.to_thread(_upsert)

    async def upsert_artifacts_batch(self, artifacts: list[Artifact]) -> int:
        """Batch upsert artifacts. Returns count of upserted."""
        def _batch():
            col = self.db.collection(self.ARTIFACTS_COLLECTION)
            count = 0
            for artifact in artifacts:
                key = _safe_key(artifact.key)
                doc = {
                    "_key": key,
                    "raw_key": artifact.key,
                    "kind": artifact.kind.value,
                    "label": artifact.label,
                    "file_path": artifact.file_path,
                    "module": artifact.module,
                    "client_id": artifact.client_id,
                    "project_id": artifact.project_id,
                    "metadata": artifact.metadata,
                }
                if col.has(key):
                    col.update(doc)
                else:
                    col.insert(doc)
                count += 1
            return count

        return await asyncio.to_thread(_batch)

    async def get_artifact(self, artifact_key: str) -> dict | None:
        """Get artifact by key."""
        key = _safe_key(artifact_key)

        def _get():
            col = self.db.collection(self.ARTIFACTS_COLLECTION)
            if col.has(key):
                return col.get(key)
            return None

        return await asyncio.to_thread(_get)

    async def delete_artifact(self, artifact_key: str) -> bool:
        """Delete artifact and all its edges."""
        key = _safe_key(artifact_key)

        def _delete():
            col = self.db.collection(self.ARTIFACTS_COLLECTION)
            if not col.has(key):
                return False

            # Delete dependency edges referencing this artifact
            full_id = f"{self.ARTIFACTS_COLLECTION}/{key}"
            aql_deps = f"""
                FOR e IN {self.DEPS_COLLECTION}
                FILTER e._from == @id OR e._to == @id
                REMOVE e IN {self.DEPS_COLLECTION}
            """
            self.db.aql.execute(aql_deps, bind_vars={"id": full_id})

            # Delete task-artifact links referencing this artifact
            aql_links = f"""
                FOR doc IN {self.LINKS_COLLECTION}
                FILTER doc.artifact_id == @id
                REMOVE doc IN {self.LINKS_COLLECTION}
            """
            self.db.aql.execute(aql_links, bind_vars={"id": full_id})

            col.delete(key)
            return True

        return await asyncio.to_thread(_delete)

    # -------------------------------------------------------------------
    # Dependency CRUD
    # -------------------------------------------------------------------

    async def add_dependency(self, dep: ArtifactDep) -> str:
        """Add a structural dependency between two artifacts."""
        from_key = _safe_key(dep.from_key)
        to_key = _safe_key(dep.to_key)
        edge_key = _safe_key(f"{dep.from_key}--{dep.dep_kind.value}--{dep.to_key}")

        doc = {
            "_key": edge_key,
            "_from": f"{self.ARTIFACTS_COLLECTION}/{from_key}",
            "_to": f"{self.ARTIFACTS_COLLECTION}/{to_key}",
            "dep_kind": dep.dep_kind.value,
            "metadata": dep.metadata,
        }

        def _add():
            col = self.db.collection(self.DEPS_COLLECTION)
            if col.has(edge_key):
                col.update(doc)
            else:
                col.insert(doc)
            return edge_key

        return await asyncio.to_thread(_add)

    async def add_dependencies_batch(self, deps: list[ArtifactDep]) -> int:
        """Batch add dependencies. Returns count."""
        def _batch():
            col = self.db.collection(self.DEPS_COLLECTION)
            count = 0
            for dep in deps:
                from_key = _safe_key(dep.from_key)
                to_key = _safe_key(dep.to_key)
                edge_key = _safe_key(f"{dep.from_key}--{dep.dep_kind.value}--{dep.to_key}")
                doc = {
                    "_key": edge_key,
                    "_from": f"{self.ARTIFACTS_COLLECTION}/{from_key}",
                    "_to": f"{self.ARTIFACTS_COLLECTION}/{to_key}",
                    "dep_kind": dep.dep_kind.value,
                    "metadata": dep.metadata,
                }
                if col.has(edge_key):
                    col.update(doc)
                else:
                    col.insert(doc)
                count += 1
            return count

        return await asyncio.to_thread(_batch)

    # -------------------------------------------------------------------
    # Task-Artifact linking
    # -------------------------------------------------------------------

    async def link_task_to_artifact(self, link: TaskArtifactLink) -> str:
        """Link a TaskGraph vertex to an artifact it touches.

        Stored as a document (not edge) with explicit artifact_key reference.
        """
        art_key = _safe_key(link.artifact_key)
        doc_key = _safe_key(
            f"{link.task_graph_id}__{link.vertex_id}--{link.touch_kind.value}--{link.artifact_key}"
        )

        doc = {
            "_key": doc_key,
            "artifact_key": art_key,
            "artifact_id": f"{self.ARTIFACTS_COLLECTION}/{art_key}",
            "vertex_id": link.vertex_id,
            "task_graph_id": link.task_graph_id,
            "touch_kind": link.touch_kind.value,
            "detail": link.detail,
        }

        def _link():
            col = self.db.collection(self.LINKS_COLLECTION)
            if col.has(doc_key):
                col.update(doc)
            else:
                col.insert(doc)
            return doc_key

        return await asyncio.to_thread(_link)

    async def get_vertex_artifacts(self, task_graph_id: str, vertex_id: str) -> list[dict]:
        """Get all artifacts linked to a specific vertex."""
        def _query():
            aql = """
                FOR link IN @@links
                FILTER link.task_graph_id == @graph_id AND link.vertex_id == @vertex_id
                LET artifact = DOCUMENT(link.artifact_id)
                RETURN MERGE(link, {artifact: artifact})
            """
            cursor = self.db.aql.execute(aql, bind_vars={
                "@links": self.LINKS_COLLECTION,
                "graph_id": task_graph_id,
                "vertex_id": vertex_id,
            })
            return list(cursor)

        return await asyncio.to_thread(_query)

    # -------------------------------------------------------------------
    # Impact Analysis — the core value of this module
    # -------------------------------------------------------------------

    async def find_affected_artifacts(
        self,
        artifact_key: str,
        max_depth: int = 3,
        direction: str = "INBOUND",
    ) -> list[dict]:
        """Find all artifacts affected by a change to the given artifact.

        Traverses ArtifactDependencies INBOUND (= who depends on me?) to
        find everything that would break if this artifact changes.

        Args:
            artifact_key: The changed artifact
            max_depth: How many hops to traverse (default 3)
            direction: INBOUND (who depends on me), OUTBOUND (what do I depend on),
                      or ANY (both directions)

        Returns:
            List of {artifact: dict, depth: int, path: list[str]} for each
            affected artifact, sorted by depth (closest first).
        """
        key = _safe_key(artifact_key)
        start_node = f"{self.ARTIFACTS_COLLECTION}/{key}"

        def _traverse():
            aql = f"""
                FOR v, e, p IN 1..@maxDepth {direction}
                @startNode
                {self.DEPS_COLLECTION}
                OPTIONS {{uniqueVertices: "global", bfs: true}}
                RETURN {{
                    artifact: v,
                    depth: LENGTH(p.edges),
                    edge_types: p.edges[*].dep_kind,
                    path: p.vertices[*]._key
                }}
            """
            cursor = self.db.aql.execute(aql, bind_vars={
                "maxDepth": max_depth,
                "startNode": start_node,
            })
            results = list(cursor)
            results.sort(key=lambda r: r["depth"])
            return results

        return await asyncio.to_thread(_traverse)

    async def find_affected_task_vertices(
        self,
        artifact_key: str,
        task_graph_id: str,
        max_depth: int = 3,
    ) -> list[dict]:
        """Find task vertices that would be affected by a change to an artifact.

        Two-step query:
        1. Find all affected artifacts (via dependency traversal)
        2. Find all task vertices linked to those affected artifacts

        Returns:
            List of {vertex_id, touch_kind, artifact_key, artifact_label, depth}
        """
        key = _safe_key(artifact_key)
        start_node = f"{self.ARTIFACTS_COLLECTION}/{key}"

        def _query():
            aql = f"""
                LET affected = (
                    FOR v IN 1..@maxDepth INBOUND
                    @startNode
                    {self.DEPS_COLLECTION}
                    OPTIONS {{uniqueVertices: "global", bfs: true}}
                    RETURN DISTINCT v._id
                )

                LET all_affected = APPEND(affected, [@startNode])

                FOR art_id IN all_affected
                    FOR link IN {self.LINKS_COLLECTION}
                    FILTER link.artifact_id == art_id AND link.task_graph_id == @graph_id
                    LET artifact = DOCUMENT(art_id)
                    RETURN {{
                        vertex_id: link.vertex_id,
                        touch_kind: link.touch_kind,
                        artifact_key: artifact.raw_key,
                        artifact_label: artifact.label,
                        depth: LENGTH(
                            (FOR v, e, p IN 0..@maxDepth INBOUND
                             art_id {self.DEPS_COLLECTION}
                             FILTER v._id == @startNode
                             LIMIT 1
                             RETURN p.edges)
                        )
                    }}
            """
            cursor = self.db.aql.execute(aql, bind_vars={
                "maxDepth": max_depth,
                "startNode": start_node,
                "graph_id": task_graph_id,
            })
            return list(cursor)

        return await asyncio.to_thread(_query)

    async def find_conflicting_vertices(
        self,
        task_graph_id: str,
    ) -> list[dict]:
        """Find pairs of task vertices that modify the same artifact.

        This detects potential conflicts where two vertices might step on
        each other's toes (both modifying the same class, for example).

        Returns:
            List of {artifact_key, artifact_label, vertices: [{vertex_id, touch_kind}]}
        """
        def _query():
            aql = f"""
                FOR link IN {self.LINKS_COLLECTION}
                FILTER link.task_graph_id == @graph_id
                AND link.touch_kind IN ["modifies", "renames", "deletes", "creates"]
                COLLECT artifact_id = link.artifact_id INTO grouped
                LET artifact = DOCUMENT(artifact_id)
                FILTER LENGTH(grouped) > 1
                RETURN {{
                    artifact_key: artifact.raw_key,
                    artifact_label: artifact.label,
                    artifact_kind: artifact.kind,
                    vertices: grouped[*].link.{{vertex_id: link.vertex_id, touch_kind: link.touch_kind}}
                }}
            """
            cursor = self.db.aql.execute(aql, bind_vars={
                "graph_id": task_graph_id,
            })
            return list(cursor)

        return await asyncio.to_thread(_query)

    # -------------------------------------------------------------------
    # Bulk operations for project structure import
    # -------------------------------------------------------------------

    async def import_project_structure(
        self,
        client_id: str,
        project_id: str,
        artifacts: list[Artifact],
        dependencies: list[ArtifactDep],
    ) -> dict:
        """Import a project's code structure into the artifact graph.

        Called during initial decomposition or when a project is analyzed.
        Replaces existing project structure (idempotent).

        Returns: {artifacts_count, deps_count}
        """
        # Tag all artifacts with tenant info
        for a in artifacts:
            a.client_id = client_id
            a.project_id = project_id

        art_count = await self.upsert_artifacts_batch(artifacts)
        dep_count = await self.add_dependencies_batch(dependencies)

        logger.info(
            "Imported project structure: client=%s project=%s artifacts=%d deps=%d",
            client_id, project_id, art_count, dep_count,
        )

        return {"artifacts_count": art_count, "deps_count": dep_count}

    async def get_project_stats(self, client_id: str, project_id: str) -> dict:
        """Get statistics about a project's artifact graph."""
        def _stats():
            aql = """
                LET arts = (
                    FOR a IN @@artifacts
                    FILTER a.client_id == @cid AND a.project_id == @pid
                    COLLECT kind = a.kind WITH COUNT INTO cnt
                    RETURN {kind, count: cnt}
                )
                LET total_arts = SUM(arts[*].count)
                LET total_deps = LENGTH(
                    FOR e IN @@deps
                    LET from_art = DOCUMENT(e._from)
                    FILTER from_art.client_id == @cid AND from_art.project_id == @pid
                    RETURN 1
                )
                RETURN {
                    total_artifacts: total_arts,
                    total_dependencies: total_deps,
                    by_kind: arts
                }
            """
            cursor = self.db.aql.execute(aql, bind_vars={
                "@artifacts": self.ARTIFACTS_COLLECTION,
                "@deps": self.DEPS_COLLECTION,
                "cid": client_id,
                "pid": project_id,
            })
            results = list(cursor)
            return results[0] if results else {"total_artifacts": 0, "total_dependencies": 0, "by_kind": []}

        return await asyncio.to_thread(_stats)


# ---------------------------------------------------------------------------
# Module-level singleton
# ---------------------------------------------------------------------------

artifact_graph_store = ArtifactGraphStore()
