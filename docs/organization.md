# Jervis — Organization & Dev Workflow

**SSOT** pro lokální dev setup, workspace konfiguraci, build + deploy workflow a K8s gotchy. Popisuje, jak IntelliJ vidí celý Jervis monorepo (Kotlin + Python) jako jediný workspace s jedním Python SDK, a jak se staví a deployuje end-to-end.

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
| `backend/service-X/requirements.txt` | **Prod Docker image** — immutable, reprodukovatelný build | `==X.Y.Z` exact nebo `>=X.Y.Z` (pinuje se přes root `constraints.txt`) |
| `backend/service-X/pyproject.toml` | **Dev workspace** — IntelliJ SDK, lint, unit tests | `>=X.Y.Z` flexibilní |
| `constraints.txt` (root) | **Cross-service SSOT verzí** — sdílené balíčky (fastapi, uvicorn, httpx, pydantic, grpcio, protobuf, mongo) | `==X.Y.Z` exact |

**Drift mezi `pyproject.toml` ↔ `requirements.txt` je přijatelný.** Dockerfile kopíruje `requirements.txt` a `constraints.txt`, instaluje `pip install -r requirements.txt -c /constraints.txt`. `uv sync` čte `pyproject.toml` a resolve-uje nejnovější kompatibilní. Pokud se versions rozejdou, prod je stejný (Docker nezměnil file), lokální dev má novější.

**`constraints.txt` = single source of truth pro meziservisně sdílené verze.** Když chceš bumpnout fastapi ze `0.115.6` na `0.116.0`, upravíš `constraints.txt` a rebuildneš všechny služby — nikdy víc per-service drift. Per-service `requirements.txt` nemusí kopírovat přesné verze sdílených balíčků, stačí `fastapi>=0.115` — Docker build je přes `-c /constraints.txt` pinuje na jednu hodnotu.

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

**Python verze:** root `pyproject.toml` požaduje `>=3.12,<3.13` — sjednoceno napříč root + všemi workspace členy. Upper bound `<3.13` avoids `tree-sitter-languages==1.10.2` missing cp313 wheels (knowledgebase dep). Všechny Dockerfiles používají `python:3.12-slim`. Pokud máš lokálně jen 3.11, nainstaluj 3.12 (`brew install python@3.12`) — uv si ji vybere sám.

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

## Gradle — jednotný build surface

Root `build.gradle.kts` registruje tasky, které pokrývají celé monorepo (Kotlin + Python + Docker + K8s). Shell skripty v `k8s/` zůstávají SSOT; Gradle tasky jsou tenké wrappery, aby **IntelliJ Gradle tool window + `./gradlew`** byly jediný entry point.

### Task skupiny

| Skupina | Tasky | Co dělají |
|---|---|---|
| `jervis build` | `jervisBuildAll`, `buildAll` | Full monorepo build (Kotlin compile/test + všechny Docker images) / Kotlin-only legacy |
| `jervis docker` | `dockerBuild<Service>`, `dockerBuildAll` | Build + push + `kubectl apply` jedné/všech služeb (deleguje na `k8s/build_*.sh`) |
| `jervis deploy` | `redeploy<Service>`, `jervisRedeployAll`, `jervisDeployAll` | Redeploy bez rebuildu / plný infra deploy včetně namespace/PVC/ingress |
| `jervis python` | `pythonLint<Service>`, `pythonLintAll`, `pythonTest<Service>`, `pythonTestAll`, `pythonSync` | Ruff lint / pytest přes `uv run` / `uv sync --all-packages` |
| `jervis proto` | `protoGenerate`, `protoLint`, `protoVerify` | Wrappery kolem `make proto-*` (Protobuf codegen + drift check) |

### Automatická discovery

Tasky se registrují při Gradle configuration time:

- `dockerBuild<Service>` — **automaticky pro každý `k8s/build_*.sh`** (kromě `build_all/image/service.sh`). Přidej nový skript → Gradle ho okamžitě vidí, žádná změna `build.gradle.kts`.
- `redeploy<Service>` — **automaticky pro každý `k8s/app_*.yaml`**.
- `pythonLint<Service>` / `pythonTest<Service>` — pro každou službu v seznamu `pythonWorkspaceServices` (zrcadlí uv workspace members v `pyproject.toml`).

### Naming konvence

Skript `k8s/build_o365_browser_pool.sh` → task `dockerBuildO365BrowserPool`. Underscore-separated snake_case se konvertuje na PascalCase přes `toCamel()` helper v root `build.gradle.kts`.

### Příklady použití

```bash
# Lokální dev check (rychlé)
./gradlew pythonLintAll              # ruff na všech Python službách
./gradlew protoVerify                # lint + breaking + drift check

# Single-service rebuild + redeploy
./gradlew dockerBuildOrchestrator    # build + push + apply + rollout
./gradlew redeployServer             # jen rollout restart (bez rebuildu)

# Celá infrastruktura
./gradlew jervisBuildAll             # Kotlin + všechny Docker images
./gradlew jervisRedeployAll          # Rebuild + restart všech služeb
./gradlew jervisDeployAll            # First-time full deploy (namespace + PVC + ...)

# Proto
./gradlew protoGenerate              # stejné jako `make proto-generate`
```

V IntelliJ Gradle panelu jsou všechny pod `jervis *` skupinami — Double-click = run; drag do **Run Configurations** = save pro opakované spuštění.

### Konvence pro nové služby

Přidáváš novou Python službu? Postup:

1. Vytvoř `backend/service-<name>/pyproject.toml` + `requirements.txt` + `Dockerfile` + `app/`.
2. Přidej do `pyproject.toml` root `[tool.uv.workspace] members`.
3. Přidej do `build.gradle.kts` root do seznamu `pythonWorkspaceServices` (1 řádek).
4. Vytvoř `k8s/build_<name>.sh` + `k8s/app_<name>.yaml` (copy z existujícího).
5. `uv sync` + `./gradlew tasks` → nové tasky jsou vidět.

## Desktop app — dev profily

Desktop klient má tři profily pro server URL (konfigurované v `apps/desktop/build.gradle.kts` v mapě `serverUrls`):

| Profil | URL |
|---|---|
| `local` (default) | `http://localhost:5500` |
| `remote` | `http://192.168.100.117:5500` (LAN) |
| `public` | `https://jervis.damek-soft.eu` |

**Spuštění:**

```bash
./gradlew :apps:desktop:runLocal                            # profil local
./gradlew :apps:desktop:runRemote                           # LAN
./gradlew :apps:desktop:runPublic                           # public
./gradlew :apps:desktop:run -Pjervis.profile=remote         # alternativně přes property
./gradlew :apps:desktop:run -Pjervis.server.url=http://X:Y  # custom URL
```

Z IntelliJ: Gradle tool window → `apps:desktop > application > runLocal/runRemote/runPublic`.

**Build distribučního balíčku:**

```bash
./gradlew :apps:desktop:packageDistributionForCurrentOS   # auto-detekce OS
./gradlew :apps:desktop:packageDmg                         # macOS
./gradlew :apps:desktop:packageMsi                         # Windows
./gradlew :apps:desktop:packageDeb                         # Linux
# → apps/desktop/build/compose/binaries/main/
```

## K8s deployment gotchy

### `kubectl apply` neodstraní pole, která už nejsou v YAML

Pokud z `app_<service>.yaml` odstraníš např. `hostNetwork: true` nebo `hostPort: 11430`, pole **zůstane** v K8s deploymentu. Vede to k port konfliktům při rolling update nebo nesrovnalostem mezi declared state a aktuálním stavem.

**Řešení:** všechny `k8s/build_*.sh` skripty používají `validate_deployment.sh`, která po `kubectl apply`:
1. Projede deployment spec
2. Automaticky provede `kubectl patch` k odstranění nežádoucích polí
3. Loguje warning

**Kontrolovaná pole:** `hostNetwork: true`, `hostPort` v container ports.

**Scripty s validací:** `build_server.sh`, `build_orchestrator.sh`, `build_ollama_router.sh`, `build_kb.sh`, `build_service.sh`, `build_all.sh`.

**Přidání nové kontroly:** edituj `validate_deployment_spec()` v `k8s/validate_deployment.sh`.

### `jervis-secrets` + `regcred` prerequisites

`build_all.sh` validuje před deployem:
- `jervis-secrets` (musí existovat — obsahuje credentials včetně `CLAUDE_CODE_OAUTH_TOKEN` / `ANTHROPIC_API_KEY`)
- `regcred` (pull secret pro private registry `registry.damek-soft.eu/jandamek`)

Setup secrets:

```bash
kubectl create secret generic jervis-secrets \
  --from-literal=CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-... \
  --from-literal=ANTHROPIC_API_KEY=sk-ant-api03-... \
  --dry-run=client -o yaml | kubectl apply -f -
```

## Budoucí fáze dev workflow

- **Remote debug v K8s** — `debugpy` v Python entrypoint (env flag `PYTHON_DEBUG=1`), JDWP v JVM server. Port-forward helper `scripts/k8s-debug.sh`.
- **Commit `.idea/runConfigurations/*.xml`** pro debug + build (force-add přes globální `.idea` ignore, nebo team-wise).

## Troubleshooting

**`VIRTUAL_ENV=python-workspace/.venv` warning při `uv` volání:**
Stará hodnota v tvé shell env z předchozího setupu. `unset VIRTUAL_ENV` nebo restart shellu. Warning je kosmetický — uv používá správně projektový `.venv/`.
