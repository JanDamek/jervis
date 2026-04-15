"""Executor — performs an Action on a Playwright page.

Pure adapter: no decisions, no fallbacks. Tries reasonable selector strategies
to find an element by visible text / label, executes, returns success bool +
optional error string.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import Literal, Optional

from playwright.async_api import Page

logger = logging.getLogger("o365-browser-pool.agent.executor")


ActionType = Literal[
    "click", "fill", "press", "navigate", "wait", "ask_user", "done", "error",
    "select_tab",  # navigate within app to a known tab kind (chat/email/calendar)
]


@dataclass
class Action:
    type: ActionType
    target: Optional[str] = None  # button/link text, input label, URL, key name
    value: Optional[str] = None   # text to fill, key to press, etc.
    submit: bool = False          # press Enter / click submit after fill
    reason: str = ""              # human-readable why


@dataclass
class ActionResult:
    success: bool
    error: Optional[str] = None
    note: Optional[str] = None


async def _try_click_text(page: Page, text: str) -> bool:
    """Click first visible element matching text, by various strategies."""
    if not text:
        return False
    # 1. Exact role-based match — most reliable
    for role in ("button", "link", "menuitem"):
        try:
            loc = page.get_by_role(role, name=text)
            if await loc.count() > 0 and await loc.first.is_visible(timeout=500):
                await loc.first.click()
                logger.info("Executor: clicked %s '%s'", role, text)
                return True
        except Exception:
            continue
    # 2. Visible text (substring)
    try:
        loc = page.get_by_text(text, exact=False)
        if await loc.count() > 0 and await loc.first.is_visible(timeout=500):
            await loc.first.click()
            logger.info("Executor: clicked text '%s'", text)
            return True
    except Exception:
        pass
    return False


async def _try_fill_label(page: Page, label: str, value: str) -> bool:
    """Find the input the agent meant and fill it.

    Order of strategies — most specific first to avoid filling the wrong field
    when the label string contains multiple hints (e.g. "Enter the password
    for user@example.com" must NEVER hit an email selector):
      1. Type hint based on label keywords (password / code / email).
         Mutually exclusive — password disqualifies email/code, etc.
      2. Label / placeholder text match.
    """
    if not label:
        return False
    lower = label.lower()
    is_password = "password" in lower or "heslo" in lower
    is_code = (("code" in lower or "kód" in lower or "kod" in lower)
               and not is_password)
    is_email = (("email" in lower or "username" in lower or " user " in f" {lower} ")
                and not is_password and not is_code)

    candidates: list = []
    if is_password:
        candidates.append(lambda: page.locator('input[type="password"]:visible'))
    elif is_code:
        candidates.append(lambda: page.locator(
            'input[name="otc"]:visible, input[autocomplete="one-time-code"]:visible'
        ))
    elif is_email:
        candidates.append(lambda: page.locator(
            'input[type="email"]:visible, input[name="loginfmt"]:visible, '
            'input[name="username"]:visible'
        ))

    candidates.append(lambda: page.get_by_label(label, exact=False))
    candidates.append(lambda: page.get_by_placeholder(label, exact=False))

    for fn in candidates:
        try:
            loc = fn()
            if await loc.count() > 0:
                await loc.first.fill(value)
                logger.info("Executor: filled '%s' (len=%d)", label, len(value))
                return True
        except Exception:
            continue
    return False


async def _press_submit(page: Page) -> None:
    """After fill, click a generic submit button or press Enter."""
    await asyncio.sleep(0.5)
    for txt in ("Sign in", "Next", "Verify", "Submit", "Přihlásit", "Další", "Odeslat", "Ověřit"):
        try:
            loc = page.get_by_role("button", name=txt)
            if await loc.count() > 0:
                await loc.first.click()
                logger.info("Executor: submit clicked '%s'", txt)
                return
        except Exception:
            continue
    try:
        await page.keyboard.press("Enter")
        logger.info("Executor: submit via Enter")
    except Exception:
        pass


async def execute(page: Page, action: Action) -> ActionResult:
    """Perform an action. No reasoning here, just mechanical execution."""
    t = action.type

    if t in ("wait", "ask_user", "done", "error"):
        return ActionResult(success=True, note=f"no-op for {t}")

    if t == "navigate":
        if not action.target:
            return ActionResult(success=False, error="navigate requires target URL")
        try:
            await page.goto(action.target, wait_until="domcontentloaded", timeout=30000)
            await asyncio.sleep(2)
            return ActionResult(success=True)
        except Exception as e:
            return ActionResult(success=False, error=f"navigation failed: {e}")

    if t == "click":
        ok = await _try_click_text(page, action.target or "")
        if not ok:
            return ActionResult(success=False, error=f"could not find clickable '{action.target}'")
        return ActionResult(success=True)

    if t == "fill":
        if action.value is None:
            return ActionResult(success=False, error="fill requires value")
        ok = await _try_fill_label(page, action.target or "", action.value)
        if not ok:
            return ActionResult(success=False, error=f"could not find input '{action.target}'")
        if action.submit:
            await _press_submit(page)
        return ActionResult(success=True)

    if t == "press":
        try:
            await page.keyboard.press(action.target or "Enter")
            return ActionResult(success=True)
        except Exception as e:
            return ActionResult(success=False, error=f"press failed: {e}")

    return ActionResult(success=False, error=f"unknown action type: {t}")
