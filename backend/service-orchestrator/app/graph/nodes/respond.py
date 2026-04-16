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

from app.chat.context import chat_context_assembler
from app.config import settings, estimate_tokens
from app.models import CodingTask
from app.graph.nodes._helpers import llm_with_cloud_fallback, is_error_message, detect_tool_loop
from app.llm.provider import TIER_CONFIG, llm_provider
from app.tools.definitions import ALL_RESPOND_TOOLS_FULL
from app.tools.executor import execute_tool, AskUserInterrupt, ApprovalRequiredInterrupt, _TOOL_EXECUTION_TIMEOUT_S
from app.tools.kotlin_client import kotlin_client
from app.tools.ollama_parsing import extract_tool_calls

logger = logging.getLogger(__name__)

_MAX_TOOL_ITERATIONS = settings.respond_max_iterations
_MIN_ANSWER_CHARS = settings.respond_min_answer_chars
_MAX_SHORT_RETRIES = settings.respond_max_short_retries


def _build_respond_context(state: dict) -> str:
    """Assemble context block from state for the respond node."""
    project_context = state.get("project_context", "")
    clarification = state.get("clarification_response")
    evidence = state.get("evidence_pack", {})
    parts: list[str] = []

    # Task identity
    client_name = state.get("client_name")
    project_name = state.get("project_name")
    identity_parts = []
    if client_name:
        identity_parts.append(f"Client: {client_name}")
    if project_name:
        identity_parts.append(f"Project: {project_name}")
    if identity_parts:
        parts.append("## Task Context\n" + "\n".join(identity_parts))

    # User context — auto-prefetched knowledge
    user_context = state.get("user_context")
    if user_context:
        parts.append(f"## User Context (learned from previous conversations)\n{user_context}")

    # Memory Agent context
    memory_context = state.get("memory_context")
    if memory_context:
        parts.append(f"## Working Memory (affairs & context)\n{memory_context}")

    if project_context:
        parts.append(f"## Project Context (from Knowledge Base)\n{project_context}")

    # Evidence pack KB results
    if evidence:
        for kr in evidence.get("kb_results", []):
            content = kr.get("content", "")
            if content:
                parts.append(f"## Knowledge Base\n{content}")
        tracker_artifacts = evidence.get("tracker_artifacts", [])
        if tracker_artifacts:
            parts.append("## Referenced Items")
            for ta in tracker_artifacts:
                ref = ta.get("ref", "?")
                content = ta.get("content", "")
                parts.append(f"### {ref}\n{content}")

    # Chat history
    chat_history = state.get("chat_history")
    if chat_history:
        history_parts = []
        for block in (chat_history.get("summary_blocks") or []):
            prefix = "[CHECKPOINT] " if block.get("is_checkpoint") else ""
            history_parts.append(f"{prefix}Messages {block['sequence_range']}: {block['summary']}")
        for msg in (chat_history.get("recent_messages") or []):
            content = msg.get("content", "")
            if is_error_message(content):
                logger.debug("Filtering out error message from chat history: %s", content[:100])
                continue
            label = {"user": "Uživatel", "assistant": "Jervis"}.get(msg["role"], msg["role"])
            history_parts.append(f"{label}: {content}")
        if history_parts:
            parts.append("## Conversation History\n" + "\n\n".join(history_parts))

    if clarification:
        parts.append(f"## User Clarification\n{json.dumps(clarification, default=str, indent=2)}")

    env_data = state.get("environment")
    if env_data:
        parts.append(f"## Environment\n{json.dumps(env_data, default=str)}")

    return "\n\n".join(parts)


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
    context_block = _build_respond_context(state)

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
                "• Po uložení POTVRĎ co jsi se naučil a řekni že to bude dostupné pro budoucí dotazy\n"
                "• NIKDY neříkej jen 'ok' — vždy AKTIVNĚ ulož nové informace do KB\n\n"
                "SCHEDULED TASKS — POUZE PRO TERMÍNY:\n"
                "• create_scheduled_task POUZE když existuje KONKRÉTNÍ TERMÍN nebo ČASOVÝ TRIGGER\n"
                "• Příklady SPRÁVNĚ: 'faktura splatná 15.4.', 'meeting za týden', 'zkontrolovat deploy zítra'\n"
                "• Příklady ŠPATNĚ: 'analyzovat kód' (udělej to teď), 'ověřit dokumentaci' (udělej to teď)\n"
                "• Běžná práce jde do normální fronty — NIKDY neodkládej práci kterou můžeš udělat TEĎ\n\n"
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
    await _save_chat_message(task.id, "USER", task.query)

    # Tool list (includes memory tools)
    respond_tools = ALL_RESPOND_TOOLS_FULL

    # Agentic tool-use loop
    iteration = 0
    tool_call_history: list[tuple[str, str]] = []  # (name, args_json) for loop detection
    state_updates: dict = {}  # Accumulated state changes from tools
    tool_loop_break = False
    while iteration < _MAX_TOOL_ITERATIONS:
        iteration += 1
        logger.info("Respond: iteration %d/%d", iteration, _MAX_TOOL_ITERATIONS)

        # Estimate context tokens
        message_tokens = sum(estimate_tokens(str(m)) for m in messages)
        tools_tokens = sum(estimate_tokens(str(t)) for t in respond_tools)
        estimated_tokens = message_tokens + tools_tokens + settings.default_output_tokens
        logger.debug("Respond: messages=%d tokens, tools=%d tokens, output=%d tokens, total=%d tokens",
                     message_tokens, tools_tokens, settings.default_output_tokens, estimated_tokens)

        response = await llm_with_cloud_fallback(
            state={**state, "allow_cloud_prompt": allow_cloud_prompt},
            messages=messages,
            task_type="conversational",
            context_tokens=estimated_tokens,
            max_tokens=settings.default_output_tokens,
            tools=respond_tools,
        )

        choice = response.choices[0]
        message = choice.message

        logger.info(
            "Respond: LLM response - finish_reason=%s, has_content=%s, content_len=%d, has_tool_calls=%s",
            getattr(choice, "finish_reason", None),
            bool(message.content),
            len(message.content or ""),
            bool(getattr(message, "tool_calls", None)),
        )
        if message.content:
            logger.info("Respond: message.content = %s", message.content[:500])

        # Extract tool calls (shared Ollama JSON workaround + native format)
        tool_calls, remaining_text = extract_tool_calls(message)

        if not tool_calls:
            # No more tool calls → final answer
            answer = remaining_text or message.content or ""
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

            return {"final_result": answer, **state_updates}

        # Execute tool calls
        logger.info("Respond: executing %d tool calls", len(tool_calls))
        messages.append(message.model_dump())  # Add assistant message with tool_calls

        for tool_call in tool_calls:
            tool_name = tool_call.function.name
            try:
                arguments = json.loads(tool_call.function.arguments)
            except json.JSONDecodeError:
                arguments = {}

            # Tool loop detection BEFORE execution — skip duplicate calls
            loop_reason, _loop_count = detect_tool_loop(tool_call_history, tool_name, arguments)
            if loop_reason:
                logger.warning("Respond: tool loop for %s — skipping execution", tool_name)
                messages.append({
                    "role": "tool",
                    "tool_call_id": tool_call.id,
                    "name": tool_name,
                    "content": f"ERROR: {loop_reason}",
                })
                tool_loop_break = True
                break

            logger.info("Respond: calling tool %s with args: %s", tool_name, arguments)

            # Execute tool — may raise AskUserInterrupt for ask_user tool
            # W-22: Wrap in asyncio.wait_for to prevent hung tools
            # NOTE: skip_approval=True — approval gate is handled at the chat
            # handler level (handler_agentic.py) which broadcasts approval via
            # NotificationRpcImpl remote channel. Graph-level respond runs inside
            # an already-approved task, so individual tool calls here must not
            # re-trigger the gate — otherwise the interrupt leaks as a traceback
            # (fail_vertex path in langgraph_runner).
            try:
                result = await asyncio.wait_for(
                    execute_tool(
                        tool_name=tool_name,
                        arguments=arguments,
                        client_id=client_id,
                        project_id=project_id,
                        processing_mode=state.get("processing_mode", "FOREGROUND"),
                        group_id=state.get("group_id"),
                        skip_approval=True,
                    ),
                    timeout=_TOOL_EXECUTION_TIMEOUT_S,
                )
            except asyncio.TimeoutError:
                logger.warning(
                    "TOOL_TIMEOUT | tool=%s | timeout=%ds",
                    tool_name, _TOOL_EXECUTION_TIMEOUT_S,
                )
                result = f"Error: Tool '{tool_name}' timed out after {_TOOL_EXECUTION_TIMEOUT_S}s."
            except ApprovalRequiredInterrupt as e:
                # Defensive: should not happen with skip_approval=True, but if
                # something in the execute path still raises, degrade gracefully
                # instead of failing the vertex with a stack trace.
                logger.warning(
                    "Respond: unexpected ApprovalRequiredInterrupt for tool %s (action=%s) — treating as deny",
                    tool_name, e.action,
                )
                result = f"Error: Tool '{tool_name}' requires approval that is not available in graph context."
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

            # State-modifying tools: update graph state directly
            if tool_name == "environment_keep_running":
                env_keep = arguments.get("enabled", True)
                state_updates["keep_environment_running"] = env_keep
                logger.info("ENV_KEEP_RUNNING set to %s by respond node", env_keep)

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
        "content": "Poskytni finální odpověď na základě shromážděných informací. Nevolej další nástroje."
    }]
    final_tokens = sum(estimate_tokens(str(m)) for m in final_messages) + settings.default_output_tokens

    # W-12: Try real-time streaming first for forced final answer
    answer = await _stream_answer_realtime(state, final_messages, final_tokens)

    if not answer:
        # Fallback to batch generation + fake streaming
        final_response = await llm_with_cloud_fallback(
            state={**state, "allow_cloud_prompt": allow_cloud_prompt},
            messages=final_messages,
            task_type="conversational",
            context_tokens=final_tokens,
            max_tokens=settings.default_output_tokens,
        )
        answer = final_response.choices[0].message.content or ""
        await _stream_answer_to_ui(state, answer)

    # W-19: Save assistant answer to MongoDB
    await _save_assistant_message(state, answer)

    return {"final_result": answer, **state_updates}


# W-12: Real token streaming — streams LLM tokens as they arrive
_STREAM_CHUNK_SIZE = settings.stream_chunk_size


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

    Posts directly to the router's `/api/chat` with streaming enabled and
    forwards each token to the UI via kotlin_client. Caller contract is the
    minimal one — capability + client_id — router picks the model.
    """
    import json as _json

    import httpx

    task = CodingTask(**state["task"])
    client_id = state.get("client_id", "") or ""
    project_id = state.get("project_id")
    capability = (task.capability or "chat").lower()
    message_id = f"stream-{uuid.uuid4().hex[:12]}"

    router_base = settings.ollama_url.rstrip("/").replace("/v1", "").replace("/api", "")
    url = f"{router_base}/api/chat"
    headers = {
        "Content-Type": "application/json",
        "X-Capability": capability,
    }
    if client_id:
        headers["X-Client-Id"] = client_id
    body = {
        "messages": messages,
        "stream": True,
        "options": {
            "temperature": 0.1,
            "num_predict": settings.default_output_tokens,
        },
    }

    content_parts: list[str] = []
    emitted = 0
    try:
        timeout = httpx.Timeout(connect=10, read=None, write=10, pool=30)
        async with httpx.AsyncClient(timeout=timeout) as client:
            async with client.stream("POST", url, json=body, headers=headers) as resp:
                resp.raise_for_status()
                async for line in resp.aiter_lines():
                    if not line.strip():
                        continue
                    try:
                        chunk = _json.loads(line)
                    except _json.JSONDecodeError:
                        continue
                    msg = chunk.get("message") or {}
                    token = msg.get("content") or ""
                    if token:
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
                    if chunk.get("done"):
                        break

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
        return ""


async def _save_chat_message(conversation_id: str, role: str, content: str) -> None:
    """W-19: Save a message to MongoDB for chat history persistence."""
    if not content:
        return
    try:
        seq = await chat_context_assembler.get_next_sequence(conversation_id)
        await chat_context_assembler.save_message(
            conversation_id=conversation_id,
            role=role,
            content=content,
            correlation_id=f"respond-{uuid.uuid4().hex[:8]}",
            sequence=seq,
        )
    except Exception as e:
        logger.warning("W-19: Failed to save %s message: %s", role, e)


async def _save_assistant_message(state: dict, answer: str) -> None:
    """W-19: Save assistant answer to MongoDB for chat history persistence."""
    task = CodingTask(**state["task"])
    await _save_chat_message(task.id, "ASSISTANT", answer)
