# Bug: Chat plete projekty + špatné porozumění textu

**Priority**: HIGH
**Area**: Orchestrator → chat handler, context, memory

## Problém 1: Pletení projektů (nUFO vs BMS)

Chat si plete kontext projektů. V konverzaci:

1. Uživatel řeší TracingService.kt chybu — chat správně identifikuje nUFO projekt
2. Uživatel říká "udělej si checkout větve script-performance" — chat odpovídá o nUFO
3. Uživatel opraví: "tak pozor to není nUFO ale bms. tak znova ale v jiném projektu"
4. Chat přepne na BMS Commerzbank — ale mluví o "bms-core modulu" s tracing chybou,
   což je problém z nUFO. Míchá kontext obou projektů dohromady.

**Příčina**: Lokální paměť (summary + recent messages) nerozlišuje jasně mezi projekty.
Informace z jednoho projektu "protékají" do druhého.

**Fix**: Summary bloky a kontext musí být tagované project/client ID. Při switch_context
chat musí jasně oddělit: "toto je kontext projektu X, toto je kontext projektu Y".

## Problém 2: switch_context se neprojevil v UI

Uživatel chtěl přepnout projekt, chat zavolal `switch_context`, ale v UI zůstalo
"MMB / nUFO". Buď:
- `switch_context` tool jen mění interní stav chatu, ne UI selekci
- Nebo kRPC event pro změnu kontextu se nepropagoval do UI

**Fix**: `switch_context` musí propagovat změnu do UI (kRPC event → ViewModel update).

## Problém 3: Pochopení textu s překlepy a gramatikou

Qwen3-30B špatně rozumí českému textu s překlepy a hovorovou řečí:
- "udělej si checkout větve script performance a nebo raději přímo na toto navazující"
  → chat pochopil, ale zařadil do špatného projektu
- "tak pozor to není nUFO ale bms. tak znova ale v jiném projektu"
  → chat pochopil přepnutí, ale přenesl kontext z nUFO do BMS

**Možnosti zlepšení**:
1. **Preprocessing** — před LLM volání normalizovat text: opravit překlepy, doplnit
   diakritiku, rozepsat zkratky. Může být malý model (7B) nebo rule-based.
2. **System prompt** — explicitně instruovat: "Uživatel píše česky s překlepy a
   hovorovou řečí. Interpretuj volně, ptej se pokud je nejednoznačné."
3. **Kontext separace** — při přepnutí projektu jasně oddělit: předchozí projekt =
   uzavřený kontext, nový projekt = čistý začátek s vlastní historií

## Screenshot

Konverzace kde chat:
1. Řeší TracingService.kt v nUFO (správně)
2. Uživatel říká checkout script-performance → chat odpovídá o nUFO (špatně, je to BMS)
3. Uživatel opraví → chat přepne na BMS ale mluví o tracing chybě z nUFO (pletení)

## Files

- `backend/service-orchestrator/app/chat/context.py` — context assembler, project separation
- `backend/service-orchestrator/app/chat/handler_agentic.py` — switch_context handling
- `backend/service-orchestrator/app/tools/` — switch_context tool implementation
- `shared/ui-common/.../chat/ChatViewModel.kt` — UI context switch propagation
