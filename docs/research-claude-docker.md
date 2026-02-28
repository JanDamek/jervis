# Research: Oficiální Claude Docker Image vs. Jervis Wrapper

> **Datum:** 2026-02-28
> **Stav:** Výzkum dokončen, čeká se na rozhodnutí

---

## 1. Existující oficiální Docker podpora od Anthropic

### 1.1 Oficiální Docker image?

**NE – Anthropic NEPUBLIKUJE oficiální Docker image na Docker Hub.**

Neexistuje `anthropic/claude-code:latest` ani žádný jiný oficiálně publikovaný image.
Anthropic poskytuje pouze:
- **Referenční DevContainer Dockerfile** v repozitáři `anthropics/claude-code`
- **Dokumentaci** jak si image sestavit

Uživatel/organizace si musí image buildít sami z referenčního Dockerfile nebo z vlastního.

### 1.2 Oficiální DevContainer (referenční implementace)

Anthropic udržuje referenční devcontainer v repozitáři [anthropics/claude-code](https://github.com/anthropics/claude-code/tree/main/.devcontainer):

| Soubor | Účel |
|--------|------|
| `.devcontainer/Dockerfile` | Node.js 20 base + Claude Code CLI + firewall |
| `.devcontainer/devcontainer.json` | VS Code/IDE konfigurace, extensions, volumes |
| `.devcontainer/init-firewall.sh` | Egress firewall – default-deny + allowlist |

**Klíčové vlastnosti:**
- Node.js 20 base image
- Claude Code instalován globálně: `npm install -g @anthropic-ai/claude-code@${VERSION}`
- ZSH s Powerlevel10k
- Firewall s default-deny egress policy (allowlist: npm, GitHub, Claude API)
- Podpora `--dangerously-skip-permissions` pro unattended mode

### 1.3 Claude Agent SDK (nový – nahrazuje claude-code-sdk)

**Přejmenování:**
- `@anthropic-ai/claude-code` (npm CLI) → stále funguje, **npm instalace deprecated** ve prospěch nativního instalátoru
- `claude-code-sdk` (Python PyPI) → **deprecated** → nahrazen `claude-agent-sdk`
- `@anthropic-ai/claude-agent-sdk` (npm TypeScript SDK) → **aktuální verze 0.2.63**

**Dva způsoby programatického použití:**

```python
# Python SDK
from claude_agent_sdk import query, ClaudeAgentOptions

async for msg in query(
    prompt="Fix the bug in auth.py",
    options=ClaudeAgentOptions(
        allowed_tools=["Read", "Edit", "Bash"],
        mcp_servers={"jervis": {"type": "http", "url": "http://jervis-mcp:8100/mcp"}},
        max_turns=50,
    )
):
    print(msg)
```

```typescript
// TypeScript SDK
import { query } from "@anthropic-ai/claude-agent-sdk";

const result = query({
  prompt: "Review this PR",
  options: {
    allowedTools: ["Read", "Grep", "Glob"],
    mcpServers: { /* ... */ },
  }
});
```

### 1.4 Hosting Guide od Anthropic

Anthropic publikoval [oficiální hosting guide](https://platform.claude.com/docs/en/agent-sdk/hosting):

**Požadavky na container:**
- Python 3.10+ NEBO Node.js 18+
- Claude Code CLI: `npm install -g @anthropic-ai/claude-code`
- Min. 1 GiB RAM, 5 GiB disk, 1 CPU
- Outbound HTTPS na `api.anthropic.com`

**Doporučené deployment patterny:**
1. **Ephemeral Sessions** – nový container per task, zničen po dokončení ← **ODPOVÍDÁ NAŠEMU K8S JOB MODELU**
2. **Long-Running Sessions** – persistentní container s více procesy
3. **Hybrid Sessions** – ephemeral + session resumption z DB
4. **Single Containers** – více agentů v jednom containeru

### 1.5 Secure Deployment Guide

[Oficiální security guide](https://platform.claude.com/docs/en/agent-sdk/secure-deployment):

| Technologie | Izolace | Overhead | Složitost |
|------------|---------|----------|-----------|
| Sandbox runtime | Dobrá | Velmi nízký | Nízká |
| Containers (Docker) | Závisí na setup | Nízký | Střední |
| gVisor | Výborná | Střední/Vysoký | Střední |
| VMs (Firecracker) | Výborná | Vysoký | Střední/Vysoká |

**Doporučení pro hardened container:**
```bash
docker run \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=100m \
  --network none \
  --memory 2g --cpus 2 --pids-limit 100 \
  --user 1000:1000 \
  -v /path/to/code:/workspace:rw \
  agent-image
```

**Credential management:** Proxy pattern – agent nikdy nevidí API klíče přímo.

---

## 2. Současný stav Jervis: Náš Claude wrapper

### 2.1 Dockerfile (`backend/service-claude/Dockerfile`)

Aktuální image: **vlastní build** na `eclipse-temurin:21-jre-jammy`:
- JRE 21 (pro Kotlin service)
- Node.js 20
- Claude Code CLI (`npm install -g @anthropic-ai/claude-code`)
- GitHub CLI (`gh`)
- MongoDB shell (`mongosh`)
- ArangoDB shell (`arangosh`)
- PostgreSQL client (`psql`)
- Python venv + Joern MCP server
- Kotlin JAR (`jervis-claude.jar`) – persistent deployment mód

### 2.2 Entrypoint Job (`shared-entrypoints/entrypoint-job.sh`)

Spuštění Claude v K8s Job:
```bash
claude --dangerously-skip-permissions --output-format json [--model $CLAUDE_MODEL] "$INSTRUCTIONS"
```

### 2.3 Workspace Manager (`app/agents/workspace_manager.py`)

Python orchestrátorový kód, který:
1. Zapisuje `.jervis/instructions.md` + `task.json`
2. Vytváří `.claude/mcp.json` (HTTP MCP server)
3. Generuje `CLAUDE.md` s kontextem projektu
4. Nastavuje global gitignore (`.jervis/`, `.claude/`, `CLAUDE.md`)
5. Importuje GPG klíč pro commit signing

---

## 3. Analýza: Přejít na oficiální image vs. zachovat wrapper?

### Varianta A: Buildovat z referenčního Anthropic DevContainer Dockerfile

```
┌──────────────────────────────────────────────────┐
│  FROM node:20  (Anthropic referenční Dockerfile) │
│  + Claude Code CLI (npm)                         │
│  + init-firewall.sh (egress filtering)           │
│  + ZSH/tooling                                   │
│  ── plus naše extensions ────────────────────── │
│  + DB klienty (mongosh, arangosh, psql)          │
│  + GitHub CLI                                    │
│  + GPG tooling                                   │
│  + Joern MCP server                              │
└──────────────────────────────────────────────────┘
```

| Pro | Proti |
|-----|-------|
| Sleduje Anthropic reference (firewall, security) | Musíme ručně sledovat Anthropic devcontainer změny |
| Egress firewall z devcontaineru | Chybí Python Agent SDK vrstva |
| | Stále CLI invokace, ne programatické řízení |
| | Musíme mergovat naše extensions s referencí |

### Varianta B: Vlastní build + Python Agent SDK wrapper ← **DOPORUČENO**

```
┌──────────────────────────────────────────────────┐
│  FROM node:22-slim (nebo node:20)                │
│  ┌─────────────────────────────────────────────┐ │
│  │ Claude Code CLI (npm install -g)            │ │
│  │ + Python 3.12 + claude-agent-sdk (pip)      │ │
│  │ + DB klienty (mongosh, arangosh, psql)      │ │
│  │ + GitHub CLI                                │ │
│  │ + GPG tooling                               │ │
│  │ + Joern MCP server (stdio)                  │ │
│  │ + entrypoint-job.sh                         │ │
│  │ + init-firewall.sh (z Anthropic reference)  │ │
│  └─────────────────────────────────────────────┘ │
│  CLAUDE.md ← injektován workspace managerem     │
│  .mcp.json ← injektován workspace managerem     │
│  .gitignore global ← nastaveno entrypointem     │
└──────────────────────────────────────────────────┘
```

| Pro | Proti |
|-----|-------|
| Python SDK pro programatické řízení | Musíme buildovat vlastní image |
| Claude Agent SDK (`claude-agent-sdk`) | Claude Code CLI verzi musíme updatovat |
| Sjednocené Python rozhraní (orchestrátor ↔ agent) | |
| Firewall adoptován z Anthropic reference | |
| Kompatibilní s naším orchestrátorem | |
| DB klienty a MCP servery zachovány | |
| Plná kontrola nad security a toolingem | |

### Varianta C: Ponechat současný build (status quo)

| Pro | Proti |
|-----|-------|
| Žádná změna architektury | Claude Code se musí manuálně aktualizovat |
| Funguje dnes | Nesleduje best practices Anthropic |
| | Firewall a security musíme řešit sami |
| | Zbytečný JRE 21 (Kotlin JAR nepotřebný pro K8s Job) |

---

## 4. Doporučení: Varianta B – Hybridní přístup

### 4.1 Nový Dockerfile

> Pozn.: Anthropic nepublikuje oficiální Docker image. Buildujeme vlastní
> z Node.js base, s Claude Code CLI nainstalovaným přes npm. Adoptujeme
> firewall script z referenčního devcontaineru.

```dockerfile
# =============================================================================
# Jervis Claude Agent – K8s Job Image
# Based on Anthropic reference devcontainer pattern
# =============================================================================
FROM node:22-slim

# === System dependencies ===
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    git curl wget ca-certificates gnupg lsb-release \
    ripgrep jq diffutils patch less tree file unzip \
    procps findutils make \
    dnsutils iproute2 openssh-client \
    python3 python3-pip python3-venv \
    # Firewall (adopted from Anthropic devcontainer)
    iptables ipset sudo \
    && rm -rf /var/lib/apt/lists/*

# === Claude Code CLI (globally via npm) ===
RUN npm install -g @anthropic-ai/claude-code

# === GitHub CLI ===
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
    | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg && \
    chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] \
    https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list && \
    apt-get update && apt-get install -y gh && rm -rf /var/lib/apt/lists/*

# === DB clients (needed for agent DB operations) ===
# MongoDB Shell
RUN curl -fsSL https://www.mongodb.org/static/pgp/server-7.0.asc \
    | gpg --dearmor -o /usr/share/keyrings/mongodb-server-7.0.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg] \
    https://repo.mongodb.org/apt/debian bookworm/mongodb-org/7.0 main" \
    > /etc/apt/sources.list.d/mongodb-org-7.0.list && \
    apt-get update && apt-get install -y --no-install-recommends mongodb-mongosh && \
    rm -rf /var/lib/apt/lists/*
# ArangoDB Shell
RUN curl -fsSL https://download.arangodb.com/arangodb311/DEBIAN/Release.key \
    | gpg --dearmor -o /usr/share/keyrings/arangodb.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/arangodb.gpg] \
    https://download.arangodb.com/arangodb311/DEBIAN/ /" \
    > /etc/apt/sources.list.d/arangodb.list && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends arangodb3-client && \
    rm -rf /var/lib/apt/lists/*
# PostgreSQL client
RUN apt-get update && apt-get install -y --no-install-recommends postgresql-client && \
    rm -rf /var/lib/apt/lists/*

# === Python venv: Agent SDK + MCP servers ===
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir claude-agent-sdk

# Joern MCP server (stdio subprocess)
COPY backend/service-joern-mcp/requirements.txt /opt/jervis/mcp/joern-requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/joern-requirements.txt
COPY backend/service-joern-mcp/server.py /opt/jervis/mcp/joern-server.py

# === Entrypoint ===
COPY backend/shared-entrypoints/entrypoint-job.sh /opt/jervis/entrypoint-job.sh
RUN chmod +x /opt/jervis/entrypoint-job.sh

# NOTE: No Kotlin JAR needed – K8s Job mode only (no persistent deployment)
# NOTE: No JRE needed – saves ~200MB vs eclipse-temurin base

ENTRYPOINT ["/opt/jervis/entrypoint-job.sh"]
```

**Klíčové změny oproti současnému Dockerfile:**
- **Odstraněn JRE 21** – K8s Job nepotřebuje Kotlin service (úspora ~200MB)
- **Přidán `claude-agent-sdk`** – Python SDK pro programatické řízení
- **Base `node:22-slim`** místo `eclipse-temurin:21-jre-jammy`
- **Odstraněn `jervis-claude.jar`** – Job mode nepotřebuje persistent deployment

### 4.2 Injekce konfigurace při vytváření podu

**Workspace Manager už dnes správně injektuje:**

1. **CLAUDE.md** → `workspace/CLAUDE.md`
   - Project context, instrukce, KB kontext, environment info
   - MCP tool descriptions
   - FORBIDDEN ACTIONS (git rules)

2. **`.mcp.json`** → `workspace/.mcp.json` (nebo `.claude/mcp.json` – obojí funguje)
   - HTTP MCP server endpoint (jervis-mcp)
   - Bearer token auth
   - Pozn.: Claude Code oficiálně preferuje `.mcp.json` v project root

3. **Global .gitignore** → via `entrypoint-job.sh`
   - `.jervis/`, `.claude/`, `CLAUDE.md`, `.aider.conf.yml`

**Co přidat:**

4. **`~/.claude/settings.json`** → Globální Claude Code settings
   ```json
   {
     "permissions": {
       "allow": ["Read", "Write", "Edit", "Bash", "Glob", "Grep"],
       "deny": []
     },
     "preferences": {
       "outputFormat": "json"
     }
   }
   ```

5. **Rozšířit `.claude/mcp.json`** o další MCP servery z UI settings (viz sekce 5)

### 4.3 Python SDK wrapper místo CLI

Namísto:
```bash
claude --dangerously-skip-permissions --output-format json "$INSTRUCTIONS"
```

Použít Python Agent SDK:
```python
from claude_agent_sdk import query, ClaudeAgentOptions

async def run_claude_agent(instructions: str, mcp_config: dict, max_turns: int = 50):
    """Run Claude agent with full programmatic control."""
    messages = []
    async for msg in query(
        prompt=instructions,
        options=ClaudeAgentOptions(
            model=os.environ.get("CLAUDE_MODEL", "claude-sonnet-4-6"),
            allowed_tools=["Read", "Write", "Edit", "Bash", "Glob", "Grep", "mcp__jervis"],
            mcp_servers=mcp_config,
            max_turns=max_turns,
            permission_mode="dangerously_skip_permissions",
            cwd=os.environ["WORKSPACE"],
        )
    ):
        messages.append(msg)
    return messages
```

**Výhody Python SDK wrapperu:**
- Strukturovaný výstup (ne parsování CLI stdout)
- Callback na každý tool use (logování, metriky)
- Session resumption podpora
- MCP servery konfigurovatelné programaticky (ne jen soubory)
- Lepší error handling
- Sjednocené rozhraní s orchestrátorem (Python ↔ Python)

---

## 5. MCP Server Settings v UI

### 5.1 Proč to potřebujeme

Coding agenti (Claude, Junie) a chat/orchestrátor potřebují přístup k nástrojům přes MCP. Aktuálně máme:
- `jervis-mcp` (HTTP) – KB, environment, Jira, Git tools
- `joern-server.py` (stdio) – code analysis

**Uživatel by měl moci v UI:**
1. Přidat/odebrat vlastní MCP servery (např. Sentry, Datadog, vlastní API)
2. Konfigurovat, které MCP servery jsou dostupné pro chat vs. coding agenty
3. Definovat env vars a auth tokeny pro MCP servery
4. Sdílet MCP konfiguraci napříč projekty (per-client scope)

### 5.2 Navrhované DTO

```kotlin
// MCP server definition
data class McpServerSettingsDto(
    val name: String,                    // "sentry", "datadog", "custom-api"
    val type: String,                    // "http" | "stdio"
    val url: String?,                    // for HTTP type
    val command: String?,                // for stdio type
    val args: List<String>?,             // for stdio type
    val headers: Map<String, String>?,   // auth headers
    val env: Map<String, String>?,       // env vars for stdio
    val enabledForChat: Boolean = true,  // available to chat/orchestrator
    val enabledForAgents: Boolean = true,// available to coding agents
    val scope: String = "client",        // "client" | "project"
)

// Collection of MCP settings per client
data class McpSettingsDto(
    val clientId: String,
    val servers: List<McpServerSettingsDto>,
)
```

### 5.3 Integrace s workspace managerem

Při vytváření K8s Job podu orchestrátor:
1. Načte MCP settings z DB (per client + per project)
2. Sloučí se systémovými MCP servery (jervis-mcp, joern)
3. Vygeneruje `.claude/mcp.json` se všemi servery
4. Pro stdio servery: zahrne binárky do Docker image nebo mount z PVC

### 5.4 UI Sekce v Settings

```
Settings > MCP Servers (Nástroje)
┌──────────────────────────────────────────────────┐
│  MCP Servery – externí nástroje pro AI agenty    │
│                                                  │
│  ┌─ jervis (systémový) ─────────────── ✓ ─────┐ │
│  │ HTTP: http://jervis-mcp:8100/mcp            │ │
│  │ ☑ Chat  ☑ Coding agenti                     │ │
│  └─────────────────────────────────────────────┘ │
│                                                  │
│  ┌─ joern (systémový) ──────────────── ✓ ─────┐ │
│  │ stdio: python3 /opt/jervis/mcp/joern.py     │ │
│  │ ☐ Chat  ☑ Coding agenti                     │ │
│  └─────────────────────────────────────────────┘ │
│                                                  │
│  ┌─ sentry (vlastní) ──────────────── ✏ ✕ ───┐ │
│  │ HTTP: https://sentry.io/api/mcp             │ │
│  │ Auth: Bearer ***                             │ │
│  │ ☑ Chat  ☑ Coding agenti                     │ │
│  └─────────────────────────────────────────────┘ │
│                                                  │
│  [+ Přidat MCP server]                           │
└──────────────────────────────────────────────────┘
```

---

## 6. Globální .gitignore a AI security

### 6.1 Současný stav (funguje dobře)

Entrypoint `entrypoint-job.sh` nastavuje:
```bash
git config --global core.excludesFile /tmp/.jervis-global-gitignore
```

Obsah:
```
.jervis/
.claude/
CLAUDE.md
.aider.conf.yml
```

### 6.2 Doporučení na rozšíření

```gitignore
# Jervis orchestrator artifacts (auto-generated, NEVER commit)
.jervis/
.claude/
CLAUDE.md
.aider.conf.yml
.junie/

# AI agent cache/temp files
.claude-cache/
.aider.tags.cache.v*/
.openhands/

# Security – credentials that might be generated
.env.local
*.pem
*.key
```

### 6.3 Pre-commit hook (volitelně)

```bash
#!/bin/bash
# Prevent AI artifacts from being committed
FORBIDDEN_PATTERNS=(".jervis/" ".claude/" "CLAUDE.md" ".aider.conf.yml")
for pattern in "${FORBIDDEN_PATTERNS[@]}"; do
    if git diff --cached --name-only | grep -q "$pattern"; then
        echo "ERROR: Attempting to commit AI agent artifact: $pattern"
        exit 1
    fi
done
```

---

## 7. Auto-update Claude Code CLI z NAS/PVC cache

### 7.1 Problém

Claude Code CLI (`@anthropic-ai/claude-code`) se aktualizuje i vícekrát denně.
Baked-in verze v Docker image zastarává za hodiny. Rebuildit image pokaždé
je zbytečný overhead.

### 7.2 Řešení: NAS-cached npm prefix + entrypoint update

```
┌─────────────────────────────────────────────────────┐
│  Docker Image (stabilní, rebuild jen pro tool updates)│
│  ┌───────────────────────────────────────────────┐  │
│  │ Node.js 20 + system tools + baked-in CLI      │  │
│  │ /opt/jervis/update-claude-cli.sh              │  │
│  │ /opt/jervis/entrypoint-job.sh                 │  │
│  └───────────────────────────────────────────────┘  │
│                                                     │
│  PVC Mount: /opt/jervis/data/                       │
│  └── claude-cli/          ← NAS-cached CLI          │
│      ├── bin/claude       ← latest binary           │
│      ├── lib/node_modules/                          │
│      ├── .installed-version                         │
│      └── .update.lock     ← flock for concurrency   │
└─────────────────────────────────────────────────────┘
```

**Tok při startu K8s Job:**

1. `entrypoint-job.sh` sourcuje `update-claude-cli.sh`
2. Script kontroluje čas od posledního updatu (default interval: 1 hodina)
3. Pokud je update potřeba → `flock` zamkne `.update.lock`
4. `npm install -g @anthropic-ai/claude-code@latest` s `NPM_CONFIG_PREFIX=/opt/jervis/data/claude-cli`
5. Zapíše verzi do `.installed-version`
6. Odemkne lock → další joby skočí přes
7. Prepend `PATH` s NAS bin → NAS verze má přednost před baked-in

**Časové nároky:**
- Už aktuální: **~2-5s** (npm kontrola registru)
- Potřeba update: **~10-30s** (stažení + instalace)
- Čekání na lock (jiný job updatuje): **max 120s** (timeout)

### 7.3 Soubory

| Soubor | Účel |
|--------|------|
| `backend/service-claude/update-claude-cli.sh` | Auto-update script s flock + NAS cache |
| `backend/shared-entrypoints/entrypoint-job.sh` | Sourcuje update script pro `AGENT_TYPE=claude` |
| `backend/service-claude/entrypoint-job.sh` | Standalone entrypoint, sourcuje update script |
| `backend/service-claude/Dockerfile` | Kopíruje update script + keeps baked-in CLI as fallback |

### 7.4 Konfigurace (env vars)

| Proměnná | Default | Popis |
|----------|---------|-------|
| `CLAUDE_CLI_CACHE` | `/opt/jervis/data/claude-cli` | Cesta k NAS cache |
| `CLAUDE_CLI_UPDATE_INTERVAL` | `3600` (1h) | Min. sekund mezi update checky |

### 7.5 Fallback

Pokud NAS/PVC není dostupný nebo `npm update` selže, agent automaticky
použije baked-in verzi CLI z Docker image. Žádný hard fail.

---

## 8. Shrnutí akcí

### Dokončeno

| # | Akce | Stav |
|---|------|------|
| 1 | Auto-update Claude CLI z NAS/PVC cache (`update-claude-cli.sh`) | **Hotovo** |
| 2 | Integrace do shared entrypoint + standalone entrypoint | **Hotovo** |
| 3 | Dockerfile: kopíruje update script, keeps baked-in CLI as fallback | **Hotovo** |

### Okamžité (další kroky)

| # | Akce | Priorita |
|---|------|----------|
| 4 | Přepsat `service-claude/Dockerfile` na `FROM node:22-slim` (bez JRE) | Vysoká |
| 5 | Přidat Python Agent SDK wrapper (`claude-agent-sdk`) jako alternativu k CLI | Vysoká |
| 6 | Rozšířit `.gitignore` template v entrypointu | Střední |
| 7 | Přidat `~/.claude/settings.json` injekci do workspace manageru | Střední |

### Střednědobé (nové features)

| # | Akce | Priorita |
|---|------|----------|
| 8 | MCP Server Settings UI (nová sekce v Settings) | Vysoká |
| 9 | MCP Settings DTOs + RPC + Repository | Vysoká |
| 10 | Integrace MCP settings do workspace manageru | Vysoká |
| 11 | MCP settings propagace do chat/orchestrátoru | Střední |

### Dlouhodobé (architektura)

| # | Akce | Priorita |
|---|------|----------|
| 12 | Přechod z CLI invokace na Python Agent SDK v entrypointu | Střední |
| 13 | Session resumption support pro long-running tasks | Nízká |
| 14 | Sandbox runtime (`@anthropic-ai/sandbox-runtime`) evaluace | Nízká |

---

## 9. Zdroje

- [Claude Code DevContainer](https://code.claude.com/docs/en/devcontainer) – Oficiální dokumentace
- [Claude Code Headless/Programmatic](https://code.claude.com/docs/en/headless) – `-p` flag, CLI options
- [Claude Agent SDK Overview](https://platform.claude.com/docs/en/agent-sdk/overview) – SDK dokumentace
- [Hosting the Agent SDK](https://platform.claude.com/docs/en/agent-sdk/hosting) – Container patterns
- [Secure Deployment](https://platform.claude.com/docs/en/agent-sdk/secure-deployment) – Security hardening
- [Claude Code GitHub](https://github.com/anthropics/claude-code) – Source + devcontainer
- [`@anthropic-ai/claude-agent-sdk` npm](https://www.npmjs.com/package/@anthropic-ai/claude-agent-sdk) – TypeScript SDK
- [`claude-agent-sdk` PyPI](https://pypi.org/project/claude-agent-sdk/) – Python SDK (via migration)
- [Docker Sandboxes for Claude Code](https://www.docker.com/blog/docker-sandboxes-run-claude-code-and-other-coding-agents-unsupervised-but-safely/) – Docker security
- [Claude Code MCP Configuration](https://code.claude.com/docs/en/mcp) – `.mcp.json` format
