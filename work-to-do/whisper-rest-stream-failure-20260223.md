# Bug: Whisper REST stream ends without result or error

**Severity**: HIGH (3 meetingy zůstaly ve stavu FAILED, žádný automatický retry)
**Date**: 2026-02-23

## Symptom

Po startu serveru MeetingContinuousIndexer resetuje 3 meetingy z TRANSCRIBING na UPLOADED,
ihned spustí transkripci všech 3 paralelně (max_parallel_jobs=5).
Všechny 3 selžou za ~47 sekund se stejnou chybou:

```
ERROR MeetingTranscriptionService - Whisper returned error for meeting XXX:
  Whisper REST stream ended without result or error event
```

## Analýza

### Timeline (z logů)
```
10:44:04  MeetingContinuousIndexer reset 3 meetingů TRANSCRIBING → UPLOADED
10:44:05  maxParallelJobs changed: 3 → 5
10:44:05  Start transcription pro všechny 3 (65-88 MB soubory)
10:44:08  Sending audio to http://192.168.100.117:8786/transcribe
10:44:52  Všechny 3 fail: "stream ended without result or error event"
```

### Root cause

**WhisperRestClient.kt:198-201** — SSE stream z Whisper REST serveru se uzavře bez jakéhokoli
eventu (ani progress, ani result, ani error). Klient projde celý while loop na readUTF8Line(),
nedostane žádná data, a vrátí fallback chybu.

### Pravděpodobné příčiny na straně Whisper REST serveru

1. **3 paralelní requesty, každý nahrává model zvlášť** — `whisper_rest_server.py:272-273`:
   `model = WhisperModel(model_name, device="cpu")` se volá v KAŽDÉM requestu.
   Medium model = ~1.5 GB. 3 requesty = 4.5 GB jen pro modely + 220 MB audio dat.

2. **Single worker** — `whisper_rest_server.py:380`: `workers=1` (uvicorn single worker).
   Všechny 3 SSE streamy i transkripce běží v jednom procesu.

3. **Žádný concurrency limit** na straně REST serveru — přijme libovolný počet requestů.

4. **OOM kill nebo crash** — server pravděpodobně spadne při pokusu o 3× load modelu,
   ale FastAPI/sse_starlette neodešle error event, jen uzavře spojení.

### Dotčené soubory

| Soubor | Řádek | Popis |
|--------|-------|-------|
| `backend/service-whisper/whisper_rest_server.py` | 272-273 | Model se loaduje per-request, ne globálně |
| `backend/service-whisper/whisper_rest_server.py` | 380 | `workers=1` — single uvicorn worker |
| `backend/service-whisper/whisper_rest_server.py` | 109-223 | `event_generator()` — žádný limit souběžných transkripci |
| `backend/server/.../meeting/WhisperRestClient.kt` | 198-201 | Fallback error message |
| `backend/server/.../meeting/MeetingTranscriptionService.kt` | 70-73 | Meeting přechází do FAILED bez retry |

### Meetingy v FAILED stavu

- `699c1027518a0ded78ec6a74` — 67 MB (35 min audio)
- `698db9dde40ac962ba6f2c61` — 88 MB (46 min audio)
- `699c1008518a0ded78ec6a73` — 68 MB (35 min audio)

## Doporučená oprava

### 1. Whisper REST server — globální model cache + semaphore
```python
# Singleton model instance (load once, reuse)
_model_cache: dict[str, WhisperModel] = {}
_transcription_semaphore = asyncio.Semaphore(1)  # Max 1 concurrent transcription

def get_model(model_name: str) -> WhisperModel:
    if model_name not in _model_cache:
        _model_cache[model_name] = WhisperModel(model_name, device="cpu")
    return _model_cache[model_name]
```

### 2. Server-side concurrency limit
Odmítnout request s HTTP 429 (Too Many Requests) pokud semaphore je obsazený,
místo tichého selhání.

### 3. Kotlin retry pro Whisper REST
`MeetingTranscriptionService` by měl rozlišovat:
- Whisper chyba v transkripci → FAILED (finální)
- Spojení uzavřeno / stream prázdný → retry s backoff (transientní chyba)

### 4. MeetingContinuousIndexer — sekvenční spouštění
Místo spuštění všech UPLOADED meetingů najednou, respektovat kapacitu Whisper serveru.
Server by měl hlásit kapacitu přes `/health` endpoint (volná sloty).
