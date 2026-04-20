"""gRPC server for `jervis-correction`.

Fully-typed surface — CorrectionService has one RPC per former REST
route; every field is declared in the proto so callers never decode a
free-form JSON body. Segment lists + question lists + rule lists are
carried as repeated typed messages.
"""

from __future__ import annotations

import logging

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.correction import correction_pb2, correction_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("correction.grpc")


def _seg_from_proto(seg: correction_pb2.CorrectionSegment) -> dict:
    out: dict = {
        "i": int(seg.i),
        "startSec": float(seg.start_sec),
        "endSec": float(seg.end_sec),
        "text": seg.text,
    }
    if seg.speaker:
        out["speaker"] = seg.speaker
    return out


def _seg_to_proto(seg: dict) -> correction_pb2.CorrectionSegment:
    return correction_pb2.CorrectionSegment(
        i=int(seg.get("i", 0)),
        start_sec=float(seg.get("startSec", 0.0)),
        end_sec=float(seg.get("endSec", 0.0)),
        text=str(seg.get("text", "")),
        speaker=str(seg.get("speaker") or ""),
    )


def _question_to_proto(q: dict) -> correction_pb2.CorrectionQuestion:
    return correction_pb2.CorrectionQuestion(
        id=str(q.get("id", "")),
        i=int(q.get("i", 0)),
        original=str(q.get("original", "")),
        question=str(q.get("question", "")),
        options=[str(o) for o in q.get("options") or []],
        context=str(q.get("context") or ""),
    )


class CorrectionServicer(correction_pb2_grpc.CorrectionServiceServicer):

    async def SubmitCorrection(
        self,
        request: correction_pb2.SubmitCorrectionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.SubmitCorrectionResponse:
        from app.agent import correction_agent

        if not request.client_id or not request.original or not request.corrected:
            return correction_pb2.SubmitCorrectionResponse(
                status="error", error="client_id + original + corrected required",
            )
        try:
            result = await correction_agent.submit_correction(
                client_id=request.client_id,
                project_id=request.project_id or None,
                original=request.original,
                corrected=request.corrected,
                category=request.category or "general",
                context=request.context or None,
            )
        except Exception as e:
            logger.exception("CORRECTION_SUBMIT_FAIL")
            return correction_pb2.SubmitCorrectionResponse(status="error", error=str(e)[:300])
        return correction_pb2.SubmitCorrectionResponse(
            correction_id=str(result.get("correctionId", "")),
            source_urn=str(result.get("sourceUrn", "")),
            status=str(result.get("status", "success")),
        )

    async def CorrectTranscript(
        self,
        request: correction_pb2.CorrectTranscriptRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectResult:
        from app.agent import correction_agent

        segments = [_seg_from_proto(s) for s in request.segments]
        try:
            result = await correction_agent.correct_transcript(
                client_id=request.client_id,
                project_id=request.project_id or None,
                segments=segments,
                chunk_size=int(request.chunk_size) if request.chunk_size > 0 else 20,
                meeting_id=request.meeting_id or None,
                speaker_hints=dict(request.speaker_hints) if request.speaker_hints else None,
            )
        except Exception as e:
            logger.exception("CORRECTION_CORRECT_FAIL")
            return correction_pb2.CorrectResult(status="failed")

        return correction_pb2.CorrectResult(
            segments=[_seg_to_proto(s) for s in (result.get("segments") or [])],
            questions=[_question_to_proto(q) for q in (result.get("questions") or [])],
            status=str(result.get("status", "success")),
        )

    async def ListCorrections(
        self,
        request: correction_pb2.ListCorrectionsRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.ListCorrectionsResponse:
        from app.agent import correction_agent

        try:
            corrections = await correction_agent.list_corrections(
                client_id=request.client_id,
                project_id=request.project_id or None,
                max_results=int(request.max_results) if request.max_results > 0 else 100,
            )
        except Exception as e:
            logger.exception("CORRECTION_LIST_FAIL")
            corrections = []

        out = []
        for c in corrections:
            meta = c.get("metadata") or {}
            out.append(correction_pb2.CorrectionChunk(
                content=str(c.get("content", "")),
                source_urn=str(c.get("sourceUrn", "")),
                metadata=correction_pb2.CorrectionChunkMeta(
                    original=str(meta.get("original", "")),
                    corrected=str(meta.get("corrected", "")),
                    category=str(meta.get("category", "general")),
                    context=str(meta.get("context", "")),
                    correction_id=str(meta.get("correctionId", "")),
                ),
            ))
        return correction_pb2.ListCorrectionsResponse(corrections=out)

    async def AnswerCorrectionQuestions(
        self,
        request: correction_pb2.AnswerCorrectionsRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.AnswerCorrectionsResponse:
        from app.agent import correction_agent

        answers = [
            {
                "original": r.original,
                "corrected": r.corrected,
                "category": r.category or "general",
                "context": r.context or None,
            }
            for r in request.answers
        ]
        try:
            results = await correction_agent.apply_answers_as_corrections(
                client_id=request.client_id,
                project_id=request.project_id or None,
                answers=answers,
            )
        except Exception as e:
            logger.exception("CORRECTION_ANSWER_FAIL")
            return correction_pb2.AnswerCorrectionsResponse(
                status="error", error=str(e)[:300],
            )
        return correction_pb2.AnswerCorrectionsResponse(
            status="success", rules_created=len(results),
        )

    async def CorrectWithInstruction(
        self,
        request: correction_pb2.CorrectWithInstructionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectWithInstructionResponse:
        from app.agent import correction_agent

        segments = [_seg_from_proto(s) for s in request.segments]
        try:
            result = await correction_agent.correct_with_instruction(
                client_id=request.client_id,
                project_id=request.project_id or None,
                segments=segments,
                instruction=request.instruction,
            )
        except Exception as e:
            logger.exception("CORRECTION_INSTRUCT_FAIL")
            return correction_pb2.CorrectWithInstructionResponse(
                status="failed", summary=str(e)[:300],
            )
        new_rules = [
            correction_pb2.InstructRuleResult(
                correction_id=str(r.get("correctionId", "")),
                source_urn=str(r.get("sourceUrn", "")),
                status=str(r.get("status", "")),
            )
            for r in (result.get("newRules") or [])
        ]
        return correction_pb2.CorrectWithInstructionResponse(
            segments=[_seg_to_proto(s) for s in (result.get("segments") or [])],
            new_rules=new_rules,
            status=str(result.get("status", "success")),
            summary=str(result.get("summary") or ""),
        )

    async def CorrectTargeted(
        self,
        request: correction_pb2.CorrectTargetedRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.CorrectResult:
        from app.agent import correction_agent

        segments = [_seg_from_proto(s) for s in request.segments]
        try:
            result = await correction_agent.correct_targeted(
                client_id=request.client_id,
                project_id=request.project_id or None,
                segments=segments,
                retranscribed_indices=list(request.retranscribed_indices),
                user_corrected_indices=dict(request.user_corrected_indices) if request.user_corrected_indices else {},
                meeting_id=request.meeting_id or None,
            )
        except Exception as e:
            logger.exception("CORRECTION_TARGETED_FAIL")
            return correction_pb2.CorrectResult(status="failed")

        return correction_pb2.CorrectResult(
            segments=[_seg_to_proto(s) for s in (result.get("segments") or [])],
            questions=[_question_to_proto(q) for q in (result.get("questions") or [])],
            status=str(result.get("status", "success")),
        )

    async def DeleteCorrection(
        self,
        request: correction_pb2.DeleteCorrectionRequest,
        context: grpc.aio.ServicerContext,
    ) -> correction_pb2.DeleteCorrectionResponse:
        from app.agent import correction_agent

        if not request.source_urn:
            return correction_pb2.DeleteCorrectionResponse(
                status="error", error="source_urn required",
            )
        try:
            result = await correction_agent.delete_correction(request.source_urn)
        except Exception as e:
            logger.exception("CORRECTION_DELETE_FAIL")
            return correction_pb2.DeleteCorrectionResponse(status="error", error=str(e)[:300])
        return correction_pb2.DeleteCorrectionResponse(
            status=str(result.get("status", "success")),
            chunks_deleted=int(result.get("chunks_deleted") or 0),
            nodes_cleaned=int(result.get("nodes_cleaned") or 0),
            edges_cleaned=int(result.get("edges_cleaned") or 0),
            nodes_deleted=int(result.get("nodes_deleted") or 0),
            edges_deleted=int(result.get("edges_deleted") or 0),
        )


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    from jervis_contracts.grpc_options import build_server_options

    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=build_server_options(),
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
