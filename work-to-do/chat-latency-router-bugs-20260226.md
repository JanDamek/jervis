# BUG: Chat latence 15 min — router zbytečně reloaduje model + GPU contention

**Datum:** 2026-02-26
**Priorita:** HIGH
**Typ:** PERFORMANCE BUG

---

## Pozorovaný průběh chatu (reálná data)

Celková doba: **14 min 45s** pro jednu odpověď (5 iterací + forced response).

| Iter | LLM latence | Tool | Poznámka |
|------|-------------|------|----------|
| 1 | **180s** | `list_unclassified_meetings` | Model reload přidal ~120s |
| 2 | 65s | `kb_search('nUFO tracing issue')` | Normální |
| 3 | **181s** | `kb_search('nUFO current issues')` | Contention s embedding |
| 4 | 95s | `list_unclassified_meetings` | Opakování toolu |
| 5 | **228s** | Drift detected, forced response | Nejdelší iterace |
| Final | 106s | Streaming 245 tokenů | Streaming odpověď |

**Očekávaná latence**: ~30-60s per iterace → celkem 3-5 min
**Skutečná latence**: 65-228s per iterace → celkem 15 min

---

## Bug 1: Router zbytečně unloaduje a reloaduje stejný model (CRITICAL)

### Popis

Router unloadoval `qwen3-coder-tool:30b` a okamžitě ho reloadoval zpět:

```
09:26:58 GPU p40 still has 1 active requests after 60s wait, unloading anyway
09:26:58 Unloading model qwen3-coder-tool:30b from GPU p40
09:26:58 Loading model qwen3-coder-tool:30b on GPU p40 (keep_alive=10m)
09:27:14 Model qwen3-coder-tool:30b loaded on GPU p40 (30.0GB used)
```

**Reload trval 16s** a během té doby žádný request nemohl být zpracován.
Navíc po reloadu Ollama potřebuje warm-up — první inference je pomalejší.

### Příčina

Router má logiku "pokud GPU má jiné modely vedle :30b, unloadni je".
Ale unload cyklus nerozlišuje — unloadne i :30b samotný když čeká na
uvolnění embedding requestů, a pak ho hned loadne zpět.

### Navrhované řešení

1. **Nikdy neunloadovat model který je potřeba** — pokud příchozí request
   je pro `qwen3-coder-tool:30b` a ten je loaded, neunloadovat ho
2. **Unloadovat jen cizí modely** — pouze `qwen3-embedding:8b` a jiné,
   nikdy model pro který existuje pending request
3. **Přidat guard**: `if model_to_unload == model_to_load: skip`

### Soubory

- `backend/service-ollama-router/app/gpu.py` — unload logika
- `backend/service-ollama-router/app/router_core.py` — model swap rozhodování

---

## Bug 2: Nekonzistentní LLM latence 65s vs 228s (HIGH)

### Popis

Stejný model, stejná velikost kontextu (~12k tokenů), ale latence
kolísá 3.5x (65s → 228s). Příčiny:

1. **GPU contention s embedding**: KB write posílá embedding requesty
   mezi chat iteracemi → GPU přepíná kontext
2. **Model swap overhead**: Router přepíná mezi :30b a embedding:8b,
   každý swap = unload/load cyklus
3. **Ollama concurrent requests**: Více requestů na GPU současně
   zpomaluje každý z nich

### Navrhované řešení

1. **Dedicated embedding queue** — embedding requesty plánovat mimo
   aktivní chat (pokud běží CRITICAL chat, pozastavit NORMAL embedding)
2. **Model pinning pro chat** — dokud běží chat session (iterace 1-6),
   nepřepínat model na GPU
3. **Batch embedding** — místo N jednotlivých embedding calls udělat
   jeden batch call (Ollama /api/embed podporuje batch)

---

## Bug 3: Tool loop — `list_unclassified_meetings` voláno 2x, `kb_search` 3x (MEDIUM)

### Popis

LLM opakovaně volá stejné tooly:
- `list_unclassified_meetings` voláno 2x (iter 1 + iter 4) se stejnými args
- `kb_search` voláno 3x (iter 2, 3, 5) s mírně odlišnými query

Drift detection funguje (zastavil po 3x kb_search), ale **4 zbytečné iterace**
stojí 8+ minut navíc.

### Navrhované řešení

1. **Tool result cache per session** — pokud tool byl volaný se stejnými args,
   vrátit cached výsledek bez LLM roundtripu
2. **Striktější loop detection** — detekovat opakování po 2x, ne 3x
3. **Lepší system prompt** — instruovat model aby neodpakoval tooly

### Soubor

- `backend/service-orchestrator/app/chat/handler_agentic.py` — drift detection
- `backend/service-orchestrator/app/tools/ollama_parsing.py` — tool call extraction

---

## Pozitivní zjištění

- **Chat end-to-end funguje** — odpověď dorazila (245 tokenů, 613 znaků)
- **Drift detection funguje** — zastavil tool loop po 3x `kb_search`
- **Intent detection OK** — `['core', 'task_mgmt']` → 18/33 tools
- **Context assembly OK** — 107 messages, summaries, ~2k tokens kontext
- **KB search OK** — 10s latence, výsledky nalezeny
- **Streaming odpověď OK** — funguje přes SSE
