# Jervis — Project Overview

## Co je Jervis

**Jervis** je personalizovaný AI asistent pro developera, postavený jako **monorepo** s Kotlin Multiplatform frontendy (Desktop / Android / iOS / watchOS / Wear OS) a polyglot backendem (Kotlin/Spring + Python/FastAPI). Běží výhradně jako **privátní K8s deployment** v zázemí uživatele — žádný SaaS, žádná sdílená instance.

## Fáze vývoje

**PoC — žádná backward compatibility, plný refactor kdykoliv dává smysl.** Dokud není postaveno vše co má postaveno být, není co udržovat zpětně kompatibilní. Každá architektonická změna se aplikuje end-to-end.

## Primární rozhraní

**Chat = hlavní interface.** Obrazovky v aplikaci jsou jen debug náhled na stavy, které JERVIS sám řídí. Uživatel s JERVISem mluví — JERVIS rozhodne, které nástroje použít, které služby volat, jaký task vytvořit.

## Klíčové schopnosti

- **Meeting capture a přepis** — desktop loopback recorder + browser pod scraper (Teams, Zoom, Meet). Whisper + pyannote pro diarizaci.
- **Knowledge Base** — perzistentní paměť projektu přes ArangoDB graph + Weaviate vektorové hledání + MongoDB jako primary store. RAG pro každou konverzaci.
- **Orchestrator** — LangGraph agentic runner, který řeší složitější úkoly rozdělením na sub-tasks, plánováním a dispatch na coding agenty (Claude CLI, Junie, Aider, OpenHands) jako K8s Jobs.
- **Coding agent dispatch** — K8s Jobs pro isolované coding úkoly s workspace mount, credentials z K8s secrets.
- **Integrace** — Teams (scraping přes browser pod), WhatsApp Web, Slack, Discord, O365 (kalendář, pošta, soubory), GitHub, GitLab, Jira, Confluence.

## Tech stack

| Vrstva | Technologie |
|---|---|
| **UI** | Compose Multiplatform 1.9.3, Material 3, Kotlin 2.3.0 |
| **Desktop** | JVM (Compose Desktop) |
| **Mobile** | Android (Compose) + iOS (Compose + SwiftUI pro watchOS) |
| **Server** | Kotlin + Spring Boot + Ktor (kRPC WebSocket, CBOR) |
| **Python služby** | FastAPI + uvicorn + LangGraph + LiteLLM + Playwright |
| **Pod-to-pod API** | Protobuf + gRPC over h2c (Buf + grpc-kotlin + grpcio) |
| **UI ↔ Server** | kRPC / WebSocket / CBOR (push-only streams per guideline #9) |
| **Databáze** | MongoDB (primary) + ArangoDB (graph) + Weaviate (vector) |
| **LLM** | Multi-provider přes LiteLLM + Ollama (lokální) + cloud modely, routing přes `service-ollama-router` |
| **Deployment** | Kubernetes (privátní cluster), registry `registry.damek-soft.eu/jandamek` |

## Org principy

- **VŠE přes Jervis pipeline** — přímý přístup do DB/podů jen pro debugging samotného Jervise.
- **DB-level filtering** — nikdy app-side filter, vše v MongoDB/ArangoDB query.
- **Push-only streams** — každý live UI view = kRPC `Flow<Snapshot>`; zákaz pull/refresh round-tripů.
- **Kotlin-first** — nová funkcionalita v Kotlinu. Python pouze kde je nepostradatelný (LangGraph, ML frameworks, piper-tts, whisper, VLM).
- **Docs-first** — žádná implementace bez čtení / update relevantního SSOT.

## Kde začít

- Instalace dev workspace → [organization.md](organization.md)
- Co dělá který modul → [modules.md](modules.md)
- Systémová architektura → [architecture.md](architecture.md)
- Dev rules → [guidelines.md](guidelines.md)
