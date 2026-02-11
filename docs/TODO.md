# TODO – Plánované Features a Vylepšení

Tento dokument obsahuje seznam plánovaných features, vylepšení a refaktoringů,
které budou implementovány jako separate tickety.

## Autoscaling & Performance

### KB Autoscaling při Read Timeout

**Problém:**
- KB může být přetížený při velkém množství současných ingest operací
- Orchestrator dostává read timeouts při čekání na embedding/retrieve
- 90% času KB stojí na Ollama při embeddingu (zápis) a generování vrcholů/hran (retrieve)
- CPU/RAM metriky nejsou indikátorem zátěže

**Řešení:**
- Orchestrator při read timeout může dynamicky zvýšit repliky KB deployment (kubectl scale)
- Default: 1 replika
- Max: 5 replik
- Scale-up trigger: read timeout při komunikaci s KB
- Scale-down: zatím bez automatiky (není dobrá metrika)

**Implementace:**
1. Orchestrator permissions: přidat RBAC práva pro `kubectl scale deployment/jervis-knowledgebase`
2. Python orchestrator: při timeout volat `kubectl scale --replicas=N`
3. Exponenciální scale-up: 1 → 2 → 3 → 5 (při opakovaných timeoutech)
4. Manual scale-down: admin musí ručně snížit, nebo timeout-based (pokud X minut žádný timeout, scale down o 1)

**Soubory:**
- `k8s/rbac.yaml` – rozšířit orchestrator ServiceAccount o scale permissions
- `backend/service-orchestrator/app/k8s/scaler.py` – KB scaling logic
- `backend/service-orchestrator/app/kb/client.py` – catch timeout, trigger scale-up

**Priorita:** Medium
**Complexity:** Simple
**Status:** Planned

---

## Další TODOs

_(Další features se budou přidávat sem podle potřeby)_
