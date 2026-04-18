"""gRPC server for jervis-visual-capture.

Exposes VisualCaptureService{Snapshot, PtzGoto, PtzPresets} on :5501.
The Python capture/PTZ handlers keep the same semantics the FastAPI
routes used; the passthrough JSON shape matches the legacy request
bodies so the Kotlin caller doesn't need per-field decoding.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.visual_capture import capture_pb2, capture_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("visual_capture.grpc")


class VisualCaptureServicer(capture_pb2_grpc.VisualCaptureServiceServicer):
    async def Snapshot(
        self,
        request: capture_pb2.SnapshotRequest,
        context: grpc.aio.ServicerContext,
    ) -> capture_pb2.RawJsonResponse:
        from app.main import capture_snapshot, SnapshotRequest as SnapshotModel

        body = _parse_json(request.request_json)
        try:
            model = SnapshotModel(**body) if body else SnapshotModel()
        except Exception as e:
            return capture_pb2.RawJsonResponse(
                status=400,
                body_json=json.dumps({"detail": f"Invalid snapshot request: {e}"}),
            )
        try:
            result = await capture_snapshot(model)
        except Exception as e:
            logger.exception("VISUAL_SNAPSHOT_GRPC_FAIL")
            return capture_pb2.RawJsonResponse(
                status=502,
                body_json=json.dumps({"status": "error", "error": str(e)[:300]}),
            )
        return capture_pb2.RawJsonResponse(status=200, body_json=_dump_json(result))

    async def PtzGoto(
        self,
        request: capture_pb2.PtzGotoRequest,
        context: grpc.aio.ServicerContext,
    ) -> capture_pb2.RawJsonResponse:
        from app.main import ptz_goto, PTZRequest

        body = _parse_json(request.request_json)
        preset = body.get("preset", "")
        if not preset:
            return capture_pb2.RawJsonResponse(
                status=400, body_json=json.dumps({"detail": "preset required"}),
            )
        try:
            result = await ptz_goto(PTZRequest(preset=preset))
        except Exception as e:
            logger.exception("VISUAL_PTZ_GRPC_FAIL")
            return capture_pb2.RawJsonResponse(
                status=502,
                body_json=json.dumps({"status": "error", "error": str(e)[:300]}),
            )
        return capture_pb2.RawJsonResponse(status=200, body_json=_dump_json(result))

    async def PtzPresets(
        self,
        request: capture_pb2.PtzPresetsRequest,
        context: grpc.aio.ServicerContext,
    ) -> capture_pb2.RawJsonResponse:
        from app.main import ptz_presets

        try:
            result = await ptz_presets()
        except Exception as e:
            return capture_pb2.RawJsonResponse(
                status=502,
                body_json=json.dumps({"status": "error", "error": str(e)[:300]}),
            )
        return capture_pb2.RawJsonResponse(status=200, body_json=_dump_json(result))


def _parse_json(s: str) -> dict:
    if not s:
        return {}
    try:
        v = json.loads(s)
        return v if isinstance(v, dict) else {}
    except Exception:
        return {}


def _dump_json(v: Any) -> str:
    try:
        return json.dumps(v, default=str, ensure_ascii=False)
    except Exception:
        return "{}"


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    max_msg_bytes = 16 * 1024 * 1024
    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=[
            ("grpc.max_receive_message_length", max_msg_bytes),
            ("grpc.max_send_message_length", max_msg_bytes),
        ],
    )
    capture_pb2_grpc.add_VisualCaptureServiceServicer_to_server(
        VisualCaptureServicer(), server,
    )
    reflection.enable_server_reflection(
        (
            capture_pb2.DESCRIPTOR.services_by_name["VisualCaptureService"].full_name,
            reflection.SERVICE_NAME,
        ),
        server,
    )
    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC visual-capture listening on :%d", port)
    return server
