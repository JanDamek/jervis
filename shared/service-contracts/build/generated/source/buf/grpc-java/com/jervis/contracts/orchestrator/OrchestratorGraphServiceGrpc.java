package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorGraphService — AgentGraph lookup + idle maintenance
 * trigger. Administrative vertex CRUD (delete/update/create/cleanup/
 * purge-stale) stays on FastAPI until a cross-service consumer dials
 * it (UI path goes through Kotlin kRPC, not this servicer).
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

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.VertexIdRequest,
      com.jervis.contracts.orchestrator.VertexMutationAck> getDeleteVertexMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteVertex",
      requestType = com.jervis.contracts.orchestrator.VertexIdRequest.class,
      responseType = com.jervis.contracts.orchestrator.VertexMutationAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.VertexIdRequest,
      com.jervis.contracts.orchestrator.VertexMutationAck> getDeleteVertexMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.VertexIdRequest, com.jervis.contracts.orchestrator.VertexMutationAck> getDeleteVertexMethod;
    if ((getDeleteVertexMethod = OrchestratorGraphServiceGrpc.getDeleteVertexMethod) == null) {
      synchronized (OrchestratorGraphServiceGrpc.class) {
        if ((getDeleteVertexMethod = OrchestratorGraphServiceGrpc.getDeleteVertexMethod) == null) {
          OrchestratorGraphServiceGrpc.getDeleteVertexMethod = getDeleteVertexMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.VertexIdRequest, com.jervis.contracts.orchestrator.VertexMutationAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteVertex"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.VertexIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.VertexMutationAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorGraphServiceMethodDescriptorSupplier("DeleteVertex"))
              .build();
        }
      }
    }
    return getDeleteVertexMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.UpdateVertexRequest,
      com.jervis.contracts.orchestrator.VertexMutationAck> getUpdateVertexMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateVertex",
      requestType = com.jervis.contracts.orchestrator.UpdateVertexRequest.class,
      responseType = com.jervis.contracts.orchestrator.VertexMutationAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.UpdateVertexRequest,
      com.jervis.contracts.orchestrator.VertexMutationAck> getUpdateVertexMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.UpdateVertexRequest, com.jervis.contracts.orchestrator.VertexMutationAck> getUpdateVertexMethod;
    if ((getUpdateVertexMethod = OrchestratorGraphServiceGrpc.getUpdateVertexMethod) == null) {
      synchronized (OrchestratorGraphServiceGrpc.class) {
        if ((getUpdateVertexMethod = OrchestratorGraphServiceGrpc.getUpdateVertexMethod) == null) {
          OrchestratorGraphServiceGrpc.getUpdateVertexMethod = getUpdateVertexMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.UpdateVertexRequest, com.jervis.contracts.orchestrator.VertexMutationAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateVertex"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.UpdateVertexRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.VertexMutationAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorGraphServiceMethodDescriptorSupplier("UpdateVertex"))
              .build();
        }
      }
    }
    return getUpdateVertexMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.CreateVertexRequest,
      com.jervis.contracts.orchestrator.VertexMutationAck> getCreateVertexMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateVertex",
      requestType = com.jervis.contracts.orchestrator.CreateVertexRequest.class,
      responseType = com.jervis.contracts.orchestrator.VertexMutationAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.CreateVertexRequest,
      com.jervis.contracts.orchestrator.VertexMutationAck> getCreateVertexMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.CreateVertexRequest, com.jervis.contracts.orchestrator.VertexMutationAck> getCreateVertexMethod;
    if ((getCreateVertexMethod = OrchestratorGraphServiceGrpc.getCreateVertexMethod) == null) {
      synchronized (OrchestratorGraphServiceGrpc.class) {
        if ((getCreateVertexMethod = OrchestratorGraphServiceGrpc.getCreateVertexMethod) == null) {
          OrchestratorGraphServiceGrpc.getCreateVertexMethod = getCreateVertexMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.CreateVertexRequest, com.jervis.contracts.orchestrator.VertexMutationAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateVertex"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.CreateVertexRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.VertexMutationAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorGraphServiceMethodDescriptorSupplier("CreateVertex"))
              .build();
        }
      }
    }
    return getCreateVertexMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.CleanupRequest,
      com.jervis.contracts.orchestrator.CleanupResult> getForceCleanupMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ForceCleanup",
      requestType = com.jervis.contracts.orchestrator.CleanupRequest.class,
      responseType = com.jervis.contracts.orchestrator.CleanupResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.CleanupRequest,
      com.jervis.contracts.orchestrator.CleanupResult> getForceCleanupMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.CleanupRequest, com.jervis.contracts.orchestrator.CleanupResult> getForceCleanupMethod;
    if ((getForceCleanupMethod = OrchestratorGraphServiceGrpc.getForceCleanupMethod) == null) {
      synchronized (OrchestratorGraphServiceGrpc.class) {
        if ((getForceCleanupMethod = OrchestratorGraphServiceGrpc.getForceCleanupMethod) == null) {
          OrchestratorGraphServiceGrpc.getForceCleanupMethod = getForceCleanupMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.CleanupRequest, com.jervis.contracts.orchestrator.CleanupResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ForceCleanup"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.CleanupRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.CleanupResult.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorGraphServiceMethodDescriptorSupplier("ForceCleanup"))
              .build();
        }
      }
    }
    return getForceCleanupMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.PurgeStaleRequest,
      com.jervis.contracts.orchestrator.PurgeStaleResult> getPurgeStaleMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PurgeStale",
      requestType = com.jervis.contracts.orchestrator.PurgeStaleRequest.class,
      responseType = com.jervis.contracts.orchestrator.PurgeStaleResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.PurgeStaleRequest,
      com.jervis.contracts.orchestrator.PurgeStaleResult> getPurgeStaleMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.PurgeStaleRequest, com.jervis.contracts.orchestrator.PurgeStaleResult> getPurgeStaleMethod;
    if ((getPurgeStaleMethod = OrchestratorGraphServiceGrpc.getPurgeStaleMethod) == null) {
      synchronized (OrchestratorGraphServiceGrpc.class) {
        if ((getPurgeStaleMethod = OrchestratorGraphServiceGrpc.getPurgeStaleMethod) == null) {
          OrchestratorGraphServiceGrpc.getPurgeStaleMethod = getPurgeStaleMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.PurgeStaleRequest, com.jervis.contracts.orchestrator.PurgeStaleResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PurgeStale"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.PurgeStaleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.PurgeStaleResult.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorGraphServiceMethodDescriptorSupplier("PurgeStale"))
              .build();
        }
      }
    }
    return getPurgeStaleMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.MemorySearchRequest,
      com.jervis.contracts.orchestrator.MemorySearchResult> getMemorySearchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MemorySearch",
      requestType = com.jervis.contracts.orchestrator.MemorySearchRequest.class,
      responseType = com.jervis.contracts.orchestrator.MemorySearchResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.MemorySearchRequest,
      com.jervis.contracts.orchestrator.MemorySearchResult> getMemorySearchMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.MemorySearchRequest, com.jervis.contracts.orchestrator.MemorySearchResult> getMemorySearchMethod;
    if ((getMemorySearchMethod = OrchestratorGraphServiceGrpc.getMemorySearchMethod) == null) {
      synchronized (OrchestratorGraphServiceGrpc.class) {
        if ((getMemorySearchMethod = OrchestratorGraphServiceGrpc.getMemorySearchMethod) == null) {
          OrchestratorGraphServiceGrpc.getMemorySearchMethod = getMemorySearchMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.MemorySearchRequest, com.jervis.contracts.orchestrator.MemorySearchResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MemorySearch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.MemorySearchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.MemorySearchResult.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorGraphServiceMethodDescriptorSupplier("MemorySearch"))
              .build();
        }
      }
    }
    return getMemorySearchMethod;
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
   * trigger. Administrative vertex CRUD (delete/update/create/cleanup/
   * purge-stale) stays on FastAPI until a cross-service consumer dials
   * it (UI path goes through Kotlin kRPC, not this servicer).
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

    /**
     * <pre>
     * Memory graph admin — used by UI proxy path through the Kotlin server.
     * </pre>
     */
    default void deleteVertex(com.jervis.contracts.orchestrator.VertexIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VertexMutationAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteVertexMethod(), responseObserver);
    }

    /**
     */
    default void updateVertex(com.jervis.contracts.orchestrator.UpdateVertexRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VertexMutationAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateVertexMethod(), responseObserver);
    }

    /**
     */
    default void createVertex(com.jervis.contracts.orchestrator.CreateVertexRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VertexMutationAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateVertexMethod(), responseObserver);
    }

    /**
     */
    default void forceCleanup(com.jervis.contracts.orchestrator.CleanupRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.CleanupResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getForceCleanupMethod(), responseObserver);
    }

    /**
     */
    default void purgeStale(com.jervis.contracts.orchestrator.PurgeStaleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.PurgeStaleResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPurgeStaleMethod(), responseObserver);
    }

    /**
     */
    default void memorySearch(com.jervis.contracts.orchestrator.MemorySearchRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.MemorySearchResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMemorySearchMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorGraphService.
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD (delete/update/create/cleanup/
   * purge-stale) stays on FastAPI until a cross-service consumer dials
   * it (UI path goes through Kotlin kRPC, not this servicer).
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
   * trigger. Administrative vertex CRUD (delete/update/create/cleanup/
   * purge-stale) stays on FastAPI until a cross-service consumer dials
   * it (UI path goes through Kotlin kRPC, not this servicer).
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

    /**
     * <pre>
     * Memory graph admin — used by UI proxy path through the Kotlin server.
     * </pre>
     */
    public void deleteVertex(com.jervis.contracts.orchestrator.VertexIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VertexMutationAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteVertexMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void updateVertex(com.jervis.contracts.orchestrator.UpdateVertexRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VertexMutationAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateVertexMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createVertex(com.jervis.contracts.orchestrator.CreateVertexRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VertexMutationAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateVertexMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void forceCleanup(com.jervis.contracts.orchestrator.CleanupRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.CleanupResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getForceCleanupMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void purgeStale(com.jervis.contracts.orchestrator.PurgeStaleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.PurgeStaleResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPurgeStaleMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void memorySearch(com.jervis.contracts.orchestrator.MemorySearchRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.MemorySearchResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMemorySearchMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorGraphService.
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD (delete/update/create/cleanup/
   * purge-stale) stays on FastAPI until a cross-service consumer dials
   * it (UI path goes through Kotlin kRPC, not this servicer).
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

    /**
     * <pre>
     * Memory graph admin — used by UI proxy path through the Kotlin server.
     * </pre>
     */
    public com.jervis.contracts.orchestrator.VertexMutationAck deleteVertex(com.jervis.contracts.orchestrator.VertexIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteVertexMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.VertexMutationAck updateVertex(com.jervis.contracts.orchestrator.UpdateVertexRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateVertexMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.VertexMutationAck createVertex(com.jervis.contracts.orchestrator.CreateVertexRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateVertexMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.CleanupResult forceCleanup(com.jervis.contracts.orchestrator.CleanupRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getForceCleanupMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.PurgeStaleResult purgeStale(com.jervis.contracts.orchestrator.PurgeStaleRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPurgeStaleMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.MemorySearchResult memorySearch(com.jervis.contracts.orchestrator.MemorySearchRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getMemorySearchMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service OrchestratorGraphService.
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD (delete/update/create/cleanup/
   * purge-stale) stays on FastAPI until a cross-service consumer dials
   * it (UI path goes through Kotlin kRPC, not this servicer).
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

    /**
     * <pre>
     * Memory graph admin — used by UI proxy path through the Kotlin server.
     * </pre>
     */
    public com.jervis.contracts.orchestrator.VertexMutationAck deleteVertex(com.jervis.contracts.orchestrator.VertexIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteVertexMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.VertexMutationAck updateVertex(com.jervis.contracts.orchestrator.UpdateVertexRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateVertexMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.VertexMutationAck createVertex(com.jervis.contracts.orchestrator.CreateVertexRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateVertexMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.CleanupResult forceCleanup(com.jervis.contracts.orchestrator.CleanupRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getForceCleanupMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.PurgeStaleResult purgeStale(com.jervis.contracts.orchestrator.PurgeStaleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPurgeStaleMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.MemorySearchResult memorySearch(com.jervis.contracts.orchestrator.MemorySearchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMemorySearchMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorGraphService.
   * <pre>
   * OrchestratorGraphService — AgentGraph lookup + idle maintenance
   * trigger. Administrative vertex CRUD (delete/update/create/cleanup/
   * purge-stale) stays on FastAPI until a cross-service consumer dials
   * it (UI path goes through Kotlin kRPC, not this servicer).
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

    /**
     * <pre>
     * Memory graph admin — used by UI proxy path through the Kotlin server.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.VertexMutationAck> deleteVertex(
        com.jervis.contracts.orchestrator.VertexIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteVertexMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.VertexMutationAck> updateVertex(
        com.jervis.contracts.orchestrator.UpdateVertexRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateVertexMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.VertexMutationAck> createVertex(
        com.jervis.contracts.orchestrator.CreateVertexRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateVertexMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.CleanupResult> forceCleanup(
        com.jervis.contracts.orchestrator.CleanupRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getForceCleanupMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.PurgeStaleResult> purgeStale(
        com.jervis.contracts.orchestrator.PurgeStaleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPurgeStaleMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.MemorySearchResult> memorySearch(
        com.jervis.contracts.orchestrator.MemorySearchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getMemorySearchMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_TASK_GRAPH = 0;
  private static final int METHODID_RUN_MAINTENANCE = 1;
  private static final int METHODID_DELETE_VERTEX = 2;
  private static final int METHODID_UPDATE_VERTEX = 3;
  private static final int METHODID_CREATE_VERTEX = 4;
  private static final int METHODID_FORCE_CLEANUP = 5;
  private static final int METHODID_PURGE_STALE = 6;
  private static final int METHODID_MEMORY_SEARCH = 7;

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
        case METHODID_DELETE_VERTEX:
          serviceImpl.deleteVertex((com.jervis.contracts.orchestrator.VertexIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VertexMutationAck>) responseObserver);
          break;
        case METHODID_UPDATE_VERTEX:
          serviceImpl.updateVertex((com.jervis.contracts.orchestrator.UpdateVertexRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VertexMutationAck>) responseObserver);
          break;
        case METHODID_CREATE_VERTEX:
          serviceImpl.createVertex((com.jervis.contracts.orchestrator.CreateVertexRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VertexMutationAck>) responseObserver);
          break;
        case METHODID_FORCE_CLEANUP:
          serviceImpl.forceCleanup((com.jervis.contracts.orchestrator.CleanupRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.CleanupResult>) responseObserver);
          break;
        case METHODID_PURGE_STALE:
          serviceImpl.purgeStale((com.jervis.contracts.orchestrator.PurgeStaleRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.PurgeStaleResult>) responseObserver);
          break;
        case METHODID_MEMORY_SEARCH:
          serviceImpl.memorySearch((com.jervis.contracts.orchestrator.MemorySearchRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.MemorySearchResult>) responseObserver);
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
        .addMethod(
          getDeleteVertexMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.VertexIdRequest,
              com.jervis.contracts.orchestrator.VertexMutationAck>(
                service, METHODID_DELETE_VERTEX)))
        .addMethod(
          getUpdateVertexMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.UpdateVertexRequest,
              com.jervis.contracts.orchestrator.VertexMutationAck>(
                service, METHODID_UPDATE_VERTEX)))
        .addMethod(
          getCreateVertexMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.CreateVertexRequest,
              com.jervis.contracts.orchestrator.VertexMutationAck>(
                service, METHODID_CREATE_VERTEX)))
        .addMethod(
          getForceCleanupMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.CleanupRequest,
              com.jervis.contracts.orchestrator.CleanupResult>(
                service, METHODID_FORCE_CLEANUP)))
        .addMethod(
          getPurgeStaleMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.PurgeStaleRequest,
              com.jervis.contracts.orchestrator.PurgeStaleResult>(
                service, METHODID_PURGE_STALE)))
        .addMethod(
          getMemorySearchMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.MemorySearchRequest,
              com.jervis.contracts.orchestrator.MemorySearchResult>(
                service, METHODID_MEMORY_SEARCH)))
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
              .addMethod(getDeleteVertexMethod())
              .addMethod(getUpdateVertexMethod())
              .addMethod(getCreateVertexMethod())
              .addMethod(getForceCleanupMethod())
              .addMethod(getPurgeStaleMethod())
              .addMethod(getMemorySearchMethod())
              .build();
        }
      }
    }
    return result;
  }
}
