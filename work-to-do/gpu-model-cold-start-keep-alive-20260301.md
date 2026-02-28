# GPU — model cold start po neaktivitě (keep_alive + warmup ping)

**Priorita**: HIGH
**Status**: OPEN

---

## Problém

Po delší neaktivitě (30+ minut bez GPU požadavku) se 30b model vyhodí z VRAM, přestože router nastavuje `keep_alive: "-1"`. První chat request po neaktivitě pak trvá **284 sekund** (model se načítá z disku zpět do VRAM) místo běžných 7-8 sekund.

### Naměřeno (2026-02-28)

| GPU | Cold start (první request) | Warm (následující) |
|-----|---------------------------|-------------------|
| p40-2 (`ollama.damek.local`) | **284s** | 8s |
| p40-1 (`nas.damek.local`) | **42s** | 7s |

Timeline:
- 22:22:51 — předchozí chat timeout (300s), request zrušen
- 22:22–22:55 — **33 minut ticho**, žádný GPU požadavek
- 22:55:49 — nový chat → p40-2 → **284s** (cold start, model se načítá z disku)
- 23:00:40 — iterace 2 → p40-2 → **8s** (model warm)

### Aktuální stav konfigurace

Router **již nastavuje** `keep_alive: "-1"` (v ConfigMap i v MODEL_SETS):
```yaml
DEFAULT_KEEP_ALIVE: "-1"     # configmap.yaml
```
```python
MODEL_SETS = {
    "primary": {
        "models": ["qwen3-coder-tool:30b", "qwen3-embedding:8b"],
        "keep_alive": "-1",   # models.py
    },
}
```

Přesto se model po neaktivitě uvolnil z VRAM. Možné příčiny:
1. Ollama host ignoruje `keep_alive` z API a používá vlastní default (5 min)
2. Ollama host se restartoval (neřízený proces na bare metal)
3. Model byl vyhozen kvůli VRAM pressure (30b + 8b = ~23.5 GB na 24 GB kartě)
4. `keep_alive: "-1"` se posílá jen v proxy requestech, ale ne při initial load

---

## Řešení 1: Ověřit a fixnout keep_alive na Ollama hostu

### 1.1 Zkontrolovat Ollama server konfiguraci

Na obou Ollama hostech (`nas.damek.local`, `ollama.damek.local`) ověřit:
```bash
# Systemd environment nebo /etc/ollama/config
OLLAMA_KEEP_ALIVE=-1
```

Ollama server má vlastní `OLLAMA_KEEP_ALIVE` environment variable, která **přepisuje** API parametr pokud je nastavena. Pokud chybí, default je `5m`.

### 1.2 Nastavit na obou hostech

```bash
# /etc/systemd/system/ollama.service nebo ekvivalent
Environment="OLLAMA_KEEP_ALIVE=-1"
```

Restartovat Ollama service na obou hostech.

### 1.3 Ověřit po nastavení

```bash
# Na každém hostu
curl http://localhost:11434/api/ps
# → model musí zůstat loaded i po 30+ min neaktivity
```

---

## Řešení 2: Warmup ping v routeru

I pokud keep_alive funguje, přidat **periodický warmup ping** jako pojistku — malý request který udrží model "teplý" v VRAM.

### 2.1 Nová metoda v `router_core.py`

```python
async def _warmup_loop(self):
    """Periodicky pingnout oba GPU backendy aby model zůstal v VRAM."""
    while True:
        await asyncio.sleep(WARMUP_INTERVAL_S)  # default: 4 min (pod Ollama 5min keep_alive)
        for gpu in self.gpu_backends:
            if gpu.healthy:
                try:
                    await self._send_warmup_ping(gpu)
                except Exception as e:
                    logger.warning("Warmup ping failed for %s: %s", gpu.name, e)
```

### 2.2 Warmup ping = minimální generate request

```python
async def _send_warmup_ping(self, gpu: GpuBackend):
    """Pošle prázdný generate request s keep_alive aby model zůstal loaded."""
    payload = {
        "model": self.config.orchestrator_model,  # qwen3-coder-tool:30b
        "prompt": "",
        "keep_alive": -1,
        "stream": False,
    }
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(f"{gpu.url}/api/generate", json=payload)
        if resp.status_code == 200:
            logger.debug("Warmup ping OK: %s model=%s", gpu.name, self.config.orchestrator_model)
```

### 2.3 Spustit loop při startu

V `RouterCore.__init__()` nebo startup:
```python
asyncio.create_task(self._warmup_loop())
```

### 2.4 Konfigurace

Nový env var v ConfigMap:
```yaml
WARMUP_INTERVAL_S: "240"   # 4 minuty (bezpečně pod 5min Ollama default)
WARMUP_ENABLED: "true"
```

### 2.5 Inteligentní warmup — přeskočit pokud GPU aktivní

Warmup ping je zbytečný pokud GPU právě zpracovává requesty. Přidat kontrolu:
```python
async def _warmup_loop(self):
    while True:
        await asyncio.sleep(WARMUP_INTERVAL_S)
        for gpu in self.gpu_backends:
            if gpu.healthy and gpu.active_requests == 0:
                last_activity = time.monotonic() - gpu.last_request_time
                if last_activity > WARMUP_INTERVAL_S * 0.8:  # ~3.2 min
                    await self._send_warmup_ping(gpu)
```

---

## Řešení 3: Warmup obou modelů (30b + 8b embedding)

Warmup ping by měl udržet oba modely z primary setu:
```python
for model_name in self.model_sets["primary"]["models"]:
    await self._send_warmup_ping(gpu, model_name)
```

Pro embedding model použít `/api/embeddings` místo `/api/generate`:
```python
if "embedding" in model_name:
    payload = {"model": model_name, "prompt": "warmup", "keep_alive": -1}
    await client.post(f"{gpu.url}/api/embeddings", json=payload)
```

---

## Soubory

- **Ollama hosty** — `/etc/systemd/system/ollama.service` (nebo ekvivalent) na `nas.damek.local` a `ollama.damek.local`
- `backend/service-ollama-router/app/router_core.py` — warmup loop
- `backend/service-ollama-router/app/config.py` — nové config parametry
- `backend/service-ollama-router/app/models.py` — MODEL_SETS reference
- `k8s/configmap.yaml` — `WARMUP_INTERVAL_S`, `WARMUP_ENABLED`

## Priorita implementace

1. **Nejdřív** ověřit `OLLAMA_KEEP_ALIVE=-1` na obou hostech (5 minut práce, může vyřešit celý problém)
2. **Pak** warmup ping v routeru jako pojistka (i kdyby keep_alive fungovalo, warmup zajistí detekci problémů)

## Ověření

1. Nastavit `OLLAMA_KEEP_ALIVE=-1` na hostech → po 30 min neaktivity model stále v VRAM (`/api/ps`)
2. Warmup ping → v router logách vidím `Warmup ping OK` každé 4 minuty na obou GPU
3. Chat po 30+ min neaktivity → první iterace < 15s (ne 284s)
4. `WARMUP_ENABLED=false` → ping se nespouští (konfigurovatelné)
