# KB Indexation Pipeline — Systémová Analýza

**Datum:** 2026-03-23
**Status:** Probíhající analýza

---

## 1. Architektonické Principy (co má být)

KB je nejkritičtější komponenta JERVIS. Agent nemůže fungovat bez kvalitních strukturovaných dat a vztahů.

### Dual Storage Model
- **RAG** (Weaviate): embedding + sémantické vyhledávání
- **Graph** (ArangoDB): entity + vztahy + navigace
- **Bidirectional linking**: chunks ↔ graph nodes ↔ edges

### Ingest Flow (docs/knowledge-base.md)
```
Content → Chunking → RAG Embedding → Graph Extraction (LLM) → Summary Generation → Callback
```

### Proaktivní Kontext (cílový stav)
- Orchestrátor PŘED LLM callem automaticky stáhne relevantní kontext z KB
- Thought Map (spreading activation) + RAG prefetch (cosine similarity)
- Model VŽDY vidí relevantní data → nemusí volat tool

---

## 2. Nalezené Systémové Problémy

### 2.1 Graph Extraction — `nodes=0` pro většinu obsahu

**Příčina:** `format_json=True` na Ollama API + qwen3:14b → model vrací `{}`
**Oprava:** `format_json=False` + `/no_think` tag → model vrací JSON v plain textu
**Stav:** ✅ Opraveno, ověřeno (test email: 10 nodes, 9 edges)
**Dopad:** Stovky emailů indexovaných bez grafových dat — potřeba re-extraction

### 2.2 Kvalifikátor — Systémový Bias k DONE

**Příčiny (6):**
1. Prompt říká "prefer DONE over false alert" → ignoruje urgent
2. `hasActionableContent` nezná faktury, platby, smlouvy
3. Urgency extraction nezná "splatnost" (jen sprint/release deadlines)
4. `MAX_ITERATIONS=5` omezuje cross-source matching
5. 8b model příliš malý pro česky + multilingual inference
6. Anglický prompt, český obsah

**Stav:** ⚠️ Analyzováno, čeká na architektonický návrh opravy
**Princip opravy:** Urgence musí být strukturální (extrahovaná při ingestu), ne LLM inference při kvalifikaci

### 2.3 Orchestrátor — Nepoužívá KB

**Problém:** Model se rozhodne ptát uživatele místo hledat v KB
**Příčina:** `kb_search` je reaktivní tool → model ho ignoruje (free modely)
**Návrh:** Proaktivní dual kontext — Thought Map + RAG prefetch PŘED LLM callem
**Stav:** ⚠️ Návrh, čeká na implementaci

### 2.4 Email Threading — Neúplné propojení

**Co funguje:** emailThreadId, emailMessageId, REPLY_TO edges
**Co chybí:**
- References header → REPLY_TO edges pro celý thread
- Fallback: messageId jako thread anchor pokud threadId chybí
- Stará data: metadata neprocházely (SQLite fronta přežila restart)
**Stav:** ⚠️ Částečně opraveno, potřeba re-extraction starých emailů

### 2.5 Meeting Indexace — Skipování a metadata

**Co chybí:**
- Meetings bez clientId se SKIPUJÍ (neindexují)
- Speaker names: NULL → žádná identifikace mluvčího
- Meeting metadata (meetingId, title, type) nejsou v FullIngestRequest.metadata
**Stav:** ⏳ Agent opravuje

### 2.6 Chat Přílohy — Garbage text

**Co bylo:** PDF dekódovaný jako UTF-8 → garbage → model error
**Oprava:** Document Extraction Service (nová microservice)
**Co chybí:** Metadata (taskId, messageId) v KB indexaci příloh
**Stav:** ✅ Service vytvořena a nasazena, metadata opravena

### 2.7 Docs vs Realita — Nekonzistence

**Dokumentace říká:** Per-client collections (`c{clientId}_nodes`)
**Kód dělá:** Global collections (`KnowledgeNodes`) s `clientId` polem
**Stav:** ⚠️ Dokumentace potřebuje update

### 2.8 Kvalifikátor Summary Extraction

**`_generate_summary()` extrahuje:**
- `hasActionableContent`, `suggestedActions`
- `isAssignedToMe`
- `hasFutureDeadline`, `suggestedDeadline`
- `urgency`, `summary`, `entities`

**Problém:** Extrakce závisí na LLM inference malým modelem. Prompt nezná české platební termíny.
**Princip opravy:** Strukturální extrakce (regex/heuristiky) pro částky, data, deadlines. LLM jen pro sémantiku.

---

## 3. Architektonické Návrhy

### 3.1 Strukturální Extrakce při Ingestu

PŘED LLM extrakcí provést deterministické parsování:
- Částky (regex: Kč, EUR, $, CZK)
- Data (regex: dd.mm.yyyy, splatnost, deadline, termín)
- Email adresy, telefony, IČO, DIČ
- Variabilní symboly, čísla faktur, objednávek

Výsledky uložit jako strukturovaná metadata na RAG chunks → kvalifikátor je dostane bez LLM inference.

### 3.2 Proaktivní Dual Kontext

PŘED každým LLM callem automaticky:
1. Thought Map prefetch (spreading activation) → insights, decisions
2. RAG prefetch (cosine similarity) → relevantní dokumenty
3. Oba výsledky → system prompt → model VŽDY vidí data

### 3.3 Kvalifikátor bez Bias

- Odstranit "prefer DONE over false alert"
- Strukturální urgence: deadline < 7 dní = URGENT automaticky (ne LLM inference)
- Cross-source matching: normalizované entity keys (alias registry)
- Vyšší model pro kvalifikaci (ne 8b)

### 3.4 Jednotná Extraction Pipeline

Všechny zdroje (email, meeting, chat, Slack, Teams, git):
1. → Document Extraction Service (ne-text → text)
2. → Strukturální parsování (částky, data, kontakty)
3. → RAG embedding (Weaviate)
4. → LLM graph extraction (entity, relationship, thoughts)
5. → Summary generation (urgency, actionable, deadline)
6. → Callback → kvalifikátor

---

## 3.5 Kaskádové Selhání — Proč JERVIS Nenašel Fakturu

Každý krok v pipeline má malý bug. Kombinace = totální selhání:

```
1. Email přijde → indexuje se do KB                    ✅ funguje
2. Summary: hasActionableContent=false                 ❌ faktura ≠ "žádost o akci"
   (prompt nezná finanční dokumenty)
3. Summary: hasFutureDeadline=false                    ❌ "splatnost" ≠ "deadline/milník"
   (prompt nezná platební termíny)
4. Summary: urgency=normal                             ❌ "<24h" pravidlo nedetekuje 3-7 denní deadline
5. Kvalifikátor: DONE                                  ❌ bias "prefer DONE over false alert"
   (hasActionableContent=false, urgency=normal → DONE)
6. Uživatel se ptá v chatu
7. Thought Map prefetch: timeout (GPU busy s KB)       ❌ prázdný kontext
8. Model nemá KB kontext → 36 tools v promptu          ❌ free model ztratí instrukce
9. Model: "nemám info, zeptám se" místo kb_search      ❌ reaktivní místo proaktivní
10. Uživatel frustrován → musí najít sám               ❌
```

**Klíč:** Žádný jednotlivý bug není fatální. Kaskáda 5+ malých bugů je fatální.

---

## 4. Prioritní Akce

| # | Akce | Řeší kroky | Dopad | Složitost |
|---|------|-----------|-------|-----------|
| 1 | **Summary prompt: obecný, ne specifický** | #2, #3, #4 | Faktura/smlouva/platba = actionable + deadline | S |
| 2 | **RAG prefetch do orchestrátoru** | #7, #8, #9 | Model vždy vidí KB data bez tool call | S |
| 3 | **Kvalifikátor: odebrat DONE bias** | #5 | Urgence správně detekována | S |
| 4 | **Strukturální extrakce (částky, data)** | #2, #3 | Deterministická detekce bez LLM inference | M |
| 5 | **format_json=False v summary** | #2 | Model nevrátí {} | S |
| 6 | **Re-extraction starých emailů** | — | Graph data pro 300+ emailů | S |
| 7 | **Meeting indexace bez clientId** | — | Neztratí data | S (done) |
| 8 | **Docs aktualizace** | — | Konzistence | S |
| 9 | **JOERN integrace audit** | — | Kódová analýza v grafu | L |
