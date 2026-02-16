"""Memory flush node — persist memory state after response generation.

Runs between respond and finalize when use_memory_agent is enabled.
Feature-gated: returns empty dict when disabled.

Responsibilities:
1. Restore MemoryAgent from state
2. Append current query + response to active affair messages
3. Flush write buffer to KB
4. Serialize updated agent back to state
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from app.config import settings
from app.memory.agent import MemoryAgent
from app.memory.models import AffairMessage

logger = logging.getLogger(__name__)


async def memory_flush(state: dict) -> dict:
    """Persist memory state and flush write buffer.

    Returns:
        memory_agent: updated serialized MemoryAgent state dict
    """
    if not settings.use_memory_agent:
        return {}

    try:
        agent_data = state.get("memory_agent")
        if not agent_data or not isinstance(agent_data, dict):
            return {}

        agent = MemoryAgent.from_state_dict(agent_data)

        # Append current interaction to active affair messages
        if agent.session.active_affair:
            task = state.get("task", {})
            query = task.get("query", "")
            final_result = state.get("final_result", "")
            now = datetime.now(timezone.utc).isoformat()

            if query:
                agent.session.active_affair.messages.append(
                    AffairMessage(role="user", content=query[:2000], timestamp=now)
                )
            if final_result:
                agent.session.active_affair.messages.append(
                    AffairMessage(
                        role="assistant",
                        content=final_result[:2000],
                        timestamp=now,
                    )
                )

            # Trim messages to last 20 to prevent unbounded growth
            if len(agent.session.active_affair.messages) > 20:
                agent.session.active_affair.messages = (
                    agent.session.active_affair.messages[-20:]
                )

        # Flush write buffer and update affairs in KB
        await agent.flush_session()

        logger.info("memory_flush: session flushed successfully")

        return {
            "memory_agent": agent.to_state_dict(),
        }

    except Exception as e:
        logger.warning("memory_flush failed (non-blocking): %s", e)
        return {}
