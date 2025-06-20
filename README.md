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
