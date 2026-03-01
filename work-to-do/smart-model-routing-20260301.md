# Smart model routing — orchestrátor posílá use case, router rozhoduje model

**Priorita**: HIGH
**Status**: OPEN (2026-03-01)
**Nahrazuje**: `chat-routing-openrouter-20260301.md`

---

## Koncept

Orchestrátor (Python) by NEMĚL vybírat konkrétní model ani tier. Místo toho pošle **use case** (oblast potřeby) a router rozhodne jaký model použít na základě:

1. **Use case** — co orchestrátor potřebuje (chat, korekce, background task, coding, embedding)
2. **Client policy** — co klient smí (local only, free cloud, paid cloud, full access)
3. **Dostupné zdroje** — GPU volná? OpenRouter budget? Context size?

### Příklad flow

```
Orchestrátor: "Potřebuji CHAT pro klienta X, ~20k tokenů"
     ↓
Router: Klient X má policy=PAID_CLOUD → CHAT queue = [Sonnet 4, GPT-4o, P40 fallback]
        GPU je busy → vyberu Sonnet 4
     ↓
Výsledek: Route(target=openrouter, model=anthropic/claude-sonnet-4)
```

```
Orchestrátor: "Potřebuji CHAT pro klienta Y, ~20k tokenů"
     ↓
Router: Klient Y má policy=LOCAL_ONLY → CHAT queue = [P40]
        GPU busy → čekám v queue
     ↓
Výsledek: Route(target=local, model=p40, queued=true)
```

---

## Use cases (oblasti)

| Use case | Popis | Typický kontext | Ideální model |
|----------|-------|-----------------|---------------|
| `CHAT` | Foreground chat s uživatelem | 15–40k tokenů | Cloud (rychlost) nebo P40 |
| `CORRECTION` | Korekce/opravy po chatu | 5–15k tokenů | P40 (malý kontext, rychlé) |
| `BACKGROUND` | Background task (analýza, scan) | 10–30k tokenů | P40 nebo free cloud |
| `CODING` | Kódová analýza/generování | 20–100k tokenů | Claude Sonnet/Opus |
| `EMBEDDING` | Vektorizace pro KB/Weaviate | N/A | Lokální embedding model |
| `KB_EXTRACTION` | KB ingest, summarizace | 10–30k tokenů | P40 (batch, nízká priorita) |
| `DEADLINE_SCAN` | Periodický deadline check | 10k tokenů | P40 nebo free cloud |
| `QUALIFICATION` | SimpleQualifier rozhodování | 3–8k tokenů | P40 LOCAL_FAST |

Router pro každý use case + policy vybere optimální model.

---

## Client policy — úrovně přístupu

### Aktuální stav

`CloudModelPolicy` má 4 boolean flagy:
```kotlin
data class CloudModelPolicy(
    val autoUseAnthropic: Boolean = false,
    val autoUseOpenai: Boolean = false,
    val autoUseGemini: Boolean = false,
    val autoUseOpenrouter: Boolean = false,
)
```

Toto je příliš granulární pro routing a zároveň neřeší free vs. paid OpenRouter modely.

### Navrhovaná policy

Nahradit 4 booly jedním **tier enumem** + zachovat granulární overrides:

```kotlin
enum class ModelAccessTier {
    LOCAL_ONLY,       // Jen P40, žádný cloud. Data neopustí server.
    FREE_CLOUD,       // P40 + OpenRouter free modely (qwen3-30b-a3b:free)
    STANDARD_CLOUD,   // P40 + OpenRouter paid (Sonnet, GPT-4o, Haiku)
    FULL_ACCESS,      // Vše včetně premium (Opus, Gemini Pro)
}

data class CloudModelPolicy(
    // Nový primární field
    val accessTier: ModelAccessTier = LOCAL_ONLY,

    // Granulární overrides (optional, pro výjimky)
    val allowAnthropic: Boolean? = null,    // null = řídí se tier
    val allowOpenai: Boolean? = null,
    val allowGemini: Boolean? = null,
    val allowOpenrouter: Boolean? = null,

    // Budget limit per klient
    val monthlyBudgetUsd: Double? = null,   // null = bez limitu (nebo globální)

    // Coding agent (Claude MCP) — separátní od routing
    val allowCodingAgent: Boolean = false,
)
```

### Příklady klientů

| Klient | Tier | Co smí |
|--------|------|--------|
| Interní (vlastní) | `FULL_ACCESS` | Vše — chat na Sonnet, coding na Claude, P40 pro background |
| Klient A (placený) | `STANDARD_CLOUD` | Cloud modely pro chat, P40 pro background, coding agent |
| Klient B (basic) | `FREE_CLOUD` | P40 + free OpenRouter modely, žádný coding agent |
| Klient C (citlivá data) | `LOCAL_ONLY` | Jen P40, data neopustí server, žádný cloud |

---

## Routing logika — v routeru, ne v orchestrátoru

### Aktuální stav (orchestrátor rozhoduje)

```python
# handler_agentic.py — orchestrátor sám vybírá tier
tier = estimate_and_select_tier(messages, tools)  # LOCAL_STANDARD
route = select_route(estimated_tokens, priority, has_openrouter, use_case="chat")
```

Problém: orchestrátor musí znát infrastrukturu (GPU, OpenRouter, tiery).

### Nový stav (orchestrátor posílá use case, router rozhoduje)

```python
# handler_agentic.py — orchestrátor řekne CO potřebuje
route = request_route(
    use_case="CHAT",                    # Oblast potřeby
    client_id="68a336adc3acf65a48cab3e7",  # Kdo to potřebuje
    estimated_tokens=25000,             # Kolik tokenů ~
    priority="FOREGROUND",              # Důležitost
)
# Router vrátí: Route(target, model, tier, max_context)
```

### Router decision tree

```
request_route(use_case, client_id, tokens, priority):
  1. Načti client policy (cached, z Kotlin /internal/client/{id}/policy)
  2. Podle policy → filtruj dostupné modely:
     - LOCAL_ONLY → jen P40 entries
     - FREE_CLOUD → P40 + free cloud entries
     - STANDARD_CLOUD → P40 + paid cloud entries
     - FULL_ACCESS → vše
  3. Podle use_case → vyber queue:
     - CHAT → CHAT queue (cloud-first pro rychlost)
     - CORRECTION → CORRECTION queue (P40-first, malý kontext)
     - BACKGROUND → ORCHESTRATOR queue (P40-first, cloud fallback)
     - CODING → CODING queue (Claude-first)
     - KB_EXTRACTION → KB queue (P40 only, batch)
     - DEADLINE_SCAN → FREE queue (nejlevnější)
  4. Z filtrované queue vyber první dostupný model:
     - Check GPU availability (pro local entries)
     - Check context limit (pro všechny)
     - Check budget (pro cloud entries)
  5. Vrať Route
```

---

## Queue definice per use case

### CHAT queue (rychlost je priorita)
```
FULL_ACCESS:     claude-sonnet-4 → gpt-4o → p40
STANDARD_CLOUD:  claude-sonnet-4 → gpt-4o → p40
FREE_CLOUD:      qwen3-30b:free → p40
LOCAL_ONLY:      p40
```

### CORRECTION queue (malý kontext, P40 stačí)
```
FULL_ACCESS:     p40 → claude-haiku-4 (fallback)
STANDARD_CLOUD:  p40 → claude-haiku-4 (fallback)
FREE_CLOUD:      p40
LOCAL_ONLY:      p40
```

### BACKGROUND queue (P40 preferovaná, cloud fallback)
```
FULL_ACCESS:     p40 → claude-haiku-4 → qwen3-30b:free
STANDARD_CLOUD:  p40 → claude-haiku-4
FREE_CLOUD:      p40 → qwen3-30b:free
LOCAL_ONLY:      p40
```

### CODING queue (kvalita je priorita)
```
FULL_ACCESS:     claude-sonnet-4 → claude-opus-4 → p40
STANDARD_CLOUD:  claude-sonnet-4 → p40
FREE_CLOUD:      p40
LOCAL_ONLY:      p40
```

### KB_EXTRACTION queue (batch, nízká priorita)
```
Všechny tiers:   p40 (nízká priorita, batch processing)
```

### DEADLINE_SCAN queue (nejlevnější)
```
FULL_ACCESS:     qwen3-30b:free → p40
STANDARD_CLOUD:  qwen3-30b:free → p40
FREE_CLOUD:      qwen3-30b:free → p40
LOCAL_ONLY:      p40
```

---

## Implementační kroky

### Krok 1: Rozšířit CloudModelPolicy

`backend/server/.../entity/CloudModelPolicy.kt`:
- Přidat `accessTier: ModelAccessTier` enum
- Přidat `monthlyBudgetUsd: Double?`
- Zpětná kompatibilita: migrovat staré booly → tier

`shared/common-dto/.../openrouter/OpenRouterSettingsDtos.kt`:
- Přidat `ModelAccessTierEnum` do DTO

### Krok 2: Nový endpoint — client policy pro router

`backend/server/.../rpc/internal/InternalClientRouting.kt`:
```
GET /internal/client/{id}/model-policy → ModelAccessTier + overrides + budget
```

Python router cachuje (60s) per client_id.

### Krok 3: Nový `request_route()` v Python

`backend/service-orchestrator/app/llm/openrouter_resolver.py`:
- Nová funkce `request_route(use_case, client_id, tokens, priority)`
- Načte policy pro client_id (cached)
- Filtruje queue podle policy
- Vybere model podle dostupnosti

### Krok 4: Orchestrátor — nahradit přímý tier výběr

`backend/service-orchestrator/app/chat/handler_agentic.py`:
- Nahradit `estimate_and_select_tier()` + `select_route()` → `request_route(use_case="CHAT", ...)`

`backend/service-orchestrator/app/background/handler.py`:
- Nahradit tier selection → `request_route(use_case="BACKGROUND", ...)`

### Krok 5: UI — policy management per klient

`shared/ui-common/.../screens/settings/sections/`:
- Nový tab/sekce: "Model Access" per klient
- Dropdown: LOCAL_ONLY / FREE_CLOUD / STANDARD_CLOUD / FULL_ACCESS
- Budget limit input
- Override checkboxy pro specifické providery

### Krok 6: Queue konfigurace v UI

Stávající OpenRouter Settings → Queues tab:
- Přidat filtrování per tier (zobrazit jen modely dostupné pro daný tier)
- Preview: "Pro klienta X s tier STANDARD_CLOUD bude CHAT queue: [Sonnet, GPT-4o, P40]"

---

## Coding agent — separátní flow

Coding agent (Claude přes MCP) je oddělen od LLM routingu:
- Volá se přes `dispatch_coding_agent` tool
- Vždy cloud (Anthropic API přímo, ne OpenRouter)
- Řízeno separátním flag `allowCodingAgent` v policy
- Klient s `LOCAL_ONLY` + `allowCodingAgent=false` → nemůže volat coding agenta

---

## Migrace ze stávajícího stavu

### Co zůstane
- Queue definice v OpenRouter Settings (konfigurovatelné)
- GPU availability check přes ollama-router
- Fallback logika (cloud → local, local → queue)

### Co se změní
- Orchestrátor NEPOSÍLÁ `has_openrouter` boolean → posílá `use_case` + `client_id`
- Router SÁM rozhoduje na základě policy + queue + dostupnosti
- `CloudModelPolicy` získá `accessTier` enum místo 4 boolů
- Nový endpoint pro policy lookup z Pythonu

### Zpětná kompatibilita
- Staré `auto_use_*` flagy → mapovat na `accessTier`:
  - Všechny false → `LOCAL_ONLY`
  - Jen openrouter → `FREE_CLOUD`
  - openrouter + anthropic/openai → `STANDARD_CLOUD`
  - Vše true → `FULL_ACCESS`

---

## Dopad na P40

Po implementaci:
- **Chat (FULL/STANDARD)** → cloud → P40 uvolněna
- **Korekce** → P40 (malý kontext, rychlé)
- **Background** → P40 (cloud jen jako fallback)
- **KB extraction** → P40 (batch)
- **Embedding** → lokální model (jiný GPU slot)
- **Deadline scan** → free cloud (P40 neutrpí)

P40 bude primárně pro:
1. Korekce a jednoduché background tasky (~8k kontext, ~8s)
2. KB extraction (batch, nízká priorita)
3. Embedding
4. Fallback pro vše (když cloud nedostupný)

---

## Soubory

### Kotlin (policy + endpoint)
- `backend/server/.../entity/CloudModelPolicy.kt` — rozšířit o accessTier
- `backend/server/.../entity/ClientDocument.kt` — migrace policy
- `backend/server/.../rpc/internal/` — nový endpoint /client/{id}/model-policy
- `shared/common-dto/.../openrouter/OpenRouterSettingsDtos.kt` — ModelAccessTierEnum

### Python (routing)
- `backend/service-orchestrator/app/llm/openrouter_resolver.py` — request_route(), policy cache
- `backend/service-orchestrator/app/llm/provider.py` — TIER_CONFIG (beze změny)
- `backend/service-orchestrator/app/chat/handler_agentic.py` — use_case="CHAT"
- `backend/service-orchestrator/app/chat/handler.py` — use_case="CORRECTION"
- `backend/service-orchestrator/app/background/handler.py` — use_case="BACKGROUND"

### UI
- `shared/ui-common/.../screens/settings/sections/` — policy management per klient

## Ověření

1. Klient LOCAL_ONLY → chat na P40, žádný cloud call
2. Klient STANDARD_CLOUD → chat na Sonnet 4 (~10s odpověď), background na P40
3. Klient FREE_CLOUD → chat na free model, background na P40
4. Budget limit → po vyčerpání fallback na P40
5. Coding agent → jen pokud `allowCodingAgent=true`
6. P40 GPU utilization klesne pod 50% (chat jde na cloud)
