"""Token endpoint – returns current Bearer token for a client."""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from app.models import TokenResponse

router = APIRouter(tags=["token"])


def create_token_router(token_extractor) -> APIRouter:  # noqa: ANN001
    @router.get("/token/{client_id}")
    async def get_token(client_id: str) -> TokenResponse:
        """Return the current Graph API Bearer token for a client.

        Returns 404 if no session exists, 401 if token is expired.
        """
        info = token_extractor.get_graph_token(client_id)
        if info is None:
            # Check if we ever had a token (expired vs never had)
            raise HTTPException(
                status_code=404,
                detail=f"No valid token for client '{client_id}'. Session may need login.",
            )

        now = datetime.now(timezone.utc)
        age = int((now - info.extracted_at).total_seconds())
        return TokenResponse(
            token=info.token,
            expires_at=info.estimated_expiry.isoformat(),
            age_seconds=age,
        )

    return router
