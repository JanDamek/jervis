"""Cached gRPC stubs for the Kotlin server's pod-to-pod services.

Every Python → Kotlin server contract migrates here one-by-one. Each
stub is cached behind a grpc.aio.insecure_channel; the module is
import-safe (no network on import) and stubs are lazily constructed on
first use.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.server import (
    bug_tracker_pb2_grpc,
    cache_pb2_grpc,
    chat_approval_pb2_grpc,
    chat_context_pb2_grpc,
    environment_pb2_grpc,
    filter_rules_pb2_grpc,
    finance_pb2_grpc,
    guidelines_pb2_grpc,
    meetings_pb2_grpc,
    merge_request_pb2_grpc,
    proactive_pb2_grpc,
    project_management_pb2_grpc,
    task_api_pb2_grpc,
    time_tracking_pb2_grpc,
    urgency_pb2_grpc,
)

logger = logging.getLogger(__name__)

_channel: Optional[grpc.aio.Channel] = None
_cache_stub: Optional[cache_pb2_grpc.ServerCacheServiceStub] = None
_chat_approval_stub: Optional[chat_approval_pb2_grpc.ServerChatApprovalServiceStub] = None
_chat_context_stub: Optional[chat_context_pb2_grpc.ServerChatContextServiceStub] = None
_filter_rules_stub: Optional[filter_rules_pb2_grpc.ServerFilterRulesServiceStub] = None
_guidelines_stub: Optional[guidelines_pb2_grpc.ServerGuidelinesServiceStub] = None
_meetings_stub: Optional[meetings_pb2_grpc.ServerMeetingsServiceStub] = None
_proactive_stub: Optional[proactive_pb2_grpc.ServerProactiveServiceStub] = None
_time_tracking_stub: Optional[time_tracking_pb2_grpc.ServerTimeTrackingServiceStub] = None
_urgency_stub: Optional[urgency_pb2_grpc.ServerUrgencyServiceStub] = None
_finance_stub: Optional[finance_pb2_grpc.ServerFinanceServiceStub] = None
_project_management_stub: Optional[
    project_management_pb2_grpc.ServerProjectManagementServiceStub
] = None
_bug_tracker_stub: Optional[bug_tracker_pb2_grpc.ServerBugTrackerServiceStub] = None
_merge_request_stub: Optional[merge_request_pb2_grpc.ServerMergeRequestServiceStub] = None
_environment_stub: Optional[environment_pb2_grpc.ServerEnvironmentServiceStub] = None
_task_api_stub: Optional[task_api_pb2_grpc.ServerTaskApiServiceStub] = None


def _kotlin_server_grpc_target() -> str:
    # Host from `kotlin_server_url`, port 5501 (gRPC). Ktor kRPC stays on
    # whatever port the URL says (currently 5500).
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


def server_cache_stub() -> cache_pb2_grpc.ServerCacheServiceStub:
    global _cache_stub
    if _cache_stub is None:
        _cache_stub = cache_pb2_grpc.ServerCacheServiceStub(_get_channel())
    return _cache_stub


def server_guidelines_stub() -> guidelines_pb2_grpc.ServerGuidelinesServiceStub:
    global _guidelines_stub
    if _guidelines_stub is None:
        _guidelines_stub = guidelines_pb2_grpc.ServerGuidelinesServiceStub(_get_channel())
    return _guidelines_stub


def server_filter_rules_stub() -> filter_rules_pb2_grpc.ServerFilterRulesServiceStub:
    global _filter_rules_stub
    if _filter_rules_stub is None:
        _filter_rules_stub = filter_rules_pb2_grpc.ServerFilterRulesServiceStub(_get_channel())
    return _filter_rules_stub


def server_urgency_stub() -> urgency_pb2_grpc.ServerUrgencyServiceStub:
    global _urgency_stub
    if _urgency_stub is None:
        _urgency_stub = urgency_pb2_grpc.ServerUrgencyServiceStub(_get_channel())
    return _urgency_stub


def server_chat_context_stub() -> chat_context_pb2_grpc.ServerChatContextServiceStub:
    global _chat_context_stub
    if _chat_context_stub is None:
        _chat_context_stub = chat_context_pb2_grpc.ServerChatContextServiceStub(_get_channel())
    return _chat_context_stub


def server_proactive_stub() -> proactive_pb2_grpc.ServerProactiveServiceStub:
    global _proactive_stub
    if _proactive_stub is None:
        _proactive_stub = proactive_pb2_grpc.ServerProactiveServiceStub(_get_channel())
    return _proactive_stub


def server_time_tracking_stub() -> time_tracking_pb2_grpc.ServerTimeTrackingServiceStub:
    global _time_tracking_stub
    if _time_tracking_stub is None:
        _time_tracking_stub = time_tracking_pb2_grpc.ServerTimeTrackingServiceStub(_get_channel())
    return _time_tracking_stub


def server_meetings_stub() -> meetings_pb2_grpc.ServerMeetingsServiceStub:
    global _meetings_stub
    if _meetings_stub is None:
        _meetings_stub = meetings_pb2_grpc.ServerMeetingsServiceStub(_get_channel())
    return _meetings_stub


def server_chat_approval_stub() -> chat_approval_pb2_grpc.ServerChatApprovalServiceStub:
    global _chat_approval_stub
    if _chat_approval_stub is None:
        _chat_approval_stub = chat_approval_pb2_grpc.ServerChatApprovalServiceStub(_get_channel())
    return _chat_approval_stub


def server_finance_stub() -> finance_pb2_grpc.ServerFinanceServiceStub:
    global _finance_stub
    if _finance_stub is None:
        _finance_stub = finance_pb2_grpc.ServerFinanceServiceStub(_get_channel())
    return _finance_stub


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


def server_merge_request_stub() -> (
    merge_request_pb2_grpc.ServerMergeRequestServiceStub
):
    global _merge_request_stub
    if _merge_request_stub is None:
        _merge_request_stub = merge_request_pb2_grpc.ServerMergeRequestServiceStub(
            _get_channel()
        )
    return _merge_request_stub


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
