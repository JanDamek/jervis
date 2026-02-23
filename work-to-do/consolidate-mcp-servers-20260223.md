# Refactor: Sjednotit 3 MCP servery do jednoho

**Priority**: MEDIUM
**Date**: 2026-02-23

## Problém

Existují 3 MCP servery s duplikovanou funkcionalitou:

| Server | Typ | Kde běží | KB tools | Env tools | Ostatní |
|--------|-----|----------|----------|-----------|---------|
| `service-mcp` | HTTP (FastMCP) | Standalone K8s pod | 5 tools | **NE** | 10 (clients, tasks, meetings, mongo...) |
| `service-kb-mcp` | stdio | Uvnitř service-claude + service-junie | 7 tools | **NE** | - |
| `service-environment-mcp` | stdio | Uvnitř service-claude | **NE** | 6 tools | - |

- KB tools jsou **duplikované** — service-kb-mcp má dokonce víc (graph_search, evidence, resolve_alias)
- Environment tools **chybí** v service-mcp
- Model musí načíst všechny MCP tools najednou — separace nemá benefit

## Cíl

Jeden `service-mcp` se vším. Coding agenti se připojí přes HTTP místo stdio.

## Plán

### 1. Přidat chybějící tools do `service-mcp`

**KB tools** — doplnit z service-kb-mcp:
- `kb_get_evidence` — v service-mcp chybí
- `kb_resolve_alias` — v service-mcp chybí
- `kb_graph_search` existuje v obou, ověřit že service-mcp verze je kompletní

**Environment tools** — přidat z service-environment-mcp:
- `list_namespace_resources(namespace, resource_type)`
- `get_pod_logs(namespace, pod_name, tail_lines)`
- `get_deployment_status(namespace, name)`
- `scale_deployment(namespace, name, replicas)`
- `restart_deployment(namespace, name)`
- `get_namespace_status(namespace)`

Rozdíl: stdio verze bere namespace z env var, HTTP verze ho dostane jako parametr tool callu.

Environment tools volají `/internal/environment/{ns}/...` REST endpointy na Kotlin serveru
(`KtorRpcServer.kt:348-490`). Service-mcp k nim má přístup přes interní K8s DNS
(`http://jervis-server:5500`).

### 2. Workspace manager — přepojit agenty na HTTP MCP

`backend/service-orchestrator/app/agents/workspace_manager.py` — místo stdio konfigurace:
```python
# STARÉ (stdio)
mcp_config = {
    "mcpServers": {
        "jervis-kb": {"command": "python", "args": ["/opt/jervis/mcp/kb-server.py"]},
        "jervis-environment": {"command": "python", "args": ["/opt/jervis/mcp/environment-server.py"]},
    }
}

# NOVÉ (HTTP)
mcp_config = {
    "mcpServers": {
        "jervis": {
            "type": "http",
            "url": "http://jervis-mcp:8080/mcp",
            "headers": {"Authorization": "Bearer <token>"}
        }
    }
}
```

Token: buď sdílený secret z K8s, nebo nový interní token pro agenty.

### 3. Dockerfile cleanup

Odebrat COPY příkazy z Dockerfilů:

**`backend/service-claude/Dockerfile`** — smazat:
```dockerfile
COPY backend/service-kb-mcp/requirements.txt /opt/jervis/mcp/kb-requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/kb-requirements.txt
COPY backend/service-kb-mcp/server.py /opt/jervis/mcp/kb-server.py
COPY backend/service-environment-mcp/requirements.txt /opt/jervis/mcp/env-requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/env-requirements.txt
COPY backend/service-environment-mcp/server.py /opt/jervis/mcp/environment-server.py
```

**`backend/service-junie/Dockerfile`** — smazat:
```dockerfile
COPY backend/service-kb-mcp/requirements.txt /opt/jervis/mcp/kb-requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/kb-requirements.txt
COPY backend/service-kb-mcp/server.py /opt/jervis/mcp/kb-server.py
```

→ Menší Docker images, méně pip install při buildu.

### 4. Smazat obsoletní adresáře

- `backend/service-kb-mcp/` — smazat celý
- `backend/service-environment-mcp/` — smazat celý

### 5. Nasadit

1. Deploy `service-mcp` s novými tools (`k8s/build_mcp.sh`)
2. Rebuild `service-claude` + `service-junie` (bez MCP COPY)
3. Deploy orchestrátor s novým workspace_manager

## Dotčené soubory

| Soubor | Změna |
|--------|-------|
| `backend/service-mcp/app/main.py` | Přidat environment + chybějící KB tools |
| `backend/service-orchestrator/app/agents/workspace_manager.py` | stdio → HTTP MCP |
| `backend/service-claude/Dockerfile` | Odebrat COPY service-kb-mcp + service-environment-mcp |
| `backend/service-junie/Dockerfile` | Odebrat COPY service-kb-mcp |
| `backend/service-kb-mcp/` | SMAZAT |
| `backend/service-environment-mcp/` | SMAZAT |

## Poznámky

- Coding agent v K8s dosáhne na `jervis-mcp` přes cluster DNS (interní service)
- Tenant izolace: dnes neexistuje ani u stdio verzí (KB search nemá client filtr)
- Environment tools potřebují namespace jako parametr místo env var — čistší API
- Jeden MCP server = jedna sada tools = model je načte jednou
