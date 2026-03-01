# Environment — validace namespace před provisioningem

**Priorita**: MEDIUM
**Status**: OPEN

---

## Problém

Uživatel může nastavit namespace prostředí na název, který už v K8s existuje a kam jervis-server serviceaccount nemá oprávnění (např. `nufo`). Při provisioningu pak dostane 403 Forbidden:

```
namespaces "nufo" is forbidden: User "system:serviceaccount:jervis:jervis-server"
cannot patch resource "namespaces" in API group "" in the namespace "nufo"
```

Jervis se pokusí PATCH existující namespace, ale nemá RBAC práva na cizí namespace.

## Stav po investigaci (2026-03-01)

### Validace při mazání — OK

`EnvironmentK8sService.deleteNamespace()` (řádky 362-374) správně kontroluje label:
```kotlin
val ns = client.namespaces().withName(namespace).get()
if (ns?.metadata?.labels?.get("managed-by") != "jervis-server") {
    throw IllegalStateException("Namespace $namespace is not managed by Jervis...")
}
```

### Validace při vytváření — CHYBÍ

`EnvironmentK8sService.createNamespace()` (řádky 348-360) volá `serverSideApply()` bez kontroly:
```kotlin
private fun createNamespace(namespace: String) {
    buildK8sClient().use { client ->
        val ns = NamespaceBuilder()
            .withNewMetadata()
                .withName(namespace)
                .addToLabels("app", "jervis")
                .addToLabels("managed-by", "jervis-server")
            .endMetadata()
            .build()
        client.namespaces().resource(ns).serverSideApply()  // ⚠️ Žádná kontrola
    }
}
```

`ensureNamespaceExists()` (řádky 338-346) auto-vytváří bez ověření ownership.

## Požadavek

1. **Validace při vytváření** — před `serverSideApply()` zkontrolovat:
   - Namespace existuje a NENÍ `managed-by: jervis-server` → **ZAKÁZAT** (throw)
   - Namespace existuje a JE `managed-by: jervis-server` → OK (re-provisioning)
   - Namespace neexistuje → OK (vytvořit)

2. **Blacklist systémových namespace** (minimálně):
   - `default`, `kube-system`, `kube-public`, `kube-node-lease`
   - `jervis` (vlastní namespace Jervise)

3. **UI feedback** — při zadání zakázaného namespace ukázat chybu okamžitě v editaci

4. **API validace** — backend musí validovat i bez UI (MCP tools, orchestrator)

## Řešení

```kotlin
private fun createNamespace(namespace: String) {
    val reserved = setOf("default", "kube-system", "kube-public", "kube-node-lease", "jervis")
    require(namespace !in reserved) { "Namespace '$namespace' is reserved" }

    buildK8sClient().use { client ->
        val existing = client.namespaces().withName(namespace).get()
        if (existing != null && existing.metadata?.labels?.get("managed-by") != "jervis-server") {
            throw IllegalStateException("Namespace $namespace exists but is not managed by Jervis")
        }
        // ... serverSideApply()
    }
}
```

## Soubory

- `backend/server/.../service/environment/EnvironmentK8sService.kt` — createNamespace() + ensureNamespaceExists()
- `backend/server/.../service/environment/EnvironmentService.kt` — validace při uložení
- `shared/ui-common/.../screens/environment/OverviewTab.kt` — UI validace namespace pole
- `backend/server/.../rpc/internal/InternalEnvironmentRouting.kt` — API validace

## Ověření

1. Zadat `nufo` (existující namespace) → chyba "Namespace already exists and is not managed by Jervis"
2. Zadat `kube-system` → chyba "Reserved namespace"
3. Zadat `jervis` → chyba "Reserved namespace"
4. Zadat nový unikátní název → OK, provisioning projde
5. Re-provisioning existujícího Jervis namespace → OK
