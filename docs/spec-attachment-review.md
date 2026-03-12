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

### DocumentExtractor
- VLM-first text extraction (pymupdf pro dokumenty, qwen3-vl pro obrázky/scany)
- Python KB service: `/documents/extract-text`

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
2. Spustit text extrakci async
3. **Ne**volat `registerPreStoredAttachment()` ihned
4. Qualifier rozhodne co jde do KB

**Riziko:** Pokud Qualifier selže nebo je pomalý, přílohy nebudou v KB. Potřebujeme fallback (timeout → upload vše).

---

## Implementace – HOTOVO

### Fáze 1: MongoDB model + repository ✅
- `backend/server/.../entity/AttachmentExtractDocument.kt` – entita s ExtractionStatus enum
- `backend/server/.../repository/AttachmentExtractRepository.kt` – Spring Data repo

### Fáze 2: kb_document_upload base64 rozšíření ✅
- `backend/service-mcp/app/main.py` – `file_content` (base64) + `file_name` parametry
- Validace: buď `file_path` NEBO `file_content + file_name`, whitelist přípon, max 20 MB

### Fáze 3: TaskDocument rozšíření ✅
- `backend/server/.../entity/TaskDocument.kt` – `hasAttachments`, `attachmentCount`
- `TaskService.createTask()` – nové parametry
- `@PersistenceCreator` factory aktualizován

### Fáze 4: VLM-first text extraction pipeline ✅
- `backend/server/.../service/indexing/AttachmentExtractionService.kt` – nový service
- `backend/service-knowledgebase/app/api/routes.py` – `POST /documents/extract-text`
- `backend/service-knowledgebase/app/services/knowledge_service.py` – `extract_text_only()`
- `backend/server/.../configuration/KnowledgeServiceRestClient.kt` – `extractText()` + `TextExtractionResult`
- Strategie: VLM-first pro obrázky, pymupdf/python-docx pro dokumenty, VLM hybrid pro scanned PDF

### Fáze 5: EmailContinuousIndexer integrace ✅
- Vytváří AttachmentExtractDocument záznamy při indexaci emailu
- Fire-and-forget async text extrakce v pozadí
- Existující KB registrace zachována (dual path: extract + direct upload)

### Fáze 6: Qualifier – relevance assessment ✅
- `qualification_handler.py` – `_score_attachment_relevance()` funkce
- Čte extracts z MongoDB (attachment_extracts collection) přes motor
- LLM scoring (0.0–1.0) s JSON výstupem
- Score >= 0.7 → automatický KB upload s tagem "qualifier-approved"
- `QualifyRequestDto` + `QualifyRequest` rozšířeny o `has_attachments`, `attachment_count`
- `AgentOrchestratorService` předává nová pole

---

## Rozhodnutí k otevřeným otázkám

1. **Fallback strategy:** Existující flow (přímý KB upload) zachován jako dual-path. Pokud Qualifier selže, přílohy jsou v KB přes stávající `registerPreStoredAttachment()`.
2. **Zpětná kompatibilita:** Pouze nové emaily. Existující nemají extract records – fungují přes stávající flow.
3. **QualifyRequest rozšíření:** Qualifier čte z MongoDB přímo (motor client). Pouze `has_attachments` flag se posílá v requestu.
4. **VLM OCR:** Přes `ImageService.describe_image()` v Python KB service (ChatOllama → Ollama Router → p40-2 GPU).
5. **Duplicity:** Přijatelná. KB service deduplikuje přes content hash. Stávající flow + qualifier flow mohou registrovat stejný soubor – KB to zvládne.
