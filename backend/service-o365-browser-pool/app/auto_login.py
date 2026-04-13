"""Auto-login for Microsoft 365 via Playwright.

Automates the Microsoft login flow:
1. Enter email on login.microsoftonline.com
2. Handle "Pick an account" page (select existing account)
3. Enter password
4. Handle MFA if required (detect type, notify user, wait for code)
5. Handle "Stay signed in?" prompt
6. Handle consent / permissions page
7. Wait for Teams/Outlook to fully load
8. Detect successful login (Teams/Outlook loaded)

Supports:
- Password-only login
- Password + Authenticator app (number matching or code)
- Password + SMS code
- "Use another method" selection
- Consumer (*.live.com) and business (*.office.com) accounts
"""

from __future__ import annotations

import asyncio
import logging
import time
from enum import Enum

from playwright.async_api import Page

logger = logging.getLogger("o365-browser-pool.auto-login")


class LoginStage(str, Enum):
    """Current stage of the login flow."""
    NAVIGATING = "navigating"
    EMAIL_ENTRY = "email_entry"
    PICK_ACCOUNT = "pick_account"
    PASSWORD_ENTRY = "password_entry"
    MFA_REQUIRED = "mfa_required"
    STAY_SIGNED_IN = "stay_signed_in"
    CONSENT = "consent"
    ORG_INFO = "org_info"  # "Access to X is monitored" page
    TEAMS_LOADING = "teams_loading"
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


# Domains that indicate successful login
_LOGGED_IN_DOMAINS = [
    "teams.cloud.microsoft",
    "teams.microsoft.com/v2",
    "teams.microsoft.com/_",
    "teams.live.com",
    "outlook.office.com",
    "outlook.office365.com",
    "outlook.live.com",
]

# Teams loading indicators (app loaded but still initializing)
_TEAMS_LOADING_PATTERNS = [
    "teams.cloud.microsoft",
    "teams.microsoft.com",
    "teams.live.com",
]


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
        # ── FIDO/Passkey bypass ──
        # Microsoft consumer accounts redirect to login.microsoft.com/consumers/fido/*
        # which hangs in headless Chromium (no real authenticator hardware).
        #
        # Strategy 1: Override navigator.credentials.get to reject immediately
        # Strategy 2: Intercept FIDO navigation at network level and abort it
        try:
            await page.context.add_init_script("""
                // Override WebAuthn credentials API to force fallback to password
                if (navigator.credentials) {
                    const origGet = navigator.credentials.get.bind(navigator.credentials);
                    navigator.credentials.get = function(options) {
                        if (options && options.publicKey) {
                            console.log('[jervis] FIDO credentials.get intercepted — rejecting');
                            return Promise.reject(new DOMException('No authenticator available', 'NotAllowedError'));
                        }
                        return origGet(options);
                    };
                    navigator.credentials.create = function(options) {
                        if (options && options.publicKey) {
                            console.log('[jervis] FIDO credentials.create intercepted — rejecting');
                            return Promise.reject(new DOMException('No authenticator available', 'NotAllowedError'));
                        }
                        return origGet(options);
                    };
                }
            """)
            logger.info("Auto-login: FIDO/WebAuthn bypass script injected")
        except Exception as e:
            logger.warning("Auto-login: failed to inject FIDO bypass: %s", e)

        # No FIDO route interception — let the page load.
        # The init script above rejects navigator.credentials.get, which should
        # trigger the FIDO page's fallback UI.

        # Navigate to login page — use domcontentloaded (networkidle too slow for Teams)
        logger.info("Auto-login: navigating to %s", login_url)
        try:
            await page.goto(login_url, wait_until="domcontentloaded", timeout=30000)
        except Exception:
            logger.warning("Auto-login: navigation timeout, continuing...")
        await asyncio.sleep(3)

        # Track FIDO retries
        _fido_retry_count = 0

        # Main login loop — handles all stages sequentially
        max_iterations = 25
        for iteration in range(max_iterations):
            stage = await _detect_stage(page)
            logger.info("Auto-login: iteration %d, stage = %s, url = %s",
                        iteration, stage, page.url[:80])

            if stage == LoginStage.LOGGED_IN:
                # Verify it's not a brief redirect
                if iteration == 0:
                    logger.info("Auto-login: LOGGED_IN on first check, verifying...")
                    await asyncio.sleep(5)
                    stage = await _detect_stage(page)
                    if stage == LoginStage.LOGGED_IN:
                        return LoginResult(stage=LoginStage.LOGGED_IN)
                    continue
                return LoginResult(stage=LoginStage.LOGGED_IN)

            if stage == LoginStage.TEAMS_LOADING:
                logger.info("Auto-login: Teams is loading, waiting...")
                await asyncio.sleep(5)
                continue

            if stage == LoginStage.EMAIL_ENTRY:
                result = await _enter_email(page, username)
                if result.stage == LoginStage.ERROR:
                    return result
                await asyncio.sleep(3)
                continue

            if stage == LoginStage.PICK_ACCOUNT:
                result = await _pick_account(page, username)
                if result.stage == LoginStage.ERROR:
                    return result
                await asyncio.sleep(3)
                continue

            if stage == LoginStage.PASSWORD_ENTRY:
                result = await _enter_password(page, password)
                if result.stage == LoginStage.ERROR:
                    return result
                await asyncio.sleep(5)
                continue

            if stage == LoginStage.MFA_REQUIRED:
                return await _detect_mfa_type(page)

            if stage == LoginStage.STAY_SIGNED_IN:
                await _handle_stay_signed_in(page)
                await asyncio.sleep(5)
                continue

            if stage == LoginStage.CONSENT:
                await _handle_consent(page)
                await asyncio.sleep(3)
                continue

            if stage == LoginStage.ORG_INFO:
                await _handle_org_info(page)
                await asyncio.sleep(5)
                continue

            if stage == LoginStage.NAVIGATING:
                current_url = page.url or ""

                # Chrome error page (e.g. from blocked navigation)
                if "chrome-error://" in current_url:
                    logger.info("Auto-login: chrome error page, going back...")
                    try:
                        await page.go_back(wait_until="domcontentloaded", timeout=10000)
                    except Exception:
                        # If go_back fails, navigate to login URL
                        try:
                            await page.goto(login_url, wait_until="domcontentloaded", timeout=30000)
                        except Exception:
                            pass
                    await asyncio.sleep(3)
                    continue

                # FIDO/passkey page — wait for credentials.get rejection to trigger fallback
                if "login.microsoft.com" in current_url and "fido" in current_url:
                    _fido_retry_count += 1
                    logger.info(
                        "Auto-login: FIDO page (attempt %d), url=%s",
                        _fido_retry_count, current_url[:100],
                    )

                    if _fido_retry_count <= 3:
                        # Wait for credentials.get rejection to trigger fallback UI
                        await asyncio.sleep(5)

                        # The FIDO page shows "We couldn't sign you in" with
                        # "Sign in another way" link (id=idA_PWD_SwitchToCredPicker)
                        # Click it to get to password/credential picker
                        switch_link = await _find_element(page, [
                            '#idA_PWD_SwitchToCredPicker',
                            'a:has-text("Sign in another way")',
                            'a:has-text("Jiný způsob přihlášení")',
                            'a:has-text("Other ways to sign in")',
                        ])
                        if switch_link:
                            logger.info("Auto-login: clicking 'Sign in another way' on FIDO page")
                            await switch_link.click()
                            await asyncio.sleep(3)

                            # After clicking, look for password option in credential picker
                            pwd_option = await _find_element(page, [
                                '#idA_PWD_SwitchToPassword',
                                'div[data-value="pwd"]',
                                'div[data-testid="password"]',
                                'a:has-text("Use your password")',
                                'a:has-text("Use a password")',
                                'a:has-text("Password")',
                                'a:has-text("Heslo")',
                                'a:has-text("Použít heslo")',
                                'div:has-text("Password"):not(:has(div))',
                                'div:has-text("Enter password"):not(:has(div))',
                            ])
                            if pwd_option:
                                logger.info("Auto-login: clicking password option in credential picker")
                                await pwd_option.click()
                                await asyncio.sleep(3)
                            else:
                                # Log what credential picker shows
                                try:
                                    page_info = await page.evaluate("""
                                        (() => ({
                                            text: document.body?.innerText?.substring(0, 500),
                                            links: Array.from(document.querySelectorAll('a')).map(a => ({
                                                text: a.textContent?.trim()?.substring(0, 50),
                                                id: a.id,
                                                visible: a.offsetWidth > 0 && a.offsetHeight > 0
                                            })),
                                            divs: Array.from(document.querySelectorAll('div[data-value]')).map(d => ({
                                                text: d.textContent?.trim()?.substring(0, 50),
                                                value: d.getAttribute('data-value'),
                                                visible: d.offsetWidth > 0 && d.offsetHeight > 0
                                            })),
                                        }))()
                                    """)
                                    logger.info(
                                        "Auto-login: credential picker text: %s",
                                        (page_info.get("text") or "")[:300],
                                    )
                                    logger.info(
                                        "Auto-login: credential picker links: %s",
                                        page_info.get("links", []),
                                    )
                                    logger.info(
                                        "Auto-login: credential picker divs: %s",
                                        page_info.get("divs", []),
                                    )
                                except Exception:
                                    pass
                        else:
                            # No "Sign in another way" link — try back button
                            back_btn = await _find_element(page, [
                                '#idBtn_Back',
                            ])
                            if back_btn:
                                logger.info("Auto-login: clicking Back on FIDO page")
                                await back_btn.click()
                                await asyncio.sleep(3)
                        continue

                    if _fido_retry_count == 4:
                        # Try going back
                        logger.info("Auto-login: FIDO stuck, going back...")
                        try:
                            await page.go_back(wait_until="domcontentloaded", timeout=10000)
                            await asyncio.sleep(5)
                        except Exception:
                            pass
                        continue

                    if _fido_retry_count >= 5:
                        return LoginResult(
                            stage=LoginStage.ERROR,
                            error="FIDO/passkey page stuck — cannot bypass. "
                                  "Disable passkey in Microsoft account security settings, "
                                  "or use a business account.",
                        )
                        continue

                # Stuck on login.live.com/oauth20_authorize with no UI
                if "login.live.com" in current_url and "oauth20_authorize" in current_url:
                    _fido_retry_count += 1
                    logger.info(
                        "Auto-login: stuck on oauth20_authorize (attempt %d), checking page...",
                        _fido_retry_count,
                    )

                    # Debug: log page text to understand what we're looking at
                    try:
                        body_text = await page.text_content("body") or ""
                        logger.info(
                            "Auto-login: oauth page body (%d chars): %s",
                            len(body_text), body_text[:300].replace("\n", " "),
                        )
                    except Exception:
                        pass

                    # Maybe the page has login inputs but _detect_stage missed them
                    # (e.g., login.live.com page with a different layout)
                    email_input = await _find_element(page, [
                        'input[type="email"]',
                        'input[name="loginfmt"]',
                        'input[name="login"]',
                    ])
                    if email_input:
                        logger.info("Auto-login: found email input on oauth page!")
                        # This is actually an email entry page
                        result = await _enter_email(page, username)
                        await asyncio.sleep(3)
                        continue

                    password_input = await _find_element(page, [
                        'input[type="password"]',
                        'input[name="passwd"]',
                    ])
                    if password_input:
                        logger.info("Auto-login: found password input on oauth page!")
                        result = await _enter_password(page, password)
                        await asyncio.sleep(3)
                        continue

                    if _fido_retry_count >= 6:
                        # Try clearing cookies and going direct to teams.microsoft.com
                        await page.context.clear_cookies()
                        try:
                            await page.goto(
                                login_url,
                                wait_until="domcontentloaded",
                                timeout=30000,
                            )
                        except Exception:
                            pass
                        await asyncio.sleep(3)
                        continue

                    await asyncio.sleep(3)
                    continue

                # Generic: check for "Sign in another way" on any login page
                # (passkey error on microsoftonline.com shows this link)
                if "login" in current_url:
                    switch_link = await _find_element(page, [
                        '#idA_PWD_SwitchToCredPicker',
                        'a:has-text("Sign in another way")',
                        'a:has-text("Jiný způsob přihlášení")',
                    ])
                    if switch_link:
                        _fido_retry_count += 1
                        logger.info(
                            "Auto-login: found 'Sign in another way' on login page "
                            "(attempt %d)", _fido_retry_count,
                        )
                        await switch_link.click()
                        await asyncio.sleep(3)

                        # Look for password option in credential picker
                        pwd_option = await _find_element(page, [
                            '#idA_PWD_SwitchToPassword',
                            'div[data-value="pwd"]',
                            'div[data-testid="password"]',
                            'a:has-text("Use your password")',
                            'a:has-text("Use a password")',
                            'a:has-text("Password")',
                            'a:has-text("Heslo")',
                            'div:has-text("Password"):not(:has(div))',
                        ])
                        if pwd_option:
                            logger.info("Auto-login: clicking password option")
                            await pwd_option.click()
                            await asyncio.sleep(3)
                        continue

                await asyncio.sleep(3)
                continue

            if stage == LoginStage.ERROR:
                error_text = await _get_error_text(page)
                return LoginResult(
                    stage=LoginStage.ERROR,
                    error=f"Login error: {error_text}",
                )

        # Exhausted iterations — check final state
        final_stage = await _detect_stage(page)
        if final_stage == LoginStage.LOGGED_IN:
            return LoginResult(stage=LoginStage.LOGGED_IN)

        return LoginResult(
            stage=LoginStage.ERROR,
            error=f"Login did not complete after {max_iterations} iterations "
                  f"(last stage: {final_stage}, url: {page.url[:100]})",
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
        await asyncio.sleep(1.5)

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


async def poll_mfa_approval(page: Page, timeout_seconds: int = 120) -> LoginResult:
    """Poll page for MFA approval completion (authenticator_number type).

    When the user approves in Microsoft Authenticator, the login page
    auto-transitions. We poll every 3s to detect the transition.
    Returns LOGGED_IN on success, ERROR on timeout or failure.
    """
    start = time.time()
    logger.info("Polling for MFA approval (timeout=%ds)", timeout_seconds)

    while time.time() - start < timeout_seconds:
        try:
            url = page.url or ""
            stage = await _detect_stage(page)
            elapsed = int(time.time() - start)
            logger.info("MFA poll: stage=%s url=%s elapsed=%ds", stage.value, url[:80], elapsed)

            if stage == LoginStage.STAY_SIGNED_IN:
                await _handle_stay_signed_in(page)
                logger.info("MFA approved — login complete")
                return LoginResult(stage=LoginStage.LOGGED_IN)

            if stage == LoginStage.ORG_INFO:
                await _handle_org_info(page)
                logger.info("MFA approved — org info page handled")
                # After clicking Continue, Teams will load
                await asyncio.sleep(5)
                continue

            if stage == LoginStage.LOGGED_IN:
                logger.info("MFA approved — login complete")
                return LoginResult(stage=LoginStage.LOGGED_IN)

            if stage == LoginStage.ERROR:
                error_text = await _get_error_text(page)
                logger.warning("MFA approval failed: %s", error_text)
                return LoginResult(
                    stage=LoginStage.ERROR,
                    error=f"MFA approval failed: {error_text}",
                )
        except Exception as e:
            logger.warning("MFA polling error: %s", e)

        await asyncio.sleep(3)

    logger.warning("MFA approval timed out after %ds", timeout_seconds)
    return LoginResult(
        stage=LoginStage.ERROR,
        error="MFA approval timeout — user did not approve in time",
    )


# ─── Internal helpers ───


async def _detect_stage(page: Page) -> LoginStage:
    """Detect current login stage from page URL and DOM content."""
    url = page.url or ""

    # Org info page ("Access to X is monitored") — can appear on ANY domain
    # including MCAS proxy (*.mcas.ms). Must check before domain-specific logic.
    if "mcas.ms" in url or "access" in url:
        # MCAS proxy page — check for Continue link or monitored text
        try:
            body_text = await page.text_content("body", timeout=2000) or ""
            if "Continue to Microsoft Teams" in body_text or "is monitored" in body_text:
                logger.info("ORG_INFO detected via body text on %s", url[:60])
                return LoginStage.ORG_INFO
        except Exception:
            pass
    org_info = await _find_element(page, [
        'a:has-text("Continue to Microsoft Teams")',
        'a:has-text("Pokračovat na Microsoft Teams")',
    ], timeout_ms=500)
    if org_info:
        return LoginStage.ORG_INFO

    # On Teams/Outlook domain — check if actually loaded (not just URL redirect)
    is_on_teams = any(d in url for d in _LOGGED_IN_DOMAINS)
    is_on_teams_domain = any(p in url for p in _TEAMS_LOADING_PATTERNS) and "login" not in url

    if is_on_teams or is_on_teams_domain:
        # MUST verify with DOM elements — URL alone is not reliable!
        # Teams SPA may briefly show /v2/ URL before redirecting to login.
        app_loaded = await _find_element(page, [
            '[data-tid="app-layout"]',          # Teams v2 app container
            '[data-app-section="main"]',         # Teams main section
            'button[aria-label*="Chat"]',        # Chat nav button
            'button[aria-label*="Chaty"]',       # Chat nav button (Czech)
            'button[aria-label*="Teams"]',       # Teams nav button
            'button[aria-label*="Activity"]',    # Activity nav button
            'button[aria-label*="Aktivita"]',    # Activity nav button (Czech)
            'div[data-tid="app-bar"]',           # Navigation bar
            '#app-bar-container',                # Nav bar container
        ])
        if app_loaded:
            return LoginStage.LOGGED_IN
        # Teams domain but not loaded yet — could be loading OR about to redirect
        return LoginStage.TEAMS_LOADING

    # Microsoft login page?
    if "login.microsoftonline.com" in url or "login.live.com" in url:
        # Check for "Pick an account" page (multiple accounts listed)
        pick_account = await _find_element(page, [
            '#tilesHolder',                              # Account tiles container
            'div[data-test-id="accountList"]',           # Account list
            '.table[role="presentation"]',               # Account table
            'div:has-text("Pick an account")',
            'div:has-text("Vyberte účet")',
        ])
        if pick_account:
            return LoginStage.PICK_ACCOUNT

        # Microsoft login pages often have BOTH email and password inputs in DOM
        # simultaneously, with one hidden. Use a page title / heading heuristic
        # first, then fall back to element detection with short timeouts.
        page_text = ""
        try:
            page_text = await page.text_content("body") or ""
        except Exception:
            pass

        # Heading-based detection (most reliable — Microsoft shows different headings)
        has_password_heading = "Enter password" in page_text or "Zadejte heslo" in page_text
        has_email_heading = "Sign in" in page_text and ("email" in page_text.lower() or "phone" in page_text.lower())

        if has_password_heading:
            password_input = await _find_element(page, [
                'input[type="password"]',
                'input[name="passwd"]',
            ], timeout_ms=2000)
            if password_input:
                return LoginStage.PASSWORD_ENTRY

        if has_email_heading:
            email_input = await _find_element(page, [
                'input[type="email"]:not([aria-hidden="true"])',
                'input[name="loginfmt"]:not([aria-hidden="true"])',
            ], timeout_ms=2000)
            if email_input:
                return LoginStage.EMAIL_ENTRY

        # Fallback: try both with short timeout (password first)
        password_input = await _find_element(page, [
            'input[type="password"]:not([aria-hidden="true"])',
            'input[name="passwd"]:not([aria-hidden="true"])',
        ], timeout_ms=1000)
        if password_input:
            return LoginStage.PASSWORD_ENTRY

        email_input = await _find_element(page, [
            'input[type="email"]:not([aria-hidden="true"])',
            'input[name="loginfmt"]:not([aria-hidden="true"])',
        ], timeout_ms=1000)
        if email_input:
            return LoginStage.EMAIL_ENTRY

        # Check for MFA
        mfa_element = await _find_element(page, [
            'input[name="otc"]',
            'input[id="idTxtBx_SAOTCC_OTC"]',
            '#idDiv_SAOTCC_Description',
            '#idDiv_SAOTCAS_Description',
            'div[data-bind*="authenticator"]',
            '#idRichContext_DisplaySign',              # Number matching
            'div:has-text("Approve sign in request")',
            'div:has-text("Schvalte žádost")',
        ])
        if mfa_element:
            return LoginStage.MFA_REQUIRED

        # Check for "Stay signed in?" (text + Yes/No buttons)
        stay_text = await _find_element(page, [
            '#KmsiBanner',                             # KMSI banner
            'div:has-text("Stay signed in?")',
            'div:has-text("Zůstat přihlášeni?")',
        ])
        stay_btn = await _find_element(page, [
            'input[id="idSIButton9"]',
            'input[value="Yes"]',
            'button:has-text("Yes")',
            'button:has-text("Ano")',
        ])
        if stay_text or stay_btn:
            return LoginStage.STAY_SIGNED_IN

        # Check for consent / permissions page
        consent = await _find_element(page, [
            'input[value="Accept"]',
            'input[value="Přijmout"]',
            'button:has-text("Accept")',
            'button:has-text("Přijmout")',
            '#idBtn_Accept',
            'div:has-text("Permissions requested")',
            'div:has-text("Požadovaná oprávnění")',
        ])
        if consent:
            return LoginStage.CONSENT

        # Check for error messages
        error_el = await _find_element(page, [
            '#usernameError',
            '#passwordError',
            '.alert-error',
            '#service_exception_message',
            'div[role="alert"]',
        ])
        if error_el:
            text = await error_el.text_content() or ""
            # Passkey/FIDO errors are expected — the init script rejects
            # navigator.credentials.get, causing this error. The page should
            # have "Sign in another way" link. Don't treat as fatal error.
            if "passkey" in text.lower() or "fido" in text.lower():
                logger.info(
                    "Login: passkey error detected (expected): %s", text[:100],
                )
                # Check for "Sign in another way" on the same page
                switch_link = await _find_element(page, [
                    '#idA_PWD_SwitchToCredPicker',
                    'a:has-text("Sign in another way")',
                    'a:has-text("Jiný způsob přihlášení")',
                ])
                if switch_link:
                    return LoginStage.NAVIGATING  # Will be handled by FIDO handler
                return LoginStage.NAVIGATING
            logger.warning("Login error detected: %s", text)
            return LoginStage.ERROR

    # FIDO/passkey page (consumer accounts) — iframe-based, can't interact directly
    # The login loop handles this: after 3 iterations, it goes back and retries
    if "login.microsoft.com" in url and "fido" in url:
        # Check if password input magically appeared (rare, but possible)
        pwd_input = await _find_element(page, [
            'input[type="password"]',
            'input[name="passwd"]',
        ])
        if pwd_input:
            return LoginStage.PASSWORD_ENTRY

        # FIDO page is iframe-based — treat as NAVIGATING (loop will handle bypass)
        return LoginStage.NAVIGATING

    # On microsoftonline.com but might be a redirect page
    if "microsoftonline.com" in url or "login.microsoft.com" in url:
        return LoginStage.NAVIGATING

    return LoginStage.NAVIGATING


async def _enter_email(page: Page, email: str) -> LoginResult:
    """Enter email address and submit."""
    logger.info("Auto-login: entering email %s", email)

    # Microsoft login has a hidden duplicate input (tabindex=-1, aria-hidden=true)
    # that Playwright can find but can't click (footer div intercepts pointer).
    # Use :visible pseudo-class or filter by tabindex to get the real input.
    email_input = await _find_element(page, [
        'input[type="email"]:not([aria-hidden="true"])',
        'input[name="loginfmt"]:not([aria-hidden="true"])',
        'input[type="email"]',
        'input[name="loginfmt"]',
    ])
    if not email_input:
        return LoginResult(
            stage=LoginStage.ERROR,
            error="Email input not found on login page",
        )

    # Use fill() directly — avoids click() which can fail when footer overlaps
    await email_input.fill(email)
    await asyncio.sleep(1.5)

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


async def _pick_account(page: Page, username: str) -> LoginResult:
    """Handle 'Pick an account' page — select the matching account or use another."""
    logger.info("Auto-login: 'Pick an account' page, looking for %s", username)

    # Try to click the account tile matching the username
    # Account tiles contain the email address as text
    try:
        # Try data-test-id first (most reliable)
        tile = page.locator(f'div[data-test-id="{username}"]').first
        if await tile.is_visible(timeout=1000):
            await tile.click()
            logger.info("Auto-login: clicked account tile for %s", username)
            await asyncio.sleep(3)
            return LoginResult(stage=await _detect_stage(page))
    except Exception:
        pass

    # Try to find the account by text content
    try:
        # Look for small text elements containing the email
        tiles = page.locator(f'small:has-text("{username}")')
        count = await tiles.count()
        if count > 0:
            await tiles.first.click()
            logger.info("Auto-login: clicked account small text for %s", username)
            await asyncio.sleep(3)
            return LoginResult(stage=await _detect_stage(page))
    except Exception:
        pass

    # Try clicking on any list item that contains the username
    try:
        account_item = page.locator(f'div[role="listitem"]:has-text("{username}")').first
        if await account_item.is_visible(timeout=1000):
            await account_item.click()
            logger.info("Auto-login: clicked account listitem for %s", username)
            await asyncio.sleep(3)
            return LoginResult(stage=await _detect_stage(page))
    except Exception:
        pass

    # Fallback: click "Use another account"
    use_another = await _find_element(page, [
        '#otherTile',
        'div[data-test-id="otherTile"]',
        'div:has-text("Use another account")',
        'div:has-text("Použít jiný účet")',
    ])
    if use_another:
        await use_another.click()
        logger.info("Auto-login: clicked 'Use another account'")
        await asyncio.sleep(3)
        return LoginResult(stage=await _detect_stage(page))

    return LoginResult(
        stage=LoginStage.ERROR,
        error="Could not select account on 'Pick an account' page",
    )


async def _enter_password(page: Page, password: str) -> LoginResult:
    """Enter password and submit."""
    logger.info("Auto-login: entering password")

    password_input = await _find_element(page, [
        'input[type="password"]',
        'input[name="passwd"]',
    ])
    if not password_input:
        # Maybe on FIDO/passkey page or "Use password" link needs clicking
        use_password = await _find_element(page, [
            # Standard "Use password" links
            'a:has-text("Use your password instead")',
            'a:has-text("Use a password instead")',
            'a:has-text("Použít heslo")',
            '#idA_PWD_SwitchToPassword',
            'a[id*="Password"]',
            # FIDO page alternatives
            'a:has-text("Other ways to sign in")',
            'a:has-text("Sign in another way")',
            'a:has-text("Jiný způsob přihlášení")',
            'a:has-text("Try another way")',
            'button:has-text("Other ways to sign in")',
            'button:has-text("Sign in another way")',
            # Back button on FIDO page
            '#backButton',
            'a[id="back"]',
        ])
        if use_password:
            await use_password.click()
            logger.info("Auto-login: clicked alternative sign-in method")
            await asyncio.sleep(3)

            # After clicking "other ways", may need to select "password"
            password_option = await _find_element(page, [
                'div:has-text("Password")',
                'div:has-text("Heslo")',
                'a:has-text("Password")',
                'a:has-text("Heslo")',
                '#idA_PWD_SwitchToPassword',
                'a:has-text("Use your password instead")',
                'a:has-text("Use a password instead")',
            ])
            if password_option:
                await password_option.click()
                logger.info("Auto-login: selected password option")
                await asyncio.sleep(2)

            password_input = await _find_element(page, [
                'input[type="password"]',
                'input[name="passwd"]',
            ])

    if not password_input:
        # Debug: log page content to understand the current page
        try:
            page_text = await page.text_content("body") or ""
            logger.warning(
                "Auto-login: password input not found. URL=%s, body_preview=%s",
                page.url[:100], page_text[:500].replace("\n", " "),
            )
        except Exception:
            pass
        return LoginResult(
            stage=LoginStage.ERROR,
            error="Password input not found",
        )

    await password_input.fill(password)
    await asyncio.sleep(1.5)

    # Click Sign in
    sign_in_btn = await _find_element(page, [
        'input[id="idSIButton9"]',
        'input[type="submit"]',
        'button:has-text("Sign in")',
        'button:has-text("Přihlásit")',
        'button:has-text("Přihlásit se")',
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

    # Unknown MFA type
    logger.warning("Auto-login: unknown MFA type")
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
        # Fallback: press Enter (usually selects the primary button)
        await page.keyboard.press("Enter")
        await asyncio.sleep(3)


async def _handle_consent(page: Page) -> None:
    """Accept consent / permissions page."""
    logger.info("Auto-login: handling consent/permissions page")
    accept_btn = await _find_element(page, [
        '#idBtn_Accept',
        'input[value="Accept"]',
        'input[value="Přijmout"]',
        'button:has-text("Accept")',
        'button:has-text("Přijmout")',
        'input[type="submit"]',
    ])
    if accept_btn:
        await accept_btn.click()
        await asyncio.sleep(3)
    else:
        await page.keyboard.press("Enter")
        await asyncio.sleep(3)


async def _handle_org_info(page: Page) -> None:
    """Handle organizational info page ('Access to X is monitored')."""
    logger.info("Auto-login: handling org info page — clicking Continue")
    # Try clicking Continue in all frames (MCAS page uses iframes)
    clicked = False
    for frame in page.frames:
        try:
            result = await frame.evaluate("""() => {
                const els = document.querySelectorAll('a, button, input[type="submit"], span');
                for (const el of els) {
                    if (el.textContent && el.textContent.includes('Continue to Microsoft')) {
                        el.click();
                        return 'clicked: ' + el.tagName + ' ' + el.textContent.trim().substring(0, 50);
                    }
                }
                // Try any element with Continue text
                for (const el of els) {
                    if (el.textContent && el.textContent.includes('Continue')) {
                        el.click();
                        return 'clicked: ' + el.tagName + ' ' + el.textContent.trim().substring(0, 50);
                    }
                }
                return null;
            }""")
            if result:
                logger.info("Auto-login: %s (frame: %s)", result, frame.url[:60])
                clicked = True
                break
        except Exception:
            pass

    if not clicked:
        # Tab through to Continue link and press Enter
        logger.info("Auto-login: Continue not found in frames, trying Tab+Enter")
        for _ in range(5):
            await page.keyboard.press("Tab")
            await asyncio.sleep(0.3)
        await page.keyboard.press("Enter")

    await asyncio.sleep(5)


async def _get_error_text(page: Page) -> str:
    """Extract error message text from login page."""
    for selector in [
        '#usernameError',
        '#passwordError',
        '.alert-error',
        '#service_exception_message',
        'div[role="alert"]',
    ]:
        try:
            el = page.locator(selector).first
            if await el.is_visible(timeout=500):
                return await el.text_content() or "Unknown error"
        except Exception:
            continue
    return "Unknown error"


async def _find_element(page: Page, selectors: list[str], timeout_ms: int = 3000):
    """Try multiple selectors, return first match or None."""
    for selector in selectors:
        try:
            el = page.locator(selector).first
            if await el.is_visible(timeout=timeout_ms):
                return el
        except Exception:
            continue
    return None
