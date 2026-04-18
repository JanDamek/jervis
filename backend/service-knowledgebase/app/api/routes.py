"""Knowledge Base API routes — now empty.

All knowledge-base RPCs live on the gRPC server (:5501, see
`app/grpc_server.py`). This module retains empty APIRouter instances so
`main.py` keeps its include_router + KB_MODE split intact; they provide
no routes but still satisfy the Depends() wiring. The `service` symbol
remains here because the gRPC servicers dial
`app.api.routes.service` for the shared KnowledgeService singleton.

When `main.py` eventually drops FastAPI entirely, the remaining
semaphore-based concurrency limiters move into the gRPC interceptors
and this file can be deleted outright.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from app.services.knowledge_service import KnowledgeService

logger = logging.getLogger(__name__)

# Global service — initialized in main.py lifespan with extraction queue.
# Every gRPC servicer reaches for `api_routes.service` via a late import
# so the singleton is always the same instance.
service: KnowledgeService = None  # type: ignore


# Empty routers — kept so the `KB_MODE=read|write|all` split in main.py
# continues to compile. No routes are registered on either side.
read_router = APIRouter()
write_router = APIRouter()

# Legacy combined router — still exported for imports elsewhere.
router = APIRouter()
router.include_router(read_router)
router.include_router(write_router)
