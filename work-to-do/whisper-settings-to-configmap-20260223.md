# Whisper: Přesunout nastavení z UI do ConfigMap

> **Datum:** 2026-02-23
> **Rozhodnutí:** Whisper nastavení nemá být v UI — uživatel to nikdy nebude měnit. Přesunout do ConfigMap.

---

## 1. Problém

Whisper settings screen (`WhisperSettings.kt`) je plný parametrů, které uživatel nikdy nebude měnit přes UI. Navíc:

- **Jazyk** (`language`) — zbytečné pole. Nikdy se neví v jakém jazyce meeting bude → vždy autodetekce
- **Úloha** (`task: TRANSCRIBE/TRANSLATE`) — překlad do angličtiny nemá smysl, vždy se přepisuje v originále
- Ostatní parametry (beam size, VAD, práh) jsou technické — patří do ConfigMap, ne do UI

---

## 2. Řešení: Smazat UI, přesunout do ConfigMap

### Smazat z UI

| Soubor | Co |
|--------|-----|
| `shared/ui-common/.../sections/WhisperSettings.kt` | Celý UI screen (296 řádků) — SMAZAT |
| Menu entry `WHISPER` v sidebar | Odkaz v navigaci — SMAZAT |

### Smazat zbytečné DTO/RPC (volitelně)

| Soubor | Co |
|--------|-----|
| `shared/common-dto/.../whisper/WhisperSettingsDtos.kt` | DTOs — SMAZAT (nebo zjednodušit) |
| `shared/common-api/.../IWhisperSettingsService.kt` | RPC interface — SMAZAT |
| `backend/server/.../rpc/WhisperSettingsRpcImpl.kt` | RPC impl — SMAZAT |

Backend by měl číst nastavení přímo z properties/ConfigMap, ne z MongoDB.

### Smazat z DTO

- `language` field — odstranit kompletně (vždy `null` = autodetekce)
- `task` field — odstranit kompletně (vždy `TRANSCRIBE`, nikdy `TRANSLATE`)

### Přidat do ConfigMap (`k8s/configmap.yaml`)

```yaml
whisper:
  deployment-mode: REST_REMOTE          # REST_REMOTE nebo K8S_JOB
  rest-remote-url: "http://192.168.100.117:8786"  # URL pro REST mód
  model: medium                         # tiny / base / small / medium / large-v3
  beam-size: 8                          # 1-10, přesnější
  vad-filter: true                      # přeskočit ticho
  word-timestamps: false                # NE časování po slovech
  condition-on-previous-text: true      # kontextové navazování
  no-speech-threshold: 0.6             # práh detekce řeči
  max-parallel-jobs: 5                 # paralelní REST requesty
  # Smazané parametry:
  # language — vždy autodetekce (null)
  # task — vždy TRANSCRIBE
```

### Aktuální defaults vs nové hodnoty

| Parametr | Aktuální default | Nová hodnota | Změna |
|----------|-----------------|--------------|-------|
| `deploymentMode` | K8S_JOB | **REST_REMOTE** | ✓ |
| `restRemoteUrl` | 192.168.100.117:8786 | beze změny | — |
| `model` | BASE | **MEDIUM** | ✓ |
| `beamSize` | 5 | **8** | ✓ |
| `vadFilter` | true | true | — |
| `wordTimestamps` | false | false | — |
| `conditionOnPreviousText` | true | true | — |
| `noSpeechThreshold` | 0.6 | 0.6 | — |
| `maxParallelJobs` | 3 | **5** | ✓ |
| `language` | null | **SMAZAT** | ✓ |
| `task` | TRANSCRIBE | **SMAZAT** | ✓ |

### Speaker diarization (poznámka)

Uživatel zmínil "pokud existuje možnost řečník, tak ano". Aktuální Whisper implementace nemá speaker diarization (identifikace řečníků). Toto by vyžadovalo integraci s pyannote-audio nebo podobnou knihovnou — samostatná feature, ne součást tohoto tasku.

---

## 3. Spring Properties class

**Soubor:** `backend/server/.../configuration/WhisperProperties.kt` (nebo ekvivalent)

Aktuálně se Whisper settings čtou z MongoDB (`WhisperSettingsDocument`). Po přesunu do ConfigMap:
- Vytvořit `WhisperProperties` (`@ConfigurationProperties(prefix = "whisper")`)
- Číst přímo z ConfigMap/application.yml
- Smazat MongoDB dokument `whisper_settings`

---

## 4. Relevantní soubory

| Soubor | Řádky | Co |
|--------|-------|----|
| `shared/ui-common/.../sections/WhisperSettings.kt` | celý | UI screen — SMAZAT |
| `shared/common-dto/.../whisper/WhisperSettingsDtos.kt` | celý | DTOs — SMAZAT language, task |
| `shared/common-api/.../IWhisperSettingsService.kt` | celý | RPC interface — SMAZAT |
| `backend/server/.../rpc/WhisperSettingsRpcImpl.kt` | celý | RPC impl — SMAZAT |
| `k8s/configmap.yaml` | — | Přidat whisper sekci |
| `backend/service-whisper/whisper_runner.py` | — | Čte parametry — ověřit kompatibilitu |
| `backend/service-whisper/whisper_rest_server.py` | — | REST server — ověřit parametry |
| `docs/architecture.md` | — | Aktualizovat whisper sekci |
