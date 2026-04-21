"""LangChain tools for the pod agent — generic primitives only.

Per `docs/teams-pod-agent.md` §5 + §15 and
`docs/teams-pod-agent-langgraph.md` §17, the pod agent drives everything
through small, semantically-neutral tools. No compound "scrape_chat"
walker, no hardcoded semantic extractors, no regex. The agent composes
higher-level flows from primitives every turn.

Tool surface (~25 tools):

OBSERVATION
  inspect_dom(selector, attrs, text, max_matches, tab_name)
  look_at_screen(reason, ask, tab_name)

NAVIGATION
  list_tabs / open_tab / switch_tab / close_tab / navigate
  report_capabilities

ACTIONS
  click (CSS selector) / click_visual (NL description)
  fill (CSS selector) / fill_visual (NL description)
  fill_credentials (selector, field)  — LLM never sees the secret
  press / wait

STATE & NOTIFICATIONS
  report_state / notify_user
  query_user_activity / is_work_hours

STORAGE PRIMITIVES
  store_chat_row / store_message / store_discovered_resource
  store_calendar_event / store_mail_header / mark_seen

MEETING
  meeting_presence_report(present, meeting_stage_visible)
  start_meeting_recording / stop_meeting_recording / leave_meeting

CONTROL
  done / error

Dependencies (Playwright context, tab registry, storage, meeting recorder,
credentials) are resolved via `contextvars.ContextVar` (app.agent.context).
Tool signatures carry only semantic arguments.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Annotated, Literal

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import tool
from langgraph.prebuilt import InjectedState

from app.agent import _dom_query, work_hours
from app.agent.context import get_pod_context
from app.grpc_clients import router_inference_stub
from app.pod_state import PodState
from jervis.common import enums_pb2, types_pb2
from jervis.router import inference_pb2
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger("o365-browser-pool.tools")


def _utcnow_iso() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


# ---- Observation --------------------------------------------------------

@tool
async def inspect_dom(
    selector: str,
    attrs: list[str] | None = None,
    text: bool = True,
    max_matches: int = 200,
    tab_name: str = "",
) -> dict:
    """Scoped CSS query over the current DOM with shadow-root + same-origin
    iframe pierce. Generic — NO semantic interpretation.

    Returns:
        {matches: [{text, attrs, bbox}], count, url, truncated}

    Use for fast (~50–200 ms) verification of a known field. When count=0
    or the shape is unexpected, escalate to `look_at_screen` — do not
    guess another selector.

    Args:
        selector: CSS selector. Pierces shadow-DOM boundaries automatically.
        attrs: Per-match attribute names to return (e.g. ['data-tid',
            'aria-label']). Empty = no attrs.
        text: When True, include trimmed text (aria-label → innerText →
            textContent) per match.
        max_matches: Hard cap (≤500). `truncated` flags when hit.
        tab_name: Named tab. Empty = current first page.
    """
    ctx = get_pod_context()
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"matches": [], "count": 0, "url": "", "truncated": False,
                "error": f"no page for tab_name={tab_name!r}"}
    result = await _dom_query.query(
        page, selector=selector, attrs=attrs or [],
        text=text, max_matches=max_matches,
    )
    if ctx.last_dom_signature != result["url"]:
        ctx.last_dom_delta_ts = time.time()
    ctx.last_dom_signature = result["url"]
    ctx.last_observation_kind = "dom"
    ctx.last_observation_at = _utcnow_iso()
    return result


@tool
async def look_at_screen(
    reason: str,
    ask: str = "",
    tab_name: str = "",
) -> dict:
    """VLM observation via router. Use for unknown state (cold start, post-
    navigate, post-error, ACTIVE heartbeat) OR when inspect_dom returns
    count=0 for a selector the agent expected to match.

    Returns:
        {app_state, summary, visible_actions, detected_text}

        app_state ∈ {login, mfa, chat_list, conversation, meeting_stage,
                     loading, unknown}

    Args:
        reason: Short tag ("cold_start", "mfa_code", "post_action_verify",
            "heartbeat", "dom_empty").
        ask: Optional focused question (e.g. "return the 2-digit number
            shown on the page"). When provided, the `detected_text` field
            carries the focused answer.
        tab_name: Named tab. Empty = current first page.
    """
    ctx = get_pod_context()
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"app_state": "unknown", "summary": f"no page for tab_name={tab_name!r}",
                "visible_actions": [], "detected_text": {}}
    try:
        shot = await page.screenshot(type="jpeg", quality=70, full_page=False)
    except Exception as e:
        return {"app_state": "unknown", "summary": f"screenshot failed: {e}",
                "visible_actions": [], "detected_text": {}}

    # `/no_think` disables qwen3-vl chain-of-thought — the model emits the
    # JSON answer directly instead of streaming paragraphs of reasoning.
    # Without it we paid 1000+ chunks of "Let's look at the image..." and
    # never got a parseable JSON object.
    prompt = (
        "/no_think Output ONLY a single JSON object. No prose, no "
        "reasoning, no code fences. Shape:\n"
        '{"app_state":"login|mfa|chat_list|conversation|meeting_stage|loading|unknown",'
        '"summary":"<short sentence in English>",'
        '"visible_actions":[{"label":"<button text>","bbox":{"x":0,"y":0,"w":0,"h":0}}],'
        '"detected_text":{}}\n'
        "Describe this Microsoft 365 web screen."
        + (f"\nFocused question: {ask}\nPut the focused answer into detected_text." if ask else "")
    )

    req_ctx = types_pb2.RequestContext(
        scope=types_pb2.Scope(client_id=ctx.client_id),
        priority=enums_pb2.PRIORITY_BACKGROUND,
        capability=enums_pb2.CAPABILITY_VISUAL,
        intent="o365-pod-vlm-observe",
    )
    prepare_context(req_ctx)
    request = inference_pb2.GenerateRequest(
        ctx=req_ctx,
        model_hint="qwen3-vl-tool:latest",
        prompt=prompt,
        images=[shot],
        options=inference_pb2.ChatOptions(temperature=0.0, num_predict=1024),
    )

    body_parts: list[str] = []
    chunk_count = 0
    try:
        async for chunk in router_inference_stub().Generate(request):
            chunk_count += 1
            if chunk.response_delta:
                body_parts.append(chunk.response_delta)
    except Exception as e:
        return {"app_state": "unknown", "summary": f"vlm call failed: {e}",
                "visible_actions": [], "detected_text": {}}

    body = "".join(body_parts)
    logger.info(
        "look_at_screen: chunks=%d body_len=%d preview=%r",
        chunk_count, len(body), body[:300],
    )
    parsed = _try_parse_vlm_json(body)
    ctx.last_observation_kind = "vlm"
    ctx.last_observation_at = _utcnow_iso()
    ctx.last_app_state = parsed.get("app_state", "unknown")
    return parsed


def _try_parse_vlm_json(body: str) -> dict:
    out = {"app_state": "unknown", "summary": body.strip()[:500],
           "visible_actions": [], "detected_text": {}}
    if not body:
        return out
    stripped = body.strip()
    if stripped.startswith("```"):
        lines = [l for l in stripped.splitlines() if not l.strip().startswith("```")]
        stripped = "\n".join(lines)
    start = stripped.find("{")
    end = stripped.rfind("}")
    if start < 0 or end <= start:
        return out
    try:
        data = json.loads(stripped[start:end + 1])
    except Exception:
        return out
    out["app_state"] = str(data.get("app_state", "unknown"))
    out["summary"] = str(data.get("summary", ""))[:500]
    va = data.get("visible_actions") or []
    if isinstance(va, list):
        out["visible_actions"] = [
            {"label": str(a.get("label", "")), "bbox": a.get("bbox") or {}}
            for a in va if isinstance(a, dict)
        ][:50]
    dt = data.get("detected_text") or {}
    if isinstance(dt, dict):
        out["detected_text"] = {str(k): str(v) for k, v in dt.items()}
    return out


# ---- Navigation ---------------------------------------------------------

@tool
async def list_tabs() -> dict:
    """List all browser tabs currently open in this pod, with name, URL,
    and closed flag."""
    ctx = get_pod_context()
    return {"tabs": ctx.tab_registry.list(ctx.client_id)}


@tool
async def open_tab(url: str, name: str) -> dict:
    """Open a URL in a new tab and register under `name`. If `name` already
    exists, the existing tab is reused and navigated.

    Args:
        url: Absolute URL to navigate to.
        name: Short semantic name chosen by the agent ('chat', 'mail',
            'calendar', 'meeting', …).
    """
    ctx = get_pod_context()
    existing = ctx.tab_registry.get(ctx.client_id, name)
    if existing is not None:
        try:
            await existing.goto(url, wait_until="domcontentloaded", timeout=30000)
            return {"ok": True, "name": name, "url": existing.url, "reused": True}
        except Exception as e:
            return {"ok": False, "error": str(e), "reused": True}
    try:
        page = await ctx.browser_context.new_page()
        await page.goto(url, wait_until="domcontentloaded", timeout=30000)
        ctx.tab_registry.register(ctx.client_id, name, page)
        return {"ok": True, "name": name, "url": page.url, "reused": False}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def switch_tab(name: str) -> dict:
    """Bring the named tab to the foreground. Useful for VNC visibility.

    Args:
        name: Registered tab name.
    """
    ctx = get_pod_context()
    page = ctx.tab_registry.get(ctx.client_id, name)
    if page is None:
        return {"ok": False, "error": f"no tab named {name!r}"}
    try:
        await page.bring_to_front()
        return {"ok": True, "name": name, "url": page.url}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def close_tab(name: str) -> dict:
    """Close and unregister a tab.

    Args:
        name: Tab name.
    """
    ctx = get_pod_context()
    page = ctx.tab_registry.get(ctx.client_id, name)
    if page is None:
        return {"ok": False, "error": f"no tab named {name!r}"}
    try:
        await page.close()
    except Exception:
        pass
    ctx.tab_registry.remove(ctx.client_id, name)
    return {"ok": True, "name": name}


@tool
async def navigate(url: str, tab_name: str = "") -> dict:
    """Navigate a tab to a URL.

    Args:
        url: Absolute URL.
        tab_name: Named tab. Empty = current first page.
    """
    ctx = get_pod_context()
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"ok": False, "error": f"no page for tab_name={tab_name!r}"}
    try:
        await page.goto(url, wait_until="domcontentloaded", timeout=30000)
        return {"ok": True, "url": page.url}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def report_capabilities(capabilities: list[str]) -> dict:
    """Tell the Kotlin server which READ capabilities are working.

    Args:
        capabilities: Subset of ['CHAT_READ', 'EMAIL_READ', 'CALENDAR_READ'].
    """
    ctx = get_pod_context()
    from app.kotlin_callback import notify_capabilities_discovered
    try:
        await notify_capabilities_discovered(ctx.client_id, ctx.connection_id, capabilities)
        return {"ok": True, "capabilities": capabilities}
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ---- Actions ------------------------------------------------------------

@tool
async def click(selector: str, tab_name: str = "") -> dict:
    """Click the first element matching a CSS selector (shadow-DOM pierced
    via Playwright's default locator engine).

    Args:
        selector: CSS selector.
        tab_name: Named tab. Empty = current first page.
    """
    ctx = get_pod_context()
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"ok": False, "error": f"no page for tab_name={tab_name!r}"}
    try:
        await page.locator(selector).first.click(timeout=5000)
        return {"ok": True, "selector": selector}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def click_visual(description: str, tab_name: str = "") -> dict:
    """VLM-resolved click — ask the vision model for the bbox of an element
    described in natural language, then click the bbox center. Use when
    the UI has no stable selector or `click(selector)` times out.

    Args:
        description: Natural-language description ("Join now button",
            "mute microphone", "Leave meeting").
        tab_name: Named tab. Empty = current first page.
    """
    ctx = get_pod_context()
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"ok": False, "error": f"no page for tab_name={tab_name!r}"}

    obs = await look_at_screen.ainvoke({
        "reason": "visual_click",
        "ask": f"return bbox for element described as: {description}",
        "tab_name": tab_name,
    })
    bbox = None
    for action in obs.get("visible_actions", []):
        label = action.get("label", "").lower()
        if description.lower() in label or label in description.lower():
            bbox = action.get("bbox") or {}
            break
    if not bbox:
        dt = obs.get("detected_text", {}) or {}
        raw = dt.get("bbox")
        if isinstance(raw, str):
            try:
                bbox = json.loads(raw)
            except Exception:
                bbox = None

    if not bbox or not all(k in bbox for k in ("x", "y", "w", "h")):
        return {"ok": False, "error": f"VLM did not resolve bbox for {description!r}",
                "observation": obs}

    cx = float(bbox["x"]) + float(bbox["w"]) / 2
    cy = float(bbox["y"]) + float(bbox["h"]) / 2
    try:
        await page.mouse.click(cx, cy)
        return {"ok": True, "bbox": bbox}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def fill(selector: str, value: str, tab_name: str = "") -> dict:
    """Fill a form field by CSS selector. NEVER pass credentials here —
    use `fill_credentials` for password / email. MFA codes are never
    typed into the browser (Authenticator-only, product §17).

    Args:
        selector: CSS selector.
        value: Literal value to type.
        tab_name: Named tab.
    """
    ctx = get_pod_context()
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"ok": False, "error": f"no page for tab_name={tab_name!r}"}
    try:
        await page.locator(selector).first.fill(value, timeout=5000)
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def fill_visual(description: str, value: str, tab_name: str = "") -> dict:
    """VLM-resolved fill — locate a text field by natural-language
    description and type `value` into it. NEVER pass credentials here.

    Args:
        description: Natural-language description of the field.
        value: Literal value to type.
        tab_name: Named tab.
    """
    ctx = get_pod_context()
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"ok": False, "error": f"no page for tab_name={tab_name!r}"}
    obs = await look_at_screen.ainvoke({
        "reason": "visual_fill",
        "ask": f"return bbox for the input/textarea described as: {description}",
        "tab_name": tab_name,
    })
    bbox = None
    for action in obs.get("visible_actions", []):
        label = action.get("label", "").lower()
        if description.lower() in label:
            bbox = action.get("bbox") or {}
            break
    if not bbox or not all(k in bbox for k in ("x", "y", "w", "h")):
        return {"ok": False, "error": f"VLM did not resolve bbox for {description!r}"}
    cx = float(bbox["x"]) + float(bbox["w"]) / 2
    cy = float(bbox["y"]) + float(bbox["h"]) / 2
    try:
        await page.mouse.click(cx, cy)
        await page.keyboard.type(value, delay=20)
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def fill_credentials(
    selector: str,
    field: Literal["email", "password"],
    tab_name: str = "",
) -> dict:
    """Fill a credential field. The LLM NEVER sees the secret — the runtime
    pulls it from the pod's credentials at call time.

    MFA codes are NEVER filled here. Per product §17 the only supported
    second factor is Microsoft Authenticator number-match — the user taps
    the number on their phone, nothing is typed back into the browser.

    Args:
        selector: CSS selector for the input.
        field: 'email' or 'password'.
        tab_name: Named tab.
    """
    ctx = get_pod_context()
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"ok": False, "error": f"no page for tab_name={tab_name!r}"}
    if field == "email":
        value = ctx.credentials.get("email", "")
    elif field == "password":
        value = ctx.credentials.get("password", "")
    else:
        return {"ok": False, "error": f"unknown credential field: {field}"}
    if not value:
        return {"ok": False, "error": f"no value available for field {field}"}
    try:
        await page.locator(selector).first.fill(value, timeout=5000)
        return {"ok": True, "field": field}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def press(key: str, tab_name: str = "") -> dict:
    """Press a keyboard key (Enter, Tab, Escape, …).

    Args:
        key: Playwright key name.
        tab_name: Named tab.
    """
    ctx = get_pod_context()
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"ok": False, "error": f"no page for tab_name={tab_name!r}"}
    try:
        await page.keyboard.press(key)
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def wait(seconds: float, reason: str) -> dict:
    """Sleep with an annotated reason (logs).

    Args:
        seconds: Clamped 0.5–300.
        reason: Short reason.
    """
    await asyncio.sleep(max(0.5, min(300.0, float(seconds))))
    return {"ok": True, "reason": reason}


# ---- State + notifications ---------------------------------------------

@tool
async def report_state(
    state: str,
    reason: str = "",
    mfa_type: str = "",
    mfa_message: str = "",
    mfa_number: str = "",
) -> dict:
    """Transition PodState. Invalid transitions return an error.

    Args:
        state: Target — STARTING / AUTHENTICATING / AWAITING_MFA / ACTIVE /
            RECOVERING / EXECUTING_INSTRUCTION / ERROR.
        reason: Short explanation.
        mfa_type: For AWAITING_MFA — 'authenticator_number',
            'authenticator_code', 'authenticator_push'.
        mfa_message: Czech text for the user.
        mfa_number: 2–3 digit number shown by Microsoft Authenticator.
    """
    ctx = get_pod_context()
    try:
        target = PodState[state.upper()]
    except KeyError:
        return {"ok": False, "error": f"unknown state: {state}"}
    await ctx.state_manager.transition(
        target,
        reason=reason or None,
        mfa_type=mfa_type or None,
        mfa_message=mfa_message or None,
        mfa_number=mfa_number or None,
    )
    return {"ok": True, "state": target.value}


@tool
async def notify_user(
    kind: Literal["urgent_message", "meeting_invite", "meeting_alone_check",
                  "auth_request", "mfa", "error", "info"],
    message: str,
    mfa_code: str = "",
    chat_id: str = "",
    chat_name: str = "",
    sender: str = "",
    preview: str = "",
    meeting_id: str = "",
    screenshot: str = "",
) -> dict:
    """Push a kind-aware notification to the Kotlin server. Direct 1:1
    messages and @mentions MUST use kind='urgent_message'. kind='mfa'
    MUST include mfa_code.

    `message` / `preview` are shown to the user in Czech. Keep them short
    (1–2 sentences). Server prefixes the connection name.

    Args:
        kind: See docs/teams-pod-agent.md §7.
        message: Short Czech text.
        mfa_code: REQUIRED when kind='mfa' — the 2–3 digit Authenticator
            number. Empty otherwise.
        chat_id: urgent_message / meeting_invite — chat slug.
        chat_name: Human-readable chat name.
        sender: urgent_message — sender display name.
        preview: urgent_message — original message preview.
        meeting_id: meeting_alone_check — MeetingDocument id.
        screenshot: error — optional path to a captured screenshot.
    """
    ctx = get_pod_context()
    if kind == "mfa" and not mfa_code:
        return {"ok": False, "error": "kind='mfa' requires mfa_code"}

    dedup_key = f"{kind}|{chat_id or meeting_id or mfa_code or ''}"
    if dedup_key in ctx.notified_contexts and kind not in ("urgent_message", "mfa"):
        return {"ok": False, "error": "already notified in this context"}
    ctx.notified_contexts.add(dedup_key)

    from app.grpc_clients import server_o365_session_stub
    from jervis.common import types_pb2
    from jervis.server import o365_session_pb2
    from jervis_contracts.interceptors import prepare_context

    grpc_ctx = types_pb2.RequestContext()
    prepare_context(grpc_ctx)
    request = o365_session_pb2.NotifyRequest(
        ctx=grpc_ctx,
        connection_id=ctx.connection_id,
        kind=kind,
        message=message,
        chat_id=chat_id or "",
        chat_name=chat_name or "",
        sender=sender or "",
        preview=preview or "",
        screenshot=screenshot or "",
        mfa_code=mfa_code or "",
        meeting_id=meeting_id or "",
    )
    try:
        resp = await server_o365_session_stub().Notify(request, timeout=10.0)
        if kind == "urgent_message" and chat_id:
            await ctx.storage.ledger_mark_urgent_sent(ctx.connection_id, chat_id)
        return {"ok": True, "status": resp.status, "priority": resp.priority}
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ---- Work hours / activity ---------------------------------------------

@tool
async def query_user_activity() -> dict:
    """Seconds since the user's UI last pinged the server. Large number ⇒
    user is away. Authoritative value for off-hours relogin decisions (§18)."""
    ctx = get_pod_context()
    seconds = await work_hours.query_user_activity_seconds(ctx.client_id)
    return {"last_active_seconds": seconds}


@tool
async def is_work_hours() -> dict:
    """True when local time is Mon–Fri 09:00–16:00 Europe/Prague."""
    return {"is_work_hours": work_hours.is_work_hours_now()}


# ---- Storage primitives ------------------------------------------------

@tool
async def store_chat_row(
    chat_id: str,
    chat_name: str,
    is_direct: bool,
    is_group: bool,
    unread_count: int = 0,
    unread_direct_count: int = 0,
    last_message_at: str = "",
) -> dict:
    """Upsert one chat row into o365_message_ledger (pod is sole writer).

    Args:
        chat_id: Stable slug the agent chose for this chat (use the same
            slug for related store_message calls).
        chat_name: Human-readable name.
        is_direct: True for 1:1 DM.
        is_group: True for group chat.
        unread_count: Current unread count, 0 if none.
        unread_direct_count: Unread count attributable to direct pings
            (mentions + DM).
        last_message_at: ISO timestamp of most recent visible message,
            or empty.
    """
    ctx = get_pod_context()
    from datetime import datetime
    ts = None
    if last_message_at:
        try:
            ts = datetime.fromisoformat(last_message_at.replace("Z", "+00:00"))
        except Exception:
            ts = None
    await ctx.storage.ledger_upsert(
        ctx.connection_id, ctx.client_id, chat_id, chat_name,
        is_direct=is_direct, is_group=is_group,
        last_message_at=ts,
        unread_count=int(unread_count),
        unread_direct_count=int(unread_direct_count),
    )
    return {"ok": True, "chat_id": chat_id}


@tool
async def store_message(
    chat_id: str,
    chat_name: str,
    message_id: str,
    sender: str,
    content: str,
    timestamp: str = "",
    is_mention: bool = False,
    attachment_kind: str = "",
) -> dict:
    """Insert one observed message into o365_scrape_messages (state=NEW).
    Dedup key = (connectionId, messageHash). When `message_id` is a DOM
    external id (e.g. data-mid), use it. Otherwise pass an empty string
    and a content hash will be computed.

    Args:
        chat_id: Stable slug (same as store_chat_row).
        chat_name: Human-readable name (used for indexer topic grouping).
        message_id: External DOM id. Empty ⇒ hash of sender+timestamp+content.
        sender: Sender display name.
        content: Message text.
        timestamp: ISO or UI-visible timestamp string.
        is_mention: True when the logged-in user was @mentioned.
        attachment_kind: 'image', 'file', 'video', 'audio', 'link', or ''.
    """
    ctx = get_pod_context()
    inserted = await ctx.storage.store_message_row(
        connection_id=ctx.connection_id,
        client_id=ctx.client_id,
        chat_id=chat_id,
        chat_name=chat_name,
        message_id=message_id,
        sender=sender,
        content=content,
        timestamp=timestamp or None,
        is_mention=bool(is_mention),
        attachment_kind=attachment_kind or None,
    )
    return {"ok": True, "inserted": inserted}


@tool
async def store_discovered_resource(
    resource_type: Literal["chat", "channel", "team", "calendar", "mailbox"],
    external_id: str,
    display_name: str,
    team_name: str = "",
    description: str = "",
) -> dict:
    """Upsert a discovered resource (chat/channel/team/calendar/mailbox)
    in o365_discovered_resources. UI maps resources to projects.

    Args:
        resource_type: Category.
        external_id: Stable id the agent chose (slug).
        display_name: Human-readable name.
        team_name: Owning team (for channel).
        description: Optional short description.
    """
    ctx = get_pod_context()
    ok = await ctx.storage.store_discovered_resource(
        connection_id=ctx.connection_id,
        client_id=ctx.client_id,
        resource_type=resource_type,
        external_id=external_id,
        display_name=display_name,
        team_name=team_name or None,
        description=description or None,
    )
    return {"ok": True, "inserted": ok, "external_id": external_id}


@tool
async def store_calendar_event(
    external_id: str,
    title: str,
    start: str = "",
    end: str = "",
    organizer: str = "",
    join_url: str = "",
) -> dict:
    """Upsert a calendar event into scraped_calendar.

    Args:
        external_id: Stable id (Graph event id, URL slug, or agent hash).
        title: Event title.
        start: ISO start time.
        end: ISO end time.
        organizer: Organizer display name.
        join_url: Teams meeting join URL, if present.
    """
    ctx = get_pod_context()
    ok = await ctx.storage.store_calendar_event(
        connection_id=ctx.connection_id,
        client_id=ctx.client_id,
        external_id=external_id,
        title=title,
        start=start or None,
        end=end or None,
        organizer=organizer or None,
        join_url=join_url or None,
    )
    return {"ok": True, "inserted": ok, "external_id": external_id}


@tool
async def store_mail_header(
    external_id: str,
    sender: str,
    subject: str,
    received_at: str = "",
    preview: str = "",
    is_unread: bool = False,
) -> dict:
    """Upsert a mail header into scraped_mail (metadata only, no body).

    Args:
        external_id: Stable id (Graph message id / conversation id).
        sender: From field.
        subject: Subject line.
        received_at: ISO timestamp.
        preview: Short preview snippet.
        is_unread: True when the UI shows unread.
    """
    ctx = get_pod_context()
    ok = await ctx.storage.store_mail_header(
        connection_id=ctx.connection_id,
        client_id=ctx.client_id,
        external_id=external_id,
        sender=sender,
        subject=subject,
        received_at=received_at or None,
        preview=preview,
        is_unread=bool(is_unread),
    )
    return {"ok": True, "inserted": ok, "external_id": external_id}


@tool
async def mark_seen(chat_id: str) -> dict:
    """Reset unread counters for a chat after the pod has read it.

    Args:
        chat_id: Slug used in store_chat_row.
    """
    ctx = get_pod_context()
    if not chat_id:
        return {"ok": False, "error": "missing chat_id"}
    await ctx.storage.ledger_mark_seen(ctx.connection_id, chat_id)
    return {"ok": True, "chat_id": chat_id}


# ---- Meeting presence + recording --------------------------------------

@tool
async def meeting_presence_report(present: bool, meeting_stage_visible: bool) -> dict:
    """Report meeting presence to the server. `present` = any meeting
    context (stage, pre-join, lobby). `meeting_stage_visible` = actual
    in-call stage rendered (distinguishes stage-visible from background
    presence like a calling screen).
    """
    pod_ctx = get_pod_context()
    try:
        from app.grpc_clients import server_meeting_attend_stub
        from jervis.server import meeting_attend_pb2
        from jervis.common import types_pb2
        from jervis_contracts.interceptors import prepare_context

        req_ctx = types_pb2.RequestContext()
        prepare_context(req_ctx)
        resp = await server_meeting_attend_stub().ReportPresence(
            meeting_attend_pb2.PresenceRequest(
                ctx=req_ctx,
                connection_id=pod_ctx.connection_id or "",
                client_id=pod_ctx.client_id or "",
                present=bool(present),
            ),
            timeout=5.0,
        )
        return {"ok": resp.ok, "present": resp.present}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@tool
async def start_meeting_recording(
    meeting_id: str = "",
    title: str = "",
    joined_by: Literal["user", "agent"] = "agent",
    tab_name: str = "",
) -> dict:
    """Start recording the current meeting. When `meeting_id` is empty, a
    MeetingDocument is allocated server-side (ad-hoc / user-joined).

    Args:
        meeting_id: Existing MeetingDocument id (scheduled), or empty for
            ad-hoc.
        title: Title override (ad-hoc). Empty → 'Ad-hoc meeting'.
        joined_by: 'user' when the user clicked Join via VNC, 'agent'
            when agent composed the join.
        tab_name: Tab holding the meeting. Empty = first page with the
            stage visible.
    """
    ctx = get_pod_context()
    if ctx.meeting_recorder is None:
        return {"ok": False, "error": "meeting_recorder not wired"}
    page = ctx.resolve_tab(tab_name)
    if page is None:
        return {"ok": False, "error": f"no page for tab_name={tab_name!r}"}
    session = await ctx.meeting_recorder.start_adhoc(
        client_id=ctx.client_id,
        page=page,
        title=title or None,
        joined_by=joined_by,
        meeting_id=meeting_id or None,
        connection_id=ctx.connection_id,
    )
    if session is None:
        return {"ok": False, "error": "failed to allocate meeting"}
    # Tell the background watcher we now own a live meeting so it can
    # start end-detection bookkeeping on the correct tab.
    if ctx.watcher is not None:
        resolved_tab = tab_name or ""
        if not resolved_tab:
            for entry in ctx.tab_registry.list(ctx.client_id):
                if entry.get("name") and not entry.get("closed"):
                    resolved_tab = entry["name"]
                    break
        try:
            ctx.watcher.set_active_meeting(
                meeting_id=session.meeting_id,
                tab_name=resolved_tab,
                joined_by=session.joined_by,
            )
        except Exception:
            pass
    return {
        "ok": True,
        "meeting_id": session.meeting_id,
        "task_id": session.task_id,
        "state": session.state,
        "joined_by": session.joined_by,
        "chunks_uploaded": session.chunks_uploaded,
    }


@tool
async def stop_meeting_recording(meeting_id: str = "") -> dict:
    """Stop the in-progress recording. When `meeting_id` is empty, stops
    whichever recording is active for this client."""
    ctx = get_pod_context()
    if ctx.meeting_recorder is None:
        return {"ok": False, "error": "meeting_recorder not wired"}
    if meeting_id:
        task_id = await ctx.meeting_recorder.stop_by_meeting_id(meeting_id)
    else:
        task_id = await ctx.meeting_recorder.stop_adhoc_for_client(ctx.client_id)
    if task_id is None:
        return {"ok": False, "error": "no recording running"}
    if ctx.watcher is not None:
        try:
            ctx.watcher.clear_active_meeting()
        except Exception:
            pass
    return {"ok": True, "task_id": task_id}


@tool
async def leave_meeting(meeting_id: str, reason: str) -> dict:
    """Leave the current meeting. Tool composes:
      (1) stop_meeting_recording
      (2) click [data-tid='call-end'] (or VLM fallback click_visual 'Leave')
      (3) verify meeting_stage disappeared within 10 s
      (4) meeting_presence_report(present=false, meeting_stage_visible=false)

    Args:
        meeting_id: MeetingDocument id. Empty ⇒ leave whatever is active.
        reason: Short reason ('user_asked', 'no_show', 'post_activity_alone',
            'meeting_ended_banner', 'scheduled_overrun').
    """
    ctx = get_pod_context()
    stopped = await stop_meeting_recording.ainvoke({"meeting_id": meeting_id})

    page = ctx.resolve_tab("")
    click_ok = False
    if page is not None:
        try:
            await page.locator('[data-tid="call-end"]').first.click(timeout=3000)
            click_ok = True
        except Exception:
            pass
    if not click_ok:
        visual = await click_visual.ainvoke({"description": "Leave meeting", "tab_name": ""})
        click_ok = bool(visual.get("ok"))

    left = False
    for _ in range(10):
        await asyncio.sleep(1)
        probe = await _dom_query.query(
            page, selector='[data-tid="meeting-stage"]',
            attrs=[], text=False, max_matches=1,
        ) if page is not None else {"count": 0}
        if int(probe.get("count", 0)) == 0:
            left = True
            break

    await meeting_presence_report.ainvoke({
        "present": False, "meeting_stage_visible": False,
    })
    return {"ok": left and click_ok, "left": left, "click_ok": click_ok,
            "stopped": bool(stopped.get("ok")), "reason": reason}


# ---- Terminators -------------------------------------------------------

@tool
async def query_history(
    state: Annotated[dict, InjectedState],
    n: int = 20,
    before_index: int = -1,
    contains: str = "",
    kind: Literal["", "human", "ai", "tool", "system"] = "",
) -> dict:
    """Look up older messages from this pod's full conversation history.

    The agent's LLM context only sees the **last 10 messages** — every
    older Human/AI/Tool message lives in the LangGraph MongoDB
    checkpoint. Call this tool when you need to recall something earlier
    (e.g. a chat-row id you stored an hour ago, the URL you navigated
    away from, what the watcher said two cycles back).

    Args:
        n: Maximum number of messages to return (newest within the
            filter, capped at 50). Default 20.
        before_index: Only return messages whose absolute index in the
            full history is < this value. Use -1 (default) to include
            up to the very latest. Pass an earlier index to page
            backwards through history.
        contains: Optional case-insensitive substring filter applied
            against message text + tool_call args.
        kind: Optional role filter — 'human', 'ai', 'tool', 'system',
            or '' to include all roles.

    Returns:
        {total: <total messages in history>, returned: <count>,
         messages: [{index, role, ts?, content_preview, tool_calls?,
                     tool_call_id?, name?}, ...]}
        Newest-first within the filter. Each `content_preview` is
        capped at 600 characters.
    """
    messages: list[BaseMessage] = state.get("messages") or []
    total = len(messages)
    if not messages:
        return {"total": 0, "returned": 0, "messages": []}

    upper = total if before_index < 0 else min(before_index, total)
    sub = list(enumerate(messages[:upper]))
    needle = contains.strip().lower()
    role_map = {
        "human": HumanMessage,
        "ai": AIMessage,
        "tool": ToolMessage,
        "system": SystemMessage,
    }
    role_cls = role_map.get(kind, None) if kind else None

    out: list[dict] = []
    # Walk newest-first.
    for idx, m in reversed(sub):
        if role_cls is not None and not isinstance(m, role_cls):
            continue
        text = m.content if isinstance(m.content, str) else json.dumps(m.content, default=str)
        tc_list = getattr(m, "tool_calls", None) or []
        tc_summary = [
            {"id": tc.get("id", ""), "name": tc.get("name", ""),
             "args": tc.get("args", {})}
            for tc in tc_list
        ]
        haystack = text or ""
        if tc_summary:
            haystack = haystack + " " + json.dumps(tc_summary, default=str)
        if needle and needle not in haystack.lower():
            continue
        entry: dict = {
            "index": idx,
            "role": _role_of(m),
            "content_preview": (text or "")[:600],
        }
        if tc_summary:
            entry["tool_calls"] = tc_summary
        if isinstance(m, ToolMessage):
            entry["tool_call_id"] = m.tool_call_id
            if getattr(m, "name", None):
                entry["name"] = m.name
        out.append(entry)
        if len(out) >= max(1, min(50, n)):
            break

    return {"total": total, "returned": len(out), "messages": out}


def _role_of(m: BaseMessage) -> str:
    if isinstance(m, HumanMessage):
        return "human"
    if isinstance(m, AIMessage):
        return "ai"
    if isinstance(m, ToolMessage):
        return "tool"
    if isinstance(m, SystemMessage):
        return "system"
    return type(m).__name__.lower()


@tool
async def done(summary: str) -> dict:
    """Mark the current reasoning cycle complete.

    Args:
        summary: Short description of what was accomplished.
    """
    return {"ok": True, "summary": summary}


@tool
async def error(reason: str, screenshot: bool = False) -> dict:
    """Transition to ERROR state and wait for an instruction.

    Args:
        reason: Short reason for logs / user.
        screenshot: When True, capture a screenshot path into the error
            metadata (agent can reference it via notify_user kind='error').
    """
    ctx = get_pod_context()
    await ctx.state_manager.transition(PodState.ERROR, reason=reason)
    return {"ok": True, "state": "ERROR", "screenshot_requested": bool(screenshot)}


ALL_TOOLS = [
    # Observation
    inspect_dom, look_at_screen,
    # Navigation
    list_tabs, open_tab, switch_tab, close_tab, navigate, report_capabilities,
    # Actions
    click, click_visual, fill, fill_visual, fill_credentials, press, wait,
    # State + notifications
    report_state, notify_user,
    # Work hours / activity
    query_user_activity, is_work_hours,
    # Storage primitives
    store_chat_row, store_message, store_discovered_resource,
    store_calendar_event, store_mail_header, mark_seen,
    # Meeting
    meeting_presence_report, start_meeting_recording, stop_meeting_recording,
    leave_meeting,
    # History / introspection
    query_history,
    # Terminators
    done, error,
]
