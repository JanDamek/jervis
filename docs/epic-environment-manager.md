# Epic: Environment Manager — kompletní K8s prostředí řízené chatem

**Priority**: HIGH
**Area**: Backend + UI + Agent (orchestrátor, chat, Claude CLI)

## Vize

Prostředí = K8s namespace přiřazený klientovi/projektu/skupině. Obsahuje infrastrukturu
(DB, cache, MQ) i samotnou aplikaci klienta (build z git repo). Celé prostředí se staví,
upravuje a ovládá primárně přes **chat** — uživatel popisuje jak aplikace funguje, co
potřebuje, a agent si vše připraví.

## Klíčové principy

1. **Chat-first** — většinu (ne-li vše) nastavuje chat/agent, UI je pro přehled a manuální zásahy
2. **Jervis DB = zdroj pravdy** — YAML, deployment popisy, konfigurace se ukládají do MongoDB.
   Při restartu K8s se celý namespace znovu vytvoří z DB
3. **kubectl přímý přístup** — agent komunikuje s K8s hlavně přes kubectl (ne jen Kotlin API).
   Jervis/agent/orchestrátor/Claude CLI běží na K8s kde jsou i environments
4. **Flexibilní vazba** — prostředí může patřit 1 projektu, N projektům téhož klienta, nebo skupině
5. **Kompletní pipeline** — od git clone → Dockerfile → build image → deploy do namespace

---

## Fáze 1: Datový model a šablony

### 1.1 Šablony komponent (templates)
Předpřipravené šablony pro nejčastější infrastrukturu:
- PostgreSQL (verze: 17, 16, 15, 14)
- MongoDB (7, 6, 5)
- Redis (7, 6)
- RabbitMQ (3-management)
- Kafka (7.6, 7.5)
- Elasticsearch (8.12, 8.11, 7.17)
- MySQL (8.0, 5.7)
- MinIO (latest)
- Oracle (23-slim)

Každá šablona: image verze, výchozí porty, ENV proměnné, volume mount path, health probe.

### 1.2 Datový model prostředí (MongoDB)
```
EnvironmentDocument:
  id, name, namespace
  clientId, projectId?, groupId?    # flexibilní vazba
  state: STOPPED | PROVISIONING | RUNNING | ERROR
  storageSizeGi: Int
  components: List<EnvironmentComponent>
  yamlManifests: Map<String, String>  # uložené YAML pro recreate

EnvironmentComponent:
  name, type (INFRASTRUCTURE | APPLICATION)
  image, ports, envVars, volumeMountPath
  sourceRepo?: String               # git repo URL pro APPLICATION typ
  sourceBranch?: String
  dockerfilePath?: String            # cesta k Dockerfile v repu
  deploymentYaml?: String            # uložený deployment manifest
  serviceYaml?: String               # uložený service manifest
  configMapData?: Map<String, String>
  state: PENDING | DEPLOYING | RUNNING | ERROR | STOPPED
```

### 1.3 RPC endpointy
- `getComponentTemplates()` — seznam šablon pro UI dropdown
- `listEnvironments()`, `getEnvironment(id)`, `createEnvironment()`, `updateEnvironment()`
- `deleteEnvironment(id)` — smaže namespace + vše v K8s
- `syncEnvironmentResources(id)` — recreate z DB do K8s

---

## Fáze 2: K8s orchestrace

### 2.1 RBAC pro agenta
Agent (Claude CLI / orchestrátor) potřebuje v K8s:
- Plný přístup do environment namespaces (pods, deployments, services, configmaps, secrets, PVC, jobs)
- `kubectl exec` pro debugging
- `kubectl logs` pro diagnostiku
- Build image (kaniko job nebo docker-in-docker)

ServiceAccount s ClusterRole pro environment namespaces:
```yaml
rules:
- apiGroups: ["", "apps", "batch"]
  resources: ["*"]
  verbs: ["*"]
```

### 2.2 kubectl přístup z agenta
Každý pod který potřebuje ovládat K8s musí mít:
- ServiceAccount s příslušnými RBAC právy
- Kubeconfig přes Secret/ConfigMap nebo in-cluster config
- `kubectl` binárku v Docker image

Alternativa: **MCP server pro K8s** — Claude CLI umí MCP, pokud existuje K8s MCP
server, agent ho může použít místo přímého kubectl.

### 2.3 Provisioning flow
1. Vytvořit namespace
2. Vytvořit PVC (sdílené úložiště)
3. Pro každou infra komponentu: ConfigMap + Deployment + Service + health probes
4. Pro každou aplikační komponentu: git clone → build → deploy
5. Uložit manifesty do DB
6. Aktualizovat stav

### 2.4 Recreate z DB
Při restartu K8s nebo `syncEnvironmentResources`:
- Přečíst uložené YAML manifesty z MongoDB
- `kubectl apply` každý manifest
- Ověřit health

---

## Fáze 3: Aplikační komponenty (build pipeline)

### 3.1 Git → Image → Deploy
Chat/agent musí umět celý pipeline:
1. **Clone** repo z připojení (GitLab/GitHub) — přes přihlášení z ConnectionDocument
2. **Detekovat/vytvořit Dockerfile** — pokud existuje použít, jinak agent vytvoří
3. **Build image** — Kaniko job v K8s (nepotřebuje Docker daemon)
4. **Push do registru** — lokální registr nebo Harbor
5. **Deploy** — Deployment + Service v namespace prostředí

### 3.2 Vazba na zdrojový kód
Komponenta typu APPLICATION má:
- `sourceRepo` — URL git repozitáře (z připojení)
- `sourceBranch` — branch k deployi
- `dockerfilePath` — cesta v repu
- Agent toto nastavuje přes chat konverzaci

---

## Fáze 4: Chat/Agent integrace

### 4.1 Nové chat tools pro prostředí
```
environment_list          — seznam prostředí
environment_get           — detail prostředí s komponentami
environment_create        — vytvořit nové prostředí
environment_add_component — přidat komponentu (z šablony nebo custom)
environment_configure     — upravit ENV, porty, image, resources
environment_deploy        — provisionovat/aktualizovat v K8s
environment_logs          — kubectl logs pro komponentu
environment_exec          — kubectl exec pro debugging
environment_build         — build aplikační komponenty z git
environment_status        — stav všech podů v namespace
```

### 4.2 Typický chat flow
```
Uživatel: "Potřebuji prostředí pro Spring Boot aplikaci s PostgreSQL a Redis"
Agent:
  1. environment_create(name="myapp-dev", client=X)
  2. environment_add_component(template=POSTGRESQL, version="16-alpine")
  3. environment_add_component(template=REDIS, version="7-alpine")
  4. environment_add_component(type=APPLICATION, sourceRepo="gitlab.com/...", branch="main")
  5. environment_deploy()

Uživatel: "Přidej RabbitMQ a nastav SPRING_PROFILES_ACTIVE=dev"
Agent:
  1. environment_add_component(template=RABBITMQ)
  2. environment_configure(component="myapp", env={"SPRING_PROFILES_ACTIVE": "dev"})
  3. environment_deploy()

Uživatel: "RUN"
Agent: spustí celé prostředí, build aplikace z gitu, deploy vše do K8s
```

### 4.3 Claude CLI + MCP
Coding agent (Claude CLI) bude mít:
- Přístup ke kubectl přes terminál
- Případně K8s MCP server pro strukturovaný přístup
- Schopnost editovat Dockerfile, docker-compose, K8s manifesty

---

## Fáze 5: UI — Environment Manager

### 5.1 Přehled
- Seznam prostředí se stavem (Running/Stopped/Error)
- Filtr dle klienta/projektu
- Quick actions: Start, Stop, Delete

### 5.2 Detail prostředí
- **Overview tab**: název, namespace, stav, vazba na klienta/projekt
- **Components tab**: seznam komponent se stavem, logy, restart
  - Infra: ikona typu, verze, porty, ENV
  - Aplikace: git repo, branch, poslední build, logy
- **Configuration tab**: deployment YAML, service YAML, configmap, secrets
- **Terminal tab**: kubectl exec do podu (pokud možné v UI)

### 5.3 Odebrat z Nastavení
Záložka "Prostředí" v SettingsScreen je zbytečná — odebrat. Vše přes Environment Manager.

---

## Technické požadavky

- **Jervis DB jako SSOT** — vše se ukládá, K8s se recreatne z DB
- **Agent má plný kubectl přístup** — RBAC, ServiceAccount, kubeconfig
- **Build v K8s** — Kaniko (ne Docker daemon)
- **Chat tools** — kompletní CRUD + deploy + logs + exec
- **Šablony** — předpřipravené pro běžnou infru
- **Bez záložky Prostředí v Nastavení** — vše v Environment Manageru

## Soubory (existující základ)

- `shared/common-dto/.../environment/EnvironmentDtos.kt`
- `shared/common-api/.../service/IEnvironmentService.kt`
- `backend/server/.../entity/EnvironmentDocument.kt`
- `backend/server/.../service/environment/EnvironmentK8sService.kt`
- `backend/server/.../service/environment/ComponentDefaults.kt`
- `backend/server/.../rpc/EnvironmentRpcImpl.kt`
- `shared/ui-common/.../environment/` (EnvironmentManager UI)
- `k8s/orchestrator-rbac.yaml`
