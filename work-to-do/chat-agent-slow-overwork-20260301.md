# Chat agent — pomalé odpovědi a zbytečná práce (overwork)

**Priorita**: HIGH
**Status**: PARTIAL (2026-03-01) — max_iterations limit existuje (6/10 dle délky zprávy), ale 5/6 root causes neřešeno (context scaling, tool filtering, brain dedup, Operation not allowed, transition 500)

---

## Problém

Chat agent na jednoduchou zprávu ("tady je popis bugů, vše je v KB") spustí 4+ iterací LLM, každá trvá 90-290 sekund. Celková doba odpovědi přesahuje 10 minut místo očekávaných 10-20 sekund.

### Naměřeno (2026-03-01, 10:04–10:15)

| Iterace | LLM doba | Token kontext | Tool call | Nutné? |
|---------|----------|---------------|-----------|--------|
| 1/6 | 92s | ~19k | `store_knowledge` | NE — user řekl "vše je v KB" |
| 2/6 | 137s | ~20k | `brain_create_issue` | MOŽNÁ — ale neměl se zeptat? |
| 3/6 | **292s** | ~21k | `brain_add_comment` | NE — právě vytvořil issue |
| 4/6 | ???s | ~21k+ | ??? | stále běží |

Celkem: **10+ minut** na zprávu kde správná odpověď je potvrdit a zeptat se.

---

## Root cause 1: LLM se zpomaluje s rostoucím kontextem

30b model na P40 (24GB VRAM) je quick při malém kontextu (~8s pro 8k tokenů), ale dramaticky se zpomaluje:
- ~19k tokenů → 92s
- ~20k tokenů → 137s
- ~21k tokenů → 292s (nad ~48k kontext spill do CPU RAM → 7-12 tok/s)

Přitom 21k tokenů by neměl být problém (pod 48k VRAM limitem). Ale celkový kontext pro LLM = system prompt (~3k) + messages (~21k) + tool schemas (30/33 tools × ~200 tokenů = ~6k) + tool results z předchozích iterací (~3k per iterace).

**Skutečný kontext v iteraci 3**: ~3k (system) + ~21k (messages) + ~6k (tools) + ~6k (tool results iter 1+2) = **~36k tokenů** → blíží se k 48k VRAM limitu.

### Řešení
1. **Omezit počet tools** — neposlat 30/33 tools, ale jen relevantní (intent-based selection je v kódu ale posílá příliš)
2. **Zkrátit tool schemas** — descriptions jsou moc dlouhé
3. **Neposílat tool results z předchozích iterací** — nebo je sumárizovat
4. **Kontextový budget** — po překročení ~25k tokenů sumárizovat starší zprávy před dalším LLM callem

### Soubory
- `backend/service-orchestrator/app/chat/handler_agentic.py` — iterační loop, tool results
- `backend/service-orchestrator/app/chat/handler.py` — intent → tool selection (řádek ~70: `intent=['brain', 'research', 'task_mgmt', 'core'] → 30/33 tools`)
- `backend/service-orchestrator/app/chat/context.py` — context assembly

---

## Root cause 2: Agent dělá zbytečné tool cally

Agent NERESPEKTUJE pravidlo "odpovídej přímo bez tools pokud znáš odpověď". I když user řekne "tady je popis, vše je v KB", agent:
1. Ukládá do KB (zbytečné — user řekl že to tam je)
2. Vytváří Jira issue (neřekl to ale ok — měl se zeptat)
3. Přidává komentář k čerstvě vytvořenému issue (zbytečné)
4. Pokračuje v dalších iteracích

Každý zbytečný tool call = +90-290 sekund čekání.

### Řešení

#### 2.1 Posílit "ODPOVĚZ PŘÍMO" pravidlo
System prompt (`system_prompt.py`) má pravidlo "maximálně 2-3 tool calls na odpověď", ale agent ho nedodržuje (4+ tool calls v jedné odpovědi). Problém je že pravidlo není dost silné pro 30b model.

Přidat do system_prompt.py:
```
## ⚠️ STOP pravidlo — max 2 iterace pro jednoduché zprávy
Pokud uživatel:
- Sděluje informaci / popis → POTVRĎ a ZEPTEJ SE jestli má vytvořit task. NEVOLEJ tools.
- Sdílí analýzu / postup → PŘIJMI a SHRŇ. NEVOLEJ store_knowledge (user to už udělal).
- Řekne "vše je v KB" / "vytvořil jsem soubor" → NEUKLÁDEJ ZNOVU. Jen potvrď.

**Po 2 tool calls**: ZASTAVIT a odpovědět uživateli. Nepokračovat dalšími iteracemi.
```

#### 2.2 Iteration limiter v handler_agentic.py
Přidat tvrdý limit na iterace pro jednoduché zprávy:
```python
# Pokud zpráva je krátká (< 200 znaků) a neobsahuje otázku → max 2 iterace
if len(user_message) < 200 and '?' not in user_message:
    max_iterations = 2
```

Nebo měkčí: po 2 iteracích s tool calls přidat do kontextu system message:
```python
if iteration >= 2 and total_tool_calls >= 3:
    messages.append({
        "role": "system",
        "content": "STOP: Již jsi provedl 3+ tool calls. Odpověz uživateli a skonči."
    })
```

### Soubory
- `backend/service-orchestrator/app/chat/system_prompt.py` — posílit pravidla
- `backend/service-orchestrator/app/chat/handler_agentic.py` — iteration/tool call limiter

---

## Root cause 3: Intent decomposition selhává

Log ukazuje:
```
WARNING: Intent decomposition failed: (treating as single intent)
```

Když intent decomposition selže, handler posílá **30/33 tools** (všechny kromě 3). To je zbytečné — pro jednoduchou informativní zprávu stačí 5-10 tools (memory, kb_search, kb_delete, store_knowledge, respond_to_user_task).

### Řešení
Při selhání intent decomposition → fallback na **minimální tool set** (core tools), ne na "všechno":
```python
# handler.py
if intent_decomposition_failed:
    tools = CORE_TOOLS  # ~10 tools místo 30
```

### Soubory
- `backend/service-orchestrator/app/chat/handler.py` — intent fallback (řádek ~70)

---

## Root cause 4: brain_add_comment po brain_create_issue je zbytečný

Agent vytvořil Jira issue a hned na něj přidává komentář se STEJNÝM obsahem. To je pattern který 30b model dělá často — "udělej issue a pak přidej detail jako komentář". Je to vždy zbytečné.

### Řešení
V tool execution logice detekovat pattern `brain_create_issue` → `brain_add_comment` na stejný issue a přeskočit:
```python
if tool_name == "brain_add_comment":
    last_tool = previous_tool_calls[-1] if previous_tool_calls else None
    if last_tool and last_tool["name"] == "brain_create_issue":
        # Skip — just created the issue, comment is redundant
        return {"result": "Skipped — issue was just created with full description"}
```

Nebo jednodušeji: do `brain_create_issue` result message přidat "Nepřidávej komentář, issue již obsahuje plný popis."

### Soubory
- `backend/service-orchestrator/app/chat/handler_agentic.py` — tool call dedup
- `backend/service-orchestrator/app/tools/handlers.py` — brain_create_issue result message

---

## Shrnutí dopadů

| Problém | Dopad | Řešení |
|---------|-------|--------|
| Kontext roste s iteracemi | 92s → 292s per call | Omezit tools, sumárizovat results |
| Zbytečné tool calls | 3/4 calls nepotřeba | Posílit system prompt, iteration limiter |
| Intent decomposition fail → 30 tools | Pomalé, zbytečné | Fallback na core tools |
| brain_add_comment duplikát | +5 min čekání | Dedup pattern / result hint |

**Kumulativní efekt**: jednoduchá zpráva trvá 10+ minut místo 10 sekund.

---

## Root cause 5: "Operation not allowed" — finální odpověď nahrazena raw errorem

### Problém
Po 15 minutách zpracování (5 iterací) agent vyprodukoval 94-znakovou odpověď v blocking mode. Pak spustil **streaming call** aby odpověď doručil uživateli. Tento streaming call překročil Ollama `num_ctx` limit → Ollama vrátila `{"message":"Operation not allowed"}` jako **generovaný text** (ne jako HTTP error). Orchestrátor to streamoval do UI jako "odpověď".

Uživatel po 15 minutách čekání viděl:
```
{"error": {"type": "llm_call_failed", "message": "{"message":"Operation not allowed"}\n"}}
```

### Timeline
```
09:19:38 — blocking LLM → finish_reason=stop, 94 chars (SPRÁVNÁ odpověď)
09:19:38 — streaming LLM call → p40-1 (nas.damek.local)
09:19:43 — topic_tracker LLM failed: TimeoutError (GPU přetížená)
09:19:43 — PYTHON_CHAT_END | type=done
→ UI dostala "Operation not allowed" místo 94-znakové odpovědi
```

### Řešení

#### 5.1 Nepoužívat streaming call pro doručení blocking odpovědi
Pokud blocking call vrátil `finish_reason=stop` s content, **streamovat přímo ten text** — ne volat LLM znovu:
```python
# handler_agentic.py — po blocking final answer
if final_answer_content:
    yield {"type": "text", "content": final_answer_content}  # Přímo streamovat
    # NE: await streaming_llm_call(same_context)
```

#### 5.2 Detekovat "Operation not allowed" ve streaming
Pokud streaming vrátí "Operation not allowed", zachytit a:
- Pokud existuje blocking odpověď → použít ji místo erroru
- Pokud ne → vrátit uživatelsky přívětivou chybovou hlášku

#### 5.3 Snížit kontext pro streaming call
Pokud se streaming call musí dělat, použít menší kontext (jen final answer text, bez tool results).

### Soubory
- `backend/service-orchestrator/app/chat/handler_agentic.py` — final answer delivery
- `backend/service-orchestrator/app/chat/handler_streaming.py` — streaming error handling
- `backend/service-orchestrator/app/llm/provider.py` — `_call_with_retry` (řádek 367)

---

## Root cause 6: brain_transition_issue 500 error

Agent po vytvoření JI-22 zkusil `brain_transition_issue` → "To Do", ale server vrátil 500.

```
WARNING: brain_transition_issue failed: Server error '500 Internal Server Error'
  for url 'http://jervis-server:5500/internal/brain/jira/issue/JI-22/transition'
```

Pravděpodobně Jira transition name neodpovídá dostupným přechodům, nebo endpoint má bug.

### Soubory
- `backend/server/.../rpc/internal/InternalBrainRouting.kt` — transition endpoint
- `backend/server/.../service/brain/BrainService.kt` — Jira transition logic

---

## Shrnutí dopadů (aktualizováno)

| Problém | Dopad | Řešení |
|---------|-------|--------|
| Kontext roste s iteracemi | 92s → 292s per call | Omezit tools, sumárizovat results |
| Zbytečné tool calls | 3/4 calls nepotřeba | Posílit system prompt, iteration limiter |
| Intent decomposition fail → 30 tools | Pomalé, zbytečné | Fallback na core tools |
| brain_add_comment duplikát | +5 min čekání | Dedup pattern / result hint |
| **Operation not allowed** | **Odpověď ztracena, raw error v UI** | **Streamovat blocking result přímo** |
| brain_transition_issue 500 | Zbytečná iterace + error | Fix transition endpoint |

## Ověření

1. Odeslat jednoduchou zprávu "tady je popis bugů" → odpověď do 30s, max 1-2 tool calls
2. Odeslat "vytvoř issue v Jiře" → 1 tool call (brain_create_issue), žádný brain_add_comment
3. Kontext po 3 iteracích < 30k tokenů (tool results sumárizované)
4. Intent decomposition failure → max 10 tools, ne 30
5. Po 5 iteracích → uživatel MUSÍ vidět odpověď (ne raw JSON error)
6. brain_transition_issue → nesmí vrátit 500
