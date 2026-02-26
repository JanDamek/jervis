# BUG: Kompilační chyba v EPIC kódu — FilteringRulesService

**Datum:** 2026-02-26
**Priorita:** HIGH (blokuje server build)
**Větev:** master (merge z `claude/jervis-epic-plan-mWG9w`)

---

## Chyba

`FilteringRulesService.kt:102` — `maxByDescending` neexistuje v Kotlin stdlib.
Správná funkce je `maxBy` (non-nullable, list je ověřen jako neprázdný)
nebo `maxByOrNull` (nullable).

## Kompilační výstup

```
e: FilteringRulesService.kt:102:14 Unresolved reference 'maxByDescending'.
e: FilteringRulesService.kt:102:32 Unresolved reference 'it'.
e: FilteringRulesService.kt:103:14 Cannot infer type for type parameter 'T'.
e: FilteringRulesService.kt:103:21 Cannot infer type for type parameter 'T'.
e: FilteringRulesService.kt:105:49 Unresolved reference 'id'.
e: FilteringRulesService.kt:105:68 Unresolved reference 'action'.
e: FilteringRulesService.kt:109:15 Unresolved reference 'action'.
```

## Soubor

`backend/server/src/main/kotlin/com/jervis/service/filtering/FilteringRulesService.kt` řádek 100–109

## Navrhovaná oprava

```kotlin
// Před (nefunkční):
return matchingRules
    .maxByDescending { it.action.ordinal }
    .also { rule ->
        logger.debug {
            "FILTER_MATCH: rule=${rule?.id} action=${rule?.action} " +
                "source=$sourceType subject=${subject.take(50)}"
        }
    }
    ?.action

// Po (funkční):
val winner = matchingRules.maxBy { it.action.ordinal }
logger.debug {
    "FILTER_MATCH: rule=${winner.id} action=${winner.action} " +
        "source=$sourceType subject=${subject.take(50)}"
}
return winner.action
```

## Kontext

- `matchingRules` je `List<FilteringRule>`, emptiness je zkontrolován na řádku 98
- `maxBy` je safe (non-nullable) protože list je neprázdný
- Alternativně `maxByOrNull` + `?.action` pokud preferujete defenzivní styl

## Dopad

- Server se nezkompiluje → nelze nasadit
- Orchestrátor + KB nasazeny OK (Python, bez Kotlin kódu)
- Stávající server běží (starý build)
