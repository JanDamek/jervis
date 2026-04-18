package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerOpenRouterSettingsService is a reverse callback surface: the
 * ollama-router (router) calls the Kotlin server to (a) fetch settings
 * including model queues + persisted per-model stats, and (b) push back
 * in-memory stats periodically for MongoDB persistence.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerOpenRouterSettingsServiceGrpc {

  private ServerOpenRouterSettingsServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerOpenRouterSettingsService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetOpenRouterSettingsRequest,
      com.jervis.contracts.server.OpenRouterSettings> getGetSettingsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSettings",
      requestType = com.jervis.contracts.server.GetOpenRouterSettingsRequest.class,
      responseType = com.jervis.contracts.server.OpenRouterSettings.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetOpenRouterSettingsRequest,
      com.jervis.contracts.server.OpenRouterSettings> getGetSettingsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetOpenRouterSettingsRequest, com.jervis.contracts.server.OpenRouterSettings> getGetSettingsMethod;
    if ((getGetSettingsMethod = ServerOpenRouterSettingsServiceGrpc.getGetSettingsMethod) == null) {
      synchronized (ServerOpenRouterSettingsServiceGrpc.class) {
        if ((getGetSettingsMethod = ServerOpenRouterSettingsServiceGrpc.getGetSettingsMethod) == null) {
          ServerOpenRouterSettingsServiceGrpc.getGetSettingsMethod = getGetSettingsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetOpenRouterSettingsRequest, com.jervis.contracts.server.OpenRouterSettings>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSettings"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetOpenRouterSettingsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.OpenRouterSettings.getDefaultInstance()))
              .setSchemaDescriptor(new ServerOpenRouterSettingsServiceMethodDescriptorSupplier("GetSettings"))
              .build();
        }
      }
    }
    return getGetSettingsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.PersistModelStatsRequest,
      com.jervis.contracts.server.PersistModelStatsResponse> getPersistModelStatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PersistModelStats",
      requestType = com.jervis.contracts.server.PersistModelStatsRequest.class,
      responseType = com.jervis.contracts.server.PersistModelStatsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.PersistModelStatsRequest,
      com.jervis.contracts.server.PersistModelStatsResponse> getPersistModelStatsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.PersistModelStatsRequest, com.jervis.contracts.server.PersistModelStatsResponse> getPersistModelStatsMethod;
    if ((getPersistModelStatsMethod = ServerOpenRouterSettingsServiceGrpc.getPersistModelStatsMethod) == null) {
      synchronized (ServerOpenRouterSettingsServiceGrpc.class) {
        if ((getPersistModelStatsMethod = ServerOpenRouterSettingsServiceGrpc.getPersistModelStatsMethod) == null) {
          ServerOpenRouterSettingsServiceGrpc.getPersistModelStatsMethod = getPersistModelStatsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.PersistModelStatsRequest, com.jervis.contracts.server.PersistModelStatsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PersistModelStats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PersistModelStatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PersistModelStatsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerOpenRouterSettingsServiceMethodDescriptorSupplier("PersistModelStats"))
              .build();
        }
      }
    }
    return getPersistModelStatsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerOpenRouterSettingsServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerOpenRouterSettingsServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerOpenRouterSettingsServiceStub>() {
        @java.lang.Override
        public ServerOpenRouterSettingsServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerOpenRouterSettingsServiceStub(channel, callOptions);
        }
      };
    return ServerOpenRouterSettingsServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerOpenRouterSettingsServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerOpenRouterSettingsServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerOpenRouterSettingsServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerOpenRouterSettingsServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerOpenRouterSettingsServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerOpenRouterSettingsServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerOpenRouterSettingsServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerOpenRouterSettingsServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerOpenRouterSettingsServiceBlockingStub>() {
        @java.lang.Override
        public ServerOpenRouterSettingsServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerOpenRouterSettingsServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerOpenRouterSettingsServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerOpenRouterSettingsServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerOpenRouterSettingsServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerOpenRouterSettingsServiceFutureStub>() {
        @java.lang.Override
        public ServerOpenRouterSettingsServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerOpenRouterSettingsServiceFutureStub(channel, callOptions);
        }
      };
    return ServerOpenRouterSettingsServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerOpenRouterSettingsService is a reverse callback surface: the
   * ollama-router (router) calls the Kotlin server to (a) fetch settings
   * including model queues + persisted per-model stats, and (b) push back
   * in-memory stats periodically for MongoDB persistence.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void getSettings(com.jervis.contracts.server.GetOpenRouterSettingsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.OpenRouterSettings> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetSettingsMethod(), responseObserver);
    }

    /**
     */
    default void persistModelStats(com.jervis.contracts.server.PersistModelStatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PersistModelStatsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPersistModelStatsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerOpenRouterSettingsService.
   * <pre>
   * ServerOpenRouterSettingsService is a reverse callback surface: the
   * ollama-router (router) calls the Kotlin server to (a) fetch settings
   * including model queues + persisted per-model stats, and (b) push back
   * in-memory stats periodically for MongoDB persistence.
   * </pre>
   */
  public static abstract class ServerOpenRouterSettingsServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerOpenRouterSettingsServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerOpenRouterSettingsService.
   * <pre>
   * ServerOpenRouterSettingsService is a reverse callback surface: the
   * ollama-router (router) calls the Kotlin server to (a) fetch settings
   * including model queues + persisted per-model stats, and (b) push back
   * in-memory stats periodically for MongoDB persistence.
   * </pre>
   */
  public static final class ServerOpenRouterSettingsServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerOpenRouterSettingsServiceStub> {
    private ServerOpenRouterSettingsServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerOpenRouterSettingsServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerOpenRouterSettingsServiceStub(channel, callOptions);
    }

    /**
     */
    public void getSettings(com.jervis.contracts.server.GetOpenRouterSettingsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.OpenRouterSettings> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetSettingsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void persistModelStats(com.jervis.contracts.server.PersistModelStatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PersistModelStatsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPersistModelStatsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerOpenRouterSettingsService.
   * <pre>
   * ServerOpenRouterSettingsService is a reverse callback surface: the
   * ollama-router (router) calls the Kotlin server to (a) fetch settings
   * including model queues + persisted per-model stats, and (b) push back
   * in-memory stats periodically for MongoDB persistence.
   * </pre>
   */
  public static final class ServerOpenRouterSettingsServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerOpenRouterSettingsServiceBlockingV2Stub> {
    private ServerOpenRouterSettingsServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerOpenRouterSettingsServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerOpenRouterSettingsServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.OpenRouterSettings getSettings(com.jervis.contracts.server.GetOpenRouterSettingsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetSettingsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PersistModelStatsResponse persistModelStats(com.jervis.contracts.server.PersistModelStatsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPersistModelStatsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerOpenRouterSettingsService.
   * <pre>
   * ServerOpenRouterSettingsService is a reverse callback surface: the
   * ollama-router (router) calls the Kotlin server to (a) fetch settings
   * including model queues + persisted per-model stats, and (b) push back
   * in-memory stats periodically for MongoDB persistence.
   * </pre>
   */
  public static final class ServerOpenRouterSettingsServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerOpenRouterSettingsServiceBlockingStub> {
    private ServerOpenRouterSettingsServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerOpenRouterSettingsServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerOpenRouterSettingsServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.OpenRouterSettings getSettings(com.jervis.contracts.server.GetOpenRouterSettingsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSettingsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PersistModelStatsResponse persistModelStats(com.jervis.contracts.server.PersistModelStatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPersistModelStatsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerOpenRouterSettingsService.
   * <pre>
   * ServerOpenRouterSettingsService is a reverse callback surface: the
   * ollama-router (router) calls the Kotlin server to (a) fetch settings
   * including model queues + persisted per-model stats, and (b) push back
   * in-memory stats periodically for MongoDB persistence.
   * </pre>
   */
  public static final class ServerOpenRouterSettingsServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerOpenRouterSettingsServiceFutureStub> {
    private ServerOpenRouterSettingsServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerOpenRouterSettingsServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerOpenRouterSettingsServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.OpenRouterSettings> getSettings(
        com.jervis.contracts.server.GetOpenRouterSettingsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetSettingsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.PersistModelStatsResponse> persistModelStats(
        com.jervis.contracts.server.PersistModelStatsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPersistModelStatsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_SETTINGS = 0;
  private static final int METHODID_PERSIST_MODEL_STATS = 1;

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
        case METHODID_GET_SETTINGS:
          serviceImpl.getSettings((com.jervis.contracts.server.GetOpenRouterSettingsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.OpenRouterSettings>) responseObserver);
          break;
        case METHODID_PERSIST_MODEL_STATS:
          serviceImpl.persistModelStats((com.jervis.contracts.server.PersistModelStatsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.PersistModelStatsResponse>) responseObserver);
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
          getGetSettingsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetOpenRouterSettingsRequest,
              com.jervis.contracts.server.OpenRouterSettings>(
                service, METHODID_GET_SETTINGS)))
        .addMethod(
          getPersistModelStatsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.PersistModelStatsRequest,
              com.jervis.contracts.server.PersistModelStatsResponse>(
                service, METHODID_PERSIST_MODEL_STATS)))
        .build();
  }

  private static abstract class ServerOpenRouterSettingsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerOpenRouterSettingsServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerOpenRouterSettingsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerOpenRouterSettingsService");
    }
  }

  private static final class ServerOpenRouterSettingsServiceFileDescriptorSupplier
      extends ServerOpenRouterSettingsServiceBaseDescriptorSupplier {
    ServerOpenRouterSettingsServiceFileDescriptorSupplier() {}
  }

  private static final class ServerOpenRouterSettingsServiceMethodDescriptorSupplier
      extends ServerOpenRouterSettingsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerOpenRouterSettingsServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerOpenRouterSettingsServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerOpenRouterSettingsServiceFileDescriptorSupplier())
              .addMethod(getGetSettingsMethod())
              .addMethod(getPersistModelStatsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
