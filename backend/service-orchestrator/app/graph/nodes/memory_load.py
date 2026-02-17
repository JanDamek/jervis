"""Memory load node — initialize Memory Agent and detect context switches.

Runs between intake and evidence_pack.

Responsibilities:
1. Create or restore MemoryAgent from state
2. Load session (LQM hot path or KB cold start)
3. Detect context switch (CONTINUE/SWITCH/AD_HOC/NEW_AFFAIR)
4. Execute switch if needed
5. Compose affair-aware context for downstream nodes
"""

from __future__ import annotations

import logging

from app.memory.agent import MemoryAgent
from app.memory.models import ContextSwitchType

logger = logging.getLogger(__name__)


async def memory_load(state: dict) -> dict:
    """Load memory context and detect context switches.

    Returns:
        memory_agent: serialized MemoryAgent state dict
        memory_context: composed affair context string
        context_switch_type: detected switch type string
    """
    try:
        task = state.get("task", {})
        client_id = task.get("client_id", "")
        project_id = task.get("project_id")
        query = task.get("query", "")

        if not client_id:
            logger.warning("memory_load: no client_id, skipping")
            return {}

        # Restore or create MemoryAgent
        existing = state.get("memory_agent")
        if existing and isinstance(existing, dict):
            agent = MemoryAgent.from_state_dict(existing)
            logger.info("memory_load: restored MemoryAgent from state")
        else:
            agent = MemoryAgent(client_id=client_id, project_id=project_id, processing_mode=state.get("processing_mode", "FOREGROUND"))
            logger.info("memory_load: created new MemoryAgent")

        # Load session (LQM hot or KB cold start)
        chat_history = state.get("chat_history")
        await agent.load_session(state, chat_history)

        # Detect context switch
        switch_result = await agent.detect_context_switch(query, state)
        switch_type = switch_result.type.value

        logger.info(
            "memory_load: context switch=%s (confidence=%.2f, reasoning=%s)",
            switch_type,
            switch_result.confidence,
            switch_result.reasoning[:100] if switch_result.reasoning else "",
        )

        # Execute context switch if needed
        if switch_result.type in (
            ContextSwitchType.SWITCH,
            ContextSwitchType.NEW_AFFAIR,
        ):
            status = await agent.switch_context(switch_result, state)
            logger.info("memory_load: switch executed — %s", status)
        elif switch_result.type == ContextSwitchType.AD_HOC:
            # For ad-hoc: don't switch, but note the type for respond node
            logger.info("memory_load: ad-hoc query, keeping current context")

        # Compose affair-aware context
        memory_context = await agent.compose_context(max_tokens=8000)

        return {
            "memory_agent": agent.to_state_dict(),
            "memory_context": memory_context,
            "context_switch_type": switch_type,
        }

    except Exception as e:
        logger.warning("memory_load failed (non-blocking): %s", e)
        return {}
