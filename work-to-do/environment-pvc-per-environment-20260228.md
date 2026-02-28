# Environment — PVC musí být per prostředí, ne sdílený

**Priorita**: HIGH
**Status**: DONE

---

## Problém

Všechna prostředí používají stejný PVC název `jervis-env-data`. Data různých prostředí se míchají na jednom volume. Pokud dva environment mají komponentu se stejným mount path, data se přepíší.

## Požadavek

1. **PVC per environment** — každé prostředí musí mít vlastní PVC s unikátním názvem
   - Pattern: `jervis-env-{environmentId}` nebo `jervis-env-{namespace}`
   - PVC se vytvoří při provisioningu v namespace prostředí
   - PVC se smaže při smazání prostředí (nebo nabídnout volbu "zachovat data")

2. **Coding agent přístup přes K8s** — coding agent a orchestrator se k datům prostředí dostanou přes:
   - Environment API → zjistí namespace + PVC name
   - K8s API → mount PVC do coding agent podu / jobu
   - NIKDY přímý přístup na host path

3. **Provisioning** — při vytvoření prostředí:
   - Vytvořit namespace (pokud neexistuje)
   - Vytvořit PVC `jervis-env-{id}` v daném namespace
   - Uložit skutečný PVC name do environment dokumentu

4. **Coding agent job** — při dispatchování coding tasku:
   - Načíst environment → zjistit namespace + PVC name
   - Job spec: mount PVC jako volume do coding agent kontejneru
   - Working directory = mount path z PVC

## Soubory

- `backend/server/.../service/environment/EnvironmentK8sService.kt` — provisioning: vytvořit unikátní PVC
- `backend/server/.../service/environment/EnvironmentService.kt` — uložit PVC name do entity
- `shared/common-dto/.../environment/EnvironmentDtos.kt` — ověřit že pvcName je per environment (ne hardcoded default)
- `backend/server/.../service/environment/ComponentDefaults.kt` — pokud generuje default PVC name, musí být unikátní
- `backend/service-orchestrator/app/tools/definitions.py` — coding agent dispatch musí předat environment info
- `shared/ui-common/.../screens/settings/sections/EnvironmentDialogs.kt` — NewEnvironmentDialog: generovat unikátní PVC name

## Ověření

1. Vytvořit 2 prostředí → každé má vlastní PVC s unikátním názvem
2. `kubectl get pvc -n {namespace}` → vidím `jervis-env-{id}` (ne `jervis-env-data`)
3. Coding agent job mountne správný PVC pro dané prostředí
4. Smazání prostředí smaže i PVC (nebo nabídne volbu)
