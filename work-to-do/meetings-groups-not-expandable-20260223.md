# Bug: Meetings timeline — starší týdny nejdou rozbalit

**Severity**: HIGH (nelze se dostat ke starším nahrávkám)
**Date**: 2026-02-23

## Symptom

V Meetings screenu "Týden 16.2. – 22.2." a "Týden 9.2. – 15.2." se nedají rozkliknout.
Klik na skupinu nic neudělá — žádný loading indicator, žádná chyba, žádné nahrávky.

## Analýza

### UI kód (funguje správně)
- `MeetingsScreen.kt:367`: `onClick = { viewModel.toggleGroup(group) }` — handler existuje
- `MeetingViewModel.kt:216-236`: `toggleGroup()` volá `repository.meetings.listMeetingsByRange()`
- `MeetingViewModel.kt:230`: catch blok nastavuje `_error`, ale chyba se v UI nemusí zobrazit

### Pravděpodobné příčiny
1. **kRPC `listMeetingsByRange` selže tiše** — metoda je v `IMeetingService.kt:77` a
   `MeetingRpcImpl.kt:748`, ale volání může selhat na date parsing (`Instant.parse(fromIso)`)
   pokud formát `periodStart`/`periodEnd` neodpovídá ISO-8601
2. **Error se nezobrazuje** — `_error` se v MeetingsScreen nečte / nezobrazuje
3. **`getMeetingTimeline`** (`MeetingRpcImpl.kt:676-745`) generuje `periodStart`/`periodEnd`
   jako `Instant.toString()`, ale `listMeetingsByRange` parsuje `Instant.parse()` — formát
   by měl sedět, ale ověřit

### K ověření
- Přidat log do `toggleGroup()` catch bloku aby se chyba vypisovala
- Zkontrolovat `_error` StateFlow — zobrazuje se v MeetingsScreen?
- Zkontrolovat server logy při kliknutí na skupinu

### Dotčené soubory
| Soubor | Řádek | Popis |
|--------|-------|-------|
| `shared/.../ui/meeting/MeetingsScreen.kt` | 356-369 | Group header onClick |
| `shared/.../ui/meeting/MeetingViewModel.kt` | 216-236 | toggleGroup implementation |
| `backend/.../rpc/MeetingRpcImpl.kt` | 748-764 | listMeetingsByRange server |
| `shared/.../service/IMeetingService.kt` | 77 | RPC interface |
