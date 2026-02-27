# Refactor: Odebrat záložku "Prostředí" z Nastavení

**Priority**: MEDIUM
**Area**: UI → Settings → Prostředí (EnvironmentSettings)

## Problem

Záložka "Prostředí" v Nastavení je zbytečná — veškerá funkcionalita (CRUD, detail,
komponenty, konfigurace) bude v Environment Manageru. Tato záložka je duplicitní
a navíc nefunkční (nejde se vrátit na seznam ze zobrazení detailu).

## Řešení

Odebrat záložku "Prostředí" ze SettingsScreen sidebaru. Veškerá správa prostředí
se provádí přes Environment Manager (samostatná obrazovka).

## Files

- `shared/ui-common/.../screens/settings/SettingsScreen.kt` — odebrat "Prostředí" z navigace
- `shared/ui-common/.../settings/sections/EnvironmentSettings.kt` — smazat soubor
