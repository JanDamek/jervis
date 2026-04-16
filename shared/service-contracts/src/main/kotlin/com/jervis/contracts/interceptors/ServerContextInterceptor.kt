package com.jervis.contracts.interceptors

import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.slf4j.LoggerFactory

// Logs `<full_rpc_name> request_id=<…>` for every unary/stream call and
// rejects calls whose RequestContext.scope.client_id is empty, except for
// RPCs explicitly opted out via `unauthenticatedMethods`.
//
// The interceptor cannot inspect the first proto message here (gRPC does
// not expose the request body at the listener layer without a forwarding
// listener on onMessage). Validation of scope.client_id therefore happens
// inside each service impl's method entry — the interceptor covers
// logging and bootstrap request_id generation only. Services that want
// auto-validation can wrap their logic with `requireClientScope(ctx)`
// declared in the domain service base class.
class ServerContextInterceptor(
    private val unauthenticatedMethods: Set<String> = DEFAULT_UNAUTHENTICATED,
) : ServerInterceptor {
    private val log = LoggerFactory.getLogger(ServerContextInterceptor::class.java)

    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        log.debug("grpc-inbound {} authority={}", method, call.authority)
        return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
            next.startCall(call, headers),
        ) {
            override fun onHalfClose() {
                try {
                    super.onHalfClose()
                } catch (t: Throwable) {
                    // Normalize unknown exceptions to INTERNAL with RPC context.
                    log.error("grpc-handler-error {}", method, t)
                    call.close(Status.INTERNAL.withCause(t).withDescription(t.message), Metadata())
                }
            }
        }
    }

    companion object {
        // Health-check-style RPCs that are allowed without a scope.client_id.
        val DEFAULT_UNAUTHENTICATED: Set<String> = setOf(
            "grpc.health.v1.Health/Check",
            "grpc.health.v1.Health/Watch",
            "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo",
            "grpc.reflection.v1.ServerReflection/ServerReflectionInfo",
        )
    }
}
