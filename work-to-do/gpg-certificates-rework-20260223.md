# GPG Certifikáty: přepracovat vazbu na prostředí + distribuce do agentů

> **Datum:** 2026-02-23
> **Zjištěno:** UI review — GPG certifikáty nejsou provázány s prostředím, chybí distribuce do K8s
> **Koncept:** Desktop UI načte GPG klíč → uloží do MongoDB → K8s agent ho použije pro podepisování commitů

---

## 1. Aktuální stav

### Co funguje (50% hotovo)

- **Upload UI** (`GpgCertificateSettings.kt`): Uživatel může nahrát GPG privátní klíč per klient
- **MongoDB storage** (`gpg_certificates` kolekce): Klíč ID, jméno, email, privátní klíč, passphrase
- **REST endpoint** (`/internal/gpg-key/{clientId}`): Orchestrátor může stáhnout klíč
- **Client settings** (`ClientDto.gitCommitGpgSign`, `gitCommitGpgKeyId`): Přepínač "GPG podpis commitů" + Key ID

### Co NEFUNGUJE (chybí distribuce)

- **Orchestrátor NEVOLÁ** `/internal/gpg-key/{clientId}` při vytváření K8s Job
- **K8s Job nedostane** žádné GPG proměnné (`GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `GPG_KEY_ID`)
- **Agent entrypoint** neimportuje GPG klíč, nekonfiguruje `git config commit.gpgsign`
- **Klíč se nikdy nedostane** z MongoDB do K8s podu → podepisování commitů nefunguje

---

## 2. Koncepční problém: Vazba na klienta vs prostředí

### Aktuální model (špatně)

GPG certifikát je vázán na **klienta** (`clientId`). Ale:
- Klient může mít více prostředí (dev, staging, prod)
- Každé prostředí má vlastní K8s namespace
- GPG klíč by měl být vázán na **prostředí** (kde agent běží), ne na klienta

### Správný model

**GPG certifikát → uložen v MongoDB → provázán s prostředím**

Účel stránky "GPG Certifikáty":
1. **Desktop UI načte GPG klíč** ze systému (nebo uživatel vloží ručně)
2. **Uloží do MongoDB** — duplikace klíče z desktopu do serveru
3. **Při vytvoření K8s Job** — orchestrátor stáhne klíč a předá do podu
4. **Agent v K8s** importuje klíč a podepisuje commity

**Nastavení PODPISU** (zda podepisovat, jakým klíčem) je pak **u klienta/projektu**:
- `Client.gitCommitGpgSign = true/false`
- `Client.gitCommitGpgKeyId = "ABCDEF1234567890"` — výběr z nahraných certifikátů

---

## 3. Co je potřeba implementovat

### 3.1 UI: Vazba na prostředí místo klienta (P1)

**Soubor:** `GpgCertificateSettings.kt`

Aktuálně filtruje podle klienta. Mělo by:
- Zobrazit certifikáty per **prostředí** (EnvironmentDto)
- Nebo: certifikáty jsou globální (celý server), výběr je pak u klienta/projektu

### 3.2 Distribuce klíče do K8s Job (P0 — bez toho GPG nefunguje)

**Soubor:** `backend/service-orchestrator/app/agents/job_runner.py` (`_build_job_manifest()`)

Při vytváření K8s Job:
1. Zkontrolovat `client.gitCommitGpgSign`
2. Pokud ano → `GET /internal/gpg-key/{clientId}`
3. Vytvořit K8s Secret s privátním klíčem
4. Mountnout do podu jako env vars: `GPG_PRIVATE_KEY`, `GPG_KEY_ID`, `GPG_PASSPHRASE`

### 3.3 Agent entrypoint: GPG import (P0)

**Soubory:** `backend/shared-entrypoints/entrypoint-job.sh`, `backend/service-claude/entrypoint-job.sh`

Při startu podu:
```bash
if [ -n "$GPG_PRIVATE_KEY" ]; then
    echo "$GPG_PRIVATE_KEY" | gpg --batch --import
    git config --global commit.gpgsign true
    git config --global user.signingkey "$GPG_KEY_ID"
    if [ -n "$GPG_PASSPHRASE" ]; then
        echo "$GPG_PASSPHRASE" | gpg-preset-passphrase ...
    fi
fi
```

### 3.4 Client settings: Výběr certifikátu z dropdown (P1)

**Soubor:** `ClientsSharedHelpers.kt` (`GitCommitConfigFields`)

Aktuálně: `gpgKeyId` je volný textový field. Mělo by:
- Dropdown s nahranými certifikáty (z GPG Certifikáty stránky)
- Zobrazit: Key ID + jméno + email

---

## 4. Relevantní soubory

| Soubor | Řádky | Co |
|--------|-------|----|
| `shared/ui-common/.../sections/GpgCertificateSettings.kt` | celý | Upload UI — přepracovat vazbu |
| `shared/common-dto/.../coding/GpgCertificateDto.kt` | celý | DTOs — OK |
| `shared/common-api/.../IGpgCertificateService.kt` | celý | RPC interface — OK |
| `backend/server/.../rpc/GpgCertificateRpcImpl.kt` | 60-69 | `getActiveKey()` — interní metoda, funguje |
| `backend/server/.../rpc/KtorRpcServer.kt` | ~1455-1490 | `/internal/gpg-key/{clientId}` — endpoint existuje, nikdo nevolá |
| `backend/server/.../entity/GpgCertificateDocument.kt` | celý | MongoDB entity — OK |
| `backend/service-orchestrator/app/agents/job_runner.py` | 334-435 | `_build_job_manifest()` — chybí GPG vars |
| `backend/shared-entrypoints/entrypoint-job.sh` | celý | Agent entrypoint — chybí GPG import |
| `shared/ui-common/.../sections/ClientsSharedHelpers.kt` | 40-138 | `GitCommitConfigFields` — gpgKeyId jako text, měl by být dropdown |
| `shared/common-dto/.../ClientDto.kt` | — | `gitCommitGpgSign`, `gitCommitGpgKeyId` |
| `docs/architecture.md` | §12 | Coding agents — chybí GPG dokumentace |
