"""
Live Meeting Helper pipeline.

Runs while recording is active + helper enabled. Processes audio transcription
chunks and generates real-time assistance (translation, Q&A prediction,
answer suggestions) pushed to selected device via WebSocket.

Pipeline:
  Audio chunk (from recording)
    -> Whisper transcription (GPU VD)
    -> Context accumulator (rolling 5-min window)
    -> Parallel LLM analysis:
        a) Translation (if non-Czech meeting) -> push to device
        b) Q&A predictor: anticipate questions -> push to device
        c) Answer suggester: draft responses -> push to device
    -> WebSocket push to device
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from collections import deque

logger = logging.getLogger(__name__)


@dataclass
class HelperMessage:
    """Message to push to the helper device."""
    type: str  # "translation", "suggestion", "question_predict"
    text: str
    context: str = ""
    from_lang: str = ""
    to_lang: str = ""
    timestamp: str = ""

    def to_json(self) -> str:
        return json.dumps({
            "type": self.type,
            "text": self.text,
            "context": self.context,
            "from": self.from_lang,
            "to": self.to_lang,
            "timestamp": self.timestamp,
        }, ensure_ascii=False)


@dataclass
class HelperSession:
    """Active meeting helper session state."""
    meeting_id: str
    device_id: str
    source_lang: str = "en"
    target_lang: str = "cs"
    active: bool = True
    # Rolling context window (last 5 minutes of transcript segments)
    context_window: deque = field(default_factory=lambda: deque(maxlen=30))
    # Track last analysis timestamps to avoid duplicate processing
    last_translation_at: float = 0.0
    last_suggestion_at: float = 0.0
    last_qa_predict_at: float = 0.0


# Active helper sessions: meeting_id -> HelperSession
_sessions: dict[str, HelperSession] = {}

# WebSocket connections for devices: device_id -> list of send callbacks
_device_connections: dict[str, list] = {}


def register_device_connection(device_id: str, send_fn):
    """Register a WebSocket send function for a device."""
    if device_id not in _device_connections:
        _device_connections[device_id] = []
    _device_connections[device_id].append(send_fn)
    logger.info("Device %s connected for meeting helper", device_id)


def unregister_device_connection(device_id: str, send_fn):
    """Unregister a WebSocket send function."""
    if device_id in _device_connections:
        _device_connections[device_id] = [
            fn for fn in _device_connections[device_id] if fn is not send_fn
        ]
        if not _device_connections[device_id]:
            del _device_connections[device_id]
    logger.info("Device %s disconnected from meeting helper", device_id)


async def push_to_device(device_id: str, message: HelperMessage):
    """Push a helper message to the connected device."""
    connections = _device_connections.get(device_id, [])
    if not connections:
        logger.debug("No connections for device %s, dropping message", device_id)
        return

    msg_json = message.to_json()
    for send_fn in connections:
        try:
            await send_fn(msg_json)
        except Exception as e:
            logger.warning("Failed to push to device %s: %s", device_id, e)


def start_session(meeting_id: str, device_id: str, source_lang: str = "en", target_lang: str = "cs") -> HelperSession:
    """Start a new meeting helper session."""
    session = HelperSession(
        meeting_id=meeting_id,
        device_id=device_id,
        source_lang=source_lang,
        target_lang=target_lang,
    )
    _sessions[meeting_id] = session
    logger.info("Meeting helper started: meeting=%s, device=%s, %s->%s", meeting_id, device_id, source_lang, target_lang)
    return session


def stop_session(meeting_id: str):
    """Stop a meeting helper session."""
    session = _sessions.pop(meeting_id, None)
    if session:
        session.active = False
        logger.info("Meeting helper stopped: meeting=%s", meeting_id)


def get_session(meeting_id: str) -> HelperSession | None:
    """Get active helper session for a meeting."""
    return _sessions.get(meeting_id)


async def process_transcript_chunk(
    meeting_id: str,
    transcript_text: str,
    speaker: str = "",
    llm_provider=None,
) -> list[HelperMessage]:
    """
    Process a new transcript chunk and generate helper messages.

    Called whenever a new Whisper segment is transcribed. Runs analysis
    in parallel and pushes results to the device.
    """
    session = _sessions.get(meeting_id)
    if not session or not session.active:
        return []

    now = time.time()
    timestamp = time.strftime("%H:%M:%S")

    # Add to rolling context window
    session.context_window.append({
        "text": transcript_text,
        "speaker": speaker,
        "time": timestamp,
    })

    # Build context from window
    context_text = "\n".join(
        f"[{seg['time']}] {seg.get('speaker', '')}: {seg['text']}"
        for seg in session.context_window
    )

    messages: list[HelperMessage] = []

    if not llm_provider:
        logger.debug("No LLM provider for meeting helper, skipping analysis")
        return messages

    # Run translation, suggestions, and Q&A prediction in parallel
    tasks = []

    # Translation (every chunk)
    if session.source_lang != session.target_lang:
        tasks.append(_translate(
            transcript_text, session.source_lang, session.target_lang,
            timestamp, llm_provider,
        ))

    # Answer suggestions (every 30 seconds to avoid flooding)
    if now - session.last_suggestion_at > 30:
        session.last_suggestion_at = now
        tasks.append(_suggest_answers(
            context_text, session.target_lang, timestamp, llm_provider,
        ))

    # Q&A prediction (every 45 seconds — anticipate upcoming questions)
    if now - session.last_qa_predict_at > 45 and len(session.context_window) >= 5:
        session.last_qa_predict_at = now
        tasks.append(_predict_questions(
            context_text, session.target_lang, timestamp, llm_provider,
        ))

    if tasks:
        results = await asyncio.gather(*tasks, return_exceptions=True)
        for result in results:
            if isinstance(result, HelperMessage):
                messages.append(result)
                await push_to_device(session.device_id, result)
            elif isinstance(result, Exception):
                logger.warning("Helper analysis failed: %s", result)

    return messages


async def _translate(
    text: str,
    source_lang: str,
    target_lang: str,
    timestamp: str,
    llm_provider,
) -> HelperMessage:
    """Translate a transcript segment."""
    from app.llm.provider import ModelTier

    lang_names = {
        "cs": "Czech", "en": "English", "de": "German",
        "fr": "French", "es": "Spanish", "pl": "Polish",
    }
    src_name = lang_names.get(source_lang, source_lang)
    tgt_name = lang_names.get(target_lang, target_lang)

    response = await llm_provider.completion(
        messages=[{
            "role": "user",
            "content": f"Translate from {src_name} to {tgt_name}. Return ONLY the translation, nothing else.\n\n{text}",
        }],
        model_tier=ModelTier.LOCAL_STANDARD,
        max_tokens=500,
        temperature=0.3,
    )

    translated = response.choices[0].message.content.strip()
    return HelperMessage(
        type="translation",
        text=translated,
        from_lang=source_lang,
        to_lang=target_lang,
        timestamp=timestamp,
    )


async def _suggest_answers(
    context: str,
    target_lang: str,
    timestamp: str,
    llm_provider,
) -> HelperMessage:
    """Analyze conversation and suggest potential answers/responses."""
    from app.llm.provider import ModelTier

    lang_instruction = "Respond in Czech." if target_lang == "cs" else f"Respond in {target_lang}."

    response = await llm_provider.completion(
        messages=[{
            "role": "user",
            "content": (
                f"You are a meeting assistant. Analyze this conversation and provide:\n"
                f"1. If someone asked a question, suggest a brief answer\n"
                f"2. If a topic needs the user's input, suggest what to say\n"
                f"3. If nothing actionable, respond with 'OK'\n\n"
                f"{lang_instruction}\n"
                f"Be brief (1-2 sentences max).\n\n"
                f"Conversation:\n{context}"
            ),
        }],
        model_tier=ModelTier.LOCAL_STANDARD,
        max_tokens=300,
        temperature=0.5,
    )

    suggestion = response.choices[0].message.content.strip()
    if suggestion.upper() in ("OK", "OK.", "N/A", "-"):
        # No actionable suggestion
        return HelperMessage(type="suggestion", text="", timestamp=timestamp)

    return HelperMessage(
        type="suggestion",
        text=suggestion,
        context="Based on recent conversation",
        timestamp=timestamp,
    )


async def _predict_questions(
    context: str,
    target_lang: str,
    timestamp: str,
    llm_provider,
) -> HelperMessage:
    """Predict upcoming questions based on conversation flow.

    Analyzes the conversation context to anticipate what questions
    the other participants are likely to ask next, giving the user
    time to prepare answers.
    """
    from app.llm.provider import ModelTier

    lang_instruction = "Respond in Czech." if target_lang == "cs" else f"Respond in {target_lang}."

    response = await llm_provider.completion(
        messages=[{
            "role": "user",
            "content": (
                f"You are a meeting analyst. Based on this conversation, predict "
                f"the most likely NEXT question someone will ask the user.\n\n"
                f"Rules:\n"
                f"- Analyze the conversation flow and topics being discussed\n"
                f"- Predict 1 specific question that is likely coming next\n"
                f"- If you also know a good answer, include it briefly\n"
                f"- If no question is likely, respond with 'OK'\n\n"
                f"{lang_instruction}\n"
                f"Format: 'Question: ... | Answer hint: ...'\n\n"
                f"Conversation:\n{context}"
            ),
        }],
        model_tier=ModelTier.LOCAL_STANDARD,
        max_tokens=300,
        temperature=0.5,
    )

    prediction = response.choices[0].message.content.strip()
    if prediction.upper() in ("OK", "OK.", "N/A", "-"):
        return HelperMessage(type="question_predict", text="", timestamp=timestamp)

    return HelperMessage(
        type="question_predict",
        text=prediction,
        context="Anticipated based on conversation flow",
        timestamp=timestamp,
    )
