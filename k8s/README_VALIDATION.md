# K8s Deployment Validation

## Problém

`kubectl apply` neprovádí **odstranění** polí z deploymentu, která už nejsou v YAML souboru. Například:

- Pokud z YAML odstraníte `hostNetwork: true`, pole stále zůstane v K8s deploymentu
- Pokud z YAML odstraníte `hostPort: 11430`, pole stále zůstane v container ports

To může způsobit problémy:
- Port konflikty při rolling update (když `hostPort` nebo `hostNetwork` drží port)
- Neočekávané chování (deployment má jinou konfiguraci než YAML)

## Řešení

Všechny build scripty používají sdílenou validační funkci `validate_deployment.sh`, která:

1. **Po `kubectl apply`** kontroluje deployment spec
2. **Automaticky odstraní** problematická pole pomocí `kubectl patch`
3. **Loguje varování**, pokud se patch musel použít

### Kontrolované pole:

- `hostNetwork: true` (mělo by být false nebo absent)
- `hostPort` v container ports (nemělo by být přítomno)

## Použití

### V build scriptech

```bash
#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

# ... build steps ...

# Apply YAML
kubectl apply -f "$SCRIPT_DIR/app_myservice.yaml" -n jervis

# Validate (removes stale fields if needed)
validate_deployment_spec "jervis-myservice" "jervis"

# Continue with rollout
kubectl set image deployment/jervis-myservice ...
```

### Scriptu s validací

- ✅ `build_server.sh`
- ✅ `build_orchestrator.sh`
- ✅ `build_ollama_router.sh`
- ✅ `build_kb.sh` (read + write deployments)
- ✅ `build_service.sh` (helper pro Atlassian, GitHub, GitLab, Tika)
- ✅ `build_all.sh` (validuje společné zdroje před deploym entem)

### Validace společných zdrojů

`build_all.sh` také kontroluje:
- `jervis-secrets` (musí existovat)
- `regcred` (pro private registry)

## Příklad výstupu

```
Validating deployment spec for jervis-ollama-router...
✓ Deployment spec validated
```

Pokud je potřeba patch:
```
Validating deployment spec for jervis-ollama-router...
✗ WARNING: hostPort=11430 found in container[0].ports[0]
  Removing: kubectl patch deployment/jervis-ollama-router -n jervis ...
✓ Deployment spec validated
```

## Rozšíření

Pro přidání nových kontrol, editujte `validate_deployment_spec()` funkci v `validate_deployment.sh`.
