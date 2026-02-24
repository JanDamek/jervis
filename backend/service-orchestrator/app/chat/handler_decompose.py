"""Long message processing: summarization, decomposition, sub-topics, combining.

Responsibilities:
- Save original long messages to KB (no-trim principle)
- Summarize very long messages (>16k chars) into structured compact form
- Classify messages into sub-topics for parallel processing
- Process individual sub-topics through mini agentic loops
- Combine sub-topic results into one cohesive response
"""
from __future__ import annotations

import asyncio
import json
import logging
import re

from app.chat.handler_context import build_messages
from app.chat.handler_streaming import call_llm
from app.chat.handler_tools import extract_tool_calls, execute_chat_tool
from app.chat.models import ChatStreamEvent, SubTopic, SubTopicResult
from app.chat.tools import TOOL_DOMAINS
from app.llm.provider import llm_provider
from app.models import ModelTier

logger = logging.getLogger(__name__)

# Thresholds
DECOMPOSE_THRESHOLD = 8000       # chars (~2k tokens)
SUBTOPIC_MAX_ITERATIONS = 3
MAX_SUBTOPICS = 5

# LLM system prompts for decomposition pipeline
_CLASSIFIER_SYSTEM = (
    "Jsi analytik zpráv. Urči, zda zpráva obsahuje JEDNO nebo VÍCE nezávislých témat.\n\n"
    "Pravidla:\n"
    "- Jedno téma = celá zpráva se týká jedné věci (i dlouhý bug report s logy)\n"
    "- Více témat = NEZÁVISLÉ požadavky (bug + otázka + žádost o task)\n"
    "- Max 5 témat\n"
    '- Odpověz POUZE validním JSON:\n'
    '{"topic_count": N, "topics": [{"title": "...", "type": "bug_report|question|request|info|task", '
    '"char_start": 0, "char_end": 5000}]}'
)

_SUMMARIZER_SYSTEM = (
    "Jsi analytik. Zpracuj dlouhou zprávu do strukturovaného souhrnu.\n\n"
    "Pravidla:\n"
    "- Zachovej VŠECHNY klíčové informace, požadavky, otázky, rozhodnutí\n"
    "- Identifikuj VŠECHNY akční položky (co uživatel chce udělat)\n"
    "- Zachovej konkrétní hodnoty: čísla, jména, ID ticketů, chybové kódy\n"
    "- Pokud zpráva obsahuje logy/stacktraces, shrň jen klíčová zjištění\n"
    "- Stručně — max 3000 znaků. Piš česky.\n\n"
    "Formát odpovědi:\n"
    "## Souhrn\n[1-3 věty celkový kontext]\n\n"
    "## Požadavky\n- [konkrétní akce 1]\n- [konkrétní akce 2]\n...\n\n"
    "## Klíčové detaily\n- [fakta, čísla, ID, jména]\n\n"
    "## Otázky\n- [explicitní otázky uživatele]"
)

_COMBINER_SYSTEM = (
    "Spojíš odpovědi na jednotlivá témata do jedné souvislé zprávy pro uživatele.\n\n"
    "Pravidla:\n"
    "- Zachovej VŠECHNY informace z každé odpovědi\n"
    "- Použij markdown ## nadpisy pro jednotlivá témata\n"
    "- Piš česky, stručně a věcně\n"
    "- Nepoužívej úvod typu 'Na základě analýzy...' — piš rovnou věcně"
)


async def save_original_to_kb(
    message: str,
    client_id: str | None,
    project_id: str | None,
    session_id: str,
) -> None:
    """Save original long message to KB before summarization (no-trim principle)."""
    from app.tools.executor import execute_tool as exec_tool

    subject = f"Originální zpráva z chatu (session {session_id}, {len(message)} znaků)"
    result = await exec_tool(
        tool_name="store_knowledge",
        arguments={
            "subject": subject,
            "content": message,
            "source": f"chat:{session_id}",
            "tags": "original_message,long_message,chat",
        },
        client_id=client_id or "",
        project_id=project_id,
        processing_mode="FOREGROUND",
    )
    logger.info("Chat: saved original %d char message to KB: %s", len(message), result[:100])


async def summarize_long_message(message: str) -> str | None:
    """Summarize a very long message into a structured compact form.

    Uses LOCAL_FAST (~5s) to create a ~2-4k char summary.
    Returns summary string, or None on failure.
    """
    msg_len = len(message)

    # Build excerpt: head + 3 evenly-spaced middle segments + tail
    head = message[:2000]
    tail = message[-1000:]

    middle_parts = []
    for i in range(1, 4):
        frac = i / 4
        mid_pos = int(msg_len * frac)
        start = max(0, mid_pos - 250)
        end = min(msg_len, mid_pos + 250)
        middle_parts.append(f"[... pozice ~{mid_pos} ...]\n{message[start:end]}")

    user_content = (
        f"Zpráva ({msg_len} znaků, níže jsou ukázky):\n\n"
        f"--- ZAČÁTEK ---\n{head}\n\n"
        + "\n\n".join(middle_parts) + "\n\n"
        f"--- KONEC ---\n{tail}\n\n"
        f"Vytvoř strukturovaný souhrn zachovávající VŠECHNY požadavky a klíčové detaily."
    )

    try:
        response = await call_llm(
            messages=[
                {"role": "system", "content": _SUMMARIZER_SYSTEM},
                {"role": "user", "content": user_content},
            ],
            tier=ModelTier.LOCAL_FAST,
            max_tokens=2048,
            timeout=90.0,
        )
        summary = (response.choices[0].message.content or "").strip()

        if len(summary) < 50:
            logger.warning("Chat summarizer: response too short (%d chars), skipping", len(summary))
            return None

        logger.info("Chat summarizer: %d chars → %d chars (%.0f%% reduction)",
                     msg_len, len(summary), (1 - len(summary) / msg_len) * 100)
        return summary

    except asyncio.TimeoutError:
        logger.warning("Chat summarizer: timed out (90s)")
        return None
    except Exception as e:
        logger.warning("Chat summarizer: failed (%s)", e)
        return None


async def maybe_decompose(message: str) -> list[SubTopic] | None:
    """Classify whether a long message contains multiple distinct topics.

    Returns list of SubTopic if multi-topic, None if single-topic or on failure.
    """
    msg_len = len(message)
    if msg_len < DECOMPOSE_THRESHOLD:
        return None

    head = message[:1500]
    tail = message[-500:]

    middle_samples: list[tuple[int, str]] = []
    if msg_len > 5000:
        for frac in (1 / 3, 2 / 3):
            mid_pos = int(msg_len * frac)
            start = max(0, mid_pos - 250)
            end = min(msg_len, mid_pos + 250)
            middle_samples.append((mid_pos, message[start:end]))

    parts = [f"Zpráva ({msg_len} znaků):\n"]
    parts.append(f"ZAČÁTEK (0–1500):\n{head}\n")
    for mid_pos, sample in middle_samples:
        parts.append(f"STŘED (~{mid_pos}):\n{sample}\n")
    parts.append(f"KONEC ({msg_len - 500}–{msg_len}):\n{tail}\n")
    parts.append("Analyzuj a urči počet nezávislých témat.")

    try:
        response = await call_llm(
            messages=[
                {"role": "system", "content": _CLASSIFIER_SYSTEM},
                {"role": "user", "content": "\n".join(parts)},
            ],
            tier=ModelTier.LOCAL_FAST,
            max_tokens=512,
            timeout=15.0,
        )

        content = (response.choices[0].message.content or "").strip()

        # Strip markdown fences if present
        if content.startswith("```"):
            content = re.sub(r'^```(?:json)?\s*', '', content)
            content = re.sub(r'\s*```$', '', content)

        parsed = json.loads(content)

        if parsed.get("topic_count", 0) <= 1:
            logger.debug("Chat decompose: classifier says single-topic")
            return None

        topics_raw = parsed.get("topics", [])
        if not topics_raw or len(topics_raw) > MAX_SUBTOPICS:
            logger.warning("Chat decompose: invalid topic count %d, fallback", len(topics_raw))
            return None

        # Build SubTopic objects
        subtopics: list[SubTopic] = []
        for t in topics_raw:
            title = t.get("title", "").strip()
            topic_type = t.get("type", "info")
            char_start = max(0, int(t.get("char_start", 0)))
            char_end = min(msg_len, int(t.get("char_end", msg_len)))

            if not title or char_end <= char_start:
                continue
            subtopics.append(SubTopic(title=title, topic_type=topic_type,
                                      char_start=char_start, char_end=char_end))

        if len(subtopics) <= 1:
            return None

        # Close gaps > 200 chars between topics
        subtopics.sort(key=lambda t: t.char_start)
        for i in range(len(subtopics) - 1):
            gap = subtopics[i + 1].char_start - subtopics[i].char_end
            if gap > 200:
                subtopics[i].char_end = subtopics[i + 1].char_start

        subtopics[0].char_start = 0
        subtopics[-1].char_end = msg_len

        logger.info("Chat decompose: %d topics — %s",
                     len(subtopics), [(t.title, t.char_start, t.char_end) for t in subtopics])
        return subtopics

    except asyncio.TimeoutError:
        logger.warning("Chat decompose: classifier timed out (15s), fallback")
        return None
    except Exception as e:
        logger.warning("Chat decompose: classifier failed (%s), fallback", e)
        return None


async def process_sub_topic(
    topic: SubTopic,
    topic_index: int,
    total_topics: int,
    request,
    context,
    runtime_ctx,
    selected_tools: list[dict],
    system_prompt: str,
) -> SubTopicResult:
    """Process one sub-topic through a mini agentic loop."""
    from app.chat.handler_agentic import detect_drift

    section = request.message[topic.char_start:topic.char_end]
    scoped_message = (
        f"[Téma {topic_index}/{total_topics}: {topic.title}]\n\n"
        f"{section}\n\n"
        f"Odpověz POUZE na toto téma. Stručně a věcně."
    )

    messages = build_messages(
        system_prompt=system_prompt,
        context=context,
        task_context_msg=None,
        current_message=scoped_message,
    )

    used_tools: list[str] = []
    created_tasks: list[dict] = []
    responded_tasks: list[str] = []
    last_tool_sig: str | None = None
    consecutive_same = 0
    domain_history: list[set[str]] = []
    distinct_tools_used: set[str] = set()
    tool_call_history: list[tuple[str, str]] = []

    try:
        for iteration in range(SUBTOPIC_MAX_ITERATIONS):
            message_chars = sum(len(str(m)) for m in messages)
            estimated_tokens = message_chars // 4 + sum(len(str(t)) for t in selected_tools) // 4 + 4096

            tier = llm_provider.escalation.select_local_tier(estimated_tokens)
            logger.info("Chat sub-topic %d/%d iter %d: estimated=%d → tier=%s",
                        topic_index, total_topics, iteration + 1, estimated_tokens, tier.value)

            response = await call_llm(messages=messages, tier=tier, tools=selected_tools)

            choice = response.choices[0]
            tool_calls, remaining_text = extract_tool_calls(choice.message)

            if not tool_calls:
                final_text = remaining_text or choice.message.content or ""
                logger.info("Chat sub-topic %d/%d: answer after %d iterations (%d chars)",
                            topic_index, total_topics, iteration + 1, len(final_text))
                return SubTopicResult(topic=topic, text=final_text,
                                      used_tools=used_tools, created_tasks=created_tasks,
                                      responded_tasks=responded_tasks)

            # Drift detection
            tool_sig = "|".join(f"{tc.function.name}:{tc.function.arguments}" for tc in tool_calls)
            if tool_sig == last_tool_sig:
                consecutive_same += 1
            else:
                consecutive_same = 1
                last_tool_sig = tool_sig

            iter_domains = set()
            for tc in tool_calls:
                domain = TOOL_DOMAINS.get(tc.function.name, "unknown")
                iter_domains.add(domain)
                distinct_tools_used.add(tc.function.name)
                tool_call_history.append((tc.function.name, tc.function.arguments))
            domain_history.append(iter_domains)

            drift_reason = detect_drift(
                consecutive_same, domain_history, distinct_tools_used, iteration,
                tool_call_history=tool_call_history,
            )
            if drift_reason:
                logger.warning("Chat sub-topic %d/%d: drift (%s)", topic_index, total_topics, drift_reason)
                messages.append({"role": "system", "content": f"STOP — {drift_reason}. Odpověz."})
                forced = await call_llm(messages=messages, tier=tier)
                return SubTopicResult(topic=topic, text=forced.choices[0].message.content or "",
                                      used_tools=used_tools, created_tasks=created_tasks,
                                      responded_tasks=responded_tasks)

            # Execute tool calls
            assistant_msg = {"role": "assistant", "content": remaining_text or None, "tool_calls": []}
            for tc in tool_calls:
                assistant_msg["tool_calls"].append({
                    "id": tc.id, "type": "function",
                    "function": {"name": tc.function.name, "arguments": tc.function.arguments},
                })
            messages.append(assistant_msg)

            for tool_call in tool_calls:
                tool_name = tool_call.function.name
                try:
                    arguments = json.loads(tool_call.function.arguments)
                except json.JSONDecodeError:
                    logger.warning("Chat sub-topic: malformed args for %s", tool_name)
                    messages.append({"role": "tool", "tool_call_id": tool_call.id,
                                     "content": f"Chyba: argumenty pro {tool_name} nejsou platný JSON."})
                    continue

                if tool_name == "switch_context":
                    result = "switch_context ignorován v sub-topic zpracování"
                else:
                    result = await execute_chat_tool(
                        tool_name, arguments,
                        request.active_client_id, request.active_project_id,
                    )
                    used_tools.append(tool_name)
                    if tool_name == "create_background_task":
                        created_tasks.append(arguments)
                    if tool_name == "respond_to_user_task":
                        responded_tasks.append(arguments.get("task_id", ""))

                messages.append({"role": "tool", "tool_call_id": tool_call.id, "content": result})

            remaining_iters = SUBTOPIC_MAX_ITERATIONS - iteration - 1
            messages.append({
                "role": "system",
                "content": f'[FOCUS] Téma: "{topic.title}". Zbývá {remaining_iters} iterací. Pokud máš dost info, ODPOVĚZ.',
            })

        # Max iterations — force response
        logger.warning("Chat sub-topic %d/%d: max iterations, forcing answer", topic_index, total_topics)
        messages.append({"role": "system", "content": "Dosáhl jsi limitu iterací. Odpověz."})
        forced = await call_llm(messages=messages, tier=tier)
        return SubTopicResult(topic=topic, text=forced.choices[0].message.content or "",
                              used_tools=used_tools, created_tasks=created_tasks,
                              responded_tasks=responded_tasks)

    except Exception as e:
        logger.warning("Chat sub-topic %d/%d failed: %s", topic_index, total_topics, e)
        return SubTopicResult(topic=topic, text=f"[Chyba při zpracování tématu '{topic.title}': {e}]",
                              used_tools=used_tools, created_tasks=created_tasks,
                              responded_tasks=responded_tasks)


async def combine_results(
    results: list[SubTopicResult],
    original_message_summary: str,
) -> str:
    """Combine sub-topic results into one cohesive response."""
    if len(results) == 1:
        return results[0].text

    parts = [f"## {r.topic.title}\n{r.text}" for r in results]
    formatted = "\n\n---\n\n".join(parts)

    try:
        response = await call_llm(
            messages=[
                {"role": "system", "content": _COMBINER_SYSTEM},
                {"role": "user", "content": formatted + "\n\nSpoj do jedné odpovědi."},
            ],
            tier=ModelTier.LOCAL_FAST,
            timeout=90.0,
        )
        combined = (response.choices[0].message.content or "").strip()
        if combined:
            logger.info("Chat combiner: merged %d topics (%d chars)", len(results), len(combined))
            return combined
    except asyncio.TimeoutError:
        logger.warning("Chat combiner timed out (90s), using plain concatenation")
    except Exception as e:
        logger.warning("Chat combiner failed (%s), using plain concatenation", e)

    return "\n\n---\n\n".join(parts)
