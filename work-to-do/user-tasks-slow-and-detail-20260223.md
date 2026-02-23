# Bug: User Tasks — extrémně pomalé načítání + prázdný detail

**Severity**: CRITICAL (UI nepoužitelné)
**Date**: 2026-02-23

## 1. Pomalé načítání seznamu

### Příčina: Regex search bez indexu
`UserTaskService.kt:142-147`:
```kotlin
val regex = ".*${Regex.escape(query)}.*"
criteria.orOperator(
    Criteria.where("taskName").regex(regex, "i"),
    Criteria.where("content").regex(regex, "i"),  // ← velké textové pole, FULL SCAN!
)
```
- `content` pole obsahuje tisíce znaků per task
- Žádný MongoDB text index → **full collection scan**
- Regex `.*pattern.*` je nejpomalejší možný vzor

### Příčina: Dva DB dotazy místo jednoho
`UserTaskService.kt:150-160` — count + data query, oba prochází regex:
```kotlin
val totalCount = mongoTemplate.count(countQuery, TaskDocument::class.java)  // 1. scan
val items = mongoTemplate.find(dataQuery, TaskDocument::class.java)          // 2. scan
```

### Příčina: Celý TaskDocument se loaduje
`TaskMapper.kt:7-20` — mapper přenáší vše včetně:
- `content` — plný obsah (5000+ znaků)
- `attachments` — metadata s vision analysis
- `agentCheckpointJson` — serializovaný stav agenta
- `qualificationSteps`, `orchestratorSteps` — detailní kroky

Pro seznam stačí: id, title, state, clientId, projectId, createdAt, pendingQuestion.

### Příčina: Chat historie bez paginace
`ChatMessageRepository.kt:35`:
```kotlin
findByConversationIdOrderBySequenceAsc(conversationId: ObjectId): Flow<ChatMessageDocument>
```
Loaduje VŠECHNY zprávy bez limitu.

### Doporučená oprava
1. **MongoDB text index** na `taskName` + `content` → nahradit regex za `$text` search
2. **Compound index** `(type, createdAt)` pro rychlý filter+sort
3. **Projekce** — loadovat jen potřebná pole pro seznam (ne celý dokument)
4. **Jeden dotaz** — `estimatedDocumentCount()` nebo `facet` místo dvou scanu
5. **Paginace** chat historie — max 20 zpráv na stránku

## 2. Prázdný detail user tasku

### Symptom
Po otevření user tasku v detailu chybí:
- **Původní zpráva** — proč to přišlo na user task, co to vyvolalo
- **Přímá otázka** od orchestrátoru — `pendingUserQuestion` se nezobrazuje prominentně
- **Kontext otázky** — `userQuestionContext` není viditelný
- **Historie komunikace** — chat mezi uživatelem a orchestrátorem

### Analýza
`UserTasksScreen.kt:272-281` — detail view loaduje `getChatHistory(taskId)`,
ale UI nezobrazuje strukturovaně:
- Kontext: odkud task přišel (email, issue, confluence...)
- Otázku orchestrátoru a na co přesně čeká odpověď
- Historii iterací (uživatel → orchestrátor → uživatel → ...)

### Doporučená oprava
Detail user tasku by měl zobrazit:
1. **Hlavička**: typ zdroje (email/issue/...), klient, projekt, datum
2. **Původní obsah**: co spustilo task (email subject+body, issue title, atd.)
3. **Otázka orchestrátoru**: prominentně zvýrazněná pending question
4. **Chat historie**: chronologická konverzace user ↔ orchestrátor
5. **Odpověď**: input pole pro uživatele

## 3. Anglické user tasky

### Stav
Kotlin server labels (`UserTaskService.kt:38-59`) již opraveny na češtinu:
- "Dotaz agenta:" (bylo "Agent Question:")
- "Původní úloha:" (bylo "Original Task:")
- "Kontext:" (bylo "Context:")

**Ale**: stávající user tasky v MongoDB mají starý anglický formát.
Nové tasky od orchestrátoru budou česky — orchestrátor prompt (`respond.py:156-164`)
je v češtině a `ask_user` tool generuje otázky v jazyce promptu.

### K ověření
- Ověřit, že orchestrátor LLM skutečně generuje `ask_user` otázky česky
- Starší anglické tasky v DB se nezmění — to je OK, postupně odpadnou
- Pokud orchestrátor stále generuje anglicky → posílit system prompt:
  "VŽDY komunikuj s uživatelem ČESKY, včetně ask_user otázek"

## 4. Kalendář — DONE tasky se zobrazují, staré emaily naplánované na dnešek

### Symptom
Kalendář zobrazuje 3 DONE tasky — všechno staré emaily (2017, 2018) s anglickými popisky,
naplánované na 23.2.2026 13:30. Stav "Dokončeno" → nemají v kalendáři co dělat.

### Problémy
1. **DONE tasky se zobrazují v kalendáři** — kalendář by měl ukazovat jen aktivní
   (SCHEDULED, READY, QUALIFYING...), ne dokončené. DONE filtrovat pryč.
2. **Staré emaily naplánované na dnešek** — kvalifikátor naplánoval emaily z 2017/2018
   na aktuální datum. Kvalifikátor by měl rozpoznat že email je starý a NEnaplánovat ho.
3. **Prošlé SCHEDULED_TASK** — pokud termín < now a task NENÍ done,
   ihned eskalovat jako urgentní USER_TASK na začátek fronty.
4. **Anglické popisky** — "An email inviting...", "Confirmation of CACI telephone..." —
   orchestrátor generoval anglicky, viz §3 výše.
5. **Chybí kalendářní zobrazení** — aktuálně jen seznam. Přidat přepínač
   seznam / denní / týdenní kalendář. Týdenní kalendář jako grid (Po-Ne),
   denní jako timeline s hodinami. Přepínač v JTopBar vedle refreshe.

### Dotčené soubory

| Soubor | Řádek | Problém |
|--------|-------|---------|
| `backend/.../service/task/UserTaskService.kt` | 130-167 | Regex scan, dva dotazy, celý dokument |
| `backend/.../mapper/TaskMapper.kt` | 7-20 | Mapuje vše včetně velkých polí |
| `backend/.../rpc/UserTaskRpcImpl.kt` | 42-49, 62-67 | Žádná projekce, celá historie |
| `backend/.../repository/ChatMessageRepository.kt` | 35 | Bez paginace |
| `backend/.../entity/TaskDocument.kt` | 96-157 | Chybí compound index (type, createdAt), text index |
| `shared/.../ui/UserTasksScreen.kt` | 76-94, 272-281 | Sekvenční RPC, detail bez struktury |
