package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerOrchestratorProgressService is the reverse-callback surface the
 * Python orchestrator (and the correction agent) use to push progress,
 * status and graph updates into the Kotlin server. All calls are
 * fire-and-forget from Python's perspective — the server acks with
 * `ok=true` and dispatches async fan-out to NotificationRpc / chat streams.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerOrchestratorProgressServiceGrpc {

  private ServerOrchestratorProgressServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerOrchestratorProgressService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.OrchestratorProgressRequest,
      com.jervis.contracts.server.AckResponse> getOrchestratorProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "OrchestratorProgress",
      requestType = com.jervis.contracts.server.OrchestratorProgressRequest.class,
      responseType = com.jervis.contracts.server.AckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.OrchestratorProgressRequest,
      com.jervis.contracts.server.AckResponse> getOrchestratorProgressMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.OrchestratorProgressRequest, com.jervis.contracts.server.AckResponse> getOrchestratorProgressMethod;
    if ((getOrchestratorProgressMethod = ServerOrchestratorProgressServiceGrpc.getOrchestratorProgressMethod) == null) {
      synchronized (ServerOrchestratorProgressServiceGrpc.class) {
        if ((getOrchestratorProgressMethod = ServerOrchestratorProgressServiceGrpc.getOrchestratorProgressMethod) == null) {
          ServerOrchestratorProgressServiceGrpc.getOrchestratorProgressMethod = getOrchestratorProgressMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.OrchestratorProgressRequest, com.jervis.contracts.server.AckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "OrchestratorProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.OrchestratorProgressRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerOrchestratorProgressServiceMethodDescriptorSupplier("OrchestratorProgress"))
              .build();
        }
      }
    }
    return getOrchestratorProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.OrchestratorStatusRequest,
      com.jervis.contracts.server.AckResponse> getOrchestratorStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "OrchestratorStatus",
      requestType = com.jervis.contracts.server.OrchestratorStatusRequest.class,
      responseType = com.jervis.contracts.server.AckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.OrchestratorStatusRequest,
      com.jervis.contracts.server.AckResponse> getOrchestratorStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.OrchestratorStatusRequest, com.jervis.contracts.server.AckResponse> getOrchestratorStatusMethod;
    if ((getOrchestratorStatusMethod = ServerOrchestratorProgressServiceGrpc.getOrchestratorStatusMethod) == null) {
      synchronized (ServerOrchestratorProgressServiceGrpc.class) {
        if ((getOrchestratorStatusMethod = ServerOrchestratorProgressServiceGrpc.getOrchestratorStatusMethod) == null) {
          ServerOrchestratorProgressServiceGrpc.getOrchestratorStatusMethod = getOrchestratorStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.OrchestratorStatusRequest, com.jervis.contracts.server.AckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "OrchestratorStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.OrchestratorStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerOrchestratorProgressServiceMethodDescriptorSupplier("OrchestratorStatus"))
              .build();
        }
      }
    }
    return getOrchestratorStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.QualificationDoneRequest,
      com.jervis.contracts.server.AckResponse> getQualificationDoneMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "QualificationDone",
      requestType = com.jervis.contracts.server.QualificationDoneRequest.class,
      responseType = com.jervis.contracts.server.AckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.QualificationDoneRequest,
      com.jervis.contracts.server.AckResponse> getQualificationDoneMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.QualificationDoneRequest, com.jervis.contracts.server.AckResponse> getQualificationDoneMethod;
    if ((getQualificationDoneMethod = ServerOrchestratorProgressServiceGrpc.getQualificationDoneMethod) == null) {
      synchronized (ServerOrchestratorProgressServiceGrpc.class) {
        if ((getQualificationDoneMethod = ServerOrchestratorProgressServiceGrpc.getQualificationDoneMethod) == null) {
          ServerOrchestratorProgressServiceGrpc.getQualificationDoneMethod = getQualificationDoneMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.QualificationDoneRequest, com.jervis.contracts.server.AckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "QualificationDone"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.QualificationDoneRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerOrchestratorProgressServiceMethodDescriptorSupplier("QualificationDone"))
              .build();
        }
      }
    }
    return getQualificationDoneMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.MemoryGraphChangedRequest,
      com.jervis.contracts.server.AckResponse> getMemoryGraphChangedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MemoryGraphChanged",
      requestType = com.jervis.contracts.server.MemoryGraphChangedRequest.class,
      responseType = com.jervis.contracts.server.AckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.MemoryGraphChangedRequest,
      com.jervis.contracts.server.AckResponse> getMemoryGraphChangedMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.MemoryGraphChangedRequest, com.jervis.contracts.server.AckResponse> getMemoryGraphChangedMethod;
    if ((getMemoryGraphChangedMethod = ServerOrchestratorProgressServiceGrpc.getMemoryGraphChangedMethod) == null) {
      synchronized (ServerOrchestratorProgressServiceGrpc.class) {
        if ((getMemoryGraphChangedMethod = ServerOrchestratorProgressServiceGrpc.getMemoryGraphChangedMethod) == null) {
          ServerOrchestratorProgressServiceGrpc.getMemoryGraphChangedMethod = getMemoryGraphChangedMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.MemoryGraphChangedRequest, com.jervis.contracts.server.AckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MemoryGraphChanged"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.MemoryGraphChangedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerOrchestratorProgressServiceMethodDescriptorSupplier("MemoryGraphChanged"))
              .build();
        }
      }
    }
    return getMemoryGraphChangedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ThinkingGraphUpdateRequest,
      com.jervis.contracts.server.AckResponse> getThinkingGraphUpdateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ThinkingGraphUpdate",
      requestType = com.jervis.contracts.server.ThinkingGraphUpdateRequest.class,
      responseType = com.jervis.contracts.server.AckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ThinkingGraphUpdateRequest,
      com.jervis.contracts.server.AckResponse> getThinkingGraphUpdateMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ThinkingGraphUpdateRequest, com.jervis.contracts.server.AckResponse> getThinkingGraphUpdateMethod;
    if ((getThinkingGraphUpdateMethod = ServerOrchestratorProgressServiceGrpc.getThinkingGraphUpdateMethod) == null) {
      synchronized (ServerOrchestratorProgressServiceGrpc.class) {
        if ((getThinkingGraphUpdateMethod = ServerOrchestratorProgressServiceGrpc.getThinkingGraphUpdateMethod) == null) {
          ServerOrchestratorProgressServiceGrpc.getThinkingGraphUpdateMethod = getThinkingGraphUpdateMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ThinkingGraphUpdateRequest, com.jervis.contracts.server.AckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ThinkingGraphUpdate"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ThinkingGraphUpdateRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerOrchestratorProgressServiceMethodDescriptorSupplier("ThinkingGraphUpdate"))
              .build();
        }
      }
    }
    return getThinkingGraphUpdateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CorrectionProgressRequest,
      com.jervis.contracts.server.AckResponse> getCorrectionProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CorrectionProgress",
      requestType = com.jervis.contracts.server.CorrectionProgressRequest.class,
      responseType = com.jervis.contracts.server.AckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CorrectionProgressRequest,
      com.jervis.contracts.server.AckResponse> getCorrectionProgressMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CorrectionProgressRequest, com.jervis.contracts.server.AckResponse> getCorrectionProgressMethod;
    if ((getCorrectionProgressMethod = ServerOrchestratorProgressServiceGrpc.getCorrectionProgressMethod) == null) {
      synchronized (ServerOrchestratorProgressServiceGrpc.class) {
        if ((getCorrectionProgressMethod = ServerOrchestratorProgressServiceGrpc.getCorrectionProgressMethod) == null) {
          ServerOrchestratorProgressServiceGrpc.getCorrectionProgressMethod = getCorrectionProgressMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CorrectionProgressRequest, com.jervis.contracts.server.AckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CorrectionProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CorrectionProgressRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerOrchestratorProgressServiceMethodDescriptorSupplier("CorrectionProgress"))
              .build();
        }
      }
    }
    return getCorrectionProgressMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerOrchestratorProgressServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerOrchestratorProgressServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerOrchestratorProgressServiceStub>() {
        @java.lang.Override
        public ServerOrchestratorProgressServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerOrchestratorProgressServiceStub(channel, callOptions);
        }
      };
    return ServerOrchestratorProgressServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerOrchestratorProgressServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerOrchestratorProgressServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerOrchestratorProgressServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerOrchestratorProgressServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerOrchestratorProgressServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerOrchestratorProgressServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerOrchestratorProgressServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerOrchestratorProgressServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerOrchestratorProgressServiceBlockingStub>() {
        @java.lang.Override
        public ServerOrchestratorProgressServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerOrchestratorProgressServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerOrchestratorProgressServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerOrchestratorProgressServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerOrchestratorProgressServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerOrchestratorProgressServiceFutureStub>() {
        @java.lang.Override
        public ServerOrchestratorProgressServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerOrchestratorProgressServiceFutureStub(channel, callOptions);
        }
      };
    return ServerOrchestratorProgressServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerOrchestratorProgressService is the reverse-callback surface the
   * Python orchestrator (and the correction agent) use to push progress,
   * status and graph updates into the Kotlin server. All calls are
   * fire-and-forget from Python's perspective — the server acks with
   * `ok=true` and dispatches async fan-out to NotificationRpc / chat streams.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void orchestratorProgress(com.jervis.contracts.server.OrchestratorProgressRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getOrchestratorProgressMethod(), responseObserver);
    }

    /**
     */
    default void orchestratorStatus(com.jervis.contracts.server.OrchestratorStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getOrchestratorStatusMethod(), responseObserver);
    }

    /**
     */
    default void qualificationDone(com.jervis.contracts.server.QualificationDoneRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQualificationDoneMethod(), responseObserver);
    }

    /**
     */
    default void memoryGraphChanged(com.jervis.contracts.server.MemoryGraphChangedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMemoryGraphChangedMethod(), responseObserver);
    }

    /**
     */
    default void thinkingGraphUpdate(com.jervis.contracts.server.ThinkingGraphUpdateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getThinkingGraphUpdateMethod(), responseObserver);
    }

    /**
     */
    default void correctionProgress(com.jervis.contracts.server.CorrectionProgressRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCorrectionProgressMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerOrchestratorProgressService.
   * <pre>
   * ServerOrchestratorProgressService is the reverse-callback surface the
   * Python orchestrator (and the correction agent) use to push progress,
   * status and graph updates into the Kotlin server. All calls are
   * fire-and-forget from Python's perspective — the server acks with
   * `ok=true` and dispatches async fan-out to NotificationRpc / chat streams.
   * </pre>
   */
  public static abstract class ServerOrchestratorProgressServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerOrchestratorProgressServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerOrchestratorProgressService.
   * <pre>
   * ServerOrchestratorProgressService is the reverse-callback surface the
   * Python orchestrator (and the correction agent) use to push progress,
   * status and graph updates into the Kotlin server. All calls are
   * fire-and-forget from Python's perspective — the server acks with
   * `ok=true` and dispatches async fan-out to NotificationRpc / chat streams.
   * </pre>
   */
  public static final class ServerOrchestratorProgressServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerOrchestratorProgressServiceStub> {
    private ServerOrchestratorProgressServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerOrchestratorProgressServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerOrchestratorProgressServiceStub(channel, callOptions);
    }

    /**
     */
    public void orchestratorProgress(com.jervis.contracts.server.OrchestratorProgressRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getOrchestratorProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void orchestratorStatus(com.jervis.contracts.server.OrchestratorStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getOrchestratorStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void qualificationDone(com.jervis.contracts.server.QualificationDoneRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getQualificationDoneMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void memoryGraphChanged(com.jervis.contracts.server.MemoryGraphChangedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMemoryGraphChangedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void thinkingGraphUpdate(com.jervis.contracts.server.ThinkingGraphUpdateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getThinkingGraphUpdateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void correctionProgress(com.jervis.contracts.server.CorrectionProgressRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCorrectionProgressMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerOrchestratorProgressService.
   * <pre>
   * ServerOrchestratorProgressService is the reverse-callback surface the
   * Python orchestrator (and the correction agent) use to push progress,
   * status and graph updates into the Kotlin server. All calls are
   * fire-and-forget from Python's perspective — the server acks with
   * `ok=true` and dispatches async fan-out to NotificationRpc / chat streams.
   * </pre>
   */
  public static final class ServerOrchestratorProgressServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerOrchestratorProgressServiceBlockingV2Stub> {
    private ServerOrchestratorProgressServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerOrchestratorProgressServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerOrchestratorProgressServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse orchestratorProgress(com.jervis.contracts.server.OrchestratorProgressRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getOrchestratorProgressMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse orchestratorStatus(com.jervis.contracts.server.OrchestratorStatusRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getOrchestratorStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse qualificationDone(com.jervis.contracts.server.QualificationDoneRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getQualificationDoneMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse memoryGraphChanged(com.jervis.contracts.server.MemoryGraphChangedRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getMemoryGraphChangedMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse thinkingGraphUpdate(com.jervis.contracts.server.ThinkingGraphUpdateRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getThinkingGraphUpdateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse correctionProgress(com.jervis.contracts.server.CorrectionProgressRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCorrectionProgressMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerOrchestratorProgressService.
   * <pre>
   * ServerOrchestratorProgressService is the reverse-callback surface the
   * Python orchestrator (and the correction agent) use to push progress,
   * status and graph updates into the Kotlin server. All calls are
   * fire-and-forget from Python's perspective — the server acks with
   * `ok=true` and dispatches async fan-out to NotificationRpc / chat streams.
   * </pre>
   */
  public static final class ServerOrchestratorProgressServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerOrchestratorProgressServiceBlockingStub> {
    private ServerOrchestratorProgressServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerOrchestratorProgressServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerOrchestratorProgressServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse orchestratorProgress(com.jervis.contracts.server.OrchestratorProgressRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getOrchestratorProgressMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse orchestratorStatus(com.jervis.contracts.server.OrchestratorStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getOrchestratorStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse qualificationDone(com.jervis.contracts.server.QualificationDoneRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQualificationDoneMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse memoryGraphChanged(com.jervis.contracts.server.MemoryGraphChangedRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMemoryGraphChangedMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse thinkingGraphUpdate(com.jervis.contracts.server.ThinkingGraphUpdateRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getThinkingGraphUpdateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse correctionProgress(com.jervis.contracts.server.CorrectionProgressRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCorrectionProgressMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerOrchestratorProgressService.
   * <pre>
   * ServerOrchestratorProgressService is the reverse-callback surface the
   * Python orchestrator (and the correction agent) use to push progress,
   * status and graph updates into the Kotlin server. All calls are
   * fire-and-forget from Python's perspective — the server acks with
   * `ok=true` and dispatches async fan-out to NotificationRpc / chat streams.
   * </pre>
   */
  public static final class ServerOrchestratorProgressServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerOrchestratorProgressServiceFutureStub> {
    private ServerOrchestratorProgressServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerOrchestratorProgressServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerOrchestratorProgressServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AckResponse> orchestratorProgress(
        com.jervis.contracts.server.OrchestratorProgressRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getOrchestratorProgressMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AckResponse> orchestratorStatus(
        com.jervis.contracts.server.OrchestratorStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getOrchestratorStatusMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AckResponse> qualificationDone(
        com.jervis.contracts.server.QualificationDoneRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getQualificationDoneMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AckResponse> memoryGraphChanged(
        com.jervis.contracts.server.MemoryGraphChangedRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getMemoryGraphChangedMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AckResponse> thinkingGraphUpdate(
        com.jervis.contracts.server.ThinkingGraphUpdateRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getThinkingGraphUpdateMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AckResponse> correctionProgress(
        com.jervis.contracts.server.CorrectionProgressRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCorrectionProgressMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_ORCHESTRATOR_PROGRESS = 0;
  private static final int METHODID_ORCHESTRATOR_STATUS = 1;
  private static final int METHODID_QUALIFICATION_DONE = 2;
  private static final int METHODID_MEMORY_GRAPH_CHANGED = 3;
  private static final int METHODID_THINKING_GRAPH_UPDATE = 4;
  private static final int METHODID_CORRECTION_PROGRESS = 5;

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
        case METHODID_ORCHESTRATOR_PROGRESS:
          serviceImpl.orchestratorProgress((com.jervis.contracts.server.OrchestratorProgressRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse>) responseObserver);
          break;
        case METHODID_ORCHESTRATOR_STATUS:
          serviceImpl.orchestratorStatus((com.jervis.contracts.server.OrchestratorStatusRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse>) responseObserver);
          break;
        case METHODID_QUALIFICATION_DONE:
          serviceImpl.qualificationDone((com.jervis.contracts.server.QualificationDoneRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse>) responseObserver);
          break;
        case METHODID_MEMORY_GRAPH_CHANGED:
          serviceImpl.memoryGraphChanged((com.jervis.contracts.server.MemoryGraphChangedRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse>) responseObserver);
          break;
        case METHODID_THINKING_GRAPH_UPDATE:
          serviceImpl.thinkingGraphUpdate((com.jervis.contracts.server.ThinkingGraphUpdateRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse>) responseObserver);
          break;
        case METHODID_CORRECTION_PROGRESS:
          serviceImpl.correctionProgress((com.jervis.contracts.server.CorrectionProgressRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse>) responseObserver);
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
          getOrchestratorProgressMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.OrchestratorProgressRequest,
              com.jervis.contracts.server.AckResponse>(
                service, METHODID_ORCHESTRATOR_PROGRESS)))
        .addMethod(
          getOrchestratorStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.OrchestratorStatusRequest,
              com.jervis.contracts.server.AckResponse>(
                service, METHODID_ORCHESTRATOR_STATUS)))
        .addMethod(
          getQualificationDoneMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.QualificationDoneRequest,
              com.jervis.contracts.server.AckResponse>(
                service, METHODID_QUALIFICATION_DONE)))
        .addMethod(
          getMemoryGraphChangedMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.MemoryGraphChangedRequest,
              com.jervis.contracts.server.AckResponse>(
                service, METHODID_MEMORY_GRAPH_CHANGED)))
        .addMethod(
          getThinkingGraphUpdateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ThinkingGraphUpdateRequest,
              com.jervis.contracts.server.AckResponse>(
                service, METHODID_THINKING_GRAPH_UPDATE)))
        .addMethod(
          getCorrectionProgressMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CorrectionProgressRequest,
              com.jervis.contracts.server.AckResponse>(
                service, METHODID_CORRECTION_PROGRESS)))
        .build();
  }

  private static abstract class ServerOrchestratorProgressServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerOrchestratorProgressServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerOrchestratorProgressProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerOrchestratorProgressService");
    }
  }

  private static final class ServerOrchestratorProgressServiceFileDescriptorSupplier
      extends ServerOrchestratorProgressServiceBaseDescriptorSupplier {
    ServerOrchestratorProgressServiceFileDescriptorSupplier() {}
  }

  private static final class ServerOrchestratorProgressServiceMethodDescriptorSupplier
      extends ServerOrchestratorProgressServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerOrchestratorProgressServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerOrchestratorProgressServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerOrchestratorProgressServiceFileDescriptorSupplier())
              .addMethod(getOrchestratorProgressMethod())
              .addMethod(getOrchestratorStatusMethod())
              .addMethod(getQualificationDoneMethod())
              .addMethod(getMemoryGraphChangedMethod())
              .addMethod(getThinkingGraphUpdateMethod())
              .addMethod(getCorrectionProgressMethod())
              .build();
        }
      }
    }
    return result;
  }
}
