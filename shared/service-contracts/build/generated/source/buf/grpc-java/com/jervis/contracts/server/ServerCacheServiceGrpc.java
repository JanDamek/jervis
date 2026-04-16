package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerCacheService is the gRPC surface of the Kotlin server's in-memory
 * cache invalidation. Called by the Python orchestrator after writing to
 * a Mongo collection that has a Kotlin-side cache (today: `guidelines`).
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerCacheServiceGrpc {

  private ServerCacheServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerCacheService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CacheInvalidateRequest,
      com.jervis.contracts.server.CacheInvalidateResponse> getInvalidateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Invalidate",
      requestType = com.jervis.contracts.server.CacheInvalidateRequest.class,
      responseType = com.jervis.contracts.server.CacheInvalidateResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CacheInvalidateRequest,
      com.jervis.contracts.server.CacheInvalidateResponse> getInvalidateMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CacheInvalidateRequest, com.jervis.contracts.server.CacheInvalidateResponse> getInvalidateMethod;
    if ((getInvalidateMethod = ServerCacheServiceGrpc.getInvalidateMethod) == null) {
      synchronized (ServerCacheServiceGrpc.class) {
        if ((getInvalidateMethod = ServerCacheServiceGrpc.getInvalidateMethod) == null) {
          ServerCacheServiceGrpc.getInvalidateMethod = getInvalidateMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CacheInvalidateRequest, com.jervis.contracts.server.CacheInvalidateResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Invalidate"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CacheInvalidateRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CacheInvalidateResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerCacheServiceMethodDescriptorSupplier("Invalidate"))
              .build();
        }
      }
    }
    return getInvalidateMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerCacheServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerCacheServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerCacheServiceStub>() {
        @java.lang.Override
        public ServerCacheServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerCacheServiceStub(channel, callOptions);
        }
      };
    return ServerCacheServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerCacheServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerCacheServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerCacheServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerCacheServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerCacheServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerCacheServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerCacheServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerCacheServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerCacheServiceBlockingStub>() {
        @java.lang.Override
        public ServerCacheServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerCacheServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerCacheServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerCacheServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerCacheServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerCacheServiceFutureStub>() {
        @java.lang.Override
        public ServerCacheServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerCacheServiceFutureStub(channel, callOptions);
        }
      };
    return ServerCacheServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerCacheService is the gRPC surface of the Kotlin server's in-memory
   * cache invalidation. Called by the Python orchestrator after writing to
   * a Mongo collection that has a Kotlin-side cache (today: `guidelines`).
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Drop the in-memory cache for `collection`. Unknown collection = no-op.
     * </pre>
     */
    default void invalidate(com.jervis.contracts.server.CacheInvalidateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CacheInvalidateResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInvalidateMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerCacheService.
   * <pre>
   * ServerCacheService is the gRPC surface of the Kotlin server's in-memory
   * cache invalidation. Called by the Python orchestrator after writing to
   * a Mongo collection that has a Kotlin-side cache (today: `guidelines`).
   * </pre>
   */
  public static abstract class ServerCacheServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerCacheServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerCacheService.
   * <pre>
   * ServerCacheService is the gRPC surface of the Kotlin server's in-memory
   * cache invalidation. Called by the Python orchestrator after writing to
   * a Mongo collection that has a Kotlin-side cache (today: `guidelines`).
   * </pre>
   */
  public static final class ServerCacheServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerCacheServiceStub> {
    private ServerCacheServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerCacheServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerCacheServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Drop the in-memory cache for `collection`. Unknown collection = no-op.
     * </pre>
     */
    public void invalidate(com.jervis.contracts.server.CacheInvalidateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CacheInvalidateResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInvalidateMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerCacheService.
   * <pre>
   * ServerCacheService is the gRPC surface of the Kotlin server's in-memory
   * cache invalidation. Called by the Python orchestrator after writing to
   * a Mongo collection that has a Kotlin-side cache (today: `guidelines`).
   * </pre>
   */
  public static final class ServerCacheServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerCacheServiceBlockingV2Stub> {
    private ServerCacheServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerCacheServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerCacheServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Drop the in-memory cache for `collection`. Unknown collection = no-op.
     * </pre>
     */
    public com.jervis.contracts.server.CacheInvalidateResponse invalidate(com.jervis.contracts.server.CacheInvalidateRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInvalidateMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerCacheService.
   * <pre>
   * ServerCacheService is the gRPC surface of the Kotlin server's in-memory
   * cache invalidation. Called by the Python orchestrator after writing to
   * a Mongo collection that has a Kotlin-side cache (today: `guidelines`).
   * </pre>
   */
  public static final class ServerCacheServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerCacheServiceBlockingStub> {
    private ServerCacheServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerCacheServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerCacheServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Drop the in-memory cache for `collection`. Unknown collection = no-op.
     * </pre>
     */
    public com.jervis.contracts.server.CacheInvalidateResponse invalidate(com.jervis.contracts.server.CacheInvalidateRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInvalidateMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerCacheService.
   * <pre>
   * ServerCacheService is the gRPC surface of the Kotlin server's in-memory
   * cache invalidation. Called by the Python orchestrator after writing to
   * a Mongo collection that has a Kotlin-side cache (today: `guidelines`).
   * </pre>
   */
  public static final class ServerCacheServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerCacheServiceFutureStub> {
    private ServerCacheServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerCacheServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerCacheServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Drop the in-memory cache for `collection`. Unknown collection = no-op.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CacheInvalidateResponse> invalidate(
        com.jervis.contracts.server.CacheInvalidateRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInvalidateMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_INVALIDATE = 0;

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
        case METHODID_INVALIDATE:
          serviceImpl.invalidate((com.jervis.contracts.server.CacheInvalidateRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CacheInvalidateResponse>) responseObserver);
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
          getInvalidateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CacheInvalidateRequest,
              com.jervis.contracts.server.CacheInvalidateResponse>(
                service, METHODID_INVALIDATE)))
        .build();
  }

  private static abstract class ServerCacheServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerCacheServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerCacheProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerCacheService");
    }
  }

  private static final class ServerCacheServiceFileDescriptorSupplier
      extends ServerCacheServiceBaseDescriptorSupplier {
    ServerCacheServiceFileDescriptorSupplier() {}
  }

  private static final class ServerCacheServiceMethodDescriptorSupplier
      extends ServerCacheServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerCacheServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerCacheServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerCacheServiceFileDescriptorSupplier())
              .addMethod(getInvalidateMethod())
              .build();
        }
      }
    }
    return result;
  }
}
