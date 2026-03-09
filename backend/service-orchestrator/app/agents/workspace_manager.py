"""Workspace manager – prepares workspace for coding agents.

The codebase is already on the shared PVC (prepared by Kotlin server).
This module adds instructions, KB context, and agent-specific configuration.
"""

from __future__ import annotations

import json
import logging
import subprocess
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
        git_config: dict | None = None,
        guidelines_text: str | None = None,
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
            agent_type: "claude" | "kilo"
            kb_context: Pre-fetched KB context (markdown).
            environment_context: Resolved environment definition (dict).
            git_config: Git commit config from project/client settings.

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
            self._generate_env_files(workspace, environment_context)

        # 4. Agent-specific configuration
        if agent_type == "claude":
            self._setup_claude_workspace(
                workspace, client_id, project_id, kb_context, environment_context,
                guidelines_text=guidelines_text,
                git_config=git_config,
            )

        # 5. Git config (author, committer, GPG signing)
        if git_config:
            self._setup_git_config(workspace, git_config)

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
        guidelines_text: str | None = None,
        git_config: dict | None = None,
    ):
        """Claude Code: HTTP MCP config + CLAUDE.md for runtime KB + environment access."""

        # MCP configuration — single HTTP MCP server with all tools
        claude_dir = workspace / ".claude"
        claude_dir.mkdir(exist_ok=True)

        mcp_server_config: dict = {
            "type": "http",
            "url": f"{settings.mcp_url}/mcp",
        }
        if settings.mcp_api_token:
            mcp_server_config["headers"] = {
                "Authorization": f"Bearer {settings.mcp_api_token}",
            }

        mcp_config = {"mcpServers": {"jervis": mcp_server_config}}
        (claude_dir / "mcp.json").write_text(json.dumps(mcp_config, indent=2))

        # CLAUDE.md with project context and MCP tool descriptions
        claude_md_parts = [
            "# Project Context (auto-generated by Jervis)",
            "",
            "## Instructions",
            "Read `.jervis/instructions.md` for your task.",
            "",
            "## Git Rules",
            "- After making changes, commit them with a clear commit message.",
            "- Push the branch to origin after committing.",
            "- Do NOT merge branches or accept/merge MRs/PRs.",
            "- Do NOT force-push or modify git history (no rebase, amend, reset).",
            "- Report any errors with full details so the orchestrator can diagnose and fix them.",
        ]

        # Inject git config details (author, message format) if available
        if git_config:
            author_name = git_config.get("git_author_name", "")
            author_email = git_config.get("git_author_email", "")
            message_format = git_config.get("git_message_format", "")
            if author_name or author_email:
                author = f"{author_name} <{author_email}>".strip()
                claude_md_parts.append(f"- Git author/committer: {author} (already set in local git config)")
            if message_format:
                claude_md_parts.append(f"- Commit message format: `{message_format}`")
            else:
                claude_md_parts.append("- Write a concise, descriptive commit message summarizing the changes.")

        claude_md_parts.extend([
            "",
            "## Knowledge Base",
            "You have access to the `jervis` MCP server with these tools:",
            "- `kb_search(query, client_id, project_id)` – hybrid search (RAG + graph)",
            "- `kb_search_simple(query)` – quick RAG-only search",
            "- `kb_traverse(start_node)` – graph traversal",
            "- `kb_graph_search(query)` – search graph nodes",
            "- `kb_get_evidence(node_key)` – get supporting chunks",
            "- `kb_resolve_alias(alias)` – resolve entity aliases",
            "- `kb_store(content, kind)` – store findings (use sparingly)",
            "",
            f"Default context: client_id=`{client_id}`, project_id=`{project_id or ''}`",
            "Pass these IDs to KB tools for proper scoping.",
            "",
            "Use KB to look up coding conventions, architecture decisions,",
            "and previous findings before making changes.",
        ])

        if guidelines_text:
            claude_md_parts.extend(
                [
                    "",
                    guidelines_text,
                ]
            )

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

            # Environment workflow instructions
            claude_md_parts.extend(
                [
                    "",
                    "## Environment Workflow",
                    "- Read `.env` file for pre-resolved connection strings",
                    "- Start the project locally — do NOT try to build Docker images",
                    "- Use `source .env` or dotenv to load environment variables before starting",
                    "- Infrastructure services are already running in K8s (managed by Jervis)",
                ]
            )

            # Add environment MCP tool descriptions if namespace is present
            if environment_context.get("namespace"):
                ns = environment_context["namespace"]
                claude_md_parts.extend(
                    [
                        "",
                        "## Environment Tools",
                        f"Environment namespace: `{ns}` — pass this as the `namespace` parameter.",
                        "- `list_namespace_resources(namespace, resource_type)` – list pods/deployments/services",
                        "- `get_pod_logs(namespace, pod_name, tail_lines)` – read pod logs",
                        "- `get_deployment_status(namespace, name)` – deployment health and events",
                        "- `scale_deployment(namespace, name, replicas)` – scale up/down (0-10)",
                        "- `restart_deployment(namespace, name)` – trigger rolling restart",
                        "- `get_namespace_status(namespace)` – overall namespace health",
                        "",
                        "Use `environment_status` to check if infra is ready before starting your project.",
                        "Use `get_pod_logs` to diagnose runtime issues.",
                    ]
                )

        (workspace / "CLAUDE.md").write_text("\n".join(claude_md_parts))

    def _setup_claude_git_workspace(
        self,
        workspace: Path,
        client_id: str,
        project_id: str | None,
    ):
        """Claude Code: CLAUDE.md for git delegation mode (commit/push jobs)."""

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
    def _generate_env_files(workspace: Path, env: dict):
        """Generate .env files from resolved environment context."""
        projects = [c for c in env.get("components", []) if c.get("type") == "PROJECT"]
        for proj in projects:
            env_vars = proj.get("envVars", {})
            if env_vars:
                content = "\n".join(f"{k}={v}" for k, v in sorted(env_vars.items()))
                safe_name = proj["name"].lower().replace(" ", "-")
                (workspace / f".env.{safe_name}").write_text(content + "\n")
        # Single project → also write .env for convenience
        if len(projects) == 1 and projects[0].get("envVars"):
            env_vars = projects[0]["envVars"]
            content = "\n".join(f"{k}={v}" for k, v in sorted(env_vars.items()))
            (workspace / ".env").write_text(content + "\n")

    # Connection string templates per component type (for documentation)
    _CONNECTION_STRINGS: dict[str, list[tuple[str, str]]] = {
        "POSTGRESQL": [
            ("JDBC", "jdbc:postgresql://{host}:5432/jervis"),
            ("URI", "postgresql://postgres:jervis@{host}:5432/jervis"),
        ],
        "MONGODB": [
            ("URI", "mongodb://{host}:27017/jervis"),
        ],
        "REDIS": [
            ("URI", "redis://{host}:6379/0"),
        ],
        "RABBITMQ": [
            ("AMQP", "amqp://guest:guest@{host}:5672/"),
            ("Management", "http://{host}:15672"),
        ],
        "KAFKA": [
            ("Bootstrap", "{host}:9092"),
        ],
        "ELASTICSEARCH": [
            ("HTTP", "http://{host}:9200"),
        ],
        "ORACLE": [
            ("JDBC", "jdbc:oracle:thin:@{host}:1521/FREEPDB1"),
        ],
        "MYSQL": [
            ("JDBC", "jdbc:mysql://{host}:3306/jervis"),
            ("URI", "mysql://root:jervis@{host}:3306/jervis"),
        ],
        "MINIO": [
            ("S3 API", "http://{host}:9000"),
            ("Console", "http://{host}:9001"),
        ],
    }

    _DEFAULT_CREDENTIALS: dict[str, str] = {
        "POSTGRESQL": "user=postgres, password=jervis",
        "MONGODB": "no auth (dev mode)",
        "REDIS": "no auth (dev mode)",
        "RABBITMQ": "user=guest, password=guest",
        "ORACLE": "user=system, password=jervis",
        "MYSQL": "user=root, password=jervis",
        "MINIO": "access_key=jervis, secret_key=jervis123",
    }

    @staticmethod
    def _render_environment_md(env: dict) -> str:
        """Render environment context as markdown for agents."""
        ns = env.get("namespace", "unknown")
        tier = env.get("tier", "DEV")
        state = env.get("state", "UNKNOWN")
        lines = [
            f"## Environment (`{ns}`) — {tier.lower()} — state: {state}",
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

            # Connection strings for infra components
            conn_lines = []
            cred_lines = []
            for c in sorted(infra, key=lambda x: x.get("startOrder", 0)):
                ctype = c.get("type", "")
                host = c.get("host", "")
                templates = WorkspaceManager._CONNECTION_STRINGS.get(ctype, [])
                if templates:
                    for label, tmpl in templates:
                        conn_lines.append(f"- **{c['name']}** ({label}): `{tmpl.format(host=host)}`")
                creds = WorkspaceManager._DEFAULT_CREDENTIALS.get(ctype)
                if creds:
                    cred_lines.append(f"- **{c['name']}** ({ctype}): {creds}")

            if conn_lines:
                lines.append("### Connection Strings")
                lines.extend(conn_lines)
                lines.append("")

            if cred_lines:
                lines.append("### Default Credentials (DEV only)")
                lines.extend(cred_lines)
                lines.append("")

        if projects:
            lines.append("### Projects (your responsibility to build/start for testing)")
            for c in sorted(projects, key=lambda x: x.get("startOrder", 0)):
                env_vars = c.get("envVars", {})
                lines.append(f"{c.get('startOrder', 0)}. **{c['name']}**")
                # Build pipeline info
                source_repo = c.get("sourceRepo")
                source_branch = c.get("sourceBranch")
                dockerfile = c.get("dockerfilePath")
                if source_repo:
                    branch_info = f" (branch: `{source_branch}`)" if source_branch else ""
                    lines.append(f"   - Git: `{source_repo}`{branch_info}")
                if dockerfile:
                    lines.append(f"   - Dockerfile: `{dockerfile}`")
                # Connection ENV vars
                if env_vars:
                    for k, v in sorted(env_vars.items()):
                        lines.append(f"   - `{k}={v}`")
            lines.append("")

            # How to run
            lines.extend([
                "### How to Run",
                "1. Install dependencies (`npm install` / `pip install -r requirements.txt` / `./gradlew build`)",
                "2. Load environment variables: `source .env` or use dotenv library",
                "3. Start the application (`npm start` / `python main.py` / `./gradlew bootRun`)",
                "4. Infrastructure services are accessible via K8s DNS (see connection strings above)",
                "5. Do NOT try to build Docker images — run the project directly",
                "",
            ])

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

    @staticmethod
    def _setup_git_config(workspace: Path, git_config: dict) -> None:
        """Set git config --local for author, committer, and GPG signing.

        Called during workspace preparation so coding agents inherit these settings.
        """
        config_map = {
            "git_author_name": "user.name",
            "git_author_email": "user.email",
            "git_committer_name": "committer.name",
            "git_committer_email": "committer.email",
        }

        for key, git_key in config_map.items():
            value = git_config.get(key)
            if value:
                try:
                    subprocess.run(
                        ["git", "config", "--local", git_key, value],
                        cwd=workspace,
                        check=True,
                        capture_output=True,
                    )
                except subprocess.CalledProcessError as e:
                    logger.warning("Failed to set git config %s: %s", git_key, e.stderr)

        # GPG signing
        if git_config.get("git_gpg_sign"):
            try:
                subprocess.run(
                    ["git", "config", "--local", "commit.gpgsign", "true"],
                    cwd=workspace,
                    check=True,
                    capture_output=True,
                )
                gpg_key_id = git_config.get("git_gpg_key_id")
                if gpg_key_id:
                    subprocess.run(
                        ["git", "config", "--local", "user.signingkey", gpg_key_id],
                        cwd=workspace,
                        check=True,
                        capture_output=True,
                    )
            except subprocess.CalledProcessError as e:
                logger.warning("Failed to set git GPG config: %s", e.stderr)

        logger.info("Git config set for workspace: %s", workspace)

    def prepare_git_workspace(
        self,
        workspace_path: str,
        client_id: str,
        project_id: str | None,
    ):
        """Prepare workspace for git delegation mode (commit/push jobs).

        Overwrites CLAUDE.md with git-specific instructions for commit/push.
        Called from git_operations node before running the git commit/push Job.

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
        for cleanup_file in ["CLAUDE.md"]:
            p = workspace / cleanup_file
            if p.exists():
                p.unlink()

        # Remove generated .env files
        for env_file in workspace.glob(".env*"):
            if env_file.is_file():
                env_file.unlink()

        claude_dir = workspace / ".claude"
        if claude_dir.exists():
            mcp_file = claude_dir / "mcp.json"
            if mcp_file.exists():
                mcp_file.unlink()


# Singleton
workspace_manager = WorkspaceManager()
