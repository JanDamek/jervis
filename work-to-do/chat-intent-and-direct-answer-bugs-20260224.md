# Bug: Chat intent klasifikátor + direct answer halucinace

**Severity**: CRITICAL
**Date**: 2026-02-24

## Popis

Uživatel poslal "jdeme na nufo" → chat nepřepnul projekt (UI zůstalo na Commerzbank/bms).
Následný požadavek o analýzu kódu → chat vygeneroval falešné výsledky nástrojů (hallucinated kb_search, brain_search_issues).

## Bug 1: Intent klasifikátor nepokryje přirozené fráze pro přepnutí kontextu

**Soubor**: `backend/service-orchestrator/app/chat/intent.py`

TASK_MGMT pattern vyžaduje explicitní `přepni se na` / `otevři projekt` / `switch`:
```
přepni\s+(?:se\s+)?na|otevři\s+(?:projekt|klient)|switch|přepnout
```

"jdeme na nufo" → žádný match → intent = `{CORE}` → `switch_context` tool není k dispozici.

**Chybějící fráze:**
- "jdeme na X", "pojďme na X", "přejdi na X"
- "otevři X" (bez "projekt"/"klient")
- Samotný název projektu/klienta jako příkaz: "nufo", "moneta"
- "na X", "do X" (v kontextu přepnutí)

**Oprava**: Rozšířit TASK_MGMT pattern o:
```
jdeme\s+na|pojďme\s+na|přejdi\s+na|otevři\s+\w+
```

## Bug 2: Direct answer halucinuje výsledky nástrojů

**Soubor**: `backend/service-orchestrator/app/chat/handler.py` (řádky 389-425)

Pokud intent = `{CORE}` a zpráva < 500 znaků → LLM se zavolá s `tools=None` (žádné nástroje).
LLM pak vygeneruje přesvědčivou odpověď, která VYPADÁ jako by použil nástroje
(kroky "Pomocí kb_search jsem vyhledal...", "Pomocí brain_search_issues..."),
ale ve skutečnosti nic nezavolal — vše je vymyšlené.

`_NEEDS_TOOLS_MARKERS = ["potřebuji", "nemám informac", "nevím", "musím", "nemohu"]`
— nezachytí sebevědomou halucinaci, protože LLM neříká "nevím", ale rovnou vymýšlí.

**Oprava** (výběr jedné z variant):

### Varianta A: Přidat anti-halucinační markery
Do `_NEEDS_TOOLS_MARKERS` přidat detekci falešného tool use:
```python
_FAKE_TOOL_MARKERS = ["pomocí kb_search", "pomocí brain_", "pomocí code_search",
                       "kb_search", "brain_search", "code_search",
                       "krok 1:", "krok 2:", "step 1:", "step 2:"]
```
Pokud odpověď obsahuje tyto → odmítnout direct answer → agentic loop.

### Varianta B: Omezit direct answer jen na greetings (doporučeno)
Direct answer je bezpečný jen pro pozdravy a triviální konverzaci.
Pro cokoliv jiného vždy použít agentic loop s nástroji.
```python
if intent_categories == {ToolCategory.CORE, ToolCategory.GREETING}:
    # direct answer OK jen pro greetings
```

### Varianta C: Vždy poskytnout alespoň CORE tools
Místo `tools=None` předat `tools=core_tools` i v direct answer pokusu.
Pokud LLM zavolá tool → přepnout na agentic loop. Pokud ne → direct answer.

## Bug 3: RESEARCH patterns nechytí české skloňování bez háčků

**Soubor**: `backend/service-orchestrator/app/chat/intent.py`

Pattern `kód[ue]?` vyžaduje háček (ó). Uživatel napsal "kodu" (bez háčku) → no match.

Zpráva "Budeme řešit stav kodu pro chybu v tracingu" obsahuje:
- "kodu" → nechytí `kód[ue]?` (chybí háček)
- "tracingu" → nechytí nic
- "analýzu" → nechytí nic
- "kontrolu kodu" → nechytí nic
- "develop větvi" → nechytí nic

**Oprava**: Pattern rozšířit o varianty bez diakritiky:
```
kód[ue]?|kod[ue]?|code|zdrojov|source|funkc[ei]|class|tříd[auy]|trid[auy]|metod[auy]|
analýz|analyz|bug|chyb[auy]?|error|tracing|debug
```

## Priorita oprav

1. **Bug 2** (CRITICAL) — halucinace je nejhorší, uživatel dostane falešné informace
2. **Bug 1** (HIGH) — přepnutí kontextu nefunguje pro přirozené fráze
3. **Bug 3** (MEDIUM) — diakritika v intent patterns

## Dotčené soubory

| Soubor | Změna |
|--------|-------|
| `backend/service-orchestrator/app/chat/handler.py` | Opravit direct answer logiku (Bug 2) |
| `backend/service-orchestrator/app/chat/intent.py` | Rozšířit patterns (Bug 1 + Bug 3) |
