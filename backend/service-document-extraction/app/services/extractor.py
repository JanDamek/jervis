"""Document Extraction Service — converts any file format to plain text.

Extraction strategy per mime type:
- text/*, CSV, JSON, XML, YAML, Markdown → direct UTF-8 decode
- HTML → BeautifulSoup strip tags
- DOCX → python-docx paragraphs + tables
- DOC (legacy) → mammoth → HTML → BeautifulSoup
- XLSX → openpyxl sheet rows
- XLS (legacy) → xlrd sheet rows
- PPTX → python-pptx slides (shapes + tables + notes)
- ODT/ODS/ODP → odfpy text extraction
- RTF → striprtf
- PDF → pymupdf text + VLM for pages with images/scans (hybrid)
- Images → VLM description + OCR
- MSG (Outlook) → extract-msg
- EML → stdlib email parser

VLM calls go through Ollama router (HTTP) for GPU/cloud routing.
"""

from __future__ import annotations

import base64
import io
import logging
from dataclasses import dataclass, field

import httpx

logger = logging.getLogger(__name__)


@dataclass
class ImageDescription:
    description: str
    ocr_text: str = ""


@dataclass
class PageContent:
    page_number: int
    text: str
    images: list[ImageDescription] = field(default_factory=list)


@dataclass
class ExtractedDocument:
    text: str
    pages: list[PageContent] = field(default_factory=list)
    language: str = ""
    method: str = "direct"
    metadata: dict = field(default_factory=dict)


# ═══════════════════════════════════════════════════════════════════════════════
# MIME type routing
# ═══════════════════════════════════════════════════════════════════════════════

_TEXT_MIMES = {
    "text/plain", "text/csv", "text/markdown", "text/x-markdown",
    "application/json", "application/xml", "text/xml", "text/yaml",
    "application/x-yaml",
}
_HTML_MIMES = {"text/html", "application/xhtml+xml"}
_PDF_MIMES = {"application/pdf"}
_DOCX_MIMES = {"application/vnd.openxmlformats-officedocument.wordprocessingml.document"}
_DOC_MIMES = {"application/msword"}
_XLSX_MIMES = {
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
}
_XLS_MIMES = {"application/vnd.ms-excel"}  # shared with xlsx — resolved by extension
_PPTX_MIMES = {
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.ms-powerpoint",
}
_ODT_MIMES = {"application/vnd.oasis.opendocument.text"}
_ODS_MIMES = {"application/vnd.oasis.opendocument.spreadsheet"}
_ODP_MIMES = {"application/vnd.oasis.opendocument.presentation"}
_RTF_MIMES = {"application/rtf", "text/rtf"}
_MSG_MIMES = {"application/vnd.ms-outlook"}
_EML_MIMES = {"message/rfc822"}
_IMAGE_MIMES = {
    "image/png", "image/jpeg", "image/jpg", "image/webp",
    "image/bmp", "image/gif", "image/tiff",
}

_EXT_TO_TYPE = {
    # Text
    ".txt": "text", ".csv": "text", ".json": "text", ".xml": "text",
    ".yaml": "text", ".yml": "text", ".md": "text", ".log": "text",
    ".ini": "text", ".cfg": "text", ".conf": "text", ".properties": "text",
    # Code (as text)
    ".py": "text", ".js": "text", ".ts": "text", ".java": "text",
    ".kt": "text", ".kts": "text", ".swift": "text", ".go": "text",
    ".rs": "text", ".c": "text", ".cpp": "text", ".h": "text",
    ".cs": "text", ".rb": "text", ".php": "text", ".sh": "text",
    ".sql": "text", ".gradle": "text",
    # HTML
    ".html": "html", ".htm": "html", ".xhtml": "html",
    # PDF
    ".pdf": "pdf",
    # Office — modern
    ".docx": "docx", ".xlsx": "xlsx", ".pptx": "pptx",
    # Office — legacy
    ".doc": "doc", ".xls": "xls", ".ppt": "pptx",  # ppt → try pptx parser
    # OpenDocument
    ".odt": "odt", ".ods": "ods", ".odp": "odp",
    # RTF
    ".rtf": "rtf",
    # Email
    ".msg": "msg", ".eml": "eml",
    # Images
    ".png": "image", ".jpg": "image", ".jpeg": "image", ".webp": "image",
    ".bmp": "image", ".gif": "image", ".tiff": "image", ".tif": "image",
}


def _detect_type(mime_type: str, filename: str) -> str:
    mime_lower = mime_type.lower().strip() if mime_type else ""

    # MIME-based detection
    if mime_lower in _TEXT_MIMES: return "text"
    if mime_lower in _HTML_MIMES: return "html"
    if mime_lower in _PDF_MIMES: return "pdf"
    if mime_lower in _DOCX_MIMES: return "docx"
    if mime_lower in _DOC_MIMES: return "doc"
    if mime_lower in _PPTX_MIMES: return "pptx"
    if mime_lower in _ODT_MIMES: return "odt"
    if mime_lower in _ODS_MIMES: return "ods"
    if mime_lower in _ODP_MIMES: return "odp"
    if mime_lower in _RTF_MIMES: return "rtf"
    if mime_lower in _MSG_MIMES: return "msg"
    if mime_lower in _EML_MIMES: return "eml"
    if mime_lower in _IMAGE_MIMES: return "image"
    # xlsx vs xls — resolve by extension first
    if mime_lower in _XLSX_MIMES:
        fname_lower = filename.lower() if filename else ""
        if fname_lower.endswith(".xls"):
            return "xls"
        return "xlsx"

    # Extension-based fallback
    fname_lower = filename.lower() if filename else ""
    for ext, doc_type in _EXT_TO_TYPE.items():
        if fname_lower.endswith(ext):
            return doc_type

    # Unknown → try as text
    return "text"


# ═══════════════════════════════════════════════════════════════════════════════
# Main extractor
# ═══════════════════════════════════════════════════════════════════════════════

class DocumentExtractor:
    """Document extraction service — any file → plain text."""

    def __init__(self, ollama_router_url: str = "http://jervis-ollama-router:8080"):
        self.router_url = ollama_router_url

    async def extract(
        self,
        file_bytes: bytes,
        filename: str,
        mime_type: str = "",
        max_tier: str = "NONE",
    ) -> ExtractedDocument:
        doc_type = _detect_type(mime_type, filename)
        logger.info("DocumentExtractor: file=%s mime=%s type=%s size=%d",
                     filename, mime_type, doc_type, len(file_bytes))

        extractors = {
            "text": lambda: self._extract_text_direct(file_bytes, filename),
            "html": lambda: self._extract_html(file_bytes),
            "docx": lambda: self._extract_docx(file_bytes),
            "doc": lambda: self._extract_doc_legacy(file_bytes),
            "xlsx": lambda: self._extract_xlsx(file_bytes),
            "xls": lambda: self._extract_xls_legacy(file_bytes),
            "pptx": lambda: self._extract_pptx(file_bytes),
            "odt": lambda: self._extract_odf(file_bytes, "odt"),
            "ods": lambda: self._extract_odf(file_bytes, "ods"),
            "odp": lambda: self._extract_odf(file_bytes, "odp"),
            "rtf": lambda: self._extract_rtf(file_bytes),
            "msg": lambda: self._extract_msg(file_bytes),
            "eml": lambda: self._extract_eml(file_bytes),
        }

        if doc_type in extractors:
            return extractors[doc_type]()
        elif doc_type == "pdf":
            return await self._extract_pdf(file_bytes, max_tier)
        elif doc_type == "image":
            return await self._extract_image(file_bytes, max_tier)
        else:
            return self._extract_text_direct(file_bytes, filename)

    # ═══════════════════════════════════════════════════════════════════════════
    # Text / HTML
    # ═══════════════════════════════════════════════════════════════════════════

    def _extract_text_direct(self, file_bytes: bytes, filename: str) -> ExtractedDocument:
        text = file_bytes.decode("utf-8", errors="replace")
        return ExtractedDocument(text=text, method="direct", metadata={"filename": filename})

    def _extract_html(self, file_bytes: bytes) -> ExtractedDocument:
        from bs4 import BeautifulSoup
        html_text = file_bytes.decode("utf-8", errors="replace")
        soup = BeautifulSoup(html_text, "lxml")
        for tag in soup(["script", "style", "noscript"]):
            tag.decompose()
        text = soup.get_text(separator="\n", strip=True)
        return ExtractedDocument(text=text, method="html",
                                 metadata={"title": soup.title.string if soup.title else ""})

    # ═══════════════════════════════════════════════════════════════════════════
    # Word: DOCX (modern) + DOC (legacy via mammoth)
    # ═══════════════════════════════════════════════════════════════════════════

    def _extract_docx(self, file_bytes: bytes) -> ExtractedDocument:
        from docx import Document as DocxDocument
        doc = DocxDocument(io.BytesIO(file_bytes))
        parts = []
        for para in doc.paragraphs:
            if para.text.strip():
                parts.append(para.text)
        for table in doc.tables:
            rows = []
            for row in table.rows:
                cells = [cell.text.strip() for cell in row.cells]
                rows.append(" | ".join(cells))
            if rows:
                parts.append("\n".join(rows))
        text = "\n\n".join(parts)
        return ExtractedDocument(text=text, method="docx",
                                 metadata={"page_count": len(doc.sections), "has_tables": len(doc.tables) > 0})

    def _extract_doc_legacy(self, file_bytes: bytes) -> ExtractedDocument:
        """Extract .doc (legacy Word 97-2003) via mammoth → HTML → text."""
        try:
            import mammoth
            result = mammoth.convert_to_html(io.BytesIO(file_bytes))
            html = result.value
            from bs4 import BeautifulSoup
            soup = BeautifulSoup(html, "lxml")
            text = soup.get_text(separator="\n", strip=True)
            return ExtractedDocument(text=text, method="doc-mammoth",
                                     metadata={"warnings": len(result.messages)})
        except Exception as e:
            logger.warning("Legacy .doc extraction failed: %s — trying as text", e)
            return self._extract_text_direct(file_bytes, "document.doc")

    # ═══════════════════════════════════════════════════════════════════════════
    # Excel: XLSX (modern) + XLS (legacy via xlrd)
    # ═══════════════════════════════════════════════════════════════════════════

    def _extract_xlsx(self, file_bytes: bytes) -> ExtractedDocument:
        from openpyxl import load_workbook
        wb = load_workbook(io.BytesIO(file_bytes), read_only=True, data_only=True)
        parts = []
        for sheet_name in wb.sheetnames:
            ws = wb[sheet_name]
            sheet_rows = []
            for row in ws.iter_rows(values_only=True):
                cells = [str(c) if c is not None else "" for c in row]
                if any(cells):
                    sheet_rows.append(" | ".join(cells))
            if sheet_rows:
                parts.append(f"=== Sheet: {sheet_name} ===\n" + "\n".join(sheet_rows))
        wb.close()
        text = "\n\n".join(parts)
        return ExtractedDocument(text=text, method="xlsx", metadata={"sheet_count": len(wb.sheetnames)})

    def _extract_xls_legacy(self, file_bytes: bytes) -> ExtractedDocument:
        """Extract .xls (legacy Excel 97-2003) via xlrd."""
        try:
            import xlrd
            wb = xlrd.open_workbook(file_contents=file_bytes)
            parts = []
            for sheet_name in wb.sheet_names():
                ws = wb.sheet_by_name(sheet_name)
                sheet_rows = []
                for row_idx in range(ws.nrows):
                    cells = [str(ws.cell_value(row_idx, col_idx)) for col_idx in range(ws.ncols)]
                    if any(c.strip() for c in cells):
                        sheet_rows.append(" | ".join(cells))
                if sheet_rows:
                    parts.append(f"=== Sheet: {sheet_name} ===\n" + "\n".join(sheet_rows))
            text = "\n\n".join(parts)
            return ExtractedDocument(text=text, method="xls-xlrd",
                                     metadata={"sheet_count": wb.nsheets})
        except Exception as e:
            logger.warning("Legacy .xls extraction failed: %s — trying openpyxl", e)
            return self._extract_xlsx(file_bytes)

    # ═══════════════════════════════════════════════════════════════════════════
    # PowerPoint: PPTX
    # ═══════════════════════════════════════════════════════════════════════════

    def _extract_pptx(self, file_bytes: bytes) -> ExtractedDocument:
        """Extract .pptx (and attempt .ppt) — slides, tables, notes."""
        try:
            from pptx import Presentation
            prs = Presentation(io.BytesIO(file_bytes))
            parts = []
            for slide_num, slide in enumerate(prs.slides, 1):
                slide_parts = [f"--- Slide {slide_num} ---"]
                for shape in slide.shapes:
                    if shape.has_text_frame:
                        for para in shape.text_frame.paragraphs:
                            if para.text.strip():
                                slide_parts.append(para.text)
                    if shape.has_table:
                        for row in shape.table.rows:
                            cells = [cell.text.strip() for cell in row.cells]
                            slide_parts.append(" | ".join(cells))
                if slide.has_notes_slide and slide.notes_slide.notes_text_frame:
                    notes = slide.notes_slide.notes_text_frame.text.strip()
                    if notes:
                        slide_parts.append(f"[Notes: {notes}]")
                parts.append("\n".join(slide_parts))
            text = "\n\n".join(parts)
            return ExtractedDocument(text=text, method="pptx",
                                     metadata={"slide_count": len(prs.slides)})
        except Exception as e:
            logger.warning("PPTX extraction failed: %s — trying as text", e)
            return self._extract_text_direct(file_bytes, "presentation.pptx")

    # ═══════════════════════════════════════════════════════════════════════════
    # OpenDocument: ODT, ODS, ODP
    # ═══════════════════════════════════════════════════════════════════════════

    def _extract_odf(self, file_bytes: bytes, odf_type: str) -> ExtractedDocument:
        """Extract OpenDocument formats via odfpy."""
        try:
            from odf.opendocument import load as odf_load
            from odf import text as odf_text, table as odf_table
            from odf.element import Element

            doc = odf_load(io.BytesIO(file_bytes))

            def get_text_recursive(element: Element) -> str:
                """Recursively extract text from ODF elements."""
                result = []
                if hasattr(element, 'childNodes'):
                    for child in element.childNodes:
                        if hasattr(child, 'data'):
                            result.append(child.data)
                        else:
                            result.append(get_text_recursive(child))
                return "".join(result)

            parts = []

            if odf_type == "odt":
                for para in doc.getElementsByType(odf_text.P):
                    t = get_text_recursive(para).strip()
                    if t:
                        parts.append(t)
                for heading in doc.getElementsByType(odf_text.H):
                    t = get_text_recursive(heading).strip()
                    if t:
                        parts.append(f"## {t}")

            elif odf_type in ("ods", "odp"):
                for tbl in doc.getElementsByType(odf_table.Table):
                    table_name = tbl.getAttribute("name") or ""
                    if table_name:
                        parts.append(f"=== {table_name} ===")
                    for row in tbl.getElementsByType(odf_table.TableRow):
                        cells = []
                        for cell in row.getElementsByType(odf_table.TableCell):
                            cells.append(get_text_recursive(cell).strip())
                        if any(cells):
                            parts.append(" | ".join(cells))
                # Also get text paragraphs (for ODP slides)
                for para in doc.getElementsByType(odf_text.P):
                    t = get_text_recursive(para).strip()
                    if t:
                        parts.append(t)

            text = "\n\n".join(parts) if parts else "(empty document)"
            return ExtractedDocument(text=text, method=f"odf-{odf_type}", metadata={"odf_type": odf_type})
        except Exception as e:
            logger.warning("ODF %s extraction failed: %s — trying as text", odf_type, e)
            return self._extract_text_direct(file_bytes, f"document.{odf_type}")

    # ═══════════════════════════════════════════════════════════════════════════
    # RTF
    # ═══════════════════════════════════════════════════════════════════════════

    def _extract_rtf(self, file_bytes: bytes) -> ExtractedDocument:
        try:
            from striprtf.striprtf import rtf_to_text
            rtf_content = file_bytes.decode("utf-8", errors="replace")
            text = rtf_to_text(rtf_content)
            return ExtractedDocument(text=text, method="rtf")
        except Exception as e:
            logger.warning("RTF extraction failed: %s — trying as text", e)
            return self._extract_text_direct(file_bytes, "document.rtf")

    # ═══════════════════════════════════════════════════════════════════════════
    # Email: MSG (Outlook) + EML
    # ═══════════════════════════════════════════════════════════════════════════

    def _extract_msg(self, file_bytes: bytes) -> ExtractedDocument:
        """Extract Outlook .msg files."""
        try:
            import extract_msg
            import tempfile
            import os
            # extract_msg needs a file path
            with tempfile.NamedTemporaryFile(suffix=".msg", delete=False) as tmp:
                tmp.write(file_bytes)
                tmp_path = tmp.name
            try:
                msg = extract_msg.Message(tmp_path)
                parts = []
                if msg.subject:
                    parts.append(f"Subject: {msg.subject}")
                if msg.sender:
                    parts.append(f"From: {msg.sender}")
                if msg.to:
                    parts.append(f"To: {msg.to}")
                if msg.date:
                    parts.append(f"Date: {msg.date}")
                parts.append("")
                if msg.body:
                    parts.append(msg.body)
                text = "\n".join(parts)
                metadata = {
                    "subject": msg.subject or "",
                    "sender": msg.sender or "",
                    "attachment_count": len(msg.attachments),
                }
                msg.close()
                return ExtractedDocument(text=text, method="msg", metadata=metadata)
            finally:
                os.unlink(tmp_path)
        except Exception as e:
            logger.warning("MSG extraction failed: %s — trying as text", e)
            return self._extract_text_direct(file_bytes, "email.msg")

    def _extract_eml(self, file_bytes: bytes) -> ExtractedDocument:
        """Extract .eml files using stdlib email parser."""
        try:
            import email
            from email import policy
            msg = email.message_from_bytes(file_bytes, policy=policy.default)
            parts = []
            if msg["subject"]:
                parts.append(f"Subject: {msg['subject']}")
            if msg["from"]:
                parts.append(f"From: {msg['from']}")
            if msg["to"]:
                parts.append(f"To: {msg['to']}")
            if msg["date"]:
                parts.append(f"Date: {msg['date']}")
            parts.append("")

            body = msg.get_body(preferencelist=('plain', 'html'))
            if body:
                content = body.get_content()
                if body.get_content_type() == "text/html":
                    from bs4 import BeautifulSoup
                    soup = BeautifulSoup(content, "lxml")
                    content = soup.get_text(separator="\n", strip=True)
                parts.append(content)

            text = "\n".join(parts)
            return ExtractedDocument(text=text, method="eml",
                                     metadata={"subject": msg["subject"] or ""})
        except Exception as e:
            logger.warning("EML extraction failed: %s — trying as text", e)
            return self._extract_text_direct(file_bytes, "email.eml")

    # ═══════════════════════════════════════════════════════════════════════════
    # PDF (hybrid: pymupdf text + VLM for scanned pages)
    # ═══════════════════════════════════════════════════════════════════════════

    async def _extract_pdf(self, file_bytes: bytes, max_tier: str) -> ExtractedDocument:
        import fitz
        doc = fitz.open(stream=file_bytes, filetype="pdf")
        pages: list[PageContent] = []
        method = "pymupdf"
        has_vlm_pages = False

        for page_num in range(len(doc)):
            page = doc[page_num]
            page_text = page.get_text("text").strip()
            image_list = page.get_images(full=True)
            has_images = len(image_list) > 0
            is_scan = len(page_text) < 100

            if (has_images or is_scan) and (is_scan or not page_text):
                try:
                    pix = page.get_pixmap(dpi=200)
                    image_bytes = pix.tobytes("png")
                    vlm_text = await self._call_vlm(image_bytes, max_tier)
                    page_content = PageContent(
                        page_number=page_num + 1, text=vlm_text,
                        images=[ImageDescription(description=vlm_text)])
                    has_vlm_pages = True
                except Exception as e:
                    logger.warning("VLM failed for PDF page %d: %s — using pymupdf text", page_num + 1, e)
                    page_content = PageContent(page_number=page_num + 1, text=page_text)
            else:
                page_content = PageContent(page_number=page_num + 1, text=page_text)
            pages.append(page_content)

        doc.close()
        if has_vlm_pages:
            method = "hybrid"

        full_text = "\n\n".join(f"--- Page {p.page_number} ---\n{p.text}" for p in pages if p.text)
        return ExtractedDocument(text=full_text, pages=pages, method=method,
                                 metadata={"page_count": len(pages), "has_images": has_vlm_pages})

    # ═══════════════════════════════════════════════════════════════════════════
    # Image (VLM)
    # ═══════════════════════════════════════════════════════════════════════════

    async def _extract_image(self, file_bytes: bytes, max_tier: str) -> ExtractedDocument:
        text = await self._call_vlm(file_bytes, max_tier)
        return ExtractedDocument(text=text, method="vlm", metadata={"type": "image"})

    async def _call_vlm(self, image_bytes: bytes, max_tier: str) -> str:
        """VLM call via Ollama router — router decides GPU/cloud based on tier."""
        b64 = base64.b64encode(image_bytes).decode("ascii")

        prompt = ("Extract all text, data, and describe all visual elements "
                  "(diagrams, charts, tables, images) from this document. "
                  "Preserve the original language. Output plain text.")

        payload = {
            "model": "qwen2.5-vl:32b",
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}},
            ]}],
            "max_tokens": 4096,
            "temperature": 0,
        }

        headers = {"X-Max-Tier": max_tier}

        async with httpx.AsyncClient(timeout=120.0) as client:
            resp = await client.post(
                f"{self.router_url}/v1/chat/completions",
                json=payload, headers=headers,
            )
            resp.raise_for_status()
            data = resp.json()

        text = data["choices"][0]["message"]["content"]
        logger.info("VLM response: %d chars (tier=%s)", len(text), max_tier)
        return text
