"""Respond node — ADVICE + SINGLE_TASK/respond answers.

Generates answers using LLM + tools (web search, KB search) in an agentic loop.
Max 8 iterations to prevent infinite loops.
Streams final LLM answer token-by-token to UI via kotlin_client.

Hardening (W-11..W-22):
- W-14: Context overflow guard — validates messages fit selected tier
- W-17: JSON workaround validation — validates Ollama tool_call structure
- W-22: Tool execution timeout — asyncio.wait_for on each tool call
- W-13: Quality escalation — short-response detection with retry
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid

from langgraph.types import interrupt

from app.models import CodingTask
from app.graph.nodes._helpers import llm_with_cloud_fallback, is_error_message
from app.llm.provider import TIER_CONFIG, llm_provider
from app.tools.definitions import ALL_RESPOND_TOOLS_FULL
from app.tools.executor import execute_tool, AskUserInterrupt, _TOOL_EXECUTION_TIMEOUT_S
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)

_MAX_TOOL_ITERATIONS = 8  # Increased from 5 to give agent more room for complex queries
_MIN_ANSWER_CHARS = 40    # W-13: Minimum acceptable answer length (short answer retry)
_MAX_SHORT_RETRIES = 1    # W-13: Max retries for short answers


async def respond(state: dict) -> dict:
    """Answer analytical/advisory queries directly using LLM + KB context.

    Used for:
    - ADVICE tasks (meeting summaries, knowledge queries, planning advice)
    - SINGLE_TASK with action=respond (analysis, recommendations)
    """
    # BACKGROUND tasks: no user to read the response — skip LLM entirely
    if state.get("processing_mode") == "BACKGROUND":
        logger.info("BACKGROUND task — skipping respond node (no audience)")
        return {"final_result": "Background task completed."}

    task = CodingTask(**state["task"])
    project_context = state.get("project_context", "")
    clarification = state.get("clarification_response")
    evidence = state.get("evidence_pack", {})

    # Build context
    context_parts: list[str] = []

    # Task identity — client/project names from top-level state
    client_name = state.get("client_name")
    project_name = state.get("project_name")
    identity_parts = []
    if client_name:
        identity_parts.append(f"Client: {client_name}")
    if project_name:
        identity_parts.append(f"Project: {project_name}")
    if identity_parts:
        context_parts.append("## Task Context\n" + "\n".join(identity_parts))

    # User context — auto-prefetched knowledge from previous conversations
    user_context = state.get("user_context")
    if user_context:
        context_parts.append(f"## User Context (learned from previous conversations)\n{user_context}")

    # Memory Agent context — affair-aware working memory
    memory_context = state.get("memory_context")
    if memory_context:
        context_parts.append(f"## Working Memory (affairs & context)\n{memory_context}")

    if project_context:
        context_parts.append(f"## Project Context (from Knowledge Base)\n{project_context[:4000]}")

    # Evidence pack KB results
    if evidence:
        kb_results = evidence.get("kb_results", [])
        for kr in kb_results:
            content = kr.get("content", "")
            if content:
                context_parts.append(f"## Knowledge Base\n{content[:4000]}")

        tracker_artifacts = evidence.get("tracker_artifacts", [])
        if tracker_artifacts:
            context_parts.append("## Referenced Items")
            for ta in tracker_artifacts:
                ref = ta.get("ref", "?")
                content = ta.get("content", "")[:1000]
                context_parts.append(f"### {ref}\n{content}")

    # Chat history — full conversation context (summaries + recent)
    chat_history = state.get("chat_history")
    if chat_history:
        history_parts = []
        # Summary blocks (older compressed history)
        for block in (chat_history.get("summary_blocks") or []):
            prefix = "[CHECKPOINT] " if block.get("is_checkpoint") else ""
            history_parts.append(
                f"{prefix}Messages {block['sequence_range']}: {block['summary']}"
            )
        # Recent messages (verbatim) — FILTER OUT ERROR MESSAGES
        for msg in (chat_history.get("recent_messages") or []):
            content = msg.get("content", "")
            # Skip error messages (JSON error objects or error text)
            if is_error_message(content):
                logger.debug("Filtering out error message from chat history: %s", content[:100])
                continue
            label = {"user": "Uživatel", "assistant": "Jervis"}.get(msg["role"], msg["role"])
            history_parts.append(f"{label}: {content}")
        if history_parts:
            context_parts.append("## Conversation History\n" + "\n\n".join(history_parts))

    if clarification:
        context_parts.append(
            f"## User Clarification\n{json.dumps(clarification, default=str, indent=2)}"
        )

    env_data = state.get("environment")
    if env_data:
        context_parts.append(f"## Environment\n{json.dumps(env_data, default=str)[:500]}")

    context_block = "\n\n".join(context_parts) if context_parts else ""

    messages = [
        {
            "role": "system",
            "content": (
                "Jsi Jervis, AI asistent s přístupem k databázi znalostí projektu.\n\n"
                "POSTUP:\n"
                "1. Použij dostupné nástroje k načtení dat\n"
                "2. Analyzuj získané informace\n"
                "3. Odpověz konkrétně s fakty z nástrojů\n\n"
                "PRAVIDLA PRO ČTENÍ:\n"
                "• Vždy NEJDŘÍV načti data nástroji (get_kb_stats, kb_search, read_file, atd.)\n"
                "• NIKDY neodpovídej z obecných znalostí — pouze z dat získaných nástroji\n"
                "• NIKDY neříkej 'potřebuji přístup' — přístup už máš přes dostupné nástroje\n"
                "• Pokud KB neobsahuje data → použij workspace nástroje (list_files, read_file, grep_files)\n\n"
                "PRAVIDLA PRO UČENÍ (CRITICAL!):\n"
                "• Když ti uživatel řekne NOVOU informaci → VŽDY ji ulož pomocí store_knowledge\n"
                "• Vzory učení: 'X je Y', 'X znamená Y', 'zapamatuj si', 'pro příště', 'to je'\n"
                "• Příklady: 'BMS je Brokerage Management System', 'projekt používá Python 3.11'\n"
                "• Pokud info vyžaduje BUDOUCÍ ověření → vytvoř scheduled task pomocí create_scheduled_task\n"
                "• Vzory pro task: 'až budeš mít kód', 'když najdeš X', 'ověř později'\n"
                "• Po uložení POTVRĎ co jsi se naučil a řekni že to bude dostupné pro budoucí dotazy\n"
                "• NIKDY neříkej jen 'ok' — vždy AKTIVNĚ ulož nové informace do KB\n\n"
                "PRAVIDLA PRO ROZLOŽENÍ ÚKOLŮ:\n"
                "• Pokud dotaz obsahuje VÍCE než jednu samostatnou část → řeš každou ZVLÁŠŤ\n"
                "• Příklad: 'X je Y, když najdeš Z tak...' = 2 úkoly (ulož X, vytvoř task pro Z)\n"
                "• Na konci SHRŇ všechny provedené akce pro každou část dotazu\n\n"
                "PRAVIDLA PRO USER CONTEXT:\n"
                "• V kontextu máš informace naučené od uživatele z předchozích konverzací\n"
                "• NEUKLÁDEJ znovu to, co už v user context vidíš — pouze NOVÉ informace\n"
                "• Využívej naučený kontext pro personalizované odpovědi\n\n"
                "PRAVIDLA PRO DOTAZY NA UŽIVATELE:\n"
                "• Pokud je dotaz nejednoznačný nebo ti chybí kritická informace → použij ask_user\n"
                "• Příklady: 'Který modul myslíte?', 'Preferujete řešení A nebo B?'\n"
                "• NIKDY nehádej — pokud si nejsi jistý, ZEPTEJ SE\n"
                "• Používej ask_user střídmě — jen když OPRAVDU nemůžeš pokračovat bez odpovědi\n"
                "• Po odpovědi uživatele pokračuj v řešení úkolu\n\n"
                "PRAVIDLA PRO FINÁLNÍ ODPOVĚĎ (CRITICAL!):\n"
                "• Po dokončení VŠECH požadovaných úkolů → IHNED dej finální odpověď (message.content)\n"
                "• NIKDY nevolej další nástroje jen pro ověření — výsledky nástrojů VĚŘÍ a jsou finální\n"
                "• NIKDY neopakuj již provedené akce (např. store_knowledge dvakrát)\n"
                "• Finální odpověď = shrnutí všech provedených akcí, BEZ dalších tool_calls\n"
                "• Pokud jsi uložil do KB a vytvořil task → hotovo, odpověz co jsi udělal\n\n"
                "ABSOLUTNÍ ZÁKAZY:\n"
                "• NIKDY neříkej že jsi provedl akci, pokud jsi ji NEPROVEDL přes nástroj s potvrzeným výsledkem\n"
                "• NIKDY netvrd že jsi změnil kód, přejmenoval branch, nasadil build — nemáš takové nástroje\n"
                "• Pokud nemáš nástroj pro požadovanou akci → ŘEKNI to uživateli a navrhni alternativu\n\n"
                "KOREKCE vs. PŘÍKAZY:\n"
                "• Korekce = uživatel opravuje tvé předchozí tvrzení: 'ne X, ale Y', 'X bude Y', 'X je špatně'\n"
                "  → Zapamatuj si opravu (store_knowledge), poděkuj, NEPROVÁDĚJ žádnou akci\n"
                "• Příkaz = uživatel chce aby jsi něco udělal: 'udělej X', 'přepni Y', 'vytvoř Z'\n"
                "  → Pokud máš odpovídající nástroj → proveď. Pokud ne → řekni to.\n\n"
                "TVÉ SCHOPNOSTI:\n"
                "• UMÍŠ: hledat v KB, hledat na webu, číst soubory, ukládat znalosti, vytvářet Jira issues,\n"
                "  zobrazit git branche/commity/status/diff (READ-ONLY)\n"
                "• NEUMÍŠ: měnit kód, přepínat/vytvářet/mazat branche, commitovat, deployovat,\n"
                "  odesílat emaily, spouštět CI/CD\n"
                "• Pro akce které neumíš → informuj uživatele a doporuč jak to udělat ručně\n\n"
                "Odpovídej česky. Buď konkrétní a faktický."
            ),
        },
        {
            "role": "user",
            "content": (
                f"{task.query}"
                f"\n\n{context_block}" if context_block else task.query
            ),
        },
    ]

    # Extract IDs for tool execution
    client_id = state.get("client_id", "")
    project_id = state.get("project_id")
    allow_cloud_prompt = state.get("allow_cloud_prompt", False)

    # W-19: Save user message to MongoDB (idempotent — Kotlin may have already saved)
    try:
        from app.chat.context import chat_context_assembler
        seq = await chat_context_assembler.get_next_sequence(task.id)
        await chat_context_assembler.save_message(
            task_id=task.id,
            role="USER",
            content=task.query,
            correlation_id=f"respond-{uuid.uuid4().hex[:8]}",
            sequence=seq,
        )
    except Exception as e:
        logger.warning("W-19: Failed to save user message: %s", e)

    # Tool list (includes memory tools)
    respond_tools = ALL_RESPOND_TOOLS_FULL

    # Agentic tool-use loop
    iteration = 0
    tool_call_history: list[tuple[str, str]] = []  # (name, args_json) for loop detection
    tool_loop_break = False
    while iteration < _MAX_TOOL_ITERATIONS:
        iteration += 1
        logger.info("Respond: iteration %d/%d", iteration, _MAX_TOOL_ITERATIONS)

        # Estimate context tokens (rough: 1 token ≈ 4 chars)
        # Need to account for: messages + tools + output space
        message_chars = sum(len(str(m)) for m in messages)
        message_tokens = message_chars // 4

        # Tools add ~2500-3000 tokens depending on tool set
        tools_tokens = 3000  # includes memory tools

        # Reserve space for output
        output_tokens = 4096

        # Total context needed
        estimated_tokens = message_tokens + tools_tokens + output_tokens
        logger.debug("Respond: messages=%d tokens, tools=%d tokens, output=%d tokens, total=%d tokens",
                     message_tokens, tools_tokens, output_tokens, estimated_tokens)

        # Log full messages array structure
        for i, msg in enumerate(messages):
            role = msg.get("role", "?")
            content_preview = str(msg.get("content", ""))[:200]
            has_tool_calls = "tool_calls" in msg
            has_tool_call_id = "tool_call_id" in msg
            logger.debug(
                "Respond: messages[%d] role=%s, content_len=%d, preview=%s, has_tool_calls=%s, has_tool_call_id=%s",
                i, role, len(str(msg.get("content", ""))), content_preview, has_tool_calls, has_tool_call_id
            )

        response = await llm_with_cloud_fallback(
            state={**state, "allow_cloud_prompt": allow_cloud_prompt},
            messages=messages,
            task_type="conversational",
            context_tokens=estimated_tokens,  # Dynamic tier selection based on conversation length
            max_tokens=4096,
            tools=respond_tools,  # Enable all tools (KB, web, git, filesystem + memory when enabled)
        )

        choice = response.choices[0]
        message = choice.message

        # DEBUG: Log full response details
        logger.info(
            "Respond: LLM response - finish_reason=%s, has_content=%s, content_len=%d, has_tool_calls=%s",
            choice.finish_reason,
            bool(message.content),
            len(message.content or ""),
            bool(getattr(message, "tool_calls", None)),
        )
        if message.content:
            logger.info("Respond: message.content = %s", message.content[:500])

        # Check for tool calls (OpenAI format)
        tool_calls = getattr(message, "tool_calls", None)

        # WORKAROUND: Ollama qwen3-coder-tool:30b doesn't support native tool_calls
        # It outputs JSON in content instead: {"tool_calls": [...]}
        # Parse and convert to proper format
        # W-17: Added validation for tool_call structure
        if not tool_calls and message.content:
            try:
                content_json = json.loads(message.content.strip())
                if isinstance(content_json, dict) and "tool_calls" in content_json:
                    logger.info("Respond: parsing tool_calls from JSON content (Ollama workaround)")
                    raw_calls = content_json["tool_calls"]
                    if not isinstance(raw_calls, list):
                        raise ValueError(f"tool_calls is not a list: {type(raw_calls)}")

                    # Convert JSON tool calls to OpenAI-style objects (with validation)
                    class ToolCall:
                        def __init__(self, tc_dict):
                            if not isinstance(tc_dict, dict):
                                raise ValueError(f"tool_call entry is not a dict: {type(tc_dict)}")
                            self.id = tc_dict.get("id", f"call_{uuid.uuid4().hex[:8]}")
                            self.type = tc_dict.get("type", "function")
                            func = tc_dict.get("function")
                            if not isinstance(func, dict) or "name" not in func:
                                raise ValueError(f"Invalid function in tool_call: {func}")

                            class Function:
                                def __init__(self, f_dict):
                                    self.name = f_dict["name"]
                                    args = f_dict.get("arguments", {})
                                    self.arguments = json.dumps(args) if isinstance(args, dict) else str(args)

                            self.function = Function(func)

                    validated_calls = []
                    for tc in raw_calls:
                        try:
                            validated_calls.append(ToolCall(tc))
                        except (ValueError, KeyError, TypeError) as ve:
                            logger.warning("Respond: skipping invalid tool_call: %s — %s", tc, ve)

                    if validated_calls:
                        tool_calls = validated_calls
                        message.content = None  # Clear content since we extracted tool calls
                        logger.info("Respond: extracted %d valid tool calls from JSON", len(tool_calls))
                    else:
                        logger.warning("Respond: all tool_calls from JSON were invalid")
            except (json.JSONDecodeError, KeyError, TypeError, ValueError) as e:
                logger.debug("Respond: content is not JSON tool_calls format: %s", e)

        # Check if we have tool calls (including parsed from JSON)
        # Only stop if there are truly no tool calls (ignore finish_reason when tools were parsed)
        if not tool_calls:
            # No more tool calls → final answer
            answer = message.content or ""
            logger.info("Respond: final answer after %d iterations (%d chars)", iteration, len(answer))

            # W-13: Quality escalation — retry if answer is suspiciously short
            if answer and len(answer.strip()) < _MIN_ANSWER_CHARS and iteration <= _MAX_TOOL_ITERATIONS - 1:
                short_retries = state.get("_short_answer_retries", 0)
                if short_retries < _MAX_SHORT_RETRIES:
                    state["_short_answer_retries"] = short_retries + 1
                    logger.warning(
                        "SHORT_ANSWER_RETRY | len=%d < min=%d | retry=%d/%d",
                        len(answer.strip()), _MIN_ANSWER_CHARS, short_retries + 1, _MAX_SHORT_RETRIES,
                    )
                    messages.append({
                        "role": "system",
                        "content": (
                            "Tvá odpověď je příliš krátká. Rozveď ji — uživatel očekává "
                            "podrobnou odpověď s konkrétními fakty z nástrojů."
                        ),
                    })
                    continue  # retry the loop

            # Stream tokens to UI for real-time display
            await _stream_answer_to_ui(state, answer)

            # W-19: Save assistant answer to MongoDB
            await _save_assistant_message(state, answer)

            return {"final_result": answer}

        # Execute tool calls
        logger.info("Respond: executing %d tool calls", len(tool_calls))
        messages.append(message.model_dump())  # Add assistant message with tool_calls

        for tool_call in tool_calls:
            tool_name = tool_call.function.name
            try:
                arguments = json.loads(tool_call.function.arguments)
            except json.JSONDecodeError:
                arguments = {}

            logger.info("Respond: calling tool %s with args: %s", tool_name, arguments)

            # Execute tool — may raise AskUserInterrupt for ask_user tool
            # W-22: Wrap in asyncio.wait_for to prevent hung tools
            try:
                result = await asyncio.wait_for(
                    execute_tool(
                        tool_name=tool_name,
                        arguments=arguments,
                        client_id=client_id,
                        project_id=project_id,
                        processing_mode=state.get("processing_mode", "FOREGROUND"),
                    ),
                    timeout=_TOOL_EXECUTION_TIMEOUT_S,
                )
            except asyncio.TimeoutError:
                logger.warning(
                    "TOOL_TIMEOUT | tool=%s | timeout=%ds",
                    tool_name, _TOOL_EXECUTION_TIMEOUT_S,
                )
                result = f"Error: Tool '{tool_name}' timed out after {_TOOL_EXECUTION_TIMEOUT_S}s."
            except AskUserInterrupt as e:
                # Agent needs user input — interrupt graph execution
                logger.info("ASK_USER: tool requested user input: %s", e.question)

                # interrupt() pauses graph, saves checkpoint, returns when resumed
                user_response = interrupt({
                    "type": "clarification_request",
                    "action": "clarify",
                    "description": e.question,
                })

                # After resume: user_response is the resume_value from Kotlin
                # Format: {"approved": true, "reason": "user's answer text"}
                if isinstance(user_response, dict):
                    answer = user_response.get("reason", str(user_response))
                else:
                    answer = str(user_response)

                logger.info("ASK_USER: user responded (%d chars)", len(answer))
                result = f"User answered: {answer}"

            logger.info("Respond: tool %s returned %d chars", tool_name, len(result))
            logger.debug("Respond: tool %s full result: %s", tool_name, result)

            # Add tool result to messages
            tool_result_msg = {
                "role": "tool",
                "tool_call_id": tool_call.id,
                "name": tool_name,
                "content": result,
            }
            messages.append(tool_result_msg)
            logger.debug("Respond: appended tool result message: %s", tool_result_msg)

            # Tool loop detection: track (name, args) and detect repeats
            call_key = (tool_name, json.dumps(arguments, sort_keys=True))
            tool_call_history.append(call_key)
            repeat_count = tool_call_history.count(call_key)
            if repeat_count >= 2:
                logger.warning(
                    "Tool loop detected: %s called %dx with same args, breaking",
                    tool_name, repeat_count,
                )
                messages.append({
                    "role": "system",
                    "content": (
                        f"STOP: Voláš {tool_name} opakovaně se stejnými argumenty. "
                        f"Tento nástroj ti nedá jiný výsledek. "
                        f"Odpověz uživateli na základě informací které už máš."
                    ),
                })
                tool_loop_break = True
                break
        if tool_loop_break:
            break
        # Continue loop - will call LLM again with tool results

    # Force final answer — either max iterations or tool loop break
    if tool_loop_break:
        logger.warning("Respond: tool loop detected at iteration %d, forcing final answer", iteration)
    else:
        logger.warning("Respond: max iterations (%d) reached, forcing final answer", _MAX_TOOL_ITERATIONS)
    final_messages = messages + [{
        "role": "system",
        "content": "Provide your final answer now based on the information gathered. Do not call more tools."
    }]
    # Estimate: messages + no tools + output space
    final_tokens = (sum(len(str(m)) for m in final_messages) // 4) + 4096

    # W-12: Try real-time streaming first for forced final answer
    answer = await _stream_answer_realtime(state, final_messages, final_tokens)

    if not answer:
        # Fallback to batch generation + fake streaming
        final_response = await llm_with_cloud_fallback(
            state={**state, "allow_cloud_prompt": allow_cloud_prompt},
            messages=final_messages,
            task_type="conversational",
            context_tokens=final_tokens,
            max_tokens=4096,
        )
        answer = final_response.choices[0].message.content or ""
        await _stream_answer_to_ui(state, answer)

    # W-19: Save assistant answer to MongoDB
    await _save_assistant_message(state, answer)

    return {"final_result": answer}


# W-12: Real token streaming — streams LLM tokens as they arrive
_STREAM_CHUNK_SIZE = 12  # Fallback chunk size for fake streaming


async def _stream_answer_to_ui(state: dict, answer: str) -> None:
    """Stream an already-generated answer to UI token-by-token.

    W-12: This is the fallback for pre-generated answers (e.g. forced final answer).
    For normal responses, _stream_answer_realtime is preferred.

    Splits the answer into small chunks and emits each via kotlin_client.
    This provides ChatGPT-style progressive text display in the UI.
    """
    if not answer:
        return

    task = CodingTask(**state["task"])
    client_id = state.get("client_id", "")
    project_id = state.get("project_id")
    message_id = f"stream-{uuid.uuid4().hex[:12]}"

    # Split into chunks and emit
    offset = 0
    emitted = 0
    while offset < len(answer):
        chunk = answer[offset:offset + _STREAM_CHUNK_SIZE]
        offset += _STREAM_CHUNK_SIZE

        ok = await kotlin_client.emit_streaming_token(
            task_id=task.id,
            client_id=client_id,
            project_id=project_id,
            token=chunk,
            message_id=message_id,
        )
        if ok:
            emitted += 1

    # Emit final token to signal stream end
    await kotlin_client.emit_streaming_token(
        task_id=task.id,
        client_id=client_id,
        project_id=project_id,
        token="",
        message_id=message_id,
        is_final=True,
    )

    logger.info(
        "Streamed answer to UI: %d chunks, %d chars, message_id=%s",
        emitted, len(answer), message_id,
    )


async def _stream_answer_realtime(state: dict, messages: list[dict], context_tokens: int) -> str:
    """W-12: Stream LLM answer in real-time, emitting tokens as they arrive.

    Uses llm_provider.stream_completion() for actual token streaming.
    Returns the complete answer text after streaming completes.
    """
    from app.graph.nodes._helpers import priority_headers
    from app.llm.provider import _SEMAPHORE_LOCAL

    task = CodingTask(**state["task"])
    client_id = state.get("client_id", "")
    project_id = state.get("project_id")
    message_id = f"stream-{uuid.uuid4().hex[:12]}"

    escalation = llm_provider.escalation
    local_tier = escalation.select_local_tier(context_tokens)
    headers = priority_headers(state)

    try:
        async with _SEMAPHORE_LOCAL:
            stream = await llm_provider.stream_completion(
                messages=messages,
                tier=local_tier,
                max_tokens=4096,
                temperature=0.1,
                extra_headers=headers,
            )

        content_parts: list[str] = []
        emitted = 0

        async for chunk in stream:
            delta = chunk.choices[0].delta if chunk.choices else None
            if delta and delta.content:
                token = delta.content
                content_parts.append(token)

                ok = await kotlin_client.emit_streaming_token(
                    task_id=task.id,
                    client_id=client_id,
                    project_id=project_id,
                    token=token,
                    message_id=message_id,
                )
                if ok:
                    emitted += 1

        # Emit final token
        await kotlin_client.emit_streaming_token(
            task_id=task.id,
            client_id=client_id,
            project_id=project_id,
            token="",
            message_id=message_id,
            is_final=True,
        )

        answer = "".join(content_parts)
        logger.info(
            "REALTIME_STREAM | tokens=%d | chars=%d | message_id=%s",
            emitted, len(answer), message_id,
        )
        return answer

    except Exception as e:
        logger.warning("REALTIME_STREAM_FAILED | falling back to batch: %s", e)
        # Fallback: return empty to trigger batch generation
        return ""


async def _save_assistant_message(state: dict, answer: str) -> None:
    """W-19: Save assistant answer to MongoDB for chat history persistence."""
    if not answer:
        return
    try:
        from app.chat.context import chat_context_assembler
        task = CodingTask(**state["task"])
        seq = await chat_context_assembler.get_next_sequence(task.id)
        await chat_context_assembler.save_message(
            task_id=task.id,
            role="ASSISTANT",
            content=answer,
            correlation_id=f"respond-{uuid.uuid4().hex[:8]}",
            sequence=seq,
        )
    except Exception as e:
        logger.warning("W-19: Failed to save assistant message: %s", e)
