"""Cached gRPC stubs for Kotlin server contracts used by MCP tools.

Mirrors `backend/service-orchestrator/app/grpc_server_client.py` — one
insecure_channel to `<kotlin-server>:5501`, stub factories are lazy so
nothing hits the wire at import time.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.server import (
    bug_tracker_pb2_grpc,
    connection_pb2_grpc,
    environment_k8s_pb2_grpc,
    environment_pb2_grpc,
    git_pb2_grpc,
    meeting_alone_pb2_grpc,
    meeting_attend_pb2_grpc,
    project_management_pb2_grpc,
    task_api_pb2_grpc,
)

logger = logging.getLogger(__name__)

_channel: Optional[grpc.aio.Channel] = None
_connection_stub: Optional[connection_pb2_grpc.ServerConnectionServiceStub] = None
_git_stub: Optional[git_pb2_grpc.ServerGitServiceStub] = None
_meeting_alone_stub: Optional[meeting_alone_pb2_grpc.ServerMeetingAloneServiceStub] = None
_meeting_attend_stub: Optional[meeting_attend_pb2_grpc.ServerMeetingAttendServiceStub] = None
_project_management_stub: Optional[
    project_management_pb2_grpc.ServerProjectManagementServiceStub
] = None
_bug_tracker_stub: Optional[bug_tracker_pb2_grpc.ServerBugTrackerServiceStub] = None
_environment_stub: Optional[environment_pb2_grpc.ServerEnvironmentServiceStub] = None
_task_api_stub: Optional[task_api_pb2_grpc.ServerTaskApiServiceStub] = None
_environment_k8s_stub: Optional[
    environment_k8s_pb2_grpc.ServerEnvironmentK8sServiceStub
] = None


def _kotlin_server_grpc_target() -> str:
    url = settings.kotlin_server_url.rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0]
    return f"{host}:5501"


def _get_channel() -> grpc.aio.Channel:
    global _channel
    if _channel is None:
        target = _kotlin_server_grpc_target()
        _channel = grpc.aio.insecure_channel(target)
        logger.debug("kotlin-server gRPC channel opened to %s", target)
    return _channel


def server_connection_stub() -> connection_pb2_grpc.ServerConnectionServiceStub:
    global _connection_stub
    if _connection_stub is None:
        _connection_stub = connection_pb2_grpc.ServerConnectionServiceStub(_get_channel())
    return _connection_stub


def server_meeting_alone_stub() -> meeting_alone_pb2_grpc.ServerMeetingAloneServiceStub:
    global _meeting_alone_stub
    if _meeting_alone_stub is None:
        _meeting_alone_stub = meeting_alone_pb2_grpc.ServerMeetingAloneServiceStub(_get_channel())
    return _meeting_alone_stub


def server_git_stub() -> git_pb2_grpc.ServerGitServiceStub:
    global _git_stub
    if _git_stub is None:
        _git_stub = git_pb2_grpc.ServerGitServiceStub(_get_channel())
    return _git_stub


def server_meeting_attend_stub() -> meeting_attend_pb2_grpc.ServerMeetingAttendServiceStub:
    global _meeting_attend_stub
    if _meeting_attend_stub is None:
        _meeting_attend_stub = meeting_attend_pb2_grpc.ServerMeetingAttendServiceStub(_get_channel())
    return _meeting_attend_stub


def server_project_management_stub() -> (
    project_management_pb2_grpc.ServerProjectManagementServiceStub
):
    global _project_management_stub
    if _project_management_stub is None:
        _project_management_stub = (
            project_management_pb2_grpc.ServerProjectManagementServiceStub(
                _get_channel()
            )
        )
    return _project_management_stub


def server_bug_tracker_stub() -> bug_tracker_pb2_grpc.ServerBugTrackerServiceStub:
    global _bug_tracker_stub
    if _bug_tracker_stub is None:
        _bug_tracker_stub = bug_tracker_pb2_grpc.ServerBugTrackerServiceStub(
            _get_channel()
        )
    return _bug_tracker_stub


def server_environment_stub() -> environment_pb2_grpc.ServerEnvironmentServiceStub:
    global _environment_stub
    if _environment_stub is None:
        _environment_stub = environment_pb2_grpc.ServerEnvironmentServiceStub(
            _get_channel()
        )
    return _environment_stub


def server_task_api_stub() -> task_api_pb2_grpc.ServerTaskApiServiceStub:
    global _task_api_stub
    if _task_api_stub is None:
        _task_api_stub = task_api_pb2_grpc.ServerTaskApiServiceStub(_get_channel())
    return _task_api_stub


def server_environment_k8s_stub() -> (
    environment_k8s_pb2_grpc.ServerEnvironmentK8sServiceStub
):
    global _environment_k8s_stub
    if _environment_k8s_stub is None:
        _environment_k8s_stub = environment_k8s_pb2_grpc.ServerEnvironmentK8sServiceStub(
            _get_channel()
        )
    return _environment_k8s_stub
