# Chat routing — přesunout na OpenRouter, P40 jen pro jednoduché úkoly a embedding

**Priorita**: HIGH
**Status**: OPEN (2026-03-01)

---

## Strategické rozhodnutí

Chat (foreground) má běžet přes **OpenRouter** (cloud modely), ne přes P40. P40 se uvolní pro:
- **Embedding** (Weaviate vectorizace, KB ingest)
- **Jednoduché background úkoly** (kvalifikace, deadline scan, krátké analýzy)
- **KB extraction** (kratší kontexty)

### Důvod

P40 (24GB VRAM) s 30b modelem:
- **Pomalá pro chat** — 92–292s per iterace při 20k+ token kontextu
- **Blokuje ostatní úkoly** — chat CRITICAL priorita vytlačí vše
- **VRAM limit** — nad 48k tokenů spill do CPU RAM → 7–12 tok/s
- **Deadline scany a background úkoly** blokují chat a naopak

OpenRouter cloud modely:
- **Rychlé** — ~3–10s per iterace (Sonnet, GPT-4o)
- **Lepší kvalita** — respektují system prompt lépe než 30b model
- **Neblokují GPU** — P40 volná pro embedding a background

---

## Aktuální stav routing

### Chat flow (`handler_agentic.py`)
1. `estimate_and_select_tier()` → vždy vrátí LOCAL tier
2. `select_route()` → zkontroluje GPU + OpenRouter policy
3. **Pokud GPU volná → vždy local** (i když OpenRouter by byl rychlejší)
4. Fallback na OpenRouter jen při GPU timeout

### Queues (`openrouter_resolver.py`)
```
CHAT:      p40 (local, 48k) → claude-sonnet-4 → gpt-4o
FREE:      p40 (local, 48k) → qwen3-30b-a3b:free
ORCHESTRATOR: p40 (local, 48k) → qwen3-30b-a3b:free → claude-haiku-4
LARGE_CONTEXT: gemini-2.5-flash → claude-sonnet-4
CODING:    p40 (local, 48k) → claude-sonnet-4
```

### Problém
Chat VŽDY preferuje local P40 (první v CHAT queue). OpenRouter se použije jen při GPU timeout nebo busy.

---

## Řešení

### 1. Přeřadit CHAT queue — cloud first

Změnit pořadí v CHAT queue:
```
CHAT:      claude-sonnet-4 (cloud) → gpt-4o (cloud) → p40 (local, fallback)
```

P40 zůstane jako fallback jen pokud OpenRouter není dostupný (API key chybí, budget vyčerpán).

**Kde**: OpenRouter Settings v UI (dynamická konfigurace) nebo `openrouter_resolver.py` default queues (řádky 94–117).

### 2. ORCHESTRATOR/FREE/CODING queue — local first (beze změny)

Background úkoly zůstanou na P40:
```
ORCHESTRATOR: p40 → qwen3-30b-a3b:free → claude-haiku-4
FREE:         p40 → qwen3-30b-a3b:free
CODING:       p40 → claude-sonnet-4
```

### 3. Priorita na GPU — snížit chat, zvýšit background

Aktuálně chat = CRITICAL (priorita 0), background = NORMAL. Pokud chat jde na OpenRouter:
- **Chat na OpenRouter** → GPU priority irrelevantní
- **Background na P40** → může běžet bez čekání na chat
- **Embedding** → má vlastní GPU slot, neovlivněn

### 4. select_route() — respektovat queue pořadí

Aktuální logika v `select_route()`:
```python
# Pokud je local entry a GPU volná → vždy local
if entry.is_local and gpu_free:
    return Route(target="local", ...)
```

Toto je OK — pokud cloud modely jsou v queue PŘED local, cloud se vybere automaticky (protože cloud = vždy dostupný → return immediately).

Ale ověřit:
- `select_route()` iteruje queue top-to-bottom ✓
- Cloud entry (is_local=False) → return okamžitě ✓
- Pokud cloud je na pozici 1 v CHAT queue → bude vybrán vždy ✓

### 5. Fallback logika

Pokud OpenRouter selže (API error, rate limit, budget):
1. Retry na dalším cloud modelu v queue (gpt-4o)
2. Pokud všechny cloud selhaly → fallback na P40 local
3. Uživateli zobrazit info: "Používám lokální GPU (pomalejší)"

---

## Implementační kroky

### Krok 1: Změnit default CHAT queue v `openrouter_resolver.py`
```python
# Řádky 94-100
"CHAT": [
    QueueEntry(model_id="anthropic/claude-sonnet-4", is_local=False, max_context=200000, label="Claude Sonnet 4"),
    QueueEntry(model_id="openai/gpt-4o", is_local=False, max_context=128000, label="GPT-4o"),
    QueueEntry(model_id="p40", is_local=True, max_context=48000, label="P40 Local (fallback)"),
],
```

### Krok 2: Ověřit `auto_use_openrouter` flag
Chat musí mít `has_openrouter=True`. Zkontrolovat:
- `handler.py` → `request.auto_use_openrouter` — odkud se bere?
- `CloudModelPolicy.autoUseOpenrouter` — musí být `true` globálně nebo per-project

### Krok 3: Chat priority na GPU → odstranit CRITICAL
Pokud chat běží na OpenRouter, nepotřebuje `X-Ollama-Priority: 0`:
```python
# handler_agentic.py — jen pokud route.target == "local"
if route.target == "local":
    headers["X-Ollama-Priority"] = "0"
```

### Krok 4: Test + monitoring
- Odeslat chat zprávu → ověřit že jde přes OpenRouter (log: `route=openrouter model=anthropic/claude-sonnet-4`)
- Odpověď do 10–15s místo 90–292s
- Background task na P40 běží bez čekání

---

## Alternativa: UI konfigurace

Místo změny defaultů v kódu — umožnit konfiguraci přes UI:
- OpenRouter Settings → tab "Queues" → CHAT queue → přetáhnout cloud modely nahoru
- Uživatel si sám nastaví pořadí

Toto JIŽ funguje (OpenRouter Settings UI existuje). Stačí:
1. Ověřit že queue konfigurace z UI se skutečně propaguje do `select_route()`
2. Změnit pořadí v UI: cloud first, P40 last

---

## Dopad na náklady

OpenRouter pricing (orientační):
- Claude Sonnet 4: ~$3/M input, ~$15/M output
- GPT-4o: ~$2.5/M input, ~$10/M output
- Qwen3 free: $0

Chat průměr: ~25k input + ~1k output per iterace, ~2 iterace per zpráva:
- ~50k input + ~2k output = **~$0.18 per zpráva** (Sonnet)
- ~20 zpráv/den = **~$3.60/den** = **~$108/měsíc**

Vs. P40 elektřina + pomalé odpovědi + ztracená produktivita.

## Soubory

- `backend/service-orchestrator/app/llm/openrouter_resolver.py` — queue definice, select_route() (řádky 94–262)
- `backend/service-orchestrator/app/llm/provider.py` — TIER_CONFIG, headers (řádky 50–110)
- `backend/service-orchestrator/app/chat/handler_agentic.py` — chat routing (řádky 117–128)
- `backend/service-orchestrator/app/chat/handler_streaming.py` — call_llm() s route (řádky 29–81)
- `backend/service-orchestrator/app/background/handler.py` — background routing (řádky 224–231)
- `backend/server/.../entity/CloudModelPolicy.kt` — autoUseOpenrouter flag

## Ověření

1. Chat zpráva → odpověď do 15s (OpenRouter)
2. Log: `route=openrouter model=anthropic/claude-sonnet-4`
3. Background task → stále na P40 (log: `route=local`)
4. P40 GPU utilization klesne (není blokována chatem)
5. Fallback: vypnout OpenRouter API → chat fallback na P40 (pomalejší ale funkční)
