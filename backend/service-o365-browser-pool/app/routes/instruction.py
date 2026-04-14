"""Instruction API — JERVIS sends commands, pod executes via VLM+LLM."""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.ai_navigator import see_screen, decide_action, execute_action, save_error_screenshot
from app.browser_manager import BrowserManager
from app.pod_state import PodState, PodStateManager

logger = logging.getLogger("o365-browser-pool.instruction")

# Max steps per instruction
MAX_STEPS = 15
STEP_DELAY = 3


def create_instruction_router(
    browser_manager: BrowserManager,
) -> APIRouter:
    router = APIRouter()

    # Registry of state managers (populated by main.py)
    _state_managers: dict[str, PodStateManager] = {}

    def register_state_manager(client_id: str, sm: PodStateManager) -> None:
        _state_managers[client_id] = sm

    router.register_state_manager = register_state_manager  # type: ignore

    @router.post("/instruction/{client_id}")
    async def execute_instruction(client_id: str, request: Request):
        """Execute a text instruction from JERVIS.

        Body: {"instruction": "Change password to XYZ...", "timeout_seconds": 60}

        The pod uses VLM to see the screen, LLM to decide actions based on
        the instruction, and Playwright to execute them.
        """
        body = await request.json()
        instruction = body.get("instruction", "")
        if not instruction:
            return JSONResponse(status_code=400, content={"error": "instruction required"})

        state_manager = _state_managers.get(client_id)
        if not state_manager:
            return JSONResponse(status_code=404, content={"error": f"No state manager for {client_id}"})

        context = browser_manager.get_context(client_id)
        if not context or not context.pages:
            return JSONResponse(status_code=503, content={"error": "No browser context"})

        page = context.pages[0]

        # Transition to EXECUTING_INSTRUCTION
        ok = await state_manager.transition(PodState.EXECUTING_INSTRUCTION, reason=f"Instruction: {instruction[:100]}")
        if not ok:
            return JSONResponse(
                status_code=409,
                content={"error": f"Cannot execute instruction in state {state_manager.state}"},
            )

        # Execute instruction step by step
        result = await _execute_steps(page, state_manager, instruction)
        return JSONResponse(content=result)

    return router


async def _execute_steps(
    page,
    state_manager: PodStateManager,
    instruction: str,
) -> dict:
    """Execute instruction via VLM+LLM loop."""
    context = f"Execute this instruction: {instruction}"
    steps_log = []

    for step in range(MAX_STEPS):
        screen_info = await see_screen(page)
        action = await decide_action(screen_info, context=context)
        action_type = action.get("action")
        reason = action.get("reason", "")

        steps_log.append({
            "step": step,
            "screen_type": screen_info.get("screen_type"),
            "action": action_type,
            "target": action.get("target"),
            "reason": reason,
        })

        logger.info(
            "INSTRUCTION: step %d, screen=%s, action=%s, reason=%s",
            step, screen_info.get("screen_type"), action_type, reason,
        )

        if action_type == "done":
            await state_manager.transition(PodState.ACTIVE, reason="Instruction completed")
            return {"status": "completed", "steps": steps_log}

        if action_type == "error":
            screenshot_path = await save_error_screenshot(page, state_manager.client_id, reason)
            await state_manager.transition(
                PodState.ERROR,
                reason=f"Instruction failed: {reason}",
                screenshot_path=screenshot_path,
                vlm_description=screen_info.get("details", ""),
            )
            return {"status": "error", "reason": reason, "steps": steps_log}

        if action_type == "wait":
            await asyncio.sleep(STEP_DELAY)
            continue

        success = await execute_action(page, action)
        if not success:
            logger.warning("INSTRUCTION: action failed at step %d", step)

        await asyncio.sleep(STEP_DELAY)

    # Max steps reached
    await state_manager.transition(PodState.ERROR, reason="Instruction did not complete")
    return {"status": "timeout", "reason": "Max steps reached", "steps": steps_log}
