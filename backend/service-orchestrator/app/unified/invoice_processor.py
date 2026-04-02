"""
Invoice Processor for email intelligence.

When content_type == INVOICE:
1. Extract invoice fields from email body and attachments
2. Resolve client via ClientResolver
3. Create financial record (KB entry for now, Phase 4 adds proper module)
4. If due date < 7 days -> flag as urgent
"""

import re
import json
import logging
from dataclasses import dataclass, field
from datetime import datetime, timedelta

logger = logging.getLogger(__name__)


@dataclass
class InvoiceData:
    invoice_number: str = ""
    amount: float | None = None
    currency: str = "CZK"
    vat_rate: float | None = None
    vat_amount: float | None = None
    due_date: str | None = None  # ISO format
    issue_date: str | None = None  # ISO format
    variable_symbol: str = ""
    counterparty_name: str = ""
    counterparty_ico: str = ""
    counterparty_account: str = ""
    description: str = ""
    is_urgent: bool = False
    days_until_due: int | None = None


INVOICE_EXTRACTION_PROMPT = """Extract invoice/payment data from this email.

Email:
Subject: {subject}
From: {sender}
Body: {body}

Extract as JSON:
{{
    "invoice_number": "invoice number or empty string",
    "amount": null or numeric amount,
    "currency": "CZK/EUR/USD/etc",
    "vat_rate": null or VAT percentage (e.g. 21),
    "vat_amount": null or VAT amount,
    "due_date": null or "YYYY-MM-DD",
    "issue_date": null or "YYYY-MM-DD",
    "variable_symbol": "variabilní symbol or empty",
    "counterparty_name": "company or person name",
    "counterparty_ico": "IČO if found",
    "counterparty_account": "bank account number if found",
    "description": "brief description of what the invoice is for"
}}

Look for Czech terms: faktura, částka, DPH, datum splatnosti, VS (variabilní symbol), IČO.
Respond with ONLY the JSON object.
"""


async def process_invoice(
    subject: str | None,
    sender: str | None,
    body_text: str | None,
    attachments: list[dict] | None = None,
    llm_provider=None,
    kb_store_fn=None,
    client_id: str | None = None,
) -> InvoiceData:
    """
    Process an invoice email and extract structured data.

    Uses LLM for extraction with regex fallback for common patterns.
    """
    invoice = InvoiceData()
    body = body_text or ""

    # Try LLM extraction first
    if llm_provider:
        try:
            from app.llm.provider import ModelTier
            prompt = INVOICE_EXTRACTION_PROMPT.format(
                subject=subject or "(no subject)",
                sender=sender or "(unknown)",
                body=body[:3000],
            )
            response = await llm_provider.completion(
                messages=[{"role": "user", "content": prompt}],
                model_tier=ModelTier.LOCAL_COMPACT,
                max_tokens=500,
                temperature=0.1,
            )
            content = response.choices[0].message.content.strip()
            json_match = re.search(r'\{[\s\S]*\}', content)
            if json_match:
                data = json.loads(json_match.group())
                invoice.invoice_number = data.get("invoice_number", "")
                invoice.amount = data.get("amount")
                invoice.currency = data.get("currency", "CZK")
                invoice.vat_rate = data.get("vat_rate")
                invoice.vat_amount = data.get("vat_amount")
                invoice.due_date = data.get("due_date")
                invoice.issue_date = data.get("issue_date")
                invoice.variable_symbol = data.get("variable_symbol", "")
                invoice.counterparty_name = data.get("counterparty_name", "")
                invoice.counterparty_ico = data.get("counterparty_ico", "")
                invoice.counterparty_account = data.get("counterparty_account", "")
                invoice.description = data.get("description", "")
        except Exception as e:
            logger.warning(f"LLM invoice extraction failed: {e}")

    # Regex fallback for key fields if LLM didn't extract them
    if not invoice.invoice_number:
        match = re.search(r'(?:faktura|invoice|fa)[\s#:]*([A-Z0-9\-/]+)', body, re.IGNORECASE)
        if match:
            invoice.invoice_number = match.group(1)

    if not invoice.variable_symbol:
        match = re.search(r'(?:VS|variabilní\s+symbol|var\.?\s*sym\.?)[\s:]*(\d+)', body, re.IGNORECASE)
        if match:
            invoice.variable_symbol = match.group(1)

    if invoice.amount is None:
        # Try to find amount patterns like "1 234,56 CZK" or "€1,234.56"
        match = re.search(
            r'(?:částka|amount|celkem|total)[\s:]*([0-9\s]+[.,]\d{2})\s*(CZK|Kč|EUR|USD|€|\$)?',
            body, re.IGNORECASE,
        )
        if match:
            amount_str = match.group(1).replace(" ", "").replace(",", ".")
            try:
                invoice.amount = float(amount_str)
            except ValueError:
                pass
            if match.group(2):
                currency = match.group(2).strip()
                currency_map = {"Kč": "CZK", "€": "EUR", "$": "USD"}
                invoice.currency = currency_map.get(currency, currency)

    if not invoice.counterparty_ico:
        match = re.search(r'(?:IČO?|IC|IČ)[\s:]*(\d{8})', body, re.IGNORECASE)
        if match:
            invoice.counterparty_ico = match.group(1)

    # Check urgency (due date within 7 days)
    if invoice.due_date:
        try:
            due = datetime.fromisoformat(invoice.due_date)
            now = datetime.now()
            delta = (due - now).days
            invoice.days_until_due = delta
            if delta < 7:
                invoice.is_urgent = True
        except ValueError:
            pass

    # Store as KB entry for tracking
    if kb_store_fn and invoice.invoice_number:
        try:
            kb_content = format_invoice_for_kb(invoice, sender)
            await kb_store_fn(
                content=kb_content,
                kind="finding",
                source_urn=f"invoice:{invoice.invoice_number}",
                client_id=client_id,
            )
        except Exception as e:
            logger.warning(f"Failed to store invoice in KB: {e}")

    # POST to Financial Module for auto-matching and tracking
    if client_id and invoice.amount is not None:
        try:
            await _post_financial_record(invoice, client_id, sender)
        except Exception as e:
            logger.warning(f"Failed to post financial record: {e}")

    logger.info(
        f"Invoice processed: #{invoice.invoice_number}, "
        f"amount={invoice.amount} {invoice.currency}, "
        f"due={invoice.due_date}, urgent={invoice.is_urgent}"
    )

    return invoice


def format_invoice_for_kb(invoice: InvoiceData, sender: str | None = None) -> str:
    """Format invoice data for KB storage."""
    lines = [
        f"Invoice: {invoice.invoice_number}",
        f"From: {invoice.counterparty_name or sender or 'unknown'}",
    ]
    if invoice.amount:
        lines.append(f"Amount: {invoice.amount:,.2f} {invoice.currency}")
    if invoice.vat_rate:
        lines.append(f"VAT: {invoice.vat_rate}%")
    if invoice.due_date:
        lines.append(f"Due: {invoice.due_date}")
    if invoice.variable_symbol:
        lines.append(f"VS: {invoice.variable_symbol}")
    if invoice.counterparty_ico:
        lines.append(f"ICO: {invoice.counterparty_ico}")
    if invoice.description:
        lines.append(f"Description: {invoice.description}")
    return "\n".join(lines)


async def _post_financial_record(invoice: InvoiceData, client_id: str, sender: str | None = None) -> None:
    """POST extracted invoice data to Kotlin Financial Module for auto-matching."""
    import httpx
    from app.settings import settings

    payload = {
        "clientId": client_id,
        "type": "INVOICE_IN",
        "amount": invoice.amount,
        "currency": invoice.currency,
        "invoiceNumber": invoice.invoice_number or None,
        "variableSymbol": invoice.variable_symbol or None,
        "counterpartyName": invoice.counterparty_name or sender or None,
        "counterpartyIco": invoice.counterparty_ico or None,
        "counterpartyAccount": invoice.counterparty_account or None,
        "issueDate": invoice.issue_date,
        "dueDate": invoice.due_date,
        "sourceUrn": f"email:invoice:{invoice.invoice_number or 'unknown'}",
        "description": invoice.description or "",
    }
    if invoice.vat_rate:
        payload["vatRate"] = invoice.vat_rate
    if invoice.vat_amount:
        payload["vatAmount"] = invoice.vat_amount

    url = f"{settings.kotlin_server_url}/internal/finance/record"
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.post(url, json=payload)
        data = resp.json()
        matched = data.get("matched", False)
        record_id = data.get("id", "?")
        logger.info(f"Financial record created: {record_id}, matched={matched}")


def format_invoice_for_task(invoice: InvoiceData) -> str:
    """Format invoice data as task content for USER_TASK."""
    urgency = ""
    if invoice.is_urgent:
        urgency = f" **URGENTNÍ — splatnost za {invoice.days_until_due} dní!**"

    lines = [
        f"# Faktura: {invoice.invoice_number or 'bez čísla'}{urgency}",
        "",
    ]

    if invoice.counterparty_name:
        lines.append(f"**Dodavatel:** {invoice.counterparty_name}")
    if invoice.amount:
        lines.append(f"**Částka:** {invoice.amount:,.2f} {invoice.currency}")
    if invoice.vat_rate:
        vat_info = f"{invoice.vat_rate}%"
        if invoice.vat_amount:
            vat_info += f" ({invoice.vat_amount:,.2f} {invoice.currency})"
        lines.append(f"**DPH:** {vat_info}")
    if invoice.due_date:
        lines.append(f"**Datum splatnosti:** {invoice.due_date}")
    if invoice.issue_date:
        lines.append(f"**Datum vystavení:** {invoice.issue_date}")
    if invoice.variable_symbol:
        lines.append(f"**VS:** {invoice.variable_symbol}")
    if invoice.counterparty_ico:
        lines.append(f"**IČO:** {invoice.counterparty_ico}")
    if invoice.counterparty_account:
        lines.append(f"**Účet:** {invoice.counterparty_account}")
    if invoice.description:
        lines.append(f"**Popis:** {invoice.description}")

    return "\n".join(lines)
