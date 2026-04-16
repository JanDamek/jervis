#!/usr/bin/env python3
"""Claude companion runner — runs inside jervis-claude K8s Job.

Two modes (selected by env COMPANION_MODE):

* **adhoc**  — single query() pass over brief.md + context. Writes result.json
               with a free-form `summary` (markdown text).

* **session** — persistent ClaudeSDKClient. Tail `.jervis/inbox/events.jsonl`
                and feed each new event to the live session. Stream assistant
                output into `.jervis/outbox/events.jsonl`. Terminate on
                `.jervis/END` marker or SIGTERM.

Required env vars: WORKSPACE, SESSION_ID, COMPANION_MODE
Optional: TASK_ID, CLIENT_ID, PROJECT_ID, CLAUDE_CODE_OAUTH_TOKEN, ANTHROPIC_API_KEY
"""

from __future__ import annotations

import asyncio
import datetime
import json
import os
import signal
import sys
import traceback
from pathlib import Path


def _now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def _event_is_stale(event: dict, max_age_seconds: float) -> bool:
    ts = event.get("ts")
    if not ts:
        return False
    try:
        t = datetime.datetime.fromisoformat(ts)
    except ValueError:
        return False
    if t.tzinfo is None:
        t = t.replace(tzinfo=datetime.timezone.utc)
    age = (datetime.datetime.now(datetime.timezone.utc) - t).total_seconds()
    return age > max_age_seconds


def _log(msg: str) -> None:
    print(f"[companion] {msg}", flush=True)


def _load_system_prompt(workspace: Path) -> str:
    parts: list[str] = []
    claude_md = workspace / "CLAUDE.md"
    brief = workspace / ".jervis" / "brief.md"
    ctx = workspace / ".jervis" / "context.json"
    if claude_md.exists():
        parts.append(claude_md.read_text(encoding="utf-8"))
    if brief.exists():
        parts.append("\n\n## Brief\n" + brief.read_text(encoding="utf-8"))
    if ctx.exists():
        parts.append("\n\n## Context\n```json\n" + ctx.read_text(encoding="utf-8") + "\n```")
    return "\n".join(parts)


def _load_mcp_servers(workspace: Path) -> dict:
    mcp_json = workspace / ".claude" / "mcp.json"
    if not mcp_json.exists():
        return {}
    try:
        data = json.loads(mcp_json.read_text(encoding="utf-8"))
        return data.get("mcpServers", {})
    except Exception as e:
        _log(f"MCP config parse error: {e}")
        return {}


def _write_adhoc_result(workspace: Path, task_id: str, success: bool, summary: str) -> None:
    result = workspace / ".jervis" / "result.json"
    result.parent.mkdir(parents=True, exist_ok=True)
    result.write_text(json.dumps({
        "taskId": task_id,
        "success": success,
        "summary": summary,
        "agentType": "companion",
        "timestamp": _now_iso(),
    }, indent=2, ensure_ascii=False), encoding="utf-8")


def _append_outbox(workspace: Path, event_type: str, content: str, final: bool = False) -> None:
    outbox = workspace / ".jervis" / "outbox" / "events.jsonl"
    outbox.parent.mkdir(parents=True, exist_ok=True)
    with outbox.open("a", encoding="utf-8") as f:
        f.write(json.dumps({
            "ts": _now_iso(),
            "type": event_type,
            "content": content,
            "final": final,
        }, ensure_ascii=False) + "\n")


async def run_adhoc(workspace: Path) -> int:
    from claude_agent_sdk import query, ClaudeAgentOptions

    task_id = os.environ.get("TASK_ID", "unknown")
    system_prompt = _load_system_prompt(workspace)
    mcp_servers = _load_mcp_servers(workspace)

    brief = (workspace / ".jervis" / "brief.md").read_text(encoding="utf-8") if (workspace / ".jervis" / "brief.md").exists() else ""
    if not brief.strip():
        _write_adhoc_result(workspace, task_id, False, "Missing .jervis/brief.md")
        return 1

    options = ClaudeAgentOptions(
        cwd=str(workspace),
        allowed_tools=["Read", "Glob", "Grep", "Bash", "WebSearch", "WebFetch"],
        permission_mode="bypassPermissions",
        mcp_servers=mcp_servers,
        system_prompt=system_prompt,
        max_turns=80,
        max_buffer_size=64 * 1024 * 1024,
    )

    _log(f"Adhoc task={task_id} brief_len={len(brief)} mcp={list(mcp_servers.keys())}")

    collected_texts: list[str] = []
    success = False
    error_msg: str | None = None

    try:
        async for message in query(prompt=brief, options=options):
            cls_name = type(message).__name__
            msg_type = getattr(message, "type", None) or cls_name
            _log(f"SDK message: class={cls_name} type={msg_type}")
            # Claude Agent SDK emits class-typed messages (AssistantMessage / UserMessage / ResultMessage)
            if cls_name == "AssistantMessage" or msg_type in ("assistant", "AssistantMessage"):
                for block in getattr(message, "content", []) or []:
                    text = getattr(block, "text", None)
                    if text:
                        collected_texts.append(text)
                        print(text, flush=True)
            elif cls_name == "ResultMessage" or msg_type in ("result", "ResultMessage"):
                subtype = getattr(message, "subtype", "unknown")
                usage = getattr(message, "usage", None)
                total_cost = getattr(message, "total_cost_usd", None)
                num_turns = getattr(message, "num_turns", None)
                is_err = getattr(message, "is_error", None)
                result_text = getattr(message, "result", None)
                _log(f"Result detail: subtype={subtype} is_error={is_err} turns={num_turns} cost={total_cost} usage={usage} result={str(result_text)[:400]}")
                success = subtype == "success" and not is_err
                if not success:
                    error_msg = f"Agent finished: subtype={subtype} is_error={is_err} result={str(result_text)[:300]}"
        if not error_msg and not success:
            success = True
    except Exception as e:
        error_msg = f"{type(e).__name__}: {e}\n{traceback.format_exc()[-500:]}"
        _log(f"SDK error: {error_msg}")

    text_summary = "\n\n".join(t for t in collected_texts if t).strip()
    # Fail-fast: success without any assistant text is a silent failure.
    if success and not text_summary:
        success = False
        error_msg = error_msg or "Companion produced no assistant text (empty response)."

    summary = text_summary or error_msg or "(no output)"
    _write_adhoc_result(workspace, task_id, success, summary)
    return 0 if success else 1


async def run_session(workspace: Path) -> int:
    """Persistent ClaudeSDKClient fed through inbox; streams into outbox."""
    from claude_agent_sdk import ClaudeSDKClient, ClaudeAgentOptions

    session_id = os.environ.get("SESSION_ID", "unknown")
    system_prompt = _load_system_prompt(workspace)
    mcp_servers = _load_mcp_servers(workspace)

    inbox = workspace / ".jervis" / "inbox" / "events.jsonl"
    end_marker = workspace / ".jervis" / "END"
    ready_marker = workspace / ".jervis" / "READY"
    inbox.parent.mkdir(parents=True, exist_ok=True)
    inbox.touch(exist_ok=True)

    options = ClaudeAgentOptions(
        cwd=str(workspace),
        allowed_tools=["Read", "Glob", "Grep", "Bash", "WebSearch", "WebFetch"],
        permission_mode="bypassPermissions",
        mcp_servers=mcp_servers,
        system_prompt=system_prompt,
        max_turns=1000,
        max_buffer_size=64 * 1024 * 1024,
    )

    stop_flag = asyncio.Event()

    def _handle_sigterm(*_args):
        _log("SIGTERM received — stopping session")
        stop_flag.set()

    signal.signal(signal.SIGTERM, _handle_sigterm)
    signal.signal(signal.SIGINT, _handle_sigterm)

    poll_interval = float(os.environ.get("COMPANION_INBOX_POLL_INTERVAL", "0.5"))

    async with ClaudeSDKClient(options=options) as sdk:
        ready_marker.write_text(_now_iso())
        _log(f"Session {session_id} READY (mcp={list(mcp_servers.keys())})")
        _append_outbox(workspace, "note", f"Session {session_id} ready.", final=False)

        with inbox.open("r", encoding="utf-8") as f:
            f.seek(0, 2)  # tail from EOF — orchestrator appends fresh events
            while not stop_flag.is_set():
                if end_marker.exists():
                    _log("END marker found — shutting down")
                    break

                line = f.readline()
                if not line:
                    await asyncio.sleep(poll_interval)
                    continue

                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    _log(f"Malformed inbox line: {line[:200]}")
                    continue

                # Drop stale inbox events — assistant hints must be fresh.
                ttl = float(os.environ.get("COMPANION_INBOX_MAX_AGE_SECONDS", "30"))
                if ttl > 0 and _event_is_stale(event, ttl):
                    _log(f"Dropping stale inbox event (age>{ttl}s)")
                    continue

                await _process_inbox_event(sdk, workspace, event)

        _append_outbox(workspace, "note", "Session ended.", final=True)
    return 0


async def _process_inbox_event(sdk, workspace: Path, event: dict) -> None:
    """Feed one inbox event to Claude; stream text output into outbox."""
    etype = event.get("type", "user")
    content = event.get("content", "")
    meta = event.get("meta", {}) or {}

    if not content:
        return

    framing = {
        "user": "User says:\n",
        "meeting": f"Meeting transcript chunk (speaker={meta.get('speaker', '?')}):\n",
        "system": "Orchestrator hint:\n",
    }.get(etype, "Event:\n")

    prompt = framing + content

    try:
        await sdk.query(prompt)
        collected: list[str] = []
        async for message in sdk.receive_response():
            msg_type = getattr(message, "type", None)
            if msg_type == "assistant":
                for block in getattr(message, "content", []):
                    if hasattr(block, "text") and block.text:
                        collected.append(block.text)
                        print(block.text, flush=True)
            elif msg_type == "result":
                break

        text = "\n".join(collected).strip()
        if text:
            out_type = "answer" if etype == "user" else "suggestion"
            _append_outbox(workspace, out_type, text, final=True)
    except Exception as e:
        # Fail-fast: surface the failure to the orchestrator via outbox,
        # then let the exception terminate the session Job. A silent retry
        # would hide auth/budget/rate issues and keep producing bad hints.
        _log(f"Session event error: {type(e).__name__}: {e}\n{traceback.format_exc()[-400:]}")
        _append_outbox(workspace, "note", f"Session error: {type(e).__name__}: {e}", final=True)
        raise


async def main() -> int:
    workspace = Path(os.environ["WORKSPACE"])
    mode = os.environ.get("COMPANION_MODE", "adhoc").lower()
    if mode == "session":
        return await run_session(workspace)
    return await run_adhoc(workspace)


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
