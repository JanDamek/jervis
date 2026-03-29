# WhatsApp Connection — Design Document

> **Status**: Implementováno (Fáze 1)
> **Přístup**: Browser Session + VLM scraping (analogie O365 Browser Pool)
> **Fáze 1**: Pouze čtení (CHAT_READ)
> **Fáze 2**: Odesílání zpráv (CHAT_SEND)

---

## 1. Přehled

WhatsApp connection pro Jervis využije stejný architektonický vzor jako existující O365 Browser Pool — Playwright browser na malém Linux stroji, který zobrazuje WhatsApp Web a pomocí VLM (Qwen3-VL) periodicky extrahuje obsah obrazovky.

### Proč Browser + VLM a ne API?

- WhatsApp nemá veřejné API pro osobní účty (Business API vyžaduje firemní ověření)
- WhatsApp Web funguje v prohlížeči, přihlášení přes QR kód z telefonu
- Stejný princip jako O365 Browser Session — osvědčený pattern v Jervis

### Rozdíly oproti O365 Browser Pool

| Aspekt | O365 Browser Pool | WhatsApp Browser Pool |
|--------|-------------------|----------------------|
| Přihlášení | Username/password + MFA | QR kód z telefonu |
| Token extraction | Ano (bearer z network) | Ne (E2EE, žádný užitečný token) |
| Tabs | 3 (Chat, Calendar, Email) | 1 (Chat only) |
| API fallback | Graph API (OAuth2) | Žádné API |
| Capabilities | CHAT + EMAIL + CALENDAR | Pouze CHAT |
| Složitost | Vysoká | Nízká |

---

## 2. Architektura

```
┌─────────────────────────────────────────────────────────┐
│  Tiny Linux Box (K8s Pod / standalone Docker)           │
│                                                         │
│  ┌─────────────────────────────────────┐               │
│  │  service-whatsapp-browser           │               │
│  │                                     │               │
│  │  ┌──────────┐  ┌────────────────┐   │  noVNC        │
│  │  │Playwright │  │ Screen Scraper │   │◄─────────┐   │
│  │  │(Chromium) │  │ (VLM → Qwen3) │   │          │   │
│  │  │           │  └───────┬────────┘   │          │   │
│  │  │ WhatsApp  │          │            │          │   │
│  │  │   Web     │   scrape results      │          │   │
│  │  └──────────┘          │            │          │   │
│  │                         ▼            │          │   │
│  │              ┌──────────────────┐    │          │   │
│  │              │  MongoDB Storage │    │          │   │
│  │              │  (whatsapp_*)    │    │          │   │
│  │              └────────┬─────────┘    │          │   │
│  └───────────────────────┼──────────────┘          │   │
│                          │                          │   │
└──────────────────────────┼──────────────────────────┘   │
                           │                              │
                           ▼                              │
┌──────────────────────────────────┐    ┌────────────────┘
│  Kotlin Server                   │    │  Uživatel
│                                  │    │  (QR login)
│  WhatsAppPollingHandler          │    │
│  ├─ reads whatsapp_scrape_msgs   │    │
│  ├─ deduplicates                 │    │
│  └─ indexes → KB                 │    │
│                                  │    │
│  ConnectionRpcImpl               │    │
│  ├─ getBrowserSessionStatus()    │    │
│  └─ manages session lifecycle    │    │
└──────────────────────────────────┘
```

---

## 3. Nový Provider: `WHATSAPP`

### 3.1 ProviderEnum

```kotlin
enum class ProviderEnum {
    // ... existing ...
    WHATSAPP,          // WhatsApp Web (Browser Session)
}
```

### 3.2 ProviderDescriptor

```kotlin
ProviderDescriptor(
    provider = ProviderEnum.WHATSAPP,
    displayName = "WhatsApp",
    capabilities = setOf(
        ConnectionCapability.CHAT_READ,
        // CHAT_SEND přidáme ve Fázi 2
    ),
    protocols = setOf(ProtocolEnum.HTTP),
    supportsCloud = true,
    supportsSelfHosted = false,
    defaultPollingIntervalSeconds = 60,  // WhatsApp je real-time chat → častější polling
    authOptions = listOf(
        AuthOption(
            authType = AuthTypeEnum.NONE,
            displayName = "WhatsApp Web (QR kód)",
            fields = listOf(
                // Telefon číslo jen pro identifikaci, ne pro login
                FormField(FormFieldType.USERNAME, "Telefonní číslo",
                    placeholder = "+420...", required = false),
            ),
        ),
    ),
)
```

### 3.3 ConnectionDocument rozšíření

Žádné nové fieldy potřeba — stávající `o365ClientId` pattern se přejmenuje na obecnější `browserSessionId` (nebo reuse stejného pole). Pro MVP stačí reuse:

- `o365ClientId` → session ID v browser pool (obecný mechanismus)
- `username` → telefonní číslo (informativní)
- `state` → lifecycle (NEW → PENDING_LOGIN → DISCOVERING → VALID)

---

## 4. Backend Service: `service-whatsapp-browser`

### 4.1 Struktura (fork z O365 Browser Pool, zjednodušený)

```
backend/service-whatsapp-browser/
├── Dockerfile
├── requirements.txt
├── app/
│   ├── __init__.py
│   ├── main.py              # FastAPI, port 8091
│   ├── config.py             # Settings (VLM URL, MongoDB, intervals)
│   ├── models.py             # SessionState, SessionStatus, etc.
│   ├── browser_manager.py    # Playwright lifecycle (1 context)
│   ├── screen_scraper.py     # VLM scraping loop
│   ├── scrape_storage.py     # MongoDB: whatsapp_scrape_messages
│   ├── qr_detector.py        # VLM detekce QR stavu
│   ├── vlm_client.py         # Shared VLM integration (Qwen3-VL)
│   ├── kotlin_callback.py    # Notify Kotlin server o změnách
│   ├── vnc_proxy.py          # noVNC pro manuální interakci
│   └── routes/
│       ├── session.py        # POST /session/init, GET /session/status
│       ├── scrape.py         # GET /scrape/latest, GET /scrape/chats
│       └── health.py         # GET /health
```

### 4.2 Session Lifecycle

```
   ┌──────────┐   init()   ┌────────────────┐
   │   IDLE   │──────────►│ PENDING_LOGIN  │
   └──────────┘            │ (QR kód vidět) │
                           └───────┬────────┘
                                   │ VLM detekuje
                                   │ "logged in"
                                   ▼
                           ┌────────────────┐
                           │    ACTIVE      │
                           │ (scraping běží)│
                           └───────┬────────┘
                                   │ VLM detekuje
                                   │ "need to scan QR"
                                   │ nebo phone disconnected
                                   ▼
                           ┌────────────────┐
                           │   EXPIRED      │
                           │ (re-login)     │
                           └────────────────┘
```

### 4.3 QR Kód Login Flow

1. Playwright otevře `https://web.whatsapp.com`
2. VLM analyzuje screenshot → detekuje QR kód stav
3. noVNC URL se zobrazí v Jervis UI → uživatel naskenuje QR telefonem
4. VLM periodicky checkuje (interval 5s) zda se zobrazil chat list
5. Jakmile VLM vidí chat list → stav → ACTIVE, spustí se scraping

**VLM prompt pro QR detekci:**
```python
QR_CHECK_PROMPT = """Analyze this WhatsApp Web screenshot. Determine the current state:
1. If you see a QR code → state: "qr_visible"
2. If you see "Phone not connected" or similar → state: "phone_disconnected"
3. If you see a chat list with conversations → state: "logged_in"
4. If you see a loading spinner → state: "loading"
5. If you see any error message → state: "error", include the message

Return JSON:
{
  "state": "qr_visible|phone_disconnected|logged_in|loading|error",
  "description": "what you see on screen"
}"""
```

### 4.4 Screen Scraper — WhatsApp specifický

Jeden tab, dva režimy scrapování:

#### Režim A: Chat List (sidebar)

```python
CHAT_LIST_PROMPT = """Analyze this WhatsApp Web screenshot. Extract the chat list from the sidebar:

For each visible chat/conversation extract:
- name: Contact or group name
- last_message: Preview of last message
- time: Timestamp shown (e.g. "14:32", "včera", "3.1.2026")
- unread_count: Number of unread messages (green badge), 0 if none
- is_group: true if group chat icon visible
- is_pinned: true if pinned indicator visible

Return JSON:
{
  "chats": [
    {"name": "...", "last_message": "...", "time": "...", "unread_count": 0, "is_group": false, "is_pinned": false}
  ],
  "total_unread": 0,
  "has_new_messages": false
}"""
```

#### Režim B: Open Conversation (detail)

```python
CONVERSATION_PROMPT = """Analyze this WhatsApp Web screenshot showing an open conversation.
Extract all visible messages:

For each message:
- sender: Name (in group) or "me" / contact name (in 1:1)
- time: Timestamp
- content: Message text
- type: "text", "image", "voice", "document", "sticker", "video"
- is_forwarded: true if forwarded label visible
- reply_to: preview of replied message if visible, null otherwise
- attachment_type: null, "image", "video", "document", "voice", "sticker"
- attachment_description: For images describe what you see; for videos describe the thumbnail;
  for documents give filename if visible; for voice notes give duration; for stickers describe content

Also extract:
- conversation_name: Name shown at top
- is_group: whether this is a group chat
- participant_count: number of participants if group

Return JSON:
{
  "conversation_name": "...",
  "is_group": false,
  "participant_count": null,
  "messages": [
    {"sender": "...", "time": "...", "content": "...", "type": "text",
     "is_forwarded": false, "reply_to": null,
     "attachment_type": null, "attachment_description": null}
  ]
}"""
```

#### Přílohy (Attachments)

VLM scraper rozpoznává a popisuje přílohy ve zprávách:
- **Obrázky**: VLM popíše obsah obrázku (osoby, předměty, scéna)
- **Videa**: VLM popíše thumbnail videa
- **Dokumenty**: VLM přečte název souboru pokud je viditelný
- **Hlasové zprávy**: VLM přečte délku trvání
- **Stickery**: VLM popíše obsah stickeru

Metadata jsou uložena v polích `attachmentType` a `attachmentDescription` v MongoDB dokumentech.
Při indexování do KB se popis přílohy přidá do těla zprávy.

### 4.5 Scraping Strategy

```python
class WhatsAppScraper:
    """Adaptive scraping loop for WhatsApp Web."""

    # Intervals
    IDLE_INTERVAL = 120        # 2 min — no unread
    ACTIVE_INTERVAL = 30       # 30s — unread messages detected
    CONVERSATION_INTERVAL = 15 # 15s — open conversation with recent activity
    QR_CHECK_INTERVAL = 5      # 5s — waiting for QR scan

    async def scrape_loop(self):
        while self.running:
            # 1. Screenshot sidebar (chat list)
            sidebar_data = await self.scrape_chat_list()

            # 2. Check for new unread messages
            if sidebar_data.get("has_new_messages"):
                # Identify which chats have unreads
                unread_chats = [c for c in sidebar_data["chats"] if c["unread_count"] > 0]

                for chat in unread_chats:
                    # 3. Click on chat (Playwright click by name)
                    await self.open_chat(chat["name"])
                    await asyncio.sleep(2)  # Wait for render

                    # 4. Screenshot open conversation
                    conv_data = await self.scrape_conversation()

                    # 5. Store messages
                    await self.storage.store_messages(
                        chat_name=chat["name"],
                        messages=conv_data["messages"],
                        is_group=conv_data.get("is_group", False),
                    )

                self.interval = self.ACTIVE_INTERVAL
            else:
                self.interval = self.IDLE_INTERVAL

            await asyncio.sleep(self.interval)
```

**Klíčový rozdíl oproti O365**: WhatsApp scraper aktivně **kliká na chaty s nepřečtenými zprávami** aby je přečetl. O365 scraper jen pasivně čte co je na obrazovce.

### 4.6 MongoDB Collections

```
whatsapp_scrape_results       — raw VLM výstupy (sidebar + conversation screenshots)
whatsapp_scrape_messages      — deduplikované zprávy z VLM scraperu (state: NEW → PROCESSED/SKIPPED)
whatsapp_discovered_resources — seznam známých chatů a jejich metadata
whatsapp_message_index        — Kotlin message index (state: NEW → INDEXED, analogie TeamsMessageIndex)
```

**Scrape message document (whatsapp_scrape_messages):**
```json
{
  "_id": "auto",
  "connectionId": "wa_session_abc123",
  "chatName": "Mamka",
  "sender": "Mamka",
  "content": "Ahoj, jak se máš?",
  "timestamp": "2026-03-25T14:32:00Z",
  "messageHash": "sha256_of_content",
  "isGroup": false,
  "attachmentType": "image",
  "attachmentDescription": "Fotka kočky na gauči",
  "state": "NEW",
  "scrapedAt": "2026-03-25T14:33:15Z"
}
```

**Message index document (whatsapp_message_index):**
```json
{
  "_id": "auto",
  "connectionId": "conn_id",
  "clientId": "client_id",
  "projectId": null,
  "state": "NEW",
  "messageId": "wa_scrape_sha256hash",
  "chatName": "Mamka",
  "isGroup": false,
  "from": "Mamka",
  "body": "Ahoj, jak se máš?\n[image] Fotka kočky na gauči",
  "createdDateTime": "2026-03-25T14:32:00Z",
  "attachmentType": "image",
  "attachmentDescription": "Fotka kočky na gauči"
}
```

---

## 5. Kotlin Server Integration

### 5.1 WhatsAppPollingHandler

Analogie k `O365PollingHandler` — čte z MongoDB `whatsapp_messages`, indexuje do KB.

```kotlin
class WhatsAppPollingHandler : PollingHandler {

    override suspend fun poll(connection: ConnectionDocument) {
        // 1. Check browser session health
        val status = whatsappBrowserClient.getSessionStatus(connection.browserSessionId)

        when (status.state) {
            "ACTIVE" -> {
                // 2. Read new messages from MongoDB
                val messages = whatsappMessageRepo.findUnindexed(connection.id)

                // 3. Index into KB
                for (msg in messages) {
                    kbClient.indexWhatsAppMessage(msg, connection)
                    whatsappMessageRepo.markIndexed(msg.id)
                }

                // 4. Update connection state
                connectionService.updateState(connection.id, ConnectionStateEnum.VALID)
            }
            "EXPIRED", "PENDING_LOGIN" -> {
                connectionService.updateState(connection.id, ConnectionStateEnum.AUTH_EXPIRED)
            }
        }
    }
}
```

### 5.2 UI — WhatsApp Login Dialog

Analogie k `TeamsLoginDialog`, ale jednodušší:

```
┌─────────────────────────────────────┐
│  WhatsApp – Přihlášení              │
│                                     │
│  1. Otevřete WhatsApp na telefonu   │
│  2. Jděte do Nastavení → Propojená  │
│     zařízení → Propojit zařízení    │
│  3. Naskenujte QR kód v okně níže   │
│                                     │
│  ┌─────────────────────────────┐    │
│  │  [Otevřít noVNC okno]      │    │
│  └─────────────────────────────┘    │
│                                     │
│  Stav: ⏳ Čekání na naskenování QR  │
│                                     │
│  [Zrušit]                           │
└─────────────────────────────────────┘
```

Stavy:
- `PENDING_LOGIN` → "Čekání na naskenování QR kódu"
- `ACTIVE` → "Připojeno ✓" → auto-close dialog
- `EXPIRED` → "Odpojeno — naskenujte QR znovu"
- `ERROR` → chybová hláška

---

## 6. K8s Deployment

### 6.1 Service Definition

```yaml
# k8s/whatsapp-browser.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jervis-whatsapp-browser
  namespace: jervis
spec:
  replicas: 1    # Jedna instance = jedno WhatsApp číslo
  template:
    spec:
      containers:
        - name: whatsapp-browser
          image: registry.damek-soft.eu/jandamek/jervis-whatsapp-browser:latest
          ports:
            - containerPort: 8091  # API
            - containerPort: 6080  # noVNC
          env:
            - name: MONGODB_URI
              valueFrom:
                secretKeyRef:
                  name: jervis-secrets
                  key: MONGODB_URI
            - name: VLM_URL
              value: "http://jervis-ollama-router:11434"
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          volumeMounts:
            - name: browser-data
              mountPath: /data/whatsapp  # Persistent browser profile
      volumes:
        - name: browser-data
          persistentVolumeClaim:
            claimName: jervis-data-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: jervis-whatsapp-browser
  namespace: jervis
spec:
  ports:
    - name: api
      port: 8091
    - name: novnc
      port: 6080
```

### 6.2 ConfigMap

```yaml
# Přidat do configmap.yaml
WHATSAPP_BROWSER_URL: "http://jervis-whatsapp-browser:8091"
WHATSAPP_BROWSER_NOVNC_URL: "https://jervis-whatsapp.damek-soft.eu"
```

### 6.3 Persistent Browser Profile

**Kritické**: WhatsApp Web session se ukládá v browser profilu. Pokud se profil ztratí, musí se znovu skenovat QR. Proto:

- Browser profile uložený na PVC (`/data/whatsapp/chromium-profile/`)
- Playwright `--user-data-dir` flag na persistent storage
- Session vydrží i přes restart podu

---

## 7. Fáze 1: Read Only (CHAT_READ)

### Co implementovat

1. **`service-whatsapp-browser`** — Python FastAPI + Playwright + VLM scraper
2. **`ProviderEnum.WHATSAPP`** + `ProviderDescriptor` — Kotlin DTO
3. **`WhatsAppPollingHandler`** — čtení z MongoDB, indexace do KB
4. **UI: WhatsApp login dialog** — noVNC + QR status polling
5. **K8s manifests** — deployment, service, configmap update
6. **Build script** — `k8s/build_whatsapp.sh`

### Data Flow (Fáze 1)

```
WhatsApp Web (Chromium)
    │
    ▼ screenshot (every 30-120s)
VLM (Qwen3-VL)
    │
    ▼ parsed JSON
MongoDB: whatsapp_messages
    │
    ▼ polling (every 60s)
WhatsAppPollingHandler (Kotlin)
    │
    ▼ index
Knowledge Base (ArangoDB)
    │
    ▼ searchable via
kb_search("whatsapp zpráva od Mamka")
```

### Bezpečnost a limity

- **Jedno číslo** — jedna instance service, žádný multi-tenant
- **Read-only** — žádná interakce s UI kromě klikání na chaty pro čtení
- **Rate limit VLM** — max 1 screenshot/10s aby se nezahltil ollama-router
- **Persistent session** — browser profil na PVC, nemusí se scanovat QR po restartu
- **Detekce odpojení** — VLM rozpozná "Phone not connected" banner → EXPIRED stav

---

## 8. Fáze 2: Send (CHAT_SEND) — budoucí

### Princip

Playwright click + type do WhatsApp Web inputu:

```python
async def send_message(self, chat_name: str, text: str) -> bool:
    """Send a message to a specific chat via Playwright UI automation."""
    # 1. Find and click on chat in sidebar
    await self.page.click(f'span[title="{chat_name}"]')
    await asyncio.sleep(1)

    # 2. Find message input box
    input_box = self.page.locator('[contenteditable="true"][data-tab="10"]')

    # 3. Type message
    await input_box.fill(text)

    # 4. Press Enter to send
    await input_box.press("Enter")

    # 5. Verify sent (VLM check)
    await asyncio.sleep(2)
    screenshot = await self.page.screenshot()
    result = await analyze_screenshot(screenshot, SEND_VERIFY_PROMPT)
    return result.get("message_sent", False)
```

### Approval Flow

Odesílání zpráv přes orchestrator approval:
- Agent navrhne zprávu → čeká na schválení uživatele
- Po schválení → `message_send(platform="whatsapp", recipient="Mamka", content="...")`
- CommunicationAgent → WhatsApp Browser Service → Playwright send

---

## 9. MCP Tools (budoucí)

```python
# service-mcp/app/main.py — nové tools
@mcp.tool()
async def whatsapp_list_chats() -> list[dict]:
    """List all WhatsApp chats with last message preview."""

@mcp.tool()
async def whatsapp_read_chat(chat_name: str, limit: int = 20) -> dict:
    """Read recent messages from a specific WhatsApp chat."""

# Fáze 2:
@mcp.tool()
async def whatsapp_send_message(chat_name: str, message: str) -> dict:
    """Send a message to a WhatsApp chat (requires approval)."""
```

---

## 10. Odhadovaný scope práce

### Fáze 1 (Read)

| Komponenta | Popis | Základ |
|-----------|-------|--------|
| `service-whatsapp-browser/` | Python service (FastAPI + Playwright + VLM) | Fork z `service-o365-browser-pool`, stripped down |
| `ProviderEnum.WHATSAPP` | Kotlin DTO rozšíření | 1 enum + 1 descriptor |
| `WhatsAppPollingHandler` | Kotlin polling + KB indexing | Analogie O365PollingHandler |
| UI: Login dialog | Compose dialog s noVNC | Analogie TeamsLoginDialog |
| K8s manifests | Deployment + Service + ConfigMap | Template z o365-browser-pool |
| `build_whatsapp.sh` | Build + deploy script | Template z build_o365.sh |

### Co se reusuje 1:1

- `vlm_client.py` — sdílený VLM klient (Qwen3-VL)
- `vnc_proxy.py` — noVNC server pro manuální login
- `scrape_storage.py` — MongoDB storage pattern
- `kotlin_callback.py` — notifikace Kotlin serveru
- `BrowserSessionStatusDto` — stávající DTO pro session status
- `getBrowserSessionStatus()` / polling v UI — stávající RPC

### Co je nové

- **QR login detekce** — VLM prompt pro stav WhatsApp Web login page
- **Chat-aware scraping** — aktivní klikání na chaty s unread messages
- **WhatsApp-specifické VLM prompts** — sidebar + conversation extraction
- **Persistent browser profile** — WhatsApp session survives pod restarts
