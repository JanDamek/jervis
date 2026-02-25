# Meeting Timeline — starší týdny nejdou rozbalit

**Datum:** 2026-02-25
**Typ:** BUG
**Závažnost:** MEDIUM

## ~~Symptom~~ ✅ OPRAVENO

Na MeetingsScreen se zobrazují skupiny starších týdnů (např. "Týden 16.2. – 22.2." s "7 nahrávek")
s ikonou ▶, ale kliknutí na ně je nerozbalí. "Tento týden" zobrazuje meetingy správně (vždy rozbalený).

## Root cause

`startQuickRecording()` volá `startRecording(clientId = null)` → `lastClientId` se přepíše na null →
`toggleGroup()` má `val clientId = lastClientId ?: return` → tiše vrátí bez rozbalení.

## Fix

1. **startRecording()**: Nepřepisuje `lastClientId`/`lastProjectId` na null — jen pokud je explicitně
   předán non-null parametr (zachová timeline kontext).
2. **toggleGroup()**: Nahrazeno tiché `return` za viditelnou chybovou hlášku + logging.

## Soubory
- `shared/ui-common/.../meeting/MeetingViewModel.kt` — oba fixy
