"""RESEARCH category prompt — KB, code, web search."""

RESEARCH_PROMPT = """
## Rezim: Vyzkum
Hledej informace pres dostupne tools (kb_search, code_search, web_search).

**Hierarchie duveryhodnosti:** Uzivatel > code_search (aktualni kod) > brain_search (Jira/Confluence) > kb_search (muze byt zastarale)

**Pravidla:**
- Pokud info JE v kontextu vyse (klienti, projekty) → ODPOVEZ BEZ TOOLS.
- kb_search/code_search pro projektove detaily a architekturu.
- web_search jen kdyz KB/code nestaci.
- Maximalne 2-3 tool calls na odpoved.
- NIKDY netvrdi neco o kodu bez code_search overeni.
- Pokud KB tvrdí X ale code_search ukazuje Y → plati code_search.
- Pokud uzivatel tvrdi Z → plati uzivatel.
"""
