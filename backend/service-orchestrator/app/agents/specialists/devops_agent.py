"""DevOps Agent -- CI/CD, Docker, K8s operations, and deployment.

Handles infrastructure and deployment tasks including CI/CD pipeline
management, Docker operations, Kubernetes cluster management, and
deployment orchestration.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import (
    TOOL_EXECUTE_COMMAND,
    TOOL_READ_FILE,
    TOOL_LIST_FILES,
)

logger = logging.getLogger(__name__)


TOOL_K8S_STATUS: dict = {
    "type": "function",
    "function": {
        "name": "k8s_status",
        "description": (
            "Get the status of Kubernetes resources in the project namespace. "
            "Returns pod status, deployment replicas, service endpoints, "
            "and recent events."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "resource_type": {
                    "type": "string",
                    "enum": ["pods", "deployments", "services", "jobs", "configmaps", "all"],
                    "description": "Type of K8s resource to query (default all).",
                    "default": "all",
                },
                "namespace": {
                    "type": "string",
                    "description": "Kubernetes namespace (default: project namespace).",
                },
                "label_selector": {
                    "type": "string",
                    "description": "Label selector to filter resources (e.g. app=myapp).",
                },
            },
            "required": [],
        },
    },
}


TOOL_DEPLOY: dict = {
    "type": "function",
    "function": {
        "name": "deploy",
        "description": (
            "Trigger a deployment for the specified service or application. "
            "Supports rolling updates, canary deployments, and rollbacks. "
            "Requires approval for production environments."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "service": {
                    "type": "string",
                    "description": "Service/application name to deploy.",
                },
                "environment": {
                    "type": "string",
                    "enum": ["dev", "staging", "production"],
                    "description": "Target deployment environment.",
                },
                "image_tag": {
                    "type": "string",
                    "description": "Docker image tag to deploy (e.g. v1.2.3, latest, sha-abc123).",
                },
                "strategy": {
                    "type": "string",
                    "enum": ["rolling", "canary", "blue_green", "rollback"],
                    "description": "Deployment strategy (default rolling).",
                    "default": "rolling",
                },
                "dry_run": {
                    "type": "boolean",
                    "description": "If true, only show what would be deployed without applying (default false).",
                    "default": false,
                },
            },
            "required": ["service", "environment"],
        },
    },
}


_DEVOPS_TOOLS: list[dict] = [
    TOOL_EXECUTE_COMMAND,
    TOOL_READ_FILE,
    TOOL_LIST_FILES,
    TOOL_K8S_STATUS,
    TOOL_DEPLOY,
]


class DevOpsAgent(BaseAgent):
    """Specialist agent for DevOps operations.

    Handles CI/CD pipeline management, Docker operations, Kubernetes
    cluster management, and deployment orchestration. Does not
    sub-delegate to other agents.
    """

    name = "devops"
    description = (
        "Handles CI/CD, Docker, Kubernetes operations, and deployment. "
        "Can check cluster status, trigger deployments, read configs, "
        "and execute infrastructure commands."
    )
    domains = [DomainType.DEVOPS, DomainType.CODE]
    tools = _DEVOPS_TOOLS
    can_sub_delegate = False

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute DevOps operations.

        Uses the agentic loop with infrastructure tools. Does not
        sub-delegate -- all operations are handled directly.
        """
        logger.info(
            "DevOpsAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        system_prompt = (
            "You are the DevOpsAgent, a specialist in CI/CD, Docker, "
            "Kubernetes, and deployment operations.\n\n"
            "Your capabilities:\n"
            "- Check Kubernetes cluster and resource status\n"
            "- Trigger deployments with various strategies\n"
            "- Read configuration files (Dockerfiles, K8s manifests, CI configs)\n"
            "- List project files to find infrastructure configs\n"
            "- Execute infrastructure commands (kubectl, docker, helm)\n\n"
            "Guidelines:\n"
            "- Always check current status before making changes\n"
            "- Use dry-run mode for production deployments first\n"
            "- Validate configuration files before applying\n"
            "- Report resource status clearly (pods, replicas, health)\n"
            "- Warn about potential downtime or breaking changes\n"
            "- Never expose secrets or credentials in output\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=12,
        )

