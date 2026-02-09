"""MCP Server for Joern Code Analysis.

Provides Joern CPG (Code Property Graph) analysis to coding agents via MCP.
Runs as stdio process – agents (Claude Code, Junie) connect via MCP config.

Joern itself is too large (~2GB) to install in every agent container.
Instead, this lightweight MCP server creates K8s Jobs that run Joern CLI
on a shared PVC and returns the results.

Required env vars:
    WORKSPACE      – path to project directory on shared PVC
    K8S_NAMESPACE  – Kubernetes namespace (default: jervis)

Optional env vars:
    JOERN_IMAGE    – Joern container image (default: registry.damek-soft.eu/jandamek/jervis-joern:latest)
    DATA_PVC_NAME  – PVC name (default: jervis-data-pvc)
    DATA_PVC_MOUNT – PVC mount path (default: /opt/jervis/data)
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import uuid
from pathlib import Path

from mcp.server import Server
from mcp.server.stdio import stdio_server

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Server("jervis-joern")

# Configuration from environment
WORKSPACE = os.environ.get("WORKSPACE", "")
JOERN_IMAGE = os.environ.get(
    "JOERN_IMAGE", "registry.damek-soft.eu/jandamek/jervis-joern:latest"
)
K8S_NAMESPACE = os.environ.get("K8S_NAMESPACE", "jervis")
PVC_NAME = os.environ.get("DATA_PVC_NAME", "jervis-data-pvc")
PVC_MOUNT = os.environ.get("DATA_PVC_MOUNT", "/opt/jervis/data")

# Detect in-cluster vs local
IN_CLUSTER = os.path.exists("/var/run/secrets/kubernetes.io/serviceaccount/token")

# Pre-built query templates for common analysis tasks
QUERY_TEMPLATES = {
    "security": """\
@main def exec(inputPath: String) = {
  importCode(inputPath)
  val findings = scala.collection.mutable.ListBuffer[String]()

  // SQL injection: string concatenation in SQL-like calls
  cpg.call.name(".*(?i)(query|execute|prepare).*")
    .argument.isCallTo("<operator>.addition")
    .l.foreach { c =>
      findings += s"Potential SQL injection at ${c.location.filename}:${c.location.lineNumber}"
    }

  // Command injection: shell exec with dynamic input
  cpg.call.name(".*(?i)(exec|system|popen|runtime\\\\.exec).*")
    .l.foreach { c =>
      findings += s"Potential command injection at ${c.location.filename}:${c.location.lineNumber}"
    }

  // Hardcoded secrets: assignments with password/secret/key in name
  cpg.assignment.target
    .isIdentifier.name(".*(?i)(password|secret|api_?key|token).*")
    .l.foreach { i =>
      findings += s"Possible hardcoded secret '${i.name}' at ${i.location.filename}:${i.location.lineNumber}"
    }

  println(findings.mkString("\\n"))
}
""",
    "dataflow": """\
@main def exec(inputPath: String) = {
  importCode(inputPath)
  val findings = scala.collection.mutable.ListBuffer[String]()

  // Identify HTTP request parameters (sources)
  val sources = cpg.parameter
    .name(".*(?i)(request|req|input|param|body|query).*")
    .l

  // Identify sensitive sinks
  val sinks = cpg.call
    .name(".*(?i)(query|execute|write|send|log|print).*")
    .l

  findings += s"Sources (HTTP inputs): ${sources.size}"
  findings += s"Sinks (sensitive operations): ${sinks.size}"

  sources.take(20).foreach { s =>
    findings += s"  Source: ${s.name} at ${s.location.filename}:${s.location.lineNumber}"
  }
  sinks.take(20).foreach { s =>
    findings += s"  Sink: ${s.name} at ${s.location.filename}:${s.location.lineNumber}"
  }

  println(findings.mkString("\\n"))
}
""",
    "callgraph": """\
@main def exec(inputPath: String) = {
  importCode(inputPath)
  val findings = scala.collection.mutable.ListBuffer[String]()

  // Method summary
  val methods = cpg.method.nameNot("<.*>").l
  findings += s"Total methods: ${methods.size}"

  // Find methods with highest fan-out (most callees)
  methods.sortBy(-_.callOut.size).take(20).foreach { m =>
    val callees = m.callOut.callee.name.l.distinct
    if (callees.nonEmpty) {
      findings += s"${m.fullName} (${m.location.filename}:${m.location.lineNumber}) -> calls ${callees.size} methods: ${callees.take(5).mkString(", ")}${if (callees.size > 5) "..." else ""}"
    }
  }

  // Find uncalled methods (potentially dead code)
  val calledMethods = cpg.call.callee.fullName.toSet
  val uncalled = methods.filterNot(m => calledMethods.contains(m.fullName))
    .filterNot(_.name.matches(".*(?i)(main|init|constructor|test).*"))
  findings += s"Potentially uncalled methods: ${uncalled.size}"
  uncalled.take(10).foreach { m =>
    findings += s"  Dead? ${m.fullName} at ${m.location.filename}:${m.location.lineNumber}"
  }

  println(findings.mkString("\\n"))
}
""",
    "complexity": """\
@main def exec(inputPath: String) = {
  importCode(inputPath)
  val findings = scala.collection.mutable.ListBuffer[String]()

  // Methods sorted by cyclomatic complexity (approximated by control structures)
  cpg.method.nameNot("<.*>").l
    .map { m =>
      val controlNodes = m.ast.isControlStructure.size
      (m, controlNodes)
    }
    .sortBy(-_._2)
    .take(20)
    .foreach { case (m, complexity) =>
      if (complexity > 0) {
        findings += s"Complexity ${complexity}: ${m.fullName} at ${m.location.filename}:${m.location.lineNumber} (${m.lineNumberEnd.getOrElse(0).asInstanceOf[Int] - m.lineNumber.getOrElse(0).asInstanceOf[Int]} lines)"
      }
    }

  // Long methods (>50 lines)
  cpg.method.nameNot("<.*>").l
    .filter { m =>
      val lines = m.lineNumberEnd.getOrElse(0).asInstanceOf[Int] - m.lineNumber.getOrElse(0).asInstanceOf[Int]
      lines > 50
    }
    .sortBy(m => -(m.lineNumberEnd.getOrElse(0).asInstanceOf[Int] - m.lineNumber.getOrElse(0).asInstanceOf[Int]))
    .take(10)
    .foreach { m =>
      val lines = m.lineNumberEnd.getOrElse(0).asInstanceOf[Int] - m.lineNumber.getOrElse(0).asInstanceOf[Int]
      findings += s"Long method ({lines} lines): ${m.fullName} at ${m.location.filename}:${m.location.lineNumber}"
    }

  println(findings.mkString("\\n"))
}
""",
}


async def _run_k8s_job(workspace: str, query: str, timeout: int = 600) -> dict:
    """Create a K8s Job running Joern and wait for results."""
    try:
        from kubernetes import client, config
        from kubernetes.client.rest import ApiException
    except ImportError:
        raise RuntimeError(
            "kubernetes package not installed. Install with: pip install kubernetes"
        )

    config.load_incluster_config()
    batch_v1 = client.BatchV1Api()

    job_name = f"joern-{uuid.uuid4().hex[:8]}"
    jervis_dir = Path(workspace) / ".jervis"
    jervis_dir.mkdir(parents=True, exist_ok=True)

    query_file = jervis_dir / "joern-query.sc"
    result_file = jervis_dir / "joern-result.json"

    # Write query to PVC
    query_file.write_text(query)

    # Clean up any previous result
    if result_file.exists():
        result_file.unlink()

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
                                client.V1EnvVar(name="WORKSPACE", value=workspace),
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

    # Wait for job completion
    poll_interval = 5
    elapsed = 0

    while elapsed < timeout:
        await asyncio.sleep(poll_interval)
        elapsed += poll_interval

        try:
            status = batch_v1.read_namespaced_job_status(
                name=job_name, namespace=K8S_NAMESPACE
            )
        except ApiException as e:
            logger.warning(f"Failed to read job status: {e}")
            continue

        if status.status.succeeded and status.status.succeeded > 0:
            logger.info(f"Joern Job {job_name} completed successfully")
            break

        if status.status.failed and status.status.failed > 0:
            # Try to read partial results anyway
            break
    else:
        # Clean up on timeout
        try:
            batch_v1.delete_namespaced_job(
                name=job_name,
                namespace=K8S_NAMESPACE,
                body=client.V1DeleteOptions(propagation_policy="Background"),
            )
        except Exception:
            pass
        return {
            "stdout": "",
            "stderr": f"Joern Job {job_name} timed out after {timeout}s",
            "exitCode": -1,
        }

    # Read result from PVC
    if result_file.exists():
        data = json.loads(result_file.read_text())
        # Clean up
        query_file.unlink(missing_ok=True)
        return data

    return {
        "stdout": "",
        "stderr": "Joern Job completed but no result file found",
        "exitCode": 1,
    }


async def _run_local(workspace: str, query: str) -> dict:
    """Run Joern locally via subprocess (for development)."""
    joern_home = os.getenv("JOERN_HOME", "")
    joern_bin = (
        os.path.join(joern_home, "joern-cli", "joern") if joern_home else "joern"
    )

    jervis_dir = Path(workspace) / ".jervis"
    jervis_dir.mkdir(parents=True, exist_ok=True)
    query_file = jervis_dir / "joern-query.sc"
    query_file.write_text(query)

    cmd = [joern_bin, "--script", str(query_file), "--param", f"inputPath={workspace}"]
    logger.info(f"Running Joern locally: {' '.join(cmd)}")

    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout_bytes, stderr_bytes = await proc.communicate()

    query_file.unlink(missing_ok=True)

    return {
        "stdout": stdout_bytes.decode("utf-8", errors="replace"),
        "stderr": stderr_bytes.decode("utf-8", errors="replace"),
        "exitCode": proc.returncode or 0,
    }


async def _execute_joern(query: str, workspace: str | None = None) -> dict:
    """Execute a Joern query, auto-selecting K8s or local mode."""
    ws = workspace or WORKSPACE
    if not ws:
        return {
            "stdout": "",
            "stderr": "WORKSPACE env var not set and no workspace provided",
            "exitCode": 1,
        }

    if IN_CLUSTER:
        return await _run_k8s_job(ws, query)
    else:
        return await _run_local(ws, query)


@app.tool()
async def joern_analyze(query_script: str, workspace: str | None = None) -> str:
    """Run a custom Joern query script against the project codebase.

    The script receives `inputPath` as a parameter pointing to the project root.
    Use Joern's CPG query DSL (Scala-based).

    Example:
        @main def exec(inputPath: String) = {
          importCode(inputPath)
          cpg.method.name.l.foreach(println)
        }

    Args:
        query_script: Complete Joern query script content (Scala)
        workspace: Project directory path (defaults to WORKSPACE env var)
    """
    result = await _execute_joern(query_script, workspace)

    parts = []
    if result["stdout"]:
        parts.append(f"=== OUTPUT ===\n{result['stdout']}")
    if result["stderr"]:
        parts.append(f"=== ERRORS ===\n{result['stderr']}")
    parts.append(f"Exit code: {result['exitCode']}")

    return "\n\n".join(parts)


@app.tool()
async def joern_quick_scan(
    scan_type: str = "security",
    workspace: str | None = None,
) -> str:
    """Run a pre-built Joern analysis scan against the project codebase.

    Available scan types:
    - "security": Find SQL injection, command injection, hardcoded secrets
    - "dataflow": Identify HTTP input sources and sensitive sinks
    - "callgraph": Method fan-out analysis and dead code detection
    - "complexity": Cyclomatic complexity and long method detection

    Args:
        scan_type: Type of scan to run
        workspace: Project directory path (defaults to WORKSPACE env var)
    """
    if scan_type not in QUERY_TEMPLATES:
        available = ", ".join(QUERY_TEMPLATES.keys())
        return f"Unknown scan type '{scan_type}'. Available: {available}"

    query = QUERY_TEMPLATES[scan_type]
    result = await _execute_joern(query, workspace)

    parts = [f"=== Joern {scan_type.upper()} Scan ==="]
    if result["stdout"]:
        parts.append(result["stdout"])
    if result["stderr"]:
        parts.append(f"Warnings:\n{result['stderr']}")
    if result["exitCode"] != 0:
        parts.append(f"(Exit code: {result['exitCode']})")

    return "\n\n".join(parts)


@app.tool()
async def joern_read_result(workspace: str | None = None) -> str:
    """Read the latest Joern analysis result from the project workspace.

    Useful when a Joern Job has already completed (e.g., triggered by
    the Knowledge Base service) and you want to inspect its output.

    Args:
        workspace: Project directory path (defaults to WORKSPACE env var)
    """
    ws = workspace or WORKSPACE
    if not ws:
        return "WORKSPACE env var not set and no workspace provided."

    result_file = Path(ws) / ".jervis" / "joern-result.json"
    if not result_file.exists():
        return "No Joern result file found. Run a scan first with joern_quick_scan or joern_analyze."

    data = json.loads(result_file.read_text())

    parts = []
    if data.get("stdout"):
        parts.append(f"=== OUTPUT ===\n{data['stdout']}")
    if data.get("stderr"):
        parts.append(f"=== ERRORS ===\n{data['stderr']}")
    parts.append(f"Exit code: {data.get('exitCode', '?')}")

    return "\n\n".join(parts)


@app.tool()
async def joern_list_scans() -> str:
    """List available pre-built Joern scan types and their descriptions."""
    descriptions = {
        "security": "SQL injection, command injection, hardcoded secrets detection",
        "dataflow": "HTTP input sources and sensitive sink identification",
        "callgraph": "Method fan-out analysis, dead code detection",
        "complexity": "Cyclomatic complexity measurement, long method detection",
    }
    lines = ["Available Joern scan types:"]
    for name, desc in descriptions.items():
        lines.append(f"  - {name}: {desc}")
    lines.append(
        "\nUse joern_quick_scan(scan_type='...') to run, "
        "or joern_analyze(query_script='...') for custom queries."
    )
    return "\n".join(lines)


# Entry point
async def main():
    async with stdio_server() as (read_stream, write_stream):
        await app.run(read_stream, write_stream)


if __name__ == "__main__":
    asyncio.run(main())
