# Jervis — Unified IntelliJ Workspace

**SSOT** pro lokální dev setup. Popisuje, jak IntelliJ vidí celý Jervis monorepo (Kotlin + Python) jako jediný workspace s jedním Python SDK.

## Přehled

```
jervis/
├── pyproject.toml              ← root uv workspace (virtual)
├── uv.lock                     ← locked dep versions (committed)
├── .venv/                      ← uv-managed venv (gitignored)
├── libs/jervis_contracts/      ← sdílený gRPC contracts package (editable)
├── backend/
│   ├── service-<py>/           ← Python služby s vlastním pyproject.toml
│   │   ├── pyproject.toml      ← workspace member
│   │   ├── requirements.txt    ← prod Docker SSOT (exact pins)
│   │   └── Dockerfile
│   └── service-<kt>/           ← Kotlin služby (Gradle modul)
├── shared/                     ← Kotlin multiplatform moduly (Gradle)
├── apps/                       ← Desktop/Android/iOS/Wear (Gradle)
└── .idea/
    ├── modules.xml             ← committed — všechny moduly včetně Python
    └── modules/backend/<svc>/  ← committed .iml pro každý Python modul
```

## Proč dvě vrstvy deps

| Soubor | Účel | Pins |
|---|---|---|
| `backend/service-X/requirements.txt` | **Prod Docker image** — immutable, reprodukovatelný build | `==X.Y.Z` exact |
| `backend/service-X/pyproject.toml` | **Dev workspace** — IntelliJ SDK, lint, unit tests | `>=X.Y.Z` flexibilní |

**Drift mezi nimi je přijatelný.** Dockerfile kopíruje `requirements.txt` a instaluje exact pins. `uv sync` čte `pyproject.toml` a resolve-uje nejnovější kompatibilní. Pokud se versions rozejdou, prod je stejný (Docker nezměnil file), lokální dev má novější.

Kdy obě soubory upravit současně:
- Přidání nové dep → obě
- Upgrade major verze → obě (s testem)
- Hotfix patch → stačí `requirements.txt`; pyproject.toml se doladí při větším redesignu

## uv workspace členové

Virtuální root (`pyproject.toml`) obsahuje:

```toml
[tool.uv.workspace]
members = [
    "libs/jervis_contracts",
    "backend/service-correction",
    "backend/service-document-extraction",
    "backend/service-joern-mcp",
    "backend/service-knowledgebase",
    "backend/service-mcp",
    "backend/service-o365-browser-pool",
    "backend/service-ollama-router",
    "backend/service-orchestrator",
    "backend/service-tts",
    "backend/service-visual-capture",
    "backend/service-whatsapp-browser",
]

[tool.uv.sources]
jervis-contracts = { workspace = true }
```

**Vyloučeno:** `service-meeting-attender` (deprecated — viz `project-next-session-todos.md` #21).

## Kdy zůstává Python vs. kandidát na Kotlin migraci

Per `feedback-kotlin-first.md` + design diskuze 2026-04-16:

**Stays Python** (LangGraph / ML bindings):
- `service-orchestrator` — LangGraph agentic runner
- `service-knowledgebase` — RAG + embeddings + graph
- `service-tts` — piper-tts
- `service-visual-capture` — OpenCV / VLM
- `service-o365-browser-pool` — LangGraph + Playwright (sdílí orchestrator stack)

**Kotlin migration candidates** (označeny v pyproject.toml description):
- `service-mcp` — MCP bridge, žádný ML
- `service-ollama-router` — HTTP router
- `service-whatsapp-browser` — Playwright-Java
- `service-correction` — text normalizace
- `service-document-extraction` — Apache Tika ekvivalent
- `service-joern-mcp` — stdio MCP server

Migrace = oportunistická, při netriviální úpravě. Žádná velká "migration sprint".

## Instalace / sync

**První checkout / po pullu změn v pyproject.toml:**

```bash
uv sync --all-packages
```

`--all-packages` instaluje deps všech workspace members. Bez flagu se nainstalují jen root dev deps.

**Python verze:** root `pyproject.toml` požaduje `>=3.11,<3.13`. uv vybere lokálně dostupnou verzi (aktuálně Python 3.11 z brew). Dockerfiles používají `python:3.12-slim` — drift je OK, dev venv a prod image nemusí sdílet exact verzi.

**Reset venv:**

```bash
rm -rf .venv && uv sync --all-packages
```

## IntelliJ setup

1. **Otevři root `jervis/` v IntelliJ Ultimate / PyCharm Professional** — Gradle sync proběhne automaticky, načte všechny Kotlin moduly (`shared/*`, `backend/*` Kotlin, `apps/*`).
2. **Python SDK:** `File → Project Structure → SDKs → Add → Python SDK → Virtualenv Environment → Existing → /Users/.../jervis/.venv/bin/python`. Pojmenuj `Python 3.11 (jervis)` (toto jméno očekávají `.iml` soubory).
3. **Python moduly** se automaticky načtou z `.idea/modules.xml` + `.idea/modules/backend/<service>/*.iml`. Každý Python service je samostatný modul s Python facet používajícím sdílený SDK.
4. **Cross-service navigation:** Cmd+Click na `jervis_contracts.jervis.server.cache_pb2` z kterékoliv Python služby skočí do `libs/jervis_contracts/` díky editable install.

## Platform-specific dep markery

Některé balíčky nemají wheels pro všechny platformy. Řeší se markery v pyproject.toml:

```toml
"piper-tts==1.2.0; sys_platform == 'linux'",  # no macOS ARM64 wheel
```

Prod Docker (Linux) nainstaluje, Mac dev přeskočí. Editor stále vidí modul a umí lint/IntelliSense (jen runtime by spadl — což je OK, TTS běží na VD per `feedback-audio-services-on-vd.md`).

## Co NENÍ v tomto workspace

- **`service-whisper`** — nemá `Dockerfile` ani `requirements.txt` v repo. Běží na VD přes `deploy_whisper_gpu.sh`.
- **`service-vnc-router`** — pure nginx, žádný Python.
- **`service-meeting-attender`** — deprecated (TODO #21).
- **Gradle Kotlin moduly** — spravuje je Gradle přímo, uv o nich neví.

## Commit guideline

- `uv.lock` → **committed** (reprodukovatelnost).
- `.venv/` → **gitignored**.
- `.idea/modules.xml`, `.idea/modules/backend/**/*.iml` → **committed** (sdílený workspace).
- `pyproject.toml` (root + per-service) → **committed**.

## Troubleshooting

**IntelliJ neznačí Python moduly / nerozpoznává Python SDK:**
1. Zkontroluj že SDK se jmenuje přesně `Python 3.11 (jervis)` (případ-sensitive).
2. `File → Invalidate Caches → Invalidate and Restart`.

**`uv sync` failuje na missing wheel:**
1. Zkontroluj Python verzi (musí být v `>=3.11,<3.13`).
2. Pokud je balíček platform-specific, přidej `; sys_platform == 'linux'` marker.

**Imports fungují v CLI ale ne v IntelliJ:**
1. IntelliJ Python SDK musí ukazovat na `.venv/bin/python`.
2. Každý modul musí mít Python facet (v `.iml` souboru).

## Budoucí fáze (odloženo po dokončení gRPC Phase 1)

- **Fáze 3:** Gradle `buildSrc` convention plugin `jervis.python-service` — tasky `pythonLint<Service>`, `dockerBuild<Service>`, root `jervisBuildAll`. Deleguje na existující `k8s/build_*.sh`.
- **Fáze 4:** Remote debug v K8s podech — `debugpy` v Python entrypoint (env flag), JDWP v JVM. Port-forward helper `scripts/k8s-debug.sh`.
- **Fáze 5:** Commit `.idea/runConfigurations/*.xml` pro debug + build.

Viz také `docs/inter-service-contracts.md` pro Protobuf + gRPC kontrakty, které používají všechny Python služby přes `jervis_contracts` workspace member.
