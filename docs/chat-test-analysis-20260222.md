# Chat Test Analysis — 2026-02-22 (126k char message)

> Bug report z testování foreground chatu s dlouhou zprávou (126,207 chars).
> Orchestrátor deploy: commit `899183f2` (4 fixy: decompose + timeout).

## Test Timeline

| Čas | Trvání | Událost | Tier |
|-----|--------|---------|------|
| 20:43:17 | — | POST /chat, 126,207 chars, intent=`['core']` → 5/26 tools | — |
| 20:43:17 | **127s** | Decompose classifier (streaming, tools=None) | LOCAL_FAST (8k) |
| 20:45:24 | — | Classifier: 64 tokens → single-topic (fallback to agentic loop) | — |
| 20:45:24 | **251s** | Iter 1 blocking: `kb_store` — uložil CELÝ bug report do KB | LOCAL_LARGE (49k) |
| 20:49:36 | **39s** | Iter 2 blocking: `memory_store` — zkrácené shrnutí | LOCAL_LARGE (49k) |
| 20:50:15 | **14s** | Iter 3 blocking: `memory_recall` → **drift detected** ✓ | LOCAL_LARGE (49k) |
| 20:50:29 | **176s** | Final streaming response: 408 tokens, 1,129 chars | LOCAL_LARGE (49k) |
| 20:53:25 | — | **Done. Celkem: 10 min 8s** | — |

## BUG 1: Decompose classifier vrací single-topic (měl by vrátit multi-topic)

**Závažnost:** HIGH — celý decompose feature nefunguje pro tento test case.

**Pozorování:**
- Zpráva je 126k chars, obsahuje podrobný bug report s analýzou (trace data gaps, root cause, návrhy oprav)
- `_maybe_decompose()` se zavolala (msg_len > DECOMPOSE_THRESHOLD)
- Classifier streaming call trval **127s** a vrátil 64 tokens
- V logu **chybí** jakýkoliv `Chat decompose:` log → classifier vrátil `topic_count <= 1` (řádek 1130-1131) s `logger.debug` (ne INFO)
- Fallback na single-pass agentic loop

**Root cause hypotéza:**
Classifier dostává sampled excerpty (head 1500 + 2× middle 500 + tail 500 = ~3500 chars). Zpráva je jedna souvislá analýza jednoho bugu → classifier správně říká "single topic". **Decompose je navržen pro multi-topic zprávy, ne pro jednu dlouhou analýzu.**

**Ale:** I kdyby classifier fungoval korektně, trvání **127s** na LOCAL_FAST (8k ctx) je nepřijatelné. Viz BUG 2.

**Řešení:** Pro single-topic dlouhé zprávy decompose nepomůže. Potřeba jiný přístup:
- Truncation + summarization před LLM loop (zkrátit zprávu na ~4k tokens)
- Nebo: extrahovat "question/intent" z konce zprávy, zbytek jako context

## BUG 2: Decompose classifier trvá 127s místo ~3s (CPU spill na LOCAL_FAST)

**Závažnost:** HIGH — přidává 2+ minuty overhead i na single-topic zprávy.

**Pozorování:**
- `_maybe_decompose()` volá `llm_provider.completion(tier=ModelTier.LOCAL_FAST)` (8k ctx)
- `completion()` s `tools=None` jde přes `_streaming_completion()` (řádek 314)
- Router log ukazuje request na GPU p40, ale trvání **127-136s**
- Na 8k kontextu s ~3500 chars input by mělo trvat ~2-3s na GPU

**Root cause:**
Router proxy log (20:42:50): `id=84beea6d duration=129.85s` — to je KB embedding request běžící souběžně. GPU P40 je obsazená:
1. KB ingestion (NORMAL priority) drží model `qwen3-embedding:8b`
2. Chat classifier (CRITICAL) přijde, ale GPU musí swapnout model
3. Model swap 30b → load do VRAM trvá ~10-30s
4. Pak vlastní inference ~2-3s
5. Ale **souběžně** běží i druhý chat request (active=2, viz BUG 5)

**Skutečný problém:** Classifier streaming call (`/api/generate`) šel na router **BEZ headers** v prvním volání:
```
LLM streaming call: model=ollama/qwen3-coder-tool:30b headers={}
```
ALE ve druhém volání (po fix):
```
LLM streaming call: model=ollama/qwen3-coder-tool:30b headers={'X-Ollama-Priority': '0'}
```
**WAIT** — v logu z 20:43:17 vidíme `headers={'X-Ollama-Priority': '0'}` ✓. Takže priorita je správná.

**Alternativní root cause:** GPU p40 je zahlcená souběžnými requesty (KB embedding, qualifier LLM calls, chat). I CRITICAL request musí čekat na dokončení aktuálního inference na GPU.

**Řešení:**
- Decompose classifier by měl mít hard timeout (10s). Pokud nedoběhne, fallback na single-pass.
- Nebo: použít menší model pro classification (regex? nebo 8b model místo 30b).

## BUG 3: Model ignoruje anti-dump instrukci — ukládá celou zprávu do KB

**Závažnost:** HIGH — model plýtvá iteracemi ukládáním místo odpovídáním.

**Pozorování:**
- System prompt obsahuje: *"NIKDY neukládej celou zprávu uživatele do KB/memory."*
- Iteration 1: model volá `kb_store` s **CELÝM** bug reportem (~3000 slov)
- Iteration 2: model volá `memory_store` se zkráceným shrnutím
- Iteration 3: model volá `memory_recall` — drift detection konečně zastaví

**Root cause:**
qwen3-coder-tool:30b nedostatečně respektuje system prompt instrukce na 49k kontextu. Dlouhá zpráva "přehluší" instrukce. Model interpretuje bug report jako "důležitou informaci k zapamatování" místo "dotaz k zodpovězení".

**Řešení:**
1. **Tool-level guard:** `kb_store` a `memory_store` by měly odmítnout content > 500 chars s chybovou zprávou "Content too long. Store only key facts (1-2 sentences)."
2. **Odebrat storage tools z long-message flow:** Pokud msg_len > 8000, neposílat `kb_store`/`memory_store`/`store_knowledge` do tool setu.
3. **Stronger focus reminder:** Před každou iterací připomínat "Odpověz na otázku, neukládej zprávu."

## BUG 4: LOCAL_LARGE (49k) vždy spillne na CPU — timeout 600s stále může být málo

**Závažnost:** MEDIUM — 600s fix pomohl (přežili jsme), ale response je 4+ min/iterace.

**Pozorování:**
- 30b model + 49k ctx na 24GB P40: weights ~17GB + KV cache ~5-7GB → **>24GB, spill do CPU RAM**
- Iteration 1: 251s (pod 600s timeout ✓)
- Final streaming: 176s
- Celkově 10 min pro jednu zprávu

**Čísla:**
| Tier | num_ctx | VRAM fit? | Speed | Timeout |
|------|---------|-----------|-------|---------|
| LOCAL_FAST | 8k | ✓ | 30 tok/s | 300s |
| LOCAL_STANDARD | 32k | ✓ | 25-30 tok/s | 300s |
| LOCAL_LARGE | 49k | **✗ (spill)** | **7-12 tok/s** | 600s |
| LOCAL_XLARGE | 128k | ✗ | 5-8 tok/s | 900s |

**Řešení:**
- Snížit LOCAL_LARGE num_ctx na **40960** (40k) — vejde se do VRAM
- Nebo: přijmout CPU spill ale zkrátit zprávu (viz BUG 1 řešení)

## BUG 5: Duplicitní chat requesty (active=2)

**Závažnost:** MEDIUM — plýtvá GPU/CPU prostředky.

**Pozorování:**
```
FOREGROUND_CHAT_START: active=2
```
Server log ukazuje dvě souběžné SSE sessions pro stejnou zprávu.

**Root cause:**
ChatViewModel `_isLoading` guard (commit `84727804`) chrání proti double-click v UI. Ale toto je **server-side** duplikace — buď:
- UI odeslal zprávu 2× (kRPC retry?)
- Nebo server vytvořil 2 SSE streamy

**Řešení:**
- Přidat server-side dedup: `PythonChatClient` by měl kontrolovat, jestli pro daný `session_id` už neběží aktivní SSE stream
- Nebo: orchestrátor by měl odmítnout POST /chat pokud pro session_id existuje aktivní request

## BUG 6: Decompose streaming call posílá celou 126k zprávu (i na 8k ctx)

**Závažnost:** LOW-MEDIUM — zbytečný overhead.

**Pozorování:**
- `_maybe_decompose()` sampluje 3500 chars z 126k zprávy
- Ale `_build_messages()` v `handle_chat()` posílá celou `request.message` (126k chars)
- To se ale děje AŽ PO decompose — decompose sampler je správný
- **ALE:** completion() na řádku 1107 posílá jen `user_content` (sampled ~3500 chars) — to je OK

Wait — decompose classifier sampluje správně. Problém je jinde: proč streaming trval 127s na 3500 chars + 8k ctx?

**Re-analysis:** 127s na 8k ctx s ~3500 chars input je extrémní. Buď:
- GPU byla obsazená jiným requestem (KB qualifier)
- Model swap overhead (embedding → 30b)
- Nebo: streaming call čeká na semaphore (`_SEMAPHORE_LOCAL`)

**Řešení:** Logovat `semaphore.acquire` časy pro diagnostiku. Přidat timeout na decompose classifier.

## Shrnutí priorit pro vývoj

| # | Bug | Priorita | Effort |
|---|-----|----------|--------|
| 3 | Anti-dump nefunguje (model ukládá celou zprávu) | **P1** | M — tool guard + tool filtering |
| 1 | Single-topic 126k → 10 min (decompose nepomůže) | **P1** | L — message truncation/summarization |
| 5 | Duplicitní requesty (active=2) | **P1** | S — server-side dedup |
| 2 | Classifier 127s místo 3s | **P2** | S — hard timeout 10s |
| 4 | LOCAL_LARGE spill (49k > VRAM) | **P2** | S — snížit na 40k |
| 6 | Classifier latence diagnostika | **P3** | S — semaphore logging |
