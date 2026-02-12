import json
import os
import asyncio
import logging
from pathlib import Path
from pydantic import BaseModel
from typing import Optional

logger = logging.getLogger(__name__)

JOERN_IMAGE = os.getenv("JOERN_IMAGE", "registry.damek-soft.eu/jandamek/jervis-joern:latest")
K8S_NAMESPACE = os.getenv("K8S_NAMESPACE", "jervis")
PVC_NAME = os.getenv("DATA_PVC_NAME", "jervis-data-pvc")
PVC_MOUNT = os.getenv("DATA_PVC_MOUNT", "/opt/jervis/data")

# When running locally (outside K8s), fall back to subprocess
IN_CLUSTER = os.path.exists("/var/run/secrets/kubernetes.io/serviceaccount/token")


class JoernResultDto(BaseModel):
    stdout: str
    stderr: Optional[str] = None
    exitCode: int


class JoernClient:
    """Runs Joern analysis as a K8s Job (in-cluster) or subprocess (local dev)."""

    async def run(self, query: str, workspace_path: str) -> JoernResultDto:
        """
        Run a Joern query against a project directory on PVC.

        Args:
            query: Joern query script content
            workspace_path: Absolute path to the project directory on PVC
        """
        jervis_dir = Path(workspace_path) / ".jervis"
        jervis_dir.mkdir(parents=True, exist_ok=True)

        query_file = jervis_dir / "joern-query.sc"
        result_file = jervis_dir / "joern-result.json"

        # Write query to PVC
        query_file.write_text(query)

        # Clean up any previous result
        if result_file.exists():
            result_file.unlink()

        try:
            if IN_CLUSTER:
                await self._run_k8s_job(workspace_path)
            else:
                await self._run_local(workspace_path)

            # Read result from PVC
            if result_file.exists():
                data = json.loads(result_file.read_text())
                return JoernResultDto(**data)
            else:
                return JoernResultDto(
                    stdout="",
                    stderr="Joern Job completed but no result file found",
                    exitCode=1,
                )
        except Exception as e:
            logger.error(f"Joern execution failed: {e}")
            raise
        finally:
            # Clean up query file
            if query_file.exists():
                query_file.unlink()

    async def _run_k8s_job(self, workspace_path: str):
        """Create and wait for a K8s Job running Joern."""
        try:
            from kubernetes import client, config
            from kubernetes.client.rest import ApiException
        except ImportError:
            raise RuntimeError("kubernetes package not installed. Install with: pip install kubernetes")

        config.load_incluster_config()
        batch_v1 = client.BatchV1Api()

        import uuid
        job_name = f"joern-{uuid.uuid4().hex[:8]}"

        job = client.V1Job(
            api_version="batch/v1",
            kind="Job",
            metadata=client.V1ObjectMeta(
                name=job_name,
                namespace=K8S_NAMESPACE,
                labels={"app": "jervis-joern", "type": "job"},
            ),
            spec=client.V1JobSpec(
                ttl_seconds_after_finished=300,
                backoff_limit=0,
                template=client.V1PodTemplateSpec(
                    metadata=client.V1ObjectMeta(
                        labels={"app": "jervis-joern", "type": "job"},
                    ),
                    spec=client.V1PodSpec(
                        restart_policy="Never",
                        containers=[
                            client.V1Container(
                                name="joern",
                                image=JOERN_IMAGE,
                                env=[
                                    client.V1EnvVar(name="WORKSPACE", value=workspace_path),
                                ],
                                volume_mounts=[
                                    client.V1VolumeMount(
                                        name="data",
                                        mount_path=PVC_MOUNT,
                                    ),
                                ],
                                resources=client.V1ResourceRequirements(
                                    requests={"memory": "512Mi", "cpu": "500m"},
                                    limits={"memory": "4Gi", "cpu": "2"},
                                ),
                            ),
                        ],
                        volumes=[
                            client.V1Volume(
                                name="data",
                                persistent_volume_claim=client.V1PersistentVolumeClaimVolumeSource(
                                    claim_name=PVC_NAME,
                                ),
                            ),
                        ],
                    ),
                ),
            ),
        )

        logger.info(f"Creating Joern K8s Job: {job_name}")
        batch_v1.create_namespaced_job(namespace=K8S_NAMESPACE, body=job)

        # Wait for job completion (poll every 5s, timeout 10min)
        timeout = 600
        poll_interval = 5
        elapsed = 0

        while elapsed < timeout:
            await asyncio.sleep(poll_interval)
            elapsed += poll_interval

            try:
                status = batch_v1.read_namespaced_job_status(name=job_name, namespace=K8S_NAMESPACE)
            except ApiException as e:
                logger.warning(f"Failed to read job status: {e}")
                continue

            if status.status.succeeded and status.status.succeeded > 0:
                logger.info(f"Joern Job {job_name} completed successfully")
                return

            if status.status.failed and status.status.failed > 0:
                raise RuntimeError(f"Joern Job {job_name} failed")

        raise TimeoutError(f"Joern Job {job_name} timed out after {timeout}s")

    async def _run_local(self, workspace_path: str):
        """Run Joern locally via subprocess (for development)."""
        joern_home = os.getenv("JOERN_HOME", "")
        joern_bin = os.path.join(joern_home, "joern-cli", "joern") if joern_home else "joern"

        query_file = os.path.join(workspace_path, ".jervis", "joern-query.sc")
        result_file = os.path.join(workspace_path, ".jervis", "joern-result.json")

        cmd = [joern_bin, "--script", query_file, "--param", f"inputPath={workspace_path}"]
        logger.info(f"Running Joern locally: {' '.join(cmd)}")

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout_bytes, stderr_bytes = await proc.communicate()

        result = {
            "stdout": stdout_bytes.decode("utf-8", errors="replace"),
            "stderr": stderr_bytes.decode("utf-8", errors="replace"),
            "exitCode": proc.returncode or 0,
        }

        Path(result_file).write_text(json.dumps(result))

    # -----------------------------------------------------------------------
    # CPG Export — generates pruned Code Property Graph for ArangoDB ingest
    # -----------------------------------------------------------------------

    async def run_cpg_export(self, workspace_path: str) -> dict:
        """Run Joern CPG export against a project directory.

        Uses the baked-in cpg-export-query.sc script (not ad-hoc query).
        Returns parsed CPG data with methods, types, calls, and typeRefs.

        Args:
            workspace_path: Absolute path to the project directory on PVC

        Returns:
            Dict with keys: methods, types, calls, typeRefs
        """
        jervis_dir = Path(workspace_path) / ".jervis"
        jervis_dir.mkdir(parents=True, exist_ok=True)

        export_file = jervis_dir / "cpg-export.json"
        status_file = jervis_dir / "cpg-export-status.json"

        # Clean up previous results
        for f in [export_file, status_file]:
            if f.exists():
                f.unlink()

        try:
            if IN_CLUSTER:
                await self._run_cpg_export_k8s(workspace_path)
            else:
                await self._run_cpg_export_local(workspace_path)

            # Read CPG export from PVC
            if export_file.exists():
                data = json.loads(export_file.read_text())
                logger.info(
                    "CPG export loaded: %d methods, %d types, %d calls, %d typeRefs",
                    len(data.get("methods", [])),
                    len(data.get("types", [])),
                    len(data.get("calls", [])),
                    len(data.get("typeRefs", [])),
                )
                return data
            else:
                # Check status file for error details
                stderr = ""
                if status_file.exists():
                    status = json.loads(status_file.read_text())
                    stderr = status.get("stderr", "")
                raise RuntimeError(f"CPG export completed but no export file found. stderr: {stderr[:500]}")
        except Exception as e:
            logger.error(f"CPG export failed: {e}")
            raise
        finally:
            # Clean up status file (keep export file for debugging)
            if status_file.exists():
                try:
                    status_file.unlink()
                except Exception:
                    pass

    async def _run_cpg_export_k8s(self, workspace_path: str):
        """Create K8s Job for CPG export with custom entrypoint."""
        try:
            from kubernetes import client, config
            from kubernetes.client.rest import ApiException
        except ImportError:
            raise RuntimeError("kubernetes package not installed")

        config.load_incluster_config()
        batch_v1 = client.BatchV1Api()

        import uuid
        job_name = f"joern-cpg-{uuid.uuid4().hex[:8]}"

        job = client.V1Job(
            api_version="batch/v1",
            kind="Job",
            metadata=client.V1ObjectMeta(
                name=job_name,
                namespace=K8S_NAMESPACE,
                labels={"app": "jervis-joern", "type": "cpg-export"},
            ),
            spec=client.V1JobSpec(
                ttl_seconds_after_finished=300,
                backoff_limit=0,
                template=client.V1PodTemplateSpec(
                    metadata=client.V1ObjectMeta(
                        labels={"app": "jervis-joern", "type": "cpg-export"},
                    ),
                    spec=client.V1PodSpec(
                        restart_policy="Never",
                        containers=[
                            client.V1Container(
                                name="joern",
                                image=JOERN_IMAGE,
                                # Override entrypoint to use CPG export script
                                command=["/opt/jervis/entrypoint-joern-cpg-export.sh"],
                                env=[
                                    client.V1EnvVar(name="WORKSPACE", value=workspace_path),
                                ],
                                volume_mounts=[
                                    client.V1VolumeMount(
                                        name="data",
                                        mount_path=PVC_MOUNT,
                                    ),
                                ],
                                resources=client.V1ResourceRequirements(
                                    requests={"memory": "1Gi", "cpu": "1"},
                                    limits={"memory": "6Gi", "cpu": "4"},
                                ),
                            ),
                        ],
                        volumes=[
                            client.V1Volume(
                                name="data",
                                persistent_volume_claim=client.V1PersistentVolumeClaimVolumeSource(
                                    claim_name=PVC_NAME,
                                ),
                            ),
                        ],
                    ),
                ),
            ),
        )

        logger.info(f"Creating Joern CPG Export K8s Job: {job_name}")
        batch_v1.create_namespaced_job(namespace=K8S_NAMESPACE, body=job)

        # Wait for job completion (poll every 10s, timeout 5min)
        timeout = 300
        poll_interval = 10
        elapsed = 0

        while elapsed < timeout:
            await asyncio.sleep(poll_interval)
            elapsed += poll_interval

            try:
                status = batch_v1.read_namespaced_job_status(name=job_name, namespace=K8S_NAMESPACE)
            except ApiException as e:
                logger.warning(f"Failed to read CPG export job status: {e}")
                continue

            if status.status.succeeded and status.status.succeeded > 0:
                logger.info(f"Joern CPG Export Job {job_name} completed successfully")
                return

            if status.status.failed and status.status.failed > 0:
                raise RuntimeError(f"Joern CPG Export Job {job_name} failed")

        raise TimeoutError(f"Joern CPG Export Job {job_name} timed out after {timeout}s")

    async def _run_cpg_export_local(self, workspace_path: str):
        """Run CPG export locally via subprocess (for development)."""
        joern_home = os.getenv("JOERN_HOME", "")
        joern_bin = os.path.join(joern_home, "joern-cli", "joern") if joern_home else "joern"

        # Use the same export script path as in the Docker image
        export_script = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(__file__)))),
            "..", "service-joern", "cpg-export-query.sc"
        )
        if not os.path.exists(export_script):
            raise RuntimeError(f"CPG export script not found at {export_script}")

        cmd = [joern_bin, "--script", export_script, "--param", f"inputPath={workspace_path}"]
        logger.info(f"Running Joern CPG export locally: {' '.join(cmd)}")

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout_bytes, stderr_bytes = await proc.communicate()

        if proc.returncode != 0:
            stderr = stderr_bytes.decode("utf-8", errors="replace")[:500]
            raise RuntimeError(f"Joern CPG export failed (exit={proc.returncode}): {stderr}")
