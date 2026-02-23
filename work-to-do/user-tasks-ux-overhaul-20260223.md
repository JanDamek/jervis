# User Tasks: UX přepracování — offline chat s orchestrátorem

> **Datum:** 2026-02-23
> **Typ:** Feature request + UX bugy
> **Screenshots:** Detail tasku (jen popis + odpověď), seznam tasků (chybí priority, časy, kontext)
> **Koncept:** User Tasks = **offline chat s orchestrátorem** — task může pendlovat mnoho iterací (user↔agent), než se dořeší

---

## 1. BUG: Detail tasku je nepřehledný — chybí historie konverzace (P0)

### Aktuální stav

**Soubor:** `shared/ui-common/.../UserTasksScreen.kt:233-343`

Detail tasku zobrazuje:
- Nadpis (`pendingQuestion` nebo název tasku)
- Popis (jeden textový blok)
- TextField pro odpověď
- Tlačítka "Převzít do chatu" (DISABLED!) a "Odpovědět"

### Co chybí

**Task může pendlovat mnoho iterací:** Agent se zeptá → uživatel odpoví → orchestrátor zpracuje → zeptá se znovu → ... → implementace / code review / done. Ale UI ukazuje jen **poslední otázku** a **prázdný response field**.

Chybí:
1. **Historie konverzace (chat thread)** — kompletní sled otázka↔odpověď jako v chatu
   - Backend ukládá zprávy do `ChatMessageDocument` (přes `saveToChatHistory` v `UserTaskRpcImpl.kt:117-126`), ale UI je NEZOBRAZUJE
   - Uživatel nevidí: co psal minule, co agent odpověděl, kolik iterací proběhlo
2. **Kontext původního požadavku** — co přišlo (email, issue, commit), odkud to je
3. **Stav zpracování** — zda agent už pracoval, kolikrát se ptal, co udělal
4. **Attachmenty** — DTO má pole `attachments`, ale UI je nezobrazuje

### Řešení (směr)

Detail tasku přepracovat na **chat-like view**:
```
┌─ Kontext (odkud: email/issue/commit, klient, projekt) ──────┐
│ 📧 Email: Buenas tardes (Commerzbank / bms)                  │
│ Zařazeno: 23.2. 09:15                                        │
├──────────────────────────────────────────────────────────────┤
│ 🤖 Agent (09:15):                                            │
│ "Odpovědět na: The sender informs Juanito Checo..."          │
│                                                               │
│ 👤 Vy (09:20):                                               │
│ "Odpověz mu že se máme dobře a ať se ozve..."                │
│                                                               │
│ 🤖 Agent (09:21):                                            │
│ "Připravil jsem odpověď. Chcete schválit odeslání?"          │
│ [Zobrazit návrh emailu]                                      │
│                                                               │
│ 👤 Vy: ___________________________________  [Odpovědět]      │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. BUG: "Převzít do chatu" je disabled (P1)

**Soubor:** `shared/ui-common/.../UserTasksScreen.kt:331`

```kotlin
enabled = false  // ← hardcoded disabled
```

Tlačítko "Převzít do chatu" (`TaskRoutingMode.DIRECT_TO_AGENT`) je vypnuté. Uživatel nemůže převzít offline task do foreground chatu pro interaktivní řešení. Backend kód pro tuto cestu existuje (`UserTaskRpcImpl.kt:76-132`), ale UI ji blokuje.

### Řešení

Povolit tlačítko. Ověřit backend flow: task → FOREGROUND → chat window.

---

## 3. FEATURE: Seznam tasků — chybí priority, časy, kontext (P1)

### Aktuální stav

**Soubor:** `shared/ui-common/.../UserTasksScreen.kt:200-230` (`UserTaskRow`)

Každý řádek v seznamu ukazuje:
- Název tasku (často generický: "Schválení: epic_plan – ...")
- Stav badge (USER_TASK / NEW)
- Ikona koše + šipka

### Co chybí

1. **Priorita** — orchestrátor by měl přiřazovat priority (LOW/MEDIUM/HIGH/CRITICAL), ale:
   - `TaskPriorityEnum` existuje v kódu ale **NENÍ použit** nikde
   - Není v `TaskDocument`, není v `UserTaskDto`, není v UI
   - Seznam nelze řadit podle důležitosti

2. **Čas zařazení** — `createdAtEpochMillis` je v DTO ale nezobrazuje se v seznamu

3. **Poslední odpověď uživatele** — kolikrát jsem už odpovídal? Kdy naposledy?

4. **Poslední zpráva orchestrátoru** — co agent naposledy řekl/udělal?

5. **Typ interakce** — je to:
   - Clarification (agent se ptá)?
   - Approval (schválení commitu/pushe)?
   - Escalation (agent selhal)?
   - DTO má `isApproval` v eventu, ale UI to nerozlišuje

6. **Klient/Projekt** — ze screenshotu: seznam je globální, ale nevidím ke kterému klientu/projektu task patří (kromě názvu)

### Řešení (směr)

Bohatší list item:
```
┌──────────────────────────────────────────────────────────┐
│ 🔴 HIGH  Odpovědět: # Email: Buenas tardes              │
│ Commerzbank / bms · Zařazeno: 23.2. 09:15               │
│ Agent: "Odpovědět na email o zdravotním stavu..."        │
│ Vy: (čeká na odpověď)                     [2 iterace] → │
├──────────────────────────────────────────────────────────┤
│ 🟡 MED   Schválení: epic_plan — Review and Update...    │
│ MMB / nUFO · Zařazeno: 22.2. 18:30                      │
│ Agent: "Schválit push do main?"                          │
│ Vy: (čeká na odpověď)                     [1 iterace] → │
└──────────────────────────────────────────────────────────┘
```

---

## 4. FEATURE: Priorita v systému user tasků (P1)

### Aktuální stav

- `TaskPriorityEnum` existuje: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`
- **Nikde se nepoužívá** — není v `TaskDocument`, není v DTO, není v UI
- Orchestrátor nemá mechanismus jak přiřadit priority k user tasku

### Řešení (směr)

1. Přidat `priority: TaskPriorityEnum` do `TaskDocument` + `UserTaskDto`
2. Orchestrátor přiřadí priority při vytvoření user tasku (approval = HIGH, clarification = MEDIUM, escalation = CRITICAL)
3. UI zobrazí barevný indikátor + umožní řazení podle priority
4. Default sort: priority DESC, createdAt ASC

---

## 5. FEATURE: Hledání/filtr by měl být inteligentnější (P2)

### Aktuální stav

**Soubor:** `shared/ui-common/.../UserTasksScreen.kt:133-140`

Filtr je textový regex search přes `taskName` a `content`. Funguje, ale:
- Není filtr podle stavu (čeká na odpověď / odpovězeno / v zpracování)
- Není filtr podle priority
- Není filtr podle klienta/projektu
- Není filtr podle typu (clarification / approval / escalation)

### Řešení (směr)

Přidat chip filtry: stav, priorita, klient. Textový filtr ponechat pro full-text search.

---

## 6. Nesoulad s docs

| Aspekt | Docs / Očekávání | Realita |
|--------|------------------|---------|
| Konverzační historie | `ChatMessageDocument` se ukládá (`UserTaskRpcImpl:117-126`) | UI nezobrazuje historii, jen poslední otázku |
| Převzít do chatu | Backend flow existuje (`DIRECT_TO_AGENT`) | Tlačítko disabled (hardcoded `false`) |
| Priorita | `TaskPriorityEnum` definován | Nikde nepoužit — ne v DB, ne v DTO, ne v UI |
| Approval metadata | Event má `isApproval`, `interruptAction` | UI nerozlišuje typy tasků |
| Attachmenty | DTO má `attachments: List<AttachmentDto>` | Detail je nezobrazuje |

---

## 7. Relevantní soubory

| Soubor | Řádky | Co |
|--------|-------|----|
| `shared/ui-common/.../UserTasksScreen.kt` | 45-199 | Seznam tasků — list + filtr |
| `shared/ui-common/.../UserTasksScreen.kt` | 200-230 | `UserTaskRow` — řádek v seznamu |
| `shared/ui-common/.../UserTasksScreen.kt` | 233-343 | `UserTaskDetail` — detail + odpověď |
| `shared/ui-common/.../UserTasksScreen.kt` | 331 | `enabled = false` — disabled "Převzít do chatu" |
| `shared/common-dto/.../UserTaskDto.kt` | — | DTO (chybí priority, chat history) |
| `shared/common-api/.../IUserTaskService.kt` | — | RPC interface (5 metod) |
| `backend/server/.../UserTaskRpcImpl.kt` | 76-167 | `sendToAgent()` — DIRECT_TO_AGENT + BACK_TO_PENDING |
| `backend/server/.../UserTaskRpcImpl.kt` | 117-126 | `saveToChatHistory` — ukládá ale UI nezobrazuje |
| `backend/server/.../UserTaskService.kt` | 29-121 | `failAndEscalateToUserTask()` — vytvoření user tasku |
| `backend/server/.../TaskMapper.kt` | 7-20 | Mapping TaskDocument → UserTaskDto |
| `backend/server/.../TaskDocument.kt` | 131-142 | `pendingUserQuestion`, `userQuestionContext` |
| `docs/orchestrator-detailed.md` | §13 | Approval flow, iterace user↔agent |
