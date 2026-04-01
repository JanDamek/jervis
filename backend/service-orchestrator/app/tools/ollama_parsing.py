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

    # XML-style <tool_call> tags (some FREE OpenRouter models generate these)
    # Format: <tool_call>\n<function=name>\n<parameter=key>value</parameter>\n</function>\n</tool_call>
    xml_match = re.search(r'<tool_call>(.*?)</tool_call>', content, re.DOTALL)
    if xml_match:
        try:
            xml_content = xml_match.group(1).strip()
            func_match = re.search(r'<function=(\w+)>', xml_content)
            if func_match:
                func_name = func_match.group(1)
                params = {}
                for param_match in re.finditer(r'<parameter=(\w+)>\s*(.*?)\s*</parameter>', xml_content, re.DOTALL):
                    params[param_match.group(1)] = param_match.group(2).strip()
                calls = [OllamaToolCall({
                    "function": {"name": func_name, "arguments": params},
                })]
                remaining = content[:xml_match.start()] + content[xml_match.end():]
                remaining = remaining.strip() or None
                logger.info("XML workaround: extracted tool call '%s' from <tool_call> tags", func_name)
                return calls, remaining
        except Exception:
            pass

        # XML tool_call detected but couldn't parse — strip it (don't show raw XML to user)
        cleaned = re.sub(r'<tool_call>.*?</tool_call>', '', content, flags=re.DOTALL).strip()
        if cleaned:
            return [], cleaned

    return [], content
