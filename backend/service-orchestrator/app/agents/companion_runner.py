"""Claude companion runner — ad-hoc and persistent-session Jobs.

Parallel to `job_runner.JobRunner` (coding agents), but for the companion role:
- ad-hoc: fire-and-forget K8s Job for a single deep-analysis task
- session: long-running Job (meeting assistant or /deep chat session)
            fed through an append-only inbox, streamed answers via outbox

Companion is read-only on the repo (no git, no edits). It runs on the shared
PVC with its own session workspace under `/opt/jervis/data/companion/<id>/`.
"""

from __future__ import annotations

import asyncio
import datetime
import json
import logging
import uuid
from dataclasses import dataclass
from pathlib import Path

from kubernetes import client, watch as k8s_watch
from kubernetes.client.rest import ApiException

from app.agents.companion_budget import companion_budget
from app.agents.job_runner import job_runner
from app.config import settings

logger = logging.getLogger(__name__)

COMPANION_IMAGE = f"{settings.container_registry}/jervis-claude:latest"
COMPANION_ENTRYPOINT = "/opt/jervis/entrypoint-companion.sh"
# No hard time caps — Jervis runs overnight on 2-3 clients, hard cut-off
# would kill productive work mid-session. Reclamation is event-driven:
# the runner's done-callback + K8s watch detect real completion.


@dataclass
class CompanionDispatch:
    job_name: str
    workspace_path: str
    mode: str  # "adhoc" | "session"
    session_id: str | None = None


class CompanionRunner:
    """Dispatches and manages Claude companion K8s Jobs."""

    def __init__(self):
        self.data_root = Path(settings.data_root) / "companion"
        self.data_root.mkdir(parents=True, exist_ok=True)

    # ---------- ad-hoc ----------

    async def dispatch_adhoc(
        self,
        task_id: str,
        brief: str,
        context: dict | None = None,
        attachments: list[Path] | None = None,
        client_id: str = "",
        project_id: str | None = None,
        language: str = "cs",
    ) -> CompanionDispatch:
        """Fire-and-forget: create a K8s Job for a one-shot deep analysis.

        Orchestrator later polls job status + reads .jervis/result.json.
        """
        workspace = self._prepare_workspace(
            session_id=f"adhoc-{task_id}",
            brief=brief,
            context=context or {},
            attachments=attachments or [],
            client_id=client_id,
            project_id=project_id,
            language=language,
            mode="adhoc",
        )

        decision = companion_budget.check(active_sessions=self.count_running_sessions())
        if not decision.allowed:
            raise RuntimeError(f"Companion budget/limit reached: {decision.reason}")

        job_name = f"jervis-companion-{task_id}"
        manifest = self._build_job_manifest(
            job_name=job_name,
            mode="adhoc",
            session_id=f"adhoc-{task_id}",
            task_id=task_id,
            client_id=client_id,
            project_id=project_id or "",
            workspace_path=str(workspace),
        )

        logger.info("Dispatching companion (adhoc): %s (task=%s)", job_name, task_id)
        job_runner.batch_v1.create_namespaced_job(namespace=settings.k8s_namespace, body=manifest)
        companion_budget.record_adhoc()
        if decision.warning_pct:
            logger.warning("Companion budget at %d%% (adhoc today=%d)",
                           decision.warning_pct, decision.adhoc_today)

        return CompanionDispatch(
            job_name=job_name,
            workspace_path=str(workspace),
            mode="adhoc",
        )

    def collect_adhoc_result(self, workspace_path: str) -> dict | None:
        result = Path(workspace_path) / ".jervis" / "result.json"
        if not result.exists():
            return None
        try:
            return json.loads(result.read_text())
        except json.JSONDecodeError as e:
            logger.warning("Malformed companion result.json at %s: %s", result, e)
            return None

    # ---------- session ----------

    async def start_session(
        self,
        session_id: str | None,
        brief: str,
        context: dict | None = None,
        attachments: list[Path] | None = None,
        client_id: str = "",
        project_id: str | None = None,
        language: str = "cs",
    ) -> CompanionDispatch:
        """Start a persistent companion Job fed through inbox/outbox files.

        Returns immediately after Job creation. The SDK runner emits a "note"
        outbox event when ready; consumers start streaming the outbox right
        away and receive it in-band. No ready-polling here (fail-fast).
        """
        sid = session_id or f"s{uuid.uuid4().hex}"
        workspace = self._prepare_workspace(
            session_id=sid,
            brief=brief,
            context=context or {},
            attachments=attachments or [],
            client_id=client_id,
            project_id=project_id,
            language=language,
            mode="session",
        )

        decision = companion_budget.check(active_sessions=self.count_running_sessions())
        if not decision.allowed:
            raise RuntimeError(f"Companion budget/limit reached: {decision.reason}")

        job_name = f"jervis-companion-s-{sid}"
        manifest = self._build_job_manifest(
            job_name=job_name,
            mode="session",
            session_id=sid,
            task_id=sid,
            client_id=client_id,
            project_id=project_id or "",
            workspace_path=str(workspace),
        )

        logger.info("Starting companion session: %s (id=%s)", job_name, sid)
        try:
            job_runner.batch_v1.create_namespaced_job(namespace=settings.k8s_namespace, body=manifest)
            companion_budget.record_session_start(sid)
        except ApiException as e:
            if e.status == 409:
                # Session already running — restart recovery path, re-attach only
                logger.info("Companion Job %s already exists — re-attaching to existing session", job_name)
            else:
                raise

        return CompanionDispatch(
            job_name=job_name,
            workspace_path=str(workspace),
            mode="session",
            session_id=sid,
        )

    def send_event(self, session_id: str, event_type: str, content: str, meta: dict | None = None) -> None:
        """Append an event into the session inbox."""
        workspace = self._session_workspace(session_id)
        inbox = workspace / ".jervis" / "inbox" / "events.jsonl"
        inbox.parent.mkdir(parents=True, exist_ok=True)
        line = json.dumps({
            "ts": _utc_now_iso(),
            "type": event_type,
            "content": content,
            "meta": meta or {},
        }, ensure_ascii=False)
        with inbox.open("a", encoding="utf-8") as f:
            f.write(line + "\n")

    async def stream_outbox(
        self,
        session_id: str,
        stop_event: asyncio.Event | None = None,
        max_age_seconds: float | None = None,
    ):
        """Tail outbox/events.jsonl and yield events as dicts until session ends.

        max_age_seconds: drop events older than N seconds (stale hints into the
            ear are worse than no hint — e.g. assistant mode uses a short TTL
            from settings.companion_assistant_event_ttl_seconds).
        """
        workspace = self._session_workspace(session_id)
        outbox = workspace / ".jervis" / "outbox" / "events.jsonl"
        end_marker = workspace / ".jervis" / "END"
        outbox.parent.mkdir(parents=True, exist_ok=True)
        outbox.touch(exist_ok=True)

        sleep_interval = max(0.05, settings.companion_inbox_poll_interval)

        with outbox.open("r", encoding="utf-8") as f:
            f.seek(0, 2)  # tail from EOF — backlog is already stale
            while True:
                if stop_event and stop_event.is_set():
                    break
                line = f.readline()
                if not line:
                    if end_marker.exists():
                        break
                    await asyncio.sleep(sleep_interval)
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    logger.warning("Malformed outbox line: %s", line[:200])
                    continue
                if max_age_seconds is not None and _event_is_stale(event, max_age_seconds):
                    logger.debug("Dropping stale companion event (age>%ss)", max_age_seconds)
                    continue
                yield event

    async def stop_session(self, session_id: str) -> None:
        """Request shutdown. END marker signals the runner; Job delete sends SIGTERM.

        No sleep — K8s handles pod termination lifecycle. The runner traps
        SIGTERM, flushes a final outbox note, and exits.
        """
        workspace = self._session_workspace(session_id)
        (workspace / ".jervis" / "END").write_text(_utc_now_iso())
        job_name = f"jervis-companion-s-{session_id}"
        try:
            job_runner.batch_v1.delete_namespaced_job(
                name=job_name,
                namespace=settings.k8s_namespace,
                propagation_policy="Background",
            )
            logger.info("Companion session %s stopped", session_id)
        except Exception as e:
            logger.warning("Failed to delete companion Job %s: %s", job_name, e)

    async def wait_for_result(self, dispatch: CompanionDispatch) -> str:
        """Block on K8s Job terminal state via watch (event-driven, no polling).

        Fail-fast: raises RuntimeError on Job failure or empty summary. Caller
        must not swallow — if the companion can't answer, the task fails.
        """
        loop = asyncio.get_event_loop()
        state = await loop.run_in_executor(None, self._watch_job_terminal, dispatch.job_name)
        if state != "succeeded":
            raise RuntimeError(f"Companion Job {dispatch.job_name} ended: {state}")
        result = self.collect_adhoc_result(dispatch.workspace_path)
        if not result:
            raise RuntimeError(f"Companion Job {dispatch.job_name}: no result.json")
        summary = (result.get("summary") or "").strip()
        if not summary:
            raise RuntimeError(f"Companion Job {dispatch.job_name}: empty summary")
        return summary

    @staticmethod
    def _watch_job_terminal(job_name: str) -> str:
        """Block until the Job reaches a terminal state. Event-driven K8s watch.

        active_deadline_seconds on the Job itself is the only real cap — we
        do not impose an orchestrator-side timeout here.
        """
        w = k8s_watch.Watch()
        try:
            for event in w.stream(
                job_runner.batch_v1.list_namespaced_job,
                namespace=settings.k8s_namespace,
                field_selector=f"metadata.name={job_name}",
            ):
                obj = event.get("object")
                if obj is None or obj.status is None:
                    continue
                if obj.status.succeeded and obj.status.succeeded > 0:
                    return "succeeded"
                if obj.status.failed and obj.status.failed > 0:
                    return "failed"
        finally:
            w.stop()
        return "unknown"

    # ---------- helpers ----------

    def count_running_sessions(self) -> int:
        try:
            jobs = job_runner.batch_v1.list_namespaced_job(
                namespace=settings.k8s_namespace,
                label_selector="app=jervis-companion,companion-mode=session",
            )
        except Exception as e:
            logger.warning("Failed to list companion sessions: %s", e)
            return 0
        return sum(1 for j in jobs.items if j.status.active and j.status.active > 0)

    def _session_workspace(self, session_id: str) -> Path:
        return self.data_root / session_id

    def _prepare_workspace(
        self,
        session_id: str,
        brief: str,
        context: dict,
        attachments: list[Path],
        client_id: str,
        project_id: str | None,
        language: str,
        mode: str,
    ) -> Path:
        workspace = self._session_workspace(session_id)
        (workspace / ".jervis" / "inbox").mkdir(parents=True, exist_ok=True)
        (workspace / ".jervis" / "outbox").mkdir(parents=True, exist_ok=True)
        (workspace / ".jervis" / "attachments").mkdir(parents=True, exist_ok=True)
        (workspace / ".claude").mkdir(parents=True, exist_ok=True)

        # Brief (role + goal + language)
        (workspace / ".jervis" / "brief.md").write_text(brief, encoding="utf-8")
        (workspace / ".jervis" / "context.json").write_text(
            json.dumps({
                "sessionId": session_id,
                "clientId": client_id,
                "projectId": project_id,
                "language": language,
                "mode": mode,
                **context,
            }, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )

        # Copy attachments (already staged on PVC by caller; we only reference)
        for att in attachments:
            if att.exists():
                dst = workspace / ".jervis" / "attachments" / att.name
                try:
                    dst.write_bytes(att.read_bytes())
                except Exception as e:
                    logger.warning("Failed to stage attachment %s: %s", att, e)

        # MCP config → jervis HTTP MCP
        mcp_server: dict = {
            "type": "http",
            "url": f"{settings.mcp_url}/mcp",
        }
        if settings.mcp_api_token:
            mcp_server["headers"] = {"Authorization": f"Bearer {settings.mcp_api_token}"}
        (workspace / ".claude" / "mcp.json").write_text(
            json.dumps({"mcpServers": {"jervis": mcp_server}}, indent=2),
            encoding="utf-8",
        )

        # CLAUDE.md — system prompt with role and ground rules
        (workspace / "CLAUDE.md").write_text(
            _build_companion_claude_md(client_id, project_id, language, mode),
            encoding="utf-8",
        )
        return workspace

    def _build_job_manifest(
        self,
        job_name: str,
        mode: str,
        session_id: str,
        task_id: str,
        client_id: str,
        project_id: str,
        workspace_path: str,
    ) -> client.V1Job:
        env_vars = [
            client.V1EnvVar(name="TASK_ID", value=task_id),
            client.V1EnvVar(name="SESSION_ID", value=session_id),
            client.V1EnvVar(name="CLIENT_ID", value=client_id),
            client.V1EnvVar(name="PROJECT_ID", value=project_id),
            client.V1EnvVar(name="WORKSPACE", value=workspace_path),
            client.V1EnvVar(name="AGENT_TYPE", value="companion"),
            client.V1EnvVar(name="COMPANION_MODE", value=mode),
        ]
        token = settings.claude_code_oauth_token
        if token:
            env_vars.append(client.V1EnvVar(name="CLAUDE_CODE_OAUTH_TOKEN", value=token))
        else:
            env_vars.append(client.V1EnvVar(
                name="CLAUDE_CODE_OAUTH_TOKEN",
                value_from=client.V1EnvVarSource(
                    secret_key_ref=client.V1SecretKeySelector(
                        name="jervis-secrets",
                        key="CLAUDE_CODE_OAUTH_TOKEN",
                        optional=True,
                    )
                ),
            ))
        if settings.anthropic_api_key:
            env_vars.append(client.V1EnvVar(name="ANTHROPIC_API_KEY", value=settings.anthropic_api_key))
        else:
            env_vars.append(client.V1EnvVar(
                name="ANTHROPIC_API_KEY",
                value_from=client.V1EnvVarSource(
                    secret_key_ref=client.V1SecretKeySelector(
                        name="jervis-secrets",
                        key="ANTHROPIC_API_KEY",
                        optional=True,
                    )
                ),
            ))

        labels = {
            "app": "jervis-companion",
            "companion-mode": mode,
            "session-id": session_id,
            "task-id": task_id,
        }

        return client.V1Job(
            metadata=client.V1ObjectMeta(
                name=job_name,
                namespace=settings.k8s_namespace,
                labels=labels,
            ),
            spec=client.V1JobSpec(
                ttl_seconds_after_finished=settings.job_ttl_seconds,
                backoff_limit=0,
                template=client.V1PodTemplateSpec(
                    metadata=client.V1ObjectMeta(labels=labels),
                    spec=client.V1PodSpec(
                        restart_policy="Never",
                        containers=[
                            client.V1Container(
                                name="companion",
                                image=COMPANION_IMAGE,
                                image_pull_policy="Always",
                                command=[COMPANION_ENTRYPOINT],
                                env=env_vars,
                                resources=client.V1ResourceRequirements(
                                    requests={"memory": "256Mi", "cpu": "250m"},
                                    limits={"memory": "2Gi", "cpu": "2000m"},
                                ),
                                volume_mounts=[
                                    client.V1VolumeMount(
                                        name="jervis-data",
                                        mount_path="/opt/jervis/data",
                                    )
                                ],
                            )
                        ],
                        volumes=[
                            client.V1Volume(
                                name="jervis-data",
                                persistent_volume_claim=client.V1PersistentVolumeClaimVolumeSource(
                                    claim_name="jervis-data-pvc",
                                ),
                            )
                        ],
                    ),
                ),
            ),
        )


def _utc_now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def _event_is_stale(event: dict, max_age_seconds: float) -> bool:
    ts = event.get("ts")
    if not ts:
        return False
    try:
        event_time = datetime.datetime.fromisoformat(ts)
    except ValueError:
        return False
    if event_time.tzinfo is None:
        event_time = event_time.replace(tzinfo=datetime.timezone.utc)
    age = (datetime.datetime.now(datetime.timezone.utc) - event_time).total_seconds()
    return age > max_age_seconds


def _build_companion_claude_md(client_id: str, project_id: str | None, language: str, mode: str) -> str:
    lang_label = "Czech" if language.lower().startswith("cs") else "English"
    mode_label = "persistent assistant session" if mode == "session" else "single analytical task"
    return "\n".join([
        "# Jervis Companion Agent",
        "",
        f"## Mode: {mode_label}",
        f"## Default output language: {lang_label}",
        "- User-facing answers (assistant bubble): match the user's language unless brief says otherwise.",
        "- Analytical/financial/mathematical/planning output: English is fine if the audience is internal.",
        "- Client-facing material: English by default.",
        "",
        "## Your role",
        "You are the deep-analysis sidekick of the Jervis orchestrator.",
        "The orchestrator handles routing, memory, KB graph and simple replies.",
        "You receive heavy tasks: complex analyses, budgets, plans, research, image/doc reading.",
        "Output is free-form text (markdown). Do not produce JSON unless explicitly asked.",
        "",
        "## Inputs",
        "- `.jervis/brief.md` — your goal, role, constraints",
        "- `.jervis/context.json` — session/client/project metadata",
        "- `.jervis/attachments/` — images, PDFs, transcripts (read them directly, do NOT call external VLMs)",
        "- Session mode only: `.jervis/inbox/events.jsonl` is appended by the orchestrator",
        "",
        "## Outputs",
        "- Adhoc mode: write your final answer to `.jervis/result.json` as `{taskId, success, summary}` (the runner helps)",
        "- Session mode: append events to `.jervis/outbox/events.jsonl` as JSON lines `{ts,type,content,final}`",
        "  - `type=answer` — user-facing reply",
        "  - `type=suggestion` — proactive hint (UI renders separately)",
        "  - `type=note` — internal note for orchestrator",
        "",
        "## Knowledge Base (MCP `jervis`)",
        "- `kb_search(query, client_id, project_id)` — hybrid RAG + graph",
        "- `kb_search_simple(query)` — quick RAG",
        "- `kb_graph_search`, `kb_traverse`, `kb_get_evidence`, `kb_resolve_alias`",
        "- `kb_store(content, kind)` — store findings (sparingly; only non-trivial)",
        "- `web_search`, `o365_*`, `get_meeting_transcript`, `mongo_query`, `ask_jervis`",
        "",
        f"Default context: client_id=`{client_id}`, project_id=`{project_id or ''}`",
        "",
        "## Hard rules",
        "- You are READ-ONLY on the codebase. Do NOT run git, edit project files, or modify source.",
        "- Do NOT call external VLMs for images — open them directly with the Read tool.",
        "- Do NOT `find /` or scan the filesystem blindly.",
        "- Keep the orchestrator informed via the outbox — the user does not see your inner dialog.",
    ])


# Singleton
companion_runner = CompanionRunner()
