"""Chat module — context management for foreground chat and background orchestrator.

Replaces Kotlin ChatHistoryService with Python-native implementation:
- Direct MongoDB access (motor async) — no round-trip through Kotlin
- Token-budgeted context assembly
- LLM-based compression of old message blocks
- Reusable for both ChatSession (foreground) and background orchestrator
"""
