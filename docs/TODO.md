# TODO – Plánované Features a Vylepšení

Tento dokument obsahuje seznam plánovaných features, vylepšení a refaktoringů,
které budou implementovány jako separate tickety.

---

## Environment Management (Phase 2 + 3)

### Kontext – 3 vrstvy interakce s prostředím

Práce s K8s prostředími je rozdělena do tří vrstev s jasně oddělenou zodpovědností:

| Vrstva | Kde v UI | Účel | Operace |
|--------|----------|------|---------|
| **Náhled** (✅ Done) | Hlavní okno – postranní panel | Read-only monitoring | Strom prostředí, stav komponent, repliky, ready/error, logy |
| **Správa** (Phase 2) | Navigace → Environment Manager | Plná konfigurace a ovládání | CRUD komponent, images, ENV, configmapy, porty, nasazení, logy, scaling |
| **Vytvoření** (Phase 3) | Nastavení → Prostředí | Založení prostředí a přiřazení | Vytvořit prostředí, přiřadit ke klientovi/projektu, základní metadata |

**Hlavní okno (panel)** = jen **náhled** — vidět hodnoty, stav, logy. Žádná editace.
**Nastavení → Prostředí** = jen **vytvořit** prostředí, přiřadit ke klientovi, možná název a namespace. Detailní konfigurace se dělá v Environment Manageru.
**Environment Manager** = hlavní místo pro **veškerou konfiguraci** prostředí.

### Phase 2: Environment Manager (Standalone Screen)

**Prerequisite:** Phase 1 (backend + MCP) ✅ DONE — fabric8 K8s methods, EnvironmentResourceService, internal REST endpoints, MCP server, workspace manager integration, RBAC.

**Cíl:** Plnohodnotná správa K8s prostředí — od definice komponent, přes nastavení images a ENV proměnných, až po nasazení a monitoring. Včetně podpory aplikací bez vlastního Docker image.

#### A. UI – Environment Manager Screen

Nová navigační položka v hlavním menu (`Screen.EnvironmentManager`). Layout: `JListDetailLayout` — vlevo seznam prostředí, vpravo detail vybraného.

**Levý panel (seznam):**
- Všechna prostředí pro aktuálního klienta
- Stav badge (Running/Stopped/Error)
- Tlačítko "Nové prostředí"

**Pravý panel (detail) — záložky:**

1. **Přehled** — název, namespace, stav, přiřazení (klient, projekt/skupina), akce (Start, Stop, Restart, Smazat)
2. **Komponenty** — CRUD seznam komponent prostředí:
   - Každá komponenta: typ (Deployment/StatefulSet/Job/CronJob), název, image, repliky
   - Tlačítka: Přidat, Editovat, Odstranit
   - Detail komponenty (editace):
     - Image source (registry URL, tag, nebo "bez image" → base Linux)
     - Porty (containerPort, servicePort, protocol)
     - ENV proměnné (key-value, nebo odkaz na ConfigMap/Secret)
     - Volume mounts (PVC, emptyDir, hostPath)
     - Resource limits (CPU, memory)
     - Health checks (liveness, readiness)
     - Startup command override
3. **ConfigMap & Secrets** — správa ConfigMap a Secret objektů pro namespace:
   - CRUD pro ConfigMap: název → klíč-hodnota páry
   - CRUD pro Secret: název → klíč-hodnota (maskovné)
   - Možnost importovat ze souboru (.env, YAML)
   - Propojení: které komponenty odkazují na který ConfigMap/Secret
4. **Síť** — Service/Ingress konfigurace:
   - Service typ (ClusterIP, NodePort, LoadBalancer)
   - Ingress pravidla (hostname, path, TLS)
5. **Logy & Events** — real-time pod logy + K8s events:
   - Log viewer s tail -f (SSE stream)
   - K8s events pro namespace (Warning/Normal)
   - Filtrování podle komponenty/podu
6. **YAML** — raw YAML/JSON viewer pro advanced uživatele:
   - Read-only zobrazení generovaných K8s manifestů
   - Možnost "export" manifestů

#### B. Podpora aplikací bez Docker image

**Problém:** Ne každá aplikace má Dockerfile nebo Docker image. Přesto ji potřebujeme v K8s prostředí spustit.

**Řešení: Base Linux Container**
- Při definici komponenty lze vybrat "Bez vlastního image" → systém použije base Linux image (např. `ubuntu:22.04` nebo vlastní `jervis-base:latest`)
- Do base containeru se:
  1. **Namountují zdrojové kódy** přes PVC nebo init container (git clone)
  2. **Nainstalují závislosti** podle detekovaného stacku (npm install, pip install, mvn install, ...)
  3. **Spustí aplikace** podle konfigurace (startup command)
- Konfigurace "bez image" komponenty:
  - Git repo URL + branch (odkud vzít kód)
  - Working directory (path v kontejneru)
  - Build příkaz (optional: `npm run build`, `mvn package`, ...)
  - Run příkaz (`node server.js`, `python app.py`, `java -jar app.jar`, ...)
  - Runtime (Node.js, Python, Java, Go, .NET — detekce nebo manuální výběr)
  - Závislosti (automatická detekce z package.json, requirements.txt, pom.xml)

**Implementace:**
- **Init container pattern**: Init container provede git clone + build, main container spustí aplikaci
- **Base images per runtime**: `jervis-base-node:20`, `jervis-base-python:3.12`, `jervis-base-java:21`, atd.
- Nebo univerzální `jervis-base:latest` s multi-runtime (node, python, java, go pre-installed)
- Build step běží v init containeru, runtime step v main containeru (sdílený volume)

**Flow:**
```
User v UI definuje komponentu:
  → typ: "Source Code" (ne Docker image)
  → git: https://github.com/user/app.git, branch: main
  → runtime: Node.js (auto-detected from package.json)
  → run: npm start
  → env: PORT=3000, DB_URL=...

Jervis vygeneruje K8s manifest:
  → initContainer: git clone + npm install
  → container: jervis-base-node:20, command: npm start
  → volume: shared emptyDir pro kód
  → service: ClusterIP, port 3000
```

#### C. Backend rozšíření

**EnvironmentService rozšíření:**
- `createComponent(envId, componentDto)` — CRUD operace pro komponenty
- `updateComponent(envId, componentId, componentDto)`
- `deleteComponent(envId, componentId)`
- `getConfigMaps(envId)` — list ConfigMap
- `setConfigMap(envId, name, data: Map<String,String>)`
- `deleteConfigMap(envId, name)`
- `getSecrets(envId)` — list Secret (values masked)
- `setSecret(envId, name, data: Map<String,String>)`
- `deleteSecret(envId, name)`
- `deployEnvironment(envId)` — apply all manifests to K8s
- `stopEnvironment(envId)` — scale all to 0 / delete resources
- `getEvents(envId, since)` — K8s events for namespace
- `streamPodLogs(envId, podName, tailLines)` — SSE log stream

**EnvironmentComponentDto rozšíření:**
```kotlin
data class EnvironmentComponentDto(
    val id: String,
    val name: String,
    val type: ComponentType,          // DEPLOYMENT, STATEFUL_SET, JOB, CRON_JOB
    // Image source
    val imageSource: ImageSource,     // REGISTRY nebo SOURCE_CODE
    val image: String?,               // registry image (pokud REGISTRY)
    val gitRepoUrl: String?,          // git repo (pokud SOURCE_CODE)
    val gitBranch: String?,
    val runtime: RuntimeType?,        // NODE, PYTHON, JAVA, GO, DOTNET (pokud SOURCE_CODE)
    val buildCommand: String?,        // npm install, pip install, mvn package
    val runCommand: String?,          // npm start, python app.py
    val workDir: String?,             // working directory v containeru
    // Resources
    val replicas: Int = 1,
    val ports: List<PortMapping>,
    val envVars: List<EnvVar>,        // inline key-value
    val configMapRefs: List<String>,  // ConfigMap names → injected as ENV
    val secretRefs: List<String>,     // Secret names → injected as ENV
    val volumeMounts: List<VolumeMount>,
    val cpuLimit: String?,            // "500m", "1"
    val memoryLimit: String?,         // "512Mi", "2Gi"
    val startupCommand: String?,      // override entrypoint
    val healthCheckPath: String?,     // HTTP path pro liveness/readiness
    val healthCheckPort: Int?,
)
```

**Manifest Generator:**
- `K8sManifestGenerator` — z EnvironmentDto + komponent vygeneruje K8s YAML manifesty
- Pro SOURCE_CODE: generuje initContainer spec s git clone + build
- Pro REGISTRY: standardní container spec s image
- Generuje Service, Ingress, ConfigMap, Secret, PVC podle konfigurace

#### D. Navigace

- Přidat `Screen.EnvironmentManager` do `AppNavigator`
- Přidat navigační položku do hlavního menu (vedle Settings, Agent Workload, atd.)
- Přidat ikonu do menu: `Icons.Default.Dns` (nebo `Cloud`)

#### E. Existující kód k využití

- `IEnvironmentService` — RPC interface pro prostředí (list, create, delete, status)
- `IEnvironmentResourceService` — RPC interface pro K8s resources (pods, deployments, logs, scale)
- `EnvironmentResourceService` — backend fabric8 K8s operace
- `EnvironmentViewerScreen.kt` — existující viewer screen (základ pro rozšíření)
- `EnvironmentPanel.kt` — read-only panel v hlavním okně (zůstává jako náhled)
- `EnvironmentTreeComponents.kt` — composables pro strom komponent (znovupoužití)

#### F. Implementační kroky

1. **Backend DTOs** — rozšířit `EnvironmentComponentDto` o image source, ENV, configmap refs, volume mounts, resource limits
2. **Backend RPC** — nové metody na `IEnvironmentService` (CRUD komponenty, configmapy, secrety, deploy, stop)
3. **Backend K8s** — `K8sManifestGenerator` pro generování manifestů z DTO
4. **Backend K8s** — `EnvironmentResourceService` rozšíření pro configmap/secret CRUD, log streaming (SSE)
5. **Base images** — vytvořit `jervis-base-*` Docker images pro jednotlivé runtime
6. **UI Screen** — `EnvironmentManagerScreen` s `JListDetailLayout` a záložkami
7. **UI Formuláře** — editace komponent (image/source, ENV, porty, resource limits)
8. **UI ConfigMap/Secret** — CRUD formuláře pro key-value data
9. **UI Log viewer** — real-time log tailing (SSE stream → Flow)
10. **UI YAML viewer** — read-only manifest viewer
11. **Navigace** — přidat `Screen.EnvironmentManager`, menu položku

### Phase 3: Environments v Nastavení (zjednodušené)

V záložce Nastavení → Prostředí zůstane jen:
- Vytvořit nové prostředí (název, namespace)
- Přiřadit prostředí ke klientovi
- Smazat prostředí
- Odkaz "Otevřít v Environment Manageru" pro detailní konfiguraci

Detailní konfigurace se neprovádí v Nastavení — uživatel je přesměrován do Environment Manageru.

**Priorita:** High
**Complexity:** High
**Status:** Planned (Phase 2 + 3)

