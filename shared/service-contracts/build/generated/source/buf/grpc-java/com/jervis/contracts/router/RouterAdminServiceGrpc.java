package com.jervis.contracts.router;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * RouterAdminService exposes the `/router/admin/&#42;` and
 * `/router/internal/&#42;` surface of `service-ollama-router`. The transparent
 * Ollama-compatible endpoints (`/api/generate`, `/api/chat`, `/api/embeddings`)
 * stay REST because they are a vendor contract (Ollama API) — consumers
 * call them directly, no gRPC wrapper.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class RouterAdminServiceGrpc {

  private RouterAdminServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.router.RouterAdminService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.MaxContextRequest,
      com.jervis.contracts.router.MaxContextResponse> getGetMaxContextMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetMaxContext",
      requestType = com.jervis.contracts.router.MaxContextRequest.class,
      responseType = com.jervis.contracts.router.MaxContextResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.MaxContextRequest,
      com.jervis.contracts.router.MaxContextResponse> getGetMaxContextMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.MaxContextRequest, com.jervis.contracts.router.MaxContextResponse> getGetMaxContextMethod;
    if ((getGetMaxContextMethod = RouterAdminServiceGrpc.getGetMaxContextMethod) == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        if ((getGetMaxContextMethod = RouterAdminServiceGrpc.getGetMaxContextMethod) == null) {
          RouterAdminServiceGrpc.getGetMaxContextMethod = getGetMaxContextMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.MaxContextRequest, com.jervis.contracts.router.MaxContextResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetMaxContext"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.MaxContextRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.MaxContextResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterAdminServiceMethodDescriptorSupplier("GetMaxContext"))
              .build();
        }
      }
    }
    return getGetMaxContextMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.ReportModelErrorRequest,
      com.jervis.contracts.router.ReportModelErrorResponse> getReportModelErrorMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReportModelError",
      requestType = com.jervis.contracts.router.ReportModelErrorRequest.class,
      responseType = com.jervis.contracts.router.ReportModelErrorResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.ReportModelErrorRequest,
      com.jervis.contracts.router.ReportModelErrorResponse> getReportModelErrorMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.ReportModelErrorRequest, com.jervis.contracts.router.ReportModelErrorResponse> getReportModelErrorMethod;
    if ((getReportModelErrorMethod = RouterAdminServiceGrpc.getReportModelErrorMethod) == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        if ((getReportModelErrorMethod = RouterAdminServiceGrpc.getReportModelErrorMethod) == null) {
          RouterAdminServiceGrpc.getReportModelErrorMethod = getReportModelErrorMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.ReportModelErrorRequest, com.jervis.contracts.router.ReportModelErrorResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReportModelError"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ReportModelErrorRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ReportModelErrorResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterAdminServiceMethodDescriptorSupplier("ReportModelError"))
              .build();
        }
      }
    }
    return getReportModelErrorMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.ReportModelSuccessRequest,
      com.jervis.contracts.router.ReportModelSuccessResponse> getReportModelSuccessMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReportModelSuccess",
      requestType = com.jervis.contracts.router.ReportModelSuccessRequest.class,
      responseType = com.jervis.contracts.router.ReportModelSuccessResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.ReportModelSuccessRequest,
      com.jervis.contracts.router.ReportModelSuccessResponse> getReportModelSuccessMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.ReportModelSuccessRequest, com.jervis.contracts.router.ReportModelSuccessResponse> getReportModelSuccessMethod;
    if ((getReportModelSuccessMethod = RouterAdminServiceGrpc.getReportModelSuccessMethod) == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        if ((getReportModelSuccessMethod = RouterAdminServiceGrpc.getReportModelSuccessMethod) == null) {
          RouterAdminServiceGrpc.getReportModelSuccessMethod = getReportModelSuccessMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.ReportModelSuccessRequest, com.jervis.contracts.router.ReportModelSuccessResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReportModelSuccess"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ReportModelSuccessRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ReportModelSuccessResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterAdminServiceMethodDescriptorSupplier("ReportModelSuccess"))
              .build();
        }
      }
    }
    return getReportModelSuccessMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.ListModelErrorsRequest,
      com.jervis.contracts.router.ListModelErrorsResponse> getListModelErrorsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListModelErrors",
      requestType = com.jervis.contracts.router.ListModelErrorsRequest.class,
      responseType = com.jervis.contracts.router.ListModelErrorsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.ListModelErrorsRequest,
      com.jervis.contracts.router.ListModelErrorsResponse> getListModelErrorsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.ListModelErrorsRequest, com.jervis.contracts.router.ListModelErrorsResponse> getListModelErrorsMethod;
    if ((getListModelErrorsMethod = RouterAdminServiceGrpc.getListModelErrorsMethod) == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        if ((getListModelErrorsMethod = RouterAdminServiceGrpc.getListModelErrorsMethod) == null) {
          RouterAdminServiceGrpc.getListModelErrorsMethod = getListModelErrorsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.ListModelErrorsRequest, com.jervis.contracts.router.ListModelErrorsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListModelErrors"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ListModelErrorsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ListModelErrorsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterAdminServiceMethodDescriptorSupplier("ListModelErrors"))
              .build();
        }
      }
    }
    return getListModelErrorsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.ListModelStatsRequest,
      com.jervis.contracts.router.ListModelStatsResponse> getListModelStatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListModelStats",
      requestType = com.jervis.contracts.router.ListModelStatsRequest.class,
      responseType = com.jervis.contracts.router.ListModelStatsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.ListModelStatsRequest,
      com.jervis.contracts.router.ListModelStatsResponse> getListModelStatsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.ListModelStatsRequest, com.jervis.contracts.router.ListModelStatsResponse> getListModelStatsMethod;
    if ((getListModelStatsMethod = RouterAdminServiceGrpc.getListModelStatsMethod) == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        if ((getListModelStatsMethod = RouterAdminServiceGrpc.getListModelStatsMethod) == null) {
          RouterAdminServiceGrpc.getListModelStatsMethod = getListModelStatsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.ListModelStatsRequest, com.jervis.contracts.router.ListModelStatsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListModelStats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ListModelStatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ListModelStatsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterAdminServiceMethodDescriptorSupplier("ListModelStats"))
              .build();
        }
      }
    }
    return getListModelStatsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.ResetModelErrorRequest,
      com.jervis.contracts.router.ResetModelErrorResponse> getResetModelErrorMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ResetModelError",
      requestType = com.jervis.contracts.router.ResetModelErrorRequest.class,
      responseType = com.jervis.contracts.router.ResetModelErrorResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.ResetModelErrorRequest,
      com.jervis.contracts.router.ResetModelErrorResponse> getResetModelErrorMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.ResetModelErrorRequest, com.jervis.contracts.router.ResetModelErrorResponse> getResetModelErrorMethod;
    if ((getResetModelErrorMethod = RouterAdminServiceGrpc.getResetModelErrorMethod) == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        if ((getResetModelErrorMethod = RouterAdminServiceGrpc.getResetModelErrorMethod) == null) {
          RouterAdminServiceGrpc.getResetModelErrorMethod = getResetModelErrorMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.ResetModelErrorRequest, com.jervis.contracts.router.ResetModelErrorResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ResetModelError"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ResetModelErrorRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ResetModelErrorResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterAdminServiceMethodDescriptorSupplier("ResetModelError"))
              .build();
        }
      }
    }
    return getResetModelErrorMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.TestModelRequest,
      com.jervis.contracts.router.TestModelResponse> getTestModelMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "TestModel",
      requestType = com.jervis.contracts.router.TestModelRequest.class,
      responseType = com.jervis.contracts.router.TestModelResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.TestModelRequest,
      com.jervis.contracts.router.TestModelResponse> getTestModelMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.TestModelRequest, com.jervis.contracts.router.TestModelResponse> getTestModelMethod;
    if ((getTestModelMethod = RouterAdminServiceGrpc.getTestModelMethod) == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        if ((getTestModelMethod = RouterAdminServiceGrpc.getTestModelMethod) == null) {
          RouterAdminServiceGrpc.getTestModelMethod = getTestModelMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.TestModelRequest, com.jervis.contracts.router.TestModelResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "TestModel"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.TestModelRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.TestModelResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterAdminServiceMethodDescriptorSupplier("TestModel"))
              .build();
        }
      }
    }
    return getTestModelMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.RateLimitsRequest,
      com.jervis.contracts.router.RateLimitsResponse> getGetRateLimitsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetRateLimits",
      requestType = com.jervis.contracts.router.RateLimitsRequest.class,
      responseType = com.jervis.contracts.router.RateLimitsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.RateLimitsRequest,
      com.jervis.contracts.router.RateLimitsResponse> getGetRateLimitsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.RateLimitsRequest, com.jervis.contracts.router.RateLimitsResponse> getGetRateLimitsMethod;
    if ((getGetRateLimitsMethod = RouterAdminServiceGrpc.getGetRateLimitsMethod) == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        if ((getGetRateLimitsMethod = RouterAdminServiceGrpc.getGetRateLimitsMethod) == null) {
          RouterAdminServiceGrpc.getGetRateLimitsMethod = getGetRateLimitsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.RateLimitsRequest, com.jervis.contracts.router.RateLimitsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetRateLimits"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.RateLimitsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.RateLimitsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterAdminServiceMethodDescriptorSupplier("GetRateLimits"))
              .build();
        }
      }
    }
    return getGetRateLimitsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.InvalidateClientTierRequest,
      com.jervis.contracts.router.InvalidateClientTierResponse> getInvalidateClientTierMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "InvalidateClientTier",
      requestType = com.jervis.contracts.router.InvalidateClientTierRequest.class,
      responseType = com.jervis.contracts.router.InvalidateClientTierResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.InvalidateClientTierRequest,
      com.jervis.contracts.router.InvalidateClientTierResponse> getInvalidateClientTierMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.InvalidateClientTierRequest, com.jervis.contracts.router.InvalidateClientTierResponse> getInvalidateClientTierMethod;
    if ((getInvalidateClientTierMethod = RouterAdminServiceGrpc.getInvalidateClientTierMethod) == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        if ((getInvalidateClientTierMethod = RouterAdminServiceGrpc.getInvalidateClientTierMethod) == null) {
          RouterAdminServiceGrpc.getInvalidateClientTierMethod = getInvalidateClientTierMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.InvalidateClientTierRequest, com.jervis.contracts.router.InvalidateClientTierResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "InvalidateClientTier"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.InvalidateClientTierRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.InvalidateClientTierResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterAdminServiceMethodDescriptorSupplier("InvalidateClientTier"))
              .build();
        }
      }
    }
    return getInvalidateClientTierMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RouterAdminServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouterAdminServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouterAdminServiceStub>() {
        @java.lang.Override
        public RouterAdminServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouterAdminServiceStub(channel, callOptions);
        }
      };
    return RouterAdminServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static RouterAdminServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouterAdminServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouterAdminServiceBlockingV2Stub>() {
        @java.lang.Override
        public RouterAdminServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouterAdminServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return RouterAdminServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RouterAdminServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouterAdminServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouterAdminServiceBlockingStub>() {
        @java.lang.Override
        public RouterAdminServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouterAdminServiceBlockingStub(channel, callOptions);
        }
      };
    return RouterAdminServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RouterAdminServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouterAdminServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouterAdminServiceFutureStub>() {
        @java.lang.Override
        public RouterAdminServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouterAdminServiceFutureStub(channel, callOptions);
        }
      };
    return RouterAdminServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * RouterAdminService exposes the `/router/admin/&#42;` and
   * `/router/internal/&#42;` surface of `service-ollama-router`. The transparent
   * Ollama-compatible endpoints (`/api/generate`, `/api/chat`, `/api/embeddings`)
   * stay REST because they are a vendor contract (Ollama API) — consumers
   * call them directly, no gRPC wrapper.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Max available context tokens for a given TierCap (used by the
     * orchestrator's context budgeter).
     * </pre>
     */
    default void getMaxContext(com.jervis.contracts.router.MaxContextRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.MaxContextResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMaxContextMethod(), responseObserver);
    }

    /**
     * <pre>
     * Report a provider error (400/500) so the router can disable the model
     * after N consecutive failures.
     * </pre>
     */
    default void reportModelError(com.jervis.contracts.router.ReportModelErrorRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ReportModelErrorResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReportModelErrorMethod(), responseObserver);
    }

    /**
     * <pre>
     * Report a successful call — resets error counter and records stats.
     * </pre>
     */
    default void reportModelSuccess(com.jervis.contracts.router.ReportModelSuccessRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ReportModelSuccessResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReportModelSuccessMethod(), responseObserver);
    }

    /**
     * <pre>
     * Snapshot of model error state — keyed by model_id.
     * </pre>
     */
    default void listModelErrors(com.jervis.contracts.router.ListModelErrorsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ListModelErrorsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListModelErrorsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Snapshot of usage statistics for every tracked model.
     * </pre>
     */
    default void listModelStats(com.jervis.contracts.router.ListModelStatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ListModelStatsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListModelStatsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Re-enable a previously-disabled model (UI action after manual testing).
     * </pre>
     */
    default void resetModelError(com.jervis.contracts.router.ResetModelErrorRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ResetModelErrorResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getResetModelErrorMethod(), responseObserver);
    }

    /**
     * <pre>
     * Send a tiny completion to an OpenRouter model to verify it responds.
     * </pre>
     */
    default void testModel(com.jervis.contracts.router.TestModelRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.TestModelResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTestModelMethod(), responseObserver);
    }

    /**
     * <pre>
     * OpenRouter rate-limit status per queue (FREE / PAID / PREMIUM).
     * </pre>
     */
    default void getRateLimits(com.jervis.contracts.router.RateLimitsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.RateLimitsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetRateLimitsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Invalidate the router's cached client tier after cloudModelPolicy
     * changes on the Kotlin server. Empty client_id = invalidate all.
     * </pre>
     */
    default void invalidateClientTier(com.jervis.contracts.router.InvalidateClientTierRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.InvalidateClientTierResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInvalidateClientTierMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RouterAdminService.
   * <pre>
   * RouterAdminService exposes the `/router/admin/&#42;` and
   * `/router/internal/&#42;` surface of `service-ollama-router`. The transparent
   * Ollama-compatible endpoints (`/api/generate`, `/api/chat`, `/api/embeddings`)
   * stay REST because they are a vendor contract (Ollama API) — consumers
   * call them directly, no gRPC wrapper.
   * </pre>
   */
  public static abstract class RouterAdminServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RouterAdminServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RouterAdminService.
   * <pre>
   * RouterAdminService exposes the `/router/admin/&#42;` and
   * `/router/internal/&#42;` surface of `service-ollama-router`. The transparent
   * Ollama-compatible endpoints (`/api/generate`, `/api/chat`, `/api/embeddings`)
   * stay REST because they are a vendor contract (Ollama API) — consumers
   * call them directly, no gRPC wrapper.
   * </pre>
   */
  public static final class RouterAdminServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RouterAdminServiceStub> {
    private RouterAdminServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouterAdminServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouterAdminServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Max available context tokens for a given TierCap (used by the
     * orchestrator's context budgeter).
     * </pre>
     */
    public void getMaxContext(com.jervis.contracts.router.MaxContextRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.MaxContextResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetMaxContextMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Report a provider error (400/500) so the router can disable the model
     * after N consecutive failures.
     * </pre>
     */
    public void reportModelError(com.jervis.contracts.router.ReportModelErrorRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ReportModelErrorResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReportModelErrorMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Report a successful call — resets error counter and records stats.
     * </pre>
     */
    public void reportModelSuccess(com.jervis.contracts.router.ReportModelSuccessRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ReportModelSuccessResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReportModelSuccessMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Snapshot of model error state — keyed by model_id.
     * </pre>
     */
    public void listModelErrors(com.jervis.contracts.router.ListModelErrorsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ListModelErrorsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListModelErrorsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Snapshot of usage statistics for every tracked model.
     * </pre>
     */
    public void listModelStats(com.jervis.contracts.router.ListModelStatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ListModelStatsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListModelStatsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Re-enable a previously-disabled model (UI action after manual testing).
     * </pre>
     */
    public void resetModelError(com.jervis.contracts.router.ResetModelErrorRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ResetModelErrorResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getResetModelErrorMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Send a tiny completion to an OpenRouter model to verify it responds.
     * </pre>
     */
    public void testModel(com.jervis.contracts.router.TestModelRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.TestModelResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTestModelMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * OpenRouter rate-limit status per queue (FREE / PAID / PREMIUM).
     * </pre>
     */
    public void getRateLimits(com.jervis.contracts.router.RateLimitsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.RateLimitsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetRateLimitsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Invalidate the router's cached client tier after cloudModelPolicy
     * changes on the Kotlin server. Empty client_id = invalidate all.
     * </pre>
     */
    public void invalidateClientTier(com.jervis.contracts.router.InvalidateClientTierRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.InvalidateClientTierResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInvalidateClientTierMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RouterAdminService.
   * <pre>
   * RouterAdminService exposes the `/router/admin/&#42;` and
   * `/router/internal/&#42;` surface of `service-ollama-router`. The transparent
   * Ollama-compatible endpoints (`/api/generate`, `/api/chat`, `/api/embeddings`)
   * stay REST because they are a vendor contract (Ollama API) — consumers
   * call them directly, no gRPC wrapper.
   * </pre>
   */
  public static final class RouterAdminServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<RouterAdminServiceBlockingV2Stub> {
    private RouterAdminServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouterAdminServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouterAdminServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Max available context tokens for a given TierCap (used by the
     * orchestrator's context budgeter).
     * </pre>
     */
    public com.jervis.contracts.router.MaxContextResponse getMaxContext(com.jervis.contracts.router.MaxContextRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetMaxContextMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Report a provider error (400/500) so the router can disable the model
     * after N consecutive failures.
     * </pre>
     */
    public com.jervis.contracts.router.ReportModelErrorResponse reportModelError(com.jervis.contracts.router.ReportModelErrorRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReportModelErrorMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Report a successful call — resets error counter and records stats.
     * </pre>
     */
    public com.jervis.contracts.router.ReportModelSuccessResponse reportModelSuccess(com.jervis.contracts.router.ReportModelSuccessRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReportModelSuccessMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Snapshot of model error state — keyed by model_id.
     * </pre>
     */
    public com.jervis.contracts.router.ListModelErrorsResponse listModelErrors(com.jervis.contracts.router.ListModelErrorsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListModelErrorsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Snapshot of usage statistics for every tracked model.
     * </pre>
     */
    public com.jervis.contracts.router.ListModelStatsResponse listModelStats(com.jervis.contracts.router.ListModelStatsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListModelStatsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Re-enable a previously-disabled model (UI action after manual testing).
     * </pre>
     */
    public com.jervis.contracts.router.ResetModelErrorResponse resetModelError(com.jervis.contracts.router.ResetModelErrorRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getResetModelErrorMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Send a tiny completion to an OpenRouter model to verify it responds.
     * </pre>
     */
    public com.jervis.contracts.router.TestModelResponse testModel(com.jervis.contracts.router.TestModelRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getTestModelMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * OpenRouter rate-limit status per queue (FREE / PAID / PREMIUM).
     * </pre>
     */
    public com.jervis.contracts.router.RateLimitsResponse getRateLimits(com.jervis.contracts.router.RateLimitsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetRateLimitsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Invalidate the router's cached client tier after cloudModelPolicy
     * changes on the Kotlin server. Empty client_id = invalidate all.
     * </pre>
     */
    public com.jervis.contracts.router.InvalidateClientTierResponse invalidateClientTier(com.jervis.contracts.router.InvalidateClientTierRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInvalidateClientTierMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service RouterAdminService.
   * <pre>
   * RouterAdminService exposes the `/router/admin/&#42;` and
   * `/router/internal/&#42;` surface of `service-ollama-router`. The transparent
   * Ollama-compatible endpoints (`/api/generate`, `/api/chat`, `/api/embeddings`)
   * stay REST because they are a vendor contract (Ollama API) — consumers
   * call them directly, no gRPC wrapper.
   * </pre>
   */
  public static final class RouterAdminServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RouterAdminServiceBlockingStub> {
    private RouterAdminServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouterAdminServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouterAdminServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Max available context tokens for a given TierCap (used by the
     * orchestrator's context budgeter).
     * </pre>
     */
    public com.jervis.contracts.router.MaxContextResponse getMaxContext(com.jervis.contracts.router.MaxContextRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetMaxContextMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Report a provider error (400/500) so the router can disable the model
     * after N consecutive failures.
     * </pre>
     */
    public com.jervis.contracts.router.ReportModelErrorResponse reportModelError(com.jervis.contracts.router.ReportModelErrorRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReportModelErrorMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Report a successful call — resets error counter and records stats.
     * </pre>
     */
    public com.jervis.contracts.router.ReportModelSuccessResponse reportModelSuccess(com.jervis.contracts.router.ReportModelSuccessRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReportModelSuccessMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Snapshot of model error state — keyed by model_id.
     * </pre>
     */
    public com.jervis.contracts.router.ListModelErrorsResponse listModelErrors(com.jervis.contracts.router.ListModelErrorsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListModelErrorsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Snapshot of usage statistics for every tracked model.
     * </pre>
     */
    public com.jervis.contracts.router.ListModelStatsResponse listModelStats(com.jervis.contracts.router.ListModelStatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListModelStatsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Re-enable a previously-disabled model (UI action after manual testing).
     * </pre>
     */
    public com.jervis.contracts.router.ResetModelErrorResponse resetModelError(com.jervis.contracts.router.ResetModelErrorRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getResetModelErrorMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Send a tiny completion to an OpenRouter model to verify it responds.
     * </pre>
     */
    public com.jervis.contracts.router.TestModelResponse testModel(com.jervis.contracts.router.TestModelRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTestModelMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * OpenRouter rate-limit status per queue (FREE / PAID / PREMIUM).
     * </pre>
     */
    public com.jervis.contracts.router.RateLimitsResponse getRateLimits(com.jervis.contracts.router.RateLimitsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetRateLimitsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Invalidate the router's cached client tier after cloudModelPolicy
     * changes on the Kotlin server. Empty client_id = invalidate all.
     * </pre>
     */
    public com.jervis.contracts.router.InvalidateClientTierResponse invalidateClientTier(com.jervis.contracts.router.InvalidateClientTierRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInvalidateClientTierMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RouterAdminService.
   * <pre>
   * RouterAdminService exposes the `/router/admin/&#42;` and
   * `/router/internal/&#42;` surface of `service-ollama-router`. The transparent
   * Ollama-compatible endpoints (`/api/generate`, `/api/chat`, `/api/embeddings`)
   * stay REST because they are a vendor contract (Ollama API) — consumers
   * call them directly, no gRPC wrapper.
   * </pre>
   */
  public static final class RouterAdminServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RouterAdminServiceFutureStub> {
    private RouterAdminServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouterAdminServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouterAdminServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Max available context tokens for a given TierCap (used by the
     * orchestrator's context budgeter).
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.MaxContextResponse> getMaxContext(
        com.jervis.contracts.router.MaxContextRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetMaxContextMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Report a provider error (400/500) so the router can disable the model
     * after N consecutive failures.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.ReportModelErrorResponse> reportModelError(
        com.jervis.contracts.router.ReportModelErrorRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReportModelErrorMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Report a successful call — resets error counter and records stats.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.ReportModelSuccessResponse> reportModelSuccess(
        com.jervis.contracts.router.ReportModelSuccessRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReportModelSuccessMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Snapshot of model error state — keyed by model_id.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.ListModelErrorsResponse> listModelErrors(
        com.jervis.contracts.router.ListModelErrorsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListModelErrorsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Snapshot of usage statistics for every tracked model.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.ListModelStatsResponse> listModelStats(
        com.jervis.contracts.router.ListModelStatsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListModelStatsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Re-enable a previously-disabled model (UI action after manual testing).
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.ResetModelErrorResponse> resetModelError(
        com.jervis.contracts.router.ResetModelErrorRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getResetModelErrorMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Send a tiny completion to an OpenRouter model to verify it responds.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.TestModelResponse> testModel(
        com.jervis.contracts.router.TestModelRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTestModelMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * OpenRouter rate-limit status per queue (FREE / PAID / PREMIUM).
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.RateLimitsResponse> getRateLimits(
        com.jervis.contracts.router.RateLimitsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetRateLimitsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Invalidate the router's cached client tier after cloudModelPolicy
     * changes on the Kotlin server. Empty client_id = invalidate all.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.InvalidateClientTierResponse> invalidateClientTier(
        com.jervis.contracts.router.InvalidateClientTierRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInvalidateClientTierMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_MAX_CONTEXT = 0;
  private static final int METHODID_REPORT_MODEL_ERROR = 1;
  private static final int METHODID_REPORT_MODEL_SUCCESS = 2;
  private static final int METHODID_LIST_MODEL_ERRORS = 3;
  private static final int METHODID_LIST_MODEL_STATS = 4;
  private static final int METHODID_RESET_MODEL_ERROR = 5;
  private static final int METHODID_TEST_MODEL = 6;
  private static final int METHODID_GET_RATE_LIMITS = 7;
  private static final int METHODID_INVALIDATE_CLIENT_TIER = 8;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_MAX_CONTEXT:
          serviceImpl.getMaxContext((com.jervis.contracts.router.MaxContextRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.MaxContextResponse>) responseObserver);
          break;
        case METHODID_REPORT_MODEL_ERROR:
          serviceImpl.reportModelError((com.jervis.contracts.router.ReportModelErrorRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.ReportModelErrorResponse>) responseObserver);
          break;
        case METHODID_REPORT_MODEL_SUCCESS:
          serviceImpl.reportModelSuccess((com.jervis.contracts.router.ReportModelSuccessRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.ReportModelSuccessResponse>) responseObserver);
          break;
        case METHODID_LIST_MODEL_ERRORS:
          serviceImpl.listModelErrors((com.jervis.contracts.router.ListModelErrorsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.ListModelErrorsResponse>) responseObserver);
          break;
        case METHODID_LIST_MODEL_STATS:
          serviceImpl.listModelStats((com.jervis.contracts.router.ListModelStatsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.ListModelStatsResponse>) responseObserver);
          break;
        case METHODID_RESET_MODEL_ERROR:
          serviceImpl.resetModelError((com.jervis.contracts.router.ResetModelErrorRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.ResetModelErrorResponse>) responseObserver);
          break;
        case METHODID_TEST_MODEL:
          serviceImpl.testModel((com.jervis.contracts.router.TestModelRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.TestModelResponse>) responseObserver);
          break;
        case METHODID_GET_RATE_LIMITS:
          serviceImpl.getRateLimits((com.jervis.contracts.router.RateLimitsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.RateLimitsResponse>) responseObserver);
          break;
        case METHODID_INVALIDATE_CLIENT_TIER:
          serviceImpl.invalidateClientTier((com.jervis.contracts.router.InvalidateClientTierRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.InvalidateClientTierResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getGetMaxContextMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.MaxContextRequest,
              com.jervis.contracts.router.MaxContextResponse>(
                service, METHODID_GET_MAX_CONTEXT)))
        .addMethod(
          getReportModelErrorMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.ReportModelErrorRequest,
              com.jervis.contracts.router.ReportModelErrorResponse>(
                service, METHODID_REPORT_MODEL_ERROR)))
        .addMethod(
          getReportModelSuccessMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.ReportModelSuccessRequest,
              com.jervis.contracts.router.ReportModelSuccessResponse>(
                service, METHODID_REPORT_MODEL_SUCCESS)))
        .addMethod(
          getListModelErrorsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.ListModelErrorsRequest,
              com.jervis.contracts.router.ListModelErrorsResponse>(
                service, METHODID_LIST_MODEL_ERRORS)))
        .addMethod(
          getListModelStatsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.ListModelStatsRequest,
              com.jervis.contracts.router.ListModelStatsResponse>(
                service, METHODID_LIST_MODEL_STATS)))
        .addMethod(
          getResetModelErrorMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.ResetModelErrorRequest,
              com.jervis.contracts.router.ResetModelErrorResponse>(
                service, METHODID_RESET_MODEL_ERROR)))
        .addMethod(
          getTestModelMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.TestModelRequest,
              com.jervis.contracts.router.TestModelResponse>(
                service, METHODID_TEST_MODEL)))
        .addMethod(
          getGetRateLimitsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.RateLimitsRequest,
              com.jervis.contracts.router.RateLimitsResponse>(
                service, METHODID_GET_RATE_LIMITS)))
        .addMethod(
          getInvalidateClientTierMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.InvalidateClientTierRequest,
              com.jervis.contracts.router.InvalidateClientTierResponse>(
                service, METHODID_INVALIDATE_CLIENT_TIER)))
        .build();
  }

  private static abstract class RouterAdminServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RouterAdminServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.router.RouterAdminProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RouterAdminService");
    }
  }

  private static final class RouterAdminServiceFileDescriptorSupplier
      extends RouterAdminServiceBaseDescriptorSupplier {
    RouterAdminServiceFileDescriptorSupplier() {}
  }

  private static final class RouterAdminServiceMethodDescriptorSupplier
      extends RouterAdminServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RouterAdminServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (RouterAdminServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RouterAdminServiceFileDescriptorSupplier())
              .addMethod(getGetMaxContextMethod())
              .addMethod(getReportModelErrorMethod())
              .addMethod(getReportModelSuccessMethod())
              .addMethod(getListModelErrorsMethod())
              .addMethod(getListModelStatsMethod())
              .addMethod(getResetModelErrorMethod())
              .addMethod(getTestModelMethod())
              .addMethod(getGetRateLimitsMethod())
              .addMethod(getInvalidateClientTierMethod())
              .build();
        }
      }
    }
    return result;
  }
}
