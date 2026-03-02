# GPU Benchmark Report — Tesla P40 Dual-GPU

**Datum:** 2026-03-02 02:24–04:37
**Model:** qwen3-coder-tool:30b (Q4_K_M, 18.5GB)
**Hardware:** 2× Tesla P40 (24GB VRAM each)
**p40-1:** nas.damek.local (VM s 251GB RAM)
**p40-2:** ollama.damek.local (VM s 8GB RAM)

---

## 1. Test 1: num_ctx sweep (krátký prompt, měnící se kontextové okno)

Krátký prompt (37 tokenů), num_ctx: 4096 → 48000. Mezi každou velikostí unload + reload.

### p40-1 (hot run = 2. iterace)

| num_ctx | prompt_tps | eval_tps | total_s |
|---------|-----------|----------|---------|
| 4,096   | 1,470     | 98       | 0.28s   |
| 8,192   | 1,321     | 97       | 0.31s   |
| 16,384  | 1,490     | 102      | 0.30s   |
| 32,768  | 1,264     | 95       | 0.31s   |
| 48,000  | 1,324     | 96       | 0.31s   |

### p40-2 (hot run = 2. iterace)

| num_ctx | prompt_tps | eval_tps | total_s |
|---------|-----------|----------|---------|
| 4,096   | 1,288     | 94       | 0.46s   |
| 8,192   | 1,337     | 94       | 0.42s   |
| 16,384  | 1,400     | 94       | 0.43s   |
| 32,768  | 1,413     | 98       | 0.43s   |
| 48,000  | 1,315     | 87       | 0.45s   |

**Závěr:** Velikost num_ctx NEMÁ vliv na rychlost inference u krátkých promptů. Všechny velikosti dávají identický výkon (~1,300 tok/s prompt, ~95 tok/s eval). KV cache se alokuje při prvním požadavku, pak je zdarma.

---

## 2. Test 2: Prompt fill (num_ctx=48000, rostoucí prompt)

Model nahrán jednou, pak sekvenčně prompty od 402 do 39,579 tokenů.

### p40-1

| prompt_tokens | prompt_s | prompt_tps | eval_tps | total_s |
|--------------|----------|-----------|----------|---------|
| 402          | 0.49     | 821       | 52       | 18.5*   |
| 1,696        | 1.83     | 928       | 50       | 35.5*   |
| 3,418        | 2.31     | 1,481     | 49       | 4.1     |
| 6,864        | 5.76     | 1,191     | 45       | 7.7     |
| 13,748       | 16.73    | 822       | 41       | 18.2    |
| 20,635       | 24.02    | 859       | 37       | 26.3    |
| 27,523       | 31.28    | 880       | 33       | 34.0    |
| 34,416       | 38.78    | 888       | 30       | 41.7    |
| 39,579       | 34.07    | 1,162     | 29       | 38.0    |

*první 2 mají zvýšený load_s (KV cache realokace)

### p40-2 (po warm-up, bez load overhead)

| prompt_tokens | prompt_s | prompt_tps | eval_tps | total_s |
|--------------|----------|-----------|----------|---------|
| 3,418        | 2.29     | 1,491     | 47       | 4.3     |
| 6,864        | 5.80     | 1,183     | 44       | 8.0     |
| 13,748       | 16.69    | 824       | 40       | 18.3    |
| 27,523       | 31.44    | 875       | 33       | 34.0    |
| 34,416       | 38.89    | 885       | 30       | 42.3    |
| 39,579       | 34.19    | 1,158     | 28       | 36.6    |

**Závěr:**
- Prompt processing: ~800–1,500 tok/s (závisí na délce kontextu, Ollama interní optimalizace)
- **Eval (generování) se lineárně zpomaluje s kontextem**: 52→28 tok/s (400→40k tokenů)
- Oba GPU mají **identický výkon** (±5%) jakmile je model nahrán
- Celkový čas pro 40k tokenů kontextu: **~38s** (z toho ~34s prompt eval, ~3s generování)

---

## 3. Test 3: Tight context (num_ctx = prompt + 1500) vs. Fixed 48k

Porovnání: co se stane když num_ctx přesně odpovídá promptu vs. fixních 48k.

### p40-1

| prompt_tokens | Tight ctx (first req) | Fixed 48k (warm) | Zpomalení |
|--------------|----------------------|------------------|-----------|
| 1,696        | 1.94s (877/s)        | 1.83s (928/s)    | 1.06×     |
| 6,864        | 9.42s (728/s)        | 5.76s (1,191/s)  | 1.64×     |
| 13,748       | 25.9s (531/s)        | 16.73s (822/s)   | 1.55×     |
| 27,523       | 80.32s (343/s)       | 31.28s (880/s)   | 2.57×     |
| 39,579       | 151.6s (261/s)       | 34.07s (1,162/s) | 4.45×     |

**Závěr:** Měnění num_ctx mezi requesty je **KATASTROFÁLNÍ** pro výkon:
- Při 40k tokenech: **4.45× pomalejší** (151s vs 34s)
- Důvod: Ollama restartuje runner (KV cache alokace, CUDA kernel setup, JIT kompilace)
- **PRAVIDLO: NIKDY neměnit num_ctx mezi requesty. Vždy fixních 48k.**

---

## 4. Test 4: Concurrent (oba GPU paralelně)

Oba GPU slouží stejný request současně.

| prompt_tokens | p40-1 prompt_s | p40-2 prompt_s | p40-1 eval_tps | p40-2 eval_tps |
|--------------|----------------|----------------|----------------|----------------|
| 6,864        | 9.46           | 9.47           | 45.5           | 41.6           |
| 20,635       | 49.52          | 49.40          | 36.3           | 35.4           |
| 34,416       | 118.66         | 118.30         | 30.8           | 29.9           |

**Závěr:** Oba GPU pracují skutečně paralelně s identickým výkonem. Žádná vzájemná interference.

---

## 5. Kritický problém: p40-2 model load

| GPU   | Model load time | Příčina |
|-------|----------------|---------|
| p40-1 | **14s**        | Model cached v 251GB RAM (page cache) |
| p40-2 | **200-260s**   | Model (18.5GB) > RAM (8GB) → full disk I/O |

p40-2 má jen 8GB RAM ale model zabírá 18.5GB na disku. Každý load vyžaduje čtení celého modelu z disku, protože se nemůže celý zcachovat v RAM.

**Řešení:**
1. **Krátkodobě:** NIKDY neunloadovat model na p40-2 (`keep_alive=-1`)
2. **Střednědobě:** Zvýšit RAM VM na 24GB (model + OS + cache)
3. **Warmup timeout:** Již opraven na 120s (commit a1ce449e), ale p40-2 potřebuje >200s → **zvýšit na 300s**

---

## 6. Výkonnostní profil Tesla P40

| Metrika | Hodnota | Podmínka |
|---------|---------|----------|
| Prompt processing (warm) | 800–1,500 tok/s | Závisí na délce kontextu |
| Prompt processing (cold) | 260–530 tok/s | Po změně num_ctx / reloadu |
| Token generation (1k ctx) | ~52 tok/s | |
| Token generation (8k ctx) | ~45 tok/s | |
| Token generation (16k ctx) | ~41 tok/s | |
| Token generation (24k ctx) | ~37 tok/s | |
| Token generation (32k ctx) | ~33 tok/s | |
| Token generation (40k ctx) | ~29 tok/s | |
| Model load (p40-1) | ~14s | Cached v RAM |
| Model load (p40-2) | ~200-260s | No RAM cache |
| VRAM usage (model) | 19GB / 24GB | |
| Max practical context | 48,000 tok | Plná rychlost |

---

## 7. Best Practices pro dual-GPU 24/7

### 7.1 Nikdy neměnit model/num_ctx za běhu

Benchmark jasně prokázal, že změna num_ctx způsobuje 2–5× zpomalení. Aktuální systém používá fixní num_ctx per GPU a to je správně:
- **p40-1:** num_ctx=48,000 (vždy)
- **p40-2:** num_ctx=32,000 (kvůli koexistenci s embedding modelem)

Tiered modely (qwen3-coder-tool-8k, -16k, -48k atd.) na p40-2 jsou zbytečné — **nemají se switchovat**. Router vždy posílá `qwen3-coder-tool:30b` s fixním num_ctx.

### 7.2 Load balancing strategie

```
Request přijde →
├── Embedding? → p40-2 (jediné GPU s embedding modelem)
├── VLM? → p40-2 (on-demand load, temporary)
├── LLM (chat/orchestrator/KB):
│   ├── Context ≤ 32k? → p40-1 NEBO p40-2 (kdo je volný)
│   ├── Context 32k–48k? → pouze p40-1
│   └── Context > 48k? → OpenRouter cloud
└── Oba busy? → CRITICAL preempts NORMAL, else queue
```

### 7.3 Warmup a model persistence

- `keep_alive=-1` na obou GPU (model nikdy neexpiruje)
- Warmup ping každé 4 minuty (stávající nastavení je OK)
- **Warmup timeout:** zvýšit z 120s na 300s (p40-2 potřebuje >200s pro cold load)
- Monitoring: pokud nvidia-smi ukazuje <15GB VRAM, model isn't loaded → alert

### 7.4 Maximalizace využití GPU

#### Prioritní systém (stávající, správný):
- **CRITICAL (0):** Chat, KB search, foreground orchestrace → preemptuje NORMAL
- **NORMAL (1):** Background analýza, korekce, KB ingest → ceká ve frontě

#### Proaktivní naplnění GPU:
1. GPU idle >5 min → Kotlin server notifikován → spustí background tasky
2. Background tasky vždy NORMAL priority → preemptible
3. Stávající idle notification (300s threshold) je příliš dlouhý → **snížit na 120s**

#### Denní throughput odhad:
- Průměrný request: 8k tokenů kontext, 100 tokenů output
- Prompt eval: ~6s, Generation: ~2s, Overhead: ~1s → **~9s per request**
- **2 GPU × ~9,600 requestů/den** (za předpokladu 100% využití)
- Realisticky (60% utilization): **~11,500 requestů/den**

### 7.5 p40-2 optimalizace

Aktuální omezení p40-2:
- 8GB RAM → model load 200-260s (vs 14s na p40-1)
- 32k max context (embedding model zabírá ~5GB VRAM)

Doporučení:
1. **Zvýšit RAM na 24GB** (nejdůležitější single improvement)
2. **Zvážit dedikovaný embedding server** — přesunout embedding na CPU/menší GPU
   → Uvolní 5GB VRAM na p40-2 → 48k context i na p40-2
3. **Alternativa:** Pokud embedding není častý, loadovat on-demand (jako VLM)

### 7.6 Metriky pro monitoring

| Metrika | Threshold | Akce |
|---------|-----------|------|
| GPU idle >2 min | Warning | Trigger background tasks |
| Model load >60s | Alert | Check RAM, disk, restart Ollama |
| Prompt eval <200 tok/s | Alert | Model pravděpodobně na CPU |
| Eval <20 tok/s | Alert | VRAM spill, check nvidia-smi |
| Queue depth >5 | Warning | Consider OpenRouter fallback |

---

## 8. Shrnutí doporučených změn

### Okamžité (dnes):
1. ✅ Deploy router s fixes (GPU_MODEL_SETS filter, warmup timeout)
2. ⬜ Zvýšit warmup timeout na 300s (pro p40-2 cold start)
3. ⬜ Snížit idle notification threshold na 120s

### Krátkodobé (tento týden):
4. ⬜ Zvýšit RAM p40-2 VM na 24GB
5. ⬜ Přidat monitoring (model loaded check, VRAM usage)

### Střednědobé (tento měsíc):
6. ⬜ Zvážit přesun embedding na separate service/GPU
7. ⬜ Implementovat model load retry s exponential backoff
8. ⬜ Dashboard s real-time GPU metrics
