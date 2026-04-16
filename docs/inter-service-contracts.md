# Inter-Service Contracts — SSOT

> **Single source of truth** for every HTTP API between service pods.
> A change on one side forces regeneration and rebuild on every consumer.
> CI blocks the PR if generated code is out of date or if the change is breaking
> without every consumer being updated in the same PR.

---

## 1. Scope

**IN scope** (covered by this document):

- Kotlin server (`backend/server`) ↔ Python service pods (`backend/service-*`).
- Python service pod ↔ Python service pod (orchestrator ↔ KB, orchestrator ↔ router, correction agent ↔ KB, teams pod ↔ server callbacks, …).
- Any future pod — Kotlin or Python — that speaks HTTP/JSON to another pod.

**OUT of scope**:

- **kRPC/CBOR** between Kotlin UI clients and the Kotlin server (`shared/common-api`, `shared/common-dto`). Stays as-is — already single-source-of-truth, stable, streaming-aware (push-only Flows per `docs/ui-design.md` §12).
- Coding agents invoked as K8s Jobs (Claude CLI, Aider, Junie, OpenHands) — those are spawned by the orchestrator, not called via RPC.
- External third-party APIs (Graph API, Slack API, Teams Graph, Atlassian) — those are adapters inside each service, not contracts we own.

---

## 2. Stack

**Protobuf + Buf + gRPC over HTTP/2 cleartext (h2c).**

| Concern | Tool |
|---|---|
| Schema language | Protobuf (proto3) |
| Linting, breaking-change detection, codegen orchestration | [Buf CLI](https://buf.build) |
| Transport | gRPC over HTTP/2 cleartext inside the cluster |
| Kotlin message codegen | `protoc-gen-java` (from `grpc-java`) |
| Kotlin RPC codegen | [`grpc-kotlin`](https://github.com/grpc/grpc-kotlin) (Google, coroutine-native) |
| Python message + RPC codegen | [`grpcio-tools`](https://grpc.io/docs/languages/python/) run **locally** from the top-level `Makefile` (not via a Buf remote plugin) |
| Debug CLI | [`grpcurl`](https://github.com/fullstorydev/grpcurl) with server reflection enabled |

Rationale:

- **gRPC over ConnectRPC** — Protobuf + gRPC is the industry-standard for pod-to-pod RPC: production-proven by Google/Netflix/Square for 10+ years, server-streaming is boring and reliable, deadline/cancellation semantics are canonical, observability tooling is mature. An April 2026 maturity audit flagged `connect-kotlin` as maintenance-only since March 2024 and `connect-python` as pre-1.0 beta with an in-flight 1.0 rewrite by Buf; adopting either as a big-bang target would force a second migration within the year.
- **Buf over raw `protoc`** — canonical lint rules (`STANDARD`), `buf breaking` = CI guardrail blocking drift, per-module config, first-party Gradle plugin.
- **`grpc-kotlin` over Wire** — Wire is excellent for KMP clients, but the server here is JVM-only and `grpc-kotlin` is the reference Google client with first-class coroutine support. Avoiding Wire also sidesteps the "Wire messages + grpc-java stubs" runtime seam.
- **`grpcio-tools` over betterproto** — `grpcio` is Google's reference implementation, covers streaming/deadlines/cancellation/reflection, asyncio-native (`grpc.aio`). `betterproto` v1 is abandoned (last release 2021); `betterproto2` is sub-1.0 — neither is acceptable for the big-bang target.
- **Python codegen runs locally (Makefile), not via the `buf.build/protocolbuffers/python` remote plugin.** The remote plugin has shipped gencode several versions ahead of the latest `protobuf` runtime published on PyPI in 2026 (gencode `7.34.1` vs runtime `6.33.6`), causing `google.protobuf.runtime_version.VersionError` on import. `python -m grpc_tools.protoc` pinned at the same version as every service's installed `grpcio` keeps gencode/runtime in lockstep. Kotlin/Java codegen stays on Buf remote plugins — there is no robust local alternative for `grpc-kotlin`.
- **h2c inside cluster** — K8s ClusterIP services and every Python ASGI server speak HTTP/2 cleartext natively. No TLS termination inside the pod network; observability/tracing via standard gRPC interceptors.

### Versioning

**None.** Single namespace `jervis.*`. Every consumer is in this monorepo; we rebuild everything on breaking changes. No `v1/`, no `v2/`, no suffixes. This matches the project stage ("PoC — no backward compat, full refactoring") and the user's directive that no public API will ever exist.

---

## 3. Directory layout

```
proto/
├── buf.yaml
├── buf.gen.yaml
├── buf.lock
└── jervis/
    ├── common/
    │   ├── types.proto          # Scope, RequestContext (see §"No contract data in HTTP headers"), Urn, Timestamp, IDs
    │   ├── errors.proto         # ErrorDetail message for gRPC status details
    │   ├── pagination.proto
    │   └── enums.proto          # Capability, Priority, TierCap, Kind, SourceType, SourceCredibility
    ├── router/
    │   ├── decide.proto         # RouterService.Decide
    │   └── chat.proto           # RouterService.Chat (server-streaming tokens)
    ├── knowledgebase/
    │   ├── ingest.proto
    │   ├── retrieve.proto
    │   ├── documents.proto
    │   └── graph.proto
    ├── orchestrator/
    │   ├── orchestrate.proto    # OrchestratorService.Orchestrate (server-streaming events)
    │   ├── qualify.proto
    │   ├── approve.proto
    │   └── chat.proto
    ├── server_callback/          # Kotlin server as gRPC server, Python as client
    │   ├── progress.proto
    │   ├── status.proto
    │   ├── streaming_token.proto
    │   ├── qualification.proto
    │   └── agent_question.proto
    ├── whisper/
    │   └── transcribe.proto     # server-streaming segments
    ├── correction/
    │   └── correct.proto
    ├── meeting_attender/
    │   └── attend.proto
    ├── teams_pod/
    │   └── agent.proto
    ├── whatsapp_browser/
    │   └── session.proto
    ├── o365_browser_pool/
    │   ├── session.proto
    │   ├── files.proto
    │   └── mail.proto
    ├── coding_engine/
    │   └── execute.proto
    ├── joern/
    │   └── cpg.proto
    ├── document_extraction/
    │   └── extract.proto        # Tika replacement boundary
    ├── visual_capture/
    │   └── capture.proto
    ├── tts/
    │   └── speak.proto          # server-streaming audio chunks
    └── mcp/
        └── internal.proto       # only if MCP server exposes non-SSE internal RPC
```

Each subdirectory maps 1:1 to one `backend/service-*` pod. File naming follows the dominant capability; split when a single file exceeds ~300 lines of proto.

---

## 4. Transport details

### Wire protocol

gRPC over HTTP/2 cleartext (h2c). Standard gRPC path layout `/jervis.<domain>.<Service>/<Method>` with binary Protobuf body. No JSON on the wire.

Examples:

- `/jervis.router.RouterService/Decide` (unary)
- `/jervis.knowledgebase.KnowledgebaseService/Retrieve` (unary)
- `/jervis.orchestrator.OrchestratorService/Orchestrate` (server-streaming)

### Ports

- Kotlin server: existing Ktor `:5500` keeps kRPC/WebSocket for UI clients. A new listener `:5501` serves gRPC (in-process `ServerBuilder.forPort(5501)`), isolated from Ktor. Health/readiness probes for both ports added to K8s manifests in Phase 0.
- Each Python pod exposes its existing ASGI port as h2c; FastAPI sidechannel (blob upload + vendor proxies like Ollama API) mounts on the same port via ASGI dispatch.

### Debugging

- **gRPC reflection enabled** on every server (`grpc-reflection`) so `grpcurl` discovers schemas at runtime.
- `grpcurl -plaintext -d @ localhost:5501 jervis.router.RouterService/Decide < request.json` round-trips a hand-authored JSON body just like `curl` would against REST.
- `grpcurl -plaintext localhost:5501 list` / `describe` for ad-hoc exploration.
- Server logs emit the full RPC name on every request via a shared logging interceptor.

### No contract data in HTTP headers

**Rule**: every value that affects routing, authorization, business logic, or observability semantics lives **inside the proto payload**. HTTP headers carry transport metadata only (`Content-Type`, `Content-Length`, standard HTTP/2 pseudo-headers). Custom `X-*` headers are forbidden on contract-bearing traffic.

Concretely, every existing custom header is migrated to a payload field:

| Removed header | Payload field |
|---|---|
| `X-Client-Id` | `RequestContext.scope.client_id` |
| `X-Capability` (routing hint) | `RequestContext.capability` |
| `X-Ollama-Priority` | `RequestContext.priority` |
| `X-Intent` | `RequestContext.intent` |
| per-request deadline header | `RequestContext.deadline_iso` |
| correlation / trace header | `RequestContext.request_id`, `RequestContext.trace` |

Every request message that crosses a pod boundary starts with:

```proto
// jervis/common/types.proto
message Scope {
  string client_id = 1;
  string project_id = 2;   // empty string = no project scope
  string group_id  = 3;    // empty string = no group scope
}

message RequestContext {
  Scope scope                  = 1;
  Priority priority            = 2;   // BACKGROUND / FOREGROUND / CRITICAL (enums.proto)
  Capability capability        = 3;   // routing hint (enums.proto)
  string intent                = 4;   // free-form routing tag
  string deadline_iso          = 5;   // RFC3339 UTC; "" = no deadline (falls back to priority)
  TierCap max_tier             = 6;   // NONE / T1 / T2 cap for paid models
  string request_id            = 7;   // correlation id, server-generated if empty
  string task_id               = 8;   // related JERVIS task, "" if none
  int64  issued_at_unix_ms     = 9;
  map<string, string> trace    = 10;  // w3c-traceparent-style extras; no ABI guarantees
}
```

And every RPC's request carries it as field 1:

```proto
message DecideRequest {
  RequestContext ctx          = 1;
  // RPC-specific fields follow
  Capability target_capability = 2;   // what to decide — distinct from ctx.capability
  int32 min_model_size_b       = 3;
}
```

Rationale:

- **One SSOT for cross-cutting semantics.** `buf breaking` catches drift in scope/priority/deadline the same way it catches drift in any other field. Headers were invisible to the schema checker.
- **Uniform serialization.** Binary gRPC never has to parse string headers. JSON debugging (`grpcurl -d @`) shows every contract value in one place.
- **No split between "routed by headers" and "routed by body"** — a router or middleware that wants to inspect the request unmarshals proto, period.
- **Trivial auth extension** later: `RequestContext.credential` proto message if an internal service ever needs per-call auth beyond K8s network policy.

Implementation enforcement: a shared interceptor on every client populates `ctx.request_id`, `ctx.issued_at_unix_ms`, and `ctx.trace`. A shared server interceptor validates `ctx.scope.client_id` is non-empty on every RPC except explicitly-marked unauthenticated ones (health checks). Both interceptors are ~30 LOC in `shared:service-contracts` (Kotlin) and `libs/jervis_contracts/` (Python).

### Streaming

gRPC's server-streaming over HTTP/2 is the canonical mechanism. Used for:

| RPC | Reason |
|---|---|
| `OrchestratorService.Orchestrate` | Progress events (`node_start`, `node_end`, `status_change`, `result`). Replaces current push-back POSTs to Kotlin server. |
| `WhisperService.Transcribe` | Progressive segments as audio chunks are processed. |
| `RouterService.Chat` | Token-by-token LLM streaming to UI. |
| `OrchestratorChatService.Chat` | Chat tokens + tool calls + approvals. |
| `VoiceService.Process` | Voice pipeline (preliminary answer, responding, tokens, TTS). |
| `CompanionService.StreamSession` | Claude companion session events. |
| `TtsService.SpeakStream` | PCM chunks. |

No client-streaming and no bidi until a concrete need appears. Deadlines (`grpc.Deadline`) are honored on every streaming RPC using `RequestContext.deadline_iso` — the client-side interceptor converts ISO to a gRPC deadline automatically.

### Replacing current push-back pattern

Today the Python orchestrator pushes progress via POST to Kotlin `/internal/orchestrator-progress`. After migration:

- **Preferred path**: Kotlin opens `Orchestrate` as a server-stream, holds it open for the duration of the task, and consumes events as the stream emits. One connection, typed events, automatic cancellation when the client disconnects.
- **Fallback path** (only if long-running disconnects prove fragile): keep a `ServerOrchestratorCallbackService` gRPC service on the Kotlin side that Python calls. Same schema benefit — no more untyped dicts — but two connections.

We start with the preferred streaming path. Fallback is pre-specified so there's no untyped regression if streaming must be abandoned.

---

## 5. Kotlin integration

### New Gradle module: `shared:service-contracts`

- **Target**: JVM only (server is JVM; UI does not speak these protocols).
- **Source**: `proto/` (the monorepo single source).
- **Output**: `com.jervis.contracts.<domain>.*` — generated Protobuf messages (Java, Kotlin-friendly) + gRPC service stubs (`grpc-java`) + coroutine-native stubs (`grpc-kotlin`).
- **Depends on**: `shared:common-dto` is NOT a dependency. `service-contracts` is independent. Server modules depend on both and map between them where needed (usually a thin mapper per domain).

### Gradle wiring

```kotlin
// shared/service-contracts/build.gradle.kts
plugins {
    kotlin("jvm")
    id("build.buf")        // Buf Gradle plugin wraps `buf generate`
}

buf {
    // Invokes `buf generate` with proto/buf.gen.yaml as the source of truth
    generate {
        includeImports = true
    }
}

sourceSets.main {
    java.srcDir(layout.buildDirectory.dir("generated/source/buf/java"))
    java.srcDir(layout.buildDirectory.dir("generated/source/buf/grpc-java"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/source/buf/grpc-kotlin"))
}

tasks.named("compileKotlin") {
    dependsOn("buf")
}

dependencies {
    api("io.grpc:grpc-protobuf:1.66.+")
    api("io.grpc:grpc-stub:1.66.+")
    api("io.grpc:grpc-kotlin-stub:1.4.+")
    api("io.grpc:grpc-netty-shaded:1.66.+")
    api("io.grpc:grpc-services:1.66.+")        // reflection for grpcurl
    api("com.google.protobuf:protobuf-kotlin:4.28.+")
}
```

### Consumer wiring

```kotlin
// backend/server/build.gradle.kts
dependencies {
    implementation(project(":shared:service-contracts"))
}
```

Old DTO files that are replaced (deleted, not renamed):

- `PythonOrchestratorClient.kt` — DTOs and HTTP calls move to generated gRPC stubs.
- `KnowledgeServiceRestClient.kt` — same.
- `WhisperRestClient.kt`, `CorrectionClient.kt`, `CascadeLlmClient.kt`, `PythonChatClient.kt`, `OrchestratorCompanionClient.kt`, `DocumentExtractionClient.kt` — same.

Mapping to kRPC DTOs (when UI needs a slightly different shape) lives in a new thin module layer:

- `backend/server/src/main/kotlin/com/jervis/contracts/mappers/*.kt` — pure functions `fun ContractDto.toDomain(): UiDto` and back.

---

## 6. Python integration

### New package: `libs/jervis_contracts/`

```
libs/jervis_contracts/
├── pyproject.toml
├── jervis/                        # generated Protobuf + gRPC stubs — committed
│   ├── common/
│   ├── router/
│   ├── knowledgebase/
│   └── ...
└── jervis_contracts/              # hand-written helpers (interceptors, …)
    ├── __init__.py
    └── interceptors/
```

The split has two roots on purpose: `grpc_tools.protoc` emits absolute
imports (`from jervis.common import enums_pb2`), so the generated tree
must be reachable as the top-level `jervis` package. Hand-written helpers
live under `jervis_contracts/` so they never collide with generated names
and the import surface is explicit (`from jervis.common import ...` for
contract messages, `from jervis_contracts.interceptors import ...` for
client/server middleware).

`pyproject.toml` runtime deps: `grpcio`, `grpcio-tools`, `grpcio-reflection`, `protobuf`. Every Python service consumes the local package:

```toml
# backend/service-orchestrator/pyproject.toml (or requirements.txt)
dependencies = [
    "jervis-contracts @ file://../../libs/jervis_contracts",  # editable
    "grpcio>=1.66",
    "grpcio-reflection>=1.66",
    ...
]
```

In Docker images the package is copied and `pip install -e /libs/jervis_contracts` is run before other deps.

### Client usage example (Python)

```python
from jervis.router import admin_pb2, admin_pb2_grpc
from jervis.common.types_pb2 import RequestContext, Scope
from jervis.common.enums_pb2 import Capability, Priority
from jervis_contracts.interceptors import ClientContextInterceptor, prepare_context
import grpc

async with grpc.aio.insecure_channel("ollama-router:5501") as channel:
    stub = router_pb2_grpc.RouterServiceStub(channel)
    req = router_pb2.DecideRequest(
        ctx=RequestContext(
            scope=Scope(client_id="..."),
            priority=Priority.PRIORITY_FOREGROUND,
            capability=Capability.CAPABILITY_CHAT,
            deadline_iso="2026-04-16T15:00:00Z",
        ),
        target_capability=Capability.CAPABILITY_CHAT,
        min_model_size_b=7,
    )
    resp = await stub.Decide(req)
```

No more `payload = {"capability": ..., "deadline_iso": ...}` dicts. Enum values come from the generated module; a typo is a Python attribute error, not a 422 at runtime.

### Server usage example (Python)

```python
import grpc
from grpc_reflection.v1alpha import reflection
from jervis.knowledgebase import retrieve_pb2, retrieve_pb2_grpc

class KnowledgebaseImpl(retrieve_pb2_grpc.KnowledgeRetrieveServiceServicer):
    async def Retrieve(self, request: retrieve_pb2.RetrieveRequest, context) -> retrieve_pb2.RetrieveResponse:
        ...

async def serve():
    server = grpc.aio.server()
    retrieve_pb2_grpc.add_KnowledgeRetrieveServiceServicer_to_server(KnowledgebaseImpl(), server)
    reflection.enable_server_reflection(
        [retrieve_pb2.DESCRIPTOR.services_by_name["KnowledgeRetrieveService"].full_name,
         reflection.SERVICE_NAME],
        server,
    )
    server.add_insecure_port("[::]:5501")
    await server.start()
    await server.wait_for_termination()
```

FastAPI is kept only for the blob side channel (`PUT /blob/{type}/{id}` raw-bytes uploads) and vendor pass-through (Ollama REST proxy in `service-ollama-router`). Every contract-bearing endpoint becomes gRPC. FastAPI and gRPC can coexist on different ports in the same process; no shared ASGI dispatch needed.

---

## 7. Buf configuration

### `proto/buf.yaml`

```yaml
version: v2
modules:
  - path: .
breaking:
  use:
    - FILE
lint:
  use:
    - STANDARD
  except:
    - PACKAGE_VERSION_SUFFIX   # no versioning by design
  enum_zero_value_suffix: _UNSPECIFIED
  rpc_allow_same_request_response: false
  rpc_allow_google_protobuf_empty_requests: true
  rpc_allow_google_protobuf_empty_responses: true
```

### `proto/buf.gen.yaml`

```yaml
version: v2
plugins:
  # Kotlin/Java — messages
  - remote: buf.build/protocolbuffers/java
    out: ../shared/service-contracts/build/generated/source/buf/java

  # Kotlin/Java — gRPC stubs (grpc-java)
  - remote: buf.build/grpc/java
    out: ../shared/service-contracts/build/generated/source/buf/grpc-java

  # Kotlin — coroutine-native stubs (grpc-kotlin)
  - remote: buf.build/grpc/kotlin
    out: ../shared/service-contracts/build/generated/source/buf/grpc-kotlin

  # Python is generated outside Buf — see Makefile `proto-generate-python`
  # which invokes `python -m grpc_tools.protoc` directly so gencode tracks
  # the installed `grpcio-tools` version (the `buf.build/protocolbuffers/
  # python` remote plugin ships gencode ahead of the PyPI `protobuf`
  # runtime, causing VersionError on import).
```

### `proto/buf.lock`

Committed. Pinned plugin and remote-dep versions. Regenerated only via explicit `buf mod update`.

---

## 8. Build integration

### Local developer flow

```
# Edit proto/jervis/router/decide.proto
$ cd proto
$ buf lint
$ buf breaking --against '.git#branch=master'
$ buf generate
$ git status   # shows regenerated Kotlin + Python files
$ git add -A
```

### Top-level Makefile

```makefile
# /Users/damekjan/git/jervis/Makefile
.PHONY: proto-lint proto-breaking proto-generate proto-verify

proto-lint:
	cd proto && buf lint

proto-breaking:
	cd proto && buf breaking --against '.git#branch=master'

proto-generate:
	cd proto && buf generate

proto-verify: proto-lint proto-breaking
	cd proto && buf generate
	git diff --exit-code -- proto shared/service-contracts/build/generated libs/jervis_contracts/jervis_contracts/_generated
```

### Gradle integration

`shared/service-contracts` calls `buf generate` as a task dependency so a fresh checkout of the repo produces a buildable Kotlin module without any extra command.

### Python integration

Each `backend/service-*/Dockerfile`:

```dockerfile
COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install -e /libs/jervis_contracts
COPY backend/service-<name> /app
RUN pip install -e /app
```

For local dev, a `pip install -e libs/jervis_contracts` once per venv is enough.

---

## 9. CI enforcement

Pipeline order on every PR (non-negotiable):

1. **`make proto-lint`** — style.
2. **`make proto-breaking`** — blocks breaking changes unless every consumer change is in the same PR.
3. **`make proto-generate && git diff --exit-code`** — generated code must be committed and current.
4. **Full Kotlin build** (`./gradlew :shared:service-contracts:build :backend:server:build`).
5. **Full Python build** (per-service `python -m compileall`, unit test entrypoints, mypy/pyright).

If step 2 fails, the PR must either revert the breaking change or include all consumer fixes. There is no allow-list and no override. The combined invariant is: **at every commit on master, every consumer compiles against current proto.**

---

## 10. Generated code commit policy

Generated files are **committed** to the repo. Rationale:

- IDEs (IntelliJ, PyCharm, VS Code) need to resolve types before a Gradle/pip build runs.
- CI can diff against master without regenerating (faster).
- Drift is visible in code review.

Paths:

- `shared/service-contracts/build/generated/source/buf/…` — despite being under `build/`, this tree is un-ignored at the root `.gitignore` (`!shared/service-contracts/build/generated/source/buf/`) so IDEs resolve types on fresh checkout.
- `libs/jervis_contracts/jervis/…` — plain committed directory; the `jervis_contracts/` sibling directory holds hand-written interceptors and is also committed.

Formatters (ktlint, black, ruff) are configured to skip generated paths. Linters likewise.

---

## 11. Migration plan — ordered hard cuts

Each step is one PR. No feature flags, no dual-path operation. Old client is deleted the same PR the new generated client is wired in.

1. **Infrastructure PR**: `proto/` skeleton with `common/*.proto` only, `shared/service-contracts/`, `libs/jervis_contracts/`, Buf config, Makefile, CI wiring. Kotlin module builds empty, Python package installs empty. CI green.
2. **Router** (`service-ollama-router`): smallest surface, highest call volume. Defines `decide.proto`, `chat.proto`. Kotlin `CascadeLlmClient.kt` and Python `router_client.py` rewritten.
3. **Knowledge Base** (`service-knowledgebase`): highest drift today. `ingest.proto`, `retrieve.proto`, `documents.proto`, `graph.proto`. Deletes `PythonIngestRequest` etc. from `KnowledgeServiceRestClient.kt`.
4. **Orchestrator ↔ Kotlin server** (`service-orchestrator`): `orchestrate.proto` as server-streaming replaces push-back POST pattern. Also `qualify`, `approve`, `chat`. Drops untyped dict payloads in `kotlin_client.py`.
5. **Whisper + Correction**.
6. **Teams pod, WhatsApp browser, O365 browser pool, Meeting attender**.
7. **Coding engine, Joern, Document extraction (Tika), Visual capture, TTS**.
8. **MCP internal endpoints** if any remain beyond SSE.
9. **Cleanup PR**: remove all `*Client.kt` DTO duplicates, remove all Pydantic models that were only used for HTTP contracts. Audit confirms zero `payload = {…}` dicts hitting HTTP.

Expected duration: 2–3 weeks of focused work. Each PR is deployable independently (K8s rolling restart; both sides of the wire migrate in the same PR so no partial state exists in production).

---

## 12. Naming conventions

| Element | Convention | Example |
|---|---|---|
| Proto package | `jervis.<domain>` | `jervis.router` |
| Proto file | `snake_case.proto` | `ingest.proto` |
| Service name | `PascalCase` + `Service` | `KnowledgebaseService` |
| Method name | `PascalCase` verb | `Retrieve`, `IngestFull` |
| Message for RPC I/O | `<Method>Request` / `<Method>Response` | `DecideRequest`, `DecideResponse` |
| Domain message | `PascalCase` noun, no suffix | `RouterDecision`, `KbDocument` |
| Field | `snake_case` | `client_id`, `deadline_iso` |
| Enum type | `PascalCase` | `Capability` |
| Enum value | `UPPER_SNAKE_CASE` prefixed with enum name | `CAPABILITY_CHAT` |
| Zero enum value | `<ENUM>_UNSPECIFIED = 0` (mandatory) | `CAPABILITY_UNSPECIFIED = 0` |

Kotlin mapping (grpc-kotlin + grpc-java):

- Proto `jervis.router` → Java `com.jervis.contracts.router` (via `option java_package`) with `RouterServiceGrpcKt` coroutine stub and `RouterServiceGrpc` blocking/async stubs.
- Proto `snake_case` fields → generated Java builders with `setSnakeCase` / `getSnakeCase`; Kotlin callers use idiomatic DSL `routerRequest { snakeCase = "..." }`.

Python mapping (grpcio-tools):

- Proto `jervis.router` → Python `jervis_contracts.router` (with generated `router_pb2.py`, `router_pb2_grpc.py`).
- Proto `snake_case` fields → Python `snake_case` (unchanged).

---

## 13. Enum discipline — preventing drift

The project has a recorded anti-pattern: enum values added in one place, forgotten elsewhere (`feedback-enum-refactor-grep-new-values.md`). Protobuf + `buf breaking` eliminates this:

- Every enum has `<NAME>_UNSPECIFIED = 0` as the first value.
- New values append to the end with a new tag number. Never reuse or reorder tags.
- Renaming a value requires `reserved` on the old tag/name:

```proto
enum Capability {
  CAPABILITY_UNSPECIFIED = 0;
  CAPABILITY_CHAT = 1;
  CAPABILITY_EMBEDDING = 2;
  // Renamed CAPABILITY_VISION → CAPABILITY_VLM
  reserved 3;
  reserved "CAPABILITY_VISION";
  CAPABILITY_VLM = 4;
}
```

`buf breaking` blocks any change that removes, reorders, or retypes an enum value. Consumers using the old name fail to compile. No grep required — the compiler is the grep.

---

## 14. Error handling

gRPC's built-in [status codes](https://grpc.io/docs/guides/status-codes/) are canonical. Services return standard codes:

- `INVALID_ARGUMENT` — validation failure.
- `NOT_FOUND` — resource absent.
- `UNAVAILABLE` — downstream pod not ready.
- `RESOURCE_EXHAUSTED` — rate limit, 503 from router.
- `DEADLINE_EXCEEDED` — matches existing deadline-driven routing (auto-populated from `RequestContext.deadline_iso` via client interceptor).
- `INTERNAL` — unexpected.

Consumer-side: `grpc-kotlin` surfaces errors as `io.grpc.StatusException`; `grpcio` surfaces them as `grpc.RpcError` / `grpc.aio.AioRpcError`. No custom error envelope at the application layer.

A shared `jervis.common.ErrorDetail` message carries structured diagnostics when code alone is insufficient (e.g., router returns which model was tried):

```proto
message ErrorDetail {
  string reason = 1;               // machine-readable short code
  map<string, string> metadata = 2; // free-form context
}
```

Attached via gRPC status `details` field (`google.rpc.Status.details` / `io.grpc.protobuf.StatusProto`), not via response body shape.

---

## 15. What does NOT change

- `shared/common-api` + `shared/common-dto` — kRPC/CBOR single source of truth for UI ↔ server. No proto involvement.
- Push-only Flow streams for UI (`docs/ui-design.md` §12) — unchanged.
- KB graph schema, Memory structures, Thought Map — unchanged.
- K8s deployment, registry, Traefik/nginx ingress — unchanged for external HTTP. Pod-to-pod gRPC stays inside the cluster over ClusterIP; no ingress hop.
- Coding agent spawn pattern (K8s Jobs for Claude CLI, Aider, etc.) — unchanged.
- Memory and MEMORY.md conventions — unchanged.

---

## 16. Non-goals

- No publishing to `buf.build` registry. Proto stays internal to the repo.
- No external public API. Breaking changes are always safe because every consumer is in the monorepo.
- No OpenAPI side-channel. One canonical contract per boundary.
- No GraphQL, no SOAP, no JSON-Schema-only contracts.
- No coexistence with deprecated HTTP endpoints. Migration is a hard cut per service.

---

## 17. Checklist — adding a new endpoint

1. Edit the relevant `proto/jervis/<domain>/<file>.proto`. Add message, add method on the service.
2. `cd proto && buf lint && buf breaking --against '.git#branch=master' && buf generate`.
3. Implement server handler (Kotlin in `backend/server/…` or Python in `backend/service-<name>/…`) against generated service base class.
4. Call from client via generated stub.
5. Delete any old hand-written DTO that duplicates the new message.
6. Update relevant `docs/*.md` if behavior (not just transport) changes.
7. Commit proto, generated code, implementation, and docs in one PR.
8. CI verifies drift-free state, breaking-change compliance, and full-stack build.

---

## 18. Open questions (to resolve during infra PR)

- **Gradle Buf plugin version pin**: `build.buf` 0.11.0 (Jan 2026) is the first-party Gradle plugin; pin exactly in Phase 0 to avoid surprise bumps.
- **grpc-kotlin × Netty classloader**: run gRPC Netty on `:5501` isolated from Ktor `:5500`; use `grpc-netty-shaded` to avoid Netty version conflicts with any transitive Ktor Netty.
- **Python FastAPI coexistence**: each Python service runs gRPC (`grpc.aio` on `:5501`) and FastAPI (for blob upload side-channel on the existing port) as two separate listeners in one process. Verify graceful shutdown coordinates both.
- **Blob upload for KB documents**: resolved — split into `KnowledgeDocumentService.Register(hash, metadata)` (gRPC) + `PUT /blob/kb-doc/{hash}` (thin REST raw-bytes). Proto carries `blob_ref` string; REST carries bytes.
- **MCP SSE**: stays REST (protocol requirement). Only the internal MCP RPC endpoints (if any) go through gRPC; SSE continues on its existing ingress.
- **Ingress h2c**: not required for MVP because pod-to-pod gRPC stays inside the cluster (ClusterIP). If a future client needs gRPC from outside, Traefik supports h2c via configuration — revisit then.
- **Public voice API (Siri/Watch)**: stays REST + multipart. A thin Kotlin handler unpacks multipart and fans out to the gRPC orchestrator internally.

None blocks the architecture decision — all are implementation detail for Phase 0.
