# Environment — K8s zdroje tab nefunguje (chybí namespace)

**Priorita**: HIGH
**Status**: OPEN

---

## Problém

Tab "K8s zdroje" v Správa prostředí nic nezobrazuje. Namespace prostředí neexistuje v K8s (nebylo provisionováno nebo provisioning selhal), takže dotaz na K8s API vrátí prázdný výsledek nebo 404.

## Požadavek

1. **Server musí vytvořit namespace při startu/uložení prostředí** — ne až při explicitním provisioningu
   - Při uložení nového environment → vytvořit namespace (pokud neexistuje)
   - Při startu serveru → ověřit že všechny aktivní environments mají namespace v K8s
   - Label: `managed-by: jervis`, `environment-id: {id}`

2. **K8s zdroje tab** — musí umět:
   - Zobrazit stav namespace (existuje / neexistuje)
   - Pokud namespace neexistuje → zobrazit jasnou zprávu "Namespace neexistuje, prostředí nebylo provisionováno" s tlačítkem "Vytvořit namespace"
   - Pokud existuje → vypsat pods, services, PVC, configmaps v namespace

3. **Graceful handling** — pokud K8s API není dostupné nebo nemá oprávnění, zobrazit chybovou hlášku (ne prázdná stránka)

## Soubory

- `backend/server/.../service/environment/EnvironmentK8sService.kt` — namespace lifecycle (create on save, verify on startup)
- `backend/server/.../service/environment/EnvironmentService.kt` — volat K8s service při uložení
- `shared/ui-common/.../screens/environment/K8sResourcesTab.kt` — zobrazit stav namespace, error handling
- `shared/common-api/.../IEnvironmentService.kt` — RPC metoda pro K8s resources

## Ověření

1. Vytvořit nové prostředí → namespace se automaticky vytvoří v K8s
2. K8s zdroje tab → zobrazí namespace stav + resources (i když prázdné)
3. Neexistující namespace → jasná zpráva + tlačítko pro vytvoření
4. K8s nedostupné → error state, ne prázdná stránka
