# Feature: Dokončení Environment Manageru

**Priority**: HIGH
**Date**: 2026-02-23
**Scope**: 3 fáze, implementovat jako celek

## Současný stav

Environment Manager je z velké části implementovaný:
- CRUD prostředí (MongoDB entity, RPC, UI)
- K8s provisioning: namespace + Deployment + ClusterIP Service per infra komponenta
- UI: 4 taby (Přehled, Komponenty, K8s zdroje, Logy), JListDetailLayout
- Side panel s tree view, EnvironmentViewModel s pollingem
- fabric8 K8s klient (io.fabric8:kubernetes-client:7.1.0)
- RBAC ClusterRole pro namespaces, deployments, services, pods

**Chybí:**
- Šablony komponent s výběrem verzí (aktuálně 1 hardcoded image per typ)
- PVC (žádné perzistentní úložiště pro komponenty)
- ConfigMap per komponenta (envVars jsou inline v Deploymentu)
- Health probes na deploymentech
- Sync mechanismus (změna nastavení → propagace do K8s)
- Navigace z panelu prostředí do Environment Manageru

---

## Fáze 1: Šablony komponent s výběrem verzí

### 1.1 Nové DTOs — `shared/common-dto/.../environment/EnvironmentDtos.kt`

```kotlin
@Serializable
data class ComponentVersionDto(
    val label: String,      // "PostgreSQL 16 (Alpine)"
    val image: String,      // "postgres:16-alpine"
)

@Serializable
data class ComponentTemplateDto(
    val type: ComponentTypeEnum,
    val versions: List<ComponentVersionDto>,
    val defaultEnvVars: Map<String, String> = emptyMap(),
    val defaultPorts: List<PortMappingDto> = emptyList(),
    val defaultVolumeMountPath: String? = null,
)
```

Do `EnvironmentComponentDto` přidat:
```kotlin
val volumeMountPath: String? = null  // "/var/lib/postgresql/data"
```

Do `EnvironmentDto` přidat:
```kotlin
val storageSizeGi: Int = 5  // PVC velikost v Gi
```

### 1.2 Entity — `backend/server/.../entity/EnvironmentDocument.kt`

- `EnvironmentComponent` + `volumeMountPath: String? = null`
- `EnvironmentDocument` + `storageSizeGi: Int = 5`

### 1.3 ComponentDefaults.kt — rozšířit na multi-version

Aktuálně: `ComponentDefaults.kt` má flat mapu s 1 image per typ.

Nová struktura:
```kotlin
data class ComponentVersion(val label: String, val image: String)

data class ComponentDefault(
    val versions: List<ComponentVersion>,  // seřazeno od nejnovější
    val ports: List<PortMapping>,
    val defaultEnvVars: Map<String, String> = emptyMap(),
    val defaultVolumeMountPath: String? = null,
)
```

Verze pro každý typ:

| Typ | Verze | defaultVolumeMountPath |
|-----|-------|----------------------|
| POSTGRESQL | 17-alpine, 16-alpine, 15-alpine, 14-alpine | /var/lib/postgresql/data |
| MONGODB | 7, 6, 5 | /data/db |
| REDIS | 7-alpine, 6-alpine | /data |
| RABBITMQ | 3-management-alpine | /var/lib/rabbitmq |
| KAFKA | 7.6.0, 7.5.0 | /var/lib/kafka/data |
| ELASTICSEARCH | 8.12.0, 8.11.0, 7.17.0 | /usr/share/elasticsearch/data |
| MYSQL | 8.0, 5.7 | /var/lib/mysql |
| MINIO | latest | /data |
| ORACLE | 23-slim | /opt/oracle/oradata |

### 1.4 Nové RPC — `IEnvironmentService`

```kotlin
suspend fun getComponentTemplates(): List<ComponentTemplateDto>
```

Implementace v `EnvironmentRpcImpl` — čistý read z `COMPONENT_DEFAULTS`.

### 1.5 Mapper — `EnvironmentMapper.kt`

Přidat `volumeMountPath` a `storageSizeGi` do obou směrů mappingu.

### 1.6 UI — `AddComponentDialog` (EnvironmentDialogs.kt)

Přepracovat dialog:
- LaunchedEffect loaduje `getComponentTemplates()`
- Po výběru typu → dropdown **Verze** z šablony
- Preview Docker image (monospace)
- Toggle "Vlastní image" pro ruční override
- Porty a ENV se předvyplní ze šablony

### 1.7 UI — `ComponentEditPanel.kt`

- Přijímá `templates: List<ComponentTemplateDto>` jako parametr
- Dropdown verze vedle typu (pokud image odpovídá šabloně → předvybrat)
- Read-only `volumeMountPath` řádek pokud je definován

### 1.8 UI — `ComponentsTab.kt`

- Loaduje templates jednou a předává do `ComponentEditPanel`

### Dotčené soubory fáze 1:
| Soubor | Změna |
|--------|-------|
| `shared/common-dto/.../environment/EnvironmentDtos.kt` | Nové DTOs + pole |
| `shared/common-api/.../service/IEnvironmentService.kt` | Nová metoda getComponentTemplates |
| `backend/server/.../entity/EnvironmentDocument.kt` | Nová pole |
| `backend/server/.../service/environment/ComponentDefaults.kt` | Multi-version |
| `backend/server/.../rpc/EnvironmentRpcImpl.kt` | Implementace getComponentTemplates |
| `backend/server/.../mapper/EnvironmentMapper.kt` | Nová pole |
| `shared/ui-common/.../settings/sections/EnvironmentDialogs.kt` | Version picker |
| `shared/ui-common/.../environment/ComponentEditPanel.kt` | Version picker |
| `shared/ui-common/.../environment/ComponentsTab.kt` | Templates loading |

---

## Fáze 2: Navigace Panel → Environment Manager

### 2.1 `EnvironmentPanel.kt` — nový parametr

```kotlin
onOpenInManager: (String) -> Unit = {},
```

V `JTopBar.actions` přidat `JIconButton(Settings)` pro obecnou navigaci do Manageru.

### 2.2 `EnvironmentTreeComponents.kt` — manage tlačítko per environment

V `EnvironmentTreeNode` header row přidat `JIconButton(Icons.Default.Settings, 44dp)`:
```kotlin
onManage: ((String) -> Unit)? = null  // receives env.id
```

Klik → navigace přímo do detailu konkrétního prostředí v EnvironmentManageru.

### 2.3 `screens/MainScreen.kt` — propojit callback

Nový parametr `onOpenInManager: (String) -> Unit = {}`, předat do `EnvironmentPanel`.

### 2.4 `App.kt` — wire navigaci

V `MainScreen(...)`:
```kotlin
onOpenInManager = { envId ->
    appNavigator.navigateTo(Screen.EnvironmentManager(initialEnvironmentId = envId))
}
```

`EnvironmentManagerScreen` již podporuje `initialEnvironmentId` — deep-link funguje.

### Dotčené soubory fáze 2:
| Soubor | Změna |
|--------|-------|
| `shared/ui-common/.../environment/EnvironmentPanel.kt` | Nový callback + Settings ikona |
| `shared/ui-common/.../environment/EnvironmentTreeComponents.kt` | Settings ikona per environment |
| `shared/ui-common/.../screens/MainScreen.kt` | Propojení callbacku |
| `shared/ui-common/.../App.kt` | Wire na navigator |

---

## Fáze 3: Plná K8s orchestrace

### 3.1 RBAC — `k8s/orchestrator-rbac.yaml`

Přidat do `jervis-server-environment-role` ClusterRole:
```yaml
- apiGroups: [""]
  resources: ["configmaps"]
  verbs: ["get", "list", "create", "update", "delete", "patch"]
- apiGroups: [""]
  resources: ["persistentvolumeclaims"]
  verbs: ["get", "list", "create", "delete"]
```

**NUTNO aplikovat PŘED nasazením nového serveru!**

### 3.2 PVC strategie

- **Jeden PVC na prostředí** (sdílený všemi komponentami)
- Název: `jervis-env-data` (v namespace prostředí)
- Velikost: `environment.storageSizeGi` (default 5Gi)
- AccessMode: `ReadWriteOnce`
- Každá komponenta s `volumeMountPath` dostane VolumeMount se `subPath: <component-name>`
  - PostgreSQL → subPath `postgres`, MongoDB → subPath `mongodb`, atd.
  - Zabrání kolizím mezi komponentami na jednom PVC

### 3.3 ConfigMap strategie

- **Jeden ConfigMap na komponentu** — název: `<component-name>-config`
- Všechny `component.envVars` → ConfigMap data (místo inline env v Deploymentu)
- Deployment odkazuje přes `envFrom.configMapRef`
- `serverSideApply()` = idempotentní update

### 3.4 Health probes

Přidat do `ComponentDefaults`:
```kotlin
data class HealthProbeConfig(
    val type: ProbeType,  // HTTP, TCP
    val path: String? = null,
    val port: Int,
    val initialDelaySeconds: Int = 30,
    val periodSeconds: Int = 10,
)
```

Výchozí probes:
| Typ | Probe |
|-----|-------|
| POSTGRESQL | TCP :5432, delay 20s |
| MONGODB | TCP :27017, delay 15s |
| REDIS | TCP :6379, delay 5s |
| RABBITMQ | HTTP /api/healthchecks/node :15672, delay 30s |
| ELASTICSEARCH | HTTP /_cluster/health :9200, delay 30s |
| MYSQL | TCP :3306, delay 20s |
| MINIO | HTTP /minio/health/ready :9000, delay 15s |

Pokud uživatel nastaví `component.healthCheckPath` → override jako HTTP probe na prvním portu.

### 3.5 `EnvironmentK8sService.kt` — nové operace

**Nové private metody:**
- `createOrUpdatePvc(namespace, sizeGi)` — fabric8 `PersistentVolumeClaimBuilder`
- `createOrUpdateConfigMap(namespace, componentName, envVars)` — fabric8 `ConfigMapBuilder`
- `buildProbe(config)` — fabric8 `ProbeBuilder` (TCP/HTTP)

**Rozšířit `deployComponent()`:**
- Nové parametry: `pvcName`, `volumeMountPath`, `probeConfig`
- Container: `envFrom { configMapRef(name = "${name}-config") }` (místo inline env)
- Container: `volumeMount(pvcName, volumeMountPath, subPath = name)` pokud existuje
- Container: `livenessProbe` + `readinessProbe` z probeConfig
- Pod spec: `volumes { persistentVolumeClaim(pvcName) }` pokud existuje

**Nový `provisionEnvironment()` flow:**
1. createNamespace *(existující)*
2. createOrUpdatePvc *(nové)*
3. Pro každou infra komponentu:
   a. createOrUpdateConfigMap
   b. deployComponent s PVC + probe
4. Resolve property mappings *(existující)*
5. updateState RUNNING *(existující)*

**Nová metoda `syncEnvironmentResources()`:**
- Pro RUNNING prostředí: aktualizuje ConfigMaps + re-apply deploymentů
- `serverSideApply()` = idempotentní, bezpečné volat opakovaně
- Nevolá createNamespace/PVC znovu

**Rozšířit `deleteComponent()`:**
- Smazat i ConfigMap: `client.configMaps().inNamespace(ns).withName("$name-config").delete()`

**Rozšířit `deprovisionEnvironment()`:**
- Smazat ConfigMaps pro každou komponentu
- Pokud `deleteNamespace=true` → smazat i PVC

### 3.6 Nové RPC — `IEnvironmentService`

```kotlin
suspend fun syncEnvironmentResources(id: String): EnvironmentDto
```

### 3.7 UI — `OverviewTab.kt`

Nová sekce "Úložiště":
```
JSection("Úložiště") {
    JKeyValueRow("Strategie", "Jeden PVC na prostředí")
    JKeyValueRow("Velikost", "${storageSizeGi} Gi")
    JKeyValueRow("Název PVC", "jervis-env-data")
}
```

Nové tlačítko "Synchronizovat" pro RUNNING stav → volá `syncEnvironmentResources`.

### Dotčené soubory fáze 3:
| Soubor | Změna |
|--------|-------|
| `k8s/orchestrator-rbac.yaml` | RBAC: configmaps + PVC |
| `backend/server/.../service/environment/EnvironmentK8sService.kt` | PVC, ConfigMap, probes, sync |
| `backend/server/.../service/environment/ComponentDefaults.kt` | HealthProbeConfig |
| `backend/server/.../rpc/EnvironmentRpcImpl.kt` | syncEnvironmentResources |
| `shared/common-api/.../service/IEnvironmentService.kt` | syncEnvironmentResources |
| `shared/ui-common/.../environment/OverviewTab.kt` | Úložiště sekce + Sync tlačítko |

---

## Závislosti

```
Fáze 1 (šablony + verze)  ←──┐
   ↓                          │
Fáze 3 (K8s orchestrace)     │  potřebuje volumeMountPath z Fáze 1
                              │
Fáze 2 (navigace)  ──────────┘  nezávislá, může paralelně s Fází 1
```

## Ověření

- **Fáze 1**: AddComponentDialog → PostgreSQL → dropdown verzí → image preview
- **Fáze 2**: Side panel → Settings ikona u prostředí → EnvironmentManager s detailem
- **Fáze 3**: Provisionovat prostředí → ověřit v K8s:
  - `kubectl get ns` — namespace existuje
  - `kubectl get pvc -n <ns>` — `jervis-env-data` PVC existuje
  - `kubectl get cm -n <ns>` — ConfigMap per komponenta
  - `kubectl get deploy -n <ns>` — Deploymenty s health probes a volume mount
  - `kubectl describe deploy <name> -n <ns>` — readinessProbe, livenessProbe, volumeMounts

## Po implementaci

- Aktualizovat `docs/architecture.md` — sekce Environment Manager
- Aktualizovat `docs/ui-design.md` — Environment Manager UI patterns
- Smazat deprecated `EnvironmentViewerScreen` a route `Screen.EnvironmentViewer`
