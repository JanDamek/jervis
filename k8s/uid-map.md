# UID / GID mapping pro Jervis services

Reference SSOT pro Linux user/group identity každé služby. Cíl: na PVC
mount-u + LAN FS auditu vidět který modul soubor vytvořil, ale
sdílená skupina umožní R/W napříč služebními kontejnery.

## GID 1000 — `jervis` (shared group)

Všechny služby Jervis stacku jsou ve **stejné skupině** `jervis` (gid
1000). To umožní:
- PVC `jervis-data-pvc` mount s `fsGroup: 1000` recurse-uje group=1000
- soubory napsané jednou službou (např. `server` zapíše
  `.jervis/brief.md`) jsou čitelné a zapisovatelné jinou službou
  (`coding-agent`) přes group permission bits (`g+rwX`)

## UID per service

Každá služba má **vlastní uid** ve sdílené skupině 1000. Audit
`ls -la` na PVC pak ukáže přesně **kdo** soubor vytvořil:

| Service | UID | Notes |
|---|---|---|
| `server` | 1010 | Kotlin server, hlavní API |
| `orchestrator` | 1020 | Python LangGraph + Claude SDK |
| `mcp` | 1030 | MCP server (FastMCP) |
| `coding-agent` | 1040 | Job-only image, Claude CLI |
| `claude` | 1041 | Job-only image, alias / legacy |
| `companion` | 1042 | Job-only image, Claude companion |
| `kilo` | 1043 | Job-only image, OpenRouter free coding |
| `knowledgebase-read` | 1050 | KB read |
| `knowledgebase-write` | 1051 | KB write (singleton) |
| `correction` | 1060 | Transcript correction (Python + Ollama) |
| `tts` | 1070 | XTTS — VD GPU VM (Docker container) |
| `whisper` | 1071 | Faster-whisper — VD GPU VM |
| `ollama-router` | 1080 | Ollama proxy |
| `o365-gateway` | 1090 | O365 Graph API gateway |
| `o365-browser-pool` | 1091 | O365 browser scrape per-account pods |
| `atlassian` | 1100 | Atlassian connector |
| `github` | 1101 | GitHub connector |
| `gitlab` | 1102 | GitLab connector |
| `document-extraction` | 1110 | Doc parser |
| `visual-capture` | 1120 | OCR / vision |
| `vnc-router` | 1130 | nginx VNC router |
| `whatsapp-browser` | 1140 | WhatsApp scrape |

**Convention**: skupiny po **10** s rezervou pro budoucí varianty
(read/write split, sub-services). Job-only images začínají od **1040**
a v rámci skupiny `coding agents` číslují postupně.

## Dockerfile pattern

```dockerfile
# At the bottom, before ENTRYPOINT:
RUN groupadd -g 1000 jervis && \
    useradd -u <SERVICE_UID> -g jervis -m -s /bin/bash <short-name> && \
    mkdir -p /opt/jervis && \
    chown -R <short-name>:jervis /opt/jervis

USER <short-name>

ENTRYPOINT [...]
```

Příklad pro `server`:

```dockerfile
RUN groupadd -g 1000 jervis && \
    useradd -u 1010 -g jervis -m -s /bin/bash server && \
    chown -R server:jervis /opt/jervis

USER server
```

## K8s manifest pattern (Deployment / Job)

```yaml
spec:
  template:
    spec:
      securityContext:
        runAsUser: <SERVICE_UID>      # unique per service
        runAsGroup: 1000              # shared jervis group
        fsGroup: 1000                 # PVC mount group-writable
      containers: [...]
```

V Kotlin fabric8 (např. `AgentJobDispatcher.kt`):

```kotlin
.withNewSecurityContext()
    .withRunAsUser(<SERVICE_UID>L)
    .withRunAsGroup(1000L)
    .withFsGroup(1000L)
.endSecurityContext()
```

## File mode policy

- **Directories** na sdílené PVC (`.jervis/`, `agent-jobs/<id>/`):
  `0775` (`drwxrwxr-x`) — owner+group rwx, others rx
- **Files** na sdílené PVC (`brief.md`, `CLAUDE.md`, `claude-stream.jsonl`,
  `result.json`): `0664` (`-rw-rw-r--`) — owner+group rw, others r
- **Server-side `writeWorkspacePrep`** (Kotlin) explicit
  `Files.setPosixFilePermissions(...)` — nelze spoléhat jen na umask

## Audit příklad po refactor

```
$ ls -la /opt/jervis/data/clients/<id>/projects/<id>/agent-jobs/<id>/.jervis/
drwxrwxr-x server             jervis  4096 Apr 26 10:00 .
-rw-rw-r-- server             jervis  2914 Apr 26 10:00 brief.md
-rw-rw-r-- server             jervis  3500 Apr 26 10:00 CLAUDE.md
-rw-rw-r-- coding-agent       jervis 12000 Apr 26 10:05 claude-stream.jsonl
-rw-rw-r-- coding-agent       jervis   400 Apr 26 10:05 result.json
```

→ vidíš okamžitě že `brief.md` napsal server, `result.json` napsal agent.

## Rezervovaná čísla

- **0-999** — system uids, **NEPOUŽÍVAT**
- **1000** — `jervis` GROUP only, **ne user**
- **1001-1009** — rezerva (možné budoucí "jervis" virtual accounts)
- **1010-1199** — Jervis service uids (per tabulka výše + budoucí
  rozšíření)
- **1200+** — rezerva pro klientské integrace / sandboxes

## Aplikace

Refactor brief: `memory/project-uid-per-service-fs-audit.md`. Coding
agent K8s Job to provede napříč všemi Dockerfiles + K8s manifests +
fabric8 Kotlin v `AgentJobDispatcher.kt`.
