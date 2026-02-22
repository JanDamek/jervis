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
from app.chat.models import ChatRequest, ChatStreamEvent
from app.chat.system_prompt import RuntimeContext, build_system_prompt
from app.chat.tools import CHAT_TOOLS
from app.llm.provider import llm_provider
from app.models import ModelTier
from app.tools.executor import execute_tool

logger = logging.getLogger(__name__)

# Max agentic iterations (tool calls) per message
MAX_ITERATIONS = 15

# Chunk size for fake token streaming (chars, ~10 tokens)
STREAM_CHUNK_SIZE = 40


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

        # 3. If responding to user_task, load task context
        task_context_msg = None
        if request.context_task_id:
            task_context_msg = await _load_task_context_message(request.context_task_id)

        # 4. Build LLM messages
        messages = _build_messages(
            system_prompt=build_system_prompt(
                active_client_id=request.active_client_id,
                active_project_id=request.active_project_id,
                runtime_context=runtime_ctx,
            ),
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
        effective_client_id = request.active_client_id
        effective_project_id = request.active_project_id

        for iteration in range(MAX_ITERATIONS):
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
                        sequence=request.message_sequence + 1,
                        metadata={"interrupted": "true"},
                    )
                yield ChatStreamEvent(type="done", metadata={"interrupted": True})
                return

            logger.info("Chat: iteration %d/%d", iteration + 1, MAX_ITERATIONS)

            # Estimate context tokens (1 token ≈ 4 chars)
            message_chars = sum(len(str(m)) for m in messages)
            message_tokens = message_chars // 4
            tools_tokens = sum(len(str(t)) for t in CHAT_TOOLS) // 4
            output_tokens = 4096
            estimated_tokens = message_tokens + tools_tokens + output_tokens

            tier = llm_provider.escalation.select_local_tier(estimated_tokens)
            logger.info("Chat: estimated_tokens=%d (msgs=%d + tools=%d + output=%d) → tier=%s",
                        estimated_tokens, message_tokens, tools_tokens, output_tokens, tier.value)

            response = await llm_provider.completion(
                messages=messages,
                tier=tier,
                tools=CHAT_TOOLS,
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
                    sequence=request.message_sequence + 1,
                    metadata={
                        **({"used_tools": ",".join(used_tools)} if used_tools else {}),
                        **({"created_tasks": ",".join(str(t.get("title", "")) for t in created_tasks)} if created_tasks else {}),
                        **({"responded_tasks": ",".join(responded_tasks)} if responded_tasks else {}),
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

            # Loop detection — same tool+args called 3+ times = stuck
            tool_sig = "|".join(
                f"{tc.function.name}:{tc.function.arguments}" for tc in tool_calls
            )
            if tool_sig == last_tool_sig:
                consecutive_same += 1
            else:
                consecutive_same = 1
                last_tool_sig = tool_sig

            if consecutive_same >= 2:
                logger.warning("Chat: loop detected (%dx same tool call), forcing response", consecutive_same)
                # Inject system message to break loop, re-run LLM without tools
                messages.append({
                    "role": "system",
                    "content": (
                        "STOP — voláš opakovaně stejný tool se stejnými argumenty. "
                        "Odpověz uživateli s tím co víš. Nevolej žádné další tools."
                    ),
                })
                break_response = await llm_provider.completion(
                    messages=messages,
                    tier=tier,
                    tools=None,  # No tools — force text response
                    max_tokens=4096,
                    temperature=0.1,
                    extra_headers={"X-Ollama-Priority": "0"},
                )
                final_text = break_response.choices[0].message.content or "Nemám dostatek informací pro odpověď."
                logger.info("Chat: loop-break response (%d chars)", len(final_text))
                for i in range(0, len(final_text), STREAM_CHUNK_SIZE):
                    yield ChatStreamEvent(type="token", content=final_text[i:i + STREAM_CHUNK_SIZE])
                    await asyncio.sleep(0.03)
                await chat_context_assembler.save_message(
                    conversation_id=request.session_id,
                    role="ASSISTANT", content=final_text,
                    correlation_id=str(ObjectId()),
                    sequence=request.message_sequence + 1,
                    metadata={"loop_break": "true", "used_tools": ",".join(used_tools)},
                )
                yield ChatStreamEvent(type="done", metadata={"loop_break": True, "iterations": iteration + 1})
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
                tool_name = tool_call.function.name
                try:
                    arguments = json.loads(tool_call.function.arguments)
                except json.JSONDecodeError:
                    arguments = {}

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

        # Max iterations reached — force text response without tools
        logger.warning("Chat: max iterations (%d) reached, forcing response", MAX_ITERATIONS)
        messages.append({
            "role": "system",
            "content": "Dosáhl jsi maximálního počtu iterací. Odpověz uživateli s tím co víš. Nevolej žádné tools.",
        })
        try:
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
                sequence=request.message_sequence + 1,
                metadata={"max_iterations": "true", "used_tools": ",".join(used_tools)},
            )
            yield ChatStreamEvent(type="done", metadata={"max_iterations": True, "iterations": MAX_ITERATIONS})
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
                    sequence=request.message_sequence + 1,
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

    return RuntimeContext(
        clients_projects=_clients_cache,
        pending_user_tasks=pending,
        unclassified_meetings_count=unclassified,
    )


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
            result = await kotlin_client.create_background_task(
                title=arguments["title"],
                description=arguments["description"],
                client_id=arguments.get("client_id", active_client_id or ""),
                project_id=arguments.get("project_id", active_project_id),
                priority=arguments.get("priority", "medium"),
            )
            return f"Background task created: {result}"

        elif tool_name == "dispatch_coding_agent":
            result = await kotlin_client.dispatch_coding_agent(
                task_description=arguments["task_description"],
                client_id=arguments["client_id"],
                project_id=arguments["project_id"],
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

    # Find client by name (case-insensitive substring match)
    matched_client = None
    for c in ctx.clients_projects:
        cname = (c.get("name") or "").lower()
        if cname == client_name_query or client_name_query in cname:
            matched_client = c
            break

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

    # Resolve project if requested
    if project_name_query:
        projects = matched_client.get("projects", [])
        matched_project = None
        for p in projects:
            pname = (p.get("name") or "").lower()
            if pname == project_name_query or project_name_query in pname:
                matched_project = p
                break

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
