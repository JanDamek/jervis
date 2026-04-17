package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerGpuIdleService is a reverse callback surface: the ollama-router
 * calls the Kotlin server when the local GPU pool has been idle for
 * longer than `gpu_idle_notify_after_s` (default 2 min). The server
 * triggers BackgroundEngine.onGpuIdle() so analytical / proactive tasks
 * run immediately instead of waiting for the 30-min idle review loop.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerGpuIdleServiceGrpc {

  private ServerGpuIdleServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerGpuIdleService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GpuIdleRequest,
      com.jervis.contracts.server.GpuIdleResponse> getGpuIdleMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GpuIdle",
      requestType = com.jervis.contracts.server.GpuIdleRequest.class,
      responseType = com.jervis.contracts.server.GpuIdleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GpuIdleRequest,
      com.jervis.contracts.server.GpuIdleResponse> getGpuIdleMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GpuIdleRequest, com.jervis.contracts.server.GpuIdleResponse> getGpuIdleMethod;
    if ((getGpuIdleMethod = ServerGpuIdleServiceGrpc.getGpuIdleMethod) == null) {
      synchronized (ServerGpuIdleServiceGrpc.class) {
        if ((getGpuIdleMethod = ServerGpuIdleServiceGrpc.getGpuIdleMethod) == null) {
          ServerGpuIdleServiceGrpc.getGpuIdleMethod = getGpuIdleMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GpuIdleRequest, com.jervis.contracts.server.GpuIdleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GpuIdle"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GpuIdleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GpuIdleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerGpuIdleServiceMethodDescriptorSupplier("GpuIdle"))
              .build();
        }
      }
    }
    return getGpuIdleMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerGpuIdleServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGpuIdleServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGpuIdleServiceStub>() {
        @java.lang.Override
        public ServerGpuIdleServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGpuIdleServiceStub(channel, callOptions);
        }
      };
    return ServerGpuIdleServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerGpuIdleServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGpuIdleServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGpuIdleServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerGpuIdleServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGpuIdleServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerGpuIdleServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerGpuIdleServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGpuIdleServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGpuIdleServiceBlockingStub>() {
        @java.lang.Override
        public ServerGpuIdleServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGpuIdleServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerGpuIdleServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerGpuIdleServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGpuIdleServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGpuIdleServiceFutureStub>() {
        @java.lang.Override
        public ServerGpuIdleServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGpuIdleServiceFutureStub(channel, callOptions);
        }
      };
    return ServerGpuIdleServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerGpuIdleService is a reverse callback surface: the ollama-router
   * calls the Kotlin server when the local GPU pool has been idle for
   * longer than `gpu_idle_notify_after_s` (default 2 min). The server
   * triggers BackgroundEngine.onGpuIdle() so analytical / proactive tasks
   * run immediately instead of waiting for the 30-min idle review loop.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void gpuIdle(com.jervis.contracts.server.GpuIdleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GpuIdleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGpuIdleMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerGpuIdleService.
   * <pre>
   * ServerGpuIdleService is a reverse callback surface: the ollama-router
   * calls the Kotlin server when the local GPU pool has been idle for
   * longer than `gpu_idle_notify_after_s` (default 2 min). The server
   * triggers BackgroundEngine.onGpuIdle() so analytical / proactive tasks
   * run immediately instead of waiting for the 30-min idle review loop.
   * </pre>
   */
  public static abstract class ServerGpuIdleServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerGpuIdleServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerGpuIdleService.
   * <pre>
   * ServerGpuIdleService is a reverse callback surface: the ollama-router
   * calls the Kotlin server when the local GPU pool has been idle for
   * longer than `gpu_idle_notify_after_s` (default 2 min). The server
   * triggers BackgroundEngine.onGpuIdle() so analytical / proactive tasks
   * run immediately instead of waiting for the 30-min idle review loop.
   * </pre>
   */
  public static final class ServerGpuIdleServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerGpuIdleServiceStub> {
    private ServerGpuIdleServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGpuIdleServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGpuIdleServiceStub(channel, callOptions);
    }

    /**
     */
    public void gpuIdle(com.jervis.contracts.server.GpuIdleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GpuIdleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGpuIdleMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerGpuIdleService.
   * <pre>
   * ServerGpuIdleService is a reverse callback surface: the ollama-router
   * calls the Kotlin server when the local GPU pool has been idle for
   * longer than `gpu_idle_notify_after_s` (default 2 min). The server
   * triggers BackgroundEngine.onGpuIdle() so analytical / proactive tasks
   * run immediately instead of waiting for the 30-min idle review loop.
   * </pre>
   */
  public static final class ServerGpuIdleServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerGpuIdleServiceBlockingV2Stub> {
    private ServerGpuIdleServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGpuIdleServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGpuIdleServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.GpuIdleResponse gpuIdle(com.jervis.contracts.server.GpuIdleRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGpuIdleMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerGpuIdleService.
   * <pre>
   * ServerGpuIdleService is a reverse callback surface: the ollama-router
   * calls the Kotlin server when the local GPU pool has been idle for
   * longer than `gpu_idle_notify_after_s` (default 2 min). The server
   * triggers BackgroundEngine.onGpuIdle() so analytical / proactive tasks
   * run immediately instead of waiting for the 30-min idle review loop.
   * </pre>
   */
  public static final class ServerGpuIdleServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerGpuIdleServiceBlockingStub> {
    private ServerGpuIdleServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGpuIdleServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGpuIdleServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.GpuIdleResponse gpuIdle(com.jervis.contracts.server.GpuIdleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGpuIdleMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerGpuIdleService.
   * <pre>
   * ServerGpuIdleService is a reverse callback surface: the ollama-router
   * calls the Kotlin server when the local GPU pool has been idle for
   * longer than `gpu_idle_notify_after_s` (default 2 min). The server
   * triggers BackgroundEngine.onGpuIdle() so analytical / proactive tasks
   * run immediately instead of waiting for the 30-min idle review loop.
   * </pre>
   */
  public static final class ServerGpuIdleServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerGpuIdleServiceFutureStub> {
    private ServerGpuIdleServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGpuIdleServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGpuIdleServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GpuIdleResponse> gpuIdle(
        com.jervis.contracts.server.GpuIdleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGpuIdleMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GPU_IDLE = 0;

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
        case METHODID_GPU_IDLE:
          serviceImpl.gpuIdle((com.jervis.contracts.server.GpuIdleRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GpuIdleResponse>) responseObserver);
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
          getGpuIdleMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GpuIdleRequest,
              com.jervis.contracts.server.GpuIdleResponse>(
                service, METHODID_GPU_IDLE)))
        .build();
  }

  private static abstract class ServerGpuIdleServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerGpuIdleServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerGpuIdleProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerGpuIdleService");
    }
  }

  private static final class ServerGpuIdleServiceFileDescriptorSupplier
      extends ServerGpuIdleServiceBaseDescriptorSupplier {
    ServerGpuIdleServiceFileDescriptorSupplier() {}
  }

  private static final class ServerGpuIdleServiceMethodDescriptorSupplier
      extends ServerGpuIdleServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerGpuIdleServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerGpuIdleServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerGpuIdleServiceFileDescriptorSupplier())
              .addMethod(getGpuIdleMethod())
              .build();
        }
      }
    }
    return result;
  }
}
