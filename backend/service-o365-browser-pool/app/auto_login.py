"""Auto-login for Microsoft 365 via Playwright.

Automates the Microsoft login flow:
1. Enter email on login.microsoftonline.com
2. Enter password
3. Handle MFA if required (detect type, notify user, wait for code)
4. Handle "Stay signed in?" prompt
5. Detect successful login (Teams/Outlook loaded)

Supports:
- Password-only login
- Password + Authenticator app (number matching or code)
- Password + SMS code
- "Use another method" selection
"""

from __future__ import annotations

import asyncio
import logging
from enum import Enum

from playwright.async_api import Page

logger = logging.getLogger("o365-browser-pool.auto-login")


class LoginStage(str, Enum):
    """Current stage of the login flow."""
    NAVIGATING = "navigating"
    EMAIL_ENTRY = "email_entry"
    PASSWORD_ENTRY = "password_entry"
    MFA_REQUIRED = "mfa_required"
    STAY_SIGNED_IN = "stay_signed_in"
    LOGGED_IN = "logged_in"
    ERROR = "error"


class MfaType(str, Enum):
    """Type of MFA challenge detected."""
    AUTHENTICATOR_CODE = "authenticator_code"      # Enter 6-digit code from app
    AUTHENTICATOR_NUMBER = "authenticator_number"   # Approve + enter number shown
    SMS_CODE = "sms_code"                           # Enter code sent via SMS
    PHONE_CALL = "phone_call"                       # Approve via phone call
    UNKNOWN = "unknown"


class LoginResult:
    def __init__(
        self,
        stage: LoginStage,
        mfa_type: MfaType | None = None,
        mfa_message: str | None = None,
        mfa_number: str | None = None,
        error: str | None = None,
    ):
        self.stage = stage
        self.mfa_type = mfa_type
        self.mfa_message = mfa_message
        self.mfa_number = mfa_number  # Number to approve in authenticator
        self.error = error


async def auto_login(
    page: Page,
    username: str,
    password: str,
    login_url: str = "https://teams.microsoft.com",
) -> LoginResult:
    """Perform automated Microsoft login.

    Returns LoginResult indicating where the flow stopped:
    - LOGGED_IN: success, Teams loaded
    - MFA_REQUIRED: waiting for MFA code (call submit_mfa_code after)
    - ERROR: login failed
    """
    try:
        # Navigate to login page — use networkidle to wait for redirects
        logger.info("Auto-login: navigating to %s", login_url)
        try:
            await page.goto(login_url, wait_until="networkidle", timeout=30000)
        except Exception:
            # networkidle can timeout on heavy pages — fallback
            logger.warning("Auto-login: networkidle timeout, continuing...")
        await asyncio.sleep(3)

        # Wait for Microsoft login page to load
        stage = await _detect_stage(page)
        logger.info("Auto-login: initial stage = %s", stage)

        # If apparently logged in (from cached URL), wait and verify
        # Teams may briefly show /v2 URL before redirecting to login
        if stage == LoginStage.LOGGED_IN:
            logger.info("Auto-login: LOGGED_IN detected, verifying (wait 5s)...")
            await asyncio.sleep(5)
            stage = await _detect_stage(page)
            logger.info("Auto-login: after verification, stage = %s", stage)
            if stage == LoginStage.LOGGED_IN:
                return LoginResult(stage=LoginStage.LOGGED_IN)

        # Step 1: Enter email
        if stage in (LoginStage.EMAIL_ENTRY, LoginStage.NAVIGATING):
            result = await _enter_email(page, username)
            if result.stage == LoginStage.ERROR:
                return result
            stage = result.stage

        # Step 2: Enter password
        if stage == LoginStage.PASSWORD_ENTRY:
            result = await _enter_password(page, password)
            if result.stage == LoginStage.ERROR:
                return result
            stage = result.stage

        # Step 3: Handle MFA if required
        if stage == LoginStage.MFA_REQUIRED:
            return await _detect_mfa_type(page)

        # Step 4: Handle "Stay signed in?"
        if stage == LoginStage.STAY_SIGNED_IN:
            await _handle_stay_signed_in(page)
            return LoginResult(stage=LoginStage.LOGGED_IN)

        # Check if we ended up logged in
        if stage == LoginStage.LOGGED_IN:
            return LoginResult(stage=LoginStage.LOGGED_IN)

        return LoginResult(
            stage=LoginStage.ERROR,
            error=f"Unexpected stage after login: {stage}",
        )

    except Exception as e:
        logger.error("Auto-login failed: %s", e)
        return LoginResult(stage=LoginStage.ERROR, error=str(e))


async def submit_mfa_code(page: Page, code: str) -> LoginResult:
    """Submit MFA code after auto_login returned MFA_REQUIRED."""
    try:
        # Try various MFA input selectors
        mfa_input = await _find_element(page, [
            'input[name="otc"]',           # One-time code
            'input[id="idTxtBx_SAOTCC_OTC"]',  # Authenticator code
            'input[aria-label*="code"]',
            'input[type="tel"]',           # Numeric code input
            'input[placeholder*="Code"]',
            'input[placeholder*="code"]',
        ])

        if not mfa_input:
            return LoginResult(
                stage=LoginStage.ERROR,
                error="MFA input field not found",
            )

        await mfa_input.fill(code)
        await asyncio.sleep(0.5)

        # Click verify/submit button
        submit_btn = await _find_element(page, [
            'input[id="idSubmit_SAOTCC_Continue"]',
            'input[type="submit"][value*="Verify"]',
            'input[type="submit"][value*="Sign in"]',
            'button:has-text("Verify")',
            'button:has-text("Sign in")',
            'input[type="submit"]',
        ])

        if submit_btn:
            await submit_btn.click()
        else:
            await page.keyboard.press("Enter")

        await asyncio.sleep(5)

        # Check result
        stage = await _detect_stage(page)

        if stage == LoginStage.STAY_SIGNED_IN:
            await _handle_stay_signed_in(page)
            return LoginResult(stage=LoginStage.LOGGED_IN)

        if stage == LoginStage.LOGGED_IN:
            return LoginResult(stage=LoginStage.LOGGED_IN)

        if stage == LoginStage.ERROR:
            return LoginResult(
                stage=LoginStage.ERROR,
                error="MFA verification failed — check the code",
            )

        return LoginResult(stage=stage)

    except Exception as e:
        logger.error("MFA submission failed: %s", e)
        return LoginResult(stage=LoginStage.ERROR, error=str(e))


# ─── Internal helpers ───


async def _detect_stage(page: Page) -> LoginStage:
    """Detect current login stage from page content."""
    url = page.url or ""

    # Already on Teams/Outlook?
    if any(d in url for d in [
        "teams.microsoft.com/v2",
        "teams.live.com",
        "outlook.office.com",
        "outlook.live.com",
    ]):
        return LoginStage.LOGGED_IN

    # Microsoft login page?
    if "login.microsoftonline.com" in url or "login.live.com" in url:
        # Check for email input
        email_input = await _find_element(page, [
            'input[type="email"]',
            'input[name="loginfmt"]',
        ])
        if email_input:
            return LoginStage.EMAIL_ENTRY

        # Check for password input
        password_input = await _find_element(page, [
            'input[type="password"]',
            'input[name="passwd"]',
        ])
        if password_input:
            return LoginStage.PASSWORD_ENTRY

        # Check for MFA
        mfa_element = await _find_element(page, [
            'input[name="otc"]',
            'input[id="idTxtBx_SAOTCC_OTC"]',
            '#idDiv_SAOTCC_Description',
            '#idDiv_SAOTCAS_Description',
            'div[data-bind*="authenticator"]',
            'div:has-text("Approve sign in request")',
        ])
        if mfa_element:
            return LoginStage.MFA_REQUIRED

        # Check for "Stay signed in?"
        stay_btn = await _find_element(page, [
            'input[id="idSIButton9"]',
            'input[value="Yes"]',
            'button:has-text("Yes")',
        ])
        if stay_btn:
            return LoginStage.STAY_SIGNED_IN

        # Check for error messages
        error_el = await _find_element(page, [
            '#usernameError',
            '#passwordError',
            '.alert-error',
            'div[role="alert"]',
        ])
        if error_el:
            text = await error_el.text_content()
            logger.warn("Login error detected: %s", text)
            return LoginStage.ERROR

    return LoginStage.NAVIGATING


async def _enter_email(page: Page, email: str) -> LoginResult:
    """Enter email address and submit."""
    logger.info("Auto-login: entering email %s", email)

    email_input = await _find_element(page, [
        'input[type="email"]',
        'input[name="loginfmt"]',
    ])
    if not email_input:
        return LoginResult(
            stage=LoginStage.ERROR,
            error="Email input not found on login page",
        )

    await email_input.fill(email)
    await asyncio.sleep(0.5)

    # Click Next
    next_btn = await _find_element(page, [
        'input[id="idSIButton9"]',
        'input[type="submit"]',
        'button:has-text("Next")',
        'button:has-text("Další")',
    ])
    if next_btn:
        await next_btn.click()
    else:
        await page.keyboard.press("Enter")

    await asyncio.sleep(3)

    # Detect next stage
    stage = await _detect_stage(page)
    return LoginResult(stage=stage)


async def _enter_password(page: Page, password: str) -> LoginResult:
    """Enter password and submit."""
    logger.info("Auto-login: entering password")

    password_input = await _find_element(page, [
        'input[type="password"]',
        'input[name="passwd"]',
    ])
    if not password_input:
        # Maybe "Use password" link needs to be clicked first
        use_password = await _find_element(page, [
            'a:has-text("Use your password instead")',
            'a:has-text("Použít heslo")',
            '#idA_PWD_SwitchToPassword',
            'a[id*="Password"]',
        ])
        if use_password:
            await use_password.click()
            await asyncio.sleep(2)
            password_input = await _find_element(page, [
                'input[type="password"]',
                'input[name="passwd"]',
            ])

    if not password_input:
        return LoginResult(
            stage=LoginStage.ERROR,
            error="Password input not found",
        )

    await password_input.fill(password)
    await asyncio.sleep(0.5)

    # Click Sign in
    sign_in_btn = await _find_element(page, [
        'input[id="idSIButton9"]',
        'input[type="submit"]',
        'button:has-text("Sign in")',
        'button:has-text("Přihlásit")',
    ])
    if sign_in_btn:
        await sign_in_btn.click()
    else:
        await page.keyboard.press("Enter")

    await asyncio.sleep(5)

    stage = await _detect_stage(page)
    return LoginResult(stage=stage)


async def _detect_mfa_type(page: Page) -> LoginResult:
    """Detect which type of MFA is required."""
    # Check for number matching (Authenticator app shows a number to approve)
    number_el = await _find_element(page, [
        '#idRichContext_DisplaySign',
        'div.display-sign',
        'div[class*="displaySign"]',
    ])
    if number_el:
        number = await number_el.text_content()
        logger.info("Auto-login: MFA number matching — approve %s", number)
        return LoginResult(
            stage=LoginStage.MFA_REQUIRED,
            mfa_type=MfaType.AUTHENTICATOR_NUMBER,
            mfa_message=f"Potvrďte přihlášení v Microsoft Authenticator. Zadejte číslo: {number}",
            mfa_number=number,
        )

    # Check for code input (Authenticator code or SMS)
    code_input = await _find_element(page, [
        'input[name="otc"]',
        'input[id="idTxtBx_SAOTCC_OTC"]',
    ])
    if code_input:
        # Try to determine if SMS or Authenticator
        description = await _find_element(page, [
            '#idDiv_SAOTCC_Description',
            'div[id*="Description"]',
        ])
        desc_text = ""
        if description:
            desc_text = await description.text_content() or ""

        if "text" in desc_text.lower() or "sms" in desc_text.lower():
            mfa_type = MfaType.SMS_CODE
            message = "Zadejte kód z SMS zprávy"
        else:
            mfa_type = MfaType.AUTHENTICATOR_CODE
            message = "Zadejte kód z Microsoft Authenticator"

        logger.info("Auto-login: MFA code required — %s", mfa_type)
        return LoginResult(
            stage=LoginStage.MFA_REQUIRED,
            mfa_type=mfa_type,
            mfa_message=message,
        )

    # Check for phone call approval
    phone_el = await _find_element(page, [
        '#idDiv_SAOTCAS_Description',
        'div:has-text("Approve sign in request")',
        'div:has-text("Schvalte žádost")',
    ])
    if phone_el:
        return LoginResult(
            stage=LoginStage.MFA_REQUIRED,
            mfa_type=MfaType.PHONE_CALL,
            mfa_message="Potvrďte přihlášení na telefonu",
        )

    # Unknown MFA type — take screenshot for VLM analysis
    logger.warning("Auto-login: unknown MFA type, taking screenshot for analysis")
    return LoginResult(
        stage=LoginStage.MFA_REQUIRED,
        mfa_type=MfaType.UNKNOWN,
        mfa_message="Neznámý typ MFA — zkontrolujte přihlášení přes noVNC",
    )


async def _handle_stay_signed_in(page: Page) -> None:
    """Click Yes on 'Stay signed in?' prompt."""
    logger.info("Auto-login: handling 'Stay signed in?' prompt")
    yes_btn = await _find_element(page, [
        'input[id="idSIButton9"]',
        'input[value="Yes"]',
        'button:has-text("Yes")',
        'button:has-text("Ano")',
    ])
    if yes_btn:
        await yes_btn.click()
        await asyncio.sleep(3)
    else:
        # Some pages also have "Don't show this again" checkbox
        await page.keyboard.press("Enter")
        await asyncio.sleep(3)


async def _find_element(page: Page, selectors: list[str]):
    """Try multiple selectors, return first match or None."""
    for selector in selectors:
        try:
            el = page.locator(selector).first
            if await el.is_visible(timeout=500):
                return el
        except Exception:
            continue
    return None
