"""Workspace manager – prepares workspace for coding agents.

The codebase is already on the shared PVC (prepared by Kotlin server).
This module adds instructions, KB context, and agent-specific configuration.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from app.config import settings

logger = logging.getLogger(__name__)


class WorkspaceManager:
    """Prepares workspace for coding agent Jobs."""

    def __init__(self, data_root: str | None = None):
        self.data_root = Path(data_root or settings.data_root)

    async def prepare_workspace(
        self,
        task_id: str,
        client_id: str,
        project_id: str | None,
        project_path: str,
        instructions: str,
        files: list[str],
        agent_type: str,
        kb_context: str | None = None,
        environment_context: dict | None = None,
    ) -> Path:
        """Add instructions and context to an existing workspace.

        The workspace (codebase) already exists on the shared PVC.
        This method only adds orchestrator-provided metadata.

        Args:
            task_id: Unique task identifier.
            client_id: Client ID for scoping.
            project_id: Project ID (optional).
            project_path: Relative path to project on PVC (e.g. "clients/acme/web-app").
            instructions: What the agent should do.
            files: Specific files to modify.
            agent_type: "aider" | "openhands" | "claude" | "junie"
            kb_context: Pre-fetched KB context (markdown).
            environment_context: Resolved environment definition (dict).

        Returns:
            Absolute path to workspace.
        """
        workspace = self.data_root / project_path
        if not workspace.exists():
            raise FileNotFoundError(
                f"Workspace {workspace} not found on PVC. "
                f"Server should prepare codebase before orchestration."
            )

        # 1. Write instructions and metadata
        jervis_dir = workspace / ".jervis"
        jervis_dir.mkdir(exist_ok=True)

        (jervis_dir / "instructions.md").write_text(instructions)
        (jervis_dir / "task.json").write_text(
            json.dumps(
                {
                    "taskId": task_id,
                    "clientId": client_id,
                    "projectId": project_id,
                    "agentType": agent_type,
                    "files": files,
                },
                indent=2,
            )
        )

        # 2. KB context (pre-fetch – works for ALL agents)
        if kb_context:
            (jervis_dir / "kb-context.md").write_text(kb_context)

        # 3. Environment context (resolved K8s environment definition)
        if environment_context:
            (jervis_dir / "environment.json").write_text(
                json.dumps(environment_context, indent=2)
            )
            (jervis_dir / "environment.md").write_text(
                self._render_environment_md(environment_context)
            )

        # 4. Agent-specific configuration
        if agent_type == "claude":
            self._setup_claude_workspace(
                workspace, client_id, project_id, kb_context, environment_context
            )
        elif agent_type == "aider":
            self._setup_aider_workspace(workspace, files, kb_context)

        logger.info(
            "Workspace prepared: %s (agent=%s, task=%s)",
            workspace,
            agent_type,
            task_id,
        )
        return workspace

    def _setup_claude_workspace(
        self,
        workspace: Path,
        client_id: str,
        project_id: str | None,
        kb_context: str | None,
        environment_context: dict | None = None,
    ):
        """Claude Code: MCP config + CLAUDE.md for runtime KB access."""

        # MCP configuration for runtime KB access
        claude_dir = workspace / ".claude"
        claude_dir.mkdir(exist_ok=True)

        mcp_env = {
            "CLIENT_ID": client_id,
            "PROJECT_ID": project_id or "",
            "KB_URL": settings.knowledgebase_url,
        }
        # Pass group ID to MCP server for KB cross-visibility
        if environment_context and environment_context.get("groupId"):
            mcp_env["GROUP_ID"] = environment_context["groupId"]

        mcp_config = {
            "mcpServers": {
                "jervis-kb": {
                    "command": "python",
                    "args": ["/opt/jervis/mcp/kb-server.py"],
                    "env": mcp_env,
                }
            }
        }

        # Add environment MCP server if environment is provisioned
        if environment_context and environment_context.get("namespace"):
            mcp_config["mcpServers"]["jervis-environment"] = {
                "command": "python",
                "args": ["/opt/jervis/mcp/environment-server.py"],
                "env": {
                    "NAMESPACE": environment_context["namespace"],
                    "SERVER_URL": settings.kotlin_server_url,
                },
            }

        (claude_dir / "mcp.json").write_text(json.dumps(mcp_config, indent=2))

        # CLAUDE.md with project context and MCP tool descriptions
        claude_md_parts = [
            "# Project Context (auto-generated by Jervis)",
            "",
            "## Instructions",
            "Read `.jervis/instructions.md` for your task.",
            "",
            "## FORBIDDEN ACTIONS",
            "- NEVER run git commands (commit, push, branch, checkout, merge, rebase)",
            "- Make the requested code changes and exit.",
            "",
            "## Knowledge Base",
            "You have access to the `jervis-kb` MCP server with these tools:",
            "- `kb_search(query)` – hybrid search (RAG + graph)",
            "- `kb_search_simple(query)` – quick RAG-only search",
            "- `kb_traverse(start_node)` – graph traversal",
            "- `kb_graph_search(query)` – search graph nodes",
            "- `kb_get_evidence(node_key)` – get supporting chunks",
            "- `kb_store(content, kind)` – store findings (use sparingly)",
            "",
            "Use KB to look up coding conventions, architecture decisions,",
            "and previous findings before making changes.",
        ]

        if kb_context:
            claude_md_parts.extend(
                [
                    "",
                    "## Pre-fetched KB Context",
                    kb_context,
                ]
            )

        if environment_context:
            claude_md_parts.extend(
                [
                    "",
                    self._render_environment_md(environment_context),
                ]
            )

            # Add environment MCP tool descriptions if namespace is present
            if environment_context.get("namespace"):
                claude_md_parts.extend(
                    [
                        "",
                        "## Environment Tools",
                        "You have access to the `jervis-environment` MCP server with these tools:",
                        "- `list_namespace_resources(resource_type)` – list pods/deployments/services",
                        "- `get_pod_logs(pod_name, tail_lines)` – read pod logs",
                        "- `get_deployment_status(name)` – deployment health and events",
                        "- `scale_deployment(name, replicas)` – scale up/down (0-10)",
                        "- `restart_deployment(name)` – trigger rolling restart",
                        "- `get_namespace_status()` – overall namespace health",
                        "",
                        "Use these tools to diagnose runtime issues, check service health,",
                        "and manage the environment when fixing bugs.",
                    ]
                )

        (workspace / "CLAUDE.md").write_text("\n".join(claude_md_parts))

    def _setup_claude_git_workspace(
        self,
        workspace: Path,
        client_id: str,
        project_id: str | None,
    ):
        """Claude Code: CLAUDE.md for GIT DELEGATION mode (ALLOW_GIT=true)."""

        claude_md = "\n".join(
            [
                "# Git Delegation Mode (auto-generated by Jervis)",
                "",
                "## ALLOWED GIT ACTIONS",
                "- You may run git commands as specified in the instructions.",
                "- Follow the instructions exactly – do not add extra git operations.",
                "",
                "## Instructions",
                "Read `.jervis/instructions.md` for your task.",
            ]
        )
        (workspace / "CLAUDE.md").write_text(claude_md)

    @staticmethod
    def _render_environment_md(env: dict) -> str:
        """Render environment context as markdown for agents."""
        ns = env.get("namespace", "unknown")
        lines = [
            f"## Environment (`{ns}`)",
            "",
        ]

        components = env.get("components", [])
        infra = [c for c in components if c.get("type") != "PROJECT" and c.get("autoStart")]
        projects = [c for c in components if c.get("type") == "PROJECT"]

        if infra:
            lines.append("### Infrastructure (auto-started by server)")
            for c in sorted(infra, key=lambda x: x.get("startOrder", 0)):
                host = c.get("host", "")
                ports = c.get("ports", [])
                port_str = ", ".join(
                    f"{p.get('service', p.get('container'))}:{p.get('name', '')}"
                    for p in ports
                )
                lines.append(f"- **{c['name']}** ({c['type']}): `{host}` [{port_str}]")
            lines.append("")

        if projects:
            lines.append("### Projects (your responsibility to build/start for testing)")
            for c in sorted(projects, key=lambda x: x.get("startOrder", 0)):
                env_vars = c.get("envVars", {})
                lines.append(f"{c.get('startOrder', 0)}. **{c['name']}**")
                if env_vars:
                    for k, v in env_vars.items():
                        lines.append(f"   - `{k}={v}`")
            lines.append("")

        agent_instructions = env.get("agentInstructions")
        if agent_instructions:
            lines.extend([
                "### Environment Instructions",
                agent_instructions,
                "",
            ])

        links = env.get("componentLinks", [])
        if links:
            lines.append("### Component Topology")
            for link in links:
                desc = f" ({link['description']})" if link.get("description") else ""
                lines.append(f"- {link['source']} → {link['target']}{desc}")
            lines.append("")

        return "\n".join(lines)

    def _setup_aider_workspace(
        self,
        workspace: Path,
        files: list[str],
        kb_context: str | None,
    ):
        """Aider: .aider.conf.yml + read-only KB context file."""

        config_lines = ["yes: true"]  # --yes mode

        # Aider --read flag for read-only context files
        if kb_context:
            config_lines.append("read: [.jervis/kb-context.md]")

        (workspace / ".aider.conf.yml").write_text("\n".join(config_lines))

    def prepare_git_workspace(
        self,
        workspace_path: str,
        client_id: str,
        project_id: str | None,
    ):
        """Prepare workspace for git delegation mode (ALLOW_GIT=true).

        Overwrites CLAUDE.md with git-specific instructions that ALLOW git
        operations. Called from git_operations node before running the
        git commit/push Job.

        Args:
            workspace_path: Absolute path to workspace on PVC.
            client_id: Client ID for scoping.
            project_id: Project ID (optional).
        """
        workspace = Path(workspace_path)
        self._setup_claude_git_workspace(workspace, client_id, project_id)
        logger.info("Workspace prepared for git delegation: %s", workspace)

    async def cleanup_workspace(self, project_path: str):
        """Remove .jervis/ files after task completion."""
        workspace = self.data_root / project_path
        jervis_dir = workspace / ".jervis"

        if jervis_dir.exists():
            for f in jervis_dir.iterdir():
                f.unlink()
            jervis_dir.rmdir()
            logger.info("Cleaned up workspace: %s", workspace)

        # Remove generated config files
        for cleanup_file in [".aider.conf.yml", "CLAUDE.md"]:
            p = workspace / cleanup_file
            if p.exists():
                p.unlink()

        claude_dir = workspace / ".claude"
        if claude_dir.exists():
            mcp_file = claude_dir / "mcp.json"
            if mcp_file.exists():
                mcp_file.unlink()


# Singleton
workspace_manager = WorkspaceManager()
