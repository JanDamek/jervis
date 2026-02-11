# Verification Log – Coding Agents Configuration

**Datum:** 2026-02-11
**Účel:** Ověření konfigurace všech coding agentů před zahájením programování

---

## ✅ Orchestrátor — Agent Selection Logic

**Soubor:** `backend/service-orchestrator/app/graph/nodes/_helpers.py:192`

```python
def select_agent(complexity: Complexity, preference: str = "auto") -> AgentType:
    if preference != "auto":
        return AgentType(preference)  # Uživatel explicitně zvolil agenta

    match complexity:
        case Complexity.SIMPLE:     return AgentType.AIDER       # ✓ malé opravy, lokální
        case Complexity.MEDIUM:     return AgentType.OPENHANDS   # ✓ levné zpracování, lokální
        case Complexity.COMPLEX:    return AgentType.OPENHANDS   # ✓ větší analýzy, lokální
        case Complexity.CRITICAL:   return AgentType.CLAUDE      # ✓ TOP agent, nejlepší cena/výkon
```

### Použití v kódu

- **plan_steps** (`coding.py:198`) — volá `select_agent(goal.complexity, task.agent_preference)`
- **plan** (`plan.py:135`) — volá `select_agent(complexity, task.agent_preference)`
- **execute_step** (`execute.py:137,145,155`) — používá `step.agent_type.value`

---

## ✅ Application.yml — Coding Tools Properties

**Soubor:** `backend/server/src/main/resources/application.yml:150-173`

```yaml
coding-tools:
  aider:
    default-provider: ollama       # ✓ GPU instance (:11434)
    default-model: qwen3-coder-tool:30b
    paid-provider: anthropic
    paid-model: claude-3-5-sonnet-20241022

  openhands:
    default-provider: ollama
    default-model: qwen3-coder-tool:30b
    paid-provider: anthropic
    paid-model: claude-3-5-sonnet-20241022
    ollama-base-url: http://192.168.100.117:11434  # ✓ Direct Ollama URL

  junie:
    default-provider: anthropic    # ✓ Cloud only (no local default)
    default-model: claude-3-5-sonnet-20241022
    paid-provider: anthropic
    paid-model: claude-3-5-sonnet-20241022

  claude:                          # ✓ PŘIDÁNO (dříve chybělo)
    default-provider: anthropic    # ✓ Cloud only
    default-model: claude-3-5-sonnet-20241022
    paid-provider: anthropic
    paid-model: claude-3-5-sonnet-20241022
```

**Opraven problém:** Claude sekce dříve chyběla v application.yml, ale byla vyžadována v `CodingAgentSettingsRpcImpl.kt:37`.

---

## ✅ Kotlin Server — Coding Agent Settings RPC

**Soubor:** `backend/server/.../rpc/CodingAgentSettingsRpcImpl.kt`

### Agent Definitions

```kotlin
override suspend fun getSettings(): CodingAgentSettingsDto {
    val agents = listOf(
        CodingAgentConfigDto(
            name = "claude",
            displayName = "Claude (Anthropic)",
            provider = codingToolsProperties.claude.defaultProvider,  // ✓ nyní existuje
            model = codingToolsProperties.claude.defaultModel,
            apiKeySet = storedDocs["claude"]?.apiKey?.isNotBlank() == true ||
                        System.getenv("ANTHROPIC_API_KEY")?.isNotBlank() == true,
            setupTokenConfigured = storedDocs["claude"]?.setupToken?.isNotBlank() == true ||
                                   System.getenv("CLAUDE_CODE_OAUTH_TOKEN")?.isNotBlank() == true,
            consoleUrl = "https://console.anthropic.com/settings/keys",
            requiresApiKey = true,
            supportsSetupToken = true,  // ✓ Max/Pro subscription
        ),
        CodingAgentConfigDto(
            name = "junie",
            displayName = "Junie (JetBrains)",
            apiKeySet = storedDocs["junie"]?.apiKey?.isNotBlank() == true ||
                        System.getenv("JUNIE_API_KEY")?.isNotBlank() == true,
            consoleUrl = "https://account.jetbrains.com",
            requiresApiKey = true,  // ✓ JetBrains účet
        ),
        CodingAgentConfigDto(
            name = "aider",
            displayName = "Aider",
            provider = codingToolsProperties.aider.defaultProvider,
            model = codingToolsProperties.aider.defaultModel,
            requiresApiKey = false,  // ✓ Lokální Ollama
        ),
        CodingAgentConfigDto(
            name = "openhands",
            displayName = "OpenHands",
            provider = codingToolsProperties.openhands.defaultProvider,
            model = codingToolsProperties.openhands.defaultModel,
            requiresApiKey = false,  // ✓ Lokální Ollama
        ),
    )
}
```

---

## ✅ UI Settings — Coding Agents Screen

**Soubor:** `shared/ui-common/.../sections/CodingAgentsSettings.kt`

### Features

- **Claude:**
  - Max/Pro subscription — setup token (`claude setup-token`)
  - Pay-per-token — API klíč z https://console.anthropic.com/settings/keys
  - Zobrazuje status: "Max/Pro ucet" | "API klic" | "Nenastaveno"

- **Junie:**
  - API klíč z JetBrains účtu
  - Console URL: https://account.jetbrains.com

- **Aider/OpenHands:**
  - Žádné nastavení — používají lokální Ollama
  - Zobrazuje: "Pouziva lokalni Ollama - API klic neni potreba"

---

## ✅ Build Verification

```bash
./gradlew :backend:server:build -x test
```

**Výsledek:** ✅ BUILD SUCCESSFUL in 32s

---

## 📋 Summary

| Agent | Complexity | Provider | Model | API Key | Setup OK |
|-------|-----------|----------|-------|---------|----------|
| **Aider** | SIMPLE | Ollama (GPU) | qwen3-coder-tool:30b | ❌ Ne | ✅ Ano |
| **OpenHands** | MEDIUM, COMPLEX | Ollama (GPU) | qwen3-coder-tool:30b | ❌ Ne | ✅ Ano |
| **Claude** | CRITICAL | Anthropic | claude-3-5-sonnet-20241022 | ✅ Ano (nebo token) | ✅ Ano |
| **Junie** | Premium only | JetBrains | claude-3-5-sonnet-20241022 | ✅ Ano (JetBrains) | ✅ Ano |

---

## Use Case Mapping

| Úloha | Zvolený agent | Důvod |
|-------|--------------|-------|
| Malá drobná oprava | **Aider** | SIMPLE complexity, rychlé, lokální Ollama |
| Rychlé zjištění stavu v kódu | **Aider** | SIMPLE complexity, lokální |
| Standardní coding task | **OpenHands** | MEDIUM complexity, levné zpracování, lokální |
| Větší analýza codebase | **OpenHands** | COMPLEX complexity, lokální Ollama pro levné zpracování |
| Kritická změna architektury | **Claude** | CRITICAL complexity, **TOP agent**, nejlepší cena/výkon |
| Premium projekt | **Junie** | `agent_preference = "junie"`, explicitně povoleno v projektu |
| User explicitně zvolil agenta | **Podle preference** | `agent_preference != "auto"` |

---

## 📝 Coding Agent Poznámky

### Claude CLI
- **Model se nespecifikuje** — Claude CLI si vybírá automaticky podle auth metody
- OAuth token → Max/Pro subscription → top modely
- API key → pay-per-token → top modely
- `default-model` v application.yml je jen dokumentace — Claude CLI ho ignoruje

### Agent Strategy (opraveno 2026-02-11)

**Pokud `cloud_allowed = true` (ProjectRules.auto_use_anthropic):**
- **VŠE** → Claude (SIMPLE, MEDIUM, COMPLEX, CRITICAL)

**Pokud `cloud_allowed = false` (default):**
- **SIMPLE** → Aider (lokální Ollama)
- **MEDIUM** → OpenHands (lokální Ollama)
- **COMPLEX** → OpenHands (lokální Ollama)
- **CRITICAL** → Claude (TOP agent) — nejlepší cena/výkon

**Vždy:**
- **Junie** → pouze premium projekty s `agent_preference="junie"` (horší než Claude)

---

## ✅ Všechny kontroly prošly

1. ✅ Orchestrátor má správnou agent selection logiku
2. ✅ application.yml obsahuje všechny 4 agenty (claude sekce PŘIDÁNA)
3. ✅ CodingToolsProperties.kt má všechny 4 config třídy
4. ✅ CodingAgentSettingsRpcImpl.kt vrací všechny 4 agenty
5. ✅ UI má support pro setup token (Claude) a API klíče (Junie, Claude)
6. ✅ Aider a OpenHands používají lokální Ollama bez API klíčů
7. ✅ Build projde bez chyb

**Systém je připraven pro programování a analýzu kódu.**
