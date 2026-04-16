"""Generic CSS query helper with shadow-root + same-origin iframe pierce.

Replaces the old app/agent/dom_probe.py semantic walker. This module does
exactly one thing: run a CSS selector against the DOM (walking shadow
roots and same-origin iframes) and return the raw matches. It never
interprets which product concept the matches represent — that is the
agent's job.

Contract (also the tool return shape):

    {
      "matches": [{"text": str, "attrs": {k: v, ...}, "bbox": {x, y, w, h}}, ...],
      "count": int,
      "url": str,
      "truncated": bool,
    }

No regex, no hand-written parsing, no `chat_rows`/`mail_rows`/etc. fields.
"""

from __future__ import annotations

import logging

from playwright.async_api import Page

logger = logging.getLogger("o365-browser-pool.dom_query")


_QUERY_JS = r"""
(params) => {
  const { selector, attrs, text, maxMatches } = params;
  const wantAttrs = Array.isArray(attrs) ? attrs : [];
  const wantText  = text !== false;
  const cap       = Math.max(1, Math.min(Number(maxMatches) || 200, 500));

  function* walk(root) {
    yield root;
    if (!root || !root.querySelectorAll) return;
    for (const el of root.querySelectorAll('*')) {
      if (el.shadowRoot) {
        yield* walk(el.shadowRoot);
      }
      if (el.tagName === 'IFRAME') {
        try {
          const doc = el.contentDocument;
          if (doc) yield* walk(doc);
        } catch (_) { /* cross-origin, skip */ }
      }
    }
  }

  const seen = new Set();
  const out  = [];
  let truncated = false;

  for (const root of walk(document)) {
    if (!root.querySelectorAll) continue;
    let hits;
    try { hits = root.querySelectorAll(selector); }
    catch (_) { continue; }
    for (const el of hits) {
      if (seen.has(el)) continue;
      seen.add(el);

      const entry = { attrs: {} };
      if (wantText) {
        const raw = el.getAttribute?.('aria-label') || el.innerText || el.textContent || '';
        entry.text = String(raw).trim();
      }
      for (const a of wantAttrs) {
        const v = el.getAttribute?.(a);
        entry.attrs[a] = v === null || v === undefined ? null : String(v);
      }
      try {
        const r = el.getBoundingClientRect?.();
        if (r) entry.bbox = { x: Math.round(r.x), y: Math.round(r.y),
                              w: Math.round(r.width), h: Math.round(r.height) };
      } catch (_) {}
      out.push(entry);
      if (out.length >= cap) { truncated = true; break; }
    }
    if (truncated) break;
  }

  return {
    matches: out,
    count: out.length,
    url: location.href,
    truncated,
  };
}
"""


async def query(
    page: Page,
    *,
    selector: str,
    attrs: list[str] | None = None,
    text: bool = True,
    max_matches: int = 200,
) -> dict:
    """Run the scoped CSS query. Returns {matches, count, url, truncated}.

    On any Playwright / evaluation error, returns an empty result dict with
    count=0 — the agent's self-correction rule (§3) escalates to VLM.
    """
    try:
        return await page.evaluate(
            _QUERY_JS,
            {
                "selector": selector,
                "attrs": attrs or [],
                "text": bool(text),
                "maxMatches": int(max_matches),
            },
        )
    except Exception as e:
        logger.warning("inspect_dom failed selector=%r: %s", selector, e)
        return {"matches": [], "count": 0, "url": "", "truncated": False}
