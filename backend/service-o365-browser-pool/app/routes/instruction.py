"""Instruction API — JERVIS pushes a text command, the PodAgent picks it up
as the highest-priority goal and executes it via observe → reason → act loop.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app import agent_registry
from app.browser_manager import BrowserManager

logger = logging.getLogger("o365-browser-pool.instruction")


def create_instruction_router(browser_manager: BrowserManager) -> APIRouter:
    router = APIRouter()

    @router.post("/instruction/{client_id}")
    async def execute_instruction(client_id: str, request: Request):
        body = await request.json()
        instruction = (body.get("instruction") or "").strip()
        if not instruction:
            return JSONResponse(status_code=400, content={"error": "instruction required"})

        agent = agent_registry.get(client_id)
        if agent is None:
            return JSONResponse(
                status_code=404,
                content={"error": f"No agent running for {client_id}"},
            )

        agent.push_instruction(instruction)
        return JSONResponse(content={"status": "queued", "client_id": client_id})

    return router
