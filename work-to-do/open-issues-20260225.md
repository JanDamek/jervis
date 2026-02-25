# Otevřené issues — konsolidovaný přehled 2026-02-25

**Datum:** 2026-02-25
**Typ:** SOUHRN

---

## A. Orchestrátor

### ~~A4. Notifikace selhání úlohy jsou nepoužitelné (FIXED)~~

Opraveno v commitu `3d61d6d` — error mode, lidské labels, retry/discard tlačítka.

### ~~A5. ImportError: `_TIER_INDEX` crash v background handleru (FIXED)~~

Nahrazeno `clamp_tier()` z `provider.py` — nasazeno.

### A2. Halucinované Jira issues v brain projektu (MEDIUM)

IDLE_REVIEW vytvořil Jira issues JI-1 až JI-15 na základě halucinovaného termínu "Invalid traceId".
Rate limiting pro search tools je opravený (max 3× za task), ale halucinované issues zůstávají v Jira.

**Řešení**: Ručně smazat/uzavřít JI-1 až JI-15 v brain projektu.

### A3. WORKAROUND: Ollama tool_calls parsing (LOW)

Respond.py nyní používá sdílený `extract_tool_calls()` z `ollama_parsing.py`.
Workaround zůstává — přehodnotit při upgradu modelu na verzi s nativními tool_calls.

---

## ~~C. Infrastruktura~~

### ~~C1. Metrics server není nainstalovaný (FIXED)~~

Nainstalován `metrics-server` z oficiálního release + `--kubelet-insecure-tls` pro self-signed cluster.
`kubectl top pods/nodes` funguje.

---

## ~~D. Dokumentace~~

### ~~D1. Epic plán není v repo (FIXED)~~

Kompletní EPIC plán zapsán do `docs/epic-plan-autonomous.md` (335 řádků).

---

## Priorita

1. **A2** — Ruční cleanup halucinovaných Jira issues JI-1 až JI-15
2. **A3** — Ollama tool_calls workaround (čeká na model upgrade)
3. ~~**A4**~~ — FIXED
4. ~~**A5**~~ — FIXED
5. ~~**C1**~~ — FIXED
6. ~~**D1**~~ — FIXED
