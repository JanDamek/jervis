# Browser Pod Redesign — Autonomní AI stavový automat

**Status:** Návrh schválený, připraveno k implementaci
**Datum:** 2026-04-14

---

## Princip

Browser pod je **autonomní AI agent** s VLM (vidí obrazovku) a LLM (rozhoduje co dělat).
Server (JERVIS) do podu nezasahuje za běhu — jen čte stav a posílá instrukce.
Pod si řeší login, session recovery, scraping sám. Když neumí → ERROR → čeká na JERVIS.

---

## Stavový automat

```
STARTING → AUTHENTICATING ──→ ACTIVE ──→ RECOVERING → AUTHENTICATING
                ↓                              ↓
           AWAITING_MFA                    ERROR (čeká na JERVIS instrukce)
                ↓                              ↓
              ACTIVE                    EXECUTING_INSTRUCTION → ACTIVE / ERROR
```

### Stavy

| Stav | Význam | VNC | Scraping |
|------|--------|-----|----------|
| **STARTING** | Playwright init, browser startuje | ano | ne |
| **AUTHENTICATING** | Login probíhá (1 pokus) | ano | ne |
| **AWAITING_MFA** | Čeká na uživatele (Authenticator) — notifikuje server | ano | ne |
| **ACTIVE** | Přihlášen, scraping běží | ano | ano |
| **RECOVERING** | Session vypršela, pod se pokouší re-login | ano | ne |
| **ERROR** | Něco selhalo — pod nic nedělá, čeká na instrukce od JERVISe | ano | ne |
| **EXECUTING_INSTRUCTION** | Vykonává instrukci od JERVISe (změna hesla, navigace...) | ano | ne |

### Pravidla přechodů

- **AUTHENTICATING → ERROR:** 1. pokus selhal (špatné heslo, neznámá stránka, VLM/LLM neví co dělat)
- **AUTHENTICATING → AWAITING_MFA:** MFA detected — notifikuje server, čeká
- **AUTHENTICATING → ACTIVE:** Login úspěšný
- **ACTIVE → RECOVERING:** Health loop detekuje login stránku místo Teams
- **RECOVERING → AUTHENTICATING:** Pod se pokusí re-login (1 pokus)
- **RECOVERING → ERROR:** Re-login selhal
- **ERROR → EXECUTING_INSTRUCTION:** JERVIS poslal instrukci
- **EXECUTING_INSTRUCTION → ACTIVE / ERROR:** Instrukce dokončena / selhala

### Klíčové pravidlo: ŽÁDNÉ nekontrolované pokusy

- Login: **1 pokus**. Selže → ERROR.
- Re-login (RECOVERING): **1 pokus**. Selže → ERROR.
- Pod NIKDY neopakuje sám. Opakování jen na pokyn JERVISe.

---

## AI-řízený login flow

### Krok za krokem

1. **Screenshot** → VLM (`capability=vision` přes router):
   ```
   Prompt: "Co vidíš na obrazovce? Odpověz JSON:
   {screen_type, elements: [{text, type, clickable}], input_fields: [{label, type}], error_message}"
   ```

2. **LLM rozhodnutí** (textový model přes router):
   ```
   Prompt: "Jsem browser bot přihlašující se do Microsoft Teams.
   Mám credentials: email=X.
   VLM vidí: {screen_type: 'password_entry', input_fields: [{label: 'Password', type: 'password'}]}
   Co mám udělat? Odpověz JSON: {action, target, value}"
   ```

3. **Vykonání akce:**
   - `{action: "fill", target: "Password", value: "***"}` → vyplní pole
   - `{action: "click", target: "Sign in"}` → klikne na tlačítko
   - `{action: "click", target: "Approve a request on my Microsoft Authenticator"}` → klikne na MFA metodu
   - `{action: "wait", reason: "MFA approval"}` → stav AWAITING_MFA
   - `{action: "error", reason: "Invalid credentials"}` → stav ERROR

4. **Opakuj 1-3** dokud nejsi na Teams (ACTIVE) nebo ERROR.

### Vykonání akce — VLM navigace

Pod musí umět:
- **Kliknout na element podle textu:** VLM identifikuje pozici → Playwright `get_by_text(target).click()`
- **Vyplnit pole podle labelu:** Playwright `get_by_label(target).fill(value)`
- **Popsat problém:** Pokud VLM/LLM neví → screenshot + popis → ERROR + notifikace JERVISu

### Error handling

Pokud LLM vrátí `{action: "unknown"}` nebo VLM nerozpozná stránku:
1. Screenshot uloží na PVC
2. Stav → ERROR
3. POST na server: `{state: "ERROR", reason: "Neznámá stránka", screenshot_path: "...", vlm_description: "..."}`
4. Pod nic nedělá, čeká na instrukce

---

## Instrukce od JERVISe

### API endpoint na podu

```
POST /instruction/{client_id}
{
  "instruction": "Na stránce je formulář pro změnu hesla. Nové heslo je: XYZ123. Vyplň pole 'New password' a 'Confirm password', pak klikni 'Submit'.",
  "timeout_seconds": 60
}
```

### Jak pod vykoná instrukci

1. Stav → EXECUTING_INSTRUCTION
2. Screenshot → VLM: "co vidím?"
3. LLM: "Mám instrukci: {instruction}. VLM vidí: {description}. Co udělat?" → akce
4. Vykoná akci (fill, click...)
5. Screenshot → VLM: "podařilo se?"
6. LLM: "Výsledek: {description}. Instrukce: {instruction}. Je hotovo?" → ano/ne
7. Hotovo → stav ACTIVE (nebo zpět AUTHENTICATING)
8. Selhalo → stav ERROR + popis

### Příklady instrukcí

- "Změň heslo na stránce. Nové heslo: ABC123."
- "Klikni na 'Use another account' a přihlas se jako user@company.com"
- "Na stránce je CAPTCHA. Vyřeš ji." (VLM vidí obrázek, LLM rozhodne)
- "Klikni na Accept pro souhlas s podmínkami"

---

## Persistentní záložky

### Princip

- Browser context persistentní na PVC (cookies, session storage)
- Záložky jsou součást uloženého stavu
- Při startu: co existuje → nechat, neotvírat/nezavírat
- Nové záložky pouze při prvním startu

### Autorizace

- Login v jedné záložce → session cookie platí pro všechny
- Po ACTIVE: refresh ostatních záložek (Teams, Email, Calendar)
- Žádná navigace na login z každé záložky zvlášť

---

## Session health loop

Coroutine, každých 60s po dosažení ACTIVE:

1. **DOM check** — `page.query_selector('[data-tid="chat-list"]')`
   - Existuje → ACTIVE, nic nedělat
   - Neexistuje → krok 2

2. **VLM screenshot** → "co je na obrazovce?"
   - Teams loaded ale jiná stránka → OK (calendar, settings...)
   - Login stránka → stav RECOVERING → 1 pokus re-login
   - Chybová stránka → VLM popis → LLM rozhodnutí → akce nebo ERROR

3. **Notifikace na server** — jen při změně stavu

---

## Chat change detection + notifikace

Po dosažení ACTIVE:

1. **DOM diff každých 30-60s** — porovná chat sidebar s posledním stavem
2. **Nová zpráva** → klikne na chat → DOM extrakce → uloží do `o365_scrape_messages`
3. **VLM fallback** pokud DOM selektory selžou
4. **POST na server** `/internal/o365/session-event` typ `NEW_MESSAGE`:
   - connectionId, chatName, sender, preview
   - Server vytvoří TaskDocument s vysokou prioritou
   - Orchestrátor zpracuje přednostně
5. **Adaptivní intervaly** — aktivní konverzace 30s, klid 5min

---

## Vztah pod ↔ server

| Směr | Co | Kdy |
|------|----|----|
| Pod → Server | Změna stavu | Při každé změně |
| Pod → Server | Nová zpráva (NEW_MESSAGE) | Při detekci |
| Pod → Server | MFA potřeba (AWAITING_MFA + číslo) | Při MFA |
| Pod → Server | ERROR + popis + screenshot | Při selhání |
| Server → Pod | Init config (credentials, capabilities) | Při vytvoření connection |
| Server → Pod | MFA kód (pokud uživatel zadá) | Na vyžádání |
| Server → Pod | Instrukce (text příkaz) | Když JERVIS rozhodne |
| Server ← Pod | Scrape data z MongoDB | Indexer polluje |

---

## Pořadí implementace

1. **Stavový automat** — nové stavy, pod řídí, server čte
2. **AI login flow** — VLM screenshot + LLM rozhodnutí místo selektorů
3. **1 pokus pravidlo** — login/re-login, selže → ERROR
4. **Instrukce API** — `POST /instruction/{client_id}`, VLM+LLM vykonání
5. **Persistentní záložky** — neotevírat/nezavírat, stored state
6. **Session health loop** — 60s DOM check + VLM fallback
7. **Chat change detection** — DOM diff + NEW_MESSAGE notifikace
8. **Odstranění starého kódu** — `_detect_stage`, `auto_login` selektory, server-side INVALID, timeouty

---

## Co se NESMAŽE hned

- `auto_login.py` — refaktoruje se postupně, ne smaže najednou
- `_detect_stage` selektory — zůstanou jako fallback vedle VLM
- Stávající scraping — funguje, jen se přidá health loop a notifikace

## Co se SMAŽE

- Server-side INVALID nastavování (ConnectionRpcImpl)
- Timeouty na MFA (120s), init (90s)
- `setup_tabs` otevírání/zavírání záložek při každém initu
- Duplicitní VNC error hlášky v UI
