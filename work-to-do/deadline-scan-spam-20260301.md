# Deadline scan — spamuje GPU, vytváří duplicitní issues

**Priorita**: HIGH
**Status**: OPEN (2026-03-01)

---

## Problém

BackgroundEngine dispatchuje "periodic deadline scan" SCHEDULED_TASK příliš často. Naměřeno 7 scanů za 13 minut (11:28–11:42), přestože interval je nastavený na 5 minut.

Každý scan:
- Běží 5 iterací na GPU (76–306 sekund)
- Volá `brain_search_issues` → `brain_create_issue` opakovaně
- Detekuje loop → forced break → "success" ale s duplikáty
- **Blokuje GPU pro chat** — uživatel čeká na odpověď, ale GPU zpracovává deadline scan

### Naměřeno (2026-03-01, 11:28–11:42)

```
11:28:44 BACKGROUND_START deadline scan → 306s, 5 iterations, loop break
11:30:04 BACKGROUND_START deadline scan → ... (stále běží předchozí?)
11:35:11 BACKGROUND_START deadline scan → 76s, 5 iterations, loop break
11:36:31 BACKGROUND_START deadline scan → 116s, 5 iterations, loop break
11:38:32 BACKGROUND_START deadline scan → ...
11:40:33 BACKGROUND_START deadline scan → ...
11:42:03 BACKGROUND_START deadline scan → ...
```

---

## Root cause 1: Dedup kontrola nebrání řetězení

`BackgroundEngine.kt` (řádek ~1560) kontroluje jestli existuje pending scan:
```kotlin
val existing = taskRepository.findFirstByTypeAndStateIn(
    type = SCHEDULED_TASK,
    states = listOf(NEW, READY_FOR_QUALIFICATION, QUALIFYING, READY_FOR_GPU, PYTHON_ORCHESTRATING),
)
```

Ale jakmile scan skončí (DONE), dedup pass → nový scan se okamžitě dispatchuje. S 60s scheduler loop check a ~80s execution time se scany řetězí téměř nepřetržitě.

### Řešení
1. **`lastDeadlineScan` timestamp** — aktualizovat i po úspěšném completion, ne jen při dispatchi
2. **Cooldown po DONE** — kontrolovat i `DONE` scany, pokud skončily před < 5 min
3. **Nebo nejjednodušeji**: po dispatch nastavit `lastDeadlineScan = now`, scheduler loop pak čeká plných 5 minut. Ověřit že `lastDeadlineScan` přežívá restart (persistence nebo initial = now)

## Root cause 2: brain_create_issue loop v deadline scanu

Agent opakovaně volá `brain_create_issue` pro stejné deadlines → loop detection → forced break. To znamená že:
- Vytváří duplicitní Jira issues pro stejné deadlines
- Nikdy nedokončí scan čistě

### Řešení
1. **Dedup v brain_create_issue** — před vytvořením issue zkontrolovat zda podobný issue neexistuje (brain_search_issues)
2. **Deadline scan context** — přidat do promptu: "Nejprve zkontroluj existující issues, nevytvářej duplikáty"
3. **Result z brain_create_issue** — přidat klíč vytvořeného issue aby agent věděl co už udělal

## Root cause 3: Deadline scan blokuje GPU pro chat

Scan běží na `local_standard` tier (stejná GPU jako chat). Když scan běží 76–306s, chat musí čekat.

### Řešení
1. **Nižší priorita pro scheduled tasks** — `X-Ollama-Priority: 5` (ne 0/CRITICAL)
2. **Nebo přesunout deadline scan na OpenRouter** (viz strategické rozhodnutí o routingu)
3. **Rate limit** — max 1 deadline scan za 10 minut, ne 5

---

## Dopad na GPU

7 scanů × ~150s průměr = **~17.5 minut GPU blokace** za 13 minut reálného času. GPU je **100% vytížena deadline scany**, chat odpovědi čekají v queue.

## Soubory

- `backend/server/.../service/background/BackgroundEngine.kt` — scheduler loop, dispatch, dedup (řádky ~723-770, ~1560-1630)
- `backend/service-orchestrator/app/background/handler.py` — execution, loop detection
- `backend/server/.../service/deadline/DeadlineTrackerService.kt` — utility

## Ověření

1. Po restartu → max 1 deadline scan za 5 minut
2. Žádné duplicitní Jira issues z deadline scanu
3. Chat odpověď do 30s i když deadline scan běží (priorita/routing)
