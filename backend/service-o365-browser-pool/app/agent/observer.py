"""Observer — turns the current page into a canonical Observation.

Strict rule: NEVER classify based on URL. The observer only cares about what
the page actually presents (DOM text, inputs, buttons, headings, errors,
loading indicators). If the DOM gives too few signals, the agent calls VLM
to look at the screenshot and append a verification description.

The Observer never decides what screen this is — that's the Reasoner's job.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Optional

from playwright.async_api import Page

from app.vlm_client import analyze_screenshot

logger = logging.getLogger("o365-browser-pool.agent.observer")


@dataclass
class InputField:
    label: str
    type: str
    value_present: bool
    placeholder: str = ""


@dataclass
class Button:
    text: str
    enabled: bool = True
    role: str = "button"


@dataclass
class Observation:
    """Canonical screen description. No URL-based classification."""
    title: str = ""
    headings: list[str] = field(default_factory=list)
    visible_text: str = ""  # truncated body text
    inputs: list[InputField] = field(default_factory=list)
    buttons: list[Button] = field(default_factory=list)
    links: list[str] = field(default_factory=list)
    error_text: Optional[str] = None
    is_loading: bool = False
    page_ready: bool = False
    # App-shell markers — single boolean per app, derived from data-tid/role markers
    app_shells: list[str] = field(default_factory=list)  # ["teams", "outlook", "calendar"]
    # Free-text VLM description (only present if observer asked VLM)
    vlm_description: Optional[str] = None
    # Diagnostic: how many DOM signals the observer found
    signal_count: int = 0
    # The URL is recorded for navigation/logging only — Reasoner ignores it
    url: str = ""

    def signature(self) -> str:
        """Stable signature used for stuck-detection (changes when screen changes)."""
        parts = [
            self.title.strip().lower(),
            ",".join(sorted(self.headings))[:200],
            ",".join(sorted(i.label.lower() for i in self.inputs)),
            ",".join(sorted(b.text.lower() for b in self.buttons))[:200],
            ",".join(sorted(self.app_shells)),
            "loading" if self.is_loading else "",
            (self.error_text or "")[:80],
        ]
        return "|".join(parts)

    def to_prompt_dict(self) -> dict:
        """Compact dict for LLM prompt (no internal flags)."""
        return {
            "title": self.title,
            "headings": self.headings,
            "visible_text": self.visible_text[:1200],
            "inputs": [{"label": i.label, "type": i.type, "filled": i.value_present,
                         "placeholder": i.placeholder} for i in self.inputs],
            "buttons": [{"text": b.text, "enabled": b.enabled, "role": b.role}
                        for b in self.buttons],
            "links": self.links[:20],
            "error_text": self.error_text,
            "is_loading": self.is_loading,
            "page_ready": self.page_ready,
            "app_shells_visible": self.app_shells,
            "vlm_description": self.vlm_description,
        }


# App-shell markers — these are deterministic indicators that the user is
# already inside the app (not a login/marketing/error page). Used to short-
# circuit reasoning when the user is just using the app.
_APP_SHELL_MARKERS = {
    "teams": [
        '[data-tid="chat-list"]',
        '[data-tid="app-layout"]',
        '[data-tid="app-bar"]',
        '#LeftRail',
    ],
    "outlook": [
        '[data-app-section="MailModule"]',
        'div[role="navigation"][aria-label*="Mail" i]',
    ],
    "calendar": [
        '[data-app-section="Calendar"]',
        'div[role="navigation"][aria-label*="Calendar" i]',
    ],
}

# Threshold below which observer asks VLM to describe the screen
_MIN_SIGNAL_COUNT_FOR_DOM_ONLY = 2


async def _safe(coro, default=None):
    try:
        return await coro
    except Exception:
        return default


async def _collect_text(locator, max_chars: int = 2000) -> str:
    try:
        txt = await locator.first.inner_text(timeout=2000)
        return (txt or "")[:max_chars]
    except Exception:
        return ""


async def _collect_inputs(page: Page) -> list[InputField]:
    out: list[InputField] = []
    try:
        loc = page.locator("input:visible, textarea:visible")
        n = min(await loc.count(), 30)
        for i in range(n):
            el = loc.nth(i)
            try:
                input_type = await el.get_attribute("type") or "text"
                placeholder = (await el.get_attribute("placeholder")) or ""
                aria_label = (await el.get_attribute("aria-label")) or ""
                name_attr = (await el.get_attribute("name")) or ""
                # Try associated <label>
                label_text = ""
                input_id = await el.get_attribute("id")
                if input_id:
                    try:
                        label = page.locator(f'label[for="{input_id}"]')
                        if await label.count() > 0:
                            label_text = (await label.first.inner_text(timeout=500)).strip()
                    except Exception:
                        pass
                label = label_text or aria_label or placeholder or name_attr or input_type
                value = (await el.input_value()) if input_type not in ("submit", "button") else ""
                out.append(InputField(
                    label=label.strip()[:60],
                    type=input_type,
                    value_present=bool(value),
                    placeholder=placeholder.strip()[:60],
                ))
            except Exception:
                continue
    except Exception:
        pass
    return out


async def _collect_buttons(page: Page) -> list[Button]:
    out: list[Button] = []
    seen: set[str] = set()
    try:
        # All clickable elements: buttons, role-buttons, submit inputs,
        # and ALL anchors (MCAS / many MS pages render CTA as plain <a>).
        loc = page.locator(
            'button:visible, '
            '[role="button"]:visible, '
            'input[type="submit"]:visible, '
            'input[type="button"]:visible, '
            'a:visible'
        )
        n = min(await loc.count(), 60)
        for i in range(n):
            el = loc.nth(i)
            try:
                text = (await el.inner_text(timeout=500)).strip()
                if not text:
                    text = (await el.get_attribute("aria-label")) or ""
                text = text.strip()[:80]
                if not text or text.lower() in seen:
                    continue
                seen.add(text.lower())
                disabled = (await el.get_attribute("disabled")) is not None or \
                           (await el.get_attribute("aria-disabled")) == "true"
                role = (await el.get_attribute("role")) or "button"
                out.append(Button(text=text, enabled=not disabled, role=role))
            except Exception:
                continue
    except Exception:
        pass
    return out


async def _collect_headings(page: Page) -> list[str]:
    out: list[str] = []
    try:
        loc = page.locator('h1:visible, h2:visible, [role="heading"]:visible')
        n = min(await loc.count(), 15)
        for i in range(n):
            try:
                t = (await loc.nth(i).inner_text(timeout=500)).strip()
                if t and t not in out:
                    out.append(t[:120])
            except Exception:
                continue
    except Exception:
        pass
    return out


async def _detect_app_shells(page: Page) -> list[str]:
    found: list[str] = []
    for app, selectors in _APP_SHELL_MARKERS.items():
        for sel in selectors:
            try:
                if await page.locator(sel).first.is_visible(timeout=500):
                    found.append(app)
                    break
            except Exception:
                continue
    return found


async def _detect_loading(page: Page) -> bool:
    try:
        # Generic spinner / progressbar
        if await page.locator(
            '[role="progressbar"]:visible, '
            'div[aria-busy="true"]:visible, '
            '.ms-Spinner:visible'
        ).count() > 0:
            return True
    except Exception:
        pass
    return False


async def _detect_error(page: Page) -> Optional[str]:
    try:
        loc = page.locator(
            '[role="alert"]:visible, '
            '#passwordError:visible, '
            '#usernameError:visible, '
            '.alert-error:visible'
        )
        if await loc.count() > 0:
            txt = (await loc.first.inner_text(timeout=500)).strip()
            if txt:
                return txt[:300]
    except Exception:
        pass
    return None


async def _vlm_describe(page: Page) -> Optional[str]:
    """Ask VLM to describe what's visible. Free text, no structured classification."""
    try:
        screenshot = await page.screenshot(type="jpeg", quality=80)
        prompt = (
            "Describe what you see on this screen in 3-6 sentences. "
            "Mention what kind of page it is, what input fields and buttons "
            "are visible, and any prominent text or numbers. "
            "Do NOT speculate about state machine names — just describe."
        )
        text = await analyze_screenshot(
            screenshot, prompt, processing_mode="BACKGROUND",
        )
        return (text or "").strip()[:1200]
    except Exception as e:
        logger.warning("VLM describe failed: %s", e)
        return None


async def observe(page: Page) -> Observation:
    """Build canonical Observation from the current page state."""
    obs = Observation(url=page.url or "")

    try:
        obs.title = (await page.title()) or ""
    except Exception:
        pass

    obs.app_shells = await _detect_app_shells(page)
    obs.headings = await _collect_headings(page)
    obs.inputs = await _collect_inputs(page)
    obs.buttons = await _collect_buttons(page)
    obs.error_text = await _detect_error(page)
    obs.is_loading = await _detect_loading(page)

    # Body text (truncated) — only when no app shell, to keep prompts compact
    if not obs.app_shells:
        try:
            obs.visible_text = await _collect_text(page.locator("body"), max_chars=2000)
        except Exception:
            obs.visible_text = ""

    obs.page_ready = bool(obs.title or obs.headings or obs.app_shells)

    # Diagnostic signal count — used to decide if we need VLM to verify
    obs.signal_count = (
        (1 if obs.app_shells else 0)
        + len(obs.headings)
        + len(obs.inputs)
        + min(len(obs.buttons), 5)
        + (1 if obs.error_text else 0)
    )

    # If we are inside an app shell, DOM is enough — no VLM cost needed.
    if obs.app_shells:
        return obs

    # Outside an app shell (login flow, MFA, MCAS notice, errors, unknown
    # screens) — always have VLM describe what's visible. The agent should
    # never have to guess; cheap VLM call < user-visible failure.
    logger.info(
        "Observer: outside app shell (signals=%d, buttons=%d) — VLM describes",
        obs.signal_count, len(obs.buttons),
    )
    obs.vlm_description = await _vlm_describe(page)
    return obs
