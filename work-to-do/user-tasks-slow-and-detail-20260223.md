# Bug: User Tasks — zbývající drobnosti

**Severity**: LOW
**Date**: 2026-02-23

## Vyřešeno
- ~~CancellationException~~ — re-throw ve všech scope.launch blocích
- ~~Chat historie bez paginace~~ — `getChatHistory` nyní `takeLast(50)`
- ~~Chyba se nezobrazuje~~ — chatError StateFlow v detailu
- ~~Pomalé načítání seznamu~~ — lightweight DTO, text index, single query
- ~~Prázdný detail~~ — strukturovaná hlavička (state badge, datum, zdroj)
- ~~DONE tasky v kalendáři~~ — FilterChip toggle v SchedulerScreen

## Zbývá

### 1. Anglické user tasky
- Server labels opraveny na češtinu
- **K ověření**: orchestrátor LLM generuje `ask_user` otázky česky?

### 2. Kalendář — pokročilé funkce
- **Staré emaily naplánované na dnešek** — kvalifikátor by neměl plánovat staré emaily
- **Prošlé SCHEDULED_TASK** → eskalovat jako urgentní USER_TASK
- **Kalendářní zobrazení** — přepínač seznam / denní / týdenní grid
