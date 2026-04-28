"""Atomic compact writer for jervis-coding-agent.

Writes ``.jervis/compact.md`` via ``tmp + fsync + rename`` so a SIGKILL
mid-write can never produce a half-flushed compact (POSIX guarantee on
``rename(2)``). After the rename lands, appends a single
``compact_checkpoint`` event to ``.jervis/claude-stream.jsonl`` so the
restart parser knows to drop earlier stream history on resume.

Two entrypoints:

  * ``write_compact_atomic`` — programmatic API used during the agent
    run (mid-session compact at 180k tokens).
  * ``emergency_mid_restart_compact`` — invoked by the bash entrypoint
    after a SIGKILL when the stream exceeds 150k token estimate AND no
    valid compact.md exists. It calls Anthropic's API directly with the
    compaction-agent system prompt, then atomically lands the result.
    Retry-forever per project Core Principles (no hard timeout).

The compaction agent system prompt is intentionally a verbatim copy of
``BaseClaudeSessionManager._call_compaction_agent_with_retry`` in
``backend/service-orchestrator/app/sessions/base_session_manager.py`` —
keeping the two narrative styles consistent across the live in-process
session and the per-job restart bootstrap.
"""

from __future__ import annotations

import datetime
import json
import os
import sys
import time
from pathlib import Path


COMPACTION_AGENT_SYSTEM_PROMPT = (
    "You are the compaction agent. Your role is to summarise the "
    "provided conversation transcript into a structured markdown "
    "narrative covering: Recent decisions (top 10), Pending todos "
    "with dependencies, Current state of work, Active relationships "
    "(people / projects / deadlines currently relevant), Knowledge "
    "updates worth preserving long-term. Be concise — no raw chat "
    "turns, no tool call dumps. Output only the markdown."
)


def _utc_now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def write_compact_atomic(
    compact_md: str,
    *,
    workspace_dir: Path,
    tokens_at_checkpoint: int | None = None,
) -> Path:
    """Write ``compact_md`` to ``.jervis/compact.md`` atomically.

    Steps:
      1. Write content to ``compact.md.tmp``.
      2. ``fsync`` the temp file.
      3. ``rename`` to the final path (atomic on POSIX).
      4. Append a ``compact_checkpoint`` JSON line to
         ``claude-stream.jsonl`` and ``fsync`` the stream.

    The restart parser ignores ``compact.md.tmp`` (treated as incomplete
    write); if no final ``compact.md`` exists alongside, it falls back to
    full stream parsing as if no compact landed.
    """
    if not compact_md.strip():
        raise ValueError("write_compact_atomic: compact_md is empty")

    workspace_dir = Path(workspace_dir)
    jervis_dir = workspace_dir / ".jervis"
    jervis_dir.mkdir(parents=True, exist_ok=True)

    final_path = jervis_dir / "compact.md"
    tmp_path = jervis_dir / "compact.md.tmp"
    stream_path = jervis_dir / "claude-stream.jsonl"

    # 1+2: durably persist the temp file before the rename.
    tmp_path.write_text(compact_md, encoding="utf-8")
    with tmp_path.open("rb") as fh:
        os.fsync(fh.fileno())

    # 3: atomic publish. ``Path.replace`` calls ``os.replace`` which
    #    invokes rename(2) — atomic on POSIX, also on the same NFS export
    #    (single dir entry swap).
    tmp_path.replace(final_path)

    # 4: announce checkpoint to the stream so the restart parser drops
    #    earlier history. Best-effort: even if the append fails, the
    #    compact.md is already on disk and the parser falls back to whole
    #    stream parse + compact use (correct, just less efficient).
    checkpoint = {
        "type": "compact_checkpoint",
        "ts": _utc_now_iso(),
        "compact_path": ".jervis/compact.md",
        "tokens_at_checkpoint": int(tokens_at_checkpoint or 0),
    }
    try:
        with stream_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(checkpoint, ensure_ascii=False) + "\n")
            fh.flush()
            os.fsync(fh.fileno())
    except OSError:
        # Don't fail the compact path on a stream-append issue.
        pass

    return final_path


def emergency_mid_restart_compact(workspace_dir: Path) -> Path | None:
    """Called by the bash entrypoint when the stream is over the
    emergency threshold AND no valid compact.md exists yet. Compacts the
    raw stream via Anthropic's API and lands ``.jervis/compact.md``.

    Heuristic threshold + tokens estimate are applied by the caller —
    here we just unconditionally process whatever's on disk.

    Retry-forever for transient API errors (rate limit, connection,
    transient 5xx); exits with the compact written or stays in the loop.
    """
    workspace_dir = Path(workspace_dir)
    stream_path = workspace_dir / ".jervis" / "claude-stream.jsonl"
    if not stream_path.exists():
        return None
    transcript = _stream_to_transcript(stream_path)
    if not transcript.strip():
        return None

    compact_md = _call_compaction_agent_blocking(transcript)
    if not compact_md:
        return None
    # Token estimate ≈ chars / 3 (matches the entrypoint heuristic).
    tokens_estimate = max(1, len(transcript) // 3)
    return write_compact_atomic(
        compact_md,
        workspace_dir=workspace_dir,
        tokens_at_checkpoint=tokens_estimate,
    )


def _stream_to_transcript(stream_path: Path) -> str:
    """Flatten the Claude CLI stream-json events into a plain
    ``### USER`` / ``### ASSISTANT`` markdown transcript suitable for
    the compaction agent. Skips bookkeeping events (system, result,
    compact_checkpoint, unknown)."""
    parts: list[str] = []
    for raw_line in stream_path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(obj, dict):
            continue
        evt_type = obj.get("type")

        if evt_type == "assistant":
            blocks = (obj.get("message") or {}).get("content") or []
            text_parts: list[str] = []
            tool_calls: list[str] = []
            for block in blocks if isinstance(blocks, list) else []:
                if not isinstance(block, dict):
                    continue
                if block.get("type") == "text":
                    txt = (block.get("text") or "").strip()
                    if txt:
                        text_parts.append(txt)
                elif block.get("type") == "tool_use":
                    name = block.get("name") or "unknown"
                    tool_calls.append(name)
            assembled: list[str] = []
            if text_parts:
                assembled.append("\n".join(text_parts))
            if tool_calls:
                assembled.append(f"[tool_use: {', '.join(tool_calls)}]")
            if assembled:
                parts.append("### ASSISTANT\n" + "\n".join(assembled))

        elif evt_type == "user":
            blocks = (obj.get("message") or {}).get("content") or []
            for block in blocks if isinstance(blocks, list) else []:
                if not isinstance(block, dict):
                    continue
                if block.get("type") == "tool_result":
                    content = block.get("content")
                    if isinstance(content, str):
                        snippet = content
                    elif isinstance(content, list):
                        snippet = "\n".join(
                            (b.get("text") or "")
                            for b in content
                            if isinstance(b, dict)
                        )
                    else:
                        snippet = ""
                    snippet = snippet.strip()
                    if len(snippet) > 600:
                        snippet = snippet[:600] + "…"
                    if snippet:
                        parts.append("### TOOL_RESULT\n" + snippet)
                elif block.get("type") == "text":
                    txt = (block.get("text") or "").strip()
                    if txt:
                        parts.append("### USER\n" + txt)

    return "\n\n".join(parts)


def _call_compaction_agent_blocking(history: str) -> str | None:
    """Synchronous Anthropic API call for the entrypoint's emergency
    path. Retry-forever for transient errors. Mirrors the orchestrator's
    `_call_compaction_agent_with_retry` style but blocking / stdlib so
    the bash entrypoint can shell out without an asyncio runtime."""
    if not history.strip():
        return None
    try:
        from anthropic import (
            Anthropic,
            APIConnectionError,
            APIStatusError,
            RateLimitError,
        )
    except ImportError:
        # The container has `claude-agent-sdk` (depends on `anthropic`),
        # so this should always succeed; if for some reason it doesn't,
        # surface a None and let the caller proceed with a fallback
        # bootstrap (raw replay of the stream digest).
        print("[compact_writer] anthropic SDK not available", file=sys.stderr)
        return None

    api_key = os.environ.get("ANTHROPIC_API_KEY") or os.environ.get(
        "CLAUDE_CODE_OAUTH_TOKEN"
    )
    if not api_key:
        print(
            "[compact_writer] no ANTHROPIC_API_KEY / CLAUDE_CODE_OAUTH_TOKEN available — skipping",
            file=sys.stderr,
        )
        return None

    client = Anthropic(api_key=api_key)
    model = os.environ.get("JERVIS_COMPACTION_MODEL") or "claude-sonnet-4-6"

    user_prompt = (
        "Here is the session transcript. Produce the compact narrative.\n\n"
        f"{history}"
    )

    delay = 1.0
    started = time.monotonic()
    warned = False
    while True:
        try:
            resp = client.messages.create(
                model=model,
                max_tokens=8000,
                system=COMPACTION_AGENT_SYSTEM_PROMPT,
                messages=[{"role": "user", "content": user_prompt}],
            )
            blocks = getattr(resp, "content", None) or []
            collected: list[str] = []
            for block in blocks:
                text = getattr(block, "text", None)
                if text:
                    collected.append(text)
            content = "\n".join(collected).strip()
            return content or None
        except (RateLimitError, APIConnectionError, APIStatusError) as exc:
            elapsed = time.monotonic() - started
            if elapsed > 60 and not warned:
                print(
                    f"[compact_writer] retrying compaction agent (>60s elapsed): {exc}",
                    file=sys.stderr,
                )
                warned = True
            time.sleep(min(delay, 30.0))
            delay = min(delay * 2, 30.0)
        except Exception as exc:  # noqa: BLE001 — non-retryable
            print(
                f"[compact_writer] compaction agent failed (non-retryable): {exc}",
                file=sys.stderr,
            )
            return None


# -- CLI entrypoint -----------------------------------------------------


def _main(argv: list[str]) -> int:
    """``python3 -m compact_writer emergency <WORKSPACE>`` — invoked by
    the bash entrypoint when restart parser flags a stream over the
    emergency threshold without a usable compact.md.
    """
    if len(argv) < 3 or argv[1] != "emergency":
        print(
            "usage: compact_writer.py emergency <workspace_dir>",
            file=sys.stderr,
        )
        return 2
    workspace_dir = Path(argv[2])
    out = emergency_mid_restart_compact(workspace_dir)
    if out is None:
        print("[compact_writer] no compact written")
        return 1
    print(f"[compact_writer] wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv))
