# UI — Markdown renderer crash při recomposition

**Priorita**: MEDIUM
**Status**: RESOLVED (2026-02-27)

### Implementation summary

- Stabilized `message.text` with `remember(message.text)` before passing to `Markdown()`
- Prevents AST/text mismatch during recomposition triggered by global events
- Varianta A (quick fix) — `remember` with key on text content

---

## Problém

`StringIndexOutOfBoundsException: Range [312, 334) out of bounds for length 318` v
`mikepenz/multiplatform-markdown-renderer:0.38.0` při renderování markdown v chatu.

Crash nastává při recomposition vyvolaném `QualificationProgress` global eventy —
markdown AST parser má node s rozsahem, který přesahuje délku textu.

## Stack trace (klíčové řádky)

```
ASTUtil.kt:18 — getTextInNode()
Extensions.kt:56 — getUnescapedTextInNode()
AnnotatedStringKtx.kt:294 — buildMarkdownAnnotatedString()
MarkdownParagraph.kt:23 — MarkdownParagraph()
→ MarkdownListItem → MarkdownOrderedList → Markdown()
```

## Pravděpodobná příčina

Text se změní (recomposition) zatímco AST strom z předchozího parse stále odkazuje
na staré offsety. Knihovna nerecalkuluje AST při změně textu — výsledek je
`substring()` mimo rozsah.

## Řešení

### Varianta A: Wrap Markdown v try-catch (rychlý fix)

```kotlin
// ChatMessageDisplay.kt:413
try {
    Markdown(content = message.text, ...)
} catch (e: StringIndexOutOfBoundsException) {
    // Fallback na plain text při selhání parseru
    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
}
```

Pozor: Compose `@Composable` funkce nelze wrappovat v try-catch přímo.
Alternativa — `remember` s key na text:

```kotlin
val safeContent = remember(message.text) { message.text }
Markdown(content = safeContent, ...)
```

### Varianta B: Upgrade knihovny

Zkontrolovat zda novější verze (0.39+) fixuje tento bug.
GitHub: https://github.com/mikepenz/multiplatform-markdown-renderer/issues

### Varianta C: Sanitize markdown před renderováním

Odfiltrovat problematické sekvence nebo oříznout text na bezpečnou délku.

## Soubory

- `shared/ui-common/.../ChatMessageDisplay.kt:413` — `Markdown()` volání
- `shared/ui-common/build.gradle.kts:71` — verze knihovny `0.38.0`
