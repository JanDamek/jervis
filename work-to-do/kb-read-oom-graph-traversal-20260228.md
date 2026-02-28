# KB Read — OOM Kill (Exit Code 137) při graph traversalech

**Priorita**: HIGH
**Status**: OPEN
**Symptom**: Pod `jervis-knowledgebase-read` má 48 restartů, Exit Code 137 (OOM killed, limit 2Gi)

---

## Příčina

Graph traversal v `graph_service.py` nemá **LIMIT** v AQL dotazu. Vysokostupňové uzly
(např. `user:*`, `property:*`) vrací 1000+ výsledků, vše se materializuje v paměti.

Normální provoz: ~216Mi. Burst traffic s velkými traversaly → spike přes 2Gi → OOM kill.

## Kritické body (v pořadí dopadu)

### 1. Traversal bez LIMITu — `graph_service.py:557-595`

```python
# CHYBÍ LIMIT — vrací VŠECHNY sousedy
FOR v, e, p IN @minDepth..@maxDepth ANY @startNode KnowledgeEdges
FILTER ...
RETURN {vertex: v, depth: LENGTH(p.edges)}
```

**Fix**: Přidat `LIMIT @maxResults` do AQL, default 100-200 per traversal.

### 2. Hybrid retriever akumuluje výsledky — `hybrid_retriever.py:290-361`

`_expand_via_graph()` dělá až 10 traversalů (max_seeds=10) a akumuluje
vše do jednoho listu. 10 seedů × 1000+ uzlů = 10k+ objektů v paměti.

**Fix**: Limit per seed + celkový cap na akumulované výsledky.

### 3. get_chunks_by_ids bez limitu — `rag_service.py`

Fetchuje z Weaviate všechny chunk ID reference z grafu, po jednom, bez limitu.

**Fix**: Batch + cap na počet chunk IDs.

### 4. Chybějící konfigurace — `config.py`

Přidat:
```python
MAX_GRAPH_TRAVERSAL_RESULTS: int = 200
MAX_GRAPH_EXPANSION_TOTAL: int = 500
MAX_CHUNK_FETCH_BATCH: int = 100
```

## Soubory

- `backend/service-knowledgebase/app/services/graph_service.py` — AQL LIMIT
- `backend/service-knowledgebase/app/services/hybrid_retriever.py` — expansion cap
- `backend/service-knowledgebase/app/services/rag_service.py` — chunk fetch limit
- `backend/service-knowledgebase/app/core/config.py` — nové limity
