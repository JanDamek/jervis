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

**Buf + Protobuf + ConnectRPC** (HTTP/1.1 + JSON body).

| Concern | Tool |
|---|---|
| Schema language | Protobuf (proto3) |
| Linting, breaking-change detection, codegen orchestration | [Buf CLI](https://buf.build) |
| Transport | [ConnectRPC](https://connectrpc.com) — HTTP/1.1 POST, JSON or proto body |
| Kotlin message codegen | [`com.squareup.wire`](https://github.com/square/wire) |
| Kotlin RPC codegen | [`connect-kotlin`](https://github.com/connectrpc/connect-kotlin) |
| Python message codegen | [`betterproto`](https://github.com/danielgtaylor/python-betterproto) (async dataclasses) |
| Python RPC codegen | [`connect-python`](https://github.com/connectrpc/connect-python) |

Rationale:

- **ConnectRPC over plain gRPC** — no HTTP/2 requirement, works with our existing Ktor and FastAPI ingress, debuggable with `curl` (JSON mode), streaming supported.
- **Buf over raw `protoc`** — canonical lint rules, `buf breaking` = CI guardrail, per-module config.
- **Wire over `protoc-gen-kotlin`** — idiomatic Kotlin data classes, KMP-ready, active Square maintenance.
- **Betterproto over `protoc-gen-python`** — dataclasses with type hints, async-first, no legacy generated-code ugliness.

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
    │   ├── types.proto          # Scope (client/project/group), IDs, timestamps, urns
    │   ├── errors.proto         # Error envelope (rarely needed; Connect errors cover most)
    │   ├── pagination.proto
    │   └── enums.proto          # Capability, Priority, Kind, SourceType, SourceCredibility
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
    ├── server_callback/          # Kotlin server as ConnectRPC server, Python as client
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

### URL scheme

`POST /jervis.<domain>.<Service>/<Method>`

Examples:

- `POST /jervis.router.RouterService/Decide`
- `POST /jervis.knowledgebase.KnowledgebaseService/Retrieve`
- `POST /jervis.orchestrator.OrchestratorService/Orchestrate` (server-streaming)

### Content-Type

- `application/json` — default for debugging and human inspection. `curl -H 'Content-Type: application/json' -d '{"query":"..."}' http://kb:8080/jervis.knowledgebase.KnowledgebaseService/Retrieve` must work.
- `application/proto` — opt-in for latency-sensitive paths (whisper streaming, router decide). Flip per-client via config flag, schema stays identical.

### Streaming

ConnectRPC supports server-streaming over HTTP/1.1 using the [Connect streaming protocol](https://connectrpc.com/docs/protocol/#streaming-rpcs). Used for:

| RPC | Reason |
|---|---|
| `OrchestratorService.Orchestrate` | Progress events (`node_start`, `node_end`, `status_change`). Replaces current push-back POSTs to Kotlin server. |
| `WhisperService.Transcribe` | Progressive segments as audio chunks are processed. |
| `RouterService.Chat` | Token-by-token LLM streaming to UI. |
| `TtsService.Speak` | PCM chunks. |

No client-streaming and no bidirectional streaming until a concrete need appears. Current needs are all request/response or server-push.

### Replacing current push-back pattern

Today the Python orchestrator pushes progress via POST to Kotlin `/internal/orchestrator-progress`. After migration:

- **Preferred path**: Kotlin opens `Orchestrate` as a server-stream, holds it open for the duration of the task, and consumes events as the stream emits. One connection, typed events.
- **Fallback path** (only if long-running disconnects prove fragile): keep a `server_callback` ConnectRPC service on the Kotlin side that Python calls. Same schema benefit — no more untyped dicts — but two connections.

We start with the preferred streaming path. Fallback is pre-specified so there's no untyped regression if streaming must be abandoned.

---

## 5. Kotlin integration

### New Gradle module: `shared:service-contracts`

- **Target**: JVM only (server is JVM; UI does not speak these protocols).
- **Source**: `proto/` (the monorepo single source).
- **Output**: `com.jervis.contracts.<domain>.*` — data classes + ConnectRPC service interfaces + ConnectRPC client factories.
- **Depends on**: `shared:common-dto` is NOT a dependency. `service-contracts` is independent. Server modules depend on both and map between them where needed (usually a thin mapper per domain).

### Gradle wiring

```kotlin
// shared/service-contracts/build.gradle.kts
plugins {
    kotlin("jvm")
    id("com.squareup.wire")
    id("build.buf")        // Buf Gradle plugin wraps `buf generate`
}

wire {
    kotlin {
        out = layout.buildDirectory.dir("generated/source/wire").get().asFile.path
    }
}

buf {
    // Invokes `buf generate` with this module's buf.gen.yaml
    generate {
        includeImports = true
    }
}

tasks.named("compileKotlin") {
    dependsOn("buf")
}

dependencies {
    api("com.connectrpc:connect-kotlin:0.8.+")
    api("com.squareup.wire:wire-runtime:5.+")
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

- `PythonOrchestratorClient.kt` — DTOs move to generated, HTTP calls move to ConnectRPC client.
- `KnowledgeServiceRestClient.kt` — same.
- `WhisperRestClient.kt`, `CorrectionClient.kt`, `CascadeLlmClient.kt` — same.

Mapping to kRPC DTOs (when UI needs a slightly different shape) lives in a new thin module layer:

- `backend/server/src/main/kotlin/com/jervis/contracts/mappers/*.kt` — pure functions `fun ContractDto.toDomain(): UiDto` and back.

---

## 6. Python integration

### New package: `libs/jervis_contracts/`

```
libs/jervis_contracts/
├── pyproject.toml
├── jervis_contracts/
│   ├── __init__.py
│   └── _generated/           # `buf generate` output — committed
│       ├── common/
│       ├── router/
│       ├── knowledgebase/
│       └── ...
└── tests/
```

`pyproject.toml` has no runtime deps beyond `betterproto` and `connectrpc`. Every Python service consumes it:

```toml
# backend/service-orchestrator/pyproject.toml (or requirements.txt)
dependencies = [
    "jervis-contracts @ file://../../libs/jervis_contracts",  # editable
    ...
]
```

In Docker images the package is copied and `pip install -e` is run before other deps.

### Client usage example (Python)

```python
from jervis_contracts.router import RouterServiceClient, DecideRequest, Capability
import httpx

client = RouterServiceClient(base_url="http://ollama-router:8080", http_client=httpx.AsyncClient())
resp = await client.decide(DecideRequest(
    capability=Capability.CAPABILITY_CHAT,
    deadline_iso="2026-04-16T15:00:00Z",
    min_model_size_b=7,
))
```

No more `payload = {"capability": ..., "deadline_iso": ...}` dicts. Enum values come from the generated module; a typo is a Python syntax error, not a 422.

### Server usage example (Python)

```python
from connectrpc.server import ConnectWSGI
from jervis_contracts.knowledgebase import (
    KnowledgebaseServiceBase,
    RetrieveRequest, RetrieveResponse,
)

class KnowledgebaseImpl(KnowledgebaseServiceBase):
    async def retrieve(self, req: RetrieveRequest) -> RetrieveResponse:
        ...

app = ConnectWSGI()
app.register(KnowledgebaseImpl())
```

FastAPI is dropped for ConnectRPC services. If a service still needs REST endpoints (admin UI, health, file upload multipart), those mount on the same ASGI app as a separate router — ConnectRPC and REST coexist.

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
  # Kotlin — messages (Wire)
  - local: protoc-gen-wire
    out: ../shared/service-contracts/build/generated/wire
    opt:
      - kotlin_package_for_java_package=true

  # Kotlin — RPC (Connect)
  - remote: buf.build/connectrpc/kotlin
    out: ../shared/service-contracts/build/generated/connect-kotlin

  # Python — messages (betterproto)
  - local: protoc-gen-python_betterproto
    out: ../libs/jervis_contracts/jervis_contracts/_generated

  # Python — RPC (Connect)
  - remote: buf.build/connectrpc/python
    out: ../libs/jervis_contracts/jervis_contracts/_generated
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

- `shared/service-contracts/build/generated/…` — despite being under `build/`, this is gitignored exception: `!build/generated/**` in the module's `.gitignore`.
- `libs/jervis_contracts/jervis_contracts/_generated/…` — plain committed directory.

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

Kotlin mapping (Wire):

- Proto `jervis.router` → Kotlin `com.jervis.contracts.router`.
- Proto `snake_case` fields → Kotlin `camelCase` properties.

Python mapping (betterproto):

- Proto `jervis.router` → Python `jervis_contracts.router`.
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

ConnectRPC's built-in [error model](https://connectrpc.com/docs/protocol/#error-codes) is canonical. Services return standard codes:

- `invalid_argument` — validation failure.
- `not_found` — resource absent.
- `unavailable` — downstream pod not ready.
- `resource_exhausted` — rate limit, 503 from router.
- `deadline_exceeded` — matches existing deadline-driven routing.
- `internal` — unexpected.

Consumer-side, Wire/Connect-Kotlin surfaces this as `ConnectException`. Betterproto/Connect-Python surfaces it as `connectrpc.errors.ConnectError`. No custom error envelope at the application layer.

A shared `jervis.common.ErrorDetail` message exists for structured diagnostics when code alone is insufficient (e.g., router returns which model was tried):

```proto
message ErrorDetail {
  string reason = 1;               // machine-readable short code
  map<string, string> metadata = 2; // free-form context
}
```

Attached via ConnectRPC's `details` field, not via response body shape.

---

## 15. What does NOT change

- `shared/common-api` + `shared/common-dto` — kRPC/CBOR single source of truth for UI ↔ server. No proto involvement.
- Push-only Flow streams for UI (`docs/ui-design.md` §12) — unchanged.
- KB graph schema, Memory structures, Thought Map — unchanged.
- K8s deployment, registry, ingress — unchanged. ConnectRPC fits existing HTTP ingress.
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

## 18. Open questions (to resolve before step 1)

- **ConnectRPC Python maturity**: verify `connect-python` supports server-streaming over HTTP/1.1 in the version we pin. If blocker, fall back to ConnectRPC streaming over HTTP/2 inside the cluster (still Connect protocol, just h2c). Adds nothing to spec — transport-only.
- **Wire's KMP story**: confirm JVM-only output is what we want, or whether generating `common` + `jvm` source sets buys us anything (answer today: no — only the server consumes these contracts on JVM).
- **Gradle Buf plugin**: two candidates — `build.buf.gradle` (first-party) and `com.google.protobuf` (legacy). Use first-party.
- **Multipart upload** (currently in `KnowledgeServiceRestClient.uploadKbDocument`): proto doesn't model raw multipart. Options: (a) split into two calls — `RegisterDocument` (proto) + a thin REST `PUT /kb/blob/{id}` for the bytes; (b) use `bytes` field in proto and accept 30 MB messages. Recommend (a).
- **SSE for MCP**: MCP ingress at `jervis-mcp.damek-soft.eu` uses SSE per spec. SSE stays REST (protocol requirement). Only internal MCP RPC goes through ConnectRPC if it exists.

Each open question is tracked for resolution during the infrastructure PR (step 1 of §11). None blocks the decision — the overall architecture is final.
