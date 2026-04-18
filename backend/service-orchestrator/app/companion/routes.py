"""Companion routes — migrated to gRPC (OrchestratorCompanionService).

All six FastAPI endpoints (/companion/adhoc, /companion/adhoc/{task_id},
/companion/session, /companion/session/{id}/event,
/companion/session/{id}/stream, /companion/session/{id}/stop) now live on
`app/grpc_server.py` via OrchestratorCompanionService. This module retains
an empty APIRouter so `app.main.app.include_router(companion_router)`
compiles; delete when the FastAPI app retires.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/companion", tags=["companion"])
