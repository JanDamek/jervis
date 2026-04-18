"""gRPC server for `jervis-correction`.

Exposes CorrectionService — one RPC per former REST route. Request +
response bodies ride as passthrough JSON strings, matching the legacy
DTOs on the caller side (Kotlin CorrectionClient uses kotlinx
serialisation, so we avoid re-encoding fields into the proto).
"""

from __future__ import annotations

import json
import logging

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.correction import correction_pb2, correction_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("correction.grpc")


class CorrectionServicer(correction_pb2_grpc.CorrectionServiceServicer):
    async def SubmitCorrection(
        self,
        request: correction_pb2.CorrectionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectionResponse:
        from app.agent import correction_agent

        body = _parse_json(request.body_json)
        return await _dispatch("submit", context, lambda: correction_agent.submit_correction(
            client_id=body["clientId"],
            project_id=body.get("projectId"),
            original=body["original"],
            corrected=body["corrected"],
            category=body.get("category", "general"),
            context=body.get("context"),
        ))

    async def CorrectTranscript(
        self,
        request: correction_pb2.CorrectionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectionResponse:
        from app.agent import correction_agent

        body = _parse_json(request.body_json)
        return await _dispatch("correct", context, lambda: correction_agent.correct_transcript(
            client_id=body["clientId"],
            project_id=body.get("projectId"),
            segments=body["segments"],
            chunk_size=body.get("chunkSize", 20),
            meeting_id=body.get("meetingId"),
            speaker_hints=body.get("speakerHints"),
        ))

    async def ListCorrections(
        self,
        request: correction_pb2.CorrectionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectionResponse:
        from app.agent import correction_agent

        body = _parse_json(request.body_json)

        async def _list():
            corrections = await correction_agent.list_corrections(
                client_id=body["clientId"],
                project_id=body.get("projectId"),
                max_results=body.get("maxResults", 100),
            )
            return {"corrections": corrections}

        return await _dispatch("list", context, _list)

    async def AnswerCorrectionQuestions(
        self,
        request: correction_pb2.CorrectionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectionResponse:
        from app.agent import correction_agent

        body = _parse_json(request.body_json)

        async def _answer():
            results = await correction_agent.apply_answers_as_corrections(
                client_id=body["clientId"],
                project_id=body.get("projectId"),
                answers=body.get("answers", []),
            )
            return {"status": "success", "rulesCreated": len(results)}

        return await _dispatch("answer", context, _answer)

    async def CorrectWithInstruction(
        self,
        request: correction_pb2.CorrectionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectionResponse:
        from app.agent import correction_agent

        body = _parse_json(request.body_json)
        return await _dispatch("instruct", context, lambda: correction_agent.correct_with_instruction(
            client_id=body["clientId"],
            project_id=body.get("projectId"),
            segments=body["segments"],
            instruction=body["instruction"],
        ))

    async def CorrectTargeted(
        self,
        request: correction_pb2.CorrectionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectionResponse:
        from app.agent import correction_agent

        body = _parse_json(request.body_json)
        return await _dispatch("targeted", context, lambda: correction_agent.correct_targeted(
            client_id=body["clientId"],
            project_id=body.get("projectId"),
            segments=body["segments"],
            retranscribed_indices=body.get("retranscribedIndices", []),
            user_corrected_indices=body.get("userCorrectedIndices", {}),
            meeting_id=body.get("meetingId"),
        ))

    async def DeleteCorrection(
        self,
        request: correction_pb2.CorrectionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectionResponse:
        from app.agent import correction_agent

        body = _parse_json(request.body_json)
        return await _dispatch("delete", context, lambda: correction_agent.delete_correction(body["sourceUrn"]))


def _parse_json(s: str) -> dict:
    if not s:
        return {}
    try:
        v = json.loads(s)
        return v if isinstance(v, dict) else {}
    except Exception:
        return {}


async def _dispatch(label: str, context: grpc.aio.ServicerContext, fn) -> correction_pb2.CorrectionResponse:
    try:
        result = await fn()
    except KeyError as e:
        return correction_pb2.CorrectionResponse(
            status=400,
            body_json=json.dumps({"detail": f"Missing field: {e}"}),
        )
    except Exception as e:
        logger.exception("CORRECTION_%s_FAIL", label.upper())
        return correction_pb2.CorrectionResponse(
            status=500,
            body_json=json.dumps({"detail": str(e)[:300]}),
        )
    return correction_pb2.CorrectionResponse(
        status=200,
        body_json=json.dumps(result, default=str, ensure_ascii=False),
    )


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    max_msg_bytes = 64 * 1024 * 1024
    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=[
            ("grpc.max_receive_message_length", max_msg_bytes),
            ("grpc.max_send_message_length", max_msg_bytes),
        ],
    )
    correction_pb2_grpc.add_CorrectionServiceServicer_to_server(
        CorrectionServicer(), server,
    )
    reflection.enable_server_reflection(
        (
            correction_pb2.DESCRIPTOR.services_by_name["CorrectionService"].full_name,
            reflection.SERVICE_NAME,
        ),
        server,
    )
    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC correction listening on :%d", port)
    return server
