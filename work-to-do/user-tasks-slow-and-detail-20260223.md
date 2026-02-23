# User Tasks — zbývající drobnosti

**Severity**: LOW
**Date**: 2026-02-23

### 1. Anglické user tasky — DONE
- Server labels opraveny na češtinu
- Orchestrátor LLM: potvrzeno — `ask_user` otázky se generují česky
  (respond node system prompt je celý česky, včetně příkladů ask_user otázek)

### 2. Kalendář — pokročilé funkce
- ~~**Staré emaily naplánované na dnešek**~~ → DONE: KbResultRouter nyní kontroluje
  zda deadline je v minulosti (deadline_already_passed) — neprovede scheduling
- ~~**Prošlé SCHEDULED_TASK**~~ → DONE: BackgroundEngine eskaluje SCHEDULED_TASKy
  prošlé >24h jako urgentní USER_TASK
- **Kalendářní zobrazení** — přepínač seznam / denní / týdenní grid (future feature)
