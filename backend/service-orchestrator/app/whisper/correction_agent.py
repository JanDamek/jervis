"""
Whisper transcript correction agent.

Stores/retrieves correction rules from KB and applies them via Ollama GPU.
Correction rules are regular KB chunks with kind="transcript_correction",
making them available to any agent with KB access (orchestrator, correction agent, etc.).

The agent lives in the orchestrator service to share Ollama GPU access and avoid
duplicating LLM calls across services.

Interactive mode: when the agent is uncertain about corrections, it generates
questions for the user. Answers are saved as correction rules for future use.
"""

import asyncio
import json
import logging
import re
import time
import uuid
from typing import Any

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

CORRECTION_KIND = "transcript_correction"
CHUNK_SIZE = 20
MAX_RETRIES = 1
# Output budget for non-reasoning model: JSON only (~4k tokens for 20 segments)
OUTPUT_BUDGET = 8192
# GPU VRAM cap — models above this spill to CPU RAM and become slow
GPU_CTX_CAP = 49152
# Streaming heartbeat: no token for this long = dead
HEARTBEAT_DEAD_SECONDS = 300  # 5 min
# How often to emit progress during streaming
PROGRESS_EMIT_INTERVAL = 10  # seconds


class HeartbeatTimeoutError(Exception):
    """Ollama stopped sending tokens (heartbeat dead)."""
    pass

CATEGORY_LABELS = {
    "person_name": "Person Names / Jmena osob",
    "company_name": "Company Names / Nazvy firem",
    "department": "Departments / Oddeleni",
    "terminology": "Terminology / Terminologie",
    "abbreviation": "Abbreviations / Zkratky",
    "general": "General Corrections / Obecne opravy",
}


class CorrectionAgent:
    """Transcript correction agent backed by KB + Ollama."""

    def __init__(self):
        self.kb_url = f"{settings.knowledgebase_url}/api/v1"
        self.ollama_url = settings.ollama_url
        self.model = settings.default_correction_model  # qwen3-coder-tool:30b (num_ctx overridden dynamically)

    async def submit_correction(
        self,
        client_id: str,
        project_id: str | None,
        original: str,
        corrected: str,
        category: str = "general",
        context: str | None = None,
    ) -> dict:
        """Store a correction rule as a KB chunk."""
        correction_id = str(uuid.uuid4())
        source_urn = f"transcript-correction::{correction_id}"

        content = self._build_correction_content(original, corrected, category, context)

        ingest_payload = {
            "clientId": client_id,
            "projectId": project_id,
            "sourceUrn": source_urn,
            "kind": CORRECTION_KIND,
            "content": content,
            "metadata": {
                "original": original,
                "corrected": corrected,
                "category": category,
                "context": context or "",
                "correctionId": correction_id,
            },
        }

        async with httpx.AsyncClient(timeout=120) as http:
            resp = await http.post(f"{self.kb_url}/ingest", json=ingest_payload)
            resp.raise_for_status()

        logger.info(
            "Stored correction: '%s' -> '%s' (category=%s, sourceUrn=%s)",
            original, corrected, category, source_urn,
        )
        return {
            "correctionId": correction_id,
            "sourceUrn": source_urn,
            "status": "success",
        }

    async def delete_correction(self, source_urn: str) -> dict:
        """Delete a correction from KB."""
        async with httpx.AsyncClient(timeout=120) as http:
            resp = await http.post(
                f"{self.kb_url}/purge",
                json={"sourceUrn": source_urn},
            )
            resp.raise_for_status()
            return resp.json()

    async def list_corrections(
        self,
        client_id: str,
        project_id: str | None = None,
        max_results: int = 100,
    ) -> list[dict]:
        """List all corrections for a client/project from KB."""
        async with httpx.AsyncClient(timeout=120) as http:
            resp = await http.post(
                f"{self.kb_url}/chunks/by-kind",
                json={
                    "clientId": client_id,
                    "projectId": project_id,
                    "kind": CORRECTION_KIND,
                    "maxResults": max_results,
                },
            )
            resp.raise_for_status()
            return resp.json().get("chunks", [])

    async def correct_transcript(
        self,
        client_id: str,
        project_id: str | None,
        segments: list[dict],
        chunk_size: int = CHUNK_SIZE,
        meeting_id: str | None = None,
    ) -> dict:
        """
        Correct transcript segments using KB-stored corrections + Ollama GPU.

        Returns dict with:
          - segments: list of corrected segments (best-effort)
          - questions: list of questions when agent is uncertain
          - status: "success" or "needs_input"
        """
        if not segments:
            return {"segments": segments, "questions": [], "status": "success"}

        all_text = " ".join(seg["text"] for seg in segments)

        # Load all stored corrections for this client/project
        corrections = await self._load_corrections(client_id, project_id)
        logger.info(
            "Loaded %d corrections for transcript (%d chars, %d segments)",
            len(corrections), len(all_text), len(segments),
        )

        correction_prompt = self._format_corrections_for_prompt(corrections)

        # Process in chunks
        corrected_segments = []
        all_questions = []
        total_chunks = (len(segments) + chunk_size - 1) // chunk_size
        for chunk_idx, chunk_start in enumerate(range(0, len(segments), chunk_size)):
            chunk = segments[chunk_start:chunk_start + chunk_size]

            # Emit progress before processing this chunk
            await self._emit_correction_progress(
                meeting_id, client_id, chunk_idx, total_chunks,
                f"Korekce chunk {chunk_idx + 1}/{total_chunks}",
            )

            chunk_result = await self._correct_chunk_interactive(
                chunk, correction_prompt, all_text,
                meeting_id=meeting_id, client_id=client_id,
                chunk_idx=chunk_idx, total_chunks=total_chunks,
            )
            corrected_segments.extend(chunk_result["segments"])
            all_questions.extend(chunk_result["questions"])

        # Emit 100% when done
        await self._emit_correction_progress(
            meeting_id, client_id, total_chunks, total_chunks,
            "Korekce dokoncena",
        )

        status = "needs_input" if all_questions else "success"
        return {
            "segments": corrected_segments,
            "questions": all_questions,
            "status": status,
        }

    async def correct_with_instruction(
        self,
        client_id: str,
        project_id: str | None,
        segments: list[dict],
        instruction: str,
    ) -> dict:
        """
        Re-correct transcript based on a user instruction.

        The user describes what needs to be fixed in natural language.
        The agent re-corrects the entire transcript using the instruction
        and existing KB rules, then extracts new rules from the instruction.
        """
        if not segments:
            return {"segments": segments, "newRules": [], "status": "success", "summary": "Zadne segmenty k oprave."}

        corrections = await self._load_corrections(client_id, project_id)
        correction_prompt = self._format_corrections_for_prompt(corrections)

        system_prompt = self._build_instruction_system_prompt(
            correction_prompt, instruction,
        )

        full_text = " ".join(seg["text"] for seg in segments)
        user_prompt = self._build_user_prompt(segments, full_text)

        logger.info(
            "Instruction-based correction: %d segments, instruction='%s'",
            len(segments), instruction[:100],
        )

        for attempt in range(MAX_RETRIES + 1):
            try:
                response_text = await self._call_ollama(system_prompt, user_prompt)
                parsed = self._parse_instruction_response(response_text, segments)
                if parsed is not None:
                    # Save any new rules the agent extracted
                    new_rules = parsed.get("newRules", [])
                    saved_rules = []
                    for rule in new_rules:
                        try:
                            result = await self.submit_correction(
                                client_id=client_id,
                                project_id=project_id,
                                original=rule["original"],
                                corrected=rule["corrected"],
                                category=rule.get("category", "general"),
                            )
                            saved_rules.append(result)
                        except Exception as e:
                            logger.warning("Failed to save extracted rule: %s", e)

                    # Build summary of what changed
                    changed_count = sum(
                        1 for i, seg in enumerate(parsed["segments"])
                        if i < len(segments) and seg.get("text", "") != segments[i].get("text", "")
                    )
                    summary = f"Opraveno {changed_count} segmentu."
                    if saved_rules:
                        summary += f" Vytvoreno {len(saved_rules)} novych pravidel."

                    return {
                        "segments": parsed["segments"],
                        "newRules": saved_rules,
                        "status": "success",
                        "summary": summary,
                    }
                logger.warning(
                    "Failed to parse instruction response (attempt %d)", attempt + 1,
                )
            except Exception as e:
                logger.error(
                    "Instruction correction failed (attempt %d): %s", attempt + 1, e,
                )

        logger.warning("All instruction correction attempts failed")
        return {"segments": segments, "newRules": [], "status": "failed", "summary": "Oprava se nezdarila."}

    async def correct_targeted(
        self,
        client_id: str,
        project_id: str | None,
        segments: list[dict],
        retranscribed_indices: list[int],
        user_corrected_indices: dict[str, str],
        meeting_id: str | None = None,
    ) -> dict:
        """
        Targeted correction: only process retranscribed segments via LLM.

        User corrections are applied directly (no LLM needed).
        Retranscribed segments are sent to the correction agent for cleanup.
        Untouched segments pass through as-is.
        """
        # Apply user corrections directly
        for idx_str, text in user_corrected_indices.items():
            idx = int(idx_str)
            if 0 <= idx < len(segments):
                segments[idx]["text"] = text

        # If no retranscribed segments, we're done
        if not retranscribed_indices:
            return {"segments": segments, "questions": [], "status": "success"}

        corrections = await self._load_corrections(client_id, project_id)
        correction_prompt = self._format_corrections_for_prompt(corrections)

        # Build full transcript context from all segments
        full_text = " ".join(s["text"] for s in segments)

        # Extract only the retranscribed segments for LLM correction
        affected = [segments[i] for i in retranscribed_indices if 0 <= i < len(segments)]

        await self._emit_correction_progress(
            meeting_id, client_id, 0, 1,
            f"Cílená korekce {len(affected)} segmentů",
        )

        chunk_result = await self._correct_chunk_interactive(
            affected, correction_prompt, full_text,
            meeting_id=meeting_id, client_id=client_id,
            chunk_idx=0, total_chunks=1,
        )

        # Merge corrected segments back into the full list
        corrected_by_index = {}
        for seg in chunk_result["segments"]:
            seg_idx = seg.get("i")
            if seg_idx is not None:
                corrected_by_index[seg_idx] = seg["text"]

        for idx in retranscribed_indices:
            if idx in corrected_by_index:
                segments[idx]["text"] = corrected_by_index[idx]

        await self._emit_correction_progress(
            meeting_id, client_id, 1, 1,
            "Cílená korekce dokončena",
        )

        status = "needs_input" if chunk_result.get("questions") else "success"
        return {
            "segments": segments,
            "questions": chunk_result.get("questions", []),
            "status": status,
        }

    async def apply_answers_as_corrections(
        self,
        client_id: str,
        project_id: str | None,
        answers: list[dict],
    ) -> list[dict]:
        """Store user answers as correction rules in KB. Returns created rules."""
        results = []
        for answer in answers:
            original = answer.get("original", "")
            corrected = answer.get("corrected", "")
            if not original or not corrected:
                continue
            result = await self.submit_correction(
                client_id=client_id,
                project_id=project_id,
                original=original,
                corrected=corrected,
                category=answer.get("category", "general"),
                context=answer.get("context"),
            )
            results.append(result)
        logger.info("Stored %d correction rules from user answers", len(results))
        return results

    async def _load_corrections(
        self,
        client_id: str,
        project_id: str | None,
    ) -> list[dict]:
        """Load all correction rules from KB for this client/project."""
        try:
            raw = await self.list_corrections(client_id, project_id, max_results=200)
        except Exception as e:
            logger.warning("Failed to load corrections from KB, proceeding without rules: %s", e)
            return []

        corrections = []
        for item in raw:
            metadata = item.get("metadata", {})
            original = metadata.get("original", "")
            corrected = metadata.get("corrected", "")
            if original and corrected:
                corrections.append({
                    "original": original,
                    "corrected": corrected,
                    "category": metadata.get("category", "general"),
                    "context": metadata.get("context", ""),
                })

        return corrections

    async def _emit_correction_progress(
        self,
        meeting_id: str | None,
        client_id: str,
        chunks_done: int,
        total_chunks: int,
        message: str | None = None,
        percent_override: float | None = None,
        tokens_generated: int = 0,
    ):
        """Send correction progress to Kotlin server for UI broadcast."""
        if not meeting_id:
            return
        if percent_override is not None:
            percent = percent_override
        else:
            percent = (chunks_done / total_chunks * 100.0) if total_chunks > 0 else 0.0
        payload = {
            "meetingId": meeting_id,
            "clientId": client_id,
            "percent": percent,
            "chunksDone": chunks_done,
            "totalChunks": total_chunks,
            "message": message,
            "tokensGenerated": tokens_generated,
        }
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                await client.post(
                    f"{settings.kotlin_server_url}/internal/correction-progress",
                    json=payload,
                )
        except Exception as e:
            logger.debug("Failed to emit correction progress: %s", e)

    async def _correct_chunk_interactive(
        self,
        segments: list[dict],
        correction_prompt: str,
        full_transcript: str,
        meeting_id: str | None = None,
        client_id: str | None = None,
        chunk_idx: int = 0,
        total_chunks: int = 1,
    ) -> dict:
        """Send a chunk of segments to Ollama for interactive correction."""
        system_prompt = self._build_system_prompt_interactive(correction_prompt)
        user_prompt = self._build_user_prompt(segments, full_transcript)
        seg_indices = [s.get("i", i) for i, s in enumerate(segments)]

        for attempt in range(MAX_RETRIES + 1):
            try:
                response_text = await self._call_ollama(
                    system_prompt, user_prompt,
                    meeting_id=meeting_id, client_id=client_id,
                    chunk_idx=chunk_idx, total_chunks=total_chunks,
                )
                parsed = self._parse_interactive_response(response_text, segments)
                if parsed is not None:
                    logger.info(
                        "Chunk correction succeeded (attempt %d): %d segments [%s..%s]",
                        attempt + 1, len(segments),
                        seg_indices[0] if seg_indices else "?",
                        seg_indices[-1] if seg_indices else "?",
                    )
                    return parsed
                logger.warning(
                    "Failed to parse correction response (attempt %d, %d segments [%s..%s])",
                    attempt + 1, len(segments),
                    seg_indices[0] if seg_indices else "?",
                    seg_indices[-1] if seg_indices else "?",
                )
            except Exception as e:
                logger.error(
                    "Correction LLM call failed (attempt %d): %s", attempt + 1, e,
                )

        logger.warning(
            "All %d correction attempts failed for segments [%s..%s], using original text",
            MAX_RETRIES + 1,
            seg_indices[0] if seg_indices else "?",
            seg_indices[-1] if seg_indices else "?",
        )
        return {"segments": segments, "questions": []}

    async def _call_ollama(
        self,
        system_prompt: str,
        user_prompt: str,
        meeting_id: str | None = None,
        client_id: str | None = None,
        chunk_idx: int = 0,
        total_chunks: int = 1,
    ) -> str:
        """Call Ollama chat API with streaming + heartbeat liveness detection."""
        # Estimate input tokens (~1 token per 2.5 chars for Czech)
        input_chars = len(system_prompt) + len(user_prompt)
        input_tokens_est = int(input_chars / 2.5)
        # num_ctx = input + output budget (JSON corrections for chunk)
        num_ctx = input_tokens_est + OUTPUT_BUDGET
        # Round up to nearest 4k, cap at GPU VRAM limit
        num_ctx = min(((num_ctx + 4095) // 4096) * 4096, GPU_CTX_CAP)
        num_predict = OUTPUT_BUDGET

        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "stream": True,
            "options": {
                "num_predict": num_predict,
                "num_ctx": num_ctx,
                "temperature": 0.3,
            },
        }
        logger.info(
            "Calling Ollama %s streaming (num_ctx=%d, num_predict=%d, input≈%d tokens, input_chars=%d)",
            self.model, num_ctx, num_predict, input_tokens_est, input_chars,
        )

        content_parts: list[str] = []
        token_count = 0
        done_reason = "?"
        eval_count = "?"
        last_progress_emit = time.monotonic()

        async with httpx.AsyncClient(timeout=None) as http_client:
            async with http_client.stream(
                "POST",
                f"{self.ollama_url}/api/chat",
                json=payload,
            ) as response:
                response.raise_for_status()

                async for raw_line in self._iter_lines_with_heartbeat(response):
                    if not raw_line.strip():
                        continue
                    try:
                        data = json.loads(raw_line)
                    except json.JSONDecodeError:
                        logger.debug("Non-JSON streaming line: %s", raw_line[:200])
                        continue

                    # Accumulate content tokens
                    chunk_content = data.get("message", {}).get("content", "")
                    if chunk_content:
                        content_parts.append(chunk_content)
                        token_count += 1

                    # Final message with metadata
                    if data.get("done"):
                        done_reason = data.get("done_reason", "?")
                        eval_count = data.get("eval_count", "?")

                    # Emit intra-chunk progress periodically
                    now = time.monotonic()
                    if meeting_id and client_id and (now - last_progress_emit) >= PROGRESS_EMIT_INTERVAL:
                        last_progress_emit = now
                        chunk_base = (chunk_idx / total_chunks) * 100
                        intra = min(token_count / OUTPUT_BUDGET, 1.0)
                        percent = chunk_base + (1.0 / total_chunks) * 100 * intra * 0.9
                        await self._emit_correction_progress(
                            meeting_id, client_id,
                            chunk_idx, total_chunks,
                            f"Chunk {chunk_idx + 1}/{total_chunks}: generuji... {token_count} tokenů",
                            percent_override=percent,
                            tokens_generated=token_count,
                        )

        content = "".join(content_parts)

        logger.info(
            "Ollama streaming complete: %d chars, %d tokens, eval_count=%s, done_reason=%s",
            len(content), token_count, eval_count, done_reason,
        )

        if done_reason == "length":
            logger.warning(
                "OUTPUT TRUNCATED: model hit num_predict=%d limit. "
                "Response may be incomplete (raw=%d chars). First 500 chars: %s",
                num_predict, len(content), content.strip()[:500],
            )

        return content

    async def _iter_lines_with_heartbeat(self, response: httpx.Response):
        """
        Iterate over streaming response lines with heartbeat timeout.
        Raises HeartbeatTimeoutError if no data arrives for HEARTBEAT_DEAD_SECONDS.
        """
        aiter = response.aiter_lines().__aiter__()
        while True:
            try:
                line = await asyncio.wait_for(
                    aiter.__anext__(),
                    timeout=HEARTBEAT_DEAD_SECONDS,
                )
                yield line
            except asyncio.TimeoutError:
                raise HeartbeatTimeoutError(
                    f"Ollama stopped sending tokens for {HEARTBEAT_DEAD_SECONDS}s"
                )
            except StopAsyncIteration:
                return

    # --- Prompt building ---

    def _build_correction_content(
        self, original: str, corrected: str, category: str, context: str | None,
    ) -> str:
        """Build natural language content for embedding in KB."""
        parts = [
            f'When "{original}" appears in a transcript, '
            f'it should be corrected to "{corrected}".',
            f"Category: {CATEGORY_LABELS.get(category, category)}.",
        ]
        if context:
            parts.append(f"Context: {context}.")
        return " ".join(parts)

    def _format_corrections_for_prompt(self, corrections: list[dict]) -> str:
        """Format corrections for the LLM system prompt."""
        if not corrections:
            return ""

        grouped: dict[str, list[dict]] = {}
        for c in corrections:
            cat = c.get("category", "general")
            grouped.setdefault(cat, []).append(c)

        lines = []
        for cat, entries in grouped.items():
            label = CATEGORY_LABELS.get(cat, cat.title())
            lines.append(f"## {label}")
            for e in entries:
                line = f'- "{e["original"]}" -> "{e["corrected"]}"'
                ctx = e.get("context", "")
                if ctx:
                    line += f" ({ctx})"
                lines.append(line)
            lines.append("")

        return "\n".join(lines)

    def _build_system_prompt_interactive(self, correction_prompt: str) -> str:
        """Build system prompt with interactive question generation."""
        base = (
            "You correct Whisper speech-to-text transcripts. "
            "The audio is mostly Czech/Slovak with occasional English terms.\n\n"
            "APPROACH:\n"
            "1. Read the FULL transcript context to understand what the meeting "
            "is about — the topic, participants, and subject matter\n"
            "2. For each garbled segment, figure out what the speaker MEANT to say "
            "based on phonetic similarity and context\n"
            "3. Reconstruct coherent sentences that match the intended meaning\n\n"
            "KEY RULES:\n"
            "- Whisper severely garbles Czech — words may be split, merged, or "
            "phonetically mangled beyond recognition\n"
            "- READ the garbled text ALOUD in your head — the sounds often reveal "
            "the intended Czech/Slovak words\n"
            "- Apply all CORRECTION RULES below — these are verified phrases from "
            "the user's domain\n"
            "- Keep spoken style — do NOT formalize grammar or add punctuation "
            "beyond what helps readability\n"
            "- Do NOT translate between languages\n"
            "- Each corrected segment MUST make sense in context of the full "
            "conversation\n\n"
            "INTERACTIVE MODE:\n"
            "- Always provide your best correction for every segment\n"
            "- If you encounter unknown proper nouns or domain terms that are NOT "
            "in the correction rules, add a question\n"
            "- Do NOT ask about common words\n\n"
            "OUTPUT — valid JSON only, no markdown:\n"
            "With questions: "
            '{"corrections":[{"i":0,"t":"text"},...], '
            '"questions":[{"id":"q1","i":5,"original":"unclear",'
            '"question":"Correct spelling?","options":["A","B"]}]}\n'
            "Without questions: "
            '[{"i":0,"t":"text"},{"i":1,"t":"text"},...]\n'
        )

        if correction_prompt:
            base += f"\nCORRECTION RULES (always apply these):\n{correction_prompt}"
        else:
            base += (
                "\nNo correction rules stored yet. "
                "Use context and phonetic reasoning to fix errors.\n"
            )

        return base

    def _build_instruction_system_prompt(
        self, correction_prompt: str, instruction: str,
    ) -> str:
        """Build system prompt for instruction-based correction."""
        base = (
            "You correct Whisper speech-to-text transcripts. "
            "Apply the user's instruction to fix the transcript.\n\n"
            "1. Understand the conversation meaning from full context\n"
            "2. Apply the user instruction across ALL relevant segments\n"
            "3. Also apply existing correction rules below\n"
            "4. Keep spoken style, do NOT translate between languages\n\n"
            f"USER INSTRUCTION:\n{instruction}\n\n"
            "OUTPUT — valid JSON only, no markdown:\n"
            '{"corrections":[{"i":0,"t":"text"},...],'
            '"newRules":[{"original":"wrong","corrected":"right",'
            '"category":"person_name|company_name|terminology|general"}]}\n'
            "newRules: reusable patterns from the instruction (name spellings etc.)\n"
        )

        if correction_prompt:
            base += f"\nEXISTING CORRECTION RULES:\n{correction_prompt}"

        return base

    def _build_user_prompt(
        self, segments: list[dict], full_transcript: str,
    ) -> str:
        """Build user prompt with segments to correct."""
        entries = []
        for idx, seg in enumerate(segments):
            entries.append({"i": seg.get("i", idx), "t": seg["text"]})

        prompt = ""

        # Full transcript as context — truncate if too long to save input tokens.
        # The model needs enough to understand the topic, not every word.
        if full_transcript:
            max_context_chars = 8000  # ~3200 tokens — enough for topic understanding
            if len(full_transcript) > max_context_chars:
                truncated = full_transcript[:max_context_chars]
                prompt += (
                    f"FULL TRANSCRIPT (first {max_context_chars} chars, read for topic context):\n"
                    f"{truncated}\n...(truncated)\n\n"
                )
            else:
                prompt += (
                    f"FULL TRANSCRIPT (read first to understand the topic):\n"
                    f"{full_transcript}\n\n"
                )

        prompt += (
            "SEGMENTS TO CORRECT:\n"
            f"{json.dumps(entries, ensure_ascii=False)}"
        )

        return prompt

    # --- Response parsing ---

    def _parse_interactive_response(
        self, content: str, original_segments: list[dict],
    ) -> dict | None:
        """Parse LLM response — either simple array or object with questions."""
        raw_len = len(content)
        text = content.strip()

        # Remove <think> tags if present (some models add these)
        text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()
        # Handle unclosed <think> (truncated output) — strip from <think> to end
        has_unclosed_think = "<think>" in text
        text = re.sub(r"<think>.*", "", text, flags=re.DOTALL).strip()

        if not text:
            logger.warning(
                "Empty response after stripping <think> tags (raw=%d chars, unclosed_think=%s). "
                "Model likely exhausted num_predict on reasoning. Raw tail: ...%s",
                raw_len, has_unclosed_think, content[-300:] if raw_len > 0 else "(empty)",
            )
            return None

        # Handle markdown code blocks
        match = re.search(
            r"```(?:json)?\s*\n?(\{.*?\}|\[.*?\])\s*\n?```", text, re.DOTALL,
        )
        if match:
            text = match.group(1)

        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            # Try to extract JSON object or array from surrounding text
            obj_match = re.search(r"\{.*\}", text, re.DOTALL)
            arr_match = re.search(r"\[.*\]", text, re.DOTALL)
            if obj_match:
                try:
                    parsed = json.loads(obj_match.group(0))
                except json.JSONDecodeError:
                    if arr_match:
                        try:
                            parsed = json.loads(arr_match.group(0))
                        except json.JSONDecodeError:
                            logger.warning("JSON parse failed: %s", text[:200])
                            return None
                    else:
                        logger.warning("JSON parse failed: %s", text[:200])
                        return None
            elif arr_match:
                try:
                    parsed = json.loads(arr_match.group(0))
                except json.JSONDecodeError:
                    logger.warning("JSON parse failed: %s", text[:200])
                    return None
            else:
                logger.warning("No JSON found in response: %s", text[:200])
                return None

        # Format 1: dict with "corrections" + "questions"
        if isinstance(parsed, dict) and "corrections" in parsed:
            corrections_list = parsed.get("corrections", [])
            questions_list = parsed.get("questions", [])

            segments = self._merge_corrections(corrections_list, original_segments)
            if segments is None:
                return None

            questions = []
            for q in questions_list:
                if isinstance(q, dict) and "question" in q:
                    questions.append({
                        "id": q.get("id", f"q-{uuid.uuid4().hex[:8]}"),
                        "i": q.get("i", 0),
                        "original": q.get("original", ""),
                        "question": q["question"],
                        "options": q.get("options", []),
                        "context": q.get("context"),
                    })

            return {"segments": segments, "questions": questions}

        # Format 2: simple array (no questions)
        if isinstance(parsed, list):
            segments = self._merge_corrections(parsed, original_segments)
            if segments is None:
                return None
            return {"segments": segments, "questions": []}

        logger.warning("Unexpected JSON structure: %s", type(parsed))
        return None

    def _parse_instruction_response(
        self, content: str, original_segments: list[dict],
    ) -> dict | None:
        """Parse response from instruction-based correction."""
        raw_len = len(content)
        text = content.strip()
        text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()
        has_unclosed_think = "<think>" in text
        text = re.sub(r"<think>.*", "", text, flags=re.DOTALL).strip()

        if not text:
            logger.warning(
                "Empty instruction response after stripping <think> (raw=%d chars, unclosed=%s). "
                "Raw tail: ...%s",
                raw_len, has_unclosed_think, content[-300:] if raw_len > 0 else "(empty)",
            )
            return None

        match = re.search(
            r"```(?:json)?\s*\n?(\{.*?\}|\[.*?\])\s*\n?```", text, re.DOTALL,
        )
        if match:
            text = match.group(1)

        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            obj_match = re.search(r"\{.*\}", text, re.DOTALL)
            if obj_match:
                try:
                    parsed = json.loads(obj_match.group(0))
                except json.JSONDecodeError:
                    logger.warning("Instruction JSON parse failed: %s", text[:200])
                    return None
            else:
                logger.warning("No JSON in instruction response: %s", text[:200])
                return None

        if isinstance(parsed, dict) and "corrections" in parsed:
            segments = self._merge_corrections(
                parsed.get("corrections", []), original_segments,
            )
            if segments is None:
                return None

            new_rules = []
            for rule in parsed.get("newRules", []):
                if isinstance(rule, dict) and rule.get("original") and rule.get("corrected"):
                    new_rules.append({
                        "original": rule["original"],
                        "corrected": rule["corrected"],
                        "category": rule.get("category", "general"),
                    })

            return {"segments": segments, "newRules": new_rules}

        # Fallback: treat as simple corrections array
        if isinstance(parsed, list):
            segments = self._merge_corrections(parsed, original_segments)
            if segments is None:
                return None
            return {"segments": segments, "newRules": []}

        logger.warning("Unexpected instruction response: %s", type(parsed))
        return None

    def _merge_corrections(
        self, corrections: list, original_segments: list[dict],
    ) -> list[dict] | None:
        """Merge corrections into original segments by index."""
        by_index: dict[int, str] = {}
        for item in corrections:
            if isinstance(item, dict) and "i" in item and "t" in item:
                by_index[item["i"]] = item["t"]

        if not by_index:
            return None

        result = []
        for idx, seg in enumerate(original_segments):
            seg_idx = seg.get("i", idx)
            corrected_text = by_index.get(seg_idx, seg["text"])
            result.append({**seg, "text": corrected_text})

        return result


# Singleton instance
correction_agent = CorrectionAgent()
