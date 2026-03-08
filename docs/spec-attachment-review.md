# Spec Review: Email Attachment Indexing & KB Upload Pipeline

**Datum review:** 2026-03-08
**Source:** `agent://claude-mcp/spec-attachment-full-2026-03-08`

---

## Existující infrastruktura (co UŽ funguje)

### Email přílohy – stahování a ukládání
- `EmailPollingHandlerBase.storeAttachmentBinary()` – IMAP polling už stahuje přílohy
- Binárka se ukládá do `kb-documents/{clientId}/` s UUID prefixem
- `EmailAttachment` data class: `filename`, `contentType`, `size`, `contentId`, `storagePath`
- `EmailContinuousIndexer.indexEmailAttachments()` – volá `AttachmentKbIndexingService.registerPreStoredAttachment()`
- **Všechny přílohy jdou rovnou do KB** (bez relevance filtru)

### KB Document Upload
- `KbDocumentUploadDto.dataBase64` – DTO pole pro base64 už existuje
- `KbDocumentRpcImpl.uploadDocument()` – dekóduje base64 → uloží na FS → registruje v KB
- `DirectoryStructureService.storeKbDocument()` – ukládá s UUID prefixem
- Python KB service: `/api/v1/documents/upload` + `/api/v1/documents/register`

### Tika
- `TikaTextExtractionService.extractPlainText()` – existuje, volá ITikaClient
- Používá se pro čištění HTML/XML z emailových těl

### Qualifier
- `qualification_handler.py` – LLM agent s CORE tools
- Výstup: QUEUED/DONE/URGENT_ALERT s `suggested_approach`
- `qualifierPreparedContext` uložen na TaskDocument jako JSON string

---

## Gap analýza – co CHYBÍ

### 1. MongoDB kolekce `attachment_extracts` ❌
- Neexistuje žádná entita ani repository
- Dnes se metadata o extrakci neukládají do MongoDB
- Chybí: relevance score, reason, kbDocId tracking

### 2. `TaskDocument.hasAttachments` + `attachmentCount` ❌
- Pole neexistují na TaskDocument
- Existuje `attachments: List<AttachmentMetadata>` (pro Atlassian přílohy)
- Email přílohy jsou v `EmailMessageIndexDocument.attachments: List<EmailAttachment>`

### 3. MCP tool `kb_document_upload` – base64 podpora ❌
- MCP tool (Python) přijímá pouze `file_path`
- Kotlin DTO to umí (`dataBase64`), ale MCP → Python KB jde přes multipart s file path

### 4. Qualifier – relevance assessment příloh ❌
- Qualifier dnes neví o přílohách emailů
- Nemá přístup k extrahovanému textu příloh
- Chybí: načtení extracts, LLM scoring, conditional KB upload

### 5. VLM OCR pro obrázky ❌
- Dnes se obrázky indexují do KB bez OCR textu
- `qwen3-vl-tool:latest` endpoint není integrován do attachment pipeline

---

## Architekturní nesoulad spec vs. realita

### Spec předpokládá Gmail API – systém používá IMAP
**Řešení:** Adaptovat na IMAP flow. Přílohy se stahují v `EmailPollingHandlerBase.parseContent()` → `storeAttachmentBinary()`. Toto **nepotřebuje změnu** – binárky jsou už na PVC.

### Spec chce PVC `/data/attachments/{task_id}/` – systém ukládá do `kb-documents/`
**Řešení:** Použít existující `kb-documents/` cestu. `EmailAttachment.storagePath` už ukazuje správně.

### Spec chce defer KB upload na Qualifier – dnes se uploaduje ihned
**Řešení:** Změnit flow v `EmailContinuousIndexer`:
1. Při indexaci: vytvořit `AttachmentExtractDocument` (PENDING)
2. Spustit Tika extrakci async
3. **Ne**volat `registerPreStoredAttachment()` ihned
4. Qualifier rozhodne co jde do KB

**Riziko:** Pokud Qualifier selže nebo je pomalý, přílohy nebudou v KB. Potřebujeme fallback (timeout → upload vše).

---

## Doporučený implementační plán

### Fáze 1: MongoDB model + repository (základ)
**Soubory:**
- `backend/server/.../entity/AttachmentExtractDocument.kt` – nová entita
- `backend/server/.../repository/AttachmentExtractRepository.kt` – nový repo

### Fáze 2: kb_document_upload base64 rozšíření (samostatné)
**Soubory:**
- `backend/service-mcp/app/main.py` – přidat `file_content` + `file_name` parametry
- Validace: buď `file_path` NEBO `file_content + file_name`

### Fáze 3: TaskDocument rozšíření
**Soubory:**
- `backend/server/.../entity/TaskDocument.kt` – přidat `hasAttachments`, `attachmentCount`
- `EmailContinuousIndexer` – nastavit při indexaci

### Fáze 4: Tika async extrakce + AttachmentExtract pipeline
**Soubory:**
- `backend/server/.../service/email/AttachmentExtractionService.kt` – nový service
- `EmailContinuousIndexer` – změnit flow: vytvářet extract records místo přímého KB uploadu

### Fáze 5: Qualifier – relevance assessment
**Soubory:**
- `qualification_handler.py` – přidat attachment scoring logiku
- `kotlin_client.py` – přidat endpoint pro čtení extracts
- Internal API route pro attachment extracts

### Fáze 6: VLM OCR integrace
- `AttachmentExtractionService` – image → VLM OCR místo Tika

---

## Otevřené otázky pro review

1. **Fallback strategy:** Co když Qualifier nedoběhne? Timeout → upload vše do KB?
2. **Zpětná kompatibilita:** Existující emaily s přílohami – migrace nebo pouze nové?
3. **QualifyRequest rozšíření:** Přidat attachment extracts do requestu, nebo qualifier čte z MongoDB přímo?
4. **VLM OCR endpoint:** Je `qwen3-vl-tool:latest` dostupný jako REST API? Jaký formát?
5. **Duplicity:** Pokud příloha jde do KB přes qualifier I přes stávající flow – deduplikace přes content hash?
