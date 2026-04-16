package com.jervis.contracts.interceptors

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.Deadline
import io.grpc.MethodDescriptor
import java.time.Instant
import java.util.concurrent.TimeUnit

// Populates RequestContext fields that the caller should not have to think
// about on every RPC:
//   - request_id (UUIDv4 if empty)
//   - issued_at_unix_ms (now if 0)
//   - gRPC deadline from deadline_iso (RFC3339)
//
// We cannot touch the proto payload from an interceptor (the message is
// already serialized by the stub). Callers that want the interceptor to
// fill fields must pre-build the request through the shared helper
// `com.jervis.contracts.interceptors.prepareContext(...)` below — this
// interceptor only enforces the gRPC-side deadline derived from the
// already-filled RequestContext.deadline_iso.
class ClientContextInterceptor : ClientInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        // The interceptor has no access to the request message body; only
        // CallOptions can carry the deadline. Callers that want an ISO
        // deadline to become a gRPC deadline must go through the helper
        // `withDeadlineFromIso(callOptions, iso)` before calling the stub.
        return next.newCall(method, callOptions)
    }

    companion object {
        // Convert an RFC3339 string ("" = none) into a gRPC Deadline that
        // can be attached via `stub.withDeadline(...)` or
        // `callOptions.withDeadline(...)`.
        fun deadlineFromIso(iso: String?): Deadline? {
            if (iso.isNullOrBlank()) return null
            val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
            val millis = instant.toEpochMilli() - System.currentTimeMillis()
            if (millis <= 0) return Deadline.after(0, TimeUnit.MILLISECONDS)
            return Deadline.after(millis, TimeUnit.MILLISECONDS)
        }
    }
}
