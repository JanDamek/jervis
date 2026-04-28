"""Restart state inspection for jervis-coding-agent.

When K8s restarts the pod (`restartPolicy=OnFailure`, `backoffLimit>0`), the
container re-enters `entrypoint-coding.sh` against the same PVC-backed
workspace. The previous run left two artifacts behind:

  - ``.jervis/claude-stream.jsonl`` — append-only NDJSON written by
    ``claude --output-format stream-json | tee …`` and (for Jervis-specific
    annotations) by the entrypoint itself.
  - ``.jervis/compact.md`` — optional structured narrative produced by a
    mid-session compact agent call (see :mod:`compact_writer`).

This module parses those artifacts and produces a :class:`RestartContext`
the bash entrypoint feeds back to Claude CLI on resume — no naive replay
of the entire prior turn graph, just brief + (compact if any) + a
post-checkpoint history summary + bootstrap hints for incomplete state.

Stream format
=============

The parser accepts the **Claude CLI native ``stream-json`` schema**
(``{"type": "assistant", "message": {"content": [...]}}`` etc.) so the
on-disk format stays consistent with the existing Kotlin observer
(``ClaudeStreamJsonlParser`` in ``backend/server`` — it surfaces these
events into the UI's Background detail panel). On top of that, Jervis
appends a single bespoke event type when a compact lands:

    {"type": "compact_checkpoint",
     "ts": "...", "compact_path": ".jervis/compact.md",
     "tokens_at_checkpoint": 182000}

Anything else is treated as opaque and carried as the "last event" hint.

SIGKILL safety
==============

A pod kill mid-write can leave a partial last line in the JSONL. Each
line is parsed independently; a JSON parse failure on the *terminal*
line is silently skipped — that's the SIGKILL fingerprint. Failures
mid-file (rare) are logged and skipped too.
"""

from __future__ import annotations

import dataclasses
import json
import os
import sys
from pathlib import Path
from typing import Any


# Tools whose pending invocation is risky to silently re-run after restart.
# Read-only tools (Read, Glob, Grep, plus most plain Bash) are idempotent
# and can be re-issued without side-effects; mutating ones must be flagged
# so the resumed agent verifies workspace state before proceeding.
NON_IDEMPOTENT_TOOLS: frozenset[str] = frozenset(
    {"Write", "Edit", "MultiEdit", "NotebookEdit"}
)


@dataclasses.dataclass
class StreamEvent:
    """Generic carrier for one parsed JSONL line."""

    type: str
    raw: dict[str, Any]
    line_no: int

    # Convenience view fields, populated when the event is recognisable.
    text: str | None = None              # assistant text block
    tool_name: str | None = None         # assistant tool_use block
    tool_input: dict[str, Any] | None = None
    tool_use_id: str | None = None
    is_tool_result: bool = False         # user message carrying tool_result
    tool_result_for: str | None = None   # tool_use_id this result answers


@dataclasses.dataclass
class RestartContext:
    """Bootstrap envelope returned to the bash entrypoint."""

    has_prior_run: bool
    bootstrap_text: str
    """Composite stdin payload to feed Claude CLI on resume.
    Always begins with brief.md; optionally followed by compact.md and a
    post-checkpoint history digest + warning section."""

    last_event: dict[str, Any] | None
    incomplete_tool_call: dict[str, Any] | None
    pending_tool_result: dict[str, Any] | None
    stream_byte_size: int
    """Raw byte count of claude-stream.jsonl. Caller uses this to decide
    whether to invoke emergency mid-restart compact (size / 3 ≈ tokens)."""

    has_compact: bool
    """Whether `.jervis/compact.md` exists and is non-empty."""

    skipped_partial_last_line: bool
    """True if the last JSONL line failed to parse — typical SIGKILL trace."""

    def to_json(self) -> dict[str, Any]:
        return {
            "has_prior_run": self.has_prior_run,
            "bootstrap_text": self.bootstrap_text,
            "last_event": self.last_event,
            "incomplete_tool_call": self.incomplete_tool_call,
            "pending_tool_result": self.pending_tool_result,
            "stream_byte_size": self.stream_byte_size,
            "has_compact": self.has_compact,
            "skipped_partial_last_line": self.skipped_partial_last_line,
        }


def parse_restart_state(workspace_dir: Path) -> RestartContext:
    """Inspect ``.jervis/claude-stream.jsonl`` + ``.jervis/compact.md`` and
    build a bootstrap context for resuming the SDK conversation.

    Steps (mirrors PR-C3 spec):

    1. If no claude-stream.jsonl OR file empty → ``has_prior_run=False``,
       bootstrap = brief.md only.
    2. Parse stream line-by-line. Skip last partial line if JSON parse
       fails (SIGKILL mid-write).
    3. Find last ``compact_checkpoint`` event; if present AND compact.md
       exists & non-empty, drop stream events before the checkpoint.
       Otherwise keep the entire (parsed) stream.
    4. Collapse post-checkpoint events into a "completed history" digest.
    5. Inspect last meaningful event: turn-end / incomplete tool_call /
       pending tool_result without follow-up assistant text.
    6. Return :class:`RestartContext`.
    """
    workspace_dir = Path(workspace_dir)
    jervis_dir = workspace_dir / ".jervis"
    stream_path = jervis_dir / "claude-stream.jsonl"
    brief_path = jervis_dir / "brief.md"
    compact_path = jervis_dir / "compact.md"

    brief_text = brief_path.read_text(encoding="utf-8") if brief_path.exists() else ""

    # --- short-circuit: nothing on disk to resume from ------------------
    if not stream_path.exists() or stream_path.stat().st_size == 0:
        return RestartContext(
            has_prior_run=False,
            bootstrap_text=brief_text,
            last_event=None,
            incomplete_tool_call=None,
            pending_tool_result=None,
            stream_byte_size=0,
            has_compact=_compact_exists(compact_path),
            skipped_partial_last_line=False,
        )

    stream_size = stream_path.stat().st_size
    raw_lines = stream_path.read_text(encoding="utf-8", errors="replace").splitlines()
    events: list[StreamEvent] = []
    skipped_partial_last_line = False
    last_checkpoint_idx: int | None = None
    last_checkpoint_evt: dict[str, Any] | None = None

    for idx, line in enumerate(raw_lines):
        stripped = line.strip()
        if not stripped:
            continue
        try:
            obj = json.loads(stripped)
        except json.JSONDecodeError:
            # Tolerate a single corrupt last line (SIGKILL mid-write).
            # Anything mid-file is also tolerated but tracked.
            if idx == len(raw_lines) - 1:
                skipped_partial_last_line = True
            continue
        if not isinstance(obj, dict):
            continue
        evt_type = obj.get("type") or "unknown"

        if evt_type == "compact_checkpoint":
            last_checkpoint_idx = len(events)
            last_checkpoint_evt = obj
            events.append(StreamEvent(type=evt_type, raw=obj, line_no=idx))
            continue

        events.append(_decode_event(evt_type, obj, idx))

    has_compact = _compact_exists(compact_path)
    use_compact = (
        has_compact
        and last_checkpoint_evt is not None
    )

    # 3. Drop pre-checkpoint events when compact.md is the canonical base.
    post_checkpoint = (
        events[last_checkpoint_idx + 1 :]
        if (use_compact and last_checkpoint_idx is not None)
        else events
    )

    # 4. Build a digest of post-checkpoint completed activity.
    history_digest = _summarise_post_checkpoint(post_checkpoint)

    # 5. Detect dangling state.
    incomplete_tool_call, pending_tool_result, last_meaningful = _classify_tail(
        post_checkpoint
    )

    # 6. Compose bootstrap stdin payload.
    bootstrap = _compose_bootstrap(
        brief_text=brief_text,
        compact_md=compact_path.read_text(encoding="utf-8") if use_compact else None,
        history_digest=history_digest,
        incomplete_tool_call=incomplete_tool_call,
        pending_tool_result=pending_tool_result,
    )

    return RestartContext(
        has_prior_run=True,
        bootstrap_text=bootstrap,
        last_event=last_meaningful.raw if last_meaningful else None,
        incomplete_tool_call=incomplete_tool_call,
        pending_tool_result=pending_tool_result,
        stream_byte_size=stream_size,
        has_compact=has_compact,
        skipped_partial_last_line=skipped_partial_last_line,
    )


# -- helpers ------------------------------------------------------------


def _compact_exists(compact_path: Path) -> bool:
    return compact_path.exists() and compact_path.stat().st_size > 0


def _decode_event(evt_type: str, obj: dict[str, Any], line_no: int) -> StreamEvent:
    """Map a Claude CLI native event onto the :class:`StreamEvent` view.

    The Kotlin parser at ``backend/server/.../ClaudeStreamJsonlParser.kt``
    is the canonical reference for the format. Multi-block messages get
    flattened: we only care about the first text / tool_use / tool_result
    block per line for tail classification — the digest pass walks all
    blocks separately.
    """
    evt = StreamEvent(type=evt_type, raw=obj, line_no=line_no)

    if evt_type == "assistant":
        message = obj.get("message") or {}
        blocks = message.get("content") or []
        if isinstance(blocks, list):
            for block in blocks:
                if not isinstance(block, dict):
                    continue
                btype = block.get("type")
                if btype == "text" and evt.text is None:
                    txt = block.get("text") or ""
                    if txt.strip():
                        evt.text = txt
                elif btype == "tool_use" and evt.tool_name is None:
                    evt.tool_name = block.get("name") or "unknown"
                    evt.tool_input = block.get("input") if isinstance(
                        block.get("input"), dict
                    ) else None
                    evt.tool_use_id = block.get("id")

    elif evt_type == "user":
        message = obj.get("message") or {}
        blocks = message.get("content") or []
        if isinstance(blocks, list):
            for block in blocks:
                if not isinstance(block, dict):
                    continue
                if block.get("type") == "tool_result":
                    evt.is_tool_result = True
                    evt.tool_result_for = block.get("tool_use_id")
                    break

    return evt


def _summarise_post_checkpoint(events: list[StreamEvent]) -> str:
    """Compose a short markdown digest of completed activity since the
    last compact checkpoint. Avoids dumping raw tool input/output (Claude
    CLI stream blocks are noisy) — we just enumerate tool calls and
    surface the final assistant text snippet, if any."""
    if not events:
        return ""
    tool_calls: list[str] = []
    last_assistant_text: str | None = None
    for evt in events:
        if evt.tool_name:
            tool_calls.append(evt.tool_name)
        if evt.text:
            last_assistant_text = evt.text
    lines: list[str] = []
    if tool_calls:
        # Cap tool call dump — long sessions can have hundreds.
        capped = tool_calls[-30:]
        suffix = "" if len(tool_calls) <= 30 else f" (showing last 30 of {len(tool_calls)})"
        lines.append("Tool calls since last checkpoint" + suffix + ":")
        for name in capped:
            lines.append(f"  - {name}")
    if last_assistant_text:
        snippet = last_assistant_text.strip()
        if len(snippet) > 800:
            snippet = snippet[:800] + "…"
        lines.append("")
        lines.append("Last assistant message before restart:")
        lines.append(snippet)
    return "\n".join(lines)


def _classify_tail(
    events: list[StreamEvent],
) -> tuple[dict[str, Any] | None, dict[str, Any] | None, StreamEvent | None]:
    """Walk events from the tail. Detect:

      - **incomplete_tool_call**: an assistant ``tool_use`` block that has
        no matching ``tool_result`` afterwards (the pod died after Claude
        emitted the call but before the result came back).
      - **pending_tool_result**: a ``tool_result`` with no follow-up
        assistant text (Claude died while ingesting the result).

    Return the last "meaningful" event (anything carrying text or tool
    metadata) for the entrypoint's log line.
    """
    incomplete_tool_call: dict[str, Any] | None = None
    pending_tool_result: dict[str, Any] | None = None
    last_meaningful: StreamEvent | None = None

    # Walk in chronological order to track outstanding tool_use → tool_result
    # pairs deterministically.
    outstanding: dict[str, StreamEvent] = {}
    last_tool_result: StreamEvent | None = None
    last_assistant_text_after_result: bool = False

    for evt in events:
        if evt.tool_name or evt.text or evt.is_tool_result:
            last_meaningful = evt

        if evt.tool_name and evt.tool_use_id:
            outstanding[evt.tool_use_id] = evt

        if evt.is_tool_result:
            tid = evt.tool_result_for or ""
            if tid in outstanding:
                outstanding.pop(tid, None)
            last_tool_result = evt
            last_assistant_text_after_result = False
        elif evt.text:
            last_assistant_text_after_result = True

    # Any tool_use without a matching result is incomplete.
    if outstanding:
        # Pick the latest one (highest line number) — that's the one
        # Claude was actively waiting on when the pod died.
        latest = max(outstanding.values(), key=lambda e: e.line_no)
        incomplete_tool_call = {
            "tool": latest.tool_name,
            "tool_use_id": latest.tool_use_id,
            "input": latest.tool_input,
            "is_non_idempotent": latest.tool_name in NON_IDEMPOTENT_TOOLS,
            "line_no": latest.line_no,
        }
    elif last_tool_result and not last_assistant_text_after_result:
        # All calls answered, but Claude never produced follow-up text —
        # it died ingesting the last result.
        pending_tool_result = {
            "tool_use_id": last_tool_result.tool_result_for,
            "line_no": last_tool_result.line_no,
        }

    return incomplete_tool_call, pending_tool_result, last_meaningful


def _compose_bootstrap(
    *,
    brief_text: str,
    compact_md: str | None,
    history_digest: str,
    incomplete_tool_call: dict[str, Any] | None,
    pending_tool_result: dict[str, Any] | None,
) -> str:
    """Assemble the resume payload Claude CLI receives on stdin.

    Sections:
      1. Original brief (verbatim).
      2. Compact narrative if available.
      3. History digest of post-checkpoint activity.
      4. Restart warnings (mutating tool not confirmed, pending result, …).
    """
    sections: list[str] = []
    if brief_text.strip():
        sections.append(brief_text.rstrip())

    if compact_md and compact_md.strip():
        sections.append(
            "\n\n"
            "## Resumed-session narrative (from .jervis/compact.md)\n\n"
            f"{compact_md.strip()}"
        )

    if history_digest.strip():
        sections.append(
            "\n\n"
            "## Activity since last compact checkpoint\n\n"
            f"{history_digest.strip()}"
        )

    warnings: list[str] = []
    if incomplete_tool_call:
        tool = incomplete_tool_call.get("tool") or "unknown"
        marker = " (NON-IDEMPOTENT)" if incomplete_tool_call.get("is_non_idempotent") else ""
        args_preview = json.dumps(
            incomplete_tool_call.get("input") or {}, ensure_ascii=False
        )
        if len(args_preview) > 400:
            args_preview = args_preview[:400] + "…"
        warnings.append(
            f"- Tool `{tool}` was called with args `{args_preview}` but its "
            f"result never arrived before the pod restarted{marker}. Verify "
            "the workspace state before re-issuing — if the tool was "
            "Write/Edit/MultiEdit/NotebookEdit, inspect the target file "
            "first to avoid double-applying a change."
        )
    if pending_tool_result:
        warnings.append(
            "- A tool result was received but you never produced a "
            "follow-up message before the pod restarted. Continue from "
            "where you left off based on the activity digest above."
        )
    if warnings:
        sections.append(
            "\n\n## Restart warnings (resume context)\n\n" + "\n".join(warnings)
        )

    return "".join(sections).strip() + "\n"


# -- CLI entrypoint -----------------------------------------------------


def _main(argv: list[str]) -> int:
    """Entrypoint used by ``entrypoint-coding.sh``.

    Usage::

        python3 -m restart_state <WORKSPACE_DIR> <OUT_PATH>

    Writes a JSON envelope to ``OUT_PATH`` and prints a one-line summary
    to stdout for the bash log.
    """
    if len(argv) < 3:
        print("usage: restart_state.py <workspace_dir> <out_path>", file=sys.stderr)
        return 2
    workspace_dir = Path(argv[1])
    out_path = Path(argv[2])

    ctx = parse_restart_state(workspace_dir)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(out_path.suffix + ".tmp")
    tmp.write_text(json.dumps(ctx.to_json(), ensure_ascii=False), encoding="utf-8")
    with tmp.open("rb") as fh:
        os.fsync(fh.fileno())
    tmp.replace(out_path)

    summary_bits = [
        f"has_prior_run={ctx.has_prior_run}",
        f"has_compact={ctx.has_compact}",
        f"stream_bytes={ctx.stream_byte_size}",
        f"incomplete_tool={(ctx.incomplete_tool_call or {}).get('tool')}",
        f"pending_result={'yes' if ctx.pending_tool_result else 'no'}",
        f"partial_last_line={ctx.skipped_partial_last_line}",
    ]
    print(" ".join(summary_bits))
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv))
