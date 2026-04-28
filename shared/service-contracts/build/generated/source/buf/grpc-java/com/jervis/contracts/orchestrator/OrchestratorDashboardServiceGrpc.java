package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorDashboardService — read-only snapshot of the SessionBroker
 * (active Claude sessions, LRU cap, pause state, agent-job holds, recent
 * LRU evictions). Consumed by the Kotlin server which fronts a kRPC
 * push-flow surface for the desktop UI Dashboard screen.
 * Unary for now: server pulls every 5 s into a MutableSharedFlow(replay=1)
 * and the UI subscribes to that flow. A future upgrade to server-streaming
 * keyed off broker audit writes is possible without changing the kRPC
 * contract.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorDashboardServiceGrpc {

  private OrchestratorDashboardServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorDashboardService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.GetSessionSnapshotRequest,
      com.jervis.contracts.orchestrator.SessionSnapshotResponse> getGetSessionSnapshotMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSessionSnapshot",
      requestType = com.jervis.contracts.orchestrator.GetSessionSnapshotRequest.class,
      responseType = com.jervis.contracts.orchestrator.SessionSnapshotResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.GetSessionSnapshotRequest,
      com.jervis.contracts.orchestrator.SessionSnapshotResponse> getGetSessionSnapshotMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.GetSessionSnapshotRequest, com.jervis.contracts.orchestrator.SessionSnapshotResponse> getGetSessionSnapshotMethod;
    if ((getGetSessionSnapshotMethod = OrchestratorDashboardServiceGrpc.getGetSessionSnapshotMethod) == null) {
      synchronized (OrchestratorDashboardServiceGrpc.class) {
        if ((getGetSessionSnapshotMethod = OrchestratorDashboardServiceGrpc.getGetSessionSnapshotMethod) == null) {
          OrchestratorDashboardServiceGrpc.getGetSessionSnapshotMethod = getGetSessionSnapshotMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.GetSessionSnapshotRequest, com.jervis.contracts.orchestrator.SessionSnapshotResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSessionSnapshot"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.GetSessionSnapshotRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.SessionSnapshotResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorDashboardServiceMethodDescriptorSupplier("GetSessionSnapshot"))
              .build();
        }
      }
    }
    return getGetSessionSnapshotMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorDashboardServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorDashboardServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorDashboardServiceStub>() {
        @java.lang.Override
        public OrchestratorDashboardServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorDashboardServiceStub(channel, callOptions);
        }
      };
    return OrchestratorDashboardServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static OrchestratorDashboardServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorDashboardServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorDashboardServiceBlockingV2Stub>() {
        @java.lang.Override
        public OrchestratorDashboardServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorDashboardServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return OrchestratorDashboardServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorDashboardServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorDashboardServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorDashboardServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorDashboardServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorDashboardServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorDashboardServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorDashboardServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorDashboardServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorDashboardServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorDashboardServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorDashboardServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorDashboardServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorDashboardService — read-only snapshot of the SessionBroker
   * (active Claude sessions, LRU cap, pause state, agent-job holds, recent
   * LRU evictions). Consumed by the Kotlin server which fronts a kRPC
   * push-flow surface for the desktop UI Dashboard screen.
   * Unary for now: server pulls every 5 s into a MutableSharedFlow(replay=1)
   * and the UI subscribes to that flow. A future upgrade to server-streaming
   * keyed off broker audit writes is possible without changing the kRPC
   * contract.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void getSessionSnapshot(com.jervis.contracts.orchestrator.GetSessionSnapshotRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionSnapshotResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetSessionSnapshotMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorDashboardService.
   * <pre>
   * OrchestratorDashboardService — read-only snapshot of the SessionBroker
   * (active Claude sessions, LRU cap, pause state, agent-job holds, recent
   * LRU evictions). Consumed by the Kotlin server which fronts a kRPC
   * push-flow surface for the desktop UI Dashboard screen.
   * Unary for now: server pulls every 5 s into a MutableSharedFlow(replay=1)
   * and the UI subscribes to that flow. A future upgrade to server-streaming
   * keyed off broker audit writes is possible without changing the kRPC
   * contract.
   * </pre>
   */
  public static abstract class OrchestratorDashboardServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorDashboardServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorDashboardService.
   * <pre>
   * OrchestratorDashboardService — read-only snapshot of the SessionBroker
   * (active Claude sessions, LRU cap, pause state, agent-job holds, recent
   * LRU evictions). Consumed by the Kotlin server which fronts a kRPC
   * push-flow surface for the desktop UI Dashboard screen.
   * Unary for now: server pulls every 5 s into a MutableSharedFlow(replay=1)
   * and the UI subscribes to that flow. A future upgrade to server-streaming
   * keyed off broker audit writes is possible without changing the kRPC
   * contract.
   * </pre>
   */
  public static final class OrchestratorDashboardServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorDashboardServiceStub> {
    private OrchestratorDashboardServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorDashboardServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorDashboardServiceStub(channel, callOptions);
    }

    /**
     */
    public void getSessionSnapshot(com.jervis.contracts.orchestrator.GetSessionSnapshotRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionSnapshotResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetSessionSnapshotMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorDashboardService.
   * <pre>
   * OrchestratorDashboardService — read-only snapshot of the SessionBroker
   * (active Claude sessions, LRU cap, pause state, agent-job holds, recent
   * LRU evictions). Consumed by the Kotlin server which fronts a kRPC
   * push-flow surface for the desktop UI Dashboard screen.
   * Unary for now: server pulls every 5 s into a MutableSharedFlow(replay=1)
   * and the UI subscribes to that flow. A future upgrade to server-streaming
   * keyed off broker audit writes is possible without changing the kRPC
   * contract.
   * </pre>
   */
  public static final class OrchestratorDashboardServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorDashboardServiceBlockingV2Stub> {
    private OrchestratorDashboardServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorDashboardServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorDashboardServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.SessionSnapshotResponse getSessionSnapshot(com.jervis.contracts.orchestrator.GetSessionSnapshotRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetSessionSnapshotMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service OrchestratorDashboardService.
   * <pre>
   * OrchestratorDashboardService — read-only snapshot of the SessionBroker
   * (active Claude sessions, LRU cap, pause state, agent-job holds, recent
   * LRU evictions). Consumed by the Kotlin server which fronts a kRPC
   * push-flow surface for the desktop UI Dashboard screen.
   * Unary for now: server pulls every 5 s into a MutableSharedFlow(replay=1)
   * and the UI subscribes to that flow. A future upgrade to server-streaming
   * keyed off broker audit writes is possible without changing the kRPC
   * contract.
   * </pre>
   */
  public static final class OrchestratorDashboardServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorDashboardServiceBlockingStub> {
    private OrchestratorDashboardServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorDashboardServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorDashboardServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.SessionSnapshotResponse getSessionSnapshot(com.jervis.contracts.orchestrator.GetSessionSnapshotRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSessionSnapshotMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorDashboardService.
   * <pre>
   * OrchestratorDashboardService — read-only snapshot of the SessionBroker
   * (active Claude sessions, LRU cap, pause state, agent-job holds, recent
   * LRU evictions). Consumed by the Kotlin server which fronts a kRPC
   * push-flow surface for the desktop UI Dashboard screen.
   * Unary for now: server pulls every 5 s into a MutableSharedFlow(replay=1)
   * and the UI subscribes to that flow. A future upgrade to server-streaming
   * keyed off broker audit writes is possible without changing the kRPC
   * contract.
   * </pre>
   */
  public static final class OrchestratorDashboardServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorDashboardServiceFutureStub> {
    private OrchestratorDashboardServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorDashboardServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorDashboardServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.SessionSnapshotResponse> getSessionSnapshot(
        com.jervis.contracts.orchestrator.GetSessionSnapshotRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetSessionSnapshotMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_SESSION_SNAPSHOT = 0;

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
        case METHODID_GET_SESSION_SNAPSHOT:
          serviceImpl.getSessionSnapshot((com.jervis.contracts.orchestrator.GetSessionSnapshotRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionSnapshotResponse>) responseObserver);
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
          getGetSessionSnapshotMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.GetSessionSnapshotRequest,
              com.jervis.contracts.orchestrator.SessionSnapshotResponse>(
                service, METHODID_GET_SESSION_SNAPSHOT)))
        .build();
  }

  private static abstract class OrchestratorDashboardServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorDashboardServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorDashboardProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorDashboardService");
    }
  }

  private static final class OrchestratorDashboardServiceFileDescriptorSupplier
      extends OrchestratorDashboardServiceBaseDescriptorSupplier {
    OrchestratorDashboardServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorDashboardServiceMethodDescriptorSupplier
      extends OrchestratorDashboardServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorDashboardServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorDashboardServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorDashboardServiceFileDescriptorSupplier())
              .addMethod(getGetSessionSnapshotMethod())
              .build();
        }
      }
    }
    return result;
  }
}
