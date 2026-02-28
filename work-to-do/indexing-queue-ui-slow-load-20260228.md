# UI — fronta indexace se načítá extrémně dlouho

**Priorita**: MEDIUM
**Status**: OPEN

---

## Problém

Obrazovka "Indexace" (fronta indexace KB) se načítá velmi dlouho. UI zůstává na loading spinneru desítky sekund než se zobrazí data.

## Pravděpodobná příčina

- Backend query na SQLite extraction queue nebo MongoDB není optimalizovaná (chybí index, full table scan)
- Načítá se příliš mnoho položek najednou (bez paginace / limitu)
- Serializace velkého response přes kRPC/CBOR je pomalá
- Nebo KB read pod je pomalý (memory pressure, restart po OOM)

## Oblasti k investigaci

1. **Backend endpoint** — co trvá dlouho:
   - `backend/server/.../rpc/` — RPC metoda pro indexing queue
   - KB Python API `/queue` nebo `/extraction-queue` endpoint
   - DB query — přidat LIMIT, paginaci, nebo index

2. **Množství dat** — kolik položek fronta obsahuje:
   - Pokud stovky/tisíce → nutná paginace
   - UI by mělo načíst první stránku rychle (max 50 položek), zbytek lazy load

3. **UI** — `shared/ui-common/.../screens/settings/sections/IndexingSettings.kt` nebo podobný soubor
   - Zkontrolovat zda volá API jednou nebo opakovaně
   - Přidat paginaci / virtuální scrolling

## Ověření

1. Otevřít Indexace → data se zobrazí do 2-3 sekund
2. S velkým množstvím položek (100+) → první stránka rychle, zbytek lazy load
