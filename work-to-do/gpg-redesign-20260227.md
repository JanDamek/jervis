# Redesign: GPG certifikáty — import ze systému, výběr místo ID

**Priority**: MEDIUM
**Area**: UI + Backend — GPG Certifikáty

## Problémy s aktuální implementací

### 1. GPG Certifikáty — "Klient" tab nemá význam
GPG klíče nejsou vázané na klienta. Jeden klíč se dá použít pro libovolného klienta
a vícekrát. Tab "Klient" (Tepsivo, MMB, Commerzbank...) na stránce GPG Certifikáty odebrat.

### 2. Ruční zadávání klíče jako string
Aktuální formulář (Key ID, Jméno, Email, Privátní klíč jako text) je špatný UX.
Aplikace musí umět importovat klíče ze systémového GPG keyring.

### 3. V klientovi "GPG Key ID" jako textový input
V ClientEditForm je pole "GPG Key ID" — má být dropdown výběr z nahraných klíčů na serveru,
ne ruční zadání ID.

## Požadovaný stav

### GPG Certifikáty stránka
- **Bez klient tabů** — globální seznam nahraných GPG klíčů
- **Import ze systému**: aplikace zavolá lokální `gpg --list-secret-keys --keyid-format long`
  na klientském zařízení, zobrazí výběr klíčů, exportuje vybraný (`gpg --export-secret-keys`)
  a nahraje na server
- Seznam nahraných klíčů: fingerprint, jméno, email, datum expirace
- Mazání klíčů

### ClientEditForm — GPG podpis commitů
- Checkbox "GPG podpis commitů" (existující)
- **Dropdown výběr** z nahraných klíčů na serveru (místo textového "GPG Key ID")
- Zobrazení: "jméno <email> (fingerprint)" v dropdown položkách
- Klíč je sdílený — více klientů může používat stejný klíč

## Technické poznámky

- Import ze systémového GPG vyžaduje platform-specific kód (Desktop: Process("gpg"),
  Android/iOS: nemusí být podporováno — jen Desktop)
- Server už má CRUD pro GPG klíče — potřeba přidat RPC endpoint pro listing klíčů
  pro dropdown
- `expect/actual` pro GPG import (Desktop only, na mobilu zobrazit info že import
  je dostupný pouze z desktopu)

## Soubory

- `shared/ui-common/.../settings/sections/GpgSettings.kt` — odebrat klient taby, přidat import
- `shared/ui-common/.../settings/sections/ClientEditForm.kt` — dropdown místo text input
- Backend: RPC endpoint pro list GPG klíčů (pro dropdown)
- Platform expect/actual pro `gpg` CLI volání
