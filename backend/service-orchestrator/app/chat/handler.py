"""ChatHandler — Jervis foreground chat orchestrator.

Thin entry-point that delegates to specialized modules:
- handler_context: runtime context loading, message building
- handler_streaming: LLM calls, token streaming, message saving
- handler_tools: tool extraction, execution, switch_context
- handler_agentic: main agentic loop with drift detection
- handler_decompose: long message summarization, decomposition, sub-topics

Flow:
1. Register foreground (preempt background)
2. Load context from MongoDB (ChatContextAssembler)
3. Intent classification → select tool subset
4. Long message pre-processing (summarize / decompose)
5. Greeting fast-path OR agentic loop
6. Release foreground (finally)
"""
from __future__ import annotations

import asyncio
import logging
import re
from typing import AsyncIterator

from bson import ObjectId

from app.chat.context import chat_context_assembler
from app.chat.handler_agentic import run_agentic_loop
from app.chat.handler_context import load_runtime_context, build_messages, load_task_context_message
from app.chat.handler_fact_check import run_fact_check, fact_check_metadata, confidence_badge
from app.chat.topic_tracker import detect_topics, update_conversation_topics, topic_metadata
from app.chat.handler_decompose import (
    save_original_to_kb,
    summarize_long_message,
    maybe_decompose,
    process_sub_topic,
    combine_results,
)
from app.chat.handler_streaming import call_llm, stream_text, save_assistant_message
from app.chat.intent import classify_intent, select_tools
from app.chat.intent_decomposer import decompose_intents, build_intent_focus_message, intent_metadata
from app.chat.intent_router import route_intent
from app.chat.models import ChatRequest, ChatStreamEvent, ChatCategory
from app.chat.prompts.builder import build_routed_prompt
from app.chat.system_prompt import build_system_prompt
from app.chat.tools import CHAT_TOOLS, ToolCategory, select_tools_by_names
from app.config import settings
from app.models import ModelTier

logger = logging.getLogger(__name__)

# Anti-dump: tools removed from long messages
_STORAGE_TOOL_NAMES = {"store_knowledge", "memory_store"}

# Greeting detection (compiled once at import time)
_GREETING_RE = re.compile(
    r"^\s*(?:ahoj|čau|zdravím|hej|hi|hello|hey|"
    r"dobr[éý]?\s+(?:ráno|den|odpoledne|večer)|"
    r"co\s+je\s+nového|co\s+se\s+děje)"
    r"[!?.\s]*$",
    re.IGNORECASE,
)

# Markers indicating the model needs tools (not a complete answer)
_NEEDS_TOOLS_MARKERS = ["potřebuji", "nemám informac", "nevím", "musím", "nemohu"]

# Markers indicating the model is hallucinating tool usage
_FAKE_TOOL_MARKERS = [
    "pomocí kb_search",
    "pomocí web_search", "pomocí memory_",
    "kb_search",
    "web_search", "memory_recall",
    "krok 1:", "krok 2:", "step 1:", "step 2:",
    "vyhledal jsem", "našel jsem v",
]


async def handle_chat(
    request: ChatRequest,
    disconnect_event: asyncio.Event | None = None,
) -> AsyncIterator[ChatStreamEvent]:
    """Process a chat message and stream response back.

    Yields ChatStreamEvent objects for SSE streaming to Kotlin -> UI.
    """
    # NOTE: foreground preemption is deferred to agentic loop AFTER route decision.
    # If chat goes to OpenRouter, background keeps the GPU undisturbed.

    try:
        # 1. Load context
        context = await chat_context_assembler.assemble_context(conversation_id=request.session_id)
        runtime_ctx = await load_runtime_context()

        # 1b. Load master map summary for context injection
        master_map_ctx = ""
        try:
            from app.graph_agent.persistence import task_graph_store
            from app.graph_agent.graph import master_map_summary
            master = await task_graph_store.get_or_create_master_graph()
            master_map_ctx = master_map_summary(master, max_tokens=2000)
        except Exception as e:
            logger.warning("Chat: failed to load master map: %s", e)

        # 2. Intent classification → select tool subset
        intent_categories = classify_intent(
            user_message=request.message,
            has_pending_user_tasks=runtime_ctx.pending_user_tasks.get("count", 0) > 0,
            has_unclassified_meetings=runtime_ctx.unclassified_meetings_count > 0,
            has_context_task_id=bool(request.context_task_id),
        )
        selected_tools = select_tools(intent_categories)

        # Anti-dump: remove storage tools for long messages
        if len(request.message) > settings.decompose_threshold:
            before_count = len(selected_tools)
            selected_tools = [t for t in selected_tools if t["function"]["name"] not in _STORAGE_TOOL_NAMES]
            if len(selected_tools) < before_count:
                logger.info("Chat: removed storage tools for long message (%d chars)", len(request.message))

        logger.info("Chat: intent=%s → %d/%d tools",
                     [c.value for c in intent_categories], len(selected_tools), len(CHAT_TOOLS))

        # 2b. Intent Router (feature-flagged)
        routing = None
        if settings.use_intent_router:
            try:
                routing = await route_intent(request.message, intent_categories)
                selected_tools = select_tools_by_names(routing.tool_names)
                logger.info(
                    "Chat: router → %s (conf=%.2f, cloud=%s, max_iter=%d, tools=%d) reason=%s",
                    routing.category.value, routing.confidence, routing.use_cloud,
                    routing.max_iterations, len(selected_tools), routing.reason,
                )
            except Exception as e:
                logger.warning("Chat: intent router failed, using regex fallback: %s", e)
                routing = None

        # 3. Task context
        task_context_msg = None
        if request.context_task_id:
            task_context_msg = await load_task_context_message(request.context_task_id)

        # 4. Build LLM messages
        if routing:
            system_prompt_text = build_routed_prompt(
                category=routing.category,
                active_client_id=request.active_client_id,
                active_project_id=request.active_project_id,
                runtime_context=runtime_ctx,
            )
        else:
            system_prompt_text = await build_system_prompt(
                active_client_id=request.active_client_id,
                active_project_id=request.active_project_id,
                runtime_context=runtime_ctx,
                session_id=request.session_id,
            )
        messages = build_messages(
            system_prompt=system_prompt_text,
            context=context,
            task_context_msg=task_context_msg,
            current_message=request.message,
        )

        # Inject master map summary into messages (if non-empty)
        if master_map_ctx:
            messages.insert(1, {
                "role": "system",
                "content": (
                    "## Thinking Map (current state)\n"
                    f"{master_map_ctx}\n\n"
                    "Use check_task_graph to inspect task details. "
                    "Use answer_blocked_vertex to respond to blocked questions."
                ),
            })
            logger.info("Chat: injected master map summary (%d chars)", len(master_map_ctx))

        msg_len = len(request.message)
        if msg_len > 4000:
            yield ChatStreamEvent(type="thinking", content="Analyzuji dlouhou zprávu...")
        else:
            yield ChatStreamEvent(type="thinking", content="Připravuji odpověď...")

        # 5. Long message pre-processing
        is_summarized = False
        is_long_message = msg_len > settings.summarize_threshold

        if is_long_message:
            yield ChatStreamEvent(type="thinking", content="Analyzuji obsah dlouhé zprávy...")

            try:
                await save_original_to_kb(
                    message=request.message,
                    client_id=request.active_client_id,
                    project_id=request.active_project_id,
                    session_id=request.session_id,
                )
            except Exception as kb_err:
                logger.warning("Chat: failed to save original to KB: %s", kb_err)

            summary = await summarize_long_message(request.message)
            if summary:
                is_summarized = True
                summarized_content = (
                    f"[Uživatel poslal dlouhou zprávu ({msg_len} znaků). "
                    f"Níže je strukturovaný souhrn zachovávající všechny požadavky. "
                    f"Originál je uložen v KB.]\n\n"
                    f"{summary}\n\n"
                    f"[Poznámka: Originální zpráva je uložena v KB (session {request.session_id}). "
                    f"Pokud potřebuješ detaily, hledej přes kb_search.]"
                )
                for i in range(len(messages) - 1, -1, -1):
                    if messages[i].get("role") == "user":
                        messages[i] = {"role": "user", "content": summarized_content}
                        break
                logger.info("Chat: summarized %d chars → %d chars", msg_len, len(summarized_content))
            else:
                # Summarizer failed → suggest background task
                logger.warning("Chat: summarizer failed for %d char message", msg_len)
                bg_suggestion = (
                    f"Zpráva je velmi dlouhá ({msg_len} znaků) a nepodařilo se ji analyzovat v reálném čase. "
                    f"Vytvořím background task pro podrobné zpracování — bude to důkladnější.\n\n"
                    f"Originál zprávy je uložen v KB. Background task si ji může přečíst celou."
                )
                # Save to DB BEFORE streaming so messages survive window switch
                await save_assistant_message(
                    request.session_id, bg_suggestion,
                    {"summarizer_failed": "true", "original_length": str(msg_len)},
                )

                async for event in stream_text(bg_suggestion):
                    yield event

                yield ChatStreamEvent(type="done", metadata={
                    "summarizer_failed": True, "suggest_background": True, "original_length": msg_len,
                })
                return

        # 6. Try decomposition for long messages
        if msg_len > settings.decompose_threshold:
            async for event in _try_decompose(
                request, context, runtime_ctx, selected_tools, system_prompt_text,
                messages, msg_len, disconnect_event, is_summarized, is_long_message,
            ):
                if event:
                    yield event
                else:
                    # None sentinel = decompose returned, skip to agentic loop
                    break
            else:
                # Decompose handled everything (returned via yield)
                return

        # 7. DIRECT fast path (router) or greeting fast path (legacy)
        if routing and routing.category == ChatCategory.DIRECT:
            logger.info("Chat: DIRECT fast-path via router")
            try:
                direct_response = await call_llm(messages=messages, tier=ModelTier.LOCAL_COMPACT)
                direct_text = direct_response.choices[0].message.content or ""
                if direct_text.strip():
                    await save_assistant_message(
                        request.session_id, direct_text,
                        {"direct_answer": "true", "router_category": "direct"},
                    )
                    async for event in stream_text(direct_text):
                        yield event
                    yield ChatStreamEvent(type="done", metadata={
                        "direct_answer": True, "iterations": 0,
                        "router_category": "direct",
                    })
                    return
            except Exception as e:
                logger.warning("Chat: DIRECT fast-path failed (%s), falling through", e)

        async for event in _try_greeting_fast_path(request, messages, intent_categories, msg_len):
            yield event
            if event.type == "done":
                return

        # 8. Multi-intent decomposition (E9-S3)
        # For medium-length messages that aren't already decomposed by topic
        if msg_len < settings.decompose_threshold and msg_len > 50:
            intents = await decompose_intents(request.message)
            if intents and len(intents) > 1:
                focus_msg = build_intent_focus_message(intents)
                if focus_msg:
                    messages.append({"role": "system", "content": focus_msg})
                    logger.info("Chat: multi-intent detected (%d intents), injected focus message", len(intents))

        # 9. Main agentic loop
        async for event in run_agentic_loop(
            request=request,
            messages=messages,
            selected_tools=selected_tools,
            runtime_ctx=runtime_ctx,
            disconnect_event=disconnect_event,
            is_summarized=is_summarized,
            msg_len=msg_len,
            max_iterations_override=routing.max_iterations if routing else None,
            use_case_override="chat_cloud" if routing and routing.use_cloud else None,
        ):
            yield event

    except Exception as e:
        logger.exception("Chat handler error: %s", e)
        import traceback
        tb = traceback.format_exception(type(e), e, e.__traceback__)
        yield ChatStreamEvent(
            type="error",
            content=str(e),
            metadata={
                "error": str(e),
                "errorType": type(e).__name__,
                "traceback": "".join(tb[-3:]),  # Last 3 frames
            },
        )

    finally:
        # Record chat exchange in master map
        try:
            from app.graph_agent.persistence import task_graph_store
            from app.graph_agent.graph import add_chat_vertex
            master = task_graph_store.get_master_graph_cached()
            if master:
                add_chat_vertex(
                    master,
                    message=request.message[:200],
                    response="(streamed)",
                    response_summary=request.message[:80],
                    client_id=request.active_client_id or "",
                    project_id=request.active_project_id,
                )
                task_graph_store.mark_dirty(master.task_id)
        except Exception as e:
            logger.debug("Failed to record chat exchange in master map: %s", e)

        # Clean up session auto-approvals
        try:
            from app.chat.handler_agentic import _session_auto_approvals
            _session_auto_approvals.pop(request.session_id, None)
        except Exception:
            pass
        try:
            from app.tools.kotlin_client import kotlin_client
            await kotlin_client.register_foreground_end()
        except Exception as e:
            logger.warning("Failed to register foreground end: %s", e)


async def _try_decompose(
    request, context, runtime_ctx, selected_tools, system_prompt_text,
    messages, msg_len, disconnect_event, is_summarized, is_long_message,
) -> AsyncIterator[ChatStreamEvent | None]:
    """Try to decompose a long message into sub-topics.

    Yields ChatStreamEvent if decomposition succeeds (and handles everything).
    Yields None sentinel if decomposition was skipped (fall through to agentic loop).
    """
    subtopics = await maybe_decompose(request.message)

    if subtopics and len(subtopics) > 1:
        logger.info("Chat: decomposing %d chars into %d sub-topics: %s",
                     msg_len, len(subtopics), [t.title for t in subtopics])
        yield ChatStreamEvent(type="thinking", content=f"Rozděluji zprávu na {len(subtopics)} témat...")

        all_results = []
        all_used_tools = []
        all_created_tasks = []
        all_responded_tasks = []

        for i, topic in enumerate(subtopics, 1):
            if disconnect_event and disconnect_event.is_set():
                logger.info("Chat: stopped during decompose after %d/%d topics", i - 1, len(subtopics))
                partial_parts = [f"## {r.topic.title}\n{r.text}" for r in all_results]
                if partial_parts:
                    partial_text = "\n\n---\n\n".join(partial_parts)
                    await save_assistant_message(
                        request.session_id, partial_text,
                        {"interrupted": "true", "decomposed": str(len(subtopics))},
                        compress=False,
                    )
                yield ChatStreamEvent(type="done", metadata={"interrupted": True})
                return

            yield ChatStreamEvent(type="thinking", content=f"Zpracovávám téma {i}/{len(subtopics)}: {topic.title}")

            result = await process_sub_topic(
                topic=topic, topic_index=i, total_topics=len(subtopics),
                request=request, context=context, runtime_ctx=runtime_ctx,
                selected_tools=selected_tools, system_prompt=system_prompt_text,
                client_id=request.active_client_id,
                project_id=request.active_project_id,
            )
            all_results.append(result)
            all_used_tools.extend(result.used_tools)
            all_created_tasks.extend(result.created_tasks)
            all_responded_tasks.extend(result.responded_tasks)

        yield ChatStreamEvent(type="thinking", content="Sestavuji odpověď...")
        final_text = await combine_results(all_results, request.message[:200])

        # Fact-check (parallel with topic update)
        fc_result = await run_fact_check(final_text, request.active_client_id, request.active_project_id)

        # Topic tracking (decompose path uses subtopic titles as topics — no LLM needed)
        decompose_topics = [{"label": t.title, "type": t.topic_type} for t in subtopics]
        await update_conversation_topics(request.session_id, decompose_topics)

        # Save to DB BEFORE streaming so messages survive window switch
        await save_assistant_message(
            request.session_id, final_text,
            {
                "decomposed": str(len(subtopics)),
                "topics": ",".join(t.title for t in subtopics),
                **({"used_tools": ",".join(all_used_tools)} if all_used_tools else {}),
                **({"created_tasks": ",".join(str(t.get("title", "")) for t in all_created_tasks)} if all_created_tasks else {}),
                **({"responded_tasks": ",".join(all_responded_tasks)} if all_responded_tasks else {}),
                **fact_check_metadata(fc_result),
            },
        )

        async for event in stream_text(final_text):
            yield event

        yield ChatStreamEvent(type="done", metadata={
            "decomposed": True, "topic_count": len(subtopics),
            "topics": [t.title for t in subtopics],
            "used_tools": all_used_tools, "created_tasks": all_created_tasks,
            "responded_tasks": all_responded_tasks,
            **confidence_badge(fc_result),
        })
        return

    # Decompose skipped or single-topic → add focus hints and fall through
    if msg_len > settings.decompose_threshold:
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
        if is_long_message:
            focus_hint += (
                " Pokud zpráva obsahuje příliš mnoho úkolů na zpracování v chatu, "
                "navrhni uživateli vytvoření background task pomocí create_background_task."
            )
        messages.append({"role": "system", "content": focus_hint})

    yield None  # Sentinel: fall through to agentic loop


async def _try_greeting_fast_path(
    request: ChatRequest,
    messages: list[dict],
    intent_categories: set,
    msg_len: int,
) -> AsyncIterator[ChatStreamEvent]:
    """Try direct answer for simple greeting messages.

    Only fires for short greetings with CORE-only intent.
    Yields events only if greeting was handled; yields nothing if not applicable.
    """
    if not (
        msg_len < 200
        and intent_categories == {ToolCategory.CORE}
        and not request.context_task_id
        and _GREETING_RE.match(request.message)
    ):
        return

    logger.info("Chat: greeting message (%d chars, CORE-only) — trying direct answer", msg_len)
    try:
        direct_response = await call_llm(messages=messages, tier=ModelTier.LOCAL_COMPACT)
        direct_text = direct_response.choices[0].message.content or ""
        direct_lower = direct_text.lower()
        has_tool_markers = any(m in direct_lower for m in _NEEDS_TOOLS_MARKERS)
        has_fake_tools = any(m in direct_lower for m in _FAKE_TOOL_MARKERS)

        if direct_text.strip() and not has_tool_markers and not has_fake_tools:
            logger.info("Chat: direct answer accepted (%d chars)", len(direct_text))
            # EPIC 14-S1 + EPIC 9-S1: Fact-check + topic detection in parallel
            fc_result, greeting_topics = await asyncio.gather(
                run_fact_check(direct_text, request.active_client_id, request.active_project_id),
                detect_topics(request.message, direct_text),
            )
            await update_conversation_topics(request.session_id, greeting_topics)
            # Save to DB BEFORE streaming so messages survive window switch
            await save_assistant_message(
                request.session_id, direct_text,
                {"direct_answer": "true", **fact_check_metadata(fc_result), **topic_metadata(greeting_topics)},
            )
            async for event in stream_text(direct_text):
                yield event
            yield ChatStreamEvent(type="done", metadata={
                "direct_answer": True, "iterations": 0, **confidence_badge(fc_result),
                **topic_metadata(greeting_topics),
            })
        else:
            logger.info("Chat: direct answer rejected (tool_markers=%s, fake_tools=%s), falling through",
                        has_tool_markers, has_fake_tools)
    except Exception as e:
        logger.warning("Chat: direct answer attempt failed (%s), falling through", e)
