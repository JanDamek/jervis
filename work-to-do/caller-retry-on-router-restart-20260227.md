# KB + Correction — chybí retry při výpadku routeru

**Priorita**: MEDIUM
**Status**: RESOLVED (2026-02-27)

### Implementation summary

- KB Graph `_llm_call()`: retry 2× with backoff 2-4s on ConnectError/RemoteProtocolError/OSError
- KB RAG `_embed_with_priority()`: retry 2× with backoff 2-4s, semaphore re-acquired on retry
- Correction `_call_ollama()`: extracted `_call_ollama_stream()`, retry 2× with backoff 2-4s

---

## Problém

Router queue je **in-memory**. Při restartu podu (K8s rolling update, OOM, crash)
se ztratí rozpracované requesty. Orchestrátor a chat mají retry (`_call_with_retry`,
3 pokusy, exponential backoff). KB a Correction **ne**.

## Stav

| Služba | Retry | Důsledek výpadku routeru |
|--------|-------|--------------------------|
| Orchestrátor | ANO (3 pokusy, backoff) | OK — retryuje |
| Chat | ANO (přes orchestrátor) | OK |
| KB Graph | **NE** | Chunky tiše selžou, neúplný graf |
| KB RAG Embedding | **NE** | Celý ingest padne |
| Correction | **NE** | Tiše vrátí neopravený text |

## Co opravit

### 1. KB Graph — `graph_service.py:_llm_call()`

Přidat retry wrapper kolem `httpx.post()`:

```python
async def _llm_call(self, prompt: str, priority: int | None = None, max_retries: int = 2) -> str:
    for attempt in range(1 + max_retries):
        try:
            resp = await self.http_client.post(url, json=payload, headers=headers, timeout=600.0)
            resp.raise_for_status()
            return resp.json()["response"]
        except (httpx.ConnectError, httpx.RemoteProtocolError, OSError) as e:
            if attempt < max_retries:
                wait = 2 ** (attempt + 1)
                logger.warning("LLM call failed (attempt %d/%d): %s, retrying in %ds",
                    attempt + 1, max_retries + 1, e, wait)
                await asyncio.sleep(wait)
            else:
                raise
```

### 2. KB RAG Embedding — `rag_service.py:_embed_with_priority()`

Stejný pattern — retry na connection error:

```python
async def _embed_with_priority(self, text: str, priority: int | None = None, max_retries: int = 2) -> list:
    for attempt in range(1 + max_retries):
        try:
            async with self._embedding_semaphore:
                resp = await self.http_client.post(url, json=payload, headers=headers)
                resp.raise_for_status()
                return resp.json().get("embeddings", [])
        except (httpx.ConnectError, httpx.RemoteProtocolError, OSError) as e:
            if attempt < max_retries:
                await asyncio.sleep(2 ** (attempt + 1))
            else:
                raise
```

### 3. Correction — `agent.py:_call_ollama()`

Retry na streaming connection error:

```python
async def _call_ollama(self, messages, ...):
    for attempt in range(3):
        try:
            async with http_client.stream("POST", ...) as response:
                response.raise_for_status()
                # ... process stream
                return result
        except (httpx.ConnectError, httpx.RemoteProtocolError, OSError) as e:
            if attempt < 2:
                await asyncio.sleep(2 ** (attempt + 1))
            else:
                raise
```

## Poznámka

Router restart trvá ~10s. Retry s backoff 2-4-8s pokryje většinu restartů.
Persistence queue v routeru **není potřeba** — retry v callerech je jednodušší
a pokrývá i jiné chyby (network, OOM, atd.).

## Soubory

- `backend/service-knowledgebase/app/services/graph_service.py` — `_llm_call()`
- `backend/service-knowledgebase/app/services/rag_service.py` — `_embed_with_priority()`
- `backend/service-correction/app/agent.py` — `_call_ollama()`
