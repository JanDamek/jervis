package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerTimeTrackingService — time log entry + rollup summary + weekly
 * capacity snapshot. Consumed by the orchestrator's chat tools (log_time,
 * time_summary, check_capacity).
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerTimeTrackingServiceGrpc {

  private ServerTimeTrackingServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerTimeTrackingService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.LogTimeRequest,
      com.jervis.contracts.server.LogTimeResponse> getLogTimeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "LogTime",
      requestType = com.jervis.contracts.server.LogTimeRequest.class,
      responseType = com.jervis.contracts.server.LogTimeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.LogTimeRequest,
      com.jervis.contracts.server.LogTimeResponse> getLogTimeMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.LogTimeRequest, com.jervis.contracts.server.LogTimeResponse> getLogTimeMethod;
    if ((getLogTimeMethod = ServerTimeTrackingServiceGrpc.getLogTimeMethod) == null) {
      synchronized (ServerTimeTrackingServiceGrpc.class) {
        if ((getLogTimeMethod = ServerTimeTrackingServiceGrpc.getLogTimeMethod) == null) {
          ServerTimeTrackingServiceGrpc.getLogTimeMethod = getLogTimeMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.LogTimeRequest, com.jervis.contracts.server.LogTimeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "LogTime"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.LogTimeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.LogTimeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTimeTrackingServiceMethodDescriptorSupplier("LogTime"))
              .build();
        }
      }
    }
    return getLogTimeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetSummaryRequest,
      com.jervis.contracts.server.GetSummaryResponse> getGetSummaryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSummary",
      requestType = com.jervis.contracts.server.GetSummaryRequest.class,
      responseType = com.jervis.contracts.server.GetSummaryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetSummaryRequest,
      com.jervis.contracts.server.GetSummaryResponse> getGetSummaryMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetSummaryRequest, com.jervis.contracts.server.GetSummaryResponse> getGetSummaryMethod;
    if ((getGetSummaryMethod = ServerTimeTrackingServiceGrpc.getGetSummaryMethod) == null) {
      synchronized (ServerTimeTrackingServiceGrpc.class) {
        if ((getGetSummaryMethod = ServerTimeTrackingServiceGrpc.getGetSummaryMethod) == null) {
          ServerTimeTrackingServiceGrpc.getGetSummaryMethod = getGetSummaryMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetSummaryRequest, com.jervis.contracts.server.GetSummaryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSummary"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetSummaryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetSummaryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTimeTrackingServiceMethodDescriptorSupplier("GetSummary"))
              .build();
        }
      }
    }
    return getGetSummaryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetCapacityRequest,
      com.jervis.contracts.server.GetCapacityResponse> getGetCapacityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetCapacity",
      requestType = com.jervis.contracts.server.GetCapacityRequest.class,
      responseType = com.jervis.contracts.server.GetCapacityResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetCapacityRequest,
      com.jervis.contracts.server.GetCapacityResponse> getGetCapacityMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetCapacityRequest, com.jervis.contracts.server.GetCapacityResponse> getGetCapacityMethod;
    if ((getGetCapacityMethod = ServerTimeTrackingServiceGrpc.getGetCapacityMethod) == null) {
      synchronized (ServerTimeTrackingServiceGrpc.class) {
        if ((getGetCapacityMethod = ServerTimeTrackingServiceGrpc.getGetCapacityMethod) == null) {
          ServerTimeTrackingServiceGrpc.getGetCapacityMethod = getGetCapacityMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetCapacityRequest, com.jervis.contracts.server.GetCapacityResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetCapacity"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetCapacityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetCapacityResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTimeTrackingServiceMethodDescriptorSupplier("GetCapacity"))
              .build();
        }
      }
    }
    return getGetCapacityMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerTimeTrackingServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTimeTrackingServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTimeTrackingServiceStub>() {
        @java.lang.Override
        public ServerTimeTrackingServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTimeTrackingServiceStub(channel, callOptions);
        }
      };
    return ServerTimeTrackingServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerTimeTrackingServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTimeTrackingServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTimeTrackingServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerTimeTrackingServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTimeTrackingServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerTimeTrackingServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerTimeTrackingServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTimeTrackingServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTimeTrackingServiceBlockingStub>() {
        @java.lang.Override
        public ServerTimeTrackingServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTimeTrackingServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerTimeTrackingServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerTimeTrackingServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTimeTrackingServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTimeTrackingServiceFutureStub>() {
        @java.lang.Override
        public ServerTimeTrackingServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTimeTrackingServiceFutureStub(channel, callOptions);
        }
      };
    return ServerTimeTrackingServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerTimeTrackingService — time log entry + rollup summary + weekly
   * capacity snapshot. Consumed by the orchestrator's chat tools (log_time,
   * time_summary, check_capacity).
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void logTime(com.jervis.contracts.server.LogTimeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.LogTimeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getLogTimeMethod(), responseObserver);
    }

    /**
     */
    default void getSummary(com.jervis.contracts.server.GetSummaryRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetSummaryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetSummaryMethod(), responseObserver);
    }

    /**
     */
    default void getCapacity(com.jervis.contracts.server.GetCapacityRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetCapacityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCapacityMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerTimeTrackingService.
   * <pre>
   * ServerTimeTrackingService — time log entry + rollup summary + weekly
   * capacity snapshot. Consumed by the orchestrator's chat tools (log_time,
   * time_summary, check_capacity).
   * </pre>
   */
  public static abstract class ServerTimeTrackingServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerTimeTrackingServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerTimeTrackingService.
   * <pre>
   * ServerTimeTrackingService — time log entry + rollup summary + weekly
   * capacity snapshot. Consumed by the orchestrator's chat tools (log_time,
   * time_summary, check_capacity).
   * </pre>
   */
  public static final class ServerTimeTrackingServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerTimeTrackingServiceStub> {
    private ServerTimeTrackingServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTimeTrackingServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTimeTrackingServiceStub(channel, callOptions);
    }

    /**
     */
    public void logTime(com.jervis.contracts.server.LogTimeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.LogTimeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getLogTimeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getSummary(com.jervis.contracts.server.GetSummaryRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetSummaryResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetSummaryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getCapacity(com.jervis.contracts.server.GetCapacityRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetCapacityResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetCapacityMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerTimeTrackingService.
   * <pre>
   * ServerTimeTrackingService — time log entry + rollup summary + weekly
   * capacity snapshot. Consumed by the orchestrator's chat tools (log_time,
   * time_summary, check_capacity).
   * </pre>
   */
  public static final class ServerTimeTrackingServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerTimeTrackingServiceBlockingV2Stub> {
    private ServerTimeTrackingServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTimeTrackingServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTimeTrackingServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.LogTimeResponse logTime(com.jervis.contracts.server.LogTimeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getLogTimeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetSummaryResponse getSummary(com.jervis.contracts.server.GetSummaryRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetSummaryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetCapacityResponse getCapacity(com.jervis.contracts.server.GetCapacityRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetCapacityMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerTimeTrackingService.
   * <pre>
   * ServerTimeTrackingService — time log entry + rollup summary + weekly
   * capacity snapshot. Consumed by the orchestrator's chat tools (log_time,
   * time_summary, check_capacity).
   * </pre>
   */
  public static final class ServerTimeTrackingServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerTimeTrackingServiceBlockingStub> {
    private ServerTimeTrackingServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTimeTrackingServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTimeTrackingServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.LogTimeResponse logTime(com.jervis.contracts.server.LogTimeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getLogTimeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetSummaryResponse getSummary(com.jervis.contracts.server.GetSummaryRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSummaryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetCapacityResponse getCapacity(com.jervis.contracts.server.GetCapacityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetCapacityMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerTimeTrackingService.
   * <pre>
   * ServerTimeTrackingService — time log entry + rollup summary + weekly
   * capacity snapshot. Consumed by the orchestrator's chat tools (log_time,
   * time_summary, check_capacity).
   * </pre>
   */
  public static final class ServerTimeTrackingServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerTimeTrackingServiceFutureStub> {
    private ServerTimeTrackingServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTimeTrackingServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTimeTrackingServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.LogTimeResponse> logTime(
        com.jervis.contracts.server.LogTimeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getLogTimeMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetSummaryResponse> getSummary(
        com.jervis.contracts.server.GetSummaryRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetSummaryMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetCapacityResponse> getCapacity(
        com.jervis.contracts.server.GetCapacityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetCapacityMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LOG_TIME = 0;
  private static final int METHODID_GET_SUMMARY = 1;
  private static final int METHODID_GET_CAPACITY = 2;

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
        case METHODID_LOG_TIME:
          serviceImpl.logTime((com.jervis.contracts.server.LogTimeRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.LogTimeResponse>) responseObserver);
          break;
        case METHODID_GET_SUMMARY:
          serviceImpl.getSummary((com.jervis.contracts.server.GetSummaryRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetSummaryResponse>) responseObserver);
          break;
        case METHODID_GET_CAPACITY:
          serviceImpl.getCapacity((com.jervis.contracts.server.GetCapacityRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetCapacityResponse>) responseObserver);
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
          getLogTimeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.LogTimeRequest,
              com.jervis.contracts.server.LogTimeResponse>(
                service, METHODID_LOG_TIME)))
        .addMethod(
          getGetSummaryMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetSummaryRequest,
              com.jervis.contracts.server.GetSummaryResponse>(
                service, METHODID_GET_SUMMARY)))
        .addMethod(
          getGetCapacityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetCapacityRequest,
              com.jervis.contracts.server.GetCapacityResponse>(
                service, METHODID_GET_CAPACITY)))
        .build();
  }

  private static abstract class ServerTimeTrackingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerTimeTrackingServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerTimeTrackingProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerTimeTrackingService");
    }
  }

  private static final class ServerTimeTrackingServiceFileDescriptorSupplier
      extends ServerTimeTrackingServiceBaseDescriptorSupplier {
    ServerTimeTrackingServiceFileDescriptorSupplier() {}
  }

  private static final class ServerTimeTrackingServiceMethodDescriptorSupplier
      extends ServerTimeTrackingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerTimeTrackingServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerTimeTrackingServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerTimeTrackingServiceFileDescriptorSupplier())
              .addMethod(getLogTimeMethod())
              .addMethod(getGetSummaryMethod())
              .addMethod(getGetCapacityMethod())
              .build();
        }
      }
    }
    return result;
  }
}
