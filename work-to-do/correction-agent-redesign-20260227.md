# Correction Agent — redesign na kontextově-vědomou korekci

**Priorita**: HIGH

## Problém

Correction agent momentálně funguje jako "slepý přepis s jemnou korekcí" — posílá chunky
paralelně bez sdíleného kontextu, nerozumí fázím meetingu, řečníkům ani obsahu projektu.
Navíc běží PŘED kvalifikací, takže nemá k dispozici kontext projektu/klienta.

### Co je špatně

1. **Paralelní zpracování chunků** (`asyncio.gather + Semaphore(4)`) — ztráta kontextu
   mezi chunky, výsledek nedává smysl jako celek
2. **Běží před kvalifikací** (TRANSCRIBED → CORRECTING) — nemá kontext projektu
3. **Nerozumí fázím meetingu** — neidentifikuje kdo mluví, o čem se jedná
4. **KB používá jen pro korekční pravidla** — neprohledává projektový kontext
5. **NORMAL priorita** — OK pro background, ale měl by mít retry na router errors

## Požadavky na redesign

### 1. Sekvenční zpracování (REVERTOVAT paralelizaci)

Chunky MUSÍ být zpracovány **sekvenčně** — každý chunk musí mít kontext z předchozích:

```python
# ŠPATNĚ (aktuální stav po merge):
results = await asyncio.gather(*[self._correct_chunk(chunk) for chunk in chunks])

# SPRÁVNĚ:
corrected_segments = []
running_context = ""  # Kumulativní kontext z předchozích chunků
for chunk in chunks:
    result = await self._correct_chunk(chunk, running_context)
    corrected_segments.extend(result.segments)
    running_context = self._update_context(running_context, result)
```

Každý chunk dostane:
- Plný text meetingu (existující `all_text`)
- **Kumulativní kontext** — co bylo opraveno v předchozích chuncích
- Identifikované fáze/řečníky z předchozích chunků

### 2. Spouštět PO kvalifikaci (ne po transkripci)

Aktuální flow:
```
TRANSCRIBED → CORRECTING → CORRECTED → INDEXED → (qualification)
```

Nový flow:
```
TRANSCRIBED → INDEXED → (qualification) → CORRECTING → CORRECTED → RE-INDEXED
```

- Po kvalifikaci je jasný **klient, projekt, technologie, jména**
- Agent může prohledávat KB s konkrétním kontextem
- Kvalifikace poskytne `qualificationSteps` — metadata o meetingu

#### Co to vyžaduje:
- `MeetingContinuousIndexer` — přesunout correction trigger za kvalifikaci
- Nový stav nebo flag: `qualified: true` → teprve pak spustit korekci
- Po korekci re-indexovat (aktualizovat KB s opraveným textem)

### 3. Rozpoznávání fází meetingu a řečníků

Agent by měl v prvním průchodu identifikovat:
- **Fáze meetingu**: úvod, review, diskuse, akční body, závěr
- **Řečníci**: kdo mluví (podle hlasu/kontextu)
- **Témata**: o čem se mluví v každé fázi

Toto slouží jako kontext pro korekci:
```
Fáze: "Code review — backend API"
Řečník: "Jan" (tech lead)
→ Korekce: technické termíny, API endpointy, názvy tříd
```

### 4. Aktivní využití KB pro projektový kontext

Po kvalifikaci má agent k dispozici klienta a projekt. Měl by:

- **Prohledat KB** pro relevantní entity (osoby, technologie, projekty)
- **Načíst korekční pravidla** specifická pro klienta/projekt (existující)
- **Použít KB graph** pro nalezení souvislostí (jména ↔ role, technologie ↔ projekt)
- **Doplnit kontext do promptu** — "V tomto projektu pracují: Jan (backend), Petr (frontend)..."

```python
# Po kvalifikaci víme klienta + projekt
kb_context = await self._load_project_context(client_id, project_id)
# → osoby, technologie, slovník, předchozí meetingy
```

### 5. Retry na connection errors

Viz `work-to-do/caller-retry-on-router-restart-20260227.md` — `_call_ollama()` nemá retry
na `httpx.ConnectError`. Přidat retry s exponential backoff (2-4-8s).

## Architektura nového flow

```
1. Whisper dokončí transkripci → TRANSCRIBED
2. Indexace surového textu → INDEXED
3. Kvalifikace (orchestrátor) → qualified=true
   → klient, projekt, qualificationSteps
4. Correction agent spuštěn:
   a. Načte kvalifikační kontext (klient, projekt, jména, technologie)
   b. Prohledá KB pro projektový kontext
   c. Načte korekční pravidla z KB
   d. První průchod: identifikace fází, řečníků, témat
   e. Sekvenční korekce chunků s kumulativním kontextem
   f. Výstup: opravené segmenty + nové otázky
5. Re-indexace opraveného textu → CORRECTED
```

## Soubory k úpravě

- `backend/service-correction/app/agent.py` — **hlavní redesign**
  - Revertovat `asyncio.gather` → sekvenční `for` loop s kontextem
  - Přidat fázi identifikace (řečníci, fáze, témata)
  - Přidat KB context loading po kvalifikaci
  - Přidat retry na connection errors
- `backend/server/.../service/meeting/TranscriptCorrectionService.kt` — trigger po kvalifikaci
- `backend/server/.../service/meeting/MeetingContinuousIndexer.kt` — přesunout correction za kvalifikaci
- `backend/server/.../entity/MeetingDocument.kt` — případně nový stav/flag

## Poznámky

- **Priorita NORMAL je OK** — korekce je background práce
- **Timeout 3600s** je přiměřený — sekvenční zpracování dlouhých meetingů
- **Chunk size 20** segmentů je rozumný — ale s kontextem bude pomalejší
- **Interactive questions** zůstávají — ale s lepším kontextem bude méně otázek
- Re-indexace po korekci je důležitá — KB musí mít opravený text
