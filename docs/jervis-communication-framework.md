# JERVIS Communication Framework

> Kompletní architektura komunikace mezi JERVIS ↔ uživatel ↔ coding agenti ↔ externí kanály.
> Tento dokument je referenční — vše co zde je, má být implementováno.

## 1. Přehled kanálů

| Kanál | Směr | Auto-response | Příklady |
|-------|------|--------------|----------|
| **Chat UI** | ↔ bidirectional | NE (vždy s uživatelem) | Desktop, iOS, Android |
| **Email (privátní)** | → příjem + ← odpověď | S potvrzením | damekjan74@gmail.com |
| **Email (JERVIS)** | → příjem + ← odpověď | ANO (plně automatický) | jervis@damek-soft.eu, jervis@mazlusek.com |
| **Teams** | → příjem + ← odpověď | S potvrzením / ANO per kanál | Commerzbank Teams |
| **Slack** | → příjem + ← odpověď | S potvrzením / ANO per kanál | — |
| **Guru/portály** | → příjem + ← odpověď | ANO (přijímání nabídek) | guru.com, toptal, upwork |
| **Push notifikace** | ← jen odchozí | — | APNs (iOS), FCM (Android), macOS |
| **Coding agent** | ↔ interní | KB auto-answer, jinak user | Claude Code na K8s |

## 2. Coding Agent ↔ JERVIS ↔ Uživatel

### 2.1 Agent potřebuje rozhodnutí

```
Coding agent pracuje → potřebuje rozhodnutí
  ├── kb_search("jak řešit X v projektu Y")
  │   ├── KB má convention/decision → auto-odpověď, agent pokračuje
  │   └── KB nemá → ask_jervis(question, priority)
  │       ├── Uživatel chatuje → fronta (počká až dořeší)
  │       ├── Uživatel idle → push notification + chat
  │       └── Kritické (blokuje pipeline) → URGENT push
  └── Odpověď od uživatele → kb_store(convention) → agent pokračuje
```

### 2.2 Agent dokončí práci

```
Coding agent dokončil task
  ├── KB search: "co po dokončení X v projektu Y?"
  │   ├── Convention nalezena ("po PR → run e2e") → auto-dispatch další task
  │   └── Nenalezena → ask user "Dokončeno X. Co dál?"
  ├── Výsledek → pushBackgroundResult do chatu
  └── Učení: uživatelova odpověď → kb_store(convention pro příště)
```

### 2.3 MCP tool `ask_jervis`

```
Tool: ask_jervis
Args: { question: str, priority: "blocking"|"question"|"info", context: str }
Returns: { answer: str, source: "kb"|"user"|"timeout" }
```

- `blocking` → agent čeká, uživatel dostane URGENT push
- `question` → agent čeká, uživatel dostane normální push
- `info` → agent pokračuje, informace jde do chatu

### 2.4 Fronta dotazů

```
MongoDB: pending_agent_questions
{
    _id, taskId, agentType, question, context,
    priority: "blocking"|"question"|"info",
    createdAt, answeredAt, answer,
    clientId, projectId,
    state: "pending"|"presented"|"answered"|"expired"
}
```

- Pokud uživatel řeší téma A, agent se ptá na B → B do fronty
- Po dokončení A → "Ještě je tu dotaz od agenta: B"
- "K reakci" badge v UI = počet pending questions

## 3. Email routing — jeden inbox, více projektů

### 3.1 Problém

`damekjan74@gmail.com` přijímá emaily pro:
- Domácnost (faktury, osobní)
- Guru/portály nabídek práce
- Technické notifikace (GitHub, GitLab, CI)
- Klientská komunikace (forwarded)

### 3.2 Řešení — routing rules

Kvalifikátor při zpracování emailu:

1. **Sender matching** → KB search sender domain/email
   - `@guru.com` → klient "Guru/Freelance", projekt podle obsahu
   - `@github.com` → detekce repozitáře → mapovat na klienta/projekt
   - `@commerzbank.com` → Commerzbank
   - Neznámý → Domácnost (default)

2. **Content matching** → KB search klíčová slova
   - "faktura", "invoice" → finanční, dohledat klienta podle firmy
   - "code review", "PR", "merge" → development, mapovat na projekt
   - "job offer", "opportunity" → Guru/Freelance

3. **Explicit rules** (conventions v KB):
   - `kb_store(kind=convention, "emails from @nateraci.cz → Domácnost, kategorie: údržba domu")`
   - `kb_store(kind=convention, "emails about BMS → Commerzbank/bms")`

### 3.3 Dedikované JERVIS email adresy

| Adresa | Účel | Auto-response |
|--------|------|---------------|
| `jervis@damek-soft.eu` | Firemní JERVIS — klientská komunikace | ANO |
| `jervis@mazlusek.com` | Osobní JERVIS — domácnost, rodina | ANO |
| `damekjan74@gmail.com` | Privátní — routing do správného projektu | S potvrzením |

Postupný přechod:
1. Nyní: vše přes damekjan74@gmail.com, JERVIS routuje
2. Budoucí: klienti dostanou jervis@damek-soft.eu, JERVIS odpovídá přímo
3. Cíl: damekjan74 jen pro osobní, vše ostatní přes JERVIS adresy

### 3.4 Založení per-projekt emailů

Časem — per projekt/klient vlastní alias:
- `bms@damek-soft.eu` → automaticky scope Commerzbank/bms
- `mmb@damek-soft.eu` → automaticky scope MMB
- Eliminuje routing logiku — email adresa = scope

## 4. Teams / Slack / portály

### 4.1 Příchozí zprávy

Stejný flow jako email:
1. **Indexace** → KB (text + metadata + thread kontext)
2. **Kvalifikace** → DONE / QUEUED / URGENT
3. **Cross-source matching** → najít souvislosti v KB

### 4.2 Auto-response kanály

Některé kanály mají povolený **plně automatický response**:

```
MongoDB: channel_auto_response_rules
{
    channelType: "teams"|"slack"|"email"|"portal",
    channelId: "...",  // specific channel/chat ID
    clientId: "...",
    autoResponse: true|false,
    responseRules: [
        { trigger: "job_offer", action: "accept_and_notify" },
        { trigger: "direct_question", action: "draft_and_wait" },
        { trigger: "newsletter", action: "ignore" },
    ],
    learningEnabled: true,  // učí se z uživatelových reakcí
}
```

### 4.3 Guru/portály práce — automatické přijímání

Flow:
1. Email přijde: "New job opportunity: Kotlin developer needed"
2. Kvalifikátor: `kb_search("guru job acceptance rules")`
3. Convention: "přijímat nabídky matching: Kotlin, Java, Spring, KMP"
4. Match → auto-response: "Interested, available from [date]"
5. Notify user: "Přijal jsem nabídku X na Guru. Detail: ..."
6. No match → QUEUED: "Nová nabídka na Guru: X. Zajímá tě to?"

**Učení:**
- Uživatel reaguje "ano, přijmi" → posílí pattern
- Uživatel reaguje "ne, tohle nechci" → kb_store(convention: "odmítat nabídky typu X")
- Postupně JERVIS ví co přijímat automaticky

### 4.4 Přímé dotazy na mě = URGENT

Pokud v Teams/Slack někdo píše **přímo mně** (DM nebo @mention):
- Vždy URGENT
- JERVIS připraví draft odpovědi
- Push notification: "Kolega X se ptá na Y. Navrhuji odpověď: Z"
- Uživatel: approve → odešle / edit → upraví a odešle / reject

### 4.5 Draft response flow

```
Příchozí zpráva (Teams DM od kolegy)
  ↓
Kvalifikátor: URGENT + přímý dotaz
  ↓
JERVIS: kb_search(kontext dotazu) → najde relevantní info
  ↓
JERVIS: vygeneruje draft odpovědi
  ↓
Push notification + chat:
  "Kolega Petr se ptá: 'Kdy bude hotový deploy BMS?'
   Navrhuji odpovědět: 'Deploy je naplánován na pátek 28.3.,
   aktuálně probíhá e2e testování.'
   [Odeslat] [Upravit] [Ignorovat]"
  ↓
Uživatel klikne [Odeslat] → JERVIS odešle přes Teams API
```

## 5. Notifikace — unified push system

### 5.1 Kanály notifikací

| Platforma | Mechanismus | Proklik |
|-----------|------------|---------|
| iOS | APNs push | Otevře JERVIS chat na správné zprávě |
| Android | FCM push | Otevře JERVIS chat |
| macOS Desktop | NSUserNotificationCenter | Otevře JERVIS okno |
| watchOS | WKNotification | Quick reply možnost |

### 5.2 Priorita notifikací

| Priorita | Zvuk | Vibrace | Badge | Příklad |
|----------|------|---------|-------|---------|
| **BLOCKING** | Ano | Ano | +1 | Agent blokován, kolega čeká na odpověď |
| **URGENT** | Ano | Ne | +1 | Faktura se splatností, přímý dotaz |
| **QUESTION** | Ne | Ne | +1 | Agent se ptá, nabídka na portálu |
| **INFO** | Ne | Ne | Ne | Task dokončen, email zpracován |

### 5.3 Desktop notifikace (macOS)

```kotlin
// Compose Desktop — NSUserNotificationCenter
expect fun showDesktopNotification(
    title: String,
    body: String,
    actionUrl: String?,  // deep link do chatu
    priority: NotificationPriority,
)
```

- Kliknutí → otevře JERVIS Desktop na správném chatu/scope
- Badge na dock ikoně = počet K reakci

## 6. Učení z reakcí — feedback loop

### 6.1 Princip

Každá reakce uživatele na JERVIS rozhodnutí = učení:

| Akce uživatele | KB zápis | Efekt |
|----------------|----------|-------|
| "Ignorovat" urgent | `one-time ignore` (NE convention) | Příště se zeptá znovu |
| "Toto téma nehlídej" | `convention: skip topic X` | Příště automaticky DONE |
| "Tohle vždy přijímej" | `convention: auto-accept type X` | Příště auto-response |
| "Commity v BMS podepisuj" | `convention: sign commits` + **akce** | Provede + zapamatuje |
| Approve draft odpovědi | reinforcement v Thought Map | Příště jistější draft |
| Reject draft odpovědi | negative signal | Příště se zeptá |

### 6.2 Ignorovat ≠ Naučit se ignorovat

**Kritický princip:**
- **Ignorovat** (tlačítko v UI) = jednorázové, příště se zeptá znovu
- **"Nehlídej toto téma"** (textová instrukce) = permanentní convention v KB
- **"Toto je vyřízeno"** (textová instrukce) = uzavře konkrétní thread, ne téma

### 6.3 Kde se učení ukládá

```
KB: kind=convention — permanentní pravidla
KB: kind=decision — jednorázová rozhodnutí
Thought Map: reinforcement — posílení/oslabení vzorů
Memory Graph: active affairs — aktuálně řešená témata
```

## 7. Email separace do projektů

### 7.1 Aktuální stav

- `damekjan74@gmail.com` → vše do "Domácnost" (default client)
- MMB email → správně do MMB client
- Commerzbank → zatím bez emailu

### 7.2 Cílový stav

```
Příchozí email na damekjan74@gmail.com
  ↓
Email indexer: parsuje sender, subject, content
  ↓
Routing engine:
  1. Sender domain match → KB conventions
     @guru.com → "Freelance" client
     @commerzbank.com → "Commerzbank" client
  2. Content keywords → KB entities
     "BMS", "processing" → Commerzbank/bms projekt
     "faktura", "úklid" → Domácnost
  3. Previous thread → inherit scope from parent email
  4. Explicit alias → damek-soft.eu/mazlusek.com = přímý scope
  5. Default → Domácnost + kvalifikátor rozhodne
  ↓
Email se uloží s clientId/projectId → KB indexace ve správném scope
```

### 7.3 Dedikované domény

```
damek-soft.eu:
  jervis@     → JERVIS automat (firemní)
  bms@        → Commerzbank/bms (auto-scope)
  jan@        → osobní firemní

mazlusek.com:
  jervis@     → JERVIS automat (osobní)
  jan@        → osobní

mazlusek.eu, mazlusek.cz:
  → aliasy na mazlusek.com
```

## 8. Implementační pořadí

### Fáze 1 — Základ (bez auto-response)
1. `pending_agent_questions` collection + API
2. `ask_jervis` MCP tool pro coding agenta
3. Question router v orchestrátoru (KB auto-answer + user escalation)
4. Post-completion chain (next_step_resolver)
5. Desktop notifikace (macOS NSUserNotificationCenter)
6. Fronta v UI ("K reakci" badge s proklikem)

### Fáze 2 — Draft responses
7. Kvalifikátor: draft response generation pro URGENT
8. UI: [Odeslat] [Upravit] [Ignorovat] pro draft responses
9. Teams/Slack: odeslání schváleného draftu přes API

### Fáze 3 — Auto-response
10. `channel_auto_response_rules` collection
11. Email auto-response pro JERVIS adresy
12. Guru/portál auto-accept flow
13. Teams/Slack auto-response per kanál

### Fáze 4 — Email routing
14. Sender/content routing engine v kvalifikátoru
15. Per-projekt email aliasy
16. Thread scope inheritance

### Fáze 5 — Učení
17. Feedback loop: reakce → KB convention
18. Ignore vs permanent rule rozlišení
19. Thought Map reinforcement z reakcí
20. Auto-response confidence scoring
