"""MCP Server for Jervis K8s Environment inspection.

Provides K8s environment access to coding agents (primarily Claude Code) via MCP protocol.
Runs as stdio process from agent workspace — Claude Code natively supports MCP via .claude/mcp.json.

Tenant context is injected via environment variables:
    NAMESPACE   — K8s namespace to inspect (required)
    SERVER_URL  — Kotlin server internal API URL (default: http://jervis-server:5500)

The MCP server does NOT talk to K8s directly. It calls the Kotlin backend's internal
REST endpoints which perform the actual fabric8 K8s operations. This keeps K8s credentials
and ServiceAccount tokens server-side.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os

import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Server("jervis-environment")

# Environment context
NAMESPACE = os.environ.get("NAMESPACE", "")
SERVER_URL = os.environ.get("SERVER_URL", "http://jervis-server:5500")


def _base_url() -> str:
    return f"{SERVER_URL}/internal/environment/{NAMESPACE}"


@app.tool()
async def list_namespace_resources(resource_type: str = "all") -> str:
    """List resources in the environment namespace.

    Returns pods, deployments, services, and secrets (names only) in the namespace.

    Args:
        resource_type: Filter by type — "all", "pods", "deployments", "services", "secrets"
    """
    if not NAMESPACE:
        return "Error: NAMESPACE not configured"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{_base_url()}/resources",
            params={"type": resource_type},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if not data.get("ok"):
            return f"Error: {data.get('error', 'unknown')}"

        resources = data.get("data", {})
        lines = [f"Namespace: {NAMESPACE}", ""]
        for rtype, items in resources.items():
            lines.append(f"## {rtype.title()} ({len(items)})")
            for item in items:
                name = item.get("name", "?")
                if rtype == "pods":
                    phase = item.get("phase", "?")
                    ready = "READY" if item.get("ready") else "NOT READY"
                    restarts = item.get("restartCount", 0)
                    lines.append(f"  - {name}: {phase} ({ready}, restarts={restarts})")
                elif rtype == "deployments":
                    avail = item.get("availableReplicas", 0)
                    total = item.get("replicas", 0)
                    image = item.get("image", "?")
                    lines.append(f"  - {name}: {avail}/{total} ready ({image})")
                elif rtype == "services":
                    svc_type = item.get("type", "?")
                    ports = item.get("ports", [])
                    lines.append(f"  - {name}: {svc_type} ({', '.join(ports) if ports else 'no ports'})")
                elif rtype == "secrets":
                    keys = item.get("keys", [])
                    lines.append(f"  - {name}: keys={keys}")
                else:
                    lines.append(f"  - {name}")
            lines.append("")
        return "\n".join(lines)


@app.tool()
async def get_pod_logs(pod_name: str, tail_lines: int = 100) -> str:
    """Get recent logs from a pod in the environment namespace.

    Args:
        pod_name: Name of the pod
        tail_lines: Number of recent log lines to return (max 1000)
    """
    if not NAMESPACE:
        return "Error: NAMESPACE not configured"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{_base_url()}/pods/{pod_name}/logs",
            params={"tail": min(tail_lines, 1000)},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        return resp.text


@app.tool()
async def get_deployment_status(name: str) -> str:
    """Get detailed status of a deployment including conditions and recent events.

    Args:
        name: Deployment name
    """
    if not NAMESPACE:
        return "Error: NAMESPACE not configured"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(f"{_base_url()}/deployments/{name}")
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if not data.get("ok"):
            return f"Error: {data.get('error', 'unknown')}"

        d = data.get("data", {})
        lines = [
            f"Deployment: {d.get('name', '?')}",
            f"Namespace: {d.get('namespace', '?')}",
            f"Image: {d.get('image', '?')}",
            f"Replicas: {d.get('availableReplicas', 0)}/{d.get('replicas', 0)} ready",
            f"Status: {'HEALTHY' if d.get('ready') else 'UNHEALTHY'}",
            "",
        ]

        conditions = d.get("conditions", [])
        if conditions:
            lines.append("Conditions:")
            for c in conditions:
                lines.append(f"  - {c.get('type')}: {c.get('status')} ({c.get('reason', '')})")
                if c.get("message"):
                    lines.append(f"    {c['message']}")
            lines.append("")

        events = d.get("events", [])
        if events:
            lines.append("Recent Events:")
            for ev in events:
                lines.append(f"  [{ev.get('type', '?')}] {ev.get('reason', '?')}: {ev.get('message', '')}")
        return "\n".join(lines)


@app.tool()
async def scale_deployment(name: str, replicas: int) -> str:
    """Scale a deployment to the specified number of replicas.

    Args:
        name: Deployment name
        replicas: Target number of replicas (0-10)
    """
    if not NAMESPACE:
        return "Error: NAMESPACE not configured"
    if replicas < 0 or replicas > 10:
        return "Error: replicas must be between 0 and 10"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{_base_url()}/deployments/{name}/scale",
            json={"replicas": replicas},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return data.get("message", "Scaled successfully") if data.get("ok") else f"Error: {data.get('error')}"


@app.tool()
async def restart_deployment(name: str) -> str:
    """Trigger a rolling restart of a deployment.

    This updates the pod template annotation to force a new rollout.

    Args:
        name: Deployment name
    """
    if not NAMESPACE:
        return "Error: NAMESPACE not configured"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(f"{_base_url()}/deployments/{name}/restart")
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return data.get("message", "Restart triggered") if data.get("ok") else f"Error: {data.get('error')}"


@app.tool()
async def get_namespace_status() -> str:
    """Get overall health status of the environment namespace.

    Returns pod counts, deployment readiness, and identifies any crashing pods.
    """
    if not NAMESPACE:
        return "Error: NAMESPACE not configured"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(f"{_base_url()}/status")
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if not data.get("ok"):
            return f"Error: {data.get('error', 'unknown')}"

        s = data.get("data", {})
        healthy = s.get("healthy", False)
        pods = s.get("pods", {})
        deps = s.get("deployments", {})
        svcs = s.get("services", {})

        lines = [
            f"Namespace: {s.get('namespace', '?')}",
            f"Overall: {'HEALTHY' if healthy else 'UNHEALTHY'}",
            "",
            f"Pods: {pods.get('running', 0)}/{pods.get('total', 0)} running",
            f"Deployments: {deps.get('ready', 0)}/{deps.get('total', 0)} ready",
            f"Services: {svcs.get('total', 0)}",
        ]

        crashing = pods.get("crashing", [])
        if crashing:
            lines.extend(["", "CRASHING PODS:"])
            for pod in crashing:
                lines.append(f"  - {pod}")
            lines.append("")
            lines.append("Use get_pod_logs(pod_name) to inspect logs from crashing pods.")

        return "\n".join(lines)


# Entry point
async def main():
    async with stdio_server() as (read_stream, write_stream):
        await app.run(read_stream, write_stream)


if __name__ == "__main__":
    asyncio.run(main())
