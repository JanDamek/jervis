#!/usr/bin/env python3
"""Claude Agent SDK runner for Jervis K8s Jobs.

Replaces direct CLI invocation (`claude --dangerously-skip-permissions`).
Reads workspace files prepared by workspace_manager, runs SDK query(),
and writes result.json for AgentTaskWatcher.

Required env vars: WORKSPACE, TASK_ID
Optional env vars: CLAUDE_CODE_OAUTH_TOKEN, ANTHROPIC_API_KEY
"""

import asyncio
import datetime
import json
import os
import subprocess
import sys
import traceback
from pathlib import Path


async def main():
    from claude_agent_sdk import query, ClaudeAgentOptions

    workspace = os.environ["WORKSPACE"]
    task_id = os.environ["TASK_ID"]
    result_file = Path(workspace) / ".jervis" / "result.json"

    # Read instructions
    instructions_path = Path(workspace) / ".jervis" / "instructions.md"
    if not instructions_path.exists():
        _write_result(result_file, task_id, False, "No instructions found at .jervis/instructions.md", [])
        sys.exit(1)
    instructions = instructions_path.read_text()

    # Read CLAUDE.md as system prompt (workspace_manager already generated it with
    # guidelines, KB context, environment info, forbidden actions, MCP tool docs)
    claude_md_path = Path(workspace) / "CLAUDE.md"
    system_prompt = claude_md_path.read_text() if claude_md_path.exists() else None

    # Build MCP config from .claude/mcp.json (workspace_manager already generated it)
    mcp_servers = {}
    mcp_json_path = Path(workspace) / ".claude" / "mcp.json"
    if mcp_json_path.exists():
        mcp_data = json.loads(mcp_json_path.read_text())
        mcp_servers = mcp_data.get("mcpServers", {})

    # SDK options
    options = ClaudeAgentOptions(
        cwd=workspace,
        allowed_tools=[
            "Read", "Write", "Edit", "Glob", "Grep", "Bash",
            "WebSearch", "WebFetch",
        ],
        permission_mode="bypassPermissions",
        mcp_servers=mcp_servers,
        system_prompt=system_prompt,
        max_turns=100,
    )

    print(f"=== Claude SDK Runner: task={task_id} workspace={workspace} ===")
    print(f"Instructions: {len(instructions)} chars")
    print(f"System prompt: {len(system_prompt) if system_prompt else 0} chars")
    print(f"MCP servers: {list(mcp_servers.keys())}")

    # Run SDK query — stream messages
    last_text = ""
    success = False
    error_msg = None

    try:
        async for message in query(prompt=instructions, options=options):
            msg_type = getattr(message, "type", None)

            if msg_type == "assistant":
                # Capture assistant text for summary
                for block in getattr(message, "content", []):
                    if hasattr(block, "text"):
                        last_text = block.text
                        # Print to stdout for K8s log collection
                        print(block.text)

            elif msg_type == "result":
                # Final result message
                subtype = getattr(message, "subtype", "unknown")
                success = subtype == "success"
                if not success:
                    error_msg = f"Agent finished with: {subtype}"
                print(f"=== Result: {subtype} ===")

        # If we got through without error and no explicit result, assume success
        if not error_msg and not success:
            success = True

    except Exception as e:
        tb = traceback.format_exc()
        error_msg = f"{type(e).__name__}: {e}\n{tb[-500:]}"
        print(f"=== SDK Error: {error_msg} ===", file=sys.stderr)

    # Detect changed files via git
    changed_files = _get_changed_files(workspace)

    # Build summary
    if error_msg:
        summary = error_msg
    elif last_text:
        summary = last_text[:500]
    else:
        summary = "Agent completed successfully." if success else "Agent completed with no output."

    _write_result(result_file, task_id, success, summary, changed_files)

    status = "SUCCESS" if success else "FAILED"
    print(f"=== Claude SDK Runner DONE: {status} | changed={len(changed_files)} files ===")
    sys.exit(0 if success else 1)


def _write_result(result_file, task_id, success, summary, changed_files):
    """Write result.json in the format expected by AgentTaskWatcher."""
    result = {
        "taskId": task_id,
        "success": success,
        "summary": summary,
        "agentType": "claude",
        "changedFiles": changed_files,
        "timestamp": datetime.datetime.now().isoformat(),
    }
    Path(result_file).parent.mkdir(parents=True, exist_ok=True)
    Path(result_file).write_text(json.dumps(result, indent=2))


def _get_changed_files(workspace):
    """Detect changed files via git diff (excludes .jervis/ artifacts)."""
    try:
        out = subprocess.check_output(
            ["git", "diff", "--name-only", "HEAD"],
            cwd=workspace, text=True, stderr=subprocess.DEVNULL,
        )
        staged = subprocess.check_output(
            ["git", "diff", "--name-only", "--cached"],
            cwd=workspace, text=True, stderr=subprocess.DEVNULL,
        )
        untracked = subprocess.check_output(
            ["git", "ls-files", "--others", "--exclude-standard"],
            cwd=workspace, text=True, stderr=subprocess.DEVNULL,
        )
        files = set(filter(None, (out + staged + untracked).splitlines()))
        return sorted(f for f in files if not f.startswith(".jervis/"))
    except Exception:
        return []


if __name__ == "__main__":
    asyncio.run(main())
