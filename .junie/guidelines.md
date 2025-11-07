JERVIS – ARCHITEKTURA A PROGRAMOVACÍ PRAVIDLA
(verze pro vývoj i AI asistenty)

ÚČEL
Jervis je vícevrstvá Kotlin/Spring Boot platforma určená pro podporu softwarové architektury, řízení projektů, indexaci
kódu a dokumentů, a pro asistenci vývojářům při práci s daty, modely a automatizací procesů. Aplikace slouží nejen k
technické práci s architekturou softwaru, ale i jako inteligentní asistent pro obecné úlohy.

ZÁKLADNÍ ARCHITEKTURA
Aplikace je rozdělena do jasně definovaných částí:
• SERVER: centrální mozek aplikace, řídí všechny procesy, orchestruje agenty, plánovače, modely, RAG (vector store),
spravuje data, komunikaci a archiv.
• UI NÁSTROJE: jediný přístupový bod pro uživatele. V současnosti existuje pouze desktopová varianta, ale architektura
podporuje více UI rozhraní (např. web, mobilní).
Každé UI komunikuje se serverem přes API klienta. Desktop je tedy jeden z mnoha potenciálních UI nástrojů.
• COMMON-API: API kontrakt mezi serverem a desktop UI. Obsahuje Spring @HttpExchange interface definice pro komunikaci.
• COMMON-DTO: Kotlin Multiplatform modul se sdílenými DTO objekty pro desktop i mobilní aplikace.
• COMMON-SERVICES: modul určený výhradně pro interní komunikaci mezi službami a serverem. Používají jej pouze:
• service-ocr → server
• service-joern → server
• service-whisper → server
Tento modul nesmí být importován do common, api-client ani desktop části.
• SERVICE-***: nezávislé mikroslužby (např. OCR, Whisper, Joern). Běží samostatně, bez nutnosti autorizace, slouží jen
pro výpočty a specializované úlohy serveru.

VŠEOBECNÁ LOGIKA
Pro uživatele existuje pouze UI, přes které komunikuje se serverem.
Server je jediný zdroj pravdy a řídí všechny procesy, data, plánování i modely.
Service-*** služby jsou jen pomocné, výpočetní moduly bez vlastní logiky řízení.
Server rozhoduje o jejich využití.
Nikde v UI nic neskývej, vždy vše rovnou zobraz, API token, password atd.

ZÁKLADNÍ KONCEPT
Základem je klient. Každý klient má své projekty. Projekty v rámci jednoho klienta mohou přistupovat ke společné RAG
paměti, sdílet znalosti a data. Klienti mezi sebou data sdílet nesmí.
Datová izolace klientů je absolutní.

CELKOVÁ STRUKTURA
server/
├── controller → REST rozhraní, pouze DTO
├── service → business logika, plánování, orchestrace
├── entity → dokumenty pro MongoDB
├── repository → přístup k databázi
├── mapper → převody mezi Domain, DTO, Entity
└── resources → konfigurace, definice background úloh
service-ocr, service-whisper, service-joern → samostatné služby
common-api → API kontrakty pro desktop klienta
common-dto → sdílené DTO objekty (KMP)
common-services → interní komunikace mezi službami a serverem
desktop → uživatelský klient (integrovaný HTTP client)

ARCHITEKTONICKÉ ZÁSADY:

1. FOLLOW EXISTING ARCHITECTURE FIRST
   Vždy nejprve analyzuj existující vzory. Nevytvářej nové abstrakce, parametry, frameworky ani utility bez důvodu.
2. FAIL FAST – DO NOT GUESS
   Chyby se nesmí maskovat. Neočekávaný stav znamená výjimku. Žádné fallback logiky.
3. NO INVENTION
   Nepřidávej nové konfigurační položky, parametry ani třídy, které nejsou v architektuře. Nepiš metody, které nejsou
   volané.
4. NO AUTO-GENERATED TESTS
   Testy se vytvářejí pouze na explicitní požadavek.
5. SEPARATION OF LAYERS
   • Controller komunikuje pouze s DTO a volá Service.
   • Service pracuje s Domain objekty a volá Repository.
   • Repository obsluhuje Entity a přístup do databáze.
   • Mapper provádí převody mezi vrstvami podle schématu:
   DTO ↔ Domain (v Controlleru)
   Domain ↔ Entity (v Service)
   Zakázáno je:
   • Controller → Repository
   • Service → DTO
   • Controller → Entity
   • Domain s perzistentními anotacemi
6. Postup indexace:
   • Základní logika každé indexace je načíst obsah a porovnat s MongoDB snapshotem a tím nalést co je nového
   • Při indexaci text chunkovat přes `TextChunkingService` a ukládat do RAG
   • Indexace kódu pokud je delší jak 4096 tokenu chunkovat stejnou službou, bude v budoucnu nahrazena a ukládat do RAG
   • Vytvořit na každou změnu Pending task, kde se popsaná celková změna s dostatečnou přesnosti aby qualifier, zvlášť
   pro každou indexaci, mohl rozhodnout, zda takto to stačí nebo přead na GPU
   • Pending task který prošel qualifikaci již je připraven pro background běh, kde je predán agentnímu plánovači s
   detailními goual pro analýzu a model věděl co přesně hledá a co má hlídat
   • Agenti model bud rozhodne, že njsou nutné žádné, kroky nebo podle goul založí potřebné úkolý, klidně pending task
   sám pro sebe, aby se pak tím zabýval podrobněji s více úhlu, nebo úkol pro živatele atd.

PRAVIDLA KÓDU A PROGRAMOVÁNÍ:
HLAVNÍ JAZYK:
• Kotlin-first. Žádný Java styl.
• Spring Boot framework.
• Coroutines jako výchozí pro asynchronní logiku.
• Reactor pouze pro interop.
• Všude kde to je jen trochu možné používen NonNull hodnoty.
• Vždy přepis veškerou závislost, nikdy nedělej *deprecated* označení, nepoužívej supressed pro deprecated místa. Vývoj
je jen náš, klidně přepiš půl aplikace pokud to odstraní chbnou závislost.
• Nesnaž se udržet zpětnou kompatibilitu, není na kodu nic jiného zavislé, bždy použij best practice řešení.

KOMUNIKACE:
• Pro spojení mezi modulý se používá výhradně @HttpExchange přes REST API.
• Ve společné klivovně je definován interface začínající I***Service.
• Controller implementuje tento interface.
• Client část používá tento interface pro komunikaci se serverem.
• Vzor implementace spojení client-controller: private fun createHttpServiceProxyFactory(webClient: WebClient)
=HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build().createClient(I***Service::class.java)

JAZYK A KOMENTÁŘE:
• Kód, proměnné, komentáře a logy musí být výhradně v angličtině.
• Čeština v kódu je zakázána.
• Inline komentáře // jsou známkou špatného návrhu.
• Pokud vysvětlují „co“ kód dělá, musí být odstraněny.
• Pokud vysvětlují „proč“, přesunout do KDoc nad metodu nebo třídu.

SOLID PRINCIPY:
• Dodržuj jasnou odpovědnost tříd a rozhraní. Logika nesmí být rozptýlená napříč vrstvami.

IF-LESS PROGRAMMING
• Omez používání if/else.
• Preferuj sealed class, polymorfismus nebo strategické patterny.
• Používej when s exhaustiveness, pokud je to čitelnější.

NAZEVNÍ KONVENCE
• Třídy a rozhraní PascalCase
• Metody a proměnné camelCase
• Žádné zkratky, používaj jasné anglické názvy.

KONSTANTY A IMUTABILITA
• Preferuj val místo var.
• Magické hodnoty nahrazuj const val.

MODEL OBJEKTŮ
• Domain: immutable, pouze val, aktualizace přes .copy(). Používá se v Service vrstvě.
• Entity: používá se pouze v Repository vrstvě, mutable pouze pokud vyžaduje DB.
• DTO: immutable, pouze pro Controller, serializováno přes @Serializable.

HRANICE VRSTEV
• Controller: DTO → Domain, Service volání
• Service: Domain → Entity, Repository volání
• Repository: Entity → DB

ZAKÁZANÉ VZTAHY
• Controller nesmí vracet Entity
• Service nesmí přijímat DTO
• Controller nesmí přistupovat k Repository

NULL-SAFETY
• Nikdy nepoužívej !!
• Používej ?:, ?., requireNotNull, checkNotNull.
• Lateinit var pouze pro DI nebo frameworkové vlastnosti. Preferuji nikdy.

ČITELNOST A STRUKTURA
• Funkce krátké, jednoúčelové.
• Sdílený kód přes extension functions.
• Eliminuj duplicity.
• Vždy používej import nikdy nepiš celý path ke tříde

SERIALIZACE
• Standard je kotlinx.serialization.
• Jackson pouze pro interop.
• Explicitně anotuj rozdílné názvy polí.

KOROUTINY A REAKTIVITA
• Používej suspend fun.
• Pro streamy Flow.
• Nikdy nepoužívej .block() v produkčním kódu.

LOGOVÁNÍ
• Structured logging: logger.info { “message with $var” }
• Vždy přidávej correlation ID nebo trace ID, pokud je dostupné.

ZÁVISLOSTI A DI
• Pouze constructor injection.
• Žádné field injection ani manuální singletony.

TESTY
• Píšou se jen na explicitní požadavek.
• Nepoužívej auto generování.

VÝKON A SPRÁVA ZDROJŮ
• Používej connection pooly, správné schedulery, implementuj retry a timeouty.

VÝSTUPNÍ POŽADAVKY PRO AI
• AI nástroje musí kód přímo upravovat podle těchto pravidel, ne pouze navrhovat změny.
• Nesmí vymýšlet nové parametry ani metody.
• Pokud si AI není jistá, musí se doptat.
• Při nejasnosti vol nejjednodušší řešení odpovídající existujícímu vzoru.
• AI nesmí měnit architekturu, vrstvy ani strukturu bez explicitního pokynu.

ZÁSADY ARCHITEKTONICKÉ DISCIPLÍNY:

1. Všechny vrstvy jsou striktně oddělené.
2. Domain model je jediný zdroj pravdy.
3. Controller slouží jen pro komunikaci.
4. Repository řeší pouze persistenci.
5. common-api obsahuje API kontrakty pro desktop UI.
6. common-dto obsahuje sdílené DTO pro všechny UI platformy (desktop, mobile).
7. common-services slouží jen pro interní komunikaci služeb se serverem.
7. UI komunikuje pouze přes API klient.
8. Klienti mezi sebou nemohou sdílet data, pouze projekty v rámci klienta sdílejí RAG.
9. Server je zodpovědný za orchestrace, řízení procesů, modely a agentní běhy.

CÍL
• Zachovat architektonickou čistotu, čitelnost a stabilitu celého systému.
• Kód musí být vždy idiomatický, bezpečný, bez zbytečných větví, komentářů nebo duplicit.

DESKTOP UI DESIGN SYSTEM
ZÁSADY
• Používej jednoduché rozvržení: BorderLayout + GridBagLayout.
• Vnější okraje: 10 px okolo hlavních panelů.
• Vnitřní okraje sekcí: 12 px, mezery mezi prvky: 8 px.
• Nadpisy sekcí: font SansSerif BOLD 13, hlavní nadpisy: SansSerif BOLD 16.
• Tlačítka skupinuj do pravého action baru v rámci sekce.
• Řádky formuláře: dvousloupcové (label vlevo, pole vpravo), labely zarovnat vlevo.
• Chyby zobrazuj přes JOptionPane.ERROR_MESSAGE, bez potlačení.
• Žádné maskování hodnot – vše zobrazuj otevřeně (tokeny, hesla) dle hlavních pravidel.

KOMPONENTY
• Hlavička okna: název v titulku, případně headerLabel uvnitř.
• Sekce: rámeček s vnitřním okrajem 12 px, název sekce nahoře.
• Action bar: FlowLayout(ALIGN_RIGHT) s mezerou 8 px.
• Form panel: GridBagLayout s mezerami 8 px a jednotným dvě‑sloupcovým rozvržením.
• Tabulky: JScrollPane, sloupce pojmenovat jednoznačně, preferovaná výška řádku standardní.

INTERAKCE
• Všechny dlouhé operace spouštěj v Coroutines (Dispatchers.IO), UI aktualizace v Dispatchers.Main.
• Žádné fallbacky – fail fast.
• Tlačítka mají jasný popis akce (např. "Save Overrides", "Refresh").

IMPLEMENTACE
• V desktop modulu je helper UiDesign (com.jervis.ui.style.UiDesign), který poskytuje:
– headerLabel(text), subHeaderLabel(text)
– sectionPanel(title, content)
– actionBar(buttons…)
– formPanel { row(label, field); fullWidth(component) }
• Každé nové okno a panel používej tyto stavební bloky.

USER TASKS OKNO
• Zobraz seznam "user-tasks" (čekající na uživatele) v tabulce.
• Horní lišta "Quick Actions" (placeholder), akce budou doplněny podle typu task.
• Refresh tlačítko obnoví data.
• Badge v ikoně aplikace (macOS Dock) zobrazuje počet aktivních user-tasků (součet pro aktuální kontext klienta).