# A2A Agent Card Fix - Summary

## Problém
Orchestrator nemohl získat agent card z A2A služeb, protože agent card byl dostupný na špatné URL.

**Chybová hláška:**
```
Failed to connect A2A client for Aider at http://jervis-aider:3100/a2a
Client request(GET http://jervis-aider:3100/.well-known/agent-card.json) invalid: 404 Not Found
```

## Kořenová příčina
Podle Koog dokumentace (https://docs.koog.ai/a2a-koog-integration/):

1. **Agent card MUSÍ být dostupný na:**
   `http://service/.well-known/agent-card.json` (v rootu serveru)

2. **A2A endpoint může být na:**
   `http://service/a2a` (libovolná cesta)

3. **`UrlAgentCardResolver` VŽDY hledá agent card na:**
   `baseUrl + /.well-known/agent-card.json` (ignoruje `path` parameter)

**Původní problém:**
- Servery měly agent card path: `/a2a/.well-known/agent-card.json` ❌
- To znamenalo agent card byl na: `http://jervis-aider:3100/a2a/.well-known/agent-card.json` ❌
- Ale `UrlAgentCardResolver` ho hledal na: `http://jervis-aider:3100/.well-known/agent-card.json` ✅

## Řešení

### 1. Oprava A2A Serverů (3x služby)

**Soubory:**
- `backend/service-aider/src/main/kotlin/com/jervis/aider/a2a/AiderA2AServer.kt`
- `backend/service-junie/src/main/kotlin/com/jervis/junie/a2a/JunieA2AServer.kt`
- `backend/service-coding-engine/src/main/kotlin/com/jervis/coding/a2a/CodingEngineA2AServer.kt`

**Změna (všechny 3 soubory):**
```kotlin
// PŘED:
transport.start(
    engineFactory = Netty,
    port = port,
    path = a2aPath,  // "/a2a"
    wait = true,
    agentCard = agentCard,
    agentCardPath = "$a2aPath/.well-known/agent-card.json",  // ❌ "/a2a/.well-known/agent-card.json"
)

// PO:
transport.start(
    engineFactory = Netty,
    port = port,
    path = a2aPath,  // "/a2a"
    wait = true,
    agentCard = agentCard,
    agentCardPath = "/.well-known/agent-card.json",  // ✅ "/.well-known/agent-card.json"
)
```

**Výsledek:**
- Agent card je nyní dostupný na: `http://jervis-aider:3100/.well-known/agent-card.json` ✅
- A2A endpoint zůstává na: `http://jervis-aider:3100/a2a` ✅

### 2. Oprava Orchestrator A2A Klienta

**Soubor:**
- `backend/server/src/main/kotlin/com/jervis/orchestrator/OrchestratorAgent.kt`

**Změna (řádky 619-624):**
```kotlin
// PŘED:
val agentCardResolver = UrlAgentCardResolver(baseUrl = baseUrl, path = path)  // path = "/a2a"

// PO:
val transport = HttpJSONRPCClientTransport(baseUrl + path, httpClient)  // "http://jervis-aider:3100/a2a"
val agentCardResolver = UrlAgentCardResolver(baseUrl = baseUrl, path = "")  // ✅ path = ""
return A2AClient(transport = transport, agentCardResolver = agentCardResolver)
```

**Vysvětlení:**
- `HttpJSONRPCClientTransport` dostává plnou URL včetně path: `http://jervis-aider:3100/a2a`
- `UrlAgentCardResolver` dostává pouze base URL: `http://jervis-aider:3100`
- Resolver automaticky přidá `/.well-known/agent-card.json`
- Výsledná URL pro agent card: `http://jervis-aider:3100/.well-known/agent-card.json` ✅

## Deployment Status

### ✅ Dokončeno:
1. **Server (orchestrator)** - nasazeno před ~40 minutami
2. **Aider** - nasazeno úspěšně (pod: `jervis-aider-857649675f-jcf7v`)
3. **Junie** - nasazeno úspěšně (pod: `jervis-junie-7fbcb8b6d4-rt4lq`)
4. **Coding Engine** - nasazeno úspěšně (pod: `jervis-coding-engine-68765bdf6d-nt7j7`)

### Verifikace:
Pro ověření, že agent card endpointy fungují:
```bash
# Test agent card endpoints
kubectl exec -n jervis deploy/jervis-aider -- curl -s http://localhost:3100/.well-known/agent-card.json
kubectl exec -n jervis deploy/jervis-junie -- curl -s http://localhost:3300/.well-known/agent-card.json
kubectl exec -n jervis deploy/jervis-coding-engine -- curl -s http://localhost:3200/.well-known/agent-card.json

# Sleduj server logy pro A2A connection attempts
kubectl logs -n jervis -l app=jervis-server --tail=100 | grep -E "A2A|agent-card"
```

## Co dál?
1. **Počkat na restart serveru** - server byl nasazen jako první, A2A služby až o ~40 minut později
2. **Otestovat A2A spojení** - odeslat request přes aplikaci, který aktivuje A2A delegaci
3. **Zkontrolovat logy** - ověřit, že agent card se úspěšně stáhl a A2A klient se připojil

## Architektura (po opravě)

```
Orchestrator (jervis-server)
  │
  ├─> A2A Client pro Aider
  │   ├─> Transport: http://jervis-aider:3100/a2a (JSON-RPC endpoint)
  │   └─> Agent Card Resolver: http://jervis-aider:3100/.well-known/agent-card.json
  │
  ├─> A2A Client pro Junie
  │   ├─> Transport: http://jervis-junie:3300/a2a
  │   └─> Agent Card Resolver: http://jervis-junie:3300/.well-known/agent-card.json
  │
  └─> A2A Client pro Coding Engine
      ├─> Transport: http://jervis-coding-engine:3200/a2a
      └─> Agent Card Resolver: http://jervis-coding-engine:3200/.well-known/agent-card.json
```

## Důležité poznatky

1. **Koog `UrlAgentCardResolver` VŽDY hledá agent card v rootu** (`baseUrl/.well-known/agent-card.json`)
2. **`path` parameter v `UrlAgentCardResolver` se NEPOUŽÍVÁ pro agent card URL**
3. **A2A endpoint a agent card endpoint jsou RŮZNÉ cesty**:
   - A2A endpoint: `/a2a` (pro JSON-RPC komunikaci)
   - Agent card: `/.well-known/agent-card.json` (pro metadata agenta)
4. **Agent card MUSÍ být dostupný před připojením A2A klienta**

## Reference
- Koog A2A Documentation: https://docs.koog.ai/a2a-koog-integration/
