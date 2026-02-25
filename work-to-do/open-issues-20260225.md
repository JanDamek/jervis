# Otevřené issues — konsolidovaný přehled 2026-02-25

**Datum:** 2026-02-25
**Typ:** SOUHRN

---

## A. Orchestrátor

### ~~A4. Notifikace selhání úlohy jsou nepoužitelné (FIXED)~~

Opraveno v commitu `3d61d6d` — error mode, lidské labels, retry/discard tlačítka.

### ~~A5. ImportError: `_TIER_INDEX` crash v background handleru (FIXED)~~

Nahrazeno `clamp_tier()` z `provider.py` — nasazeno.

### A2. Halucinované Jira issues v brain projektu (WON'T FIX)

IDLE_REVIEW vytvořil Jira issues JI-1 až JI-15 na základě halucinovaného termínu "Invalid traceId".
Rate limiting pro search tools je opravený (max 3× za task).

**Rozhodnutí**: JI je interní Jira projekt pro orchestrátor (brain) — orchestrátor si ho spravuje sám.
Issues JI-1–JI-15 se nebudou ručně mazat.

### ~~A3. Ollama tool_calls parsing (ACCEPTED — trvalý pattern)~~

Ollama + litellm nevrací tool_calls ve standardním formátu — model vrací JSON v textu odpovědi.
Sdílený `extract_tool_calls()` z `ollama_parsing.py` parsuje 3 formáty (nativní, JSON-in-content, markdown block).
Používá se ve 4 handlerech, performance dopad nulový (~ms vs. sekundy LLM).

**Rozhodnutí**: Trvalý architekturální pattern pro lokální Ollama stack, není issue.
Velikost modelu (30B max na P40) ani verze Ollama to nezmění.

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

1. ~~**A2**~~ — WON'T FIX (JI je interní Jira orchestrátoru)
2. ~~**A3**~~ — ACCEPTED (trvalý pattern, není issue)
3. ~~**A4**~~ — FIXED
4. ~~**A5**~~ — FIXED
5. ~~**C1**~~ — FIXED
6. ~~**D1**~~ — FIXED
