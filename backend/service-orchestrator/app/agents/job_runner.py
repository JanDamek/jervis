"""K8s Job runner for coding agents.

Creates K8s Jobs, streams logs, waits for completion.
Each coding task runs as an ephemeral K8s Job – no persistent Deployments.

Supports both blocking (run_coding_agent) and non-blocking (create + check + read)
modes. Non-blocking mode is used with AgentJobWatcher for interrupt-based execution.

Phase 2: Uses AgentPool for configurable concurrent limits with priority queue.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Callable
from pathlib import Path

from kubernetes import client, config, watch

from app.config import settings

logger = logging.getLogger(__name__)

# Agent image mapping
AGENT_IMAGES: dict[str, str] = {
    "aider": f"{settings.container_registry}/jervis-aider:latest",
    "openhands": f"{settings.container_registry}/jervis-coding-engine:latest",
    "claude": f"{settings.container_registry}/jervis-claude:latest",
    "junie": f"{settings.container_registry}/jervis-junie:latest",
}

# Agent timeouts (seconds)
AGENT_TIMEOUTS: dict[str, int] = {
    "aider": settings.agent_timeout_aider,
    "openhands": settings.agent_timeout_openhands,
    "claude": settings.agent_timeout_claude,
    "junie": settings.agent_timeout_junie,
}


class JobRunner:
    """Manages K8s Jobs for coding agents."""

    def __init__(self):
        self._batch_v1: client.BatchV1Api | None = None
        self._core_v1: client.CoreV1Api | None = None

    def _init_k8s(self):
        """Lazy-init K8s clients."""
        if self._batch_v1 is None:
            try:
                config.load_incluster_config()
            except config.ConfigException:
                config.load_kube_config()
            self._batch_v1 = client.BatchV1Api()
            self._core_v1 = client.CoreV1Api()

    @property
    def batch_v1(self) -> client.BatchV1Api:
        self._init_k8s()
        return self._batch_v1

    @property
    def core_v1(self) -> client.CoreV1Api:
        self._init_k8s()
        return self._core_v1

    # --- Non-blocking API (used with AgentJobWatcher + interrupt) ---

    async def create_coding_agent_job(
        self,
        task_id: str,
        agent_type: str,
        client_id: str,
        project_id: str | None,
        workspace_path: str,
        allow_git: bool = False,
        instructions_override: str | None = None,
        thread_id: str = "",
        priority: int = 0,
    ) -> str:
        """Create K8s Job and return job_name. Does NOT wait for completion.

        Uses AgentPool for concurrency control. If all slots for this agent type
        are occupied, blocks (with priority ordering) until a slot is freed or
        the pool wait timeout is reached.

        Args:
            task_id: Unique task identifier.
            agent_type: "aider" | "openhands" | "claude" | "junie"
            client_id: Client ID for scoping.
            project_id: Project ID (optional).
            workspace_path: Absolute path to workspace on shared PVC.
            allow_git: If True, agent may perform git operations.
            instructions_override: Override workspace instructions (for git delegation).
            thread_id: LangGraph thread ID (for pool tracking).
            priority: Task priority (0=foreground, 10=background).

        Returns:
            job_name: Name of the created K8s Job.

        Raises:
            AgentPoolFullError: If no slot available within timeout.
        """
        from app.agents.agent_pool import agent_pool, TaskPriority

        ns = settings.k8s_namespace

        # Acquire pool slot (blocks if full, with priority ordering)
        await agent_pool.acquire(
            agent_type=agent_type,
            priority=TaskPriority(priority),
        )

        # Write override instructions if provided
        if instructions_override:
            jervis_dir = Path(workspace_path) / ".jervis"
            jervis_dir.mkdir(exist_ok=True)
            (jervis_dir / "instructions.md").write_text(instructions_override)

        # Fetch GPG key for commit signing (if configured)
        gpg_key = None
        if allow_git and client_id:
            from app.tools.kotlin_client import kotlin_client
            gpg_key = await kotlin_client.get_gpg_key(client_id)
            if gpg_key:
                logger.info("GPG key found for client %s (keyId=%s)", client_id, gpg_key.get("keyId"))

        # Build and create Job
        job_name = f"jervis-{agent_type}-{task_id[:12]}"
        job = self._build_job_manifest(
            job_name=job_name,
            agent_type=agent_type,
            task_id=task_id,
            client_id=client_id,
            project_id=project_id or "",
            workspace_path=workspace_path,
            allow_git=allow_git,
            gpg_key=gpg_key,
        )

        logger.info("Creating K8s Job: %s (agent=%s, task=%s)", job_name, agent_type, task_id)
        self.batch_v1.create_namespaced_job(namespace=ns, body=job)

        # Track job in pool for stuck detection and metrics
        timeout = AGENT_TIMEOUTS.get(agent_type, 1800)
        agent_pool.mark_started(
            job_name=job_name,
            agent_type=agent_type,
            task_id=task_id,
            thread_id=thread_id,
            timeout_seconds=timeout,
        )

        return job_name

    def check_job_status(self, job_name: str) -> dict:
        """Check K8s Job status (single non-blocking check).

        Returns:
            {"status": "running"|"succeeded"|"failed"|"not_found", "succeeded": bool}
        """
        ns = settings.k8s_namespace
        try:
            job = self.batch_v1.read_namespaced_job(name=job_name, namespace=ns)

            if job.status.succeeded and job.status.succeeded > 0:
                return {"status": "succeeded", "succeeded": True}

            if job.status.failed and job.status.failed > 0:
                return {"status": "failed", "succeeded": False}

            return {"status": "running", "succeeded": False}
        except client.ApiException as e:
            if e.status == 404:
                return {"status": "not_found", "succeeded": False}
            raise

    def read_job_result(self, task_id: str, workspace_path: str, job_name: str) -> dict:
        """Read result.json from workspace after job completes.

        Returns:
            Result dict from result.json or fallback status dict.
        """
        result_file = Path(workspace_path) / ".jervis" / "result.json"
        if result_file.exists():
            try:
                return json.loads(result_file.read_text())
            except json.JSONDecodeError:
                pass

        # Fallback: check job status for summary
        status = self.check_job_status(job_name)
        return {
            "taskId": task_id,
            "success": status.get("succeeded", False),
            "summary": f"Job {job_name} finished: {status.get('status', 'unknown')}",
            "agentType": "",
        }

    # --- Blocking API (legacy, wraps non-blocking methods) ---

    async def run_coding_agent(
        self,
        task_id: str,
        agent_type: str,
        client_id: str,
        project_id: str | None,
        workspace_path: str,
        allow_git: bool = False,
        instructions_override: str | None = None,
        on_log_line: Callable | None = None,
    ) -> dict:
        """Run a coding agent as a K8s Job and wait for completion (blocking).

        This is the legacy blocking API. For non-blocking execution,
        use create_coding_agent_job() + check_job_status() + read_job_result().

        Args:
            task_id: Unique task identifier.
            agent_type: "aider" | "openhands" | "claude" | "junie"
            client_id: Client ID for scoping.
            project_id: Project ID (optional).
            workspace_path: Absolute path to workspace on shared PVC.
            allow_git: If True, agent may perform git operations.
            instructions_override: Override workspace instructions (for git delegation).
            on_log_line: Callback for streaming log lines.

        Returns:
            Result dict from result.json or job status.
        """
        job_name = await self.create_coding_agent_job(
            task_id=task_id,
            agent_type=agent_type,
            client_id=client_id,
            project_id=project_id,
            workspace_path=workspace_path,
            allow_git=allow_git,
            instructions_override=instructions_override,
        )

        # Stream logs in background
        log_task = None
        if on_log_line:
            log_task = asyncio.create_task(
                self._stream_job_logs(job_name, on_log_line)
            )

        # Wait for completion (blocking poll)
        result = await self._wait_for_job(job_name)

        if log_task:
            log_task.cancel()

        return self.read_job_result(task_id, workspace_path, job_name)

    def count_running_jobs(self, agent_type: str) -> int:
        """Count active Jobs for an agent type.

        Uses in-memory pool count (fast). Falls back to K8s API for verification.
        """
        from app.agents.agent_pool import agent_pool
        return agent_pool.active_count(agent_type)

    def count_running_jobs_k8s(self, agent_type: str) -> int:
        """Count active Jobs via K8s API (slower, for verification/recovery)."""
        ns = settings.k8s_namespace
        jobs = self.batch_v1.list_namespaced_job(
            namespace=ns,
            label_selector=f"agent-type={agent_type},app=jervis-coding-agent",
        )
        return sum(
            1
            for j in jobs.items
            if j.status.active and j.status.active > 0
        )

    async def _wait_for_job(
        self, job_name: str, timeout_seconds: int = 2700
    ) -> dict:
        """Wait for K8s Job to complete (blocking poll loop)."""
        ns = settings.k8s_namespace
        deadline = asyncio.get_event_loop().time() + timeout_seconds

        while asyncio.get_event_loop().time() < deadline:
            job = self.batch_v1.read_namespaced_job(
                name=job_name, namespace=ns
            )

            if job.status.succeeded and job.status.succeeded > 0:
                return {"status": "succeeded", "succeeded": True}

            if job.status.failed and job.status.failed > 0:
                return {"status": "failed", "succeeded": False}

            await asyncio.sleep(5)

        return {"status": "timeout", "succeeded": False}

    async def _stream_job_logs(
        self, job_name: str, callback: Callable
    ):
        """Stream logs from Job pod."""
        ns = settings.k8s_namespace

        # Wait for pod to exist
        pod_name = None
        for _ in range(60):
            pods = self.core_v1.list_namespaced_pod(
                namespace=ns,
                label_selector=f"job-name={job_name}",
            )
            if pods.items:
                pod = pods.items[0]
                if pod.status.phase in ("Running", "Succeeded", "Failed"):
                    pod_name = pod.metadata.name
                    break
            await asyncio.sleep(2)

        if not pod_name:
            return

        # Stream logs
        w = watch.Watch()
        try:
            for line in w.stream(
                self.core_v1.read_namespaced_pod_log,
                name=pod_name,
                namespace=ns,
                follow=True,
            ):
                if callback:
                    if asyncio.iscoroutinefunction(callback):
                        await callback(line)
                    else:
                        callback(line)
        except Exception:
            pass  # Pod terminated

    def _build_job_manifest(
        self,
        job_name: str,
        agent_type: str,
        task_id: str,
        client_id: str,
        project_id: str,
        workspace_path: str,
        allow_git: bool = False,
        gpg_key: dict | None = None,
    ) -> client.V1Job:
        """Build K8s Job manifest for a coding agent."""
        image = AGENT_IMAGES.get(agent_type)
        if not image:
            raise ValueError(f"Unknown agent type: {agent_type}")

        timeout = AGENT_TIMEOUTS.get(agent_type, 1800)

        env_vars = [
            client.V1EnvVar(name="TASK_ID", value=task_id),
            client.V1EnvVar(name="CLIENT_ID", value=client_id),
            client.V1EnvVar(name="PROJECT_ID", value=project_id),
            client.V1EnvVar(name="WORKSPACE", value=workspace_path),
            client.V1EnvVar(name="AGENT_TYPE", value=agent_type),
            client.V1EnvVar(name="ALLOW_GIT", value=str(allow_git).lower()),
            # Auth from K8s secrets
            client.V1EnvVar(
                name="ANTHROPIC_API_KEY",
                value_from=client.V1EnvVarSource(
                    secret_key_ref=client.V1SecretKeySelector(
                        name="jervis-secrets",
                        key="ANTHROPIC_API_KEY",
                        optional=True,
                    )
                ),
            ),
            client.V1EnvVar(
                name="CLAUDE_CODE_OAUTH_TOKEN",
                value_from=client.V1EnvVarSource(
                    secret_key_ref=client.V1SecretKeySelector(
                        name="jervis-secrets",
                        key="CLAUDE_CODE_OAUTH_TOKEN",
                        optional=True,
                    )
                ),
            ),
        ]

        # GPG commit signing (injected from client certificate when allow_git=True)
        if gpg_key:
            env_vars.extend([
                client.V1EnvVar(name="GPG_PRIVATE_KEY", value=gpg_key.get("privateKeyArmored", "")),
                client.V1EnvVar(name="GPG_KEY_ID", value=gpg_key.get("keyId", "")),
                client.V1EnvVar(name="GPG_KEY_PASSPHRASE", value=gpg_key.get("passphrase", "")),
                client.V1EnvVar(name="GIT_COMMITTER_NAME", value=gpg_key.get("userName", "")),
                client.V1EnvVar(name="GIT_COMMITTER_EMAIL", value=gpg_key.get("userEmail", "")),
            ])

        return client.V1Job(
            metadata=client.V1ObjectMeta(
                name=job_name,
                namespace=settings.k8s_namespace,
                labels={
                    "app": "jervis-coding-agent",
                    "agent-type": agent_type,
                    "task-id": task_id,
                },
            ),
            spec=client.V1JobSpec(
                ttl_seconds_after_finished=settings.job_ttl_seconds,
                backoff_limit=0,
                active_deadline_seconds=timeout,
                template=client.V1PodTemplateSpec(
                    metadata=client.V1ObjectMeta(
                        labels={
                            "app": "jervis-coding-agent",
                            "agent-type": agent_type,
                            "task-id": task_id,
                        }
                    ),
                    spec=client.V1PodSpec(
                        restart_policy="Never",
                        containers=[
                            client.V1Container(
                                name="agent",
                                image=image,
                                image_pull_policy="Always",
                                command=["/opt/jervis/entrypoint-job.sh"],
                                env=env_vars,
                                resources=client.V1ResourceRequirements(
                                    requests={"memory": "256Mi", "cpu": "250m"},
                                    limits={"memory": "1Gi", "cpu": "1000m"},
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


# Singleton
job_runner = JobRunner()
