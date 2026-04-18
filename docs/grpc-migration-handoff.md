# gRPC migrace — handoff prompt pro další session

> Pracovní kontext pro pokračování Phase 2 gRPC migrace. Tento soubor je
> source of truth — ostatní stavy (todo list, chat history) jsou podružné.

---

## Pravidla (absolutní, nepřekrač)

1. **Pracuje se přímo na `master`.** Žádné další feature branche se netvoří.
   Každý dokončený slice commitni rovnou na master a push na origin.
   Staré branches (`claude/phase2-grpc-completion-5J8kY`) už nejsou aktivní.
2. **Nikde žádný JSON passthrough v protech.** `string *_json` je
   zakázané pole — vše musí být typed (`message`, `repeated`, `oneof`,
   scalární). Pokud narazíš na legacy `*_json`, retype je součást slicu.
3. **Pod-to-pod = gRPC.** REST je povolený jen:
   - Ven na Ollamu a externí providery (OpenRouter, Microsoft Graph přímo,
     Google APIs, Slack, Discord, GitHub/GitLab, Atlassian).
   - `httpGet /health` pro K8s liveness/readiness probes (ne komunikační
     rozhraní).
   - Browser-facing VNC routy (`/vnc-login`, `/vnc-auth`) — končí
     v uživatelově prohlížeči, nejsou pod-to-pod.
4. **UI = kRPC** na `:5500` přes `com.jervis.service.*` interface. Live
   view = push-only `Flow<Snapshot>` (server owns `MutableSharedFlow`).
   Unary pro pagination / one-shot reads.
5. **Handler = jedna koherentní jednotka per-provider.** O365 = jeden
   servicer pokrývající Teams chaty, mail, kalendář, drive, online
   meetings, session. Slack, WhatsApp, atd. každý vlastní handler.
   Žádné umělé štěpení (např. "online meetings" jako samostatná služba
   je nesmysl — to je resource uvnitř O365 gateway).

---

## Aktuální stav (HEAD, začátek slice V1)

- Branch: `master` — pracuj rovnou na něm.
- Poslední relevantní commit: **b2e2d7e** `refactor(contracts): eliminate
  passthrough JSON from 5 protos — typed end-to-end`.
- Zmergnuto přes 29 slices: fázi 2-4 + slices 22-28 + typed retype
  (correction, o365_browser_pool, whatsapp_browser, visual_capture,
  server.visual_capture).
- `meeting-attender` běží na gRPC (slice 28). Všechny ostatní gRPC
  protos mimo 5 retypovaných mají pořád `*_json` passthrough pole.

## Regen stubů

```bash
PATH="/tmp:$PATH" /tmp/protoc321/bin/protoc \
  --proto_path=proto \
  --java_out=shared/service-contracts/build/generated/source/buf/java \
  --grpc-java_out=shared/service-contracts/build/generated/source/buf/grpc-java \
  --grpc-kotlin_out=shared/service-contracts/build/generated/source/buf/grpc-kotlin \
  --plugin=protoc-gen-grpc-java=/tmp/protoc-gen-grpc-java \
  --plugin=protoc-gen-grpc-kotlin=/tmp/protoc-gen-grpc-kotlin \
  proto/jervis/<ns>/<file>.proto

(cd libs/jervis_contracts && python3 -m grpc_tools.protoc \
  --proto_path=/home/user/jervis/proto \
  --python_out=. --pyi_out=. --grpc_python_out=. \
  jervis/<ns>/<file>.proto)

# pro nový namespace:
touch libs/jervis_contracts/jervis/<ns>/__init__.py
```

## Commit + merge flow

```bash
# práce přímo na master, jeden slice = jeden commit
git add -A
git commit -m "<message>"
git push origin master
```

Pokud hook selže, oprav root cause. Nikdy `--no-verify`.

---

## Zbývá dokončit — 17 protů / 36 `*_json` polí

Seřazeno podle priority. Každý krok = samostatný commit.

### V1 — Dokončit scaffolding ze slice b2e2d7e (3 služby)

Typed protos už existují; chybí Python servicer + Kotlin klient + migrace volajících.

**1a. Correction:**
- `backend/service-correction/app/main.py` — sundat `@app.post("/correction/*")` dekorátory (funkce zůstávají volatelné). V `lifespan` spustit `grpc_server.start_grpc_server` (port 5501) — vzor `service-meeting-attender/app/main.py`.
- `backend/server/src/main/kotlin/com/jervis/infrastructure/llm/CorrectionClient.kt` — přepsat na gRPC. DI: `@Qualifier(GrpcChannels.CORRECTION_CHANNEL) channel: ManagedChannel`. Smazat `HttpClient(CIO)`, `ContentNegotiation`, DTO JSON serializace. Každá `suspend fun` = jedno typed stub volání. Odstranit stávající DTO třídy na konci souboru — proto classy je nahrazují.
- `backend/server/src/main/kotlin/com/jervis/infrastructure/config/RpcClientsConfig.kt` — `CorrectionClient` je teď Spring `@Component` s DI channelu, ne builder z `endpoints.correction.baseUrl`. Odstranit `getCorrectionClient()`/`_correctionClient`.
- `k8s/app_correction.yaml` — přidat `containerPort grpc=5501` (name `grpc`), Service port, env `CORRECTION_GRPC_PORT=5501`. Dockerfile `EXPOSE 8000 5501`.

**1b. O365 browser pool (10 call sites):**
- Napsat `backend/service-o365-browser-pool/app/grpc_server.py` — `O365BrowserPoolServicer` implementující 6 RPC (`Health`, `GetSession`, `InitSession`, `SubmitMfa`, `CreateVncToken`, `PushInstruction`). Delegovat na route funkce po sundání dekorátorů.
- V `app/routes/session.py`, `instruction.py`, `vnc_auth.py` (jen `/vnc-token/{cid}`), `health.py` sundat `@router.post/get` dekorátory. **`/vnc-login`, `/vnc-auth`, `/scrape/.../screenshot/...` zůstávají HTTP** (browser-facing).
- `backend/server/src/main/kotlin/com/jervis/infrastructure/grpc/O365BrowserPoolGrpcClient.kt` — dynamický channel cache `ConcurrentHashMap<ObjectId, ManagedChannel>` klíčovaný `ConnectionId` (pody jsou per-connection, různý host z `BrowserPodManager.serviceUrl(cid)`). Vzor: `WhisperRestClient.kt` v `backend/server/.../meeting/`.
- Migrovat `ConnectionRpcImpl.kt` řádky 112, 125, 401, 649, 655, 688, 721, 767, 792, 856 + `BrowserPodMeetingClient.kt` 68, 109 + `ServerMeetingAloneGrpcImpl.kt` (volá `BrowserPodMeetingClient.dispatchLeave`).
- **Dead code smaž:** `ConnectionRpcImpl.kt:829` (`POST /session/{cid}/rediscover` — endpoint NEEXISTUJE na Python straně) a `:1107` (`POST /scrape/{cid}/discover` — taky NEEXISTUJE).

**1c. WhatsApp browser (5 call sites):**
- Napsat `backend/service-whatsapp-browser/app/grpc_server.py` — 5 RPC (`GetSession`, `InitSession`, `TriggerScrape`, `GetLatestScrape`, `CreateVncToken`).
- Sundat dekorátory v `session.py`, `scrape.py`, `vnc_auth.py` (token).
- `WhatsAppBrowserGrpcClient.kt` — statický channel (pod je cluster-wide, bean `WHATSAPP_BROWSER_CHANNEL` už v `GrpcChannels.kt`).
- Migrovat `ConnectionRpcImpl.kt` 515, 1664, 1706, 1748 + `WhatsAppPollingHandler.kt`.
- **Dead code smaž:** `ConnectionRpcImpl.kt:1796` (`GET /scrape/{cid}/sidebar` — neexistuje).

**1d. K8s manifesty:** `app_o365_browser_pool.yaml`, `app_whatsapp_browser.yaml`, `app_correction.yaml` — containerPort grpc=5501, Service grpc port, Dockerfile EXPOSE.

### V2 — Whisper typed

`proto/jervis/whisper/transcribe.proto` má `options_json` + `data_json`.

```proto
message TranscribeOptions {
  string task = 1;  // "transcribe" | "translate"
  string model = 2;
  string language = 3;
  int32 beam_size = 4;
  bool vad_filter = 5;
  bool word_timestamps = 6;
  string initial_prompt = 7;
  bool condition_on_previous_text = 8;
  double no_speech_threshold = 9;
  repeated ExtractionRange extraction_ranges = 10;
  bool diarize = 11;
}
message ExtractionRange { double start_sec = 1; double end_sec = 2; }

message TranscribeEvent {
  oneof payload {
    ProgressEvent progress = 1;
    ResultEvent result = 2;
    ErrorEvent error = 3;
  }
}
message ProgressEvent {
  double percent = 1;
  int32 segments_done = 2;
  double elapsed_seconds = 3;
  string last_segment_text = 4;
}
message ResultEvent {
  string text = 1;
  string language = 2;
  repeated TranscribeSegment segments = 3;
  repeated Speaker speakers = 4;
}
message TranscribeSegment {
  int32 i = 1; double start_sec = 2; double end_sec = 3;
  string text = 4; string speaker = 5;
}
message Speaker { string id = 1; string label = 2; }
message ErrorEvent { string text = 1; string error = 2; }
```

Zdroj shape: `backend/service-whisper/whisper_runner.py` (WhisperOptions + run_whisper result).
Callers: `backend/service-whisper/grpc_server.py` + `backend/server/src/main/kotlin/com/jervis/meeting/WhisperRestClient.kt`.

### V3 — Orchestrator typed (per-proto commity)

| Proto | Field | Typed shape |
|---|---|---|
| `chat.proto:28` | `ChatRequest.payload_json` | `ChatRequest { message, session_id, scope{client_id,project_id,group_id}, repeated Attachment attachments, ... }` → `app/chat/models.py::ChatRequest` |
| `dispatch.proto:34` | `DispatchRequest.payload_json` | `oneof payload { QualifyRequest; OrchestrateRequest; }` (nebo dvě samostatné RPC) |
| `graph.proto:39` | `TaskGraphResponse.graph_json` | `AgentGraph { map<string, GraphVertex> vertices; repeated GraphEdge edges; root_vertex_id; task_id; graph_type; status; ... }` → `app/agent/models.py` |
| `graph.proto:71` | `UpdateVertexRequest.fields_json` | `VertexPatch { optional title, description, parent_id, input_request, status }` (proto3 optional) |
| `graph.proto:91` | `VertexMutationAck.vertex_json` | reuse `GraphVertex` |
| `graph.proto:125` | `MemorySearchResult.result_json` | `MemorySearchResult { repeated GraphHit graph_hits; repeated ArchiveHit archive_hits; repeated KbChunkHit kb_hits; }` — zdroj `app/main.py::search_memory_impl` |
| `voice.proto:38` | `VoiceStreamEvent.data_json` | `oneof payload { PreliminaryAnswer; Responding; Token{text}; Response{text,tts_audio,meeting_id}; Stored{task_id}; Done; ErrorPayload{text}; }` |
| `companion.proto:51` | `AdhocStatusResponse.result_json` | `AdhocResult { summary, mode, repeated string artifacts, error }` — zdroj `companion_runner.py::collect_adhoc_result` |
| `control.proto:66` | `ApproveRequest.chat_history_json` | `repeated ChatMessage chat_history` (reuse z `chat.proto`) |

Callers: Python servicers v `backend/service-orchestrator/app/grpc_server.py` + Kotlin klienti `backend/server/src/main/kotlin/com/jervis/infrastructure/grpc/Orchestrator*GrpcClient.kt` + `PythonOrchestratorClient.kt`.

### V4 — Server typed (8 protů)

Zdroj shape je kotlinx-serialized DTO v `shared/common-dto/` a `shared/common-api/`:

| Proto | Fields | DTO |
|---|---|---|
| `server/environment.proto` | 5× | `EnvironmentDto`, `ComponentDto`, `EnvironmentListDto` |
| `server/environment_k8s.proto` | 3× | `K8sNamespaceStatusDto`, `K8sDeploymentDetailDto`, `K8sServiceDetailDto` |
| `server/filter_rules.proto` | 2× | `FilteringRuleDto` |
| `server/git.proto:34` | 1× | `GitRepoCreationResult` v `backend/server/.../git/` |
| `server/guidelines.proto` | 2× | `GuidelinesDocumentDto`, `GuidelinesUpdateRequest`, `MergedGuidelinesDto` |
| `server/openrouter_settings.proto` | 2× | `OpenRouterSettingsDto`, `OpenRouterStatsDto` |
| `server/project_management.proto` | 5× | `ProjectDto`, `BugTrackerDto`, `StackRecommendationDto` |
| `server/task_api.proto:60` | 1× | `TaskDto` |
| `server/urgency.proto` | 2× | `UrgencyConfigDto` |

Každý proto samostatný commit. Callers: Python `service-orchestrator/app/grpc_server_client.py` (lazy stubs), Kotlin `*GrpcImpl.kt` třídy.

### V5 — O365 Gateway typed (JEDEN handler, per 3-5 RPC commit)

`proto/jervis/o365_gateway/gateway.proto` — nahraď `Request`/`RequestBytes` passthroughy za typed RPC. **Všechno je v jednom `O365GatewayService` bez umělého dělení** — online meetings jsou jen další resource uvnitř O365 (ne samostatná služba).

```proto
service O365GatewayService {
  // Teams chats
  rpc ListChats(ListChatsRequest) returns (ListChatsResponse);
  rpc ReadChat(ReadChatRequest) returns (ListChatMessagesResponse);
  rpc SendChatMessage(SendChatMessageRequest) returns (ChatMessage);

  // Teams teams/channels
  rpc ListTeams(ListTeamsRequest) returns (ListTeamsResponse);
  rpc ListChannels(ListChannelsRequest) returns (ListChannelsResponse);
  rpc ReadChannel(ReadChannelRequest) returns (ListChannelMessagesResponse);
  rpc SendChannelMessage(SendChannelMessageRequest) returns (ChannelMessage);

  // Mail
  rpc ListMail(ListMailRequest) returns (ListMailResponse);
  rpc ReadMail(ReadMailRequest) returns (MailMessage);
  rpc SendMail(SendMailRequest) returns (SendMailAck);

  // Calendar
  rpc ListCalendarEvents(ListCalendarEventsRequest) returns (ListEventsResponse);
  rpc CreateCalendarEvent(CreateCalendarEventRequest) returns (CalendarEvent);

  // Online meetings — součást O365 handleru, ne samostatná služba
  rpc GetOnlineMeetingByJoinUrl(OnlineMeetingByJoinUrlRequest) returns (OnlineMeeting);
  rpc GetOnlineMeeting(OnlineMeetingRequest) returns (OnlineMeeting);
  rpc ListMeetingRecordings(OnlineMeetingRequest) returns (ListRecordingsResponse);
  rpc ListMeetingTranscripts(OnlineMeetingRequest) returns (ListTranscriptsResponse);
  rpc DownloadTranscriptVtt(TranscriptRef) returns (TranscriptContent);

  // Drive
  rpc ListDriveItems(ListDriveItemsRequest) returns (ListDriveItemsResponse);
  rpc GetDriveItem(DriveItemRequest) returns (DriveItem);
  rpc SearchDrive(SearchDriveRequest) returns (ListDriveItemsResponse);

  // Session
  rpc GetSessionStatus(SessionStatusRequest) returns (SessionStatus);
}
```

Zdroj shape: `backend/service-o365-gateway/src/main/kotlin/com/jervis/o365gateway/model/GraphApiModels.kt` (kotlinx-serialized Graph DTO). Deklaruj jen pole, která consumers opravdu čtou (`executor.py` + MCP `main.py` + `ConnectionRpcImpl.kt`) — zbytek orchestrator ignoroval i v JSON režimu.

Callers: `O365GatewayGrpcServer.kt` (Kotlin servicer uvnitř podu) + `service-orchestrator/app/o365_gateway_client.py` + `service-mcp/app/o365_gateway_client.py` + 16 O365 tool funkcí v `executor.py` + 16 v `mcp/main.py`.

**Dělej po 3-5 RPC na commit** (např. Teams chats → commit, Teams channels → commit, Mail → commit, Calendar → commit, Online meetings → commit, Drive → commit, Session → commit). Jeden handler = jeden servicer / jeden klient, ale commity jdou per-skupina RPC.

### V6 — Dead code cleanup (po V1–V5)

- `KnowledgeServiceRestClient.kt` — smaž nepoužívaný `HttpClient` field + Ktor imports (řádky ~29-40, 118).
- `PythonOrchestratorClient.kt` — pokud zbyly Ktor imports bez použití, smaž.
- Audit `ServerConnectionGrpcImpl.kt`, `ProviderRegistry.kt`, `UserTaskRpcImpl.kt` — hledej interní REST.
- `service-orchestrator/app/tools/executor.py:811,855` — `searxng` + externí web fetcher → zůstávají REST (externí).
- `OpenRouterSettingsRpcImpl.kt` — OpenRouter je externí, zůstává.
- `config.py` / `application.yml` — odstranit klíče `correction.baseUrl`, `visualCapture.baseUrl`, `meeting-attender.url`, atd. po přechodu na channel-based DI.

---

## Kontrolní seznam při každém slicu

- [ ] Proto bez `*_json` passthroughů.
- [ ] Stuby regenerovány (Java + Kotlin + Python + `__init__.py`).
- [ ] Python servicer (ne dekorátory, volá native Python funkce).
- [ ] Kotlin klient přes `@Qualifier(GrpcChannels.X_CHANNEL)` DI.
- [ ] Všichni callers migrovaní; REST call sites smazané.
- [ ] Dead endpointy (pokud nějaké) fyzicky smazané.
- [ ] FastAPI retire: sundány `@app.post/get`, funkce zůstává volatelná.
- [ ] K8s manifest: `containerPort grpc=5501` + Service port + `*_GRPC_PORT` env.
- [ ] Dockerfile: `EXPOSE` aktualizovaný.
- [ ] K8s probes: `httpGet /health` dokud služba drží tenký FastAPI pro probe; jinak `tcpSocket :5501`.
- [ ] Commit na `master` + push na `origin master`.
