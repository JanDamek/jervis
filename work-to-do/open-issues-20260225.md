# Otevřené issues — konsolidovaný přehled 2026-02-25

**Datum:** 2026-02-25
**Typ:** SOUHRN

---

## A. Orchestrátor

### A4. Notifikace selhání úlohy jsou nepoužitelné (HIGH)

Dialog "Úloha vyžaduje odpověď" zobrazí jen `"Úloha na pozadí selhala: MEETING_PROCESSING"` — bez
kontextu co se stalo, bez error detailu, bez akčních tlačítek. Push notifikace stejně neužitečná.
Uživatel nemůže ani odpovědět, ani schválit, ani retry.

5 dílčích bugů: chybí error message z Pythonu, špatný dialog mode, chybí lidské názvy task typů,
push text nerozlišuje error/approval/clarification, chybí Retry/Zahodit tlačítka.

**Detail:** `work-to-do/notification-quality-bug-20260225.md`

### A5. ImportError: `_TIER_INDEX` crash v background handleru (FIXED)

`handler.py:60` importoval `_TIER_INDEX` z `handler_agentic.py`, který byl refaktorem přesunut do
`provider.py`. Způsobovalo crash VŠECH background tasků.

**Fix:** Nahrazeno `clamp_tier()` z `provider.py` — už nasazeno.

### A2. Halucinované Jira issues v brain projektu (MEDIUM)

IDLE_REVIEW vytvořil Jira issues JI-1 až JI-15 na základě halucinovaného termínu "Invalid traceId".
Rate limiting pro search tools je opravený (max 3× za task), ale halucinované issues zůstávají v Jira.

**Řešení**: Ručně smazat/uzavřít JI-1 až JI-15 v brain projektu.

### A3. WORKAROUND: Ollama tool_calls parsing (LOW)

`respond.py:282` — Ollama `qwen3-coder-tool:30b` nepodporuje nativní tool_calls, tak se
ručně parsuje JSON z content fieldu. Workaround, ne bug — přehodnotit při upgradu modelu.

**Soubor**: `backend/service-orchestrator/app/graph/nodes/respond.py`

---

## C. Infrastruktura

### C1. Metrics server není nainstalovaný (LOW)

`kubectl top pods` vrací "Metrics API not available". Bez metrics serveru nelze monitorovat
spotřebu CPU/RAM podů.

**Řešení**: Nainstalovat metrics-server do K8s clusteru.

---

## D. Dokumentace

### D1. Epic plán není v repo (MEDIUM)

`docs/epic-plan-autonomous.md` je jen stub ("Kompletní EPIC plán je uložen v Claude chat kontextu").
Plán by měl být verzovaný v repo.

**Řešení**: Zapsat plný EPIC plán z Claude session do souboru.

---

## Priorita

1. **A4** — Notifikace selhání úlohy — nepoužitelné (HIGH)
2. **A2** — Ruční cleanup halucinovaných Jira issues JI-1 až JI-15
3. **D1** — Epic plán do repo
4. **C1** — Metrics server
5. **A3** — Ollama tool_calls workaround (čeká na model upgrade)
6. ~~**A5**~~ — _TIER_INDEX crash (FIXED, nasazeno)
