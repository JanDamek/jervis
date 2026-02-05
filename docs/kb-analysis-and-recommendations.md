# Knowledge Base - Analýza a Doporučení

**Datum:** 2026-02-05
**Účel:** Kritická analýza KB služby, best practices, a konkrétní doporučení

---

## 1. Shrnutí Současného Stavu

### Co je implementováno ✅
- FastAPI Python služba s Weaviate (RAG) a ArangoDB (Graph)
- Základní ingest a retrieval flow
- LLM extrakce entit a vztahů
- Filtrování podle clientId/projectId

### Co chybí nebo je špatně ❌
1. **Multi-tenant filtrování** - používá "GENERAL" string místo NULL
2. **Bidirectional linking** - RAG a Graph nejsou provázané
3. **Evidence tracing** - hrany nemají evidenceChunkIds
4. **Kanonizace entit** - chybí alias registry

---

## 2. Kritický Problém: Multi-Tenant Filtrování

### Současná implementace (ŠPATNĚ)

```python
# rag_service.py - současný kód
filters = Filter.by_property("clientId").equal("GENERAL")

if request.clientId:
    client_filter = Filter.by_property("clientId").equal(request.clientId)
    # ...
```

**Problémy:**
1. `"GENERAL"` je magic string - co když klient pojmenuje svůj účet "GENERAL"?
2. Data s `clientId=""` (prázdný string) nejsou nikdy nalezena
3. Logika je v aplikační vrstvě místo v databázi

### Správná implementace (DOPORUČENO)

**Pravidla viditelnosti:**
```
┌─────────────────────────────────────────────────────────────────┐
│ Úroveň          │ clientId │ projectId │ Viditelnost           │
├─────────────────┼──────────┼───────────┼───────────────────────┤
│ Globální        │ NULL     │ NULL      │ Všude                 │
│ Client-level    │ X        │ NULL      │ Klient X a projekty X │
│ Project-level   │ X        │ Y         │ Pouze projekt Y       │
└─────────────────────────────────────────────────────────────────┘

INVARIANT: projectId != NULL => clientId != NULL
(projekt bez klienta je zakázán)
```

**Správný dotaz při hledání pro (clientId=C, projectId=P):**
```sql
WHERE (clientId IS NULL OR clientId = 'C')
  AND (projectId IS NULL OR projectId = 'P')
```

Toto vrátí:
- Globální data (client=NULL, project=NULL) ✅
- Client-level data (client=C, project=NULL) ✅
- Project data (client=C, project=P) ✅
- Cizí client data (client=X, project=*) ❌
- Cizí project data (client=C, project=Z) ❌

**Při hledání pouze pro clienta (clientId=C, projectId=NULL):**
```sql
WHERE (clientId IS NULL OR clientId = 'C')
-- projectId se nefiltruje => vrátí všechny projekty klienta
```

### Implementace pro Weaviate

```python
# NOVÁ verze - rag_service.py
async def retrieve(self, request: RetrievalRequest) -> EvidencePack:
    vector = self.embeddings.embed_query(request.query)
    collection = self.client.collections.get("KnowledgeChunk")

    # Sestavení filtru na úrovni databáze
    # NULL reprezentujeme jako prázdný string "" (Weaviate nemá native NULL)

    # clientId filter: (clientId == "" OR clientId == request.clientId)
    global_client = Filter.by_property("clientId").equal("")

    if request.clientId:
        my_client = Filter.by_property("clientId").equal(request.clientId)
        client_filter = Filter.any_of([global_client, my_client])
    else:
        # Bez clientId => pouze globální data
        client_filter = global_client

    # projectId filter: pokud je specifikován
    if request.projectId:
        # (projectId == "" OR projectId == request.projectId)
        global_project = Filter.by_property("projectId").equal("")
        my_project = Filter.by_property("projectId").equal(request.projectId)
        project_filter = Filter.any_of([global_project, my_project])

        # Kombinace: client AND project
        filters = Filter.all_of([client_filter, project_filter])
    else:
        # Bez projectId => nefiltrujeme projekt, vrátíme všechny
        filters = client_filter

    response = collection.query.near_vector(
        near_vector=vector,
        limit=request.maxResults,
        filters=filters,
        return_metadata=MetadataQuery(distance=True)
    )
    # ...
```

### Implementace pro ArangoDB

```python
# NOVÁ verze - graph_service.py
async def traverse(self, request: TraversalRequest) -> list[GraphNode]:
    # Sestavení AQL filtru

    # clientId filter
    if request.clientId:
        client_expr = f"(v.clientId == null OR v.clientId == '' OR v.clientId == '{request.clientId}')"
    else:
        client_expr = "(v.clientId == null OR v.clientId == '')"

    # projectId filter
    if request.projectId:
        project_expr = f"(v.projectId == null OR v.projectId == '' OR v.projectId == '{request.projectId}')"
        filter_expr = f"({client_expr} AND {project_expr})"
    else:
        filter_expr = client_expr

    aql = f"""
    FOR v, e IN {request.spec.minDepth}..{request.spec.maxDepth} {request.spec.direction}
    'KnowledgeNodes/{request.startKey}'
    KnowledgeEdges
    FILTER {filter_expr}
    RETURN v
    """
    # ...
```

---

## 3. Bidirectional Linking (RAG ↔ Graph)

### Proč je to kritické?

Bez propojení:
- RAG najde text, ale neví o souvisejících entitách
- Graph najde entity, ale nemá důkazy v textu
- Agent nedokáže "spojit tečky" mezi zdroji

### Best Practice z výzkumu

Podle [Graph RAG with Neo4j](https://learnwithyan.com/neo4j/graph-rag-with-neo4j-best-practices-and-common-pitfalls/) a [Weaviate GraphRAG](https://weaviate.io/blog/graph-rag):

> "A vector similarity search is executed on the Chunk embeddings to find k most similar Chunks, then a traversal starting at the found chunks is executed to retrieve more context."

### Implementace

#### 1. Chunk → Graph (graphRefs)

Při ingestu každý chunk dostane seznam referencí na graph entity:

```python
# Při ukládání do Weaviate
chunk_properties = {
    "content": chunk_text,
    "sourceUrn": request.sourceUrn,
    "clientId": request.clientId or "",
    "projectId": request.projectId or "",
    "graphRefs": json.dumps(["jira:TASK-123", "user:john"]),  # NOVÉ
}
```

#### 2. Graph Node → Chunks (ragChunks)

Každý node v ArangoDB má seznam chunk IDs:

```python
# Při ukládání do ArangoDB
node_doc = {
    "_key": node_key,
    "label": node_label,
    "type": node_type,
    "clientId": request.clientId or "",
    "projectId": request.projectId or "",
    "ragChunks": [chunk_uuid_1, chunk_uuid_2],  # NOVÉ
}
```

#### 3. Edge → Evidence (evidenceChunkIds)

```python
# Každá hrana má důkazy
edge_doc = {
    "_key": edge_key,
    "_from": f"KnowledgeNodes/{source_key}",
    "_to": f"KnowledgeNodes/{target_key}",
    "relation": relation,
    "evidenceChunkIds": [chunk_uuid],  # NOVÉ - chunk kde byl vztah nalezen
}
```

### Upravený Ingest Flow

```
IngestRequest
    ↓
1. Chunking → chunks[] s UUID pro každý chunk
    ↓
2. LLM Extraction → nodes[], edges[] s referencemi na chunk UUID
    ↓
3. RAG Storage (Weaviate)
   - chunk.graphRefs = [extrahované entity]
    ↓
4. Graph Storage (ArangoDB)
   - node.ragChunks = [chunk UUIDs kde se entita vyskytuje]
   - edge.evidenceChunkIds = [chunk UUIDs kde byl vztah nalezen]
    ↓
IngestResult
```

### Upravený Retrieval Flow

```
Query: "Kdo pracuje na TASK-123?"
    ↓
1. RAG Search → top K chunks
    ↓
2. Extract graphRefs from chunks
   → ["jira:TASK-123", "user:john", "file:Service.kt"]
    ↓
3. Graph Traversal from seed nodes (2-hop)
   → Najde další souvislosti: team, project, related tasks
    ↓
4. Fetch ragChunks from discovered nodes
   → Další relevantní text k nalezeným entitám
    ↓
5. Combine & Deduplicate
    ↓
EvidencePack (RAG + Graph evidence)
```

---

## 4. Entity Persistence a Graph Integrity

### Problém: Informace zanikají

Současná implementace má tyto problémy:
1. **Duplicitní entity** - "User:John", "user:john", "user:John Doe" → 3 různé nodes
2. **Ztracené hrany** - pokud LLM neextrahuje obě entity, hrana se nevytvoří
3. **Žádná aktualizace** - existující node se nepřepíše novými informacemi

### Řešení: Entity Registry + Normalization

#### 1. Normalizace při ingestu

```python
def normalize_graph_ref(raw: str) -> str:
    """
    Normalizuje graph referenci do kanonické formy.

    Examples:
        "User:John  Smith" → "user:john_smith"
        "JIRA:TASK-123" → "jira:task-123"
        "order:order_530798957" → "order:530798957"
    """
    if ":" not in raw:
        return raw.lower().strip()

    namespace, value = raw.split(":", 1)
    namespace = namespace.lower().strip()
    value = value.strip()

    # Remove redundant namespace prefix
    # order:order_X → order:X
    if value.lower().startswith(namespace + "_"):
        value = value[len(namespace) + 1:]

    # Normalize whitespace and special chars
    value = re.sub(r'\s+', '_', value)
    value = re.sub(r'[^a-zA-Z0-9_\-]', '', value)

    return f"{namespace}:{value.lower()}"
```

#### 2. Alias Registry (MongoDB/ArangoDB)

```python
# Collection: entity_aliases
{
    "clientId": "client-abc",
    "aliasKey": "user:john",           # Normalizovaný alias
    "canonicalKey": "user:john.doe",   # Kanonický klíč
    "seenCount": 42,
    "lastSeenAt": "2026-02-05T10:00:00Z"
}
```

Při ingestu:
1. Normalizuj referenci
2. Zkontroluj alias registry
3. Pokud existuje alias → použij canonical key
4. Pokud ne → vytvoř nový alias s canonical = alias

#### 3. Merge při aktualizaci

```python
async def upsert_node(self, key: str, doc: dict) -> bool:
    """Upsert s merge existujících dat."""
    nodes = self.db.collection("KnowledgeNodes")

    if nodes.has(key):
        existing = nodes.get(key)
        # Merge ragChunks (append, deduplicate)
        existing_chunks = set(existing.get("ragChunks", []))
        new_chunks = set(doc.get("ragChunks", []))
        merged_chunks = list(existing_chunks | new_chunks)

        # Update with merged data
        doc["ragChunks"] = merged_chunks
        doc["updatedAt"] = datetime.utcnow().isoformat()
        nodes.update({"_key": key, **doc})
        return False  # Not new
    else:
        doc["createdAt"] = datetime.utcnow().isoformat()
        nodes.insert(doc)
        return True  # New node
```

---

## 5. Best Practices z Výzkumu

### Hybrid Retrieval Architecture

Podle [Building Production RAG Systems in 2026](https://brlikhon.engineer/blog/building-production-rag-systems-in-2026-complete-architecture-guide):

> "Hybrid retrieval is the default recommended choice in 2026. It consistently outperforms single-method pipelines for accuracy."

**Doporučení:**
1. **Vector search first** - rychlé získání kandidátů
2. **Graph expansion second** - obohacení o strukturované vztahy
3. **Reranking** - kombinace skóre z obou zdrojů

### GraphRAG Pattern

Podle [Neo4j GraphRAG](https://neo4j.com/nodes-2025/agenda/enhancing-retrieval-augmented-generation-with-graphrag-patterns-in-neo4j/):

> "Real-world benchmarks show a 20–35% improvement in retrieval precision over traditional RAG when using GraphRAG patterns."

**Klíčové principy:**
1. **Bidirectional traversal** - successors AND predecessors
2. **Source traceability** - každý výsledek má citaci
3. **Multi-hop reasoning** - propojení napříč zdroji

### Multi-Tenant v Weaviate

Podle [Weaviate Multi-tenancy](https://weaviate.io/blog/multi-tenancy-vector-search):

> "Rather than using filters to scope queries to tenants, you don't need to set a filter—the simple addition of a tenant key is enough."

**Úvaha:** Pro produkční systém zvážit Weaviate native multi-tenancy místo property filtrů. Ale pro hierarchii client/project (ne flat tenants) je property filter správný přístup.

---

## 6. Konkrétní Akční Body

### Priorita 1: Opravit Multi-Tenant Filtering

1. ❌ Odstranit "GENERAL" magic string
2. ✅ Použít NULL/prázdný string pro globální data
3. ✅ Filtrovat na úrovni databáze: `(clientId IS NULL OR clientId = X)`
4. ✅ Přidat validaci: project bez clienta je zakázán

### Priorita 2: Implementovat Bidirectional Linking

1. Přidat `graphRefs` property do Weaviate schema
2. Přidat `ragChunks` property do ArangoDB nodes
3. Přidat `evidenceChunkIds` property do ArangoDB edges
4. Upravit ingest flow pro propagaci referencí

### Priorita 3: Entity Normalization

1. Implementovat `normalize_graph_ref()` funkci
2. Vytvořit alias registry collection
3. Lookup aliasů při ingestu i retrieval

### Priorita 4: Retrieval Enhancement

1. Po RAG search extrahovat graphRefs z chunků
2. Spustit graph traversal ze seed nodes
3. Kombinovat a deduplikovat výsledky
4. Implementovat reranking

---

## 7. Schema Changes

### Weaviate - KnowledgeChunk (aktualizované)

```python
properties=[
    Property(name="content", data_type=DataType.TEXT),
    Property(name="sourceUrn", data_type=DataType.TEXT),
    Property(name="clientId", data_type=DataType.TEXT),      # "" = global
    Property(name="projectId", data_type=DataType.TEXT),     # "" = client-level
    Property(name="kind", data_type=DataType.TEXT),          # NOVÉ
    Property(name="graphRefs", data_type=DataType.TEXT_ARRAY), # NOVÉ - ["jira:X", "user:Y"]
    Property(name="observedAt", data_type=DataType.DATE),    # NOVÉ - temporal queries
]
```

### ArangoDB - KnowledgeNodes (aktualizované)

```json
{
    "_key": "jira:task-123",
    "label": "TASK-123: Fix login bug",
    "type": "jira_issue",
    "clientId": "",           // "" = global
    "projectId": "",          // "" = client-level
    "ragChunks": ["uuid1", "uuid2"],  // NOVÉ
    "properties": {},         // Arbitrary type-specific data
    "createdAt": "2026-02-05T10:00:00Z",
    "updatedAt": "2026-02-05T12:00:00Z"
}
```

### ArangoDB - KnowledgeEdges (aktualizované)

```json
{
    "_key": "jira:task-123_assigned_to_user:john",
    "_from": "KnowledgeNodes/jira:task-123",
    "_to": "KnowledgeNodes/user:john",
    "relation": "assigned_to",
    "evidenceChunkIds": ["uuid1"],  // NOVÉ
    "createdAt": "2026-02-05T10:00:00Z"
}
```

---

## 8. Zdroje

- [RAG in 2026: Bridging Knowledge and Generative AI](https://squirro.com/squirro-blog/state-of-rag-genai)
- [Building Production RAG Systems in 2026](https://brlikhon.engineer/blog/building-production-rag-systems-in-2026-complete-architecture-guide)
- [GraphRAG Practical Guide](https://learnopencv.com/graphrag-explained-knowledge-graphs-medical/)
- [Weaviate Multi-tenancy](https://weaviate.io/blog/multi-tenancy-vector-search)
- [Neo4j GraphRAG Best Practices](https://learnwithyan.com/neo4j/graph-rag-with-neo4j-best-practices-and-common-pitfalls/)
- [Weaviate + Neo4j Integration](https://weaviate.io/blog/graph-rag)
- [ArangoDB Filter Operations](https://www.arangodb.com/docs/stable/aql/operations-filter.html)
