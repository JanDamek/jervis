"""Reasoner — LLM that decides the next Action given an Observation and a Goal.

Strict rules:
- No fast paths, no regex, no screen_type enum lookup.
- LLM gets the observation + the goal description + recent action history +
  any credentials / context. It returns ONE structured action with a reason.
- Reasoner only chooses the action; Executor performs it.
"""

from __future__ import annotations

import json
import logging
from typing import Optional

import httpx

from app.agent.executor import Action
from app.agent.observer import Observation
from app.config import settings

logger = logging.getLogger("o365-browser-pool.agent.reasoner")

_MODEL = "qwen3:14b"
_ROUTER_URL = settings.ollama_router_url


_SYSTEM_PROMPT = """You are an autonomous browser agent driving Microsoft 365 web apps
(Teams, Outlook, Calendar) on behalf of a user. Each step you receive a
canonical observation of the screen and a current goal. Choose ONE next
action that moves toward the goal.

Hard rules:
- Read ONLY what the observation says — do not invent elements.
- Be patient: if the page is loading or you just acted, prefer "wait".
- Credential field rules — DO NOT include the value yourself; the agent
  runtime injects credentials based on target. You only choose the action,
  target, and submit flag:
    * Email/username/login input → action=fill, target="Email"
      (or "Username"). DO NOT include "value".
    * Password input → action=fill, target="Password". DO NOT include "value".
- Account picker: pick the tile whose text contains the user's email.

- **MFA — Authenticator push / number-match is the ONLY supported path.**
  * If the page offers MULTIPLE sign-in methods (Authenticator app vs SMS
    text code vs phone call vs security key vs "Use a verification code"),
    **ALWAYS click the Microsoft Authenticator / "Schvalování pomocí aplikace"
    / "Use an app" / "Approve a request on my Microsoft Authenticator app"
    tile.** NEVER pick SMS, text code, phone call, or "I can't use my
    Authenticator app right now".
  * Number-match screen (headline like "Zadejte do aplikace číslo XY" or
    "Enter the number shown to sign in"): action="wait_mfa" with the displayed
    number copied into `reason` (e.g. `"wait_mfa number=47"`). The runtime
    extracts it and JERVIS shows it to the user, who approves in the app.
  * Plain push approval ("Open your Authenticator app and approve the
    request" — no number shown): action="wait_mfa".
  * **NEVER use action="wait_mfa_code"** and NEVER fill a one-time code into
    a web input yourself. If the only visible option is code entry and no
    Authenticator alternative is offered, action="ask_user" describing the
    page — we do NOT fall back to code entry.
- Consent / "Stay signed in" / org-monitoring notice: click the affirmative
  button (Continue / Yes / Accept / Pokračovat).
- If the observation includes error_text mentioning incorrect/invalid
  credentials, locked account, or blocked sign-in: action="ask_user" right
  away — do not retry.
- If you cannot make progress (unknown page, no actionable buttons, or the
  same screen for several attempts): action="ask_user" with a description of
  what you see.
- Goal already satisfied: action="done".
- Hard error visible (account locked, account suspended, blocked): action="error".

When asking to fill a password, set target to a short label like "Password"
(NOT a long sentence containing the email address).

For action="click", target MUST be the EXACT visible text of one of the
elements listed in observation.buttons[].text or observation.links[].
NEVER use generic words like "button", "link", "continue button" — copy the
real text. If the observation lists no buttons but the VLM description
mentions a clickable element (e.g. "Continue to Microsoft Teams"), use
that text verbatim.

Output ONLY a JSON object, no markdown:
{
  "action": "click|fill|press|wait|wait_mfa|navigate|select_tab|done|ask_user|error",
  "target": "<element text / input label / URL / tab name>",
  "value": "<text to type, when action=fill>",
  "submit": true,    // optional: press submit after fill
  "reason": "<one short sentence why>"
}
"""


def _format_history(history: list[dict]) -> str:
    if not history:
        return "(no prior actions)"
    lines = []
    for h in history[-5:]:
        lines.append(f"- {h.get('action')} target={h.get('target')!r}"
                     f" reason={h.get('reason', '')[:60]!r}"
                     f" result={h.get('result', '?')}")
    return "\n".join(lines)


async def decide(
    observation: Observation,
    goal_description: str,
    history: list[dict],
    credentials: Optional[dict] = None,
    extra_context: Optional[str] = None,
) -> Action:
    """Ask LLM for next action. Returns Action or 'error'/'ask_user' on LLM failure."""
    creds_blob = ""
    if credentials and credentials.get("email"):
        # We do NOT expose the password to the LLM. We only mention the email
        # so the agent can pick the correct account tile / username field.
        # The runtime injects the actual password value into fill actions.
        creds_blob = (
            f"\nCredentials available: email={credentials['email']}. "
            f"(Password is held by the runtime; just choose action=fill "
            f"target=\"Password\" with NO value — runtime fills it.)"
        )

    prompt = f"""{_SYSTEM_PROMPT}

CURRENT GOAL: {goal_description}
{creds_blob}
{('EXTRA CONTEXT: ' + extra_context) if extra_context else ''}

RECENT ACTIONS (most recent last):
{_format_history(history)}

CURRENT SCREEN OBSERVATION:
{json.dumps(observation.to_prompt_dict(), indent=2, ensure_ascii=False)}

Decide the next action.
"""

    try:
        async with httpx.AsyncClient(timeout=120) as client:
            resp = await client.post(
                f"{_ROUTER_URL}/api/generate",
                json={"model": _MODEL, "prompt": prompt, "stream": False},
            )
            resp.raise_for_status()
            text = resp.json().get("response", "")
            json_str = text.strip()
            if json_str.startswith("```"):
                json_str = json_str.split("```")[1]
                if json_str.startswith("json"):
                    json_str = json_str[4:]
            data = json.loads(json_str)
    except Exception as e:
        logger.error("Reasoner LLM call failed: %s", e)
        return Action(
            type="ask_user",
            target=None,
            reason=f"Reasoner unavailable ({e}); user input needed",
        )

    raw_action = data.get("action", "ask_user")
    # Map agent-level actions to executor action types
    action_type = raw_action
    if raw_action == "wait_mfa_code":
        # Legacy — never produced by the new prompt, but if an old model emits it,
        # escalate to ask_user rather than silently waiting for a manual code.
        action_type = "ask_user"
    elif raw_action == "wait_mfa":
        # Executor treats these as 'wait'; agent loop tracks the MFA-waiting context separately
        action_type = "wait"

    return Action(
        type=action_type,  # type: ignore[arg-type]
        target=data.get("target"),
        value=data.get("value"),
        submit=bool(data.get("submit", False)),
        reason=str(data.get("reason", raw_action)),
    )
