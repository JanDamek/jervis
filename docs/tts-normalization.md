# TTS Normalization — SSOT

**Status:** Production (2026-04-23)

XTTS v2 synthesizes audio from text, but its tokenizer is not a full
text normalizer — acronyms, IDs, URLs, markdown and long sentences all
break pronunciation or, at worst, crash the CUDA kernel (186-char
tokenizer limit). Normalization is XTTS-local, runs on CPU, no LLM, no
cloud.

## Pipeline

Input raw text → `normalizer.py::normalize(...)` →
ordered pipeline:

1. **STRIP rules** — regex removals. Example: MongoObjectId
   `\b[0-9a-fA-F]{24}\b` plus `stripWrappingParens=true` deletes
   `(ID 68a33...)` as a whole, not just the hex.
2. **REPLACE rules** — regex substitutions.
   Example: URL → `odkaz`, path → `soubor`, email → `e-mail`,
   markdown bold `\*\*(.+?)\*\*` → inner text.
3. **ACRONYM rules** — case-insensitive word-boundary match,
   variants from `aliases`. Example: `BMS` / `bms` / `Bms` →
   `bé-em-es`.
4. **Number expansion** — `num2words` for standalone digits.
   `2026` → `dva tisíce dvacet šest`.
5. **Sentence split + word wrap** — sentence terminators
   (`.!?…`), then hard-wrap to ≤170 chars on word boundaries so
   XTTS tokenizer (hard limit 186) never overflows.

Output: `list[NormalizedLine]` where each line has `(text, lang)` —
`lang` is the `[CS]` / `[EN]` hint the XTTS worker uses to select
the phonemizer.

## Storage

Rules live in MongoDB collection `ttsRules`. One document holds all
three types:

```kotlin
data class TtsRuleDocument(
    val id: ObjectId,
    val type: TtsRuleType,              // ACRONYM / STRIP / REPLACE
    val language: String,                // "cs", "en", or "any"
    val scope: TtsRuleScope,             // GLOBAL / CLIENT / PROJECT
    val acronym: String? = null,         // ACRONYM
    val pronunciation: String? = null,   // ACRONYM
    val aliases: List<String>? = null,   // ACRONYM
    val pattern: String? = null,         // STRIP / REPLACE
    val description: String? = null,     // STRIP / REPLACE
    val stripWrappingParens: Boolean? = null,  // STRIP
    val replacement: String? = null,     // REPLACE
)
```

Scope precedence on lookup is **PROJECT > CLIENT > GLOBAL**. XTTS
receives the rules already sorted by precedence from
`ServerTtsRulesService.GetForScope`.

## Adding rules

Three paths, same backing service:

1. **Chat (orchestrator)** — say to Jervis: *"zapiš do TTS slovníku
   že BMS se čte bé-em-es pro klienta Commerzbank"*. Orchestrator
   tool `tts_rule_add_acronym` writes it.
2. **MCP (Claude)** — same tool family, exposed via
   `jervis-mcp` for programmatic management.
3. **UI Settings** — list/add/edit/delete with preview.

## Seed data

On first startup `TtsRuleSeeder` inserts a default set of **GLOBAL**
rules covering MongoObjectIds, UUIDs, long alnum blobs, URLs/paths/
emails, markdown, and common Czech + English IT acronyms. Re-runs
are idempotent (skips if any global rule already exists) so
operators can edit defaults without getting them overwritten.

## Key files

| File | Purpose |
|------|---------|
| `backend/server/.../tts/TtsRuleDocument.kt` | Mongo document |
| `backend/server/.../tts/TtsRuleRepository.kt` | Reactive repo |
| `backend/server/.../tts/TtsRuleService.kt` | Business logic + preview |
| `backend/server/.../tts/TtsRulesGrpcImpl.kt` | gRPC for XTTS pod |
| `backend/server/.../tts/TtsRuleRpcImpl.kt` | kRPC for Settings UI |
| `backend/server/.../tts/TtsRuleSeeder.kt` | Default rules on startup |
| `backend/service-tts/app/normalizer.py` | CPU-side pipeline |
| `backend/service-tts/app/rules_client.py` | gRPC client → server |
| `proto/jervis/server/tts_rules.proto` | Wire contract |
| `shared/common-dto/.../dto/tts/TtsRuleDtos.kt` | UI DTOs |
| `shared/common-api/.../service/tts/ITtsRuleService.kt` | kRPC iface |

## Design decisions (historical)

- **No LLM normalization.** An earlier iteration routed each TTS
  call through a small LLM over the router. It burned p40-1 GPU
  time, contended with KB/qualifier work during meetings, and was
  sensitive to prompt drift. Replaced with deterministic
  rule-based normalization on CPU. Off-box LLM stays for **content
  generation** (orchestrator, chat), not for **audio
  normalization**.
- **No pre-processing before the LLM.** Rejected pattern (see
  `feedback-no-quickfix.md`). The normalizer here is a **post-
  generation audio prep layer**, not input sanitation — semantically
  different concern.
- **Per-request rule fetch, no cache.** Simpler and safer than TTL
  cache; user-added acronyms apply on the very next TTS call.
  ~10-20 ms gRPC overhead is negligible against 2-5 s of synthesis.
- **Default `supportsStreaming = true` for OpenRouter models.** That
  flag was added for cloud chat routing, not for TTS — TTS never
  hits the router now.

## Future

- Orchestrator should **not emit raw IDs** in user-facing text (the
  STRIP rules are a belt-and-braces defense, not the primary
  control). Tracked as separate work — orchestrator system prompt
  + tool response shape, see
  `memory/project-voice-endpoints-kRPC-migration.md` area.
