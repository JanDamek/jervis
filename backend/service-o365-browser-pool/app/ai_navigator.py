"""AI Navigator — VLM sees the screen, LLM decides what to do, pod executes.

This replaces hardcoded CSS selectors with AI-driven navigation.
Uses ollama-router for both VLM (vision) and LLM (text) models.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from playwright.async_api import Page

from app.config import settings
from app.vlm_client import analyze_screenshot

logger = logging.getLogger("o365-browser-pool.ai-nav")

# LLM endpoint on ollama-router
_ROUTER_URL = settings.ollama_router_url


async def see_screen(page: Page) -> dict:
    """Take screenshot and ask VLM what's on screen.

    Returns structured dict:
    {
        "screen_type": "login_page|mfa_method_picker|mfa_approval|teams_loaded|
                        error_page|loading|unknown",
        "details": "human-readable description",
        "elements": [{"text": "Sign in", "type": "button", "clickable": true}],
        "input_fields": [{"label": "Password", "type": "password"}],
        "error_message": null | "Invalid credentials"
    }
    """
    screenshot = await page.screenshot(type="jpeg", quality=80)

    prompt = """Analyze this browser screenshot. Respond ONLY with JSON (no markdown, no explanation):
{
  "screen_type": "<one of: login_email, login_password, mfa_method_picker, mfa_approval, mfa_code_entry, teams_loaded, teams_loading, outlook_loaded, calendar_loaded, error_page, consent_page, stay_signed_in, org_info, unknown>",
  "details": "<brief description of what you see>",
  "elements": [{"text": "<visible text>", "type": "<button|link|div>", "clickable": true}],
  "input_fields": [{"label": "<field label>", "type": "<text|password|email|code>"}],
  "error_message": null
}

Key identifiers:
- login_email: email/username input field visible
- login_password: password input field visible
- mfa_method_picker: "Verify your identity" with multiple options (certificate, authenticator, SMS)
- mfa_approval: "Approve sign in request" or number to enter in Authenticator app
- mfa_code_entry: input field for verification code
- teams_loaded: Microsoft Teams interface with chats/channels visible
- teams_loading: Teams logo or spinner
- error_page: error message visible
- consent_page: "Permissions requested" or "Accept" button
- stay_signed_in: "Stay signed in?" prompt
- org_info: "Access to X is monitored" (MCAS page)
"""

    try:
        result_text = await analyze_screenshot(
            screenshot, prompt,
            processing_mode="BACKGROUND",
            max_tier="FREE",
        )
        # Parse JSON from VLM response (may have markdown wrapping)
        json_str = result_text.strip()
        if json_str.startswith("```"):
            json_str = json_str.split("```")[1]
            if json_str.startswith("json"):
                json_str = json_str[4:]
        return json.loads(json_str)
    except json.JSONDecodeError:
        logger.warning("VLM returned non-JSON: %s", result_text[:200] if result_text else "empty")
        return {
            "screen_type": "unknown",
            "details": result_text[:500] if result_text else "VLM returned empty response",
            "elements": [],
            "input_fields": [],
            "error_message": None,
        }
    except Exception as e:
        logger.error("VLM see_screen failed: %s", e)
        return {
            "screen_type": "unknown",
            "details": f"VLM error: {e}",
            "elements": [],
            "input_fields": [],
            "error_message": None,
        }


async def decide_action(
    screen_info: dict,
    context: str,
    credentials: dict | None = None,
) -> dict:
    """Ask LLM what action to take based on screen state.

    Args:
        screen_info: output from see_screen()
        context: what we're trying to do ("login to Microsoft Teams", "execute instruction: ...")
        credentials: {"email": "...", "password": "..."} if available

    Returns:
    {
        "action": "fill|click|wait|error|done",
        "target": "<element text or field label>",
        "value": "<text to fill>",
        "reason": "<why this action>"
    }
    """
    import httpx

    screen_type = screen_info.get("screen_type", "unknown")

    # Fast path — known states that don't need LLM
    if screen_type == "teams_loaded":
        return {"action": "done", "reason": "Teams is loaded"}
    if screen_type in ("outlook_loaded", "calendar_loaded"):
        return {"action": "done", "reason": f"{screen_type} — app loaded"}
    if screen_type == "teams_loading":
        return {"action": "wait", "reason": "Teams is loading"}
    if screen_type == "stay_signed_in":
        return {"action": "click", "target": "Yes", "reason": "Accept stay signed in"}
    if screen_type == "consent_page":
        return {"action": "click", "target": "Accept", "reason": "Accept consent"}
    if screen_type == "org_info":
        return {"action": "click", "target": "Continue", "reason": "Continue past org info"}

    # Fast path — login with known credentials
    if credentials:
        if screen_type == "login_email":
            return {
                "action": "fill",
                "target": "Email",
                "value": credentials.get("email", ""),
                "reason": "Enter email address",
                "submit": True,
            }
        if screen_type == "login_password":
            return {
                "action": "fill",
                "target": "Password",
                "value": credentials.get("password", ""),
                "reason": "Enter password",
                "submit": True,
            }
        if screen_type == "mfa_method_picker":
            return {
                "action": "click",
                "target": "Authenticator",
                "reason": "Select Microsoft Authenticator for MFA",
            }
        if screen_type == "mfa_approval":
            return {"action": "wait_mfa", "reason": "Waiting for MFA approval in Authenticator"}
        if screen_type == "mfa_code_entry":
            return {"action": "wait_mfa_code", "reason": "Waiting for user to provide MFA code"}

    # Error detection
    error_msg = screen_info.get("error_message")
    if screen_type == "error_page" or error_msg:
        return {
            "action": "error",
            "reason": error_msg or screen_info.get("details", "Unknown error on page"),
        }

    # Unknown state — ask LLM for decision
    creds_hint = ""
    if credentials:
        creds_hint = f"\nI have credentials: email={credentials.get('email', '?')}, password=***"

    prompt = f"""I am a browser automation bot. My goal: {context}
{creds_hint}

Current screen state (from VLM analysis):
{json.dumps(screen_info, indent=2)}

What should I do? Respond ONLY with JSON:
{{"action": "fill|click|wait|error|done", "target": "<element text or label>", "value": "<text to type>", "reason": "<why>"}}

Rules:
- "fill": type text into an input field (target = field label)
- "click": click a button/link (target = visible text on the element)
- "wait": wait and check again (page is loading)
- "error": something is wrong, cannot proceed (reason = what's wrong)
- "done": goal achieved
"""

    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_ROUTER_URL}/api/generate",
                json={
                    "model": "qwen3:14b",
                    "prompt": prompt,
                    "stream": False,
                },
            )
            resp.raise_for_status()
            response_text = resp.json().get("response", "")
            json_str = response_text.strip()
            if json_str.startswith("```"):
                json_str = json_str.split("```")[1]
                if json_str.startswith("json"):
                    json_str = json_str[4:]
            return json.loads(json_str)
    except Exception as e:
        logger.error("LLM decide_action failed: %s", e)
        return {"action": "error", "reason": f"LLM unavailable: {e}"}


async def execute_action(page: Page, action: dict) -> bool:
    """Execute an action on the page. Returns True if action was performed.

    Supports:
    - fill: type text into input field by label
    - click: click element by visible text
    - submit: press Enter after fill (when action has submit=True)
    """
    action_type = action.get("action")
    target = action.get("target", "")
    value = action.get("value", "")

    if action_type == "fill":
        # Try to find input by label, placeholder, or type
        filled = False
        for selector_fn in [
            lambda: page.get_by_label(target, exact=False),
            lambda: page.get_by_placeholder(target, exact=False),
            lambda: page.locator(f'input[type="{target.lower()}"]'),
            lambda: page.locator('input[type="email"]') if "email" in target.lower() else None,
            lambda: page.locator('input[type="password"]') if "password" in target.lower() else None,
        ]:
            try:
                locator = selector_fn()
                if locator is None:
                    continue
                if await locator.count() > 0:
                    await locator.first.fill(value)
                    filled = True
                    logger.info("AI-NAV: filled '%s'", target)
                    break
            except Exception:
                continue

        if not filled:
            logger.warning("AI-NAV: could not find input '%s'", target)
            return False

        # Submit if requested
        if action.get("submit"):
            import asyncio
            await asyncio.sleep(1)
            # Try clicking submit button first
            for btn_text in ["Sign in", "Next", "Submit", "Přihlásit", "Další", "Odeslat"]:
                try:
                    btn = page.get_by_text(btn_text, exact=False)
                    if await btn.count() > 0:
                        await btn.first.click()
                        logger.info("AI-NAV: clicked submit '%s'", btn_text)
                        return True
                except Exception:
                    continue
            # Fallback: press Enter
            await page.keyboard.press("Enter")
            logger.info("AI-NAV: pressed Enter to submit")
        return True

    if action_type == "click":
        # Click element by visible text
        for attempt_text in [target, target.split("(")[0].strip()]:
            try:
                locator = page.get_by_text(attempt_text, exact=False)
                if await locator.count() > 0:
                    await locator.first.click()
                    logger.info("AI-NAV: clicked '%s'", attempt_text)
                    return True
            except Exception:
                continue

        # Try by role
        for role in ["button", "link"]:
            try:
                locator = page.get_by_role(role, name=target)
                if await locator.count() > 0:
                    await locator.first.click()
                    logger.info("AI-NAV: clicked %s '%s'", role, target)
                    return True
            except Exception:
                continue

        logger.warning("AI-NAV: could not find clickable '%s'", target)
        return False

    if action_type in ("wait", "wait_mfa", "wait_mfa_code", "done", "error"):
        return True  # No page action needed

    logger.warning("AI-NAV: unknown action type '%s'", action_type)
    return False


async def save_error_screenshot(page: Page, client_id: str, reason: str) -> str:
    """Save screenshot for ERROR state debugging. Returns file path."""
    path = Path(settings.profiles_dir) / client_id / "error-screenshot.jpg"
    path.parent.mkdir(parents=True, exist_ok=True)
    await page.screenshot(path=str(path), type="jpeg", quality=80)
    logger.info("Saved error screenshot: %s (reason: %s)", path, reason)
    return str(path)
