# Feature: Chat kontext — hloubka paměti a strategie vzpomínání

**Priority**: MEDIUM
**Area**: Orchestrator → `app/chat/context.py`

## Problem

Chat si pamatuje jen posledních 20 zpráv (hardcoded `RECENT_MESSAGE_COUNT = 20`).
Starší konverzace jsou komprimované do summary bloků (2-3 věty), které ztrácejí detaily.

Pokud uživatel před hodinou řešil konkrétní projekt a mezitím proběhlo 20+ zpráv,
chat "zapomene" kontext a neví na co uživatel navazuje.

## Aktuální stav

```
CONTEXT_ASSEMBLED | messages=20/20 | summaries=1/1 | tokens~3192/26768 | totalDbMessages=119
```

- **20 zpráv** z 119 celkem (jen 17%)
- **1 summary** z max 15
- **3192 tokenů** z 26768 budgetu (12% využito — zbytečně nízké)
- **Žádný časový limit** — čistě počet zpráv
- Summary bloky: max 500 znaků, 2-3 věty — ztrácejí detaily

## Návrh řešení

### Základní strategie: 1 den automaticky, víc na vyžádání

1. **Výchozí paměť = 1 den**
   - Zvýšit `RECENT_MESSAGE_COUNT` nebo přidat časový filtr (posledních 24h)
   - Využít víc z budgetu (aktuálně 12%, cíl ~60-70%)

2. **Na vyžádání = cílené hledání**
   - Pokud uživatel řekne "minulý týden jsme řešili X", chat aktivně hledá
     v historii/summary/KB relevantní kontext
   - Nový tool `recall_conversation` — hledá v chat historii podle query

3. **Kvalitnější summary bloky — obsahově dostačující + KB vodítka**
   - **NE 500 znaků** — summary musí být obsahově kompletní, ne arbitrárně oříznutý
   - Velikost summary dle obsahu, ne dle limitu — důležitá konverzace = delší summary
   - **KB vodítka u každého tématu** — sourceUrn, correlationId, project/client ID,
     aby se z summary dalo najít detail v KB
   - Strukturovaně: témata, rozhodnutí, akce, zmíněné entity s identifikátory
   - Příklad: "Řešili jsme deployment nUFO (project:6915dcab, client:MMB) — rozhodnutí:
     použít PostgreSQL 16 + Redis 7. Detail viz email:69a1b0d2, task:69a17e49"
   - Chat musí z summary umět najít detail přes KB search pokud potřebuje víc

## Pozorované logy

```
21:45:05 CONTEXT_ASSEMBLED | conversationId=6998bb3ee784c7a84477ee2c | messages=20/20 | summaries=1/1 | tokens~3192/26768 | totalDbMessages=119
21:45:05 Chat: intent=['task_mgmt', 'core'] → 18/33 tools
21:46:13 Chat: calling tool switch_context with args: {'client': 'MMB', 'project': 'nUFO'}
21:46:42 Chat: drift detected — forcing response
— chat se zasekl, protože neměl dostatek kontextu o tom co uživatel dříve řešil s projektem nUFO —
21:49:30 CONTEXT_ASSEMBLED | conversationId=6998bb3ee784c7a84477ee2c | messages=20/20 | summaries=1/1 | tokens~3192/26768 | totalDbMessages=119
21:49:36 Chat: intent=['core'] → 4/33 tools   ← ještě horší, jen 4 tools
21:50:05 Chat: calling tool switch_context with args: {'client_id': '...', 'project_id': '...'}
21:50:10 Chat: drift detected — forcing response
— opět drift, chat nevěděl co má dělat s projektem bez coding tools —
```

## Konfigurační hodnoty (app/config.py)

```python
RECENT_MESSAGE_COUNT = 20          # jen 20 zpráv
TOTAL_CONTEXT_WINDOW = 32768       # Qwen3-30B
SYSTEM_PROMPT_RESERVE = 2000
RESPONSE_RESERVE = 4000
CONTEXT_BUDGET = 26768             # dostupný budget
MAX_SUMMARY_BLOCKS = 15            # max summary bloků
COMPRESS_THRESHOLD = 20            # trigger komprese
```

## Files

- `backend/service-orchestrator/app/chat/context.py` — context assembler
- `backend/service-orchestrator/app/config.py` — RECENT_MESSAGE_COUNT, budget
