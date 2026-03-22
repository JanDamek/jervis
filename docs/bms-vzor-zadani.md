# BMS - Vzorové zadání pro JERVIS orchestrátor

> Tento dokument definuje referenční scénář pro ladění JERVIS orchestrátoru.
> BMS Processing je první projekt, na kterém se JERVIS naučí správně řídit coding agenta.

---

## 1. Přehled projektu

**BMS** (Brokerage Management System) je processing aplikace pro Commerzbank.
Její modul `bms-processing` ve skriptech kopíruje chování `oldBMS` — cílem je
dosáhnout naprosto identických výstupů, ale na moderní platformě (Kotlin, Spring Boot 3,
R2DBC, PostgreSQL, reactive Coroutines/Flow).

### Klíčové vlastnosti
- **Script-driven processing** — logika je v Kotlin skriptech uložených v DB, ne v kódu
- **Chain** = definice celé aplikace (sekvence skriptů, řazení, vstupy/výstupy)
- **processing_collections** = JSONB key-value store ("blackboard") sdílený mezi kroky chainu
- **import_raw** = vstupní tabulka (patří jinému modulu, NEMĚNÍME ji)
- **Multi-pod** = processing instance běží jako K8s deployment s N replikami, synchronizované přes PostgreSQL LISTEN/NOTIFY

### Moduly (scope práce)
| Modul | Měníme? | Popis |
|-------|---------|-------|
| `bms-processing` | **ANO** | Jádro — scripty, chain engine, reactive Flow pipeline |
| `bms-common` | **ANO** | Sdílené utility, DTO, DB entity |
| `bms-rpc-api` | NE | Shared interfaces (generuje se) |
| `bms-script-designer` | NE | Compose Multiplatform UI pro editaci skriptů |
| `bms-processing-plugin` | NE (budoucnost) | IntelliJ plugin pro definici skriptů |
| `bms-middleware` | NE | Trade file import API |
| `bms-web` | NE | React frontend |

---

## 2. Architektura

### Větev
- **Poslední vyvíjená**: `feature/TPT-61286-reactive-coroutines-flow`
- Obsahuje reactive Flow engine (PoC stav)

### Data flow
```
import_raw (vstup, cizí tabulka)
    ↓
Script 1 (chain step) → context.loadSync("import_raw", filter)
    ↓ zapisuje do
processing_collections["fx_trades"] (JSONB)
    ↓
Script 2 → čte z processing_collections, transformuje
    ↓
Script N → finální výstup do processing_collections
    ↓
(budoucnost) → zápis do cílové tabulky
```

### API Mapping (oldBMS → bms-processing)
| oldBMS | bms-processing |
|--------|---------------|
| data binding | `context.loadSync()` |
| `getUserData(key)` | `context.loadSync("collection", filter)` |
| `setUserData(key, value)` | `context.saveSync("collection", searchKeys, data)` |
| `__rule_output__` | Chain control flow (OK/REJECT) |
| `logger.info()` | `context.log()` |
| `WallstreetFXMain.xml` | Chain definice v `chains` tabulce (stepsJson) |
| `AgreementRowFX` | `processing_collections` s `collectionName="fx_trades"` |

### Multi-pod synchronizace
```
Pod 1 ──┐
Pod 2 ──┤── PostgreSQL LISTEN/NOTIFY ──→ koordinace
Pod 3 ──┘
         │
         ├── NOTIFY bms_chain_start (chainName, executionId)
         ├── NOTIFY bms_script_complete (scriptId, podId)
         ├── NOTIFY bms_chain_sync (barrier pro další step)
         └── NOTIFY bms_chain_complete (executionId, status)
```

### DB změny
- Vždy přes **Flyway migrace** (inkrementální, V001__xxx.sql atd.)
- Nikdy přímý ALTER/CREATE mimo Flyway

---

## 3. Environment

- **K8s namespace**: `bms-poc` (JERVIS environment ID: `69b53898c1a96c4690c81517`)
- **PostgreSQL**: v rámci bms-poc namespace
- **Coding agent**: běží na stejném K8s clusteru → `kubectl` funguje přímo
- **JERVIS MCP tools**: coding agent má přístup ke všem tools včetně environment management

---

## 4. Pravidla pro coding agenta

### MUSÍ
1. Pracovat **výhradně** v `bms-processing` a `bms-common` modulech
2. Používat **Flyway migrace** pro jakékoliv DB změny
3. Držet se **reactive Flow** architektury (Coroutines, R2DBC, Flow)
4. Výsledky skriptů musí být **identické** s oldBMS
5. Commitovat na větev `feature/TPT-61286-reactive-coroutines-flow`
6. Nasazovat přes JERVIS environment tools (ne kubectl přímo)
7. Testovat výstupy porovnáním s oldBMS referenčními daty

### NESMÍ
1. Měnit `import_raw` tabulku ani její scripty
2. Měnit moduly mimo scope (bms-web, bms-middleware, bms-rpc-api)
3. Měnit chain/script definice v DB přímo (jen přes Flyway)
4. Používat blocking I/O (JDBC, Thread.sleep, blocking collections)
5. Vytvářet nové tabulky mimo `processing_collections` pattern
6. Měnit API endpointy nebo GraphQL schema

---

## 5. Kontrolní body pro JERVIS orchestrátor

### Paměťový graf — co si JERVIS musí pamatovat
- [ ] Scope: jen `bms-processing` + `bms-common`
- [ ] Větev: `feature/TPT-61286-reactive-coroutines-flow`
- [ ] DB změny = Flyway only
- [ ] `import_raw` = READ ONLY
- [ ] Reactive only (Flow, R2DBC, Coroutines)
- [ ] Environment: `bms-poc` namespace
- [ ] oldBMS referenční data = zdroj pravdy pro validaci

### Myšlenkový graf — navigační body
- [ ] Před změnou kódu → zkontrolovat zda je v scope modulů
- [ ] Před DB změnou → vytvořit Flyway migraci
- [ ] Po implementaci → porovnat výstup s oldBMS
- [ ] Při multi-pod → ověřit LISTEN/NOTIFY synchronizaci
- [ ] Při novém scriptu → ověřit context API kompatibilitu
- [ ] Při chainu → ověřit step ordering a collection flow

### Validace výstupu
```
1. Spustit chain přes: POST /api/engine/run/{chainName}?params=...
2. Přečíst processing_collections pro daný chain
3. Porovnat s oldBMS referenčními daty (KB obsahuje reference)
4. Diff musí být prázdný (nebo jen formátovací rozdíly)
```

---

## 6. Typický task pro coding agenta

```
Zadání: "Implementuj script STEP_03_CURRENCY_CONVERSION v chainu FX_PROCESSING"

Agent musí:
1. Přečíst definici chainu FX_PROCESSING z DB (chains tabulka)
2. Najít oldBMS ekvivalent v KB (oldBms-analysis dokumenty)
3. Implementovat script v Kotlin (reactive Flow)
4. Vytvořit Flyway migraci pro INSERT do scripts tabulky
5. Přidat step do chain definice (Flyway UPDATE)
6. Nasadit na bms-poc environment
7. Spustit chain a porovnat výstup s oldBMS
8. Pokud diff → opravit a znovu nasadit
```

---

## 7. Budoucí rozšíření

- **IntelliJ Plugin**: definice skriptů pro jakoukoliv další aplikaci (ne jen BMS)
- **Chain = definice celé aplikace**: jeden processing engine, různé chainy pro různé klienty
- **Finální zápis**: z processing_collections do cílových tabulek (není součást processingu)
- **Myšlenka**: jeden velmi výkonný nástroj nad PostgreSQL, kde scriptováním řešíme co processing má udělat, zpracování extrémního množství vstupních dat
