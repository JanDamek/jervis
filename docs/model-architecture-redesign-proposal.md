# Návrh reorganizace modelové architektury

**Datum**: 2026-03-02
**Status**: PROPOSAL (k diskuzi)
**Navazuje na**: `work-to-do/smart-model-routing-20260301.md`

---

## Executive Summary

Reorganizace rozdělí GPU role na **dedikované účely**, přesune interaktivní zátěž (chat, agenti) na **OpenRouter** (free/placený), a přidá **streaming voice** pro real-time hlasový vstup. Cíl: snížit GPU contention, zlevnit provoz, zrychlit odezvu chatu a umožnit voice-first interakci.

---

## 1. Současný stav

### GPU alokace (2× NVIDIA P40, 24GB VRAM)

```
GPU1 (p40-1):  qwen3-coder-tool:30b (48k ctx)     → všechno (chat, orchestrator, KB, korekce)
GPU2 (p40-2):  qwen3-coder-tool:30b (32k ctx)      → všechno (+ qwen3-embedding:8b pro RAG)
               + qwen3-embedding:8b (~5GB)
```

### Problémy současného stavu

| Problém | Dopad |
|---------|-------|
| **GPU contention** | Chat (CRITICAL) preemptuje background, KB čeká ve frontě |
| **Jeden model na vše** | qwen3-coder-tool:30b je general-purpose, ne specialist |
| **Whisper na CPU** | medium model ~0.5x real-time, large-v3 ~5-10x pomalejší |
| **Žádný streaming voice** | Audio se nahraje celé → transcribe → čeká se |
| **Korekce/kvalifikace blokují GPU** | Malé kontexty (5-15k) zabírají slot pro :30b |
| **Orchestrátor vybírá model** | Tight coupling na infrastrukturu |

### Request flow (současný)

```
Všechny LLM requesty → Ollama Router → GPU1/GPU2 (fronta)
                                       ↓
                                     CRITICAL (chat) preemptuje NORMAL (KB, korekce)
                                     → GPU thrashing, background nikdy nedokončí
```

---

## 2. Navrhovaná architektura

### Nové GPU role

```
┌─────────────────────────────────────────────────────────────────────┐
│  GPU1 (P40, 24GB) — BACKGROUND & KB                                │
│                                                                     │
│  qwen3-coder-tool:30b (32k ctx)    → KB extraction, background     │
│  qwen3-embedding:8b                → RAG embedding (vždy loaded)   │
│                                                                     │
│  Priorita: NORMAL only                                              │
│  Žádný CRITICAL traffic → stabilní throughput                       │
│  Use cases: KB_EXTRACTION, EMBEDDING, BACKGROUND (slow, batch)      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  GPU2 (P40, 24GB) — EMBEDDING + VL + REASONING                     │
│                                                                     │
│  Varianta A: qwen3-embedding:8b + qwen3-vl:latest (12GB)           │
│              → embedding + vision (document OCR, image analysis)    │
│                                                                     │
│  Varianta B: qwen3-embedding:8b + qwen3-30b-a3b (reasoning)        │
│              → embedding + reasoning model pro kvalifikaci           │
│                                                                     │
│  Use cases: EMBEDDING (sdílený), VL processing, QUALIFICATION       │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  OpenRouter (free + paid tiers)                                     │
│                                                                     │
│  CHAT         → claude-sonnet-4 / gpt-4o / qwen3-30b:free          │
│  ORCHESTRATOR → claude-sonnet-4 / claude-haiku-4                    │
│  CORRECTION   → claude-haiku-4 / qwen3-30b:free (malý kontext)     │
│  QUALIFICATION→ qwen3-30b:free / claude-haiku-4                     │
│  CODING       → claude-sonnet-4 / claude-opus-4                     │
│                                                                     │
│  Routing podle client policy (LOCAL_ONLY → FULL_ACCESS)             │
└─────────────────────────────────────────────────────────────────────┘
```

### Nový request flow

```
Interaktivní (chat, orchestrator, coding)
    ↓
    Router: use_case + client_policy → OpenRouter (cloud-first)
    ↓
    P40 fallback jen pro LOCAL_ONLY klienty nebo cloud outage

Pozadí (KB, embedding, background tasks)
    ↓
    Router: → GPU1 (dedikovaná, žádný CRITICAL preemption)
    ↓
    Stabilní throughput, žádné přerušení

Embedding (RAG, search)
    ↓
    Router: → GPU1 nebo GPU2 (oba mají qwen3-embedding:8b loaded)
    ↓
    Nízká latence, model vždy v paměti

Vision / Document OCR
    ↓
    Router: → GPU2 (qwen3-vl dedicated)
    ↓
    Bez swapování modelů, vždy ready

Whisper / Voice
    ↓
    Varianta: GPU2 (Moonshine/Parakeet) NEBO NAS (stávající REST)
    ↓
    Streaming real-time pro voice chat
```

---

## 3. Detailní analýza komponent

### 3.1 GPU1 — Background & KB Engine

**Modely:**
- `qwen3-coder-tool:30b` (num_ctx=32768) — KB extraction, graph building, background orchestration
- `qwen3-embedding:8b` — RAG embedding (keep_alive=-1)

**Zátěž:**
- KB ingest: ~30 chunků per dokument, LLM summary + graph extraction
- Background tasks: scheduled scany, deadline check, idle review
- Embedding: batch i on-demand query embedding

**Výhody specializace:**
- **Žádný CRITICAL preemption** → background tasky doběhnou bez přerušení
- **Stabilní model loading** → nikdy se neswapuje, :30b + embedding vždy v VRAM
- **Předvídatelný throughput** → KB ingest pipeline má garantovaný GPU čas

**Konfigurace:**
```python
GPU1_MODEL_SET = ["qwen3-coder-tool:30b", "qwen3-embedding:8b"]
GPU1_PRIORITY_FILTER = [Priority.NORMAL]  # Odmítá CRITICAL
GPU1_USE_CASES = ["KB_EXTRACTION", "EMBEDDING", "BACKGROUND", "DEADLINE_SCAN"]
```

### 3.2 GPU2 — Varianty

#### Varianta A: Embedding + VL (doporučeno)

**Modely:**
- `qwen3-embedding:8b` (~5GB) — sdílený embedding (redundance s GPU1)
- `qwen3-vl:latest` (~12GB) — vision/document OCR

**VRAM budget:** 5 + 12 = ~17GB z 24GB (OK)

**Výhody:**
- VL vždy v paměti → žádný model swap pro image processing
- Redundantní embedding → vyšší dostupnost pro RAG queries
- Document OCR pipeline je rychlejší (není třeba swapovat VL on-demand)

**Nevýhody:**
- Žádný reasoning model na GPU2 → kvalifikace/korekce musí jít přes GPU1 nebo cloud
- VL se nepoužívá tak často → GPU2 může být idle

#### Varianta B: Embedding + Reasoning (Qwen3-30b-a3b)

**Modely:**
- `qwen3-embedding:8b` (~5GB)
- `qwen3-30b-a3b` (~20GB, reasoning model) — kvalifikace, korekce, thinking

**VRAM budget:** 5 + 20 = ~25GB (těsné, ale P40 má 24GB → možný CPU spill)

**Výhody:**
- Reasoning model pro kvalifikaci a korekci lokálně
- Lepší kvalita reasoning než general-purpose :30b

**Nevýhody:**
- VRAM na hranici → možný CPU spill, pomalejší inference
- VL musí jít přes cloud nebo swapovat modely
- Qwen3-30b-a3b jako reasoning může být pomalejší (thinking tokens)

#### Varianta C: Hybrid — VL + malý reasoning (kompromis)

**Modely:**
- `qwen3-embedding:8b` (~5GB)
- `qwen3-vl:latest` (~12GB)
- Malý reasoning model (~3-5GB) pro jednoduché úlohy

**VRAM budget:** 5 + 12 + 5 = ~22GB (OK)

**Výhoda:** Pokrývá oba use cases
**Nevýhoda:** Malý reasoning model má omezené schopnosti

### 3.3 OpenRouter — Interaktivní vrstva

**Klíčový koncept:** Routing vybírá cíl, orchestrátor jen definuje *typ modelu* (use case).

```python
# Orchestrátor říká CO potřebuje:
route = request_route(
    use_case="CHAT",           # Typ: audio, coding, thinking, correction...
    client_id=client_id,
    estimated_tokens=25000,
    priority="FOREGROUND"
)

# Router rozhoduje KDE to poběží:
# → OpenRouter (claude-sonnet-4) nebo GPU (p40 fallback)
```

**Use case → model mapping (per tier):**

| Use Case | FULL_ACCESS | STANDARD_CLOUD | FREE_CLOUD | LOCAL_ONLY |
|----------|-------------|----------------|------------|------------|
| CHAT | claude-sonnet-4 → p40 | claude-sonnet-4 → p40 | qwen3-30b:free → p40 | p40 |
| CODING | claude-sonnet-4 → opus | claude-sonnet-4 | p40 | p40 |
| CORRECTION | haiku-4 → p40 | haiku-4 → p40 | qwen3-30b:free → p40 | p40 |
| QUALIFICATION | qwen3-30b:free | qwen3-30b:free | qwen3-30b:free | p40 |
| THINKING | claude-sonnet-4.5 | claude-sonnet-4 | qwen3-30b:free | p40 |
| KB_EXTRACTION | p40 (vždy) | p40 (vždy) | p40 (vždy) | p40 |
| EMBEDDING | local (vždy) | local (vždy) | local (vždy) | local |

**Korekční a kvalifikační agent na cloud:**

Hlavní benefit — uvolnění GPU pro KB:
- **Korekce** (5-15k tokenů): claude-haiku-4 je levný (~$0.25/1M input) a rychlejší než P40
- **Kvalifikace** (3-8k tokenů): qwen3-30b:free je zdarma, stačí pro classification
- **Agent chat**: cloud-first = okamžitá odezva bez čekání na GPU

### 3.4 Whisper / Voice — Streaming transkripce

#### Současný stav
- faster-whisper na CPU (`device="cpu"`)
- medium model, batch processing (ne real-time)
- REST server na NAS (rest_remote mode)

#### Navrhované varianty

##### Varianta W1: Moonshine v2 na GPU2 (streaming, real-time)

**Model:** Moonshine v2 Medium (~280MB VRAM)
- 258ms latence (43.7× rychlejší než Whisper Large v3)
- Navržený pro streaming (ergodic encoder, ne 30s windowing)
- Podporuje český jazyk (multilingual)

**Deployment:**
- Na GPU2 vedle VL/embedding (280MB je zanedbatelné)
- Streaming audio input → real-time transkripce
- Voice chat: mikrofon → Moonshine stream → LLM → TTS

**Výhody:**
- Sub-300ms latence → voice chat je plynulý
- Minimální VRAM (~280MB)
- Skutečný streaming (ne 30s windowing jako Whisper)

**Nevýhody:**
- Nový model, potřeba integrace
- Přesnost nižší než Whisper large-v3 pro batch (ale vyšší než medium)
- Czech language support nutno ověřit

##### Varianta W2: NVIDIA Parakeet TDT 0.6B v2

**Model:** 600M parametrů, ~2GB VRAM
- RTFx 3386 (50× rychlejší než alternativy)
- Optimalizovaný pro streaming (RNN-Transducer)
- **Pouze angličtina** → nevhodné pro CZ meetings

##### Varianta W3: NVIDIA Canary Qwen 2.5B

**Model:** 2.5B parametrů, ~8GB VRAM
- Nejlepší přesnost (5.63% WER)
- Multilingual včetně češtiny
- RTFx 418 (418× rychlejší než real-time)

**Deployment:**
- Potřebuje ~8GB VRAM → musí nahradit jiný model na GPU2
- Nebo dedikovaný GPU slot

**Nevýhody:**
- Zabírá značný VRAM
- Latence vyšší než Moonshine

##### Varianta W4: Hybrid — Moonshine streaming + Whisper batch (doporučeno)

```
Real-time voice chat  → Moonshine v2 Medium (GPU2, streaming, ~280MB)
Batch meeting upload  → Whisper large-v3 (NAS CPU, přesnost)
Re-transkripce        → Whisper large-v3 (beam_size=10, NAS CPU)
```

**Výhody:**
- Best of both worlds
- Moonshine pro interakci, Whisper pro kvalitu
- Minimální dopad na VRAM

##### Varianta W5: Cloud STT (AssemblyAI / Deepgram)

Pro real-time streaming:
- **AssemblyAI**: 300ms P50 latence, multilingual
- **Deepgram Nova-3**: ultra-low latence, šumové prostředí

**Výhody:** Žádné GPU, okamžité nasazení, profesionální kvalita
**Nevýhody:** Závislost na cloud, cena, data opouští server

---

## 4. Voice Chat jako součást "nervové soustavy"

### Architektura voice pipeline

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────┐
│ Mikrofon    │────→│ Moonshine v2 │────→│ LLM (cloud) │────→│ TTS      │
│ (WebSocket) │     │ (GPU2/stream)│     │ (OpenRouter) │     │ (local)  │
└─────────────┘     └──────────────┘     └─────────────┘     └──────────┘
                    ↑ real-time           ↑ streaming          ↑ streaming
                    │ <300ms              │ ~1-3s               │ ~200ms
                    │                     │                     │
                    └─────── Total latence: ~2-4s ─────────────┘
```

### Technicky je to možné:

1. **Audio input** → WebSocket stream z klienta
2. **STT streaming** → Moonshine v2 na GPU2 (sub-300ms chunks)
3. **LLM inference** → OpenRouter (claude-sonnet-4 streaming)
4. **TTS** → lokální model (Piper, Bark, nebo cloud)
5. **Audio output** → WebSocket stream zpět ke klientovi

### Integrace do Jervis

```
Nový endpoint: WS /api/voice-chat
    ↓
VoiceChatSession:
  1. Audio chunks → Moonshine STT (GPU2)
  2. Transcript → accumulate until pause detected
  3. Complete utterance → route as CHAT use_case
  4. LLM response → TTS → audio stream back
  5. Parallel: korekční pravidla z KB (learning loop)
```

### Dopad na architekturu

- GPU2 musí mít Moonshine vždy loaded (~280MB, minimální)
- OpenRouter CHAT queue zpracovává voice i text requesty
- TTS je separátní služba (CPU-based Piper nebo cloud)
- WebSocket infra potřeba pro real-time audio

---

## 5. Kompletní navrhovaná konfigurace

### Doporučená varianta (GPU + OpenRouter + Moonshine)

```
┌─────────────────────────────────────────────────────────────────┐
│ GPU1 (P40, 24GB) — BACKGROUND ENGINE                           │
│                                                                 │
│ ┌─────────────────────────────────────────────┐                 │
│ │ qwen3-coder-tool:30b (32k ctx)   ~25GB      │                │
│ │ → KB extraction, graph building              │                │
│ │ → Background orchestration                   │                │
│ │ → Batch processing (scheduled tasks)         │                │
│ ├─────────────────────────────────────────────┤                │
│ │ qwen3-embedding:8b               ~5GB        │                │
│ │ → RAG embedding (batch ingest)               │                │
│ └─────────────────────────────────────────────┘                │
│                                                                 │
│ PRIORITY: NORMAL only (no preemption)                           │
│ USE CASES: KB_EXTRACTION, BACKGROUND, DEADLINE_SCAN, EMBEDDING  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ GPU2 (P40, 24GB) — SPECIALIST ENGINE                            │
│                                                                 │
│ ┌─────────────────────────────────────────────┐                 │
│ │ qwen3-embedding:8b               ~5GB        │                │
│ │ → RAG query embedding (real-time)            │                │
│ ├─────────────────────────────────────────────┤                │
│ │ qwen3-vl:latest                  ~12GB       │                │
│ │ → Document OCR, image analysis               │                │
│ │ → KB image ingest pipeline                   │                │
│ ├─────────────────────────────────────────────┤                │
│ │ moonshine-v2-medium              ~0.3GB      │                │
│ │ → Real-time voice streaming                  │                │
│ │ → Voice chat input                           │                │
│ └─────────────────────────────────────────────┘                │
│                                                                 │
│ Total VRAM: ~17.3GB / 24GB (headroom pro batching)              │
│ USE CASES: EMBEDDING (query), VL, VOICE_STREAM                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ OpenRouter (cloud) — INTERACTIVE ENGINE                         │
│                                                                 │
│ FREE tier:                                                      │
│   qwen3-30b:free → CHAT, QUALIFICATION, DEADLINE_SCAN           │
│                                                                 │
│ PAID_LOW tier:                                                  │
│   claude-haiku-4 → CORRECTION, QUALIFICATION, CHAT fallback     │
│   gpt-4o-mini → CHAT alternative                                │
│                                                                 │
│ PAID_HIGH tier:                                                 │
│   claude-sonnet-4 → CHAT (primární), ORCHESTRATOR, THINKING     │
│   claude-sonnet-4.5 → THINKING (complex reasoning)              │
│   gpt-4o → CODING                                               │
│   claude-opus-4 → CRITICAL tasks                                │
│   gemini-2.5-pro → LARGE_CONTEXT (1M tokens)                    │
│                                                                 │
│ USE CASES: CHAT, CORRECTION, QUALIFICATION, CODING,             │
│            ORCHESTRATOR, THINKING                                │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ NAS (CPU) — BATCH TRANSCRIPTION                                 │
│                                                                 │
│ faster-whisper (medium) → Batch meeting transcription            │
│ faster-whisper (large-v3, beam=10) → Re-transkripce              │
│                                                                 │
│ USE CASES: MEETING_TRANSCRIPTION, RETRANSCRIPTION                │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. Routing redesign

### Orchestrátor → Router komunikace

```python
# PŘED (orchestrátor vybírá model):
tier = estimate_and_select_tier(messages, tools)
response = llm_provider.completion(messages, tier=LOCAL_STANDARD)

# PO (orchestrátor říká CO potřebuje):
route = request_route(
    use_case="CHAT",                    # TYP: chat/coding/thinking/correction/...
    client_id="68a336adc3acf65a48cab3e7",
    estimated_tokens=25000,
    priority="FOREGROUND"
)
response = llm_provider.completion(messages, route=route)
```

### Router decision tree (rozšířený)

```
request_route(use_case, client_id, tokens, priority):

  1. Load client policy (cached 60s)
     → accessTier: LOCAL_ONLY | FREE_CLOUD | STANDARD_CLOUD | FULL_ACCESS

  2. Map use_case → target engine:
     ├─ KB_EXTRACTION, BACKGROUND, DEADLINE_SCAN → GPU1 (always local)
     ├─ EMBEDDING → GPU1 or GPU2 (local, load balanced)
     ├─ VL_PROCESSING → GPU2 (local, qwen3-vl)
     ├─ VOICE_STREAM → GPU2 (local, moonshine)
     ├─ MEETING_TRANSCRIPTION → NAS (local, whisper REST)
     │
     ├─ CHAT → cloud-first (OpenRouter) per tier
     ├─ CORRECTION → cloud-first (haiku/free) per tier
     ├─ QUALIFICATION → cloud (free model) OR GPU2 (reasoning)
     ├─ CODING → cloud (sonnet/opus) per tier
     ├─ THINKING → cloud (sonnet-4.5) per tier
     └─ ORCHESTRATOR → cloud per tier, GPU1 fallback

  3. Per-tier queue selection (viz tabulka výše)

  4. Availability check:
     ├─ Cloud: budget check + provider availability
     ├─ GPU: slot check + queue depth
     └─ Fallback cascade: cloud → GPU1 → queue/wait

  5. Return Route(target, model, api_base, max_context)
```

---

## 7. Analýza dopadů

### Pozitivní dopady

| Oblast | Dopad | Kvantifikace |
|--------|-------|-------------|
| **Chat latence** | Cloud-first eliminuje GPU queue wait | ~10s (P40 queue) → ~2-3s (cloud streaming) |
| **GPU1 throughput** | Žádný CRITICAL preemption → KB/background stabilní | KB ingest 2-3× rychlejší (žádné přerušení) |
| **Voice chat** | Real-time streaming konverzace | Nová funkce, ~2-4s end-to-end latence |
| **Korekce kvalita** | Claude Haiku místo qwen3:30b | Vyšší přesnost korekce (frontier model) |
| **Kvalifikace rychlost** | Free cloud model (vždy dostupný) | Žádné čekání na GPU, paralelní kvalifikace |
| **VL processing** | Dedicated GPU2 slot | Žádný model swap, vždy ready |
| **Embedding** | Redundantní na obou GPU | Vyšší dostupnost, nižší latence |
| **Škálovatelnost** | Cloud se škáluje neomezeně | Žádný hardware bottleneck pro interaktivní load |
| **Cost per client** | Granulární tier control | LOCAL_ONLY = $0, FREE = ~$0, STANDARD = nízké |
| **Údržba** | Méně model swapping/contention | Jednodušší debugging, předvídatelnější chování |

### Negativní dopady / Rizika

| Oblast | Riziko | Mitigace |
|--------|--------|----------|
| **Cloud závislost** | Chat nefunguje bez internetu | P40 fallback pro LOCAL_ONLY; offline degraded mode |
| **Náklady OpenRouter** | PAID_HIGH klienti mohou být drazí | Budget limity per klient; monitoring; haiku pro méně kritické |
| **Data privacy** | Chat data jdou přes cloud | LOCAL_ONLY tier pro citlivé klienty; OpenRouter DPA |
| **Moonshine maturity** | Nový model, méně otestovaný | Fallback na Whisper REST; postupné nasazení |
| **GPU2 underutilization** | VL + embedding nepotřebují 24GB | Moonshine + budoucí modely vyplní kapacitu |
| **Complexity** | Více modelů = více moving parts | Dobrý monitoring; jasné queue definice; health checks |
| **CZ Moonshine support** | Nutno ověřit kvalitu pro češtinu | Test na reálných nahrávkách; fallback na Whisper |
| **Migration effort** | Změna routing logiky + nové modely | Postupná migrace (viz implementační plán) |

### Finanční analýza

```
SOUČASNÝ STAV:
  GPU provoz: ~$0/měsíc (vlastní hardware, jen elektřina ~$50)
  OpenRouter: ~$0-50/měsíc (minimální cloud usage)
  Celkem: ~$50-100/měsíc

NAVRHOVANÝ STAV (odhad pro 5 klientů):
  GPU provoz: ~$50/měsíc (stejná elektřina)
  OpenRouter FREE: $0 (qwen3-30b:free pro basic klienty)
  OpenRouter PAID_LOW: ~$20-50/měsíc (haiku pro korekce/kvalifikaci)
  OpenRouter PAID_HIGH: ~$50-200/měsíc (sonnet pro chat, závisí na volume)
  Celkem: ~$120-300/měsíc

  BREAK-EVEN vs. přidání dalšího GPU:
  - Nový P40 (used): ~$300-500 jednorázově + elektřina
  - Cloud advantage: škálovatelnost, frontier modely, žádná údržba
  - Cloud pro 5 klientů je ekonomičtější do ~$300/měsíc
```

---

## 8. Implementační plán (fáze)

### Fáze 1: GPU role separation (nejnižší riziko)

1. Označit GPU1 jako "background-only" (odmítá CRITICAL)
2. Přesunout embedding na obě GPU
3. Ponechat qwen3-vl na GPU2 (vždy loaded)
4. Upravit router: use_case → GPU routing

**Dopad:** Okamžité zlepšení KB throughput, minimální riziko

### Fáze 2: Cloud-first routing (střední riziko)

1. Implementovat `request_route()` v routeru
2. Přesunout CHAT na OpenRouter (cloud-first)
3. Přesunout CORRECTION na cloud (haiku/free)
4. Přesunout QUALIFICATION na cloud (free)
5. Implementovat budget tracking per klient

**Dopad:** Dramatické zrychlení chatu, GPU uvolněná pro background

### Fáze 3: Voice streaming (vyšší riziko, nová funkce)

1. Deploy Moonshine v2 na GPU2
2. Implementovat WebSocket voice endpoint
3. Integrace STT → LLM → TTS pipeline
4. UI: voice chat button v klientu

**Dopad:** Nová funkce — voice-first interakce

### Fáze 4: Optimalizace (ongoing)

1. Fine-tune queue definice na základě reálného usage
2. A/B testing modelů (haiku vs free models pro korekce)
3. Monitoring a alerting na cloud costs
4. Případně nahradit Whisper na NAS za Canary Qwen 2.5B (pokud GPU3 k dispozici)

---

## 9. Alternativní přístupy (zváženy a vyhodnoceny)

### A. Třetí GPU místo cloudu

**Pro:** Žádná cloud závislost, plná kontrola
**Proti:** Jednorázová investice + elektřina; P40 je starý HW; frontier modely (Sonnet, Opus) na lokálu nejsou dostupné
**Verdikt:** Neřeší problém kvality modelů. P40 s qwen3:30b nedosahuje kvality Claude Sonnet.

### B. Vše na cloud (žádný lokální GPU)

**Pro:** Maximální jednoduchost, nejlepší modely
**Proti:** Drahé pro batch processing (KB, embedding); data privacy concern; žádný offline mode
**Verdikt:** Embedding a KB extraction jsou volume-heavy — cloud by byl příliš drahý.

### C. vLLM místo Ollama

**Pro:** Lepší batching, PagedAttention, vyšší throughput
**Proti:** Migrace effort; Ollama je jednodušší pro multi-model setup; P40 nemá dostatečně moderní HW pro plný vLLM benefit
**Verdikt:** Pro P40 s jedním modelem je přínos minimální. Zajímavé při upgradu na A100/H100.

### D. Jeden velký model na obou GPU (tensor parallel)

**Pro:** Větší model (70B+) s lepší kvalitou
**Proti:** P40 nemá NVLink; inter-GPU bandwidth přes PCIe je pomalý; complexity
**Verdikt:** Technicky nevhodné pro P40 setup.

---

## 10. Doporučení

### Primární doporučení: Implementovat Fáze 1 + 2

**GPU1 = background/KB, GPU2 = embedding/VL, OpenRouter = interaktivní vrstva**

Důvody:
1. **Nejnižší riziko** — stávající HW, jen změna routing logiky
2. **Okamžitý benefit** — chat rychlejší, KB stabilnější
3. **Postupná migrace** — žádný big bang, po fázích
4. **Cloud cost kontrola** — tier system + budget limity zabraňují překvapením
5. **Zpětná kompatibilita** — LOCAL_ONLY klienti fungují jako dříve

### Sekundární doporučení: Moonshine pro voice (Fáze 3)

Nasadit až po stabilizaci Fáze 1+2. Ověřit CZ language support na reálných datech. Pokud Moonshine CZ nezvládá, použít cloud STT (AssemblyAI) jako alternativu.

---

## 11. Soubory k úpravě

### Kotlin
- `CloudModelPolicy.kt` — přidat `accessTier: ModelAccessTier`
- `InternalClientRouting.kt` — nový endpoint `/internal/client/{id}/model-policy`
- `OpenRouterSettingsDtos.kt` — `ModelAccessTierEnum`
- `BackgroundEngine.kt` — prioritní filtr pro GPU1

### Python (Router)
- `router_core.py` — GPU role filtering (GPU1=NORMAL only)
- `request_queue.py` — use_case → GPU mapping
- `openrouter_catalog.py` — use_case based queue selection
- Nový: `router_client.py` → `request_route()` s use_case

### Python (Orchestrátor)
- `handler_agentic.py` — `request_route(use_case="CHAT")`
- `handler.py` — `request_route(use_case="CORRECTION")`
- `provider.py` — nový Route-based completion

### Nové služby (Fáze 3)
- `backend/service-voice/` — Moonshine streaming server
- `backend/server/.../service/voice/VoiceChatService.kt` — WebSocket handler

---

## Reference

- [Moonshine v2 — streaming ASR](https://github.com/moonshine-ai/moonshine)
- [NVIDIA Parakeet TDT 0.6B v2](https://developer.nvidia.com/blog/nvidia-speech-ai-models-deliver-industry-leading-accuracy-and-performance/)
- [NVIDIA Canary Qwen 2.5B](https://northflank.com/blog/best-open-source-speech-to-text-stt-model-in-2026-benchmarks)
- [Hybrid GPU-Cloud cost analysis](https://arxiv.org/html/2509.18101v1)
- [LLM inference cost optimization](https://www.decodesfuture.com/articles/cost-efficiency-heterogeneous-gpu-llm-serving)
- [OpenRouter model catalog](https://openrouter.ai/models)
