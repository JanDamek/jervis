"""Per-client Claude session management (Fáze A pilot).

This package owns lifecycle and context assembly for long-lived per-client
Claude Companion sessions that replace the legacy LangGraph chat path when
a chat request carries an active_client_id (feature flag gated).

Modules:
- compact_store: MongoDB CRUD for `compact_snapshots` collection (narrative
                 memory that Claude writes on COMPACT_AND_EXIT)
- client_brief_builder: Builds `brief.md` for a client session (today's
                        agenda, KB prefetch, last compact)
- client_session_manager: Lazy get-or-create per-client session, TTL 30 min
                          idle watchdog, message dispatch + SSE-style stream
"""
