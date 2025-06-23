# JERVIS

**Just-in-time Embedded Reasoning & Virtual Insight System**

## O aplikaci

JERVIS je pokročilý AI asistent určený pro softwarové architekty, vývojáře a analytiky. Kombinuje sílu velkých jazykových modelů (LLM), techniky RAG (retrieval-augmented generation), hlasové rozpoznávání a pokročilou práci s kontextem projektů a kódu.

### Cíl aplikace

Hlavním cílem JERVIS je poskytnout komplexní asistenci při vývoji a správě rozsáhlých softwarových projektů. JERVIS funguje jako inteligentní pomocník, který dokáže:

- Zpracovávat a ukládat informace ve vektorové databázi (vector store)
- Analyzovat a porozumět rozsáhlým projektům a jejich souvislostem
- Aktivně vyhledávat, doplňovat a propojovat informace z různých zdrojů
- Poskytovat relevantní odpovědi a návrhy na základě kontextu celého projektu

### Jak JERVIS funguje

JERVIS využívá pokročilé technologie pro zpracování a analýzu dat:

1. **Vector Store** - ukládá veškeré informace ve formě vektorových reprezentací, což umožňuje efektivní vyhledávání a propojování souvisejících informací
2. **LLM (Large Language Model)** - zpracovává a generuje textové výstupy na základě dotazů a kontextu
3. **RAG (Retrieval-Augmented Generation)** - kombinuje vyhledávání relevantních informací s generováním odpovědí
4. **MCP (Model Context Protocol)** - umožňuje modelům interagovat s externími systémy jako terminál, email nebo jiné aplikace

### Zdroje informací a integrace

JERVIS dokáže pracovat s širokou škálou zdrojů informací:

- Záznamy ze schůzek a konverzací
- Git historie a zdrojový kód
- Emailová komunikace
- Java a Kotlin aplikace
- Dokumentace a další projektové materiály

## Technické řešení

### Komponenty

- **Kotlin GUI aplikace**: 
  - Poskytuje REST API kompatibilní s LM Studio
  - Slouží jako uživatelské rozhraní asistenta
  - Zajišťuje integraci s vývojovým prostředím
  - Spravuje vektorovou databázi pro ukládání a vyhledávání informací

- **Vector Store**: 
  - Ukládá veškeré informace ve formě vektorových embedingů
  - Umožňuje sémantické vyhledávání a propojování souvisejících informací
  - Zajišťuje perzistenci znalostí a kontextu napříč projektem

- **LLM Koordinátor**:
  - Řídí komunikaci s jazykovými modely
  - Optimalizuje dotazy a odpovědi
  - Zajišťuje relevantní kontext pro generování odpovědí
  - Podporuje jak vzdálené API (Anthropic, OpenAI), tak lokální LLM modely
  - Automaticky směruje dotazy na vhodný model podle složitosti úkolu

- **MCP (Model Context Protocol)**:
  - Implementováno pomocí knihovny Koog
  - Umožňuje modelům interagovat s externími systémy
  - Podporuje operace jako spouštění příkazů v terminálu, odesílání emailů a interakce s aplikacemi
  - Ukládá výsledky akcí do vektorové databáze pro budoucí reference
  - Integruje se s LLM koordinátorem pro rozšíření schopností modelů
  - Pro správné fungování je nutné mít nastavené API klíče pro Anthropic a OpenAI v nastavení aplikace
  - Ověření dostupnosti MCP lze provést dotazem obsahujícím příkaz pro terminál, např. "spusť příkaz ls -la"

- **Python backend**: 
  - Přepis hlasu pomocí whisper.cpp 
  - Rozlišení mluvčích v záznamech
  - Zpracování a analýza textových dat

- **AWS infrastruktura**: 
  - Inferenční a embedovací backend postavený na Amazon Bedrock
  - Využití služeb Transcribe, Titan Embeddings a OpenSearch
  - Škálovatelné řešení pro práci s velkými objemy dat

### Projektová struktura

Projekt je organizován do modulárních komponent, které spolu vzájemně komunikují:

    jervis/
    ├── README.md
    ├── src/main/kotlin/com/jervis/
    │   ├── JervisApplication.kt
    │   ├── controller/
    │   ├── module/
    │   │   ├── vectordb/
    │   │   ├── llmcoordinator/
    │   │   ├── memory/
    │   │   ├── indexer/
    │   │   └── trayui/
    │   ├── service/
    │   └── utils/
    └── src/main/resources/

## Návod k použití

### Nastavení API klíčů

Pro plnou funkčnost JERVIS je nutné nastavit API klíče pro Anthropic a OpenAI:

1. Otevřete nastavení aplikace
2. Zadejte API klíče pro Anthropic a OpenAI
3. Uložte nastavení

### Použití MCP (Model Context Protocol)

MCP umožňuje modelům interagovat s externími systémy. Pro použití MCP:

1. Ujistěte se, že máte nastavené API klíče pro Anthropic a OpenAI
2. Formulujte dotaz, který obsahuje požadavek na interakci s externím systémem, například:
   - "Spusť příkaz ls -la a ukaž mi obsah adresáře"
   - "Pošli email na adresu example@example.com s předmětem Test"
3. JERVIS automaticky rozpozná požadavek na MCP akci a provede ji
4. Výsledek akce bude zahrnut v odpovědi

### Správa limitů tokenů

JERVIS používá systém limitů tokenů pro API Anthropic, aby se předešlo překročení limitů API:

1. Výchozí limity jsou nastaveny na 20 000 vstupních tokenů a 4 000 výstupních tokenů za minutu
2. Při překročení limitu JERVIS automaticky přepne na OpenAI jako záložní řešení
3. Limity lze upravit v nastavení aplikace
4. Pro optimální výkon doporučujeme ponechat zapnutou možnost "Fallback to OpenAI on rate limit"

### Lokální LLM modely

JERVIS podporuje použití více lokálních LLM modelů s automatickým směrováním dotazů podle složitosti:

1. **GPU model**:
   - Menší, kvantizovaný model (např. phi-2, tinyllama, gemma-2b)
   - Používá se pro rychlé a krátké úkoly jako zkrácení textu, jednoduché shrnutí nebo vytvoření návrhu názvu
   - Běží na GPU pro rychlou odezvu

2. **CPU model**:
   - Větší model (např. DeepSeek-Coder v2 Lite)
   - Používá se pro složitější analýzy jako generování komentářů ke kódu, shrnutí celé třídy nebo vysvětlení složitého bloku
   - Běží na CPU pro zpracování složitějších úkolů

3. **Externí modely**:
   - Podpora pro Ollama a LM Studio
   - Možnost kombinovat modely z obou zdrojů v jednom seznamu
   - Samostatné modely pro jednoduché úkoly, programování a embedding
   - Možnost vypnout jednotlivé poskytovatele (Ollama nebo LM Studio)

4. **Automatické směrování**:
   - JERVIS automaticky vybírá vhodný model podle charakteristik dotazu
   - Krátké a jednoduché dotazy jsou směrovány na GPU model
   - Dlouhé nebo kódově zaměřené dotazy jsou směrovány na CPU model
   - Pokud jeden model není dostupný, JERVIS automaticky přepne na druhý

5. **Konfigurace**:
   - Nastavení pro interní modely lze upravit v konfiguračním souboru application.yml
   - Externí modely (Ollama, LM Studio) lze konfigurovat v nastavení aplikace
   - Lze nastavit endpointy, názvy modelů a další parametry pro všechny modely
   - Lze také konfigurovat pravidla pro směrování dotazů

## Vize a přínosy

JERVIS je navržen jako lokální i cloudově propojený nástroj, který přináší následující výhody:

- **Komplexní porozumění projektu**:
  - Rozumí kódu a kontextu projektu v celé jeho šíři
  - Dokáže identifikovat souvislosti mezi různými částmi projektu
  - Pomáhá udržovat konzistenci v rozsáhlých projektech

- **Efektivní práce s informacemi**:
  - Přepisuje a analyzuje hlasovou komunikaci
  - Udržuje paměť z konverzací (RAG)
  - Automaticky propojuje související informace z různých zdrojů

- **Podpora vývojového procesu**:
  - Pomáhá s generováním úkolů
  - Umožňuje dotazování na historii projektu
  - Poskytuje návrhy architektury a řešení problémů
  - Zrychluje orientaci v rozsáhlých projektech

- **Kontinuální učení**:
  - Průběžně se učí z nových informací
  - Adaptuje se na specifika projektu
  - Zlepšuje své odpovědi na základě zpětné vazby
