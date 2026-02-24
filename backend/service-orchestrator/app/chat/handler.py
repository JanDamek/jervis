"""ChatHandler — Jervis foreground chat agentic loop.

No StateGraph. Simple:
1. Register foreground (preempt background)
2. Load context from MongoDB (ChatContextAssembler)
3. Build LLM messages (system + summaries + recent + context_task + current)
4. Agentic loop: LLM + tools -> execute -> append result -> repeat
5. Final response -> stream tokens (chunked for progressive rendering)
6. Save assistant message to MongoDB
7. Fire-and-forget compression
8. Release foreground (finally)

LLM decides what to do — respond, search KB, create task,
respond to user_task, etc.

Progress feedback:
- "thinking" events before each tool call (human-readable description)
- "tool_call" / "tool_result" events for technical detail
- Chunked token streaming for final response

Error recovery:
- Partial save on LLM failure (accumulated tool results preserved)
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import uuid
from typing import AsyncIterator

from bson import ObjectId

from app.chat.context import chat_context_assembler
from app.chat.intent import classify_intent, select_tools
from app.chat.models import ChatRequest, ChatStreamEvent, SubTopic, SubTopicResult
from app.chat.system_prompt import RuntimeContext, build_system_prompt
from app.chat.tools import CHAT_TOOLS, TOOL_DOMAINS, ToolCategory
from app.llm.provider import llm_provider
from app.models import ModelTier
from app.tools.executor import execute_tool

logger = logging.getLogger(__name__)

# Max agentic iterations (tool calls) per message.
# Dynamic: short messages get 6, long messages (>8k chars) get 3.
# Long messages have ~40k context = each iteration costs 3-5 min on GPU.
# Short messages: typical 1 (direct), 2 (search+answer), 3-4 (multi-intent). 6 gives buffer.
MAX_ITERATIONS = 6
MAX_ITERATIONS_LONG = 3          # for messages >DECOMPOSE_THRESHOLD — prevent tier escalation

# Chunk size for fake token streaming (chars, ~10 tokens)
STREAM_CHUNK_SIZE = 40

# Long message decomposition thresholds
DECOMPOSE_THRESHOLD = 8000       # chars (~2k tokens) — below this, never decompose
SUBTOPIC_MAX_ITERATIONS = 3      # each sub-topic mini-loop (vs 6 for main)
MAX_SUBTOPICS = 5                # safety cap — more than this → fallback to single-pass

# Foreground chat tier ceiling — NEVER go above this in foreground chat.
# LOCAL_XLARGE (131k) causes catastrophic CPU spill on P40 (6GB overflow, 630s for 387 tokens).
# Better to trim the message and stay in GPU VRAM than escalate.
CHAT_MAX_TIER = ModelTier.LOCAL_LARGE

# Tier ordering for comparison (lower index = smaller/faster tier)
_TIER_ORDER = [
    ModelTier.LOCAL_FAST, ModelTier.LOCAL_STANDARD, ModelTier.LOCAL_LARGE,
    ModelTier.LOCAL_XLARGE, ModelTier.LOCAL_XXLARGE,
    ModelTier.CLOUD_REASONING, ModelTier.CLOUD_CODING, ModelTier.CLOUD_PREMIUM,
    ModelTier.CLOUD_LARGE_CONTEXT,
]
_TIER_INDEX = {t: i for i, t in enumerate(_TIER_ORDER)}

# Summarization threshold — messages above this get pre-summarized before agentic loop.
# Raw 126k chars can't fit in 40k context. Instead of losing 75% via trim,
# we summarize first (LOCAL_FAST, ~5s) and work with a structured summary.
SUMMARIZE_THRESHOLD = 16000      # chars (~4k tokens) — above this, summarize first


async def handle_chat(
    request: ChatRequest,
    disconnect_event: asyncio.Event | None = None,
) -> AsyncIterator[ChatStreamEvent]:
    """Process a chat message and stream response back.

    Yields ChatStreamEvent objects for SSE streaming to Kotlin -> UI.

    Args:
        disconnect_event: Set by main.py when user disconnects or presses Stop.
    """
    # Register foreground (preempt background tasks)
    try:
        from app.tools.kotlin_client import kotlin_client
        await kotlin_client.register_foreground_start()
    except Exception as e:
        logger.warning("Failed to register foreground start: %s", e)

    try:
        # 1. Load context from MongoDB
        context = await chat_context_assembler.assemble_context(
            conversation_id=request.session_id,
        )

        # 2. Load runtime context (clients, pending tasks, meetings)
        runtime_ctx = await _load_runtime_context()

        # 3. Intent classification → select tool subset (no LLM call, <1ms)
        intent_categories = classify_intent(
            user_message=request.message,
            has_pending_user_tasks=runtime_ctx.pending_user_tasks.get("count", 0) > 0,
            has_unclassified_meetings=runtime_ctx.unclassified_meetings_count > 0,
            has_context_task_id=bool(request.context_task_id),
        )
        selected_tools = select_tools(intent_categories)

        # Anti-dump: remove storage tools for long messages (>8000 chars).
        # The model tends to dump entire long messages into KB/memory instead of answering.
        _STORAGE_TOOL_NAMES = {"store_knowledge", "memory_store"}
        if len(request.message) > DECOMPOSE_THRESHOLD:
            before_count = len(selected_tools)
            selected_tools = [t for t in selected_tools if t["function"]["name"] not in _STORAGE_TOOL_NAMES]
            if len(selected_tools) < before_count:
                logger.info("Chat: removed storage tools for long message (%d chars)", len(request.message))

        logger.info(
            "Chat: intent=%s → %d/%d tools",
            [c.value for c in intent_categories], len(selected_tools), len(CHAT_TOOLS),
        )

        # 4. If responding to user_task, load task context
        task_context_msg = None
        if request.context_task_id:
            task_context_msg = await _load_task_context_message(request.context_task_id)

        # 5. Build LLM messages
        system_prompt_text = build_system_prompt(
            active_client_id=request.active_client_id,
            active_project_id=request.active_project_id,
            runtime_context=runtime_ctx,
        )
        messages = _build_messages(
            system_prompt=system_prompt_text,
            context=context,
            task_context_msg=task_context_msg,
            current_message=request.message,
        )

        # 4. Agentic loop
        created_tasks: list[dict] = []
        responded_tasks: list[str] = []
        used_tools: list[str] = []
        tool_summaries: list[str] = []  # For error recovery (partial save)
        last_tool_sig: str | None = None  # For loop detection
        consecutive_same = 0
        domain_history: list[set[str]] = []  # Per-iteration tool domains for drift detection
        distinct_tools_used: set[str] = set()  # All unique tools called across iterations
        effective_client_id = request.active_client_id
        effective_project_id = request.active_project_id

        # Emit thinking event BEFORE first LLM call.
        # Replaces client-side "Zpracovávám..." instantly instead of waiting 1-4 min.
        msg_len = len(request.message)
        if msg_len > 4000:
            yield ChatStreamEvent(type="thinking", content="Analyzuji dlouhou zprávu...")
        else:
            yield ChatStreamEvent(type="thinking", content="Připravuji odpověď...")

        # --- Long message pre-processing strategy ---
        #
        # Best practice for ANY long message (reports, meeting minutes, hundreds of tasks):
        #
        # 1. Messages < 8k chars:  pass through unchanged (fits in context easily)
        # 2. Messages 8k-16k chars: try decompose (multi-topic), else single-pass
        # 3. Messages > 16k chars:  SUMMARIZE first, then agentic loop on summary
        #    - Raw 126k chars can't fit in 40k context — trim loses 75% of info
        #    - Summary (~3k chars) preserves ALL key info, fits easily in context
        #    - Agentic loop works fast (~30s per iter vs 5 min with raw 40k)
        #    - Original message saved to MongoDB verbatim — nothing is lost
        #    - If message contains hundreds of tasks → suggest background offload
        #
        # For messages > DECOMPOSE_THRESHOLD, try to split into sub-topics.
        # Each sub-topic is processed separately on GPU tier (fast), then combined.
        # Fallback: any failure → existing single-pass flow (zero regression).

        # Step 1: For very long messages, summarize BEFORE decompose or agentic loop
        # NO-TRIM PRINCIPLE: NEVER truncate the current user message. Either summarize or background task.
        message_for_llm = request.message  # default: original message
        is_summarized = False
        is_long_message = msg_len > SUMMARIZE_THRESHOLD  # used for background offload hint

        if is_long_message:
            yield ChatStreamEvent(type="thinking", content="Analyzuji obsah dlouhé zprávy...")

            # Save original message to KB BEFORE any processing — nothing is ever lost.
            # Agent can retrieve it later via kb_search if needed.
            try:
                await _save_original_to_kb(
                    message=request.message,
                    client_id=request.active_client_id,
                    project_id=request.active_project_id,
                    session_id=request.session_id,
                )
            except Exception as kb_err:
                logger.warning("Chat: failed to save original to KB: %s", kb_err)

            summary = await _summarize_long_message(request.message)
            if summary:
                is_summarized = True
                # Replace the user message in messages with a compact version:
                # summary + pointer to original in KB
                summarized_content = (
                    f"[Uživatel poslal dlouhou zprávu ({msg_len} znaků). "
                    f"Níže je strukturovaný souhrn zachovávající všechny požadavky. "
                    f"Originál je uložen v KB.]\n\n"
                    f"{summary}\n\n"
                    f"[Poznámka: Originální zpráva je uložena v KB (session {request.session_id}). "
                    f"Pokud potřebuješ detaily, hledej přes kb_search.]"
                )
                # Replace the last user message in the messages array
                for i in range(len(messages) - 1, -1, -1):
                    if messages[i].get("role") == "user":
                        messages[i] = {"role": "user", "content": summarized_content}
                        break
                message_for_llm = summarized_content
                logger.info(
                    "Chat: summarized %d chars → %d chars for agentic loop",
                    msg_len, len(summarized_content),
                )
            else:
                # NO-TRIM FALLBACK: summarizer failed — suggest background task immediately.
                # NEVER fall through to pre-trim which would lose 75% of content.
                logger.warning(
                    "Chat: summarizer failed for %d char message — suggesting background task",
                    msg_len,
                )
                bg_suggestion = (
                    f"Zpráva je velmi dlouhá ({msg_len} znaků) a nepodařilo se ji analyzovat v reálném čase. "
                    f"Vytvořím background task pro podrobné zpracování — bude to důkladnější.\n\n"
                    f"Originál zprávy je uložen v KB. Background task si ji může přečíst celou."
                )
                for i in range(0, len(bg_suggestion), STREAM_CHUNK_SIZE):
                    yield ChatStreamEvent(type="token", content=bg_suggestion[i:i + STREAM_CHUNK_SIZE])
                    await asyncio.sleep(0.03)

                # Save this response
                await chat_context_assembler.save_message(
                    conversation_id=request.session_id,
                    role="ASSISTANT",
                    content=bg_suggestion,
                    correlation_id=str(ObjectId()),
                    sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                    metadata={"summarizer_failed": "true", "original_length": str(msg_len)},
                )
                yield ChatStreamEvent(type="done", metadata={
                    "summarizer_failed": True,
                    "suggest_background": True,
                    "original_length": msg_len,
                })
                return

        if msg_len > DECOMPOSE_THRESHOLD:
            subtopics = await _maybe_decompose(request.message)

            if subtopics and len(subtopics) > 1:
                logger.info(
                    "Chat: decomposing %d chars into %d sub-topics: %s",
                    msg_len, len(subtopics), [t.title for t in subtopics],
                )
                yield ChatStreamEvent(
                    type="thinking",
                    content=f"Rozděluji zprávu na {len(subtopics)} témat...",
                )

                all_results: list[SubTopicResult] = []
                all_used_tools: list[str] = []
                all_created_tasks: list[dict] = []
                all_responded_tasks: list[str] = []

                for i, topic in enumerate(subtopics, 1):
                    # Check disconnect between sub-topics
                    if disconnect_event and disconnect_event.is_set():
                        logger.info("Chat: stopped during decompose after %d/%d topics", i - 1, len(subtopics))
                        partial_parts = [f"## {r.topic.title}\n{r.text}" for r in all_results]
                        if partial_parts:
                            partial_text = "\n\n---\n\n".join(partial_parts)
                            await chat_context_assembler.save_message(
                                conversation_id=request.session_id,
                                role="ASSISTANT", content=partial_text,
                                correlation_id=str(ObjectId()),
                                sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                                metadata={"interrupted": "true", "decomposed": str(len(subtopics))},
                            )
                        yield ChatStreamEvent(type="done", metadata={"interrupted": True})
                        return

                    yield ChatStreamEvent(
                        type="thinking",
                        content=f"Zpracovávám téma {i}/{len(subtopics)}: {topic.title}",
                    )

                    result = await _process_sub_topic(
                        topic=topic,
                        topic_index=i,
                        total_topics=len(subtopics),
                        request=request,
                        context=context,
                        runtime_ctx=runtime_ctx,
                        selected_tools=selected_tools,
                        system_prompt=system_prompt_text,
                    )
                    all_results.append(result)
                    all_used_tools.extend(result.used_tools)
                    all_created_tasks.extend(result.created_tasks)
                    all_responded_tasks.extend(result.responded_tasks)

                # Combine results
                yield ChatStreamEvent(type="thinking", content="Sestavuji odpověď...")

                final_text = await _combine_results(all_results, request.message[:200])

                # Stream combined response
                for i in range(0, len(final_text), STREAM_CHUNK_SIZE):
                    chunk = final_text[i:i + STREAM_CHUNK_SIZE]
                    yield ChatStreamEvent(type="token", content=chunk)
                    await asyncio.sleep(0.03)

                # Save to MongoDB
                await chat_context_assembler.save_message(
                    conversation_id=request.session_id,
                    role="ASSISTANT",
                    content=final_text,
                    correlation_id=str(ObjectId()),
                    sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                    metadata={
                        "decomposed": str(len(subtopics)),
                        "topics": ",".join(t.title for t in subtopics),
                        **({"used_tools": ",".join(all_used_tools)} if all_used_tools else {}),
                        **({"created_tasks": ",".join(str(t.get("title", "")) for t in all_created_tasks)} if all_created_tasks else {}),
                        **({"responded_tasks": ",".join(all_responded_tasks)} if all_responded_tasks else {}),
                    },
                )

                # Compress
                try:
                    await chat_context_assembler.maybe_compress(request.session_id)
                except Exception as compress_err:
                    logger.warning("Chat compression failed: %s", compress_err)

                yield ChatStreamEvent(type="done", metadata={
                    "decomposed": True,
                    "topic_count": len(subtopics),
                    "topics": [t.title for t in subtopics],
                    "used_tools": all_used_tools,
                    "created_tasks": all_created_tasks,
                    "responded_tasks": all_responded_tasks,
                })
                return
            # else: single-topic or classifier failed → fall through to existing flow
            # For long single-topic messages: inject focus reminder into messages
            # to prevent model from dumping the message into KB/memory.
            if msg_len > DECOMPOSE_THRESHOLD:
                focus_hint = (
                    "[FOCUS] Toto je dlouhá zpráva — ODPOVĚZ na ni stručně a věcně. "
                    "NEUKLÁDEJ celou zprávu ani její části do KB/memory/knowledge. "
                    "Pokud chceš něco uložit, shrň to do 1-2 vět."
                )
                if is_summarized:
                    focus_hint += (
                        " Pracuješ se SOUHRNEM originální zprávy. Souhrn zachovává všechny klíčové informace. "
                        "Originál je uložen v KB — můžeš ho najít přes kb_search."
                    )
                # Background offload hint for ALL long messages (>16k), not just summarized.
                # BUG FIX: Previously this was inside `if is_summarized:` — so when summarizer
                # failed, the model never got the hint to suggest background task.
                if is_long_message:
                    focus_hint += (
                        " Pokud zpráva obsahuje příliš mnoho úkolů na zpracování v chatu, "
                        "navrhni uživateli vytvoření background task pomocí create_background_task."
                    )
                messages.append({"role": "system", "content": focus_hint})

        # --- Simple message fast path ---
        # For short GREETING messages with CORE-only intent, try a direct answer WITHOUT
        # tools. This avoids 60-120s overhead for simple greetings like "ahoj".
        #
        # IMPORTANT: Direct answer is restricted to greeting-only messages. Any message
        # that could require factual information (code, projects, tasks) MUST go through
        # the agentic loop with tools. Without tools the LLM confidently hallucinates
        # fake tool results (e.g. "Pomocí kb_search jsem vyhledal...") which is worse
        # than a slower but correct answer.
        _GREETING_RE = re.compile(
            r"^\s*(?:ahoj|čau|zdravím|hej|hi|hello|hey|"
            r"dobr[éý]?\s+(?:ráno|den|odpoledne|večer)|"
            r"co\s+je\s+nového|co\s+se\s+děje)"
            r"[!?.\s]*$",
            re.IGNORECASE,
        )
        if (
            msg_len < 200
            and intent_categories == {ToolCategory.CORE}
            and not request.context_task_id
            and _GREETING_RE.match(request.message)
        ):
            logger.info("Chat: greeting message (%d chars, CORE-only) — trying direct answer", msg_len)
            try:
                direct_response = await llm_provider.completion(
                    messages=messages,
                    tier=ModelTier.LOCAL_FAST,
                    tools=None,  # No tools → model MUST answer directly
                    max_tokens=2048,
                    temperature=0.1,
                    extra_headers={"X-Ollama-Priority": "0"},
                )
                direct_text = direct_response.choices[0].message.content or ""

                # Reject if model hallucinates tool usage or admits it needs tools.
                # _NEEDS_TOOLS_MARKERS: model says "I don't know / I need to search".
                # _FAKE_TOOL_MARKERS: model pretends it called tools (hallucination).
                _NEEDS_TOOLS_MARKERS = ["potřebuji", "nemám informac", "nevím", "musím", "nemohu"]
                _FAKE_TOOL_MARKERS = [
                    "pomocí kb_search", "pomocí brain_", "pomocí code_search",
                    "pomocí web_search", "pomocí memory_",
                    "kb_search", "brain_search", "code_search",
                    "web_search", "memory_recall",
                    "krok 1:", "krok 2:", "step 1:", "step 2:",
                    "vyhledal jsem", "našel jsem v",
                ]
                direct_lower = direct_text.lower()
                has_tool_markers = any(m in direct_lower for m in _NEEDS_TOOLS_MARKERS)
                has_fake_tools = any(m in direct_lower for m in _FAKE_TOOL_MARKERS)
                if direct_text.strip() and not has_tool_markers and not has_fake_tools:
                    logger.info("Chat: direct answer accepted (%d chars, no tools needed)", len(direct_text))
                    for i in range(0, len(direct_text), STREAM_CHUNK_SIZE):
                        yield ChatStreamEvent(type="token", content=direct_text[i:i + STREAM_CHUNK_SIZE])
                        await asyncio.sleep(0.03)
                    await chat_context_assembler.save_message(
                        conversation_id=request.session_id,
                        role="ASSISTANT", content=direct_text,
                        correlation_id=str(ObjectId()),
                        sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                        metadata={"direct_answer": "true"},
                    )
                    try:
                        await chat_context_assembler.maybe_compress(request.session_id)
                    except Exception:
                        pass
                    yield ChatStreamEvent(type="done", metadata={"direct_answer": True, "iterations": 0})
                    return
                else:
                    logger.info("Chat: direct answer rejected (tool_markers=%s, fake_tools=%s), falling through",
                                has_tool_markers, has_fake_tools)
            except Exception as e:
                logger.warning("Chat: direct answer attempt failed (%s), falling through", e)

        # Dynamic max iterations based on message length.
        # Long messages (>8k chars) produce ~40k token context — each iteration costs 3-5 min.
        # 3 iterations is enough (1 search + 1 action + 1 response). 6 would cause tier escalation.
        effective_max_iterations = MAX_ITERATIONS_LONG if msg_len > DECOMPOSE_THRESHOLD else MAX_ITERATIONS

        # Track tool call history for repeat detection (across all iterations, not just consecutive)
        tool_call_history: list[tuple[str, str]] = []  # (tool_name, args_hash) — for repeat detection

        for iteration in range(effective_max_iterations):
            # Check for disconnect/stop between iterations
            if disconnect_event and disconnect_event.is_set():
                logger.info("Chat: stopped by disconnect/stop after %d iterations", iteration)
                partial = _build_interrupted_content(tool_summaries)
                if partial:
                    await chat_context_assembler.save_message(
                        conversation_id=request.session_id,
                        role="ASSISTANT",
                        content=partial,
                        correlation_id=str(ObjectId()),
                        sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                        metadata={"interrupted": "true"},
                    )
                yield ChatStreamEvent(type="done", metadata={"interrupted": True})
                return

            logger.info("Chat: iteration %d/%d", iteration + 1, effective_max_iterations)

            # Estimate context tokens (1 token ≈ 4 chars)
            message_chars = sum(len(str(m)) for m in messages)
            message_tokens = message_chars // 4
            tools_tokens = sum(len(str(t)) for t in selected_tools) // 4
            output_tokens = 4096
            estimated_tokens = message_tokens + tools_tokens + output_tokens

            tier = llm_provider.escalation.select_local_tier(estimated_tokens)

            # CLAMP: foreground chat NEVER goes above LOCAL_LARGE.
            # LOCAL_XLARGE (131k) causes catastrophic CPU spill on P40 — 630s for 387 tokens.
            # Instead: stay at LOCAL_LARGE and let pre-trim handle the overflow.
            if _TIER_INDEX.get(tier, 0) > _TIER_INDEX.get(CHAT_MAX_TIER, 2):
                logger.warning(
                    "Chat: tier %s clamped to %s (estimated_tokens=%d, VRAM protection)",
                    tier.value, CHAT_MAX_TIER.value, estimated_tokens,
                )
                tier = CHAT_MAX_TIER

            logger.info("Chat: estimated_tokens=%d (msgs=%d + tools=%d + output=%d) → tier=%s",
                        estimated_tokens, message_tokens, tools_tokens, output_tokens, tier.value)

            # Warn user when context exceeds GPU VRAM (40k) — first iteration only
            if iteration == 0 and estimated_tokens > 40_000:
                yield ChatStreamEvent(
                    type="thinking",
                    content="Dlouhá zpráva — zpracování potrvá déle...",
                )

            response = await llm_provider.completion(
                messages=messages,
                tier=tier,
                tools=selected_tools,
                max_tokens=4096,
                temperature=0.1,
                extra_headers={"X-Ollama-Priority": "0"},  # CRITICAL — foreground chat
            )

            choice = response.choices[0]
            message_obj = choice.message

            # Parse tool calls (including Ollama JSON workaround)
            tool_calls, remaining_text = _extract_tool_calls(message_obj)

            if not tool_calls:
                # No tool calls -> final text response
                final_text = remaining_text or message_obj.content or ""
                logger.info("Chat: final answer after %d iterations (%d chars)", iteration + 1, len(final_text))

                # Stream response in chunks (progressive rendering)
                for i in range(0, len(final_text), STREAM_CHUNK_SIZE):
                    chunk = final_text[i:i + STREAM_CHUNK_SIZE]
                    yield ChatStreamEvent(type="token", content=chunk)
                    await asyncio.sleep(0.03)  # 30ms delay for visible streaming effect

                # Save assistant message to MongoDB
                await chat_context_assembler.save_message(
                    conversation_id=request.session_id,
                    role="ASSISTANT",
                    content=final_text,
                    correlation_id=str(ObjectId()),
                    sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                    metadata={
                        **({"used_tools": ",".join(used_tools)} if used_tools else {}),
                        **({"created_tasks": ",".join(str(t.get("title", "")) for t in created_tasks)} if created_tasks else {}),
                        **({"responded_tasks": ",".join(responded_tasks)} if responded_tasks else {}),
                        **({"summarized": "true", "original_length": str(msg_len)} if is_summarized else {}),
                    },
                )

                # Fire-and-forget compression
                try:
                    await chat_context_assembler.maybe_compress(request.session_id)
                except Exception as compress_err:
                    logger.warning("Chat compression failed: %s", compress_err)

                # Done
                yield ChatStreamEvent(type="done", metadata={
                    "created_tasks": created_tasks,
                    "responded_tasks": responded_tasks,
                    "used_tools": used_tools,
                    "iterations": iteration + 1,
                })
                return

            # --- Drift detection (multi-signal) ---
            # Signal 1: Consecutive same tool+args
            tool_sig = "|".join(
                f"{tc.function.name}:{tc.function.arguments}" for tc in tool_calls
            )
            if tool_sig == last_tool_sig:
                consecutive_same += 1
            else:
                consecutive_same = 1
                last_tool_sig = tool_sig

            # Signal 2: Domain tracking for drift detection
            iter_domains = set()
            for tc in tool_calls:
                domain = TOOL_DOMAINS.get(tc.function.name, "unknown")
                iter_domains.add(domain)
                distinct_tools_used.add(tc.function.name)
            domain_history.append(iter_domains)

            # Track tool call history for repeat detection across all iterations
            for tc in tool_calls:
                tool_call_history.append((tc.function.name, tc.function.arguments))

            # Check drift signals
            drift_reason = _detect_drift(
                consecutive_same=consecutive_same,
                domain_history=domain_history,
                distinct_tools_used=distinct_tools_used,
                iteration=iteration,
                tool_call_history=tool_call_history,
            )

            if drift_reason:
                logger.warning("Chat: drift detected (%s), forcing response", drift_reason)
                messages.append({
                    "role": "system",
                    "content": (
                        f"STOP — {drift_reason}. "
                        "Odpověz uživateli s tím co víš. Nevolej žádné další tools."
                    ),
                })
                break_response = await llm_provider.completion(
                    messages=messages,
                    tier=tier,
                    tools=None,
                    max_tokens=4096,
                    temperature=0.1,
                    extra_headers={"X-Ollama-Priority": "0"},
                )
                final_text = break_response.choices[0].message.content or "Nemám dostatek informací pro odpověď."
                logger.info("Chat: drift-break response (%d chars)", len(final_text))
                for i in range(0, len(final_text), STREAM_CHUNK_SIZE):
                    yield ChatStreamEvent(type="token", content=final_text[i:i + STREAM_CHUNK_SIZE])
                    await asyncio.sleep(0.03)
                await chat_context_assembler.save_message(
                    conversation_id=request.session_id,
                    role="ASSISTANT", content=final_text,
                    correlation_id=str(ObjectId()),
                    sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                    metadata={"drift_break": drift_reason, "used_tools": ",".join(used_tools)},
                )
                yield ChatStreamEvent(type="done", metadata={"drift_break": drift_reason, "iterations": iteration + 1})
                return

            # Execute tool calls
            logger.info("Chat: executing %d tool calls", len(tool_calls))

            # Build assistant message for LLM context
            assistant_msg = {"role": "assistant", "content": remaining_text or None, "tool_calls": []}
            for tc in tool_calls:
                assistant_msg["tool_calls"].append({
                    "id": tc.id,
                    "type": "function",
                    "function": {"name": tc.function.name, "arguments": tc.function.arguments},
                })
            messages.append(assistant_msg)

            for tool_call in tool_calls:
                # Cooperative disconnect check INSIDE tool execution loop.
                # Without this, a 268s LLM call completes, then we execute tools
                # for a stream that was already cancelled — wasting GPU cycles.
                if disconnect_event and disconnect_event.is_set():
                    logger.info("Chat: disconnect detected during tool execution (iter %d)", iteration)
                    partial = _build_interrupted_content(tool_summaries)
                    if partial:
                        await chat_context_assembler.save_message(
                            conversation_id=request.session_id,
                            role="ASSISTANT", content=partial,
                            correlation_id=str(ObjectId()),
                            sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                            metadata={"interrupted": "true"},
                        )
                    yield ChatStreamEvent(type="done", metadata={"interrupted": True})
                    return

                tool_name = tool_call.function.name
                try:
                    arguments = json.loads(tool_call.function.arguments)
                except json.JSONDecodeError:
                    logger.warning("Chat: malformed tool arguments for %s: %s",
                                   tool_name, tool_call.function.arguments[:200])
                    # Report parse error back to LLM instead of running with empty args
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "content": f"Chyba: argumenty pro {tool_name} nejsou platný JSON. Oprav formát a zkus znovu.",
                    })
                    continue

                logger.info("Chat: calling tool %s with args: %s", tool_name, str(arguments)[:200])

                # Thinking event — human-readable description before tool call
                thinking_text = _describe_tool_call(tool_name, arguments)
                yield ChatStreamEvent(type="thinking", content=thinking_text)

                yield ChatStreamEvent(
                    type="tool_call",
                    content=tool_name,
                    metadata={"tool": tool_name, "args": arguments},
                )

                # --- switch_context: resolve names → IDs, emit scope_change ---
                if tool_name == "switch_context":
                    resolved = _resolve_switch_context(arguments, runtime_ctx)
                    result = resolved["message"]
                    used_tools.append(tool_name)
                    tool_summaries.append(f"switch_context: {result[:100]}")

                    if resolved.get("client_id"):
                        effective_client_id = resolved["client_id"]
                        effective_project_id = resolved.get("project_id")
                        yield ChatStreamEvent(
                            type="scope_change",
                            metadata={
                                "clientId": resolved["client_id"],
                                "clientName": resolved.get("client_name", ""),
                                "projectId": resolved.get("project_id", ""),
                                "projectName": resolved.get("project_name", ""),
                                "projects": _resolve_client_projects_json(
                                    resolved["client_id"], runtime_ctx
                                ),
                            },
                        )
                else:
                    # Execute regular tool
                    result = await _execute_chat_tool(
                        tool_name, arguments,
                        request.active_client_id,
                        request.active_project_id,
                    )
                    used_tools.append(tool_name)
                    tool_summaries.append(f"{tool_name}: {result[:100]}")

                    # Track created/responded tasks
                    if tool_name == "create_background_task":
                        created_tasks.append(arguments)
                    if tool_name == "respond_to_user_task":
                        responded_tasks.append(arguments.get("task_id", ""))

                    # Track scope from tool arguments (e.g. create_background_task with different client_id)
                    tool_client = arguments.get("client_id")
                    tool_project = arguments.get("project_id")
                    if tool_client and tool_client != effective_client_id:
                        effective_client_id = tool_client
                        effective_project_id = tool_project
                        yield ChatStreamEvent(
                            type="scope_change",
                            metadata={
                                "clientId": tool_client,
                                "clientName": _resolve_client_name(tool_client, runtime_ctx) or "",
                                "projectId": tool_project or "",
                                "projectName": _resolve_project_name(tool_client, tool_project, runtime_ctx) or "",
                                "projects": _resolve_client_projects_json(tool_client, runtime_ctx),
                            },
                        )
                    elif tool_project and tool_project != effective_project_id:
                        effective_project_id = tool_project
                        yield ChatStreamEvent(
                            type="scope_change",
                            metadata={
                                "clientId": effective_client_id or "",
                                "clientName": _resolve_client_name(effective_client_id, runtime_ctx) or "",
                                "projectId": tool_project,
                                "projectName": _resolve_project_name(effective_client_id, tool_project, runtime_ctx) or "",
                                "projects": _resolve_client_projects_json(effective_client_id, runtime_ctx),
                            },
                        )

                yield ChatStreamEvent(
                    type="tool_result",
                    content=result[:500],
                    metadata={"tool": tool_name},
                )

                # Append to messages for next iteration
                messages.append({
                    "role": "tool",
                    "tool_call_id": tool_call.id,
                    "content": result,
                })

            # --- Focus reminder after tool results (Component C) ---
            remaining_iters = effective_max_iterations - iteration - 1
            messages.append({
                "role": "system",
                "content": (
                    f'[FOCUS] Původní otázka: "{request.message[:200]}"\n'
                    f"Zbývá {remaining_iters} iterací. Pokud máš dost info, ODPOVĚZ."
                ),
            })

            # --- Thinking event between iterations (Component E) ---
            # Prevents UI from showing stale "Přepínám na..." during next LLM call
            yield ChatStreamEvent(type="thinking", content="Analyzuji výsledky...")

        # Max iterations reached — force text response without tools
        logger.warning("Chat: max iterations (%d) reached, forcing response", effective_max_iterations)
        messages.append({
            "role": "system",
            "content": "Dosáhl jsi maximálního počtu iterací. Odpověz uživateli s tím co víš. Nevolej žádné tools.",
        })
        try:
            # Use clamped tier for forced response too
            if _TIER_INDEX.get(tier, 0) > _TIER_INDEX.get(CHAT_MAX_TIER, 2):
                tier = CHAT_MAX_TIER
            final_resp = await llm_provider.completion(
                messages=messages,
                tier=tier,
                tools=None,
                max_tokens=4096,
                temperature=0.1,
                extra_headers={"X-Ollama-Priority": "0"},
            )
            final_text = final_resp.choices[0].message.content or "Omlouvám se, vyčerpal jsem limit operací."
            for i in range(0, len(final_text), STREAM_CHUNK_SIZE):
                yield ChatStreamEvent(type="token", content=final_text[i:i + STREAM_CHUNK_SIZE])
                await asyncio.sleep(0.03)
            await chat_context_assembler.save_message(
                conversation_id=request.session_id,
                role="ASSISTANT", content=final_text,
                correlation_id=str(ObjectId()),
                sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                metadata={"max_iterations": "true", "used_tools": ",".join(used_tools)},
            )
            yield ChatStreamEvent(type="done", metadata={"max_iterations": True, "iterations": effective_max_iterations})
        except Exception as e:
            logger.error("Chat: failed to generate max-iterations response: %s", e)
            yield ChatStreamEvent(type="error", content="Vyčerpán limit operací.")

    except Exception as e:
        logger.exception("Chat handler error: %s", e)

        # Error recovery: save partial results if we had tool calls
        if tool_summaries:
            partial_content = (
                f"Provedl jsem {len(tool_summaries)} operací ale došlo k chybě:\n"
                + "\n".join(f"- {s}" for s in tool_summaries)
                + f"\n\nChyba: {e}"
            )
            try:
                await chat_context_assembler.save_message(
                    conversation_id=request.session_id,
                    role="ASSISTANT",
                    content=partial_content,
                    correlation_id=str(ObjectId()),
                    sequence=await chat_context_assembler.get_next_sequence(request.session_id),
                    metadata={"interrupted": "true", "error": str(e)},
                )
                logger.info("Chat: saved partial response (%d tool results)", len(tool_summaries))
            except Exception as save_err:
                logger.warning("Chat: failed to save partial response: %s", save_err)

        yield ChatStreamEvent(type="error", content=str(e), metadata={"error": str(e)})

    finally:
        # Release foreground
        try:
            from app.tools.kotlin_client import kotlin_client
            await kotlin_client.register_foreground_end()
        except Exception as e:
            logger.warning("Failed to register foreground end: %s", e)


# ------------------------------------------------------------------
# Drift detection
# ------------------------------------------------------------------


def _detect_drift(
    consecutive_same: int,
    domain_history: list[set[str]],
    distinct_tools_used: set[str],
    iteration: int,
    tool_call_history: list[tuple[str, str]] | None = None,
) -> str | None:
    """Multi-signal drift detection.

    Returns a human-readable reason if drift is detected, None otherwise.

    Signals:
    1. Consecutive same: 2× identical tool+args → stuck in loop
    2. Same tool repeated: same tool name called 3+ times across ANY iterations
    3. Domain drift: 3 iterations with 3+ distinct domains and no common domain → wandering
    4. Excessive tools: 8+ distinct tools after 4+ iterations → unfocused
    """
    # Signal 1: Consecutive same tool+args (existing behavior)
    if consecutive_same >= 2:
        return "opakovaně voláš stejný tool se stejnými argumenty"

    # Signal 2: Same tool name called 3+ times across all iterations (not just consecutive).
    # Catches: kb_search called in iter 1, 4, 6 with same/similar args (test #2 bug §3.2).
    if tool_call_history and len(tool_call_history) >= 3:
        from collections import Counter
        tool_name_counts = Counter(name for name, _ in tool_call_history)
        for tool_name, count in tool_name_counts.items():
            if count >= 3:
                return f"tool '{tool_name}' volán {count}× — opakuješ se, odpověz s tím co máš"

    # Signal 3: Domain drift — 3+ iterations, each with different domains, no overlap
    if len(domain_history) >= 3:
        last_three = domain_history[-3:]
        all_domains = set()
        for d in last_three:
            all_domains.update(d)
        # Check if there's ANY common domain across all 3 iterations
        common = last_three[0]
        for d in last_three[1:]:
            common = common & d
        if not common and len(all_domains) >= 3:
            return f"tool calls přeskakují mezi nesouvisejícími oblastmi ({', '.join(sorted(all_domains))})"

    # Signal 4: Excessive distinct tools after 4+ iterations
    if iteration >= 4 and len(distinct_tools_used) >= 8:
        return f"použito {len(distinct_tools_used)} různých toolů — příliš rozptýlené"

    return None


# ------------------------------------------------------------------
# Runtime context cache (clients-projects, TTL 5min)
# ------------------------------------------------------------------

_clients_cache: list[dict] = []
_clients_cache_at: float = 0
_CLIENTS_CACHE_TTL = 300  # 5 min


async def _load_runtime_context() -> RuntimeContext:
    """Load runtime data for system prompt enrichment.

    Clients/projects are cached (TTL 5min), pending tasks and meetings are always fresh.
    """
    import time

    from app.tools.kotlin_client import kotlin_client

    global _clients_cache, _clients_cache_at

    # Clients/projects — cached
    now = time.monotonic()
    if now - _clients_cache_at > _CLIENTS_CACHE_TTL or not _clients_cache:
        try:
            _clients_cache = await kotlin_client.get_clients_projects()
            _clients_cache_at = now
        except Exception as e:
            logger.warning("Failed to load clients-projects: %s", e)

    # Pending user tasks — always fresh
    try:
        pending = await kotlin_client.get_pending_user_tasks_summary(limit=3)
    except Exception as e:
        logger.warning("Failed to load pending user tasks: %s", e)
        pending = {"count": 0, "tasks": []}

    # Unclassified meetings — always fresh
    try:
        unclassified = await kotlin_client.count_unclassified_meetings()
    except Exception as e:
        logger.warning("Failed to count unclassified meetings: %s", e)
        unclassified = 0

    # Learned procedures — from KB (cached with clients, 5min TTL)
    learned_procedures: list[str] = []
    try:
        learned_procedures = await _load_learned_procedures()
    except Exception as e:
        logger.warning("Failed to load learned procedures: %s", e)

    return RuntimeContext(
        clients_projects=_clients_cache,
        pending_user_tasks=pending,
        unclassified_meetings_count=unclassified,
        learned_procedures=learned_procedures,
    )


# Cached learned procedures (TTL same as clients cache — 5min)
_procedures_cache: list[str] = []
_procedures_cache_at: float = 0


async def _load_learned_procedures() -> list[str]:
    """Load learned procedures/conventions from KB for system prompt enrichment.

    Searches KB for entries stored via memory_store(category="procedure").
    Cached for 5 minutes to avoid per-message KB search overhead.

    Returns list of procedure strings (max 20).
    """
    import time

    global _procedures_cache, _procedures_cache_at

    now = time.monotonic()
    if now - _procedures_cache_at < _CLIENTS_CACHE_TTL and _procedures_cache:
        return _procedures_cache

    try:
        from app.tools.executor import execute_tool

        # Search KB for stored procedures
        result = await execute_tool(
            tool_name="kb_search",
            arguments={"query": "postup konvence pravidlo procedure convention", "max_results": 20},
            client_id="",
            project_id=None,
            processing_mode="FOREGROUND",
        )

        # Parse results — extract key lines from KB search output
        procedures: list[str] = []
        if result and not result.startswith("Error"):
            for line in result.split("\n"):
                line = line.strip()
                # Filter for procedure-like entries (stored by memory_store with category=procedure)
                if line and len(line) > 10 and len(line) < 500:
                    if any(kw in line.lower() for kw in ["postup", "konvence", "pravidlo", "procedure", "vždy", "nikdy", "default"]):
                        procedures.append(line)

        _procedures_cache = procedures[:20]
        _procedures_cache_at = now
        if procedures:
            logger.info("Chat: loaded %d learned procedures for system prompt", len(procedures))
        return _procedures_cache

    except Exception as e:
        logger.warning("Failed to load learned procedures from KB: %s", e)
        return _procedures_cache  # Return stale cache on error


def _build_messages(
    system_prompt: str,
    context,
    task_context_msg: dict | None,
    current_message: str,
) -> list[dict]:
    """Build LLM messages from context + current message.

    Order:
    1. System prompt (who am I, rules, tools, scope)
    2. [Summaries + memory] from AssembledContext (system message)
    3. [Task context] if responding to user_task (system message)
    4. Recent messages (verbatim user/assistant)
    5. Current user message
    """
    messages = []

    # 1. System prompt
    messages.append({"role": "system", "content": system_prompt})

    # 2. Summaries + memory from AssembledContext
    for msg in context.messages:
        messages.append(msg)

    # 3. Task context (if user_task)
    if task_context_msg:
        messages.append(task_context_msg)

    # 4. Current message
    messages.append({"role": "user", "content": current_message})

    return messages


async def _load_task_context_message(task_id: str) -> dict | None:
    """Load task context for user_task response."""
    try:
        from app.tools.kotlin_client import kotlin_client
        task_data = await kotlin_client.get_user_task(task_id)
        if not task_data:
            return None

        return {
            "role": "system",
            "content": (
                f"[Kontext user_task {task_id}]\n"
                f"Název: {task_data.get('title', 'N/A')}\n"
                f"Otázka: {task_data.get('question', 'N/A')}\n"
                f"Dosavadní kontext:\n{task_data.get('context', 'N/A')}\n"
                f"\nUser na tuto otázku odpovídá v následující zprávě. "
                f"Po zpracování odpovědi zavolej respond_to_user_task."
            ),
        }
    except Exception as e:
        logger.warning("Failed to load task context for %s: %s", task_id, e)
        return None


class _ToolCall:
    """Lightweight tool call object for Ollama JSON workaround."""

    def __init__(self, tc_dict: dict):
        self.id = tc_dict.get("id", str(uuid.uuid4())[:8])
        self.type = tc_dict.get("type", "function")

        class Function:
            def __init__(self, f_dict):
                self.name = f_dict.get("name", "")
                self.arguments = json.dumps(f_dict.get("arguments", {}))
        self.function = Function(tc_dict.get("function", {}))


def _extract_tool_calls(message) -> tuple[list, str | None]:
    """Extract tool calls from LLM response, including Ollama JSON workaround.

    Returns (tool_calls, remaining_text).

    Handles:
    1. Standard litellm tool_calls field
    2. Ollama JSON-in-content {"tool_calls": [...]}
    3. JSON embedded in markdown ```json blocks
    4. Pure text (no tools)
    """
    # 1. Standard litellm tool_calls
    tool_calls = getattr(message, "tool_calls", None)
    if tool_calls:
        return tool_calls, message.content

    if not message.content:
        return [], None

    content = message.content.strip()

    # 2. Pure JSON {"tool_calls": [...]}
    try:
        parsed = json.loads(content)
        if isinstance(parsed, dict) and "tool_calls" in parsed:
            logger.info("Chat: parsing tool_calls from JSON content (Ollama workaround)")
            calls = [_ToolCall(tc) for tc in parsed["tool_calls"]]
            logger.info("Chat: extracted %d tool calls from JSON", len(calls))
            return calls, None
    except (json.JSONDecodeError, KeyError, TypeError):
        pass

    # 3. JSON in markdown ```json block
    md_match = re.search(r'```(?:json)?\s*(\{.*?"tool_calls".*?\})\s*```', content, re.DOTALL)
    if md_match:
        try:
            parsed = json.loads(md_match.group(1))
            remaining = content[:md_match.start()] + content[md_match.end():]
            remaining = remaining.strip() or None
            calls = [_ToolCall(tc) for tc in parsed["tool_calls"]]
            logger.info("Chat: extracted %d tool calls from markdown JSON block", len(calls))
            return calls, remaining
        except (json.JSONDecodeError, KeyError, TypeError):
            pass

    # 4. Pure text — no tool calls
    return [], content


def _describe_tool_call(name: str, args: dict) -> str:
    """Human-readable description of a tool call for thinking events."""
    descriptions = {
        "kb_search": f"Hledám v KB: {args.get('query', '')}",
        "web_search": f"Hledám na webu: {args.get('query', '')}",
        "code_search": f"Hledám v kódu: {args.get('query', '')}",
        "store_knowledge": f"Ukládám znalost: {args.get('subject', '')}",
        "memory_store": f"Zapamatuji si: {args.get('subject', '')}",
        "memory_recall": f"Vzpomínám: {args.get('query', '')}",
        "list_affairs": "Kontroluji aktivní témata",
        "get_kb_stats": "Zjišťuji statistiky KB",
        "get_indexed_items": "Kontroluji indexovaný obsah",
        "brain_create_issue": f"Vytvářím issue: {args.get('summary', '')}",
        "brain_update_issue": f"Aktualizuji issue: {args.get('issue_key', '')}",
        "brain_add_comment": f"Přidávám komentář k: {args.get('issue_key', '')}",
        "brain_transition_issue": f"Měním stav: {args.get('issue_key', '')} → {args.get('transition_name', '')}",
        "brain_search_issues": f"Hledám v Jiře: {args.get('jql', '')}",
        "brain_create_page": f"Vytvářím stránku: {args.get('title', '')}",
        "brain_update_page": f"Aktualizuji stránku: {args.get('page_id', '')}",
        "brain_search_pages": f"Hledám v Confluence: {args.get('query', '')}",
        "create_background_task": f"Vytvářím úkol: {args.get('title', '')}",
        "dispatch_coding_agent": "Odesílám coding task na agenta",
        "search_user_tasks": f"Hledám úkoly: {args.get('query', '')}",
        "search_tasks": f"Hledám úkoly: {args.get('query', '')}",
        "respond_to_user_task": f"Odpovídám na úkol: {args.get('task_id', '')}",
        "get_task_status": f"Kontroluji stav úkolu: {args.get('task_id', '')}",
        "list_recent_tasks": "Kontroluji nedávné úkoly",
        "classify_meeting": f"Klasifikuji nahrávku: {args.get('meeting_id', '')}",
        "list_unclassified_meetings": "Kontroluji neklasifikované nahrávky",
        "switch_context": f"Přepínám na: {args.get('client', '')} {args.get('project', '')}".strip(),
    }
    return descriptions.get(name, f"Zpracovávám: {name}")


async def _execute_chat_tool(
    tool_name: str,
    arguments: dict,
    active_client_id: str | None,
    active_project_id: str | None,
) -> str:
    """Execute a tool call, handling both base tools and chat-specific tools."""
    # Chat-specific tools that go through kotlin internal API
    chat_specific_tools = {
        "create_background_task",
        "dispatch_coding_agent",
        "search_user_tasks",  # backward compat (old tool name)
        "search_tasks",
        "get_task_status",
        "list_recent_tasks",
        "respond_to_user_task",
        "classify_meeting",
        "list_unclassified_meetings",
    }

    if tool_name in chat_specific_tools:
        return await _execute_chat_specific_tool(tool_name, arguments, active_client_id, active_project_id)

    # Base tools — use existing executor
    return await execute_tool(
        tool_name=tool_name,
        arguments=arguments,
        client_id=active_client_id or "",
        project_id=active_project_id,
        processing_mode="FOREGROUND",
    )


async def _execute_chat_specific_tool(
    tool_name: str,
    arguments: dict,
    active_client_id: str | None,
    active_project_id: str | None,
) -> str:
    """Execute chat-specific tools via Kotlin internal API."""
    try:
        from app.tools.kotlin_client import kotlin_client

        if tool_name == "create_background_task":
            effective_client_id = arguments.get("client_id") or active_client_id
            if not effective_client_id:
                return "Chyba: client_id je povinný pro vytvoření background tasku. Zeptej se uživatele na klienta."
            result = await kotlin_client.create_background_task(
                title=arguments["title"],
                description=arguments["description"],
                client_id=effective_client_id,
                project_id=arguments.get("project_id", active_project_id),
                priority=arguments.get("priority", "medium"),
            )
            return f"Background task created: {result}"

        elif tool_name == "dispatch_coding_agent":
            effective_client_id = arguments.get("client_id") or active_client_id
            effective_project_id = arguments.get("project_id") or active_project_id
            if not effective_client_id or not effective_project_id:
                return "Chyba: client_id a project_id jsou povinné pro dispatch coding agenta. Zeptej se uživatele."
            result = await kotlin_client.dispatch_coding_agent(
                task_description=arguments["task_description"],
                client_id=effective_client_id,
                project_id=effective_project_id,
            )
            return f"Coding agent dispatched: {result}"

        elif tool_name in ("search_user_tasks", "search_tasks"):
            result = await kotlin_client.search_tasks(
                query=arguments["query"],
                state=arguments.get("state"),
                max_results=arguments.get("max_results", 5),
            )
            return result

        elif tool_name == "get_task_status":
            return await kotlin_client.get_task_status(arguments["task_id"])

        elif tool_name == "list_recent_tasks":
            return await kotlin_client.list_recent_tasks(
                limit=arguments.get("limit", 10),
                state=arguments.get("state"),
                since=arguments.get("since", "today"),
                client_id=arguments.get("client_id"),
            )

        elif tool_name == "respond_to_user_task":
            result = await kotlin_client.respond_to_user_task(
                task_id=arguments["task_id"],
                response=arguments["response"],
            )
            return f"User task responded: {result}"

        elif tool_name == "classify_meeting":
            result = await kotlin_client.classify_meeting(
                meeting_id=arguments["meeting_id"],
                client_id=arguments["client_id"],
                project_id=arguments.get("project_id"),
                title=arguments.get("title"),
            )
            return f"Meeting classified: {result}"

        elif tool_name == "list_unclassified_meetings":
            result = await kotlin_client.list_unclassified_meetings()
            return result

        else:
            return f"Unknown chat tool: {tool_name}"

    except Exception as e:
        logger.warning("Chat tool %s failed: %s", tool_name, e)
        return f"Tool error: {e}"


def _resolve_switch_context(arguments: dict, ctx: RuntimeContext) -> dict:
    """Resolve client/project names to IDs from cached runtime context.

    Returns dict with:
      - client_id, client_name, project_id, project_name (on success)
      - message: human-readable result or error for LLM
    """
    client_name_query = (arguments.get("client") or "").strip().lower()
    project_name_query = (arguments.get("project") or "").strip().lower()

    if not client_name_query:
        available = ", ".join(c.get("name", "?") for c in ctx.clients_projects)
        return {"message": f"Chybí jméno klienta. Dostupní klienti: {available}"}

    # Find client by name — prefer exact match, then substring.
    # If multiple substring matches exist, report ambiguity instead of picking first.
    exact_match = None
    substring_matches: list[dict] = []
    for c in ctx.clients_projects:
        cname = (c.get("name") or "").lower()
        if cname == client_name_query:
            exact_match = c
            break
        elif client_name_query in cname:
            substring_matches.append(c)

    if exact_match:
        matched_client = exact_match
    elif len(substring_matches) == 1:
        matched_client = substring_matches[0]
    elif len(substring_matches) > 1:
        ambiguous_names = ", ".join(c.get("name", "?") for c in substring_matches)
        return {
            "message": (
                f"'{arguments.get('client')}' odpovídá více klientům: {ambiguous_names}. "
                f"Upřesni, kterého myslíš."
            ),
        }
    else:
        matched_client = None

    if not matched_client:
        available = ", ".join(c.get("name", "?") for c in ctx.clients_projects)
        return {
            "message": (
                f"Klient '{arguments.get('client')}' nenalezen. "
                f"Dostupní klienti: {available}"
            ),
        }

    client_id = matched_client["id"]
    client_name = matched_client.get("name", "")
    result = {
        "client_id": client_id,
        "client_name": client_name,
        "message": f"Přepnuto na {client_name}",
    }

    # Resolve project if requested — same exact-then-substring logic
    if project_name_query:
        projects = matched_client.get("projects", [])
        matched_project = None
        project_substring_matches: list[dict] = []
        for p in projects:
            pname = (p.get("name") or "").lower()
            if pname == project_name_query:
                matched_project = p
                break
            elif project_name_query in pname:
                project_substring_matches.append(p)

        if not matched_project:
            if len(project_substring_matches) == 1:
                matched_project = project_substring_matches[0]
            elif len(project_substring_matches) > 1:
                ambiguous_projects = ", ".join(p.get("name", "?") for p in project_substring_matches)
                result["message"] = (
                    f"Přepnuto na {client_name}, ale '{arguments.get('project')}' odpovídá "
                    f"více projektům: {ambiguous_projects}. Upřesni, který myslíš."
                )
                return result

        if matched_project:
            result["project_id"] = matched_project["id"]
            result["project_name"] = matched_project.get("name", "")
            result["message"] = f"Přepnuto na {client_name} / {result['project_name']}"
        else:
            available_projects = ", ".join(p.get("name", "?") for p in projects)
            result["message"] = (
                f"Přepnuto na {client_name}, ale projekt '{arguments.get('project')}' "
                f"nenalezen. Dostupné projekty: {available_projects}"
            )

    return result


def _resolve_client_name(client_id: str | None, ctx: RuntimeContext) -> str | None:
    """Resolve client name from cached runtime context."""
    if not client_id or not ctx:
        return None
    for c in ctx.clients_projects:
        if c.get("id") == client_id:
            return c.get("name")
    return None


def _resolve_project_name(client_id: str | None, project_id: str | None, ctx: RuntimeContext) -> str | None:
    """Resolve project name from cached runtime context."""
    if not client_id or not project_id or not ctx:
        return None
    for c in ctx.clients_projects:
        if c.get("id") == client_id:
            for p in c.get("projects", []):
                if p.get("id") == project_id:
                    return p.get("name")
    return None


def _resolve_client_projects_json(client_id: str | None, ctx: RuntimeContext) -> str:
    """Return JSON array of projects for the given client from cached runtime context."""
    if not client_id or not ctx:
        return "[]"
    for c in ctx.clients_projects:
        if c.get("id") == client_id:
            return json.dumps(c.get("projects", []))
    return "[]"


def _build_interrupted_content(tool_summaries: list[str]) -> str | None:
    """Build partial content for interrupted chat (stop/disconnect)."""
    if not tool_summaries:
        return None
    return (
        f"[Přerušeno po {len(tool_summaries)} operacích]\n"
        + "\n".join(f"- {s}" for s in tool_summaries)
    )


# ------------------------------------------------------------------
# Long message: save original to KB (no-trim principle)
# ------------------------------------------------------------------


async def _save_original_to_kb(
    message: str,
    client_id: str | None,
    project_id: str | None,
    session_id: str,
) -> None:
    """Save original long message to KB before summarization.

    NO-TRIM PRINCIPLE: The original message is NEVER truncated or discarded.
    It's saved to KB so the agent (or a background task) can retrieve it later
    via kb_search if the summary doesn't have enough detail.

    Uses store_knowledge tool (bypasses the anti-dump guard since this is
    the handler itself, not the model calling the tool).
    """
    from app.tools.executor import execute_tool

    # Store with clear metadata for later retrieval
    subject = f"Originální zpráva z chatu (session {session_id}, {len(message)} znaků)"

    # For very long messages, we store as-is — KB handles large content
    result = await execute_tool(
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


# ------------------------------------------------------------------
# Long message decomposition
# ------------------------------------------------------------------

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


async def _summarize_long_message(message: str) -> str | None:
    """Summarize a very long message into a structured compact form.

    Uses LOCAL_FAST (~5s) to create a ~2-4k char summary that preserves
    all key information, action items, questions, and details.

    The agentic loop then works with this summary instead of the raw message.
    Original message is saved to MongoDB verbatim — nothing is lost.

    Returns summary string, or None on failure (fallback to pre-trim).
    """
    msg_len = len(message)

    # Build excerpt: head (2000) + 3 evenly-spaced middle segments (500 each) + tail (1000)
    # Total ~4500 chars — covers the message well for summarization.
    head = message[:2000]
    tail = message[-1000:]

    middle_parts = []
    num_samples = 3
    for i in range(1, num_samples + 1):
        frac = i / (num_samples + 1)
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
        # Timeout 90s: Router GPU cleanup can take up to 60s when embedding model
        # needs to be unloaded (wait loop + force unload). Summarizer timeout MUST be
        # longer than 60s, otherwise it ALWAYS times out when embedding is loaded.
        # Previous 30s timeout caused summarizer to fail every time GPU had embedding model.
        response = await asyncio.wait_for(
            llm_provider.completion(
                messages=[
                    {"role": "system", "content": _SUMMARIZER_SYSTEM},
                    {"role": "user", "content": user_content},
                ],
                tier=ModelTier.LOCAL_FAST,
                tools=None,
                max_tokens=2048,
                temperature=0.1,
                extra_headers={"X-Ollama-Priority": "0"},
            ),
            timeout=90.0,  # Must be > 60s (router GPU cleanup wait)
        )

        summary = response.choices[0].message.content or ""
        summary = summary.strip()

        if len(summary) < 50:
            logger.warning("Chat summarizer: response too short (%d chars), skipping", len(summary))
            return None

        logger.info(
            "Chat summarizer: %d chars → %d chars summary (%.0f%% reduction)",
            msg_len, len(summary), (1 - len(summary) / msg_len) * 100,
        )
        return summary

    except asyncio.TimeoutError:
        logger.warning("Chat summarizer: timed out (90s), GPU likely busy with model swap")
        return None
    except Exception as e:
        logger.warning("Chat summarizer: failed (%s)", e)
        return None


async def _maybe_decompose(message: str) -> list[SubTopic] | None:
    """Classify whether a long message contains multiple distinct topics.

    Returns list of SubTopic if multi-topic, None if single-topic or on any failure.
    Uses LOCAL_FAST tier (~2-3s on GPU) with head + middle samples + tail excerpt.
    Middle samples at 1/3 and 2/3 positions catch topic boundaries in long messages.
    On ANY error: returns None (safe fallback to existing single-pass flow).
    """
    msg_len = len(message)
    if msg_len < DECOMPOSE_THRESHOLD:
        return None

    # Sampling strategy: head + evenly-spaced middle segments + tail.
    # Total budget ~3500 chars — LOCAL_FAST (8k ctx ≈ 32k chars) has plenty of room.
    # Middle samples catch topic boundaries that head+tail alone would miss.
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

    user_content = "\n".join(parts)

    try:
        # Hard timeout: classifier should finish in ~3-5s on GPU.
        # If GPU is busy (model swap, queue), fall back to single-pass rather than
        # adding 2+ minutes overhead for a classification that may say "single-topic" anyway.
        response = await asyncio.wait_for(
            llm_provider.completion(
                messages=[
                    {"role": "system", "content": _CLASSIFIER_SYSTEM},
                    {"role": "user", "content": user_content},
                ],
                tier=ModelTier.LOCAL_FAST,
                tools=None,
                max_tokens=512,
                temperature=0.1,
                extra_headers={"X-Ollama-Priority": "0"},  # CRITICAL — foreground chat
            ),
            timeout=15.0,
        )

        content = response.choices[0].message.content or ""
        content = content.strip()

        # Strip markdown fences if present
        if content.startswith("```"):
            content = re.sub(r'^```(?:json)?\s*', '', content)
            content = re.sub(r'\s*```$', '', content)

        parsed = json.loads(content)

        topic_count = parsed.get("topic_count", 0)
        if topic_count <= 1:
            logger.debug("Chat decompose: classifier says single-topic")
            return None

        topics_raw = parsed.get("topics", [])
        if not topics_raw or len(topics_raw) > MAX_SUBTOPICS:
            logger.warning("Chat decompose: invalid topic count %d (max %d), fallback",
                           len(topics_raw), MAX_SUBTOPICS)
            return None

        # Build SubTopic objects with clamped char ranges
        subtopics: list[SubTopic] = []

        for t in topics_raw:
            title = t.get("title", "").strip()
            topic_type = t.get("type", "info")
            char_start = max(0, int(t.get("char_start", 0)))
            char_end = min(msg_len, int(t.get("char_end", msg_len)))

            if not title or char_end <= char_start:
                continue

            subtopics.append(SubTopic(
                title=title,
                topic_type=topic_type,
                char_start=char_start,
                char_end=char_end,
            ))

        if len(subtopics) <= 1:
            return None

        # Close gaps > 200 chars between topics (extend previous topic's end)
        subtopics.sort(key=lambda t: t.char_start)
        for i in range(len(subtopics) - 1):
            gap = subtopics[i + 1].char_start - subtopics[i].char_end
            if gap > 200:
                subtopics[i].char_end = subtopics[i + 1].char_start

        # Ensure first topic starts at 0 and last ends at msg_len
        subtopics[0].char_start = 0
        subtopics[-1].char_end = msg_len

        logger.info("Chat decompose: %d topics — %s",
                     len(subtopics), [(t.title, t.char_start, t.char_end) for t in subtopics])
        return subtopics

    except asyncio.TimeoutError:
        logger.warning("Chat decompose: classifier timed out (15s), fallback to single-pass")
        return None
    except Exception as e:
        logger.warning("Chat decompose: classifier failed (%s), fallback to single-pass", e)
        return None


async def _process_sub_topic(
    topic: SubTopic,
    topic_index: int,
    total_topics: int,
    request: ChatRequest,
    context,
    runtime_ctx: RuntimeContext,
    selected_tools: list[dict],
    system_prompt: str,
) -> SubTopicResult:
    """Process one sub-topic through a mini agentic loop.

    Same structure as the main agentic loop but:
    - Fewer iterations (SUBTOPIC_MAX_ITERATIONS)
    - Scoped to the relevant section of the original message
    - Does NOT yield events (caller handles UI)
    - Does NOT save to MongoDB (caller aggregates and saves)
    """
    section = request.message[topic.char_start:topic.char_end]

    # Build scoped user message
    scoped_message = (
        f"[Téma {topic_index}/{total_topics}: {topic.title}]\n\n"
        f"{section}\n\n"
        f"Odpověz POUZE na toto téma. Stručně a věcně."
    )

    messages = _build_messages(
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
            # Estimate tokens and select tier
            message_chars = sum(len(str(m)) for m in messages)
            message_tokens = message_chars // 4
            tools_tokens = sum(len(str(t)) for t in selected_tools) // 4
            estimated_tokens = message_tokens + tools_tokens + 4096

            tier = llm_provider.escalation.select_local_tier(estimated_tokens)
            logger.info("Chat sub-topic %d/%d iter %d: estimated=%d → tier=%s",
                        topic_index, total_topics, iteration + 1, estimated_tokens, tier.value)

            response = await llm_provider.completion(
                messages=messages,
                tier=tier,
                tools=selected_tools,
                max_tokens=4096,
                temperature=0.1,
                extra_headers={"X-Ollama-Priority": "0"},
            )

            choice = response.choices[0]
            message_obj = choice.message
            tool_calls, remaining_text = _extract_tool_calls(message_obj)

            if not tool_calls:
                # Final text response for this sub-topic
                final_text = remaining_text or message_obj.content or ""
                logger.info("Chat sub-topic %d/%d: answer after %d iterations (%d chars)",
                            topic_index, total_topics, iteration + 1, len(final_text))
                return SubTopicResult(
                    topic=topic, text=final_text,
                    used_tools=used_tools, created_tasks=created_tasks,
                    responded_tasks=responded_tasks,
                )

            # --- Drift detection ---
            tool_sig = "|".join(
                f"{tc.function.name}:{tc.function.arguments}" for tc in tool_calls
            )
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

            drift_reason = _detect_drift(
                consecutive_same, domain_history, distinct_tools_used, iteration,
                tool_call_history=tool_call_history,
            )
            if drift_reason:
                logger.warning("Chat sub-topic %d/%d: drift (%s), forcing answer",
                               topic_index, total_topics, drift_reason)
                messages.append({"role": "system", "content": f"STOP — {drift_reason}. Odpověz."})
                forced = await llm_provider.completion(
                    messages=messages, tier=tier, tools=None,
                    max_tokens=4096, temperature=0.1,
                    extra_headers={"X-Ollama-Priority": "0"},
                )
                return SubTopicResult(
                    topic=topic, text=forced.choices[0].message.content or "",
                    used_tools=used_tools, created_tasks=created_tasks,
                    responded_tasks=responded_tasks,
                )

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
                    logger.warning("Chat sub-topic: malformed tool arguments for %s: %s",
                                   tool_name, tool_call.function.arguments[:200])
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "content": f"Chyba: argumenty pro {tool_name} nejsou platný JSON.",
                    })
                    continue

                # Skip switch_context in sub-topics (doesn't make sense per-topic)
                if tool_name == "switch_context":
                    result = "switch_context ignorován v sub-topic zpracování"
                else:
                    result = await _execute_chat_tool(
                        tool_name, arguments,
                        request.active_client_id, request.active_project_id,
                    )
                    used_tools.append(tool_name)

                    if tool_name == "create_background_task":
                        created_tasks.append(arguments)
                    if tool_name == "respond_to_user_task":
                        responded_tasks.append(arguments.get("task_id", ""))

                messages.append({
                    "role": "tool", "tool_call_id": tool_call.id, "content": result,
                })

            # Focus reminder
            remaining_iters = SUBTOPIC_MAX_ITERATIONS - iteration - 1
            messages.append({
                "role": "system",
                "content": (
                    f'[FOCUS] Téma: "{topic.title}". '
                    f"Zbývá {remaining_iters} iterací. Pokud máš dost info, ODPOVĚZ."
                ),
            })

        # Max iterations — force response
        logger.warning("Chat sub-topic %d/%d: max iterations, forcing answer", topic_index, total_topics)
        messages.append({"role": "system", "content": "Dosáhl jsi limitu iterací. Odpověz."})
        forced = await llm_provider.completion(
            messages=messages, tier=tier, tools=None,
            max_tokens=4096, temperature=0.1,
            extra_headers={"X-Ollama-Priority": "0"},
        )
        return SubTopicResult(
            topic=topic, text=forced.choices[0].message.content or "",
            used_tools=used_tools, created_tasks=created_tasks,
            responded_tasks=responded_tasks,
        )

    except Exception as e:
        logger.warning("Chat sub-topic %d/%d failed: %s", topic_index, total_topics, e)
        return SubTopicResult(
            topic=topic, text=f"[Chyba při zpracování tématu '{topic.title}': {e}]",
            used_tools=used_tools, created_tasks=created_tasks,
            responded_tasks=responded_tasks,
        )


async def _combine_results(
    results: list[SubTopicResult],
    original_message_summary: str,
) -> str:
    """Combine sub-topic results into one cohesive response.

    Uses LOCAL_FAST LLM call for natural combination.
    Falls back to plain concatenation on failure.
    """
    if len(results) == 1:
        return results[0].text

    # Build formatted results for combiner
    parts = []
    for r in results:
        parts.append(f"## {r.topic.title}\n{r.text}")
    formatted = "\n\n---\n\n".join(parts)

    try:
        response = await asyncio.wait_for(
            llm_provider.completion(
                messages=[
                    {"role": "system", "content": _COMBINER_SYSTEM},
                    {"role": "user", "content": formatted + "\n\nSpoj do jedné odpovědi."},
                ],
                tier=ModelTier.LOCAL_FAST,
                tools=None,
                max_tokens=4096,
                temperature=0.1,
                extra_headers={"X-Ollama-Priority": "0"},  # CRITICAL — foreground chat
            ),
            timeout=90.0,  # Prevent combiner from exceeding foreground timeout (300s)
        )
        combined = response.choices[0].message.content or ""
        if combined.strip():
            logger.info("Chat combiner: merged %d topics (%d chars)", len(results), len(combined))
            return combined
    except asyncio.TimeoutError:
        logger.warning("Chat combiner timed out (90s), using plain concatenation")
    except Exception as e:
        logger.warning("Chat combiner failed (%s), using plain concatenation", e)

    # Fallback: plain concatenation with markdown headers
    return "\n\n---\n\n".join(parts)
