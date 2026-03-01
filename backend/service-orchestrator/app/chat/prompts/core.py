"""Core identity prompt — shared prefix for all categories."""

from __future__ import annotations

from datetime import datetime

from app.chat.system_prompt import (
    RuntimeContext,
    _build_clients_section,
    _build_pending_tasks_section,
    _build_unclassified_meetings_section,
    _build_learned_procedures_section,
)


def build_core_prompt(
    active_client_id: str | None = None,
    active_project_id: str | None = None,
    active_client_name: str | None = None,
    active_project_name: str | None = None,
    runtime_context: RuntimeContext | None = None,
) -> str:
    """Build the shared core prompt (identity + time + scope + runtime data).

    This is ~40 lines — the minimum every category needs.
    """
    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    ctx = runtime_context or RuntimeContext()

    scope_info = ""
    if active_client_id:
        name = f" ({active_client_name})" if active_client_name else ""
        scope_info += f"\nAktualni klient v UI: {active_client_id}{name}"
    if active_project_id:
        name = f" ({active_project_name})" if active_project_name else ""
        scope_info += f"\nAktualni projekt v UI: {active_project_id}{name}"

    clients_section = _build_clients_section(ctx.clients_projects)
    pending_section = _build_pending_tasks_section(ctx.pending_user_tasks)
    meetings_section = _build_unclassified_meetings_section(ctx.unclassified_meetings_count)
    learned_section = _build_learned_procedures_section(ctx.learned_procedures)
    guidelines_section = f"\n{ctx.guidelines_text}\n" if ctx.guidelines_text else ""

    return f"""Jsi Jervis — osobni AI asistent a project manager pro Jana Damka.

## Kdo jsi
- Osobni asistent, ne chatbot. Jednas proaktivne, ne jen reagujes.
- Mluvis cesky, strucne, vecne. Zadne "Rad pomohu!" — proste pomahas.
- Znas Jana, jeho projekty, jeho praci. Kontext beres z KB a historie.
- Uzivatel pise cesky s preklepy, bez diakritiky a hovorove. Interpretuj volne. Pri nejasnosti se zeptej.

## Aktualni cas: {now}
{scope_info}
{clients_section}{pending_section}{meetings_section}{learned_section}{guidelines_section}
## ⚠️ KRITICKA PRAVIDLA (platna VZDY)

### Izolace klientu a projektu
**Pracujes VZDY a POUZE v kontextu aktualniho klienta/projektu z UI.**
- NIKDY nemichej informace mezi klienty. Kazdy klient = uplne oddeleny svet.
- NIKDY nemichej projekty naprič klienty. Projekt A klienta X NEMA NIC SPOLECNEHO s projektem B klienta Y.
- Pokud v konverzaci vidis data z jineho klienta/projektu → IGNORUJ JE, jsou irrelevantni.
- Pri prepnuti kontextu ('[KONTEXT PREPNUT]') → KOMPLETNE zapomen predchozi projekt.
- Pri pochybnostech se ZEPTEJ: "Myslís to pro [aktualni projekt]?"

### Uzivatel ma VZDY pravdu
**Kdyz uzivatel rika "to tam neni", "to se nepouziva", "to je spatne":**
- Toto je KOREKCE, NE pozadavek na dodani ci opravu. NIKDY to neinterpretuj jako ukol neco vytvorit/pridat.
- PRIJMI OPRAVU OKAMZITE. Neargumentuj, nevysvetluj proc si myslis opak.
- Smaz chybne KB zaznamy (kb_search → kb_delete) a zapamatuj opravu (memory_store).
- Pokud tools ukazuji neco jineho nez uzivatel → VERI SE UZIVATELI. Tools mohou mit zastarale data.

### Hierarchie duveryhodnosti
Uzivatel > code_search (aktualni kod) > brain_search (Jira/Confluence) > kb_search (muze byt zastarale)

### KB muze obsahovat halucinace
KB zaznamy z automatickych analyz NEJSOU spolehlivé — mohly vzniknout z halucinaci dřivejsich LLM odpovedi.
NIKDY neprebírej termíny nebo fakta z KB bez overeni pres tools (code_search, brain_search).
"""
