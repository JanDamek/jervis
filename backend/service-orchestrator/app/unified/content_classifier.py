"""
Content-Type Classifier for email intelligence.

Classifies email content BEFORE qualification into content types
(JOB_OFFER, INVOICE, NEWSLETTER, etc.) using rule-based fast checks
with LLM fallback for ambiguous cases.
"""

import re
import logging
from enum import Enum
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


class ContentType(str, Enum):
    JOB_OFFER = "JOB_OFFER"
    INVOICE = "INVOICE"
    CONTRACT = "CONTRACT"
    BUG_REPORT = "BUG_REPORT"
    SUPPORT_REQUEST = "SUPPORT_REQUEST"
    MEETING_REQUEST = "MEETING_REQUEST"
    NEWSLETTER = "NEWSLETTER"
    PERSONAL = "PERSONAL"
    OTHER = "OTHER"


@dataclass
class ContentClassification:
    content_type: ContentType
    confidence: float
    reason: str
    sub_type: str = ""
    detected_by: str = "rules"  # "rules" or "llm"


# Rule-based patterns for fast classification
NEWSLETTER_SENDER_PATTERNS = [
    r"noreply@", r"no-reply@", r"newsletter@", r"news@",
    r"marketing@", r"info@.*\.com$", r"mailer-daemon@",
    r"notifications@", r"updates@", r"digest@",
    r"donotreply@", r"do-not-reply@", r"bounce@",
]

NEWSLETTER_SUBJECT_PATTERNS = [
    r"newsletter", r"unsubscribe", r"weekly digest",
    r"monthly update", r"daily brief", r"promotional",
    r"special offer", r"limited time", r"sale\b",
]

INVOICE_SUBJECT_PATTERNS = [
    r"faktura", r"invoice", r"platba", r"payment",
    r"účtenka", r"receipt", r"vyúčtování", r"billing",
    r"dobropis", r"credit note", r"daňov[ýá] doklad",
    r"proforma", r"záloh", r"advance payment",
]

INVOICE_ATTACHMENT_PATTERNS = [
    r"faktura", r"invoice", r"fa[\-_]?\d+",
    r"doklad", r"receipt", r"platba",
]

JOB_OFFER_SUBJECT_PATTERNS = [
    r"job\s+(offer|opportunity|opening|position)",
    r"freelance", r"contractor", r"nabídka\s+práce",
    r"project\s+(opportunity|inquiry|proposal)",
    r"looking\s+for", r"hiring", r"recruitment",
    r"developer\s+needed", r"engineer\s+needed",
    r"zakázka", r"spolupráce", r"poptávka",
]

JOB_OFFER_SENDER_PATTERNS = [
    r"@guru\.com", r"@upwork\.com", r"@freelancer\.com",
    r"@toptal\.com", r"@fiverr\.com", r"@linkedin\.com",
    r"@indeed\.com", r"@glassdoor\.com",
]

CONTRACT_SUBJECT_PATTERNS = [
    r"smlouva", r"contract", r"dohoda", r"agreement",
    r"nda\b", r"smluvní", r"terms", r"podmínky",
    r"dodatek", r"amendment", r"objednávka",
]

BUG_REPORT_SUBJECT_PATTERNS = [
    r"\bbug\b", r"\berror\b", r"\bchyba\b", r"\bincident\b",
    r"\bcrash\b", r"\bfail", r"\bdown\b", r"\boutage\b",
    r"\bsev[\s-]?[12]\b", r"\bp[12]\b", r"\bcritical\b",
]

SUPPORT_REQUEST_PATTERNS = [
    r"help\b", r"pomoc", r"support", r"question",
    r"dotaz", r"problém", r"issue\b", r"požadavek",
    r"how\s+to", r"jak\s+", r"can\s+you",
]

MEETING_REQUEST_PATTERNS = [
    r"meeting\s+(request|invite|proposal)",
    r"schůzka", r"pozvánka", r"calendar",
    r"let'?s\s+meet", r"call\s+scheduled",
    r"teams\s+meeting", r"zoom\s+meeting",
    r"sejít\s+se", r"videohovor",
]


def _matches_any(text: str, patterns: list[str]) -> bool:
    if not text:
        return False
    text_lower = text.lower()
    return any(re.search(p, text_lower) for p in patterns)


def _check_attachment_names(attachments: list[dict], patterns: list[str]) -> bool:
    for att in attachments:
        filename = att.get("filename", "").lower()
        if any(re.search(p, filename) for p in patterns):
            return True
    return False


def classify_content(
    subject: str | None,
    sender: str | None,
    body_text: str | None,
    attachments: list[dict] | None = None,
) -> ContentClassification:
    """
    Rule-based content classification.

    Fast path that catches ~80% of cases without LLM.
    Returns classification with confidence. For ambiguous cases
    (confidence < 0.6), the caller should use LLM classification.
    """
    subject = subject or ""
    sender = sender or ""
    body_text = body_text or ""
    attachments = attachments or []

    # 1. Newsletter detection (highest priority — auto-DONE)
    if _matches_any(sender, NEWSLETTER_SENDER_PATTERNS):
        return ContentClassification(
            content_type=ContentType.NEWSLETTER,
            confidence=0.9,
            reason=f"Sender pattern matches newsletter: {sender}",
            detected_by="rules",
        )
    if _matches_any(subject, NEWSLETTER_SUBJECT_PATTERNS):
        return ContentClassification(
            content_type=ContentType.NEWSLETTER,
            confidence=0.8,
            reason=f"Subject contains newsletter keywords",
            detected_by="rules",
        )

    # 2. Invoice detection
    if _matches_any(subject, INVOICE_SUBJECT_PATTERNS):
        return ContentClassification(
            content_type=ContentType.INVOICE,
            confidence=0.85,
            reason=f"Subject contains invoice keywords",
            detected_by="rules",
        )
    if _check_attachment_names(attachments, INVOICE_ATTACHMENT_PATTERNS):
        return ContentClassification(
            content_type=ContentType.INVOICE,
            confidence=0.8,
            reason=f"Attachment filename suggests invoice",
            detected_by="rules",
        )

    # 3. Job offer detection
    if _matches_any(sender, JOB_OFFER_SENDER_PATTERNS):
        return ContentClassification(
            content_type=ContentType.JOB_OFFER,
            confidence=0.9,
            reason=f"Sender from known job platform",
            detected_by="rules",
        )
    if _matches_any(subject, JOB_OFFER_SUBJECT_PATTERNS):
        return ContentClassification(
            content_type=ContentType.JOB_OFFER,
            confidence=0.8,
            reason=f"Subject suggests job opportunity",
            detected_by="rules",
        )

    # 4. Contract detection
    if _matches_any(subject, CONTRACT_SUBJECT_PATTERNS):
        return ContentClassification(
            content_type=ContentType.CONTRACT,
            confidence=0.75,
            reason=f"Subject contains contract keywords",
            detected_by="rules",
        )

    # 5. Bug report detection
    if _matches_any(subject, BUG_REPORT_SUBJECT_PATTERNS):
        return ContentClassification(
            content_type=ContentType.BUG_REPORT,
            confidence=0.75,
            reason=f"Subject contains bug/error keywords",
            detected_by="rules",
        )

    # 6. Meeting request detection
    if _matches_any(subject, MEETING_REQUEST_PATTERNS):
        return ContentClassification(
            content_type=ContentType.MEETING_REQUEST,
            confidence=0.8,
            reason=f"Subject suggests meeting request",
            detected_by="rules",
        )

    # 7. Support request detection
    if _matches_any(subject, SUPPORT_REQUEST_PATTERNS):
        return ContentClassification(
            content_type=ContentType.SUPPORT_REQUEST,
            confidence=0.6,
            reason=f"Subject suggests support request",
            detected_by="rules",
        )

    # 8. No rule matched → OTHER (low confidence, LLM should classify)
    return ContentClassification(
        content_type=ContentType.OTHER,
        confidence=0.3,
        reason="No rule-based match, needs LLM classification",
        detected_by="rules",
    )


LLM_CLASSIFICATION_PROMPT = """Classify this email into exactly ONE category.

Categories:
- JOB_OFFER: freelance opportunity, project inquiry, recruitment, work proposal
- INVOICE: invoice, payment request, receipt, bank statement, billing
- CONTRACT: contract, NDA, agreement, terms and conditions
- BUG_REPORT: error report, bug, incident, system failure
- SUPPORT_REQUEST: help request, question, technical support
- MEETING_REQUEST: calendar invite, meeting proposal, call scheduling
- NEWSLETTER: bulk mail, marketing, promotional, automated notification
- PERSONAL: personal communication, casual conversation
- OTHER: none of the above

Email:
Subject: {subject}
From: {sender}
Body (first 500 chars): {body_preview}
Attachments: {attachments}

Respond with ONLY a JSON object:
{{"content_type": "<CATEGORY>", "confidence": <0.0-1.0>, "reason": "<brief reason>"}}
"""


async def classify_with_llm(
    subject: str | None,
    sender: str | None,
    body_text: str | None,
    attachments: list[dict] | None = None,
    llm_provider=None,
) -> ContentClassification:
    """
    LLM-based classification for ambiguous emails.

    Uses LOCAL_COMPACT model for speed. Called when rule-based
    classification returns confidence < 0.6.
    """
    import json

    if llm_provider is None:
        logger.warning("No LLM provider for content classification, using rules-only result")
        return classify_content(subject, sender, body_text, attachments)

    att_names = ", ".join(a.get("filename", "unknown") for a in (attachments or [])) or "none"
    prompt = LLM_CLASSIFICATION_PROMPT.format(
        subject=subject or "(no subject)",
        sender=sender or "(unknown sender)",
        body_preview=(body_text or "")[:500],
        attachments=att_names,
    )

    try:
        from app.llm.provider import ModelTier
        response = await llm_provider.completion(
            messages=[{"role": "user", "content": prompt}],
            model_tier=ModelTier.LOCAL_COMPACT,
            max_tokens=200,
            temperature=0.1,
        )

        content = response.choices[0].message.content.strip()
        # Extract JSON from response
        json_match = re.search(r'\{[^}]+\}', content)
        if json_match:
            result = json.loads(json_match.group())
            content_type = ContentType(result.get("content_type", "OTHER"))
            return ContentClassification(
                content_type=content_type,
                confidence=min(float(result.get("confidence", 0.7)), 1.0),
                reason=result.get("reason", "LLM classification"),
                detected_by="llm",
            )
    except Exception as e:
        logger.warning(f"LLM classification failed: {e}")

    # Fallback to rules
    return classify_content(subject, sender, body_text, attachments)
