"""Chunk accumulator for streaming voice input.

Buffers 5-second audio chunks during recording, accumulates
transcription text, and detects sentence boundaries for
preliminary processing.
"""

from __future__ import annotations

import re
import logging
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)

# Czech sentence boundary pattern
_SENTENCE_END = re.compile(r'[.!?]\s+')


@dataclass
class AccumulatedChunk:
    """Result of accumulating a new chunk."""
    full_text: str                   # All accumulated text so far
    new_text: str                    # Just this chunk's text
    chunk_index: int                 # 0-based chunk number
    complete_sentences: list[str]    # Sentences completed by this chunk
    pending_fragment: str            # Incomplete sentence at the end
    is_final: bool = False           # True if this is the last chunk


class ChunkAccumulator:
    """Accumulates transcribed chunks and detects sentence boundaries.

    Usage:
        acc = ChunkAccumulator()
        result = acc.add_chunk("Kolik dlužíme", chunk_index=0)
        result = acc.add_chunk("Alze? A kdy je", chunk_index=1)
        # result.complete_sentences = ["Kolik dlužíme Alze?"]
        # result.pending_fragment = "A kdy je"
        result = acc.finalize("schůzka?")
        # result.complete_sentences = ["A kdy je schůzka?"]
    """

    def __init__(self):
        self._chunks: list[str] = []
        self._processed_up_to: int = 0  # Index into full text of last sentence boundary

    def add_chunk(self, text: str, chunk_index: int) -> AccumulatedChunk:
        """Add a new chunk of transcribed text."""
        self._chunks.append(text)
        full_text = " ".join(self._chunks)

        # Find sentence boundaries in the unprocessed portion
        unprocessed = full_text[self._processed_up_to:]
        sentences = []

        for match in _SENTENCE_END.finditer(unprocessed):
            end = match.end()
            sentence = unprocessed[:end].strip()
            if sentence:
                sentences.append(sentence)
            unprocessed = unprocessed[end:]
            self._processed_up_to += end

        pending = full_text[self._processed_up_to:].strip()

        return AccumulatedChunk(
            full_text=full_text,
            new_text=text,
            chunk_index=chunk_index,
            complete_sentences=sentences,
            pending_fragment=pending,
        )

    def finalize(self, last_text: str = "") -> AccumulatedChunk:
        """Finalize accumulation — treat remaining fragment as a complete sentence."""
        if last_text:
            self._chunks.append(last_text)

        full_text = " ".join(self._chunks)
        remaining = full_text[self._processed_up_to:].strip()

        sentences = []
        if remaining:
            sentences.append(remaining)

        return AccumulatedChunk(
            full_text=full_text,
            new_text=last_text,
            chunk_index=len(self._chunks) - 1,
            complete_sentences=sentences,
            pending_fragment="",
            is_final=True,
        )

    def reset(self):
        """Reset accumulator for a new recording session."""
        self._chunks.clear()
        self._processed_up_to = 0
