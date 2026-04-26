# VNC sidebar — pod discovery + on-demand mint

SSOT pro sidebar Background **VNC sessions** sekci a její embed v chat
content area. Doplňuje `architecture-push-only-streams.md` (rule #9 —
push streams) a navazuje na master brief Fáze K.

## Problém

Předchozí `VncRpcImpl.subscribeActiveSessions` četl jen
`vnc_tokens` collection (consumed=true, sessionExpiresAt > now). Browser
pody (`jervis-browser-<connectionId>-...`) ale nikdy do `vnc_tokens`
nezapisují — token je vystavován až **on-demand** při kliku z UI.
Důsledek: sidebar v UI permanentně ukazoval `0 VNC` i když 4 browser
pody běžely a měly noVNC port aktivní.

## Topologie (čtení existujícího stavu)

- 4× pod `jervis-browser-<connectionId>-...` (Teams/O365), label
  `app: jervis-browser-<connectionId>`, container ports
  `api=8090, novnc=6080, grpc=5501`. ConnectionId je čitelná z label
  suffixu.
- 1× pod `jervis-whatsapp-browser-0` (StatefulSet), label
  `app: jervis-whatsapp-browser`. ConnectionId NENÍ v label — má vlastní
  ingress `jervis-whatsapp-vnc.damek-soft.eu` s vlastním auth flow.
  **Bude přepsán na browser-pod konvenci jako Teams** (separate task).
- Public access JEN přes `jervis-vnc.damek-soft.eu` ingress →
  `vnc-router` (nginx + lua) → upstream `jervis-browser-<connId>...:8090`.
- Token = one-shot, formát `{connectionId}_{randomHex}`. Lua extrahuje
  `connectionId` z `?token=` (1. request) → routes → sets
  `vnc_session` cookie. Následné requesty routují podle cookie. URL
  v browseru se nemění (per `feedback-vnc-no-password-in-url.md`).

## Architektura

### Server side (Kotlin)

**`VncRpcImpl.subscribeActiveSessions(clientId?): Flow<List<VncSessionSnapshot>>`**

- Scan K8s pods přes fabric8 (sdílený `KubernetesClient` pattern jako
  `AgentJobWatcher`).
- Filter: pod má status.phase=Running AND label `app` startsWith
  `jervis-browser-` AND některý containerPort má name=`novnc`.
- Per pod: `connectionId = label.app.removePrefix("jervis-browser-")`;
  `connection = connectionRepository.getById(connectionId)` pro
  human-readable name.
- WhatsApp pod (`jervis-whatsapp-browser-0`) — emit jeden placeholder
  Snapshot s `connectionId="<whatsapp-todo>"`, `note` field (TODO:
  přepsat na browser-pod konvenci jako Teams).
- Stream emise: K8s `Watch<Pod>` (push, ne polling) — symetrické s
  `AgentJobWatcher.openWatch`. Watcher reopen on close. Initial emit
  z aktuálního list-state.

**`VncRpcImpl.mintVncSession(connectionId): VncSessionAccess`** — nový
kRPC endpoint:

- Vytvoří `VncTokenDocument` (consumed=false, expiresAt=now+5min).
- Pro WhatsApp connectionId: vrátí error
  `"WhatsApp VNC bude přepsán na browser-pod konvenci"`.
- Vrátí `{ vncUrl: "https://jervis-vnc.../?token=<token>", expiresAt }`.
- Token je one-shot (per nginx config) — UI ho hned otevře v embedu /
  externím browseru.

### DTO

`VncSessionSnapshot`:
- Smaže `vncUrl` (nesmysl bez tokenu).
- Přidá `requiresMint: Boolean = true` (UI ví že před zobrazením musí
  zavolat `mintVncSession`).
- Volitelně `note: String? = null` (placeholder text pro WhatsApp).

`VncSessionAccess` (nový):
- `vncUrl: String` — `https://jervis-vnc.../?token=<token>`
- `expiresAt: String` — ISO-8601, UI může počítat countdown.

### UI (Compose multiplatform)

**WebView**: knihovna `io.github.kevinnzou:compose-webview-multiplatform`
(Compose Desktop přes KCEF, Android WebView, iOS WKWebView). Per
`feedback` "max existing libs, no NIH". JCEF runtime download at
first run; cached. Single dependency, ~1 řádek per platform v
`shared/ui-common/build.gradle.kts`.

**`VncEmbedPanel.kt`** (new in `shared/ui-common/.../sidebar/`):

```
┌─ VNC: <connection name> ─────── [↗] [×] ──┐
│  WebView(vncUrl) full panel               │
│  ...                                      │
└────────────────────────────────────────────┘
```

- Header: title + ↗ (otevřít v prohlížeči via `openUrlInBrowser`) + ×
  (close → návrat na chat přes sidebar pattern)
- Body: WebView na vncUrl
- Token expirace: kdyby URL přestala fungovat (5+ min nečinnosti),
  re-mint na klik refresh button.

**`BackgroundViewModel`** rozšíření:

```kotlin
private val _activeVnc = MutableStateFlow<ActiveVnc?>(null)
val activeVnc: StateFlow<ActiveVnc?> = _activeVnc.asStateFlow()

data class ActiveVnc(
    val connectionId: String,
    val connectionLabel: String,
    val vncUrl: String,
    val expiresAt: String,
)

fun openVncEmbed(snapshot: VncSessionSnapshot)  // mint → set state
fun openVncExternal(snapshot: VncSessionSnapshot) // mint → openUrlInBrowser
fun closeVncEmbed()                              // clear state
```

**`BackgroundSection.kt`** VNC row klik:
- Hlavní klik na řádek = embed (`openVncEmbed`)
- Sekundární icon ↗ napravo = external browser (`openVncExternal`)

**`MainScreen.kt`** layout integration:
- V chat content area: pokud `activeVnc != null` → render
  `VncEmbedPanel(activeVnc, onClose = closeVncEmbed)`
- Jinak existující chat content (nezměněno)
- Návrat na chat: user klikne "Hlavní chat" v sidebar (existující
  pattern), ChatViewModel reset → `activeVnc = null` (opačná závislost
  se vyřeší v ViewModel-side handler)

### Detail panel pro agent jobs (Fáze K piece který chyběl)

Stejný pattern v chat content area:
- `BackgroundViewModel.openNarrative(snapshot: AgentJobSnapshot)` — store
  snapshot v `_activeJobSnapshot: StateFlow<AgentJobSnapshot?>`
- `MainScreen.kt`: pokud `activeJobSnapshot != null` → render
  `AgentNarrativeDetailPanel(snapshot, narrative, onClose = closeNarrative)`

VNC embed a detail panel se navzájem vylučují (single chat content
area). Klik na druhou věc zavře první.

## Build & deploy order

1. Spec do `docs/vnc-sidebar-discovery.md` (tento dokument)
2. Server side: VncRpcImpl + DTO + IVncService rozšíření
3. `git commit` (commit BEFORE deploy per recent incident lesson)
4. `./k8s/build_server.sh`
5. Verify v Kibaně: žádný BeanCreation error, K8s Watch open
6. UI side: Add WebView dep do `shared/ui-common/build.gradle.kts`,
   VncEmbedPanel + BackgroundViewModel + MainScreen + sidebar
7. `git commit`
8. `git push origin master`
9. User spustí `./gradlew :apps:desktop:run` pro UI test

## Forbidden

- ❌ Token mint at-emit (each subscribe) — token expiruje za 5 min, na
  klik už expired. ON-DEMAND mint na klik.
- ❌ JCEF / WebView vlastní implementace (per "max existing libs").
- ❌ Změna nginx vnc-router config — existující flow funguje.
- ❌ WhatsApp special handling beyond placeholder + TODO note.
- ❌ Public URL bez tokenu — security violation.

## Acceptance test

1. UI Background sidebar zobrazí 4 VNC řádky (Teams browser pody)
   + 1 WhatsApp placeholder s TODO notem.
2. Klik na browser řádek → embed v chat area, WebView ukazuje noVNC.
3. Klik ↗ → externí browser otevřený s VNC URL (one-shot token).
4. Klik "Hlavní chat" v sidebaru → embed zavřený, návrat na chat.
5. Pod restart → sidebar update via K8s Watch (push, ne polling).
6. Klik na WhatsApp placeholder → toast / dialog "Bude přepsán".

## Source URN

`agent://claude-code/vnc-sidebar-discovery`

## Supersedes

`VncRpcImpl` predchozi vnc_tokens-only implementace (commit
`c540456b4` — Fáze J).
