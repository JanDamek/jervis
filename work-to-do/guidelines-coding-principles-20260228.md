# Guidelines — přidat coding principles (idiomatický kód, SOLID, IF-LESS)

**Priorita**: HIGH
**Status**: DONE

---

## Problém

Současný `CodingGuidelinesDto` má jen mechanická pravidla (maxFileLines, regex patterny, naming conventions). Chybí pole pro **filosofická/architektonická pravidla** — jak psát kód, jaký styl, jaké principy dodržovat.

Coding agenti potřebují jasné instrukce typu "piš idiomatický Kotlin, ne Javu v Kotlinu", ale nemají kam je dostat kromě `general.customRules`, což je sémanticky špatně.

## Řešení

### 1. Nové pole v CodingGuidelinesDto

```kotlin
// shared/common-dto/.../guidelines/GuidelinesDtos.kt
@Serializable
data class CodingGuidelinesDto(
    // ... existující pole ...
    val principles: List<String> = emptyList(),  // NOVÉ — volný text, jedno pravidlo na řádek
)
```

Merge logika v `GuidelinesDocument.kt`:
```kotlin
// Lists se concatenují (stejně jako forbiddenPatterns)
principles = base.principles + override.principles,
```

### 2. UI — nové pole v CodingSection

V `GuidelinesSettings.kt` přidat do sekce "Coding pravidla":

```
Coding principy (jeden na řádek)
┌────────────────────────────────────────────────────┐
│ Idiomatic Kotlin — never Java-style in Kotlin      │
│ SOLID principles — SRP, OCP, LSP, ISP, DIP         │
│ IF-LESS programming — sealed classes, when, ...     │
│ Self-descriptive code — no inline comments          │
│ ...                                                 │
└────────────────────────────────────────────────────┘
```

Multiline `JOutlinedTextField`, jeden princip na řádek, uložit jako `List<String>`.

### 3. Defaultní globální pravidla

V `GuidelinesDocument.defaultGlobal()` nastavit výchozí `coding.principles`:

```kotlin
coding = CodingGuidelinesDto(
    maxFileLines = 500,
    namingConventions = mapOf(
        "kotlin" to "camelCase",
        "python" to "snake_case",
    ),
    principles = listOf(
        // === Kotlin styl ===
        "Idiomatic Kotlin — NEVER Java-style code in Kotlin. Use data classes, sealed classes, extension functions, scope functions (let, run, apply, also), destructuring, sequences.",
        "Prefer expression bodies over block bodies where the expression fits on one line.",
        "Prefer val over var. Prefer immutable collections (List, Map, Set) over mutable.",
        "Use Kotlin stdlib — filterNotNull, mapNotNull, groupBy, associate, partition, zip — instead of manual loops.",

        // === SOLID ===
        "SOLID: Single Responsibility — every class/function does ONE thing. Max 500 lines per file, max 30 lines per function.",
        "SOLID: Open/Closed — extend via interfaces/sealed classes, not by modifying existing code.",
        "SOLID: Liskov Substitution — subtypes must be substitutable for base types without surprises.",
        "SOLID: Interface Segregation — small, focused interfaces. No god-interfaces.",
        "SOLID: Dependency Inversion — depend on abstractions (interfaces), inject via constructor.",

        // === IF-LESS programming ===
        "IF-LESS programming — prefer polymorphism, sealed class + when (exhaustive), strategy pattern, map lookups over if/else chains.",
        "Replace boolean flags with sealed class/enum variants.",
        "Replace nested if/else with early returns (guard clauses) or when expressions.",
        "Never use if/else for type dispatch — use sealed class + when or visitor pattern.",

        // === Komentáře a dokumentace ===
        "Self-descriptive code — NO inline comments inside function bodies. If code needs a comment, rename the variable/function to be self-explanatory.",
        "KDoc only on public API — classes, public functions, interfaces. Describe WHAT and WHY, never HOW.",
        "No TODO comments in committed code — create a task/issue instead.",

        // === Clean Code ===
        "Functions: max 3 parameters. More → use data class as parameter object.",
        "No magic numbers/strings — use named constants or enums.",
        "Fail fast — validate at boundaries, throw early, never silently swallow errors.",
        "Prefer composition over inheritance.",
        "DRY but not premature — extract only when pattern repeats 3+ times.",
    ),
),
```

### 4. Python — format do promptu

V `guidelines_resolver.py` rozšířit `format_guidelines_for_prompt()`:

```python
# V coding sekci, za existující pravidla:
if coding.get("principles"):
    coding_lines.append("- **Coding principy:**")
    for principle in coding["principles"]:
        coding_lines.append(f"  - {principle}")
```

V `format_guidelines_for_coding_agent()`:

```python
if coding.get("principles"):
    lines.append("## Coding Principles (MUST FOLLOW)")
    lines.append("")
    for principle in coding["principles"]:
        lines.append(f"- {principle}")
    lines.append("")
```

Toto je **nejdůležitější** — coding agent MUSÍ tyto principy vidět prominentně v systém promptu, ideálně na začátku.

### 5. Existující `maxFunctionLines` — nastavit default

V `defaultGlobal()` přidat:
```kotlin
maxFunctionLines = 30,
```

## Soubory

- `shared/common-dto/.../guidelines/GuidelinesDtos.kt` — nové pole `principles`
- `backend/server/.../entity/GuidelinesDocument.kt` — merge logika + defaultGlobal
- `shared/ui-common/.../settings/sections/GuidelinesSettings.kt` — UI pro principles
- `backend/service-orchestrator/app/context/guidelines_resolver.py` — format do promptu

### 6. Chat tool — aktualizovat description pro `update_guideline`

V `backend/service-orchestrator/app/chat/tools.py` → `TOOL_UPDATE_GUIDELINE`:

Aktuální description nezmiňuje `principles`. Rozšířit na:

```python
"description": (
    "Aktualizuj pravidla pro daný scope a kategorii. "
    "Scope: GLOBAL, CLIENT, PROJECT. "
    "Kategorie: coding, git, review, communication, approval, general. "
    "Pro coding kategorie: principles (list stringů = coding principy), "
    "maxFileLines, maxFunctionLines, forbiddenPatterns, requiredPatterns, namingConventions. "
    "Příklad: 'chci aby kód byl idiomatický Kotlin' → "
    "update_guideline(scope=GLOBAL, category=coding, "
    "rules={principles: ['Idiomatic Kotlin — NEVER Java-style code in Kotlin']})"
),
```

Chat MUSÍ umět:
- `"nastav pravidlo: piš idiomatický Kotlin"` → `update_guideline(scope=GLOBAL, category=coding, rules={principles: ["Idiomatic Kotlin — ..."]})`
- `"přidej coding princip pro projekt X: no magic numbers"` → `update_guideline(scope=PROJECT, category=coding, rules={principles: ["No magic numbers/strings — use named constants or enums"]}, project_id=...)`

Klíčové: `rules.principles` se na serveru **concatenuje** s existujícími (merge logika), takže chat může přidávat po jednom principu.

### 7. Chat tool — `get_guidelines` rozšířit response

Aktuální `get_guidelines` vrací raw JSON. LLM to zvládne přečíst, ale pro lepší UX by response měl obsahovat i `principles` pole v čitelné formě. Toto funguje automaticky (principles je součást DTO), jen ověřit.

## Soubory

- `shared/common-dto/.../guidelines/GuidelinesDtos.kt` — nové pole `principles`
- `backend/server/.../entity/GuidelinesDocument.kt` — merge logika + defaultGlobal
- `shared/ui-common/.../settings/sections/GuidelinesSettings.kt` — UI pro principles
- `backend/service-orchestrator/app/context/guidelines_resolver.py` — format do promptu
- `backend/service-orchestrator/app/chat/tools.py` — update_guideline description

## Ověření

1. V UI Pravidla → Globální → Coding pravidla → vidím nové pole "Coding principy" s předvyplněnými defaulty
2. Coding agent při spuštění vidí v systém promptu sekci "Coding Principles (MUST FOLLOW)" se všemi principy
3. CLIENT/PROJECT scope může přidat další principy (merge = concatenate)
4. CLIENT/PROJECT scope NEMŮŽE odebrat globální principy (merge je additivní)
5. V chatu: "nastav pravidlo: piš idiomatický Kotlin" → agent zavolá update_guideline s principles
6. V chatu: "jaká jsou aktuální coding pravidla?" → agent zavolá get_guidelines a zobrazí i principles
