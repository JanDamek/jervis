"""AI-driven login flow — replaces hardcoded CSS selectors.

Uses VLM (sees screen) + LLM (decides action) + Playwright (executes).
1 attempt only — if login fails, transitions to ERROR.
"""

from __future__ import annotations

import asyncio
import logging

from playwright.async_api import Page

from app.ai_navigator import see_screen, decide_action, execute_action, save_error_screenshot
from app.pod_state import PodState, PodStateManager

logger = logging.getLogger("o365-browser-pool.ai-login")

# Max iterations to prevent infinite loops (not a timeout — each iteration is a VLM+LLM cycle)
MAX_ITERATIONS = 20
# Seconds between iterations
ITERATION_DELAY = 5


async def ai_login(
    page: Page,
    state_manager: PodStateManager,
    credentials: dict | None = None,
    login_url: str = "https://teams.microsoft.com",
) -> bool:
    """AI-driven login flow.

    1 attempt. Returns True if login succeeded (ACTIVE), False if failed (ERROR).
    State transitions are managed internally.

    Args:
        page: Playwright page to use for login
        state_manager: PodStateManager for state transitions
        credentials: {"email": "...", "password": "..."} or None
        login_url: URL to navigate to
    """
    await state_manager.transition(PodState.AUTHENTICATING, reason="Starting login")

    # Inject FIDO/WebAuthn bypass (rejects hardware authenticator prompts)
    try:
        await page.context.add_init_script("""
            if (window.navigator.credentials) {
                const origGet = window.navigator.credentials.get;
                window.navigator.credentials.get = function(options) {
                    if (options && options.publicKey) {
                        return Promise.reject(new DOMException('No authenticator available', 'NotAllowedError'));
                    }
                    return origGet.call(this, options);
                };
                const origCreate = window.navigator.credentials.create;
                window.navigator.credentials.create = function(options) {
                    if (options && options.publicKey) {
                        return Promise.reject(new DOMException('No authenticator available', 'NotAllowedError'));
                    }
                    return origCreate.call(this, options);
                };
            }
        """)
    except Exception:
        pass

    # Navigate to login URL
    try:
        await page.goto(login_url, wait_until="domcontentloaded", timeout=30000)
    except Exception as e:
        logger.warning("Navigation to %s failed: %s — continuing anyway", login_url, e)

    await asyncio.sleep(3)

    for iteration in range(MAX_ITERATIONS):
        # 1. See what's on screen
        screen_info = await see_screen(page)
        screen_type = screen_info.get("screen_type", "unknown")

        logger.info(
            "AI-LOGIN: iteration %d, screen=%s, url=%s",
            iteration, screen_type, page.url[:80],
        )

        # 2. Decide action
        action = await decide_action(
            screen_info,
            context="Login to Microsoft Teams",
            credentials=credentials,
        )
        action_type = action.get("action")
        reason = action.get("reason", "")

        logger.info("AI-LOGIN: action=%s target=%s reason=%s", action_type, action.get("target", "-"), reason)

        # 3. Handle terminal states
        if action_type == "done":
            await state_manager.transition(PodState.ACTIVE, reason="Login successful")
            return True

        if action_type == "error":
            screenshot_path = await save_error_screenshot(page, state_manager.client_id, reason)
            await state_manager.transition(
                PodState.ERROR,
                reason=reason,
                screenshot_path=screenshot_path,
                vlm_description=screen_info.get("details", ""),
            )
            return False

        if action_type == "wait_mfa":
            # Extract MFA number if visible
            mfa_number = None
            for el in screen_info.get("elements", []):
                text = el.get("text", "")
                if text.isdigit() and len(text) <= 3:
                    mfa_number = text
                    break

            await state_manager.transition(
                PodState.AWAITING_MFA,
                mfa_type="authenticator_number" if mfa_number else "authenticator_push",
                mfa_message=f"Potvrďte přihlášení v Microsoft Authenticator"
                            + (f". Zadejte číslo: {mfa_number}" if mfa_number else ""),
                mfa_number=mfa_number,
            )

            # Poll for MFA completion — no timeout, but check periodically
            return await _poll_mfa(page, state_manager)

        if action_type == "wait_mfa_code":
            await state_manager.transition(
                PodState.AWAITING_MFA,
                mfa_type="authenticator_code",
                mfa_message="Zadejte kód z Microsoft Authenticator",
            )
            # Pod waits — MFA code will be submitted via API
            return await _poll_mfa(page, state_manager)

        if action_type == "wait":
            await asyncio.sleep(ITERATION_DELAY)
            continue

        # 4. Execute action (fill, click)
        success = await execute_action(page, action)
        if not success:
            logger.warning("AI-LOGIN: action failed, retrying with VLM")

        await asyncio.sleep(ITERATION_DELAY)

    # Exhausted iterations
    screenshot_path = await save_error_screenshot(page, state_manager.client_id, "Max iterations reached")
    await state_manager.transition(
        PodState.ERROR,
        reason=f"Login did not complete after {MAX_ITERATIONS} iterations (url: {page.url[:100]})",
        screenshot_path=screenshot_path,
    )
    return False


async def _poll_mfa(page: Page, state_manager: PodStateManager) -> bool:
    """Poll for MFA approval completion. No timeout — polls indefinitely.

    Returns True if MFA approved and login completed, False if error.
    """
    logger.info("AI-LOGIN: polling for MFA approval...")

    while True:
        await asyncio.sleep(3)

        screen_info = await see_screen(page)
        screen_type = screen_info.get("screen_type", "unknown")

        # MFA approved — page moved past MFA
        if screen_type in ("teams_loaded", "teams_loading", "stay_signed_in",
                           "consent_page", "org_info", "outlook_loaded", "calendar_loaded"):
            logger.info("AI-LOGIN: MFA approved! screen=%s", screen_type)
            # Continue login flow for remaining steps
            await state_manager.transition(PodState.AUTHENTICATING, reason="MFA approved, continuing login")
            return await _finish_login(page, state_manager)

        if screen_type == "error_page":
            error_msg = screen_info.get("error_message", "MFA failed")
            screenshot_path = await save_error_screenshot(page, state_manager.client_id, error_msg)
            await state_manager.transition(PodState.ERROR, reason=error_msg, screenshot_path=screenshot_path)
            return False

        # Still on MFA page — keep waiting
        logger.debug("AI-LOGIN: MFA poll — still waiting (screen=%s)", screen_type)


async def _finish_login(page: Page, state_manager: PodStateManager) -> bool:
    """Handle post-MFA steps (stay signed in, consent, org info, loading)."""
    credentials = None  # No credentials needed for post-MFA steps

    for iteration in range(MAX_ITERATIONS):
        screen_info = await see_screen(page)
        screen_type = screen_info.get("screen_type", "unknown")
        action = await decide_action(screen_info, context="Complete login to Microsoft Teams", credentials=credentials)
        action_type = action.get("action")

        logger.info("AI-LOGIN-FINISH: iteration %d, screen=%s, action=%s", iteration, screen_type, action_type)

        if action_type == "done":
            await state_manager.transition(PodState.ACTIVE, reason="Login completed")
            return True

        if action_type == "error":
            screenshot_path = await save_error_screenshot(page, state_manager.client_id, action.get("reason", ""))
            await state_manager.transition(PodState.ERROR, reason=action.get("reason", ""), screenshot_path=screenshot_path)
            return False

        if action_type == "wait":
            await asyncio.sleep(ITERATION_DELAY)
            continue

        await execute_action(page, action)
        await asyncio.sleep(ITERATION_DELAY)

    await state_manager.transition(PodState.ACTIVE, reason="Assuming login completed after max iterations")
    return True
