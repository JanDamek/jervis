"""gRPC server for `jervis-meeting-attender`.

Exposes MeetingAttenderService.{Attend, Stop}. The semantics match the
retired /attend + /stop REST routes — AttenderManager is the single
source of truth for session state; the FastAPI app now only hosts the
/health + /ready probes.
"""

from __future__ import annotations

import logging

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.meeting_attender import attender_pb2, attender_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("meeting_attender.grpc")


class MeetingAttenderServicer(attender_pb2_grpc.MeetingAttenderServiceServicer):
    def __init__(self, manager):
        self._manager = manager

    async def Attend(
        self,
        request: attender_pb2.AttendRequest,
        context: grpc.aio.ServicerContext,
    ) -> attender_pb2.AttendResponse:
        from app.main import AttendRequest

        if not request.task_id or not request.client_id:
            return attender_pb2.AttendResponse(
                ok=False, task_id=request.task_id, error="task_id + client_id required",
            )
        req = AttendRequest(
            task_id=request.task_id,
            client_id=request.client_id,
            project_id=request.project_id or None,
            title=request.title,
            join_url=request.join_url,
            end_time_iso=request.end_time_iso,
            provider=request.provider,
        )
        try:
            session = await self._manager.attend(req)
        except Exception as e:
            logger.exception("MEETING_ATTENDER_GRPC_FAIL task=%s", request.task_id)
            return attender_pb2.AttendResponse(
                ok=False, task_id=request.task_id, error=str(e)[:300],
            )
        return attender_pb2.AttendResponse(
            ok=True, task_id=session.task_id, state=str(session.state or ""),
        )

    async def Stop(
        self,
        request: attender_pb2.StopRequest,
        context: grpc.aio.ServicerContext,
    ) -> attender_pb2.StopResponse:
        if not request.task_id:
            return attender_pb2.StopResponse(ok=False, error="task_id required")
        try:
            await self._manager.stop(request.task_id, reason=request.reason or "")
        except Exception as e:
            logger.warning("MEETING_ATTENDER_STOP_FAIL task=%s: %s", request.task_id, e)
            return attender_pb2.StopResponse(ok=False, error=str(e)[:300])
        return attender_pb2.StopResponse(ok=True)


async def start_grpc_server(manager, port: int = 5501) -> grpc.aio.Server:
    max_msg_bytes = 16 * 1024 * 1024
    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=[
            ("grpc.max_receive_message_length", max_msg_bytes),
            ("grpc.max_send_message_length", max_msg_bytes),
        ],
    )
    attender_pb2_grpc.add_MeetingAttenderServiceServicer_to_server(
        MeetingAttenderServicer(manager), server,
    )
    reflection.enable_server_reflection(
        (
            attender_pb2.DESCRIPTOR.services_by_name["MeetingAttenderService"].full_name,
            reflection.SERVICE_NAME,
        ),
        server,
    )
    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC meeting-attender listening on :%d", port)
    return server
