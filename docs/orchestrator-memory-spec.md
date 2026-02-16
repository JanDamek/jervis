# Orchestrator Memory Architecture — Specifikace

> **Status**: Implementováno (Phase 1, 3, 4) — Phase 2 (KB extensions) deferred, Phase 5 (testing) pending
> **Cíl**: Orchestrátor musí pracovat jako inteligentní asistent s dlouhodobou pamětí,
> schopný přepínat kontexty, ukládat a vybavovat si informace přesně a rychle,
> a to vše v rámci 48k tokenového okna.

---

## 1. Analýza problému

### 1.1 Současný stav

Orchestrátor dnes pracuje s kontextem takto:

```
User query + chat_history (recent 5-10 msgs + compressed summaries)
           + project_context (KB: structure, architecture)
           + user_context (KB: user_knowledge_* categories)
           + evidence_pack (KB: semantic search results)
           → LLM (48k-256k context)
           → odpověď
```

**Co funguje dobře:**
- Persistent state přes MongoDB checkpointing
- Chat history compression (LLM-based, 500 chars/blok)
- Semantic KB retrieval pro evidence
- Tool-use loop (max 8 iterací) s kb_search + web_search
- Outcome ingestion po dokončení tasku (task_outcome → KB)

### 1.2 Co nefunguje

#### A) Žádné explicitní řízení kontextu při změně tématu

Uživatel řekne: _"Teď něco jiného — přihlásil jsem se na školu..."_

Orchestrátor:
- **Nerozpozná** explicitní přepnutí kontextu
- **Neuloží** aktuální kontext (eBay objednávka) pod jasným klíčem
- **Nenačte** relevantní kontext pro nové téma
- Výsledek: míchá eBay objednávku s univerzitou Unicorn

#### B) Žádná working memory mezi turn-y

Orchestrátor nemá koncept "co právě řeším". Každý turn:
1. Dostane chat_history (posledních 5-10 zpráv)
2. Prefetchne project_context + user_context
3. Volitelně hledá v KB přes tool-use

Ale **nemá strukturovanou working memory** — aktuální kontext, seznam otevřených záležitostí, co je důležité si pamatovat.

#### C) KB write je fire-and-forget s nízkou prioritou

- `store_knowledge` → POST /ingest → SQLite queue → embedding worker (Priority 4)
- Zapsaná data **nejsou okamžitě dostupná** pro čtení (embedding trvá sekundy až minuty)
- Orchestrátor zapíše kontext a hned ho nemůže přečíst zpět

#### D) Žádná local cache / quick memory

- Každý KB read = HTTP request + Weaviate vector search
- Opakované dotazy na stejná data = zbytečná latence
- Nedávno zapsaná data musí projít celou pipeline (ingest → queue → embed → Weaviate)

#### E) Outcome ingestion je příliš selektivní

- Jen SINGLE_TASK s code/tracker_ops + EPIC + GENERATIVE
- **ADVICE (chat) se neukládá** — přitom tam jsou cenné informace (objednávky, rozhodnutí, kontakty)
- `is_significant_task()` filtruje příliš agresivně

#### F) Kontext se nestrukturuje podle témat/záležitostí

KB ukládá data jako ploché chunks s `kind` a `sourceUrn`. Ale chybí:
- **Záležitosti** (affairs/topics) jako first-class entity
- Vazby mezi záležitostmi a jejich historií
- Schopnost načíst "vše k záležitosti X" jedním dotazem

---

## 2. Cílová architektura

### 2.1 Koncept: Memory Agent

Nový **Memory Agent** je dedikovaný sub-agent orchestrátoru, který řeší veškerou práci s pamětí.

```
┌──────────────────────────────────────────────────────────────┐
│                      ORCHESTRÁTOR                             │
│                                                                │
│  intake → [Memory Agent: load context] → respond/plan/...    │
│                                                                │
│  respond ←→ [Memory Agent: search, store, switch context]    │
│                                                                │
│  finalize → [Memory Agent: persist session] → done           │
└──────────────────────────────────────────────────────────────┘
         │                                    │
         ↓                                    ↓
┌─────────────────────┐         ┌──────────────────────────┐
│   Local Quick Memory │         │      Knowledge Base       │
│   (RAM, per-process) │ ←sync→ │  (Weaviate + ArangoDB)    │
│                       │         │                            │
│  • Active affairs     │         │  • Indexed chunks          │
│  • Recent KB results  │         │  • Knowledge graph         │
│  • Session context    │         │  • User knowledge          │
│  • Write buffer       │         │  • Task outcomes           │
│  (max ~2GB RAM)       │         │  • Affairs (NEW)           │
└─────────────────────┘         └──────────────────────────┘
```

### 2.2 Klíčové koncepty

#### A) Záležitost (Affair)

Záležitost je **tematický kontejner** pro souvislé informace:

```python
@dataclass
class Affair:
    id: str                          # UUID
    title: str                       # "Objednávka dílu na Jeep z eBay"
    summary: str                     # Aktuální shrnutí (LLM-generated)
    status: AffairStatus             # ACTIVE | PARKED | RESOLVED | ARCHIVED
    topics: list[str]                # ["auto", "jeep", "ebay", "objednávka"]
    key_facts: dict[str, str]        # {"objednávka": "07-14244-53150", "cena": "$38.10"}
    pending_actions: list[str]       # ["sledovat doručení", "zkontrolovat 20.2."]
    related_affairs: list[str]       # UUID references
    messages: list[AffairMessage]    # Historie zpráv relevantních k záležitosti
    created_at: datetime
    updated_at: datetime
    client_id: str
    project_id: str | None
```

**Stavy:**
- `ACTIVE` — právě se řeší (max 1 v daný moment)
- `PARKED` — odložená, ale živá (čeká na doručení, na odpověď)
- `RESOLVED` — vyřešená (archivovat do KB)
- `ARCHIVED` — v KB, ne v RAM

#### B) Session Context

Working memory pro aktuální session:

```python
@dataclass
class SessionContext:
    active_affair: Affair | None         # Co právě řeším
    parked_affairs: list[Affair]         # Odložené záležitosti
    recent_kb_results: LRUCache          # Cache KB výsledků (key=query, TTL=5min)
    write_buffer: list[PendingWrite]     # Čeká na zápis do KB
    conversation_topics: list[str]       # Témata z aktuální konverzace
    user_preferences: dict               # Cached user knowledge
    last_context_switch_at: datetime     # Kdy naposledy přepnul kontext
```

### 2.3 Local Quick Memory (LQM)

In-process RAM cache s write-through do KB:

```
┌─────────────────────────────────────────┐
│           Local Quick Memory (LQM)       │
│                                           │
│  Layer 1: Hot Cache (dict, ~100MB)       │
│  • Active + parked affairs               │
│  • Recent KB search results              │
│  • User context (prefetched)             │
│  • Project context (prefetched)          │
│                                           │
│  Layer 2: Write Buffer (queue, ~50MB)    │
│  • Pending KB writes (affair updates)    │
│  • Store-and-forward: write to LQM       │
│    immediately, async flush to KB        │
│                                           │
│  Layer 3: Warm Cache (LRU, ~500MB)       │
│  • Previously loaded affairs             │
│  • KB search results (TTL 10min)         │
│  • Full text of recently read files      │
│                                           │
│  Layer 4: Cold Store (mmap, ~2GB)        │
│  • Serialized archived affairs           │
│  • Loaded on demand                      │
│  Max total: ~2.5GB RAM per orchestrator  │
└─────────────────────────────────────────┘
```

**Write-through strategie:**

1. **Okamžitý zápis do LQM** — data jsou ihned dostupná pro čtení
2. **Async flush do KB** — na pozadí, s CRITICAL prioritou
3. **Potvrzení zápisu** — LQM sleduje, zda KB potvrdil zápis
4. **Fallback na LQM** — pokud KB ještě nezindexoval, čte se z LQM

---

## 3. Memory Agent — Detailní návrh

### 3.1 Rozhraní Memory Agenta

Memory Agent je **Python třída** volaná orchestrátorem, ne samostatný service:

```python
class MemoryAgent:
    """Manages orchestrator's working memory and KB interactions."""

    def __init__(self, client_id: str, project_id: str | None):
        self.lqm = LocalQuickMemory(max_ram_gb=2.5)
        self.kb_client = KBClient(read_url, write_url)
        self.session = SessionContext()

    # === Context Management ===

    async def load_session(self, chat_history: ChatHistoryPayload) -> SessionContext:
        """Na začátku orchestrace: načti/obnov session context.
        1. Načti active + parked affairs z LQM (nebo KB pokud cold start)
        2. Prefetch user_context + project_context
        3. Analyzuj poslední zprávy → detekuj aktivní záležitost
        """

    async def detect_context_switch(self, user_message: str) -> ContextSwitchResult:
        """Analyzuj zprávu: jde o pokračování, přepnutí, nebo ad-hoc dotaz?

        Returns:
            ContextSwitchResult:
                type: CONTINUE | SWITCH | AD_HOC | NEW_AFFAIR
                target_affair_id: str | None  # pokud SWITCH, kam
                confidence: float
                reasoning: str
        """

    async def switch_context(self, result: ContextSwitchResult) -> str:
        """Provede přepnutí kontextu:
        1. PARK aktuální affair (uloží stav)
        2. ACTIVATE cílovou affair (načte kontext)
        3. Vrátí kontext pro LLM prompt
        """

    async def handle_ad_hoc(self, query: str) -> str:
        """Ad-hoc dotaz (nepřepíná kontext):
        1. Hledej odpověď v parked affairs + KB
        2. Odpověz
        3. Vrať se k active affair
        """

    # === KB Operations (with LQM cache) ===

    async def search(self, query: str, scope: str = "all") -> list[SearchResult]:
        """KB search s LQM cache.
        1. Check LQM hot cache
        2. Check LQM write buffer (nedávno zapsané, ještě ne v KB)
        3. KB semantic search
        4. Cache výsledek v LQM
        """

    async def store(self, subject: str, content: str,
                    category: str, priority: WritePriority = NORMAL) -> str:
        """Uloží do LQM + async flush do KB.
        CRITICAL priority → immediate KB write + embedding
        NORMAL priority → buffered write
        """

    async def store_affair(self, affair: Affair) -> None:
        """Uloží/aktualizuje affair do LQM + KB.
        Affair je uložen jako strukturovaný dokument s jasnými klíči.
        """

    # === Affair Lifecycle ===

    async def create_affair(self, title: str, initial_context: str) -> Affair:
        """Vytvoří novou záležitost z prvních zpráv."""

    async def park_affair(self, affair_id: str, summary: str | None = None) -> None:
        """Odloží záležitost: LLM generuje summary + key_facts."""

    async def resume_affair(self, affair_id: str) -> Affair:
        """Obnoví záležitost: načte z LQM/KB, vrátí plný kontext."""

    async def resolve_affair(self, affair_id: str) -> None:
        """Uzavře záležitost: finální summary → KB, archive."""

    # === Context Composition ===

    async def compose_context(self, max_tokens: int = 8000) -> str:
        """Složí kontext pro LLM prompt z:
        1. Active affair (summary + key_facts + recent messages)
        2. Parked affairs (jen tituly + pending_actions)
        3. User context (preferences, domain)
        4. Relevant KB evidence

        Prioritizace: active affair dostane nejvíc prostoru,
        parked affairs jen headers, KB evidence jen pokud relevant.
        Token budget se dynamicky alokuje.
        """

    # === Session Persistence ===

    async def flush_session(self) -> None:
        """Na konci orchestrace: persist vše do KB.
        1. Flush write buffer
        2. Update all affairs v KB
        3. Store session metadata
        """

    async def flush_write_buffer(self) -> None:
        """Background task: periodicky flushuje write buffer do KB."""
```

### 3.2 Context Switch Detection

**Klíčová logika** — LLM-based klasifikace vstupu uživatele:

```python
CONTEXT_SWITCH_PROMPT = """
Analyzuj zprávu uživatele v kontextu aktuální konverzace.

AKTUÁLNÍ ZÁLEŽITOST: {active_affair_title}
AKTUÁLNÍ KONTEXT: {active_affair_summary}
ODLOŽENÉ ZÁLEŽITOSTI: {parked_affair_titles}

ZPRÁVA UŽIVATELE: {user_message}

Urči typ zprávy:
1. CONTINUE — pokračuje v aktuální záležitosti
2. SWITCH — přepíná na jinou (existující odloženou) záležitost
3. AD_HOC — jednorázový dotaz, po odpovědi se vrátí k aktuální záležitosti
4. NEW_AFFAIR — začíná zcela novou záležitost

Klíčové signály:
- "teď něco jiného", "jiná věc", "nové téma" → SWITCH nebo NEW_AFFAIR
- "mimochodem", "hele", "jen rychle" → AD_HOC
- Otázka na odloženou záležitost → AD_HOC (odpověz a vrať se)
- Pokračování v tématu → CONTINUE

Odpověz JSON:
{
    "type": "CONTINUE|SWITCH|AD_HOC|NEW_AFFAIR",
    "target_affair": "název existující záležitosti pokud SWITCH, jinak null",
    "reasoning": "krátké zdůvodnění",
    "new_affair_title": "název pokud NEW_AFFAIR, jinak null"
}
"""
```

**Optimalizace**: Tato klasifikace běží na LOCAL_FAST tier (qwen3:30b, malý prompt) — latence ~1-2s.

### 3.3 Affair Parking (ukládání kontextu)

Když se záležitost parkuje (přepnutí kontextu):

```python
async def park_affair(self, affair_id: str) -> None:
    affair = self.session.active_affair

    # 1. LLM generuje shrnutí aktuálního stavu
    summary_result = await self._summarize_affair(affair)
    affair.summary = summary_result["summary"]
    affair.key_facts = summary_result["key_facts"]
    affair.pending_actions = summary_result["pending_actions"]
    affair.status = AffairStatus.PARKED
    affair.updated_at = datetime.utcnow()

    # 2. Uložit do LQM (okamžitě dostupné)
    self.lqm.store_affair(affair)

    # 3. Async flush do KB s CRITICAL prioritou
    await self.kb_client.ingest(
        source_urn=f"affair:{affair.id}",
        kind="affair",
        content=affair.to_kb_document(),
        priority=Priority.CRITICAL,  # Okamžité zpracování
        metadata={
            "affair_id": affair.id,
            "title": affair.title,
            "status": "PARKED",
            "topics": ",".join(affair.topics),
        }
    )

    # 4. Clear active
    self.session.active_affair = None
```

### 3.4 Context Composition pro LLM

Dynamická alokace token budgetu:

```python
async def compose_context(self, max_tokens: int = 8000) -> str:
    sections = []
    remaining = max_tokens

    # 1. Active affair (40% budgetu)
    if self.session.active_affair:
        affair = self.session.active_affair
        budget = int(max_tokens * 0.4)
        sections.append(f"""
## Aktuální záležitost: {affair.title}
**Stav**: {affair.status.value}
**Shrnutí**: {affair.summary}
**Klíčové fakta**: {self._format_key_facts(affair.key_facts)}
**Čeká na**: {', '.join(affair.pending_actions) or 'nic'}

### Poslední zprávy k této záležitosti:
{self._format_recent_messages(affair.messages, budget)}
""")
        remaining -= self._estimate_tokens(sections[-1])

    # 2. Parked affairs — jen přehled (10% budgetu)
    if self.session.parked_affairs:
        budget = int(max_tokens * 0.1)
        parked_list = "\n".join([
            f"- **{a.title}** ({a.status.value}): {a.pending_actions[0] if a.pending_actions else 'žádná akce'}"
            for a in self.session.parked_affairs[:5]
        ])
        sections.append(f"""
## Odložené záležitosti:
{parked_list}
""")
        remaining -= self._estimate_tokens(sections[-1])

    # 3. User context (15% budgetu)
    if self.session.user_preferences:
        budget = int(max_tokens * 0.15)
        sections.append(self._format_user_context(budget))
        remaining -= self._estimate_tokens(sections[-1])

    # 4. KB evidence — zbytek budgetu
    if remaining > 500:
        evidence = await self._fetch_relevant_evidence(remaining)
        if evidence:
            sections.append(f"""
## Relevantní kontext z KB:
{evidence}
""")

    return "\n".join(sections)
```

---

## 4. KB Priority System

### 4.1 Současné priority (Ollama Router)

| Priority | Název | Použití |
|----------|-------|---------|
| 1 | CRITICAL / ORCHESTRATOR_EMBEDDING | Orchestrátor query embedding |
| 2 | HIGH | — |
| 3 | NORMAL | Standardní požadavky |
| 4 | BACKGROUND | KB ingest embedding |

### 4.2 Nové priority pro Memory Agent

| Priority | Název | Použití | SLA |
|----------|-------|---------|-----|
| 1 | CRITICAL | Orchestrátor live query | <2s |
| 1 | MEMORY_WRITE_CRITICAL | Affair parking/switching (musí být okamžitě indexováno) | <3s |
| 2 | MEMORY_WRITE_HIGH | Session flush, affair updates | <10s |
| 3 | NORMAL | Standardní KB operace | <30s |
| 4 | BACKGROUND | Bulk ingest, re-indexing | best-effort |

### 4.3 Implementace v KB

Nový endpoint pro prioritní zápis:

```
POST /api/v1/ingest-immediate
{
    "sourceUrn": "affair:uuid",
    "clientId": "...",
    "content": "...",
    "kind": "affair",
    "priority": 1,        // CRITICAL → skip queue, embed + index synchronně
    "metadata": {...}
}
```

**Chování při `priority=1` (CRITICAL):**
1. **Synchronní embedding** — volá Ollama přímo, ne přes queue
2. **Synchronní Weaviate upsert** — okamžitě searchable
3. **Synchronní ArangoDB update** — okamžitě v grafu
4. **Response až po indexaci** — caller ví, že data jsou ready

**Chování při `priority=2` (HIGH):**
1. **Zařazení na začátek queue** — před NORMAL a BACKGROUND
2. **Response okamžitě** — ale s estimated_ready_at

### 4.4 LQM jako synchronní read-through cache

```python
class LocalQuickMemory:
    """In-process memory with write-through to KB."""

    def __init__(self, max_ram_gb: float = 2.5):
        self._affairs: dict[str, Affair] = {}           # Hot: active + parked
        self._search_cache: LRUCache = LRUCache(1000)   # Warm: recent searches
        self._write_buffer: asyncio.Queue = Queue()       # Pending KB writes
        self._kb_synced: set[str] = set()                # URNs confirmed in KB

    def read(self, key: str) -> Any | None:
        """Synchronní čtení z RAM — 0ms latence."""
        return self._affairs.get(key) or self._search_cache.get(key)

    async def search(self, query: str, kb_client) -> list[SearchResult]:
        """Search s cache + write buffer fallback:
        1. Check search_cache (hit → return)
        2. Search write_buffer (nedávno zapsané, ještě ne v KB)
        3. KB semantic search
        4. Merge results (write_buffer hits mají vyšší relevanci)
        5. Cache result
        """
        # Check cache
        cached = self._search_cache.get(query)
        if cached:
            return cached

        # Search KB
        kb_results = await kb_client.retrieve(query)

        # Search write buffer (items not yet in KB)
        buffer_results = self._search_write_buffer(query)

        # Merge: buffer results first (fresher), then KB
        merged = self._merge_results(buffer_results, kb_results)

        # Cache
        self._search_cache.put(query, merged, ttl_seconds=300)
        return merged

    def _search_write_buffer(self, query: str) -> list[SearchResult]:
        """Simple keyword match on buffered writes.
        Not semantic — but catches exact matches on recently stored data.
        """
        results = []
        query_lower = query.lower()
        for item in self._write_buffer._queue:
            if query_lower in item.content.lower():
                results.append(SearchResult(
                    content=item.content,
                    source_urn=item.source_urn,
                    confidence=0.95,  # High confidence — exact match
                ))
        return results
```

---

## 5. Integrace do orchestrátoru

### 5.1 Změny v orchestrator flow

```
PŘED:
  intake → evidence_pack → respond → finalize

PO:
  intake → memory_load → [context_detect → memory_switch?] → respond → memory_flush → finalize
```

**Nové nody:**

1. **`memory_load`** (po intake, před respond):
   - Inicializuje MemoryAgent
   - Načte session context z LQM/KB
   - Načte active + parked affairs
   - Prefetchne user_context + project_context

2. **`context_detect`** (v respond node, před LLM voláním):
   - Zavolá `memory_agent.detect_context_switch(user_message)`
   - Pokud SWITCH → `memory_agent.switch_context()`
   - Pokud NEW_AFFAIR → `memory_agent.create_affair()`
   - Pokud AD_HOC → `memory_agent.handle_ad_hoc()`
   - Pokud CONTINUE → pokračuj s aktuálním kontextem

3. **`memory_flush`** (po respond, před finalize):
   - Update active affair (nové zprávy, key_facts)
   - Flush write buffer do KB
   - Persist session state

### 5.2 Změny v respond node

```python
# respond.py — upravený flow

async def respond(state: OrchestratorState) -> dict:
    memory = state.get("memory_agent")  # Initialized in memory_load

    # 1. Detect context switch
    user_message = state["task"]["query"]
    switch_result = await memory.detect_context_switch(user_message)

    if switch_result.type == ContextSwitchType.SWITCH:
        await memory.switch_context(switch_result)
    elif switch_result.type == ContextSwitchType.NEW_AFFAIR:
        await memory.create_affair(
            title=switch_result.new_affair_title,
            initial_context=user_message,
        )
    elif switch_result.type == ContextSwitchType.AD_HOC:
        # Ad-hoc: odpověz a vrať se
        ad_hoc_context = await memory.handle_ad_hoc(user_message)
        # Přidej ad_hoc_context do LLM promptu

    # 2. Compose context (z memory agent, ne z raw KB)
    context_block = await memory.compose_context(max_tokens=8000)

    # 3. Build messages pro LLM
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_message + "\n\n" + context_block},
    ]

    # 4. Tool-use loop (memory agent tools + existing tools)
    # Memory agent poskytuje: memory_search, memory_store, list_affairs, recall_affair
    tools = ALL_RESPOND_TOOLS + MEMORY_TOOLS

    # ... existing tool loop ...

    # 5. After response: update affair
    if memory.session.active_affair:
        memory.session.active_affair.messages.append(
            AffairMessage(role="user", content=user_message)
        )
        memory.session.active_affair.messages.append(
            AffairMessage(role="assistant", content=final_result)
        )

    # 6. Flush
    await memory.flush_write_buffer()

    return {"final_result": final_result, "memory_agent": memory}
```

### 5.3 Nové tools pro respond node

```python
MEMORY_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "memory_store",
            "description": "Ulož důležitou informaci do paměti. "
                "Použij pro fakta, rozhodnutí, objednávky, termíny, kontakty. "
                "Data jsou okamžitě dostupná pro budoucí dotazy.",
            "parameters": {
                "type": "object",
                "properties": {
                    "subject": {"type": "string", "description": "Klíč/téma (např. 'eBay objednávka dílu')"},
                    "content": {"type": "string", "description": "Co uložit"},
                    "category": {"type": "string", "enum": [
                        "fact", "decision", "order", "deadline",
                        "contact", "preference", "procedure"
                    ]},
                    "affair_id": {"type": "string", "description": "ID záležitosti, pokud patří k nějaké"},
                },
                "required": ["subject", "content", "category"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "memory_recall",
            "description": "Vybav si informaci z paměti. Hledá v aktuální záležitosti, "
                "odložených záležitostech i v KB. Vrací nejrelevantnější výsledky.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Co hledáš"},
                    "scope": {"type": "string", "enum": ["current", "all", "kb_only"],
                             "description": "Kde hledat: current=aktuální záležitost, all=vše, kb_only=jen KB"}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_affairs",
            "description": "Zobraz seznam záležitostí (aktivní + odložené). "
                "Použij když uživatel chce přehled co vše řeší.",
            "parameters": {"type": "object", "properties": {}}
        }
    },
]
```

---

## 6. KB rozšíření

### 6.1 Nový kind: `affair`

```python
# KB ingest format pro affair
{
    "sourceUrn": "affair:{affair_id}",
    "clientId": "...",
    "projectId": None,  # Affairs jsou client-level
    "kind": "affair",
    "content": """
# Záležitost: Objednávka dílu na Jeep z eBay

## Stav: PARKED

## Shrnutí
Objednán díl "Nožní sprška na mytí přední nápravy" z eBay za $38.10.
Objednávka č. 07-14244-53150, čeká na odeslání.

## Klíčové fakta
- Produkt: Nožní sprška na mytí přední nápravy Jeep Grand Cherokee 2014
- eBay položka: 167956961209
- Cena: $38.10
- Číslo objednávky: 07-14244-53150
- Stav: Čeká na odeslání
- Datum objednávky: 15.02.2026

## Čekající akce
- Sledovat doručení
- Zkontrolovat stav objednávky 20.02.2026
""",
    "metadata": {
        "affair_id": "uuid",
        "title": "Objednávka dílu na Jeep z eBay",
        "status": "PARKED",
        "topics": "auto,jeep,ebay,objednávka,díl",
        "created_at": "2026-02-15T21:00:00Z",
        "updated_at": "2026-02-15T21:30:00Z",
    }
}
```

### 6.2 Nový endpoint: `/api/v1/ingest-immediate`

Synchronní ingest s okamžitou indexací:

```python
@app.post("/api/v1/ingest-immediate")
async def ingest_immediate(request: IngestRequest):
    """Synchronní ingest — data ihned searchable.
    Použití: Memory Agent CRITICAL writes (affair parking, context switch).
    """
    # 1. Chunk content
    chunks = chunk_content(request.content, chunk_size=1000, overlap=200)

    # 2. Embed ALL chunks synchronně (Ollama, Priority CRITICAL)
    embeddings = await embed_chunks_sync(chunks, priority="critical")

    # 3. Upsert to Weaviate synchronně
    await weaviate_upsert_sync(chunks, embeddings, request.metadata)

    # 4. Update ArangoDB graph synchronně
    await graph_upsert_sync(request.source_urn, request.metadata)

    # 5. Return confirmation
    return {"status": "indexed", "chunks": len(chunks)}
```

### 6.3 Nový endpoint: `/api/v1/affairs`

CRUD pro záležitosti:

```
GET    /api/v1/affairs?client_id=X&status=ACTIVE,PARKED
POST   /api/v1/affairs                              # Create
PUT    /api/v1/affairs/{id}                          # Update
DELETE /api/v1/affairs/{id}                          # Archive
GET    /api/v1/affairs/{id}/search?query=...         # Search within affair
```

---

## 7. Implementační plán

### Fáze 1: Základ Memory Agenta (2-3 dny) ✅ DONE

| # | Úkol | Soubory |
|---|------|---------|
| 1.1 | Definovat `Affair`, `SessionContext`, `AffairStatus` modely | `orchestrator/app/memory/models.py` (NEW) |
| 1.2 | Implementovat `LocalQuickMemory` (RAM cache, write buffer, LRU) | `orchestrator/app/memory/lqm.py` (NEW) |
| 1.3 | Implementovat `MemoryAgent` základní rozhraní | `orchestrator/app/memory/agent.py` (NEW) |
| 1.4 | Context switch detection (LLM prompt + parsing) | `orchestrator/app/memory/context_switch.py` (NEW) |
| 1.5 | Affair lifecycle (create, park, resume, resolve) | `orchestrator/app/memory/affairs.py` (NEW) |
| 1.6 | Context composition (token budgeting, priority sections) | `orchestrator/app/memory/composer.py` (NEW) |

### Fáze 2: KB rozšíření (1-2 dny) ⏳ DEFERRED (orchestrator has fallback paths)

| # | Úkol | Soubory |
|---|------|---------|
| 2.1 | Přidat `kind=affair` support do KB ingest | `knowledgebase/app/services/ingest_service.py` |
| 2.2 | Nový endpoint `/api/v1/ingest-immediate` (synchronní) | `knowledgebase/app/main.py` |
| 2.3 | Nový endpoint `/api/v1/affairs` (CRUD) | `knowledgebase/app/main.py` |
| 2.4 | Priority routing v embedding worker | `knowledgebase/app/services/llm_extraction_worker.py` |
| 2.5 | Ollama Router: MEMORY_WRITE_CRITICAL priorita | `ollama-router/app/models.py` |

### Fáze 3: Integrace do orchestrátoru (2-3 dny) ✅ DONE

| # | Úkol | Soubory |
|---|------|---------|
| 3.1 | Přidat `memory_load` node do grafu | `orchestrator/app/graph/nodes/memory_load.py` (NEW) |
| 3.2 | Upravit `respond` node — context detection + memory tools | `orchestrator/app/graph/nodes/respond.py` |
| 3.3 | Přidat `memory_flush` node do grafu | `orchestrator/app/graph/nodes/memory_flush.py` (NEW) |
| 3.4 | Přidat memory tools (memory_store, memory_recall, list_affairs) | `orchestrator/app/tools/definitions.py`, `executor.py` |
| 3.5 | Upravit orchestrator graph flow | `orchestrator/app/graph/orchestrator.py` |
| 3.6 | Upravit state model — přidat memory_agent, affairs | `orchestrator/app/models.py` |

### Fáze 4: Outcome ingestion rozšíření (1 den) ✅ DONE

| # | Úkol | Soubory |
|---|------|---------|
| 4.1 | ADVICE tasky: extrahovat a uložit jako affairs | `orchestrator/app/graph/nodes/finalize.py` |
| 4.2 | Rozšířit `is_significant_task` — ADVICE s affairs je significant | `orchestrator/app/graph/nodes/finalize.py` |
| 4.3 | Session persistence na konci orchestrace | `orchestrator/app/memory/agent.py` |

### Fáze 5: Testing a ladění (1-2 dny) ⏳ PENDING

| # | Úkol |
|---|------|
| 5.1 | End-to-end test: objednávka → přepnutí → ad-hoc dotaz → návrat |
| 5.2 | Latence test: context switch < 3s, ad-hoc recall < 2s |
| 5.3 | RAM usage test: 100 affairs, 1000 KB results cached |
| 5.4 | KB sync test: write-through reliability, crash recovery |
| 5.5 | Multi-session test: restart orchestrátoru, affairs se obnoví z KB |

---

## 8. Příklad průběhu konverzace

```
USER: "Vybral a objednal jsem toto: https://www.ebay.com/itm/167956961209"

  Memory Agent:
  → detect_context_switch → NEW_AFFAIR (žádná aktivní)
  → create_affair(title="Objednávka dílu z eBay", context=url)
  → compose_context → [active affair: "Objednávka dílu z eBay"]

  Respond:
  → LLM zpracuje s kontextem affair
  → memory_store(subject="eBay objednávka", content="položka 167956961209", category="order")
  → odpověď

USER: "Tady je objednávka, číslo 07-14244-53150, $38.10"

  Memory Agent:
  → detect_context_switch → CONTINUE (same affair)
  → compose_context → [active affair s aktualizovanými facts]

  Respond:
  → LLM aktualizuje affair key_facts
  → memory_store(subject="objednávka detaily", content="07-14244-53150, $38.10")
  → create_scheduled_task(check delivery in 5 days)

USER: "Super, teď moje záležitost. Máš email od univerzita Unicorn."

  Memory Agent:
  → detect_context_switch → NEW_AFFAIR
      reasoning: "teď moje záležitost" = explicitní přepnutí
  → park_affair("Objednávka dílu z eBay")
      → LLM: summary, key_facts, pending_actions
      → LQM store + KB CRITICAL write
  → create_affair(title="Přihlášení na Unicorn University")
  → KB search: "unicorn university email"
  → compose_context → [active: Unicorn, parked: [eBay objednávka]]

  Respond:
  → LLM odpovídá s kontextem Unicorn emailů
  → NEMÍCHÁ s eBay objednávkou

USER: "Hele, kdy to má dorazit, ten díl na auto?"

  Memory Agent:
  → detect_context_switch → AD_HOC
      reasoning: "hele, kdy" = jednorázový dotaz na jinou záležitost
  → handle_ad_hoc("kdy dorazit díl auto")
      → search parked affairs → "Objednávka dílu z eBay"
      → recall key_facts: datum objednávky, stav
  → compose_context → [ad_hoc context z eBay + active: Unicorn]

  Respond:
  → LLM odpovídá na díl (z parked affair context)
  → Zůstává v kontextu Unicorn (active affair se nemění)

USER: "Ok, a co s tou školou, co musím udělat dál?"

  Memory Agent:
  → detect_context_switch → CONTINUE (Unicorn je stále active)
  → compose_context → [active: Unicorn, parked: [eBay]]

  Respond:
  → Pokračuje v Unicorn kontextu
```

---

## 9. Rizika a mitigace

| Riziko | Dopad | Mitigace |
|--------|-------|----------|
| LLM špatně klasifikuje context switch | Míchání kontextů | Explicitní signály ("teď jiné", "mimochodem") + confidence threshold |
| LQM ztráta dat při pádu orchestrátoru | Ztráta nedávných writes | Write buffer má TTL 30s → KB flush; critical writes jsou sync |
| KB latence při CRITICAL write | Pomalý context switch | LQM okamžitě dostupné, KB write je best-effort s retry |
| RAM overflow při mnoha affairs | OOM | Max 100 affairs v hot cache, starší → warm → cold (LRU eviction) |
| Affair deduplication | Duplicitní záležitosti | Heuristický match na title+topics při create, LLM potvrdí |
| Context switch false positive | Zbytečný park | Confidence threshold (>0.7), pod ním → CONTINUE |
