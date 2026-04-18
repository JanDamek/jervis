package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorGraphService — AgentGraph lookup + idle maintenance
 * trigger. Administrative vertex CRUD + memory search were removed in
 * slice V3-cleanup (no pod-to-pod consumer; UI path goes through the
 * Kotlin server kRPC, not this servicer).
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorGraphServiceGrpc {

  private OrchestratorGraphServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorGraphService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.GetTaskGraphRequest,
      com.jervis.contracts.orchestrator.TaskGraphResponse> getGetTaskGraphMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTaskGraph",
      requestType = com.jervis.contracts.orchestrator.GetTaskGraphRequest.class,
      responseType = com.jervis.contracts.orchestrator.TaskGraphResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.GetTaskGraphRequest,
      com.jervis.contracts.orchestrator.TaskGraphResponse> getGetTaskGraphMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.GetTaskGraphRequest, com.jervis.contracts.orchestrator.TaskGraphResponse> getGetTaskGraphMethod;
    if ((getGetTaskGraphMethod = OrchestratorGraphServiceGrpc.getGetTaskGraphMethod) == null) {
      synchronized (OrchestratorGraphServiceGrpc.class) {
        if ((getGetTaskGraphMethod = OrchestratorGraphServiceGrpc.getGetTaskGraphMethod) == null) {
          OrchestratorGraphServiceGrpc.getGetTaskGraphMethod = getGetTaskGraphMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.GetTaskGraphRequest, com.jervis.contracts.orchestrator.TaskGraphResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTaskGraph"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.GetTaskGraphRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.TaskGraphResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorGraphServiceMethodDescriptorSupplier("GetTaskGraph"))
              .build();
        }
      }
    }
    return getGetTaskGraphMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.MaintenanceRunRequest,
      com.jervis.contracts.orchestrator.MaintenanceRunResult> getRunMaintenanceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RunMaintenance",
      requestType = com.jervis.contracts.orchestrator.MaintenanceRunRequest.class,
      responseType = com.jervis.contracts.orchestrator.MaintenanceRunResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.MaintenanceRunRequest,
      com.jervis.contracts.orchestrator.MaintenanceRunResult> getRunMaintenanceMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.MaintenanceRunRequest, com.jervis.contracts.orchestrator.MaintenanceRunResult> getRunMaintenanceMethod;
    if ((getRunMaintenanceMethod = OrchestratorGraphServiceGrpc.getRunMaintenanceMethod) == null) {
      synchronized (OrchestratorGraphServiceGrpc.class) {
        if ((getRunMaintenanceMethod = OrchestratorGraphServiceGrpc.getRunMaintenanceMethod) == null) {
          OrchestratorGraphServiceGrpc.getRunMaintenanceMethod = getRunMaintenanceMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.MaintenanceRunRequest, com.jervis.contracts.orchestrator.MaintenanceRunResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RunMaintenance"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.MaintenanceRunRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.MaintenanceRunResult.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorGraphServiceMethodDescriptorSupplier("RunMaintenance"))
              .build();
        }
      }
    }
    return getRunMaintenanceMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorGraphServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorGraphServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorGraphServiceStub>() {
        @java.lang.Override
        public OrchestratorGraphServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorGraphServiceStub(channel, callOptions);
        }
      };
    return OrchestratorGraphServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static OrchestratorGraphServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorGraphServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorGraphServiceBlockingV2Stub>() {
        @java.lang.Override
        public OrchestratorGraphServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorGraphServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return OrchestratorGraphServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorGraphServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorGraphServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorGraphServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorGraphServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorGraphServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorGraphServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorGraphServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorGraphServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorGraphServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorGraphServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorGraphServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorGraphServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD + memory search were removed in
   * slice V3-cleanup (no pod-to-pod consumer; UI path goes through the
   * Kotlin server kRPC, not this servicer).
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void getTaskGraph(com.jervis.contracts.orchestrator.GetTaskGraphRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.TaskGraphResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaskGraphMethod(), responseObserver);
    }

    /**
     */
    default void runMaintenance(com.jervis.contracts.orchestrator.MaintenanceRunRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.MaintenanceRunResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRunMaintenanceMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorGraphService.
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD + memory search were removed in
   * slice V3-cleanup (no pod-to-pod consumer; UI path goes through the
   * Kotlin server kRPC, not this servicer).
   * </pre>
   */
  public static abstract class OrchestratorGraphServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorGraphServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorGraphService.
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD + memory search were removed in
   * slice V3-cleanup (no pod-to-pod consumer; UI path goes through the
   * Kotlin server kRPC, not this servicer).
   * </pre>
   */
  public static final class OrchestratorGraphServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorGraphServiceStub> {
    private OrchestratorGraphServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorGraphServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorGraphServiceStub(channel, callOptions);
    }

    /**
     */
    public void getTaskGraph(com.jervis.contracts.orchestrator.GetTaskGraphRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.TaskGraphResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTaskGraphMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void runMaintenance(com.jervis.contracts.orchestrator.MaintenanceRunRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.MaintenanceRunResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRunMaintenanceMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorGraphService.
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD + memory search were removed in
   * slice V3-cleanup (no pod-to-pod consumer; UI path goes through the
   * Kotlin server kRPC, not this servicer).
   * </pre>
   */
  public static final class OrchestratorGraphServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorGraphServiceBlockingV2Stub> {
    private OrchestratorGraphServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorGraphServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorGraphServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.TaskGraphResponse getTaskGraph(com.jervis.contracts.orchestrator.GetTaskGraphRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetTaskGraphMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.MaintenanceRunResult runMaintenance(com.jervis.contracts.orchestrator.MaintenanceRunRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRunMaintenanceMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service OrchestratorGraphService.
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD + memory search were removed in
   * slice V3-cleanup (no pod-to-pod consumer; UI path goes through the
   * Kotlin server kRPC, not this servicer).
   * </pre>
   */
  public static final class OrchestratorGraphServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorGraphServiceBlockingStub> {
    private OrchestratorGraphServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorGraphServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorGraphServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.TaskGraphResponse getTaskGraph(com.jervis.contracts.orchestrator.GetTaskGraphRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTaskGraphMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.MaintenanceRunResult runMaintenance(com.jervis.contracts.orchestrator.MaintenanceRunRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRunMaintenanceMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorGraphService.
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD + memory search were removed in
   * slice V3-cleanup (no pod-to-pod consumer; UI path goes through the
   * Kotlin server kRPC, not this servicer).
   * </pre>
   */
  public static final class OrchestratorGraphServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorGraphServiceFutureStub> {
    private OrchestratorGraphServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorGraphServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorGraphServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.TaskGraphResponse> getTaskGraph(
        com.jervis.contracts.orchestrator.GetTaskGraphRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTaskGraphMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.MaintenanceRunResult> runMaintenance(
        com.jervis.contracts.orchestrator.MaintenanceRunRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRunMaintenanceMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_TASK_GRAPH = 0;
  private static final int METHODID_RUN_MAINTENANCE = 1;

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
        case METHODID_GET_TASK_GRAPH:
          serviceImpl.getTaskGraph((com.jervis.contracts.orchestrator.GetTaskGraphRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.TaskGraphResponse>) responseObserver);
          break;
        case METHODID_RUN_MAINTENANCE:
          serviceImpl.runMaintenance((com.jervis.contracts.orchestrator.MaintenanceRunRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.MaintenanceRunResult>) responseObserver);
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
          getGetTaskGraphMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.GetTaskGraphRequest,
              com.jervis.contracts.orchestrator.TaskGraphResponse>(
                service, METHODID_GET_TASK_GRAPH)))
        .addMethod(
          getRunMaintenanceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.MaintenanceRunRequest,
              com.jervis.contracts.orchestrator.MaintenanceRunResult>(
                service, METHODID_RUN_MAINTENANCE)))
        .build();
  }

  private static abstract class OrchestratorGraphServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorGraphServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorGraphProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorGraphService");
    }
  }

  private static final class OrchestratorGraphServiceFileDescriptorSupplier
      extends OrchestratorGraphServiceBaseDescriptorSupplier {
    OrchestratorGraphServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorGraphServiceMethodDescriptorSupplier
      extends OrchestratorGraphServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorGraphServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorGraphServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorGraphServiceFileDescriptorSupplier())
              .addMethod(getGetTaskGraphMethod())
              .addMethod(getRunMaintenanceMethod())
              .build();
        }
      }
    }
    return result;
  }
}
