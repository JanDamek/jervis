package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorControlService — thread-level control RPCs over the
 * LangGraph-backed orchestrator. Long-running orchestration events flow
 * over the KbProgress / OrchestratorProgress callbacks already migrated
 * in Phase 1; this service covers the short-payload control surface.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorControlServiceGrpc {

  private OrchestratorControlServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorControlService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.HealthRequest,
      com.jervis.contracts.orchestrator.HealthResponse> getHealthMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Health",
      requestType = com.jervis.contracts.orchestrator.HealthRequest.class,
      responseType = com.jervis.contracts.orchestrator.HealthResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.HealthRequest,
      com.jervis.contracts.orchestrator.HealthResponse> getHealthMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.HealthRequest, com.jervis.contracts.orchestrator.HealthResponse> getHealthMethod;
    if ((getHealthMethod = OrchestratorControlServiceGrpc.getHealthMethod) == null) {
      synchronized (OrchestratorControlServiceGrpc.class) {
        if ((getHealthMethod = OrchestratorControlServiceGrpc.getHealthMethod) == null) {
          OrchestratorControlServiceGrpc.getHealthMethod = getHealthMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.HealthRequest, com.jervis.contracts.orchestrator.HealthResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Health"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.HealthRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.HealthResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorControlServiceMethodDescriptorSupplier("Health"))
              .build();
        }
      }
    }
    return getHealthMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StatusRequest,
      com.jervis.contracts.orchestrator.StatusResponse> getGetStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetStatus",
      requestType = com.jervis.contracts.orchestrator.StatusRequest.class,
      responseType = com.jervis.contracts.orchestrator.StatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StatusRequest,
      com.jervis.contracts.orchestrator.StatusResponse> getGetStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StatusRequest, com.jervis.contracts.orchestrator.StatusResponse> getGetStatusMethod;
    if ((getGetStatusMethod = OrchestratorControlServiceGrpc.getGetStatusMethod) == null) {
      synchronized (OrchestratorControlServiceGrpc.class) {
        if ((getGetStatusMethod = OrchestratorControlServiceGrpc.getGetStatusMethod) == null) {
          OrchestratorControlServiceGrpc.getGetStatusMethod = getGetStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.StatusRequest, com.jervis.contracts.orchestrator.StatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.StatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.StatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorControlServiceMethodDescriptorSupplier("GetStatus"))
              .build();
        }
      }
    }
    return getGetStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ApproveRequest,
      com.jervis.contracts.orchestrator.ApproveAck> getApproveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Approve",
      requestType = com.jervis.contracts.orchestrator.ApproveRequest.class,
      responseType = com.jervis.contracts.orchestrator.ApproveAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ApproveRequest,
      com.jervis.contracts.orchestrator.ApproveAck> getApproveMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ApproveRequest, com.jervis.contracts.orchestrator.ApproveAck> getApproveMethod;
    if ((getApproveMethod = OrchestratorControlServiceGrpc.getApproveMethod) == null) {
      synchronized (OrchestratorControlServiceGrpc.class) {
        if ((getApproveMethod = OrchestratorControlServiceGrpc.getApproveMethod) == null) {
          OrchestratorControlServiceGrpc.getApproveMethod = getApproveMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.ApproveRequest, com.jervis.contracts.orchestrator.ApproveAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Approve"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ApproveRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ApproveAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorControlServiceMethodDescriptorSupplier("Approve"))
              .build();
        }
      }
    }
    return getApproveMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ThreadRequest,
      com.jervis.contracts.orchestrator.CancelAck> getCancelMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Cancel",
      requestType = com.jervis.contracts.orchestrator.ThreadRequest.class,
      responseType = com.jervis.contracts.orchestrator.CancelAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ThreadRequest,
      com.jervis.contracts.orchestrator.CancelAck> getCancelMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ThreadRequest, com.jervis.contracts.orchestrator.CancelAck> getCancelMethod;
    if ((getCancelMethod = OrchestratorControlServiceGrpc.getCancelMethod) == null) {
      synchronized (OrchestratorControlServiceGrpc.class) {
        if ((getCancelMethod = OrchestratorControlServiceGrpc.getCancelMethod) == null) {
          OrchestratorControlServiceGrpc.getCancelMethod = getCancelMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.ThreadRequest, com.jervis.contracts.orchestrator.CancelAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Cancel"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ThreadRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.CancelAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorControlServiceMethodDescriptorSupplier("Cancel"))
              .build();
        }
      }
    }
    return getCancelMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ThreadRequest,
      com.jervis.contracts.orchestrator.InterruptAck> getInterruptMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Interrupt",
      requestType = com.jervis.contracts.orchestrator.ThreadRequest.class,
      responseType = com.jervis.contracts.orchestrator.InterruptAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ThreadRequest,
      com.jervis.contracts.orchestrator.InterruptAck> getInterruptMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ThreadRequest, com.jervis.contracts.orchestrator.InterruptAck> getInterruptMethod;
    if ((getInterruptMethod = OrchestratorControlServiceGrpc.getInterruptMethod) == null) {
      synchronized (OrchestratorControlServiceGrpc.class) {
        if ((getInterruptMethod = OrchestratorControlServiceGrpc.getInterruptMethod) == null) {
          OrchestratorControlServiceGrpc.getInterruptMethod = getInterruptMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.ThreadRequest, com.jervis.contracts.orchestrator.InterruptAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Interrupt"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ThreadRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.InterruptAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorControlServiceMethodDescriptorSupplier("Interrupt"))
              .build();
        }
      }
    }
    return getInterruptMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorControlServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorControlServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorControlServiceStub>() {
        @java.lang.Override
        public OrchestratorControlServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorControlServiceStub(channel, callOptions);
        }
      };
    return OrchestratorControlServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static OrchestratorControlServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorControlServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorControlServiceBlockingV2Stub>() {
        @java.lang.Override
        public OrchestratorControlServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorControlServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return OrchestratorControlServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorControlServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorControlServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorControlServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorControlServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorControlServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorControlServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorControlServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorControlServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorControlServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorControlServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorControlServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorControlServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorControlService — thread-level control RPCs over the
   * LangGraph-backed orchestrator. Long-running orchestration events flow
   * over the KbProgress / OrchestratorProgress callbacks already migrated
   * in Phase 1; this service covers the short-payload control surface.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void health(com.jervis.contracts.orchestrator.HealthRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.HealthResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHealthMethod(), responseObserver);
    }

    /**
     */
    default void getStatus(com.jervis.contracts.orchestrator.StatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetStatusMethod(), responseObserver);
    }

    /**
     */
    default void approve(com.jervis.contracts.orchestrator.ApproveRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ApproveAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getApproveMethod(), responseObserver);
    }

    /**
     */
    default void cancel(com.jervis.contracts.orchestrator.ThreadRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.CancelAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCancelMethod(), responseObserver);
    }

    /**
     * <pre>
     * Resume helper left out of the initial surface — no Kotlin consumer
     * dials it today (Approve already handles the resume-after-interrupt
     * flow). Add back if a new caller needs a dedicated resume RPC.
     * </pre>
     */
    default void interrupt(com.jervis.contracts.orchestrator.ThreadRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.InterruptAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInterruptMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorControlService.
   * <pre>
   * OrchestratorControlService — thread-level control RPCs over the
   * LangGraph-backed orchestrator. Long-running orchestration events flow
   * over the KbProgress / OrchestratorProgress callbacks already migrated
   * in Phase 1; this service covers the short-payload control surface.
   * </pre>
   */
  public static abstract class OrchestratorControlServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorControlServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorControlService.
   * <pre>
   * OrchestratorControlService — thread-level control RPCs over the
   * LangGraph-backed orchestrator. Long-running orchestration events flow
   * over the KbProgress / OrchestratorProgress callbacks already migrated
   * in Phase 1; this service covers the short-payload control surface.
   * </pre>
   */
  public static final class OrchestratorControlServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorControlServiceStub> {
    private OrchestratorControlServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorControlServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorControlServiceStub(channel, callOptions);
    }

    /**
     */
    public void health(com.jervis.contracts.orchestrator.HealthRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.HealthResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getStatus(com.jervis.contracts.orchestrator.StatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void approve(com.jervis.contracts.orchestrator.ApproveRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ApproveAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getApproveMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void cancel(com.jervis.contracts.orchestrator.ThreadRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.CancelAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCancelMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Resume helper left out of the initial surface — no Kotlin consumer
     * dials it today (Approve already handles the resume-after-interrupt
     * flow). Add back if a new caller needs a dedicated resume RPC.
     * </pre>
     */
    public void interrupt(com.jervis.contracts.orchestrator.ThreadRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.InterruptAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInterruptMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorControlService.
   * <pre>
   * OrchestratorControlService — thread-level control RPCs over the
   * LangGraph-backed orchestrator. Long-running orchestration events flow
   * over the KbProgress / OrchestratorProgress callbacks already migrated
   * in Phase 1; this service covers the short-payload control surface.
   * </pre>
   */
  public static final class OrchestratorControlServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorControlServiceBlockingV2Stub> {
    private OrchestratorControlServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorControlServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorControlServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.HealthResponse health(com.jervis.contracts.orchestrator.HealthRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.StatusResponse getStatus(com.jervis.contracts.orchestrator.StatusRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.ApproveAck approve(com.jervis.contracts.orchestrator.ApproveRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getApproveMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.CancelAck cancel(com.jervis.contracts.orchestrator.ThreadRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCancelMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Resume helper left out of the initial surface — no Kotlin consumer
     * dials it today (Approve already handles the resume-after-interrupt
     * flow). Add back if a new caller needs a dedicated resume RPC.
     * </pre>
     */
    public com.jervis.contracts.orchestrator.InterruptAck interrupt(com.jervis.contracts.orchestrator.ThreadRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInterruptMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service OrchestratorControlService.
   * <pre>
   * OrchestratorControlService — thread-level control RPCs over the
   * LangGraph-backed orchestrator. Long-running orchestration events flow
   * over the KbProgress / OrchestratorProgress callbacks already migrated
   * in Phase 1; this service covers the short-payload control surface.
   * </pre>
   */
  public static final class OrchestratorControlServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorControlServiceBlockingStub> {
    private OrchestratorControlServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorControlServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorControlServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.HealthResponse health(com.jervis.contracts.orchestrator.HealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.StatusResponse getStatus(com.jervis.contracts.orchestrator.StatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.ApproveAck approve(com.jervis.contracts.orchestrator.ApproveRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getApproveMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.CancelAck cancel(com.jervis.contracts.orchestrator.ThreadRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCancelMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Resume helper left out of the initial surface — no Kotlin consumer
     * dials it today (Approve already handles the resume-after-interrupt
     * flow). Add back if a new caller needs a dedicated resume RPC.
     * </pre>
     */
    public com.jervis.contracts.orchestrator.InterruptAck interrupt(com.jervis.contracts.orchestrator.ThreadRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInterruptMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorControlService.
   * <pre>
   * OrchestratorControlService — thread-level control RPCs over the
   * LangGraph-backed orchestrator. Long-running orchestration events flow
   * over the KbProgress / OrchestratorProgress callbacks already migrated
   * in Phase 1; this service covers the short-payload control surface.
   * </pre>
   */
  public static final class OrchestratorControlServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorControlServiceFutureStub> {
    private OrchestratorControlServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorControlServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorControlServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.HealthResponse> health(
        com.jervis.contracts.orchestrator.HealthRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.StatusResponse> getStatus(
        com.jervis.contracts.orchestrator.StatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetStatusMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.ApproveAck> approve(
        com.jervis.contracts.orchestrator.ApproveRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getApproveMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.CancelAck> cancel(
        com.jervis.contracts.orchestrator.ThreadRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCancelMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Resume helper left out of the initial surface — no Kotlin consumer
     * dials it today (Approve already handles the resume-after-interrupt
     * flow). Add back if a new caller needs a dedicated resume RPC.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.InterruptAck> interrupt(
        com.jervis.contracts.orchestrator.ThreadRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInterruptMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_HEALTH = 0;
  private static final int METHODID_GET_STATUS = 1;
  private static final int METHODID_APPROVE = 2;
  private static final int METHODID_CANCEL = 3;
  private static final int METHODID_INTERRUPT = 4;

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
        case METHODID_HEALTH:
          serviceImpl.health((com.jervis.contracts.orchestrator.HealthRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.HealthResponse>) responseObserver);
          break;
        case METHODID_GET_STATUS:
          serviceImpl.getStatus((com.jervis.contracts.orchestrator.StatusRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StatusResponse>) responseObserver);
          break;
        case METHODID_APPROVE:
          serviceImpl.approve((com.jervis.contracts.orchestrator.ApproveRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ApproveAck>) responseObserver);
          break;
        case METHODID_CANCEL:
          serviceImpl.cancel((com.jervis.contracts.orchestrator.ThreadRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.CancelAck>) responseObserver);
          break;
        case METHODID_INTERRUPT:
          serviceImpl.interrupt((com.jervis.contracts.orchestrator.ThreadRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.InterruptAck>) responseObserver);
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
          getHealthMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.HealthRequest,
              com.jervis.contracts.orchestrator.HealthResponse>(
                service, METHODID_HEALTH)))
        .addMethod(
          getGetStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.StatusRequest,
              com.jervis.contracts.orchestrator.StatusResponse>(
                service, METHODID_GET_STATUS)))
        .addMethod(
          getApproveMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.ApproveRequest,
              com.jervis.contracts.orchestrator.ApproveAck>(
                service, METHODID_APPROVE)))
        .addMethod(
          getCancelMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.ThreadRequest,
              com.jervis.contracts.orchestrator.CancelAck>(
                service, METHODID_CANCEL)))
        .addMethod(
          getInterruptMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.ThreadRequest,
              com.jervis.contracts.orchestrator.InterruptAck>(
                service, METHODID_INTERRUPT)))
        .build();
  }

  private static abstract class OrchestratorControlServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorControlServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorControlProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorControlService");
    }
  }

  private static final class OrchestratorControlServiceFileDescriptorSupplier
      extends OrchestratorControlServiceBaseDescriptorSupplier {
    OrchestratorControlServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorControlServiceMethodDescriptorSupplier
      extends OrchestratorControlServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorControlServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorControlServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorControlServiceFileDescriptorSupplier())
              .addMethod(getHealthMethod())
              .addMethod(getGetStatusMethod())
              .addMethod(getApproveMethod())
              .addMethod(getCancelMethod())
              .addMethod(getInterruptMethod())
              .build();
        }
      }
    }
    return result;
  }
}
