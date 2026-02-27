# Bug: Nastavení Prostředí — nejde se vrátit na seznam

**Priority**: HIGH
**Area**: UI → Settings → Prostředí (EnvironmentSettings)

## Problem

Po kliknutí na prostředí v seznamu se zobrazí detail (např. "BMS-Commerzbank"),
ale nelze se vrátit zpět na seznam prostředí. Chybí navigace zpět / seznam není
dostupný z detailu.

Uživatel vidí detail prostředí se sekcemi Základní informace, Komponenty, Konfigurace,
ale nemůže procházet ostatní prostředí.

## Screenshot

Detail prostředí "BMS-Commerzbank" (namespace: bms-commerzbank, stav: Běží,
1 infra komponenta, 0 projektů). Šipka zpět v horní liště pravděpodobně
nefunguje nebo chybí list/detail navigace.

## Expected Behavior

- Klik na šipku zpět → návrat na seznam prostředí
- Nebo: list-detail layout kde seznam zůstává viditelný (expanded mode)

## Files

- `shared/ui-common/.../settings/sections/EnvironmentSettings.kt`
