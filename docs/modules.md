# Jervis — Modules & Services

Co dělá který modul v monorepu. SSOT pro odpovědnosti. Architektura interakcí je v [architecture.md](architecture.md).

## Apps — klienti

| Modul | Platforma | Zodpovědnost |
|---|---|---|
| `apps/desktop` | JVM + Compose Desktop | Hlavní dev klient. Profily pro server URL: `runLocal` (localhost:5500), `runRemote` (LAN), `runPublic` (jervis.damek-soft.eu). Spuštění: `./gradlew :apps:desktop:runLocal` nebo custom přes `-Pjervis.server.url=...`. |
| `apps/mobile` | Android + iOS (Compose Multiplatform) | Mobilní klient. Stejný UI layer jako desktop (shared/ui-common), platform-specific audio recording + push notifikace. |
| `apps/wearApp` | Wear OS (Compose) | Glanceable zobrazení a voice input. |
| `apps/ios` | iOS + watchOS (SwiftUI) | Native watchOS part (SwiftUI, ne Compose) + iOS bridge pro KMP framework. |

## Shared — společné moduly

| Modul | Zodpovědnost |
|---|---|
| `shared/common-dto` | Multiplatform DTOs (data classes sdílené mezi klienty a serverem). Anotované `@Serializable` pro CBOR. |
| `shared/common-api` | RPC interface definice (`IChatService`, `IMeetingService`, atd.). Klienti i server je implementují. |
| `shared/domain` | Repository pattern + `JervisRepository` DI container. |
| `shared/service-contracts` | **Generované gRPC stuby pro Kotlin/Java** (checked-in, `build/generated/source/buf/`). SSOT pro pod-to-pod kontrakty. |
| `shared/ui-common` | Sdílené Compose UI komponenty (design system `JCard`, `JTopBar`, `JListDetailLayout`, atd.). SSOT design v [architecture.md § UI Design Architecture](architecture.md#ui-design-architecture). |

## Libs

| Modul | Zodpovědnost |
|---|---|
| `libs/jervis_contracts` | **Generované gRPC stuby pro Python** (`pip install -e`, workspace member v uv). Zrcadlí Kotlin stuby z `shared/service-contracts`. |

## Backend — Kotlin služby

| Modul | Zodpovědnost |
|---|---|
| `backend/server` | **Hlavní Spring Boot API.** Domain logika, kRPC WebSocket pro UI, gRPC server pro pod-to-pod. MongoDB repositories, LangGraph dispatcher, meeting bridge, chat router. Multi-domain package layout (`agent/`, `chat/`, `meeting/`, `task/`, `environment/`, `git/`, `calendar/`, `teams/`, `client/`, `project/`, atd.). |
| `backend/common-services` | Cross-service utility knihovna pro Kotlin služby. |
| `backend/service-coding-engine` | Kotlin-side code generation / AST operace. |
| `backend/service-atlassian` | Jira + Confluence OAuth klient a scraper. |
| `backend/service-github` | GitHub API klient. |
| `backend/service-gitlab` | GitLab API klient. |
| `backend/service-claude` | **Kotlin dispatcher pro Claude CLI K8s Jobs** (top tier coding agent pro CRITICAL úkoly). Vytváří per-task Jobs s workspace mount a credentials. Podporuje OAuth token (Max/Pro subscription) i API key (pay-per-token). Viz [architecture.md § Claude Companion](architecture.md#orchestrator-architecture-detailed) pro detail. |
| `backend/service-knowledgebase` | Kotlin-side KB API + graph bridge. Doplňuje Python `service-knowledgebase` (embeddings/RAG). |
| `backend/service-o365-gateway` | OAuth 2.1 gateway pro O365 login flow. |
| `backend/shared-entrypoints` | Sdílené K8s job entrypoint skripty (docker images). |

## Backend — Python služby

Jen tam, kde Python přináší nepostradatelné AI/ML knihovny. Kandidáti na Kotlin migraci označeni ⇒.

| Modul | Zodpovědnost | Stack |
|---|---|---|
| `backend/service-orchestrator` | **Agentic orchestrátor** — LangGraph runner, chat handler, task decomposer, LLM dispatch přes LiteLLM + ollama-router. Core logic pipelinu. | LangGraph + LangChain + LiteLLM + MongoDB |
| `backend/service-knowledgebase` | **RAG + graph + embeddings**. Weaviate + ArangoDB operace, dokument indexace, KB search. | LangChain + Weaviate + python-arango |
| `backend/service-o365-browser-pool` | **Teams / Office scraping** per-connection browser pod s LangGraph agentem. Přebírá všechen meeting / chat / file content přes Playwright. | LangGraph + Playwright + MongoDB |
| `backend/service-whatsapp-browser` | WhatsApp Web scraping přes Playwright. ⇒ Kotlin migration candidate (Playwright-Java). | Playwright + FastAPI |
| `backend/service-ollama-router` | LLM router — capability-aware dispatch (CPU/GPU, local/cloud), priority handling, context budget guard. Volán všemi službami co potřebují LLM. ⇒ Kotlin migration candidate. | FastAPI + httpx + Prometheus |
| `backend/service-mcp` | MCP server pro externí AI klienty (Claude.ai, Kilo, Junie). ⇒ Kotlin migration candidate. | fastmcp + motor |
| `backend/service-tts` | Piper TTS synthesis (Linux-only wheel). Běží na VD GPU VM, ne v K8s CPU podu. | piper-tts + FastAPI |
| `backend/service-visual-capture` | ONVIF kamery + OpenCV zachycení snímků pro VLM. | opencv + onvif-zeep + Pillow |
| `backend/service-document-extraction` | Office/HTML/EPUB/archiv parsování do textu pro KB ingest. ⇒ Kotlin migration candidate (Apache Tika). | pymupdf + python-docx + mammoth + beautifulsoup4 |
| `backend/service-correction` | Transcript text normalizace. ⇒ Kotlin migration candidate. | FastAPI + tiktoken |
| `backend/service-joern-mcp` | Joern CPG analysis MCP bridge (stdio). Vytváří K8s Jobs pro Joern CLI. | mcp + kubernetes |
| `backend/service-vnc-router` | Nginx proxy pro VNC streamy z browser podů. **Pure nginx**, žádný Python kód. | nginx |
| `backend/service-joern` | Joern CLI wrapper (2GB image, K8s Job target). | Joern (Scala) |
| `backend/service-kilo` | Kilo agent integration. | — |
| `backend/service-whisper` | Whisper přepis (Linux-only). Běží na VD GPU VM přes `k8s/deploy_whisper_gpu.sh`, ne v K8s CPU podu. | — |
| `backend/service-meeting-attender` | **DEPRECATED** — viz TODO, koncepčně špatné. Smazat. Scraping meetingů je zodpovědnost browser podu, ne samostatného attender podu. | Python (ffmpeg + Playwright) |

## K8s deployment

| Soubor | Účel |
|---|---|
| `k8s/build_<service>.sh` | Per-service: Docker build + push + `kubectl apply` + rollout restart. Auto-registrováno v Gradle jako `dockerBuild<Service>`. |
| `k8s/app_<service>.yaml` | Deployment + Service manifesty. |
| `k8s/build_all.sh` | Sekvenční build+deploy všech služeb. |
| `k8s/deploy_all.sh` | First-time full deploy: namespace + PVC + secrets + configmap + všechny služby + ingress. |
| `k8s/redeploy_service.sh <name>` | Rollout restart konkrétní služby bez rebuildu. |
| `k8s/redeploy_all.sh` | Rollout restart všeho. |
| `k8s/validate_deployment.sh` | Sdílená validační funkce. `kubectl apply` **neodstraní** pole, která už nejsou v YAML (např. `hostPort`, `hostNetwork: true`). Funkce volaná po `apply` provede `kubectl patch` k odstranění stale polí. Viz [organization.md § Deploy gotchas](organization.md#k8s-deployment-gotchy). |
| `k8s/configmap.yaml` | Všech 5 ConfigMapů v jednom souboru. |
| `k8s/pvc.yaml`, `k8s/namespace.yaml`, `k8s/ingress*.yaml` | Cluster-level zdroje. |

## Proto + kontrakty

| Složka | Obsah |
|---|---|
| `proto/jervis/**/*.proto` | **SSOT pro pod-to-pod API.** `make proto-generate` → Kotlin + Python stuby. CI kontroluje drift + `buf breaking`. |
| `shared/service-contracts/build/generated/source/buf/` | Committed Kotlin/Java stuby (reprodukovatelné z proto/). |
| `libs/jervis_contracts/jervis/` | Committed Python stuby. |

## Scripts

| Soubor | Účel |
|---|---|
| `scripts/extract_voice_samples.py` | Příprava voice samples z meeting nahrávek pro XTTS finetuning. |
| `scripts/finetune_xtts.py` | Fine-tune XTTS speaker modelu. |
| `scripts/companion_tail.sh` | Tail orchestrator companion job logy. |
