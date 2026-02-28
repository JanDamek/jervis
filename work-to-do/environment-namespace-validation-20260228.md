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

## Požadavek

1. **Validace při uložení/provisioningu** — zkontrolovat zda namespace:
   - Už existuje v K8s a NENÍ spravován Jervisem (label `managed-by: jervis` chybí) → **ZAKÁZAT**
   - Je v seznamu systémových/rezervovaných namespace → **ZAKÁZAT**
   - Už existuje a JE spravován Jervisem → OK (re-provisioning)
   - Neexistuje → OK (vytvoří se nový)

2. **Blacklist systémových namespace** (minimálně):
   - `default`, `kube-system`, `kube-public`, `kube-node-lease`
   - `jervis` (vlastní namespace Jervise)
   - Jakýkoli namespace bez labelu `managed-by: jervis`

3. **UI feedback** — při zadání zakázaného namespace ukázat chybu okamžitě v editaci, ne až při provisioningu

4. **API validace** — backend musí validovat i bez UI (MCP tools, orchestrator mohou taky vytvářet prostředí)

## Soubory

- `backend/server/.../service/environment/EnvironmentK8sService.kt` — přidat validaci před PATCH/CREATE namespace
- `backend/server/.../service/environment/EnvironmentService.kt` — validace při uložení
- `shared/ui-common/.../screens/environment/OverviewTab.kt` nebo `ComponentEditPanel.kt` — UI validace namespace pole
- `backend/server/.../rpc/internal/InternalEnvironmentRouting.kt` — API validace

## Ověření

1. Zadat `nufo` (existující namespace) → chyba "Namespace already exists and is not managed by Jervis"
2. Zadat `kube-system` → chyba "Reserved namespace"
3. Zadat `jervis` → chyba "Reserved namespace"
4. Zadat nový unikátní název → OK, provisioning projde
5. Re-provisioning existujícího Jervis namespace → OK
