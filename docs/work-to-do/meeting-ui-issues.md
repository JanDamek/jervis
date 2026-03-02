# Meeting UI — nalezené problémy

## Opraveno v tomto průchodu

### 1. JIconButton — tooltipy na hover
- `JIconButton` neměl tooltip — jen `contentDescription` pro screen reader
- **Fix:** Přidán `TooltipBox` + `PlainTooltip` z Material 3 kolem `IconButton`
- Tooltipy se zobrazí na hover na desktopu, long-press na mobilu
- Soubor: `shared/ui-common/.../design/DesignButtons.kt`

### 2. TimelineGroupHeader — vizuální odlišení od meetingů
- Týdenní separátory (`OutlinedCard`) splývaly vizuálně s kartami meetingů
- **Fix:** Přidán `surfaceVariant` background přes `CardDefaults.outlinedCardColors()`
- Separátor je nyní vizuálně odlišený šedým pozadím
- Soubor: `shared/ui-common/.../meeting/MeetingsScreen.kt`

---

## K opravě

### 3. Ikony v top baru — pořadí a srozumitelnost
- `Icons.Default.Replay` (↺) vedle `Icons.Default.Refresh` (↻) vypadají jako media skip back/forward 10s
- Mikrofon nelze použít — nahoře je již mikrofon pro ad-hoc záznam
- **K diskuzi:** Najít vhodné ikony pro "Přepsat znovu" (Whisper) a "Obnovit" (refresh)
- **Zvážit přeskupení:** ▶ 🗣 📖 | ✏ 🔄 🗑 (oddělené skupiny: přehrávání | editace | správa)
- **Soubor:** `MeetingDetailView.kt`

### 4. Korekce přepisu — prázdné `"" → ""`
- Obrazovka "Korekce přepisu" zobrazuje karty `"" → ""` — korekce s prázdnými `original` a `corrected`
- **Příčina:** Buď KB chunks mají prázdná metadata, nebo mapování z `CorrectionChunkDto` → `TranscriptCorrectionDto` ztrácí data
- **Investigovat:**
  - `backend/service-correction/app/agent.py` — jak `submit_correction()` ukládá metadata
  - `backend/server/.../rpc/TranscriptCorrectionRpcImpl.kt` — mapování z KB chunks na DTO
  - KB endpoint `/chunks/by-kind` — co vrací pro `kind=transcript_correction`
- **Soubory:** `CorrectionsScreen.kt` (zobrazení), `agent.py` (ukládání), `TranscriptCorrectionRpcImpl.kt` (mapování)

### 5. LLM korekce — žádný viditelný progress
- Pipeline ukazuje "LLM model opravuje přepis pomocí slovníku... (9 min)" ale nic víc
- Chat panel ukazuje jen spinner "Agent opravuje přepis..."
- Uživatel nevidí: kolik segmentů zpracováno, co se mění, jestli agent generuje otázky
- **Řešení:** Přidat progress callback z correction agenta → Kotlin → UI (jako u whisper transkripce)
- **Soubory:** `agent.py` (progress), `TranscriptCorrectionService.kt` (callback), `PipelineProgress.kt` + `AgentChatPanel.kt` (UI)

### 6. Chat panel — nejasný účel
- Input "Zadejte instrukci pro opravu přepisu..." se zobrazuje vždy, ale je disabled během korekce
- Uživatel neví kdy a jak ho použít
- **Řešení:** Přidat popis/hint:
  - Během korekce: "Korekce probíhá automaticky..." (místo prázdného disabled inputu)
  - Po korekci: "Zadejte instrukci pro dodatečnou opravu, např. 'oprav Novák na Novotný'"
  - Nebo: schovat chat panel dokud korekce neskončí
- **Soubor:** `AgentChatPanel.kt`

### 7. Řečníci — nastavení v UI ✅
- **Implementováno:**
  - `SpeakerDocument` (MongoDB) — profil řečníka per klient (jméno, národnost, jazyky, poznámky, vzorek hlasu)
  - `speakerMapping` na `MeetingDocument` — mapování diarizačních labelů na profily
  - `ISpeakerService` kRPC — CRUD + assignSpeakers + setVoiceSample
  - `SpeakerAssignmentPanel` — UI pro přiřazení řečníků, vytvoření nového, uložení vzorku
  - `TranscriptPanel` — zobrazuje resolved jména místo "SPEAKER_00"
  - People tlačítko v top baru meeting detailu (přepíná chat/mluvči panel)
- **Budoucí rozšíření:**
  - LLM korekce s kontextem řečníků (národnost → očekávaný jazyk přepisu)
  - Automatické rozpoznání hlasu přes voice sample
  - Správa řečníků per klient v Settings
