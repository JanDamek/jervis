"""Shared gRPC channel / server option builders.

Every Jervis Python service that hosts a `grpc.aio.server` calls
`build_server_options()` so we don't end up with one service using
defaults and blowing up the other end with ENHANCE_YOUR_CALM ("too
many pings") or DEADLINE_EXCEEDED the moment a long-running stream
idles between chunks.

Kotlin clients (see `infrastructure/grpc/GrpcChannels.kt`,
`TtsGrpcClient`, `WhisperRestClient`) send keepalive pings every 30 s
with `keepAliveWithoutCalls=true`. gRPC's server default is a 5-minute
`min_ping_interval_without_data` which RST_STREAMs such clients after
~2 pings — the fix is to let the server accept pings as often as the
clients want to send them.
"""

from __future__ import annotations


# 64 MiB fits every current unary payload (WAV slices up to ~45 s,
# graph snapshots with ~500 vertices, etc.). Whisper overrides to
# 256 MiB because meeting audio files can be >64 MiB per transcribe.
DEFAULT_MAX_MSG_BYTES = 64 * 1024 * 1024


def build_server_options(
    max_msg_bytes: int = DEFAULT_MAX_MSG_BYTES,
) -> list[tuple[str, int | str]]:
    """Return the list of `(key, value)` options for `grpc.aio.server`."""
    return [
        ("grpc.max_receive_message_length", max_msg_bytes),
        ("grpc.max_send_message_length", max_msg_bytes),
        # Accept client keepalive pings as often as every 10 s. Kotlin
        # clients ping every 30 s, so this has a comfortable margin;
        # the default of 5 min would trip the "too many pings" limiter
        # on the first long-running stream.
        ("grpc.http2.min_ping_interval_without_data_ms", 10_000),
        ("grpc.http2.max_pings_without_data", 0),
        # Server-side keepalive so half-open connections (VD reboots,
        # mobile network drops) are reaped in seconds, not minutes.
        ("grpc.keepalive_time_ms", 30_000),
        ("grpc.keepalive_timeout_ms", 5_000),
        ("grpc.keepalive_permit_without_calls", 1),
    ]


def build_client_options(
    max_msg_bytes: int = DEFAULT_MAX_MSG_BYTES,
) -> list[tuple[str, int | str]]:
    """Mirror of [build_server_options] for client channels."""
    return [
        ("grpc.max_receive_message_length", max_msg_bytes),
        ("grpc.max_send_message_length", max_msg_bytes),
        ("grpc.keepalive_time_ms", 30_000),
        ("grpc.keepalive_timeout_ms", 5_000),
        ("grpc.keepalive_permit_without_calls", 1),
        ("grpc.http2.max_pings_without_data", 0),
    ]
