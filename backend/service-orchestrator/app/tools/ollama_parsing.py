"""Shared Ollama tool-call parsing for all handlers.

Ollama models sometimes return tool calls as JSON in the message content
instead of using the standard tool_calls field. This module provides a
single implementation used by both foreground chat and background handlers.
"""

from __future__ import annotations

import json
import logging
import re
import uuid

logger = logging.getLogger(__name__)


class OllamaToolCall:
    """Lightweight tool call object parsed from Ollama JSON content."""

    def __init__(self, tc_dict: dict):
        if not isinstance(tc_dict, dict):
            raise ValueError("tool call must be a dict")
        self.id = tc_dict.get("id", f"call_{uuid.uuid4().hex[:8]}")
        self.type = tc_dict.get("type", "function")

        func = tc_dict.get("function")
        if not isinstance(func, dict) or "name" not in func:
            raise ValueError("invalid function in tool call")

        class _Function:
            def __init__(self, f_dict):
                self.name = f_dict["name"]
                args = f_dict.get("arguments", {})
                self.arguments = json.dumps(args) if isinstance(args, dict) else str(args)

        self.function = _Function(func)


def extract_tool_calls(message) -> tuple[list, str | None]:
    """Extract tool calls from LLM response, including Ollama JSON workaround.

    Returns (tool_calls, remaining_text).

    Handles:
    1. Standard litellm tool_calls field
    2. Ollama JSON-in-content {"tool_calls": [...]}
    3. JSON embedded in markdown ```json blocks
    4. Pure text (no tools)

    Used by: chat handler, background handler, delegation agents.
    """
    tool_calls = getattr(message, "tool_calls", None)
    if tool_calls:
        return tool_calls, message.content

    if not message.content:
        return [], None

    content = message.content.strip()

    # Pure JSON {"tool_calls": [...]}
    try:
        parsed = json.loads(content)
        if isinstance(parsed, dict) and "tool_calls" in parsed:
            raw_calls = parsed["tool_calls"]
            if not isinstance(raw_calls, list):
                return [], content
            calls = []
            for tc in raw_calls:
                try:
                    calls.append(OllamaToolCall(tc))
                except (ValueError, KeyError, TypeError):
                    continue
            if calls:
                logger.info("Ollama workaround: extracted %d tool calls from JSON", len(calls))
                message.content = None
                return calls, None
    except (json.JSONDecodeError, KeyError, TypeError):
        pass

    # JSON in markdown ```json block
    md_match = re.search(r'```(?:json)?\s*(\{.*?"tool_calls".*?\})\s*```', content, re.DOTALL)
    if md_match:
        try:
            parsed = json.loads(md_match.group(1))
            raw_calls = parsed.get("tool_calls", [])
            calls = []
            for tc in raw_calls:
                try:
                    calls.append(OllamaToolCall(tc))
                except (ValueError, KeyError, TypeError):
                    continue
            if calls:
                remaining = content[:md_match.start()] + content[md_match.end():]
                remaining = remaining.strip() or None
                logger.info("Ollama workaround: extracted %d tool calls from markdown block", len(calls))
                return calls, remaining
        except (json.JSONDecodeError, KeyError, TypeError):
            pass

    # XML-style <tool_call> tags (FREE OpenRouter models generate these in two formats):
    # Format A: <tool_call>\n<function=name>\n<parameter=key>value</parameter>\n</function>\n</tool_call>
    # Format B: <tool_call>\n{"function":{"name":"...","parameters":{...}}}\n</tool_call>
    xml_matches = list(re.finditer(r'<tool_call>(.*?)</tool_call>', content, re.DOTALL))
    if xml_matches:
        calls = []
        for xml_match in xml_matches:
            xml_content = xml_match.group(1).strip()
            try:
                # Format A: <function=name> tags
                func_match = re.search(r'<function=(\w+)>', xml_content)
                if func_match:
                    func_name = func_match.group(1)
                    params = {}
                    for param_match in re.finditer(r'<parameter=(\w+)>\s*(.*?)\s*</parameter>', xml_content, re.DOTALL):
                        params[param_match.group(1)] = param_match.group(2).strip()
                    calls.append(OllamaToolCall({"function": {"name": func_name, "arguments": params}}))
                    logger.info("XML workaround (format A): extracted tool call '%s'", func_name)
                    continue

                # Format B: JSON inside <tool_call> tags
                parsed = json.loads(xml_content)
                func_data = parsed.get("function") or parsed
                func_name = func_data.get("name")
                if func_name:
                    params = func_data.get("arguments") or func_data.get("parameters", {})
                    if isinstance(params, dict) and "properties" in params:
                        params = {}  # Model dumped schema, not actual args — treat as empty call
                    calls.append(OllamaToolCall({"function": {"name": func_name, "arguments": params}}))
                    logger.info("XML workaround (format B): extracted tool call '%s'", func_name)
                    continue
            except Exception:
                pass

        # Strip ALL <tool_call> blocks from content (never show raw XML to user)
        cleaned = re.sub(r'<tool_call>.*?</tool_call>', '', content, flags=re.DOTALL).strip()

        if calls:
            return calls, cleaned or None
        # No parseable tool calls but XML detected → return cleaned text (or None if empty)
        if cleaned:
            return [], cleaned
        return [], None  # Entire content was unparseable XML — return None, not raw XML

    # Plain JSON action format: {"action": "kb_search", "parameters": {"query": "..."}}
    # Some FREE models output tool calls as plain JSON in content text
    action_match = re.search(r'\{\s*"action"\s*:\s*"(\w+)".*?"parameters"\s*:\s*(\{[^}]*\})', content, re.DOTALL)
    if action_match:
        try:
            func_name = action_match.group(1)
            params = json.loads(action_match.group(2))
            calls = [OllamaToolCall({"function": {"name": func_name, "arguments": params}})]
            remaining = content[:action_match.start()] + content[action_match.end():]
            remaining = remaining.strip() or None
            logger.info("Action JSON workaround: extracted tool call '%s'", func_name)
            return calls, remaining
        except (json.JSONDecodeError, KeyError):
            pass

    return [], content
