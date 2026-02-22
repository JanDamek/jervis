# Foreground Chat: krátké zprávy extrémně pomalé + zbytečné tool calls

> **Datum:** 2026-02-22 22:51–22:56
> **Deploy:** commit `25086d1a`
> **Zprávy:** dvě krátké zprávy (běžné dotazy, ne 126k)
> **Výsledek:** odpověď správná, ale extrémně pomalá. Zbytečné tool calls.

---

## 1. Problém

Krátká zpráva (běžný dotaz) trvá **1.5-2 minuty** místo očekávaných **5-10 sekund**.

Model na jednoduchou otázku:
1. Volá `switch_context` — přepne klienta v UI (zbytečné pokud je kontext jasný)
2. Volá `kb_search` — hledá informace které MÁ v kontextu (system prompt, runtime context)
3. Volá `memory_store` — ukládá triviální fakt (zbytečné)
4. Drift detection zastaví smyčku po iter 4
5. Teprve pak vygeneruje odpověď

**Očekávaný flow:** Model odpoví PŘÍMO (0 tool calls, ~5s).

---

## 2. Data z logů

### Zpráva 1 (22:51–22:53:36):

| Iter | Trvání | Tool | Args | Zbytečné? |
|------|--------|------|------|-----------|
| 2 | **26s** | `switch_context` | `{"client": "MMB", "project": "nUFO"}` | Možná — záleží na dotazu |
| 3 | **33s** | `kb_search` | `"active project in MMB client"` | **ANO** — info je v system prompt |
| 4 | **27s** | `memory_store` | `"Active project in MMB client..."` | **ANO** — triviální fakt |
| drift | 3s | (forced response) | 46 chars, 18 tokens | — |

**Celkem: ~2 min** pro odpověď na jednoduchou otázku.

Drift detection funguje (iter 4 zastavil: "tool calls přeskakují mezi nesouvisejícími oblastmi (memory, scope, search)") — ale **4 iterace × 25s = 100s** je příliš.

### Zpráva 2 (22:55–...):

| Iter | Trvání | Tool | Args |
|------|--------|------|------|
| 1 | **21s** | `switch_context` | `{"client": "Commerzbank", "project": "bms"}` |
| 2 | **4s** | `kb_search` | `"BMS project overview and key components"` |

Opět zbytečné tool calls.

### Tier a tokeny:

- Obě zprávy: `tier=local_fast` (8k), estimated_tokens ~5000-7000 ✓
- prompt_tokens: 4000-6200
- completion_tokens: 26-51 (jen tool call JSON, ne odpověď)

---

## 3. Root Causes

### 3.1 Model ignoruje instrukci "odpovídej PŘÍMO" (P0)

**Fakta:**
- System prompt (`system_prompt.py:69`): `"DŮLEŽITÉ: Odpovídej PŘÍMO pokud znáš odpověď. Tools volej jen když potřebuješ informace."`
- Model tuto instrukci **systematicky ignoruje** a volá tools i pro informace které má v kontextu
- Runtime context v system promptu obsahuje kompletní seznam klientů a projektů s ID
- Přesto model volá `kb_search("active project in MMB client")` — informace JIŽ MÁ

**Proč model ignoruje instrukci:**
- Qwen3-30b má silný bias k tool calling — když má k dispozici tools, preferuje je volat
- System prompt instrukce "odpovídej PŘÍMO" je příliš slabá proti tool-calling biasu
- 5 tools v kontextu = 5 pokušení. Model si "najde důvod" proč volat kb_search
- Problém se zhoršuje s historií konverzace — model vidí předchozí tool calls a kopíruje pattern

**Řešení (směr):**
- **Silnější instrukce** v system promptu: ne "DŮLEŽITÉ" ale explicitní pravidlo s příklady
  - "Pokud znáš odpověď z kontextu výše → ODPOVĚZ. Nevolej tools."
  - "switch_context volej JEN když user explicitně řekne 'přepni se na X'"
  - "kb_search volej JEN když informace NENÍ v kontextu"
- **Redukce tool biasu**: Méně tools → méně pokušení. Intent classification (už existuje) by měla filtrovat agresivněji
- **Few-shot examples** v system promptu: "Q: co dělám na BMS? A: (odpověď přímo, bez tools)"
- **Tool gating**: Pro jednoduché dotazy (krátká zpráva, intent=core) neposílat tools vůbec — jen text response

### 3.2 Každá iterace trvá 20-30s na LOCAL_FAST (8k) (P1)

**Fakta:**
- LOCAL_FAST: num_ctx=8192, model=qwen3-coder-tool:30b
- 5000-7000 prompt tokens, 26-51 completion tokens
- **26s pro 26 tokenů** = ~1 tok/s
- Očekávaná rychlost na GPU (P40) s 8k kontextem: **~30 tok/s** → ~1s pro 26 tokenů

**Proč je to 26× pomalejší:**
- Embedding model může stále blokovat GPU (viz test #3 bug §3.1)
- NORMAL background requesty mohou být na GPU a CRITICAL musí čekat na unload
- Router log by ukázal zda CRITICAL request čekal na GPU

**Řešení (směr):**
- Viz test #3 bug: Router preemptive unload pro CRITICAL
- Foreground chat MUSÍ mít GPU okamžitě — ne po 60s čekání na embedding unload
- Zvážit: pokud GPU není volné do 5s → odpovědět přímo bez tools (graceful degradation)

### 3.3 Model ukládá triviální fakta do memory_store (P2)

**Fakta:**
- Iter 4 zprávy 1: `memory_store(subject="Active project in MMB client", content="After switching to MMB client, the active project is set to 'nUFO'.")`
- To je RUNTIME stav, ne fakt k zapamatování
- System prompt (`system_prompt.py:71`): `"NIKDY neukládej celou zprávu uživatele do KB/memory"` — ale toto pravidlo je o CELÝCH zprávách, ne o triviálních faktech

**Řešení (směr):**
- Rozšířit anti-dump pravidlo: "Neukládej triviální/runtime informace. Memory jen pro fakta od uživatele."
- Nebo: `memory_store` odebrat z default intent=core tool setu (jen intent=learning)

---

## 4. Relevantní soubory

| Soubor | Relevantní řádky |
|--------|------------------|
| `backend/service-orchestrator/app/chat/system_prompt.py` | 69 (odpovídej PŘÍMO), 71 (anti-dump) |
| `backend/service-orchestrator/app/chat/handler.py` | 339-400 (agentic loop), 466-473 (drift detection call) |
| `backend/service-orchestrator/app/chat/intent.py` | Intent classification, tool selection |
| `backend/service-orchestrator/app/chat/tools.py` | Tool definitions, TOOL_DOMAINS |

---

## 5. Expected vs Actual

| Metrika | Expected | Actual |
|---------|----------|--------|
| Odpověď na jednoduchou otázku | **5-10s**, 0-1 tool calls | **100-120s**, 3-4 tool calls |
| Tool calls pro info v kontextu | 0 | 2-3 (kb_search, switch_context) |
| GPU inference speed (8k, 26 tokens) | ~1s | **26s** |
| Iterace pro jednoduchou otázku | 1 (přímá odpověď) | 4 (drift-break) |
