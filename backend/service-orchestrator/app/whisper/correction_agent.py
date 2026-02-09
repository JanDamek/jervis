"""
Whisper transcript correction agent.

Stores/retrieves correction rules from KB and applies them via Ollama GPU (30B model).
Correction rules are regular KB chunks with kind="transcript_correction",
making them available to any agent with KB access (orchestrator, correction agent, etc.).

The agent lives in the orchestrator service to share Ollama GPU access and avoid
duplicating LLM calls across services.

Interactive mode: when the agent is uncertain about corrections, it generates
questions for the user. Answers are saved as correction rules for future use.
"""

import json
import logging
import re
import uuid
from typing import Any

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

CORRECTION_KIND = "transcript_correction"
CHUNK_SIZE = 20
MAX_RETRIES = 1

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
        self.model = settings.default_local_model  # qwen3-coder-tool:30b

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

        async with httpx.AsyncClient(timeout=30) as http:
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
        async with httpx.AsyncClient(timeout=30) as http:
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
        async with httpx.AsyncClient(timeout=30) as http:
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
        for chunk_start in range(0, len(segments), chunk_size):
            chunk = segments[chunk_start:chunk_start + chunk_size]
            chunk_result = await self._correct_chunk_interactive(
                chunk, correction_prompt, all_text,
            )
            corrected_segments.extend(chunk_result["segments"])
            all_questions.extend(chunk_result["questions"])

        status = "needs_input" if all_questions else "success"
        return {
            "segments": corrected_segments,
            "questions": all_questions,
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
        raw = await self.list_corrections(client_id, project_id, max_results=200)

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

    async def _correct_chunk_interactive(
        self,
        segments: list[dict],
        correction_prompt: str,
        full_transcript: str,
    ) -> dict:
        """Send a chunk of segments to Ollama for interactive correction."""
        system_prompt = self._build_system_prompt_interactive(correction_prompt)
        user_prompt = self._build_user_prompt(segments, full_transcript)

        for attempt in range(MAX_RETRIES + 1):
            try:
                response_text = await self._call_ollama(system_prompt, user_prompt)
                parsed = self._parse_interactive_response(response_text, segments)
                if parsed is not None:
                    return parsed
                logger.warning(
                    "Failed to parse correction response (attempt %d)", attempt + 1,
                )
            except Exception as e:
                logger.error(
                    "Correction LLM call failed (attempt %d): %s", attempt + 1, e,
                )

        logger.warning("All correction attempts failed, using original text")
        return {"segments": segments, "questions": []}

    async def _call_ollama(self, system_prompt: str, user_prompt: str) -> str:
        """Call Ollama chat API on GPU instance."""
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "stream": False,
            "options": {
                "num_predict": 8192,
                "temperature": 0.1,
            },
        }

        async with httpx.AsyncClient(timeout=600.0) as client:
            response = await client.post(
                f"{self.ollama_url}/api/chat",
                json=payload,
            )
            response.raise_for_status()
            data = response.json()
            return data.get("message", {}).get("content", "")

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
            "You are a transcript correction assistant. "
            "Fix speech-to-text errors in the transcript segments below.\n\n"
            "RULES:\n"
            "- Fix misspellings of names, companies, departments per the "
            "correction rules below\n"
            "- The meeting may contain Czech, Slovak, and English mixed together "
            "— do NOT translate between languages, only correct transcription "
            "errors\n"
            "- Occasionally Scandinavian or Spanish may also appear\n"
            "- Preserve informal/spoken style — do not make it formal\n"
            "- Preserve the meaning — only fix obvious speech-to-text errors\n"
            "- You MUST understand the content and context to correct properly\n\n"
            "INTERACTIVE MODE:\n"
            "- Always apply your best-guess correction for every segment\n"
            "- When you encounter names, terms, or abbreviations that:\n"
            "  (a) do NOT match any correction rule below, AND\n"
            "  (b) look like they could be misspelled proper nouns, "
            "company names, or domain-specific terms\n"
            "  → add a question to the 'questions' array\n"
            "- Do NOT ask about common words or obvious corrections\n"
            "- Only ask about genuinely ambiguous proper nouns or terminology\n\n"
            "OUTPUT FORMAT:\n"
            "If you have questions:\n"
            '{"corrections":[{"i":0,"t":"corrected text"},...],\n'
            ' "questions":[{"id":"q1","i":5,"original":"unclear text",'
            '"question":"What is the correct spelling?","options":["Option A","Option B"]}]}\n\n'
            "If NO questions (all corrections are confident):\n"
            '[{"i":0,"t":"corrected text"},{"i":1,"t":"corrected text"},...]\n\n'
            "IMPORTANT: Return ONLY valid JSON, no markdown, no explanation.\n"
        )

        if correction_prompt:
            base += f"\nCORRECTION RULES:\n{correction_prompt}"
        else:
            base += (
                "\nNo specific correction rules are stored yet. "
                "Use your best judgment to fix obvious transcription errors "
                "(misspelled names, garbled words, etc.).\n"
            )

        return base

    def _build_user_prompt(
        self, segments: list[dict], full_transcript: str,
    ) -> str:
        """Build user prompt with segments to correct."""
        entries = []
        for idx, seg in enumerate(segments):
            entries.append({"i": seg.get("i", idx), "t": seg["text"]})

        prompt = (
            "Correct these transcript segments:\n"
            f"{json.dumps(entries, ensure_ascii=False)}"
        )

        # Add context from full transcript (truncated)
        if len(full_transcript) > 500:
            context_snippet = full_transcript[:500] + "..."
            prompt += (
                f"\n\nFull transcript context (for understanding):\n"
                f"{context_snippet}"
            )

        return prompt

    # --- Response parsing ---

    def _parse_interactive_response(
        self, content: str, original_segments: list[dict],
    ) -> dict | None:
        """Parse LLM response — either simple array or object with questions."""
        text = content.strip()

        # Remove /think tags if present (some models add these)
        text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()

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
