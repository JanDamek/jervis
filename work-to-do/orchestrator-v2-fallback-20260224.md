# Bug: orchestrateV2 fallback na legacy MUSÍ být odstraněn

**Severity**: MEDIUM
**Date**: 2026-02-24
**Status**: DONE

## Vyřešeno
- ~~stream_url MissingFieldException~~ — pole odstraněno z StreamStartResponseDto
- ~~SSE /stream endpoint~~ — smazán z Python orchestrátoru
- ~~Fallback pattern v dispatchBackgroundV6~~ — v2 busy → return false, v2 exception → throw
- ~~dispatchLegacy()~~ — smazáno z AgentOrchestratorService.kt
- ~~orchestrateStream()~~ — smazáno z PythonOrchestratorClient.kt
- ~~/orchestrate/stream endpoint~~ — smazáno z main.py
