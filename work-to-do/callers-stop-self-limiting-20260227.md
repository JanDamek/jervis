# Calleři — přestat se omezovat, nechat router řídit

**Priorita**: MEDIUM

## Problém

Router má teď queue a řídí vytížení backendů. Ale calleři (orchestrátor, KB, correction)
se **sami omezují** — semaphory, sekvenční zpracování — místo aby poslali requesty
a nechali router rozhodnout kam a kdy je zpracovat.

Výsledek: i když router má kapacitu na 2 GPU × 2 + CPU = 6 slotů, calleři mu pošlou
max 1-2 requesty najednou → p40-2 je idle.

## Co změnit

### 1. Orchestrátor — zvýšit/odstranit semaphore

**Soubor**: `backend/service-orchestrator/app/llm/provider.py:38-41`

```python
# Aktuálně:
_SEMAPHORE_LOCAL = asyncio.Semaphore(2)   # Max 2 concurrent local LLM calls
_SEMAPHORE_CLOUD = asyncio.Semaphore(5)

# Změnit na:
_SEMAPHORE_LOCAL = asyncio.Semaphore(6)   # Router řídí, sem jen safety limit
```

Orchestrátor může poslat víc requestů — router je zařadí do queue a distribuuje.

### 2. KB Graph — paralelní zpracování chunků

**Soubor**: `backend/service-knowledgebase/app/services/graph_service.py:134-149`

```python
# Aktuálně (sekvenční):
for i, chunk in enumerate(chunks, 1):
    result = await self._extract_chunk(chunk)  # čeká na každý

# Změnit na (paralelní s limitem):
sem = asyncio.Semaphore(4)  # 4 concurrent, router řídí zbytek
async def process(chunk):
    async with sem:
        return await self._extract_chunk(chunk)
results = await asyncio.gather(*[process(c) for c in chunks])
```

Tím KB pošle víc requestů najednou → router je rozprostře na obě GPU.

### 3. KB RAG — zvýšit embedding semaphore

**Soubor**: `backend/service-knowledgebase/app/services/rag_service.py:21,35`

```python
# Aktuálně:
MAX_CONCURRENT_EMBEDDINGS = 5

# OK — embedding je rychlý (0.3s GPU), 5 je rozumné.
# Ale ověřit že priority header se posílá správně.
```

Toto je OK, neměnit.

### 4. Correction — paralelní chunky

**Soubor**: `backend/service-correction/app/agent.py:177+`

Sekvenční zpracování chunků. Stejný pattern jako KB Graph — přidat paralelizaci.

### 5. Timeouty callerů — zvýšit pro queue

Router teď může request chvíli držet v queue před zpracováním. Calleři musí mít
dostatečný timeout:

| Caller | Aktuálně | Doporučení |
|--------|----------|------------|
| Orchestrátor blocking | 300-1200s (tier) | OK |
| KB Graph httpx | 600s | OK |
| KB LLM (ChatOllama) | 180s | **Zvýšit na 300s** |
| Correction httpx | None (unlimited) | OK |
| KB RAG embedding | 3600s | OK |

## Princip

**Router je jediný kdo řídí zátěž.** Calleři posílají requesty volně.
Router queue + priority + preemption zajistí:
- CRITICAL (chat) nikdy nečeká za background
- Obě GPU se využijí
- CPU dostane malé modely

## Soubory

- `backend/service-orchestrator/app/llm/provider.py` — semaphore
- `backend/service-knowledgebase/app/services/graph_service.py` — paralelní chunky
- `backend/service-knowledgebase/app/services/rag_service.py` — embedding semaphore (OK)
- `backend/service-knowledgebase/app/services/knowledge_service.py` — ChatOllama timeout
- `backend/service-correction/app/agent.py` — paralelní chunky
