"""K8s Job runner for coding agents.

Creates K8s Jobs, streams logs, waits for completion.
Each coding task runs as an ephemeral K8s Job – no persistent Deployments.
"""

from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path

import httpx
from kubernetes import client, config, watch

from app.config import settings

logger = logging.getLogger(__name__)

# Agent image mapping (aider, openhands, junie removed — only claude + kilo)
AGENT_IMAGES: dict[str, str] = {
    "claude": f"{settings.container_registry}/jervis-claude:latest",
    "kilo": f"{settings.container_registry}/jervis-kilo:latest",
}

# Agent timeouts (seconds)
AGENT_TIMEOUTS: dict[str, int] = {
    "claude": settings.agent_timeout_claude,
    "kilo": settings.agent_timeout_kilo,
}

# Max concurrent jobs per agent type
MAX_CONCURRENT: dict[str, int] = {
    "claude": 2,
    "kilo": 2,
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

    async def run_coding_agent(
        self,
        task_id: str,
        agent_type: str,
        client_id: str,
        project_id: str | None,
        workspace_path: str,
        allow_git: bool = False,
        instructions_override: str | None = None,
        on_log_line: callable | None = None,
        gpg_key_id: str | None = None,
        git_user_name: str | None = None,
        git_user_email: str | None = None,
        claude_token: str | None = None,
    ) -> dict:
        """Run a coding agent as a K8s Job and wait for completion.

        Args:
            task_id: Unique task identifier.
            agent_type: "claude" | "kilo"
            client_id: Client ID for scoping.
            project_id: Project ID (optional).
            workspace_path: Absolute path to workspace on shared PVC.
            allow_git: If True, agent may perform git operations.
            instructions_override: Override workspace instructions (for git delegation).
            on_log_line: Callback for streaming log lines.
            gpg_key_id: Specific GPG key ID from project/client settings.
            git_user_name: Git author name from project/client settings.
            git_user_email: Git author email from project/client settings.

        Returns:
            Result dict from result.json or job status.
        """
        ns = settings.k8s_namespace

        # Check concurrent limit
        running = self.count_running_jobs(agent_type)
        max_concurrent = MAX_CONCURRENT.get(agent_type, 1)
        if running >= max_concurrent:
            raise RuntimeError(
                f"Agent {agent_type} has {running}/{max_concurrent} running jobs. "
                f"Wait or increase limit."
            )

        # Write override instructions if provided
        if instructions_override:
            jervis_dir = Path(workspace_path) / ".jervis"
            jervis_dir.mkdir(exist_ok=True)
            (jervis_dir / "instructions.md").write_text(instructions_override)

        # Fetch GPG key for commit signing (if configured)
        gpg_key = await self._fetch_gpg_key(client_id, gpg_key_id) if allow_git else None

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
            git_user_name=git_user_name,
            git_user_email=git_user_email,
            claude_token=claude_token,
        )

        logger.info("Creating K8s Job: %s (agent=%s, task=%s, gpg=%s)", job_name, agent_type, task_id, gpg_key is not None)
        self.batch_v1.create_namespaced_job(namespace=ns, body=job)

        # Stream logs in background
        log_task = None
        if on_log_line:
            log_task = asyncio.create_task(
                self._stream_job_logs(job_name, on_log_line)
            )

        # Wait for completion
        result = await self._wait_for_job(job_name)

        if log_task:
            log_task.cancel()

        # Read result.json from workspace
        result_file = Path(workspace_path) / ".jervis" / "result.json"
        if result_file.exists():
            try:
                return json.loads(result_file.read_text())
            except json.JSONDecodeError as e:
                logger.warning(
                    "Malformed result.json for job=%s task=%s: %s",
                    job_name, task_id, e,
                )

        return {
            "taskId": task_id,
            "success": result.get("succeeded", False),
            "summary": f"Job {job_name} finished: {result.get('status', 'unknown')}",
            "agentType": agent_type,
        }

    def count_running_jobs(self, agent_type: str) -> int:
        """Count active Jobs for an agent type."""
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
        """Wait for K8s Job to complete."""
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
        self, job_name: str, callback: callable
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

    async def dispatch_coding_agent(
        self,
        task_id: str,
        agent_type: str,
        client_id: str,
        project_id: str | None,
        workspace_path: str,
        allow_git: bool = False,
        instructions_override: str | None = None,
        gpg_key_id: str | None = None,
        git_user_name: str | None = None,
        git_user_email: str | None = None,
        claude_token: str | None = None,
    ) -> dict:
        """Dispatch a coding agent as a K8s Job and return immediately (non-blocking).

        Returns:
            Dict with job_name and metadata — does NOT wait for completion.
        """
        # Check concurrent limit
        running = self.count_running_jobs(agent_type)
        max_concurrent = MAX_CONCURRENT.get(agent_type, 1)
        if running >= max_concurrent:
            raise RuntimeError(
                f"Agent {agent_type} has {running}/{max_concurrent} running jobs. "
                f"Wait or increase limit."
            )

        # Write override instructions if provided
        if instructions_override:
            jervis_dir = Path(workspace_path) / ".jervis"
            jervis_dir.mkdir(exist_ok=True)
            (jervis_dir / "instructions.md").write_text(instructions_override)

        # Fetch GPG key for commit signing (if configured)
        gpg_key = await self._fetch_gpg_key(client_id, gpg_key_id) if allow_git else None

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
            git_user_name=git_user_name,
            git_user_email=git_user_email,
            claude_token=claude_token,
        )

        logger.info("Dispatching K8s Job (async): %s (agent=%s, task=%s, gpg=%s)", job_name, agent_type, task_id, gpg_key is not None)
        self.batch_v1.create_namespaced_job(namespace=settings.k8s_namespace, body=job)

        return {
            "job_name": job_name,
            "agent_type": agent_type,
            "task_id": task_id,
            "workspace_path": workspace_path,
        }

    def get_job_status(self, job_name: str) -> dict:
        """Check K8s Job status (non-blocking, single poll).

        Returns:
            Dict with status: "running", "succeeded", or "failed".
        """
        try:
            job = self.batch_v1.read_namespaced_job(
                name=job_name, namespace=settings.k8s_namespace,
            )
        except Exception as e:
            logger.warning("Failed to read job %s: %s", job_name, e)
            return {"status": "unknown", "error": str(e)}

        if job.status.succeeded and job.status.succeeded > 0:
            return {"status": "succeeded"}
        if job.status.failed and job.status.failed > 0:
            return {"status": "failed"}
        return {"status": "running"}

    def collect_result(self, workspace_path: str, job_name: str, task_id: str, agent_type: str) -> dict:
        """Collect result from completed K8s Job workspace."""
        result_file = Path(workspace_path) / ".jervis" / "result.json"
        if result_file.exists():
            try:
                return json.loads(result_file.read_text())
            except json.JSONDecodeError as e:
                logger.warning(
                    "Malformed result.json in collect_result for job=%s task=%s: %s",
                    job_name, task_id, e,
                )

        status = self.get_job_status(job_name)
        return {
            "taskId": task_id,
            "success": status.get("status") == "succeeded",
            "summary": f"Job {job_name} finished: {status.get('status', 'unknown')}",
            "agentType": agent_type,
        }

    async def _fetch_gpg_key(self, client_id: str, gpg_key_id: str | None = None) -> dict | None:
        """Fetch GPG key from Kotlin server for commit signing.

        Args:
            client_id: Client ID (used as fallback lookup).
            gpg_key_id: Specific GPG key ID from project/client git config.
                        If provided, server looks up by key ID first.
        """
        try:
            params = {}
            if gpg_key_id:
                params["gpgKeyId"] = gpg_key_id
            async with httpx.AsyncClient(timeout=10) as http:
                resp = await http.get(
                    f"{settings.kotlin_server_url}/internal/gpg-key/{client_id}",
                    params=params,
                )
                if resp.status_code != 200:
                    return None
                data = resp.json()
                if data.get("keyId"):
                    return data
        except Exception as e:
            logger.warning("Failed to fetch GPG key for client %s: %s", client_id, e)
        return None

    def cancel_job(self, job_name: str):
        """Cancel (delete) a K8s Job."""
        try:
            self.batch_v1.delete_namespaced_job(
                name=job_name,
                namespace=settings.k8s_namespace,
                propagation_policy="Background",
            )
            logger.info("Cancelled K8s Job: %s", job_name)
        except Exception as e:
            logger.warning("Failed to cancel job %s: %s", job_name, e)

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
        git_user_name: str | None = None,
        git_user_email: str | None = None,
        claude_token: str | None = None,
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
        ]

        # Auth: prefer direct token from orchestrator env, fallback to K8s secret
        effective_token = claude_token or settings.claude_code_oauth_token
        if effective_token:
            env_vars.append(client.V1EnvVar(name="CLAUDE_CODE_OAUTH_TOKEN", value=effective_token))
        else:
            # Fallback: try K8s secret (may be empty)
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

        if gpg_key:
            env_vars.append(client.V1EnvVar(name="GPG_KEY_ID", value=gpg_key["keyId"]))
            env_vars.append(client.V1EnvVar(name="GPG_PRIVATE_KEY", value=gpg_key["privateKeyArmored"]))
            if gpg_key.get("passphrase"):
                env_vars.append(client.V1EnvVar(name="GPG_PASSPHRASE", value=gpg_key["passphrase"]))

        # Git user identity (global fallback — local config is set by workspace_manager)
        effective_name = git_user_name or (gpg_key.get("userName") if gpg_key else None)
        effective_email = git_user_email or (gpg_key.get("userEmail") if gpg_key else None)
        if effective_name:
            env_vars.append(client.V1EnvVar(name="GIT_USER_NAME", value=effective_name))
        if effective_email:
            env_vars.append(client.V1EnvVar(name="GIT_USER_EMAIL", value=effective_email))

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
