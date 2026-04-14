# Router — client-based tier resolution

**Status:** Schváleno, implementace
**Datum:** 2026-04-14

---

## Princip

Router je jediný gateway na GPU. Všechny služby posílají `client_id` v route-decision.
Router si sám zjistí tier klienta (z MongoDB nebo server API). Žádná služba neřeší tier.

## Aktuální stav (problém)

- Orchestrátor posílá `max_tier` hardcoded nebo z task configu
- Browser pod posílá `max_tier` z init configu (push ze serveru)
- KB posílá `max_tier` hardcoded
- Pokud se tier změní, všechny služby mají starý tier

## Cílový stav

```
Služba → route-decision: {"capability": "vision", "client_id": "abc123", ...}
                                    ↓
Router: client_id → cache.get(client_id) → tier
         cache miss → MongoDB query → clients collection → cloudModelPolicy.maxOpenRouterTier
                                    ↓
Router: použije tier pro routing decision (local GPU vs OpenRouter)
```

## Změny

### 1. Router — přidat client_id parameter + tier cache

**Soubor:** `backend/service-ollama-router/app/router_core.py`
- `decide_route()` přijímá `client_id: str | None`
- Pokud `client_id` → resolve tier z cache/DB
- Pokud `max_tier` explicitně zadán → použije ho (backward compat)
- Pokud ani jedno → default FREE

**Soubor:** `backend/service-ollama-router/app/client_tier_cache.py` (nový)
- Cache: `{client_id: (tier, timestamp)}`
- TTL: 5 minut
- Při cache miss → MongoDB query na `clients` collection
- `cloudModelPolicy.maxOpenRouterTier` → tier string

**Soubor:** `backend/service-ollama-router/app/main.py`
- `route-decision` endpoint přijímá `client_id` v body
- Předá do `decide_route()`

### 2. MongoDB přístup pro router

Router potřebuje číst z MongoDB `clients` collection.
- Přidat MongoDB connection string do router configu (ConfigMap)
- Nebo: router se zeptá Kotlin serveru přes HTTP (ale to je závislost)
- **Preferuji MongoDB přímo** — router je infrastruktura, má mít přímý přístup

### 3. Služby — posílat client_id místo max_tier

**Orchestrátor:** `backend/service-orchestrator/`
- Při LLM callu posílat `client_id` header nebo v route-decision body
- Odebrat hardcoded tier

**KB service:** `backend/service-knowledgebase/`
- Posílat `client_id` v route-decision
- Odebrat hardcoded tier

**Browser pod:** `backend/service-o365-browser-pool/`
- `vlm_client.py` a `ai_navigator.py` posílat `client_id`
- Odebrat `max_tier` z init configu a settings
- Odebrat `client_max_tier` z main.py

### 4. Backward compatibility

- `max_tier` v route-decision stále funguje (explicit override)
- `client_id` má přednost — pokud je zadán, tier se resolvne z DB
- Pokud ani `client_id` ani `max_tier` → default FREE

## Pořadí implementace

1. Router: client_tier_cache.py + MongoDB connection
2. Router: decide_route() přijímá client_id
3. Router: route-decision endpoint přijímá client_id
4. Deploy router
5. Browser pod: posílat client_id místo max_tier
6. Orchestrátor: posílat client_id
7. KB service: posílat client_id
8. Odebrat hardcoded tier ze všech služeb
9. Zpět na scraping

## Po dokončení → vrátit se k

- Teams chat scraping (CommerzBank, MMB)
- Health loop testování
- Chat monitor notifikace
