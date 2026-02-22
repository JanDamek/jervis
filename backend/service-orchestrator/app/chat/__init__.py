"""Chat module — foreground chat and context management.

Components:
- context.py:       ChatContextAssembler — token-budgeted context from MongoDB
- handler.py:       ChatHandler — agentic loop (LLM + tools) for foreground chat
- models.py:        ChatRequest, ChatStreamEvent — Pydantic models
- system_prompt.py: Jervis system prompt builder
- tools.py:         Chat-specific tool definitions
- router.py:        FastAPI router for internal context/compression endpoints
"""
