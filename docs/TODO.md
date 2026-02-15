# TODO – Plánované Features a Vylepšení

Tento dokument obsahuje seznam plánovaných features, vylepšení a refaktoringů,
které budou implementovány jako separate tickety.

## Vyřešené

### ~~Blikání overlay "Spojení ztraceno" při restartu serveru~~ ✓

**Problém:** Při restartu serverové aplikace se overlay "Spojení ztraceno" neustále problikával
místo aby zůstal stabilně viditelný. Hlavní obrazovka se opakovaně na zlomek sekundy zobrazila
jako by vše fungovalo, a pak se overlay znovu objevil.

**Příčina:** Server při restartu začal odpovídat na HTTP požadavky dříve, než byly RPC/WebSocket
služby plně připravené. Výsledek:
1. HTTP test (`GET /`) prošel → WebSocket se připojil → stav `Connected` → overlay zmizel
2. První RPC volání nebo stream okamžitě selhal → stav `Disconnected` → overlay se zobrazil
3. Retry s krátkým delay (1s) → celý cyklus se opakoval → blikání

**Řešení:**
- **RPC verifikace v `performConnect()`** – po vytvoření služeb se provede testovací RPC volání
  (`getAllClients()`) ještě předtím, než se stav přepne na `Connected`. Server, který odpovídá
  na HTTP ale nemá ready RPC, je zachycen v retry smyčce s backoff delay.
- **Zvýšení retry delay** – minimum 5s mezi pokusy (lineární nárůst: 5s, 10s, 15s, 20s, 25s, 30s cap)
- **5s cooldown před reconnectem** – při selhání streamu (`resilientFlow`) nebo heartbeatu
  se čeká 5s, než se spustí nový pokus o připojení.
- **Debounce overlay** – po úspěšném reconnectu se overlay skryje až po 2s stabilitní periodě.
  Pokud spojení padne během těch 2s, overlay zůstane viditelný.
- **Attempt counter** – `RpcConnectionManager` exportuje `reconnectAttempt` StateFlow,
  UI zobrazuje číslo pokusu v overlay.

**Soubory:** `RpcConnectionManager.kt`, `MainViewModel.kt`
