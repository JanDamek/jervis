# BUG: Meeting UI — scrollování kvalifikace + editace meetingu

**Datum:** 2026-02-26
**Priorita:** MEDIUM
**Typ:** BUG + FEATURE

---

## Bug 1: Kvalifikace meetingu — nejde scrollovat dolů (MEDIUM)

### Popis

Při psaní/zobrazení kvalifikace meetingu (qualification steps) nejde listovat dolů.
Obsah se nevejde na obrazovku a uživatel nevidí celou kvalifikaci.

### Pravděpodobná příčina

`LazyColumn` nebo `Column` s qualification steps nemá `verticalScroll` modifier
nebo je uvnitř kontejneru s fixní výškou bez scrollu.

### Řešení

Zkontrolovat Composable kde se zobrazují qualification steps a přidat
`Modifier.verticalScroll(rememberScrollState())` nebo zajistit, že `LazyColumn`
má správný `Modifier.fillMaxSize()` / `weight(1f)`.

---

## Bug 2: Nejde editovat meeting — název, typ, přeřazení (HIGH)

### Popis

Meeting nelze editovat po vytvoření:
- Nelze změnit **název** meetingu
- Nelze změnit **typ** meetingu
- Nelze **přeřadit** meeting do jiného projektu/klienta pokud je chybně zařazen

### Požadované chování

Editace meetingu musí provést **kompletní přeřazení**:

1. **Editace metadat**: název, typ, projekt, klient
2. **Smazání indexovaných informací**: Pokud se mění projekt/klient, musí se:
   - Smazat existující KB záznamy (RAG + Graph) pro starý sourceUrn
   - Smazat existující embeddingy ve Weaviate
3. **Reindexace**: Po přeřazení:
   - Znovu spustit KB ingest s novým klientem/projektem
   - Aktualizovat sourceUrn na nový kontext
4. **Přesun na FS**: Přesunout soubor meetingu na správné místo ve filesystem
   (z adresáře starého klienta/projektu do nového)

### Implementace

**Backend:**
- Nový endpoint `updateMeeting(id, name?, type?, clientId?, projectId?)`
- Pokud se mění client/project:
  - KB delete: `DELETE /api/v1/source/{sourceUrn}` pro staré záznamy
  - FS move: přesunout z `data/meetings/{oldClient}/{oldProject}/` do nového
  - Re-ingest: spustit KB ingest s novým kontextem
- Pokud se mění jen název/typ: jednoduchý update v MongoDB

**UI:**
- Editační dialog/screen pro meeting (název, typ, dropdown klient/projekt)
- Potvrzení při přeřazení: "Přeřazení smaže indexované informace a znovu je vytvoří"

### Soubory k prozkoumání

| Soubor | Proč |
|--------|------|
| `shared/ui-common/.../meeting/MeetingsScreen.kt` | UI pro meetings |
| `shared/ui-common/.../meeting/MeetingViewModel.kt` | ViewModel |
| `backend/server/.../rpc/MeetingRpcImpl.kt` | RPC endpoint |
| `backend/server/.../service/meeting/MeetingService.kt` | Business logic |
| `backend/server/.../entity/MeetingDocument.kt` | Entity |
| `shared/common-api/.../service/IMeetingService.kt` | API interface |
