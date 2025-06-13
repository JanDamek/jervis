# JERVIS

**Just-in-time Embedded Reasoning & Virtual Insight System**

JERVIS je AI asistent určený pro softwarové architekty, vývojáře a analytiky. Kombinuje sílu velkých jazykových modelů (
LLM), techniky RAG (retrieval-augmented generation), hlasové rozpoznávání a pokročilou práci s kontextem projektů a
kódu.

## Projektová struktura

    jervis/
    ├── README.md
    └── src/main/kotlin/
        ├── Main.kt
        ├── ApiController.kt
        └── AwsClient.kt
    
## Komponenty

- **Kotlin GUI aplikace**: poskytuje REST API kompatibilní s LM Studio a slouží jako uživatelské rozhraní asistenta.
- **Python backend**: přepis hlasu pomocí whisper.cpp a případně rozlišení mluvčích.
- **AWS infrastruktura**: inferenční a embedovací backend postavený na Amazon Bedrock, Transcribe, Titan Embeddings a
  OpenSearch.

## Vize

JERVIS je navržen jako lokální i cloudově propojený nástroj, který:

- Rozumí kódu a kontextu projektu
- Přepisuje a analyzuje hlasovou komunikaci
- Udržuje paměť z konverzací (RAG)
- Pomáhá s generováním úkolů, dotazováním na historii a návrhy architektury
