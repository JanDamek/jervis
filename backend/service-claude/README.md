# Claude CLI Agent — ClaudeBox-style Setup

> **TOP agent** pro kritické coding úkoly (CRITICAL complexity)
> **Nejlepší cena/výkon** — preferovaný před Junie

---

## Model Selection

**Claude CLI si vybírá model automaticky** podle auth metody — NENÍ třeba specifikovat:
- OAuth token → Max/Pro subscription → nejlepší dostupné modely
- API key → pay-per-token → nejlepší dostupné modely

`default-model` v `application.yml` je jen pro dokumentaci — Claude CLI ho **ignoruje**.

---

## Autentizace

Claude CLI podporuje **dva způsoby autentizace**:

### 1. **OAuth Token (doporučeno pro Max/Pro účet)**

```bash
# Na lokálním Macu vygeneruj token
npm install -g @anthropic-ai/claude-code
claude setup-token
```

Token ulož do K8s secret:
```bash
kubectl create secret generic jervis-secrets \
  --from-literal=CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-... \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 2. **API Key (pay-per-token)**

Alternativa pro projekty bez Max/Pro:
```bash
kubectl create secret generic jervis-secrets \
  --from-literal=ANTHROPIC_API_KEY=sk-ant-api03-... \
  --dry-run=client -o yaml | kubectl apply -f -
```

---

## Docker Build

```bash
# Build ClaudeBox-style image
docker build \
  --platform linux/amd64 \
  -t registry.damek-soft.eu/jandamek/jervis-claude:latest \
  -f backend/service-claude/Dockerfile.claudebox \
  backend/service-claude/

# Push to registry
docker push registry.damek-soft.eu/jandamek/jervis-claude:latest
```

---

## K8s Job Deployment

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: jervis-claude-{task-id}
  namespace: jervis
spec:
  ttlSecondsAfterFinished: 3600  # Clean up after 1h
  template:
    spec:
      restartPolicy: Never
      containers:
      - name: claude
        image: registry.damek-soft.eu/jandamek/jervis-claude:latest
        command: ["/bin/bash", "/entrypoint-job.sh"]
        env:
        - name: TASK_ID
          value: "{task-id}"
        - name: WORKSPACE_PATH
          value: "/workspace/clients/{client-id}/projects/{project-id}"
        - name: MODE
          value: "coding"  # nebo "mcp" pro MCP server mode
        - name: CLAUDE_CODE_OAUTH_TOKEN
          valueFrom:
            secretKeyRef:
              name: jervis-secrets
              key: CLAUDE_CODE_OAUTH_TOKEN
              optional: true
        - name: ANTHROPIC_API_KEY
          valueFrom:
            secretKeyRef:
              name: jervis-secrets
              key: ANTHROPIC_API_KEY
              optional: true
        volumeMounts:
        - name: workspace
          mountPath: /workspace
        - name: entrypoint
          mountPath: /entrypoint-job.sh
          subPath: entrypoint-job.sh
      volumes:
      - name: workspace
        persistentVolumeClaim:
          claimName: jervis-workspace-pvc
      - name: entrypoint
        configMap:
          name: claude-entrypoint
          defaultMode: 0755
```

---

## Workspace Structure (připravuje workspace_manager)

```
/workspace/clients/{client-id}/projects/{project-id}/
├── .jervis/
│   ├── instructions.txt       # Úkol od orchestrátoru
│   ├── kb_context.md         # Pre-fetched KB context
│   └── environment.json      # Resolved environment context
├── CLAUDE.md                 # Project-specific instructions
└── ... (git repository)
```

---

## Agent Selection Logic

```python
# backend/service-orchestrator/app/graph/nodes/_helpers.py
def select_agent(complexity: Complexity, preference: str = "auto") -> AgentType:
    match complexity:
        case Complexity.SIMPLE:     return AgentType.AIDER       # Lokální, rychlé
        case Complexity.MEDIUM:     return AgentType.OPENHANDS   # Lokální, levné
        case Complexity.COMPLEX:    return AgentType.OPENHANDS   # Lokální, větší
        case Complexity.CRITICAL:   return AgentType.CLAUDE      # ← TOP agent!
```

**Claude je používán pouze pro CRITICAL úkoly** — nejlepší cena/výkon.

**Junie** je premium alternative, ale **horší než Claude** — používá se jen když projekt explicitně požaduje:
```python
agent_preference = "junie"  # Explicit override
```

---

## ClaudeBox Inspiration

Setup inspirovaný https://github.com/RchGrav/claudebox:
- ✅ Izolované Docker prostředí pro každý task
- ✅ OAuth token support (Max/Pro subscription)
- ✅ Automatic cleanup (K8s ttlSecondsAfterFinished)
- ✅ Workspace mount z PVC
- ✅ `--dangerously-skip-permissions` pro autonomní běh

---

## Testing Locally

```bash
# Test s OAuth tokenem
export CLAUDE_CODE_OAUTH_TOKEN="sk-ant-oat01-..."

docker run --rm -it \
  -v $(pwd):/workspace/test \
  -e TASK_ID=test-123 \
  -e WORKSPACE_PATH=/workspace/test \
  -e CLAUDE_CODE_OAUTH_TOKEN \
  registry.damek-soft.eu/jandamek/jervis-claude:latest

# Test s API klíčem
export ANTHROPIC_API_KEY="sk-ant-api03-..."

docker run --rm -it \
  -v $(pwd):/workspace/test \
  -e TASK_ID=test-456 \
  -e WORKSPACE_PATH=/workspace/test \
  -e ANTHROPIC_API_KEY \
  registry.damek-soft.eu/jandamek/jervis-claude:latest
```

---

## UI Settings

**Coding Agents Settings** (`CodingAgentsSettings.kt`):
- **Max/Pro subscription** — setup token (`claude setup-token`)
- **Pay-per-token** — API klíč z https://console.anthropic.com/settings/keys

UI zobrazuje:
- ✅ "Max/Pro ucet" — když je `CLAUDE_CODE_OAUTH_TOKEN` nastaven
- ✅ "API klic" — když je `ANTHROPIC_API_KEY` nastaven
- ❌ "Nenastaveno" — chybí obě credentials
