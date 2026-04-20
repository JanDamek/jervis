"""gRPC server for jervis-visual-capture.

Fully-typed surface — VisualCaptureService has three RPCs, each with
explicit proto messages. The Python capture/PTZ handlers live as plain
async functions in `app.main`; the servicer invokes them and maps the
result dicts onto the proto response fields.
"""

from __future__ import annotations

import logging

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
    ) -> capture_pb2.SnapshotResponse:
        from app.main import capture_snapshot, SnapshotRequest as SnapshotModel

        try:
            model = SnapshotModel(
                mode=request.mode or "scene",
                preset=request.preset or None,
                custom_prompt=request.custom_prompt or None,
            )
        except Exception as e:
            return capture_pb2.SnapshotResponse(error=f"Invalid snapshot request: {e}")
        try:
            result = await capture_snapshot(model)
        except Exception as e:
            logger.exception("VISUAL_SNAPSHOT_GRPC_FAIL")
            return capture_pb2.SnapshotResponse(error=str(e)[:300])

        return capture_pb2.SnapshotResponse(
            description=str(result.get("description") or ""),
            ocr_text=str(result.get("ocr_text") or ""),
            mode=str(result.get("mode") or request.mode or "scene"),
            model=str(result.get("model") or ""),
            frame_size_bytes=int(result.get("frame_size_bytes") or 0),
            timestamp=str(result.get("timestamp") or ""),
            preset=str(result.get("preset") or ""),
            error=str(result.get("error") or ""),
        )

    async def PtzGoto(
        self,
        request: capture_pb2.PtzGotoRequest,
        context: grpc.aio.ServicerContext,
    ) -> capture_pb2.PtzGotoResponse:
        from app.main import ptz_goto, PTZRequest

        if not request.preset:
            return capture_pb2.PtzGotoResponse(status="error", error="preset required")
        try:
            result = await ptz_goto(PTZRequest(preset=request.preset))
        except Exception as e:
            logger.exception("VISUAL_PTZ_GRPC_FAIL")
            return capture_pb2.PtzGotoResponse(status="error", error=str(e)[:300])
        return capture_pb2.PtzGotoResponse(
            status=str(result.get("status") or "ok"),
            preset=str(result.get("preset") or request.preset),
        )

    async def PtzPresets(
        self,
        request: capture_pb2.PtzPresetsRequest,
        context: grpc.aio.ServicerContext,
    ) -> capture_pb2.PtzPresetsResponse:
        from app.main import ptz_presets

        try:
            result = await ptz_presets()
        except Exception as e:
            logger.exception("VISUAL_PTZ_PRESETS_GRPC_FAIL")
            return capture_pb2.PtzPresetsResponse()
        presets = result.get("presets") or []
        return capture_pb2.PtzPresetsResponse(presets=[str(p) for p in presets])


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    from jervis_contracts.grpc_options import build_server_options

    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=build_server_options(max_msg_bytes=16 * 1024 * 1024),
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
