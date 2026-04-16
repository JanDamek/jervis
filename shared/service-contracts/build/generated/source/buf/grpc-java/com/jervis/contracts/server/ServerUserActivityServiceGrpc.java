package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerUserActivityServiceGrpc {

  private ServerUserActivityServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerUserActivityService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.LastActivityRequest,
      com.jervis.contracts.server.LastActivityResponse> getLastActivityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "LastActivity",
      requestType = com.jervis.contracts.server.LastActivityRequest.class,
      responseType = com.jervis.contracts.server.LastActivityResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.LastActivityRequest,
      com.jervis.contracts.server.LastActivityResponse> getLastActivityMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.LastActivityRequest, com.jervis.contracts.server.LastActivityResponse> getLastActivityMethod;
    if ((getLastActivityMethod = ServerUserActivityServiceGrpc.getLastActivityMethod) == null) {
      synchronized (ServerUserActivityServiceGrpc.class) {
        if ((getLastActivityMethod = ServerUserActivityServiceGrpc.getLastActivityMethod) == null) {
          ServerUserActivityServiceGrpc.getLastActivityMethod = getLastActivityMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.LastActivityRequest, com.jervis.contracts.server.LastActivityResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "LastActivity"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.LastActivityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.LastActivityResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerUserActivityServiceMethodDescriptorSupplier("LastActivity"))
              .build();
        }
      }
    }
    return getLastActivityMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerUserActivityServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerUserActivityServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerUserActivityServiceStub>() {
        @java.lang.Override
        public ServerUserActivityServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerUserActivityServiceStub(channel, callOptions);
        }
      };
    return ServerUserActivityServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerUserActivityServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerUserActivityServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerUserActivityServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerUserActivityServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerUserActivityServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerUserActivityServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerUserActivityServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerUserActivityServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerUserActivityServiceBlockingStub>() {
        @java.lang.Override
        public ServerUserActivityServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerUserActivityServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerUserActivityServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerUserActivityServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerUserActivityServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerUserActivityServiceFutureStub>() {
        @java.lang.Override
        public ServerUserActivityServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerUserActivityServiceFutureStub(channel, callOptions);
        }
      };
    return ServerUserActivityServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Seconds since the client's UI was last active (inferred from last
     * kRPC ping). Returns a very large number when unknown so callers
     * stay conservative (teams-pod off-hours guard).
     * </pre>
     */
    default void lastActivity(com.jervis.contracts.server.LastActivityRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.LastActivityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getLastActivityMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerUserActivityService.
   */
  public static abstract class ServerUserActivityServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerUserActivityServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerUserActivityService.
   */
  public static final class ServerUserActivityServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerUserActivityServiceStub> {
    private ServerUserActivityServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerUserActivityServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerUserActivityServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Seconds since the client's UI was last active (inferred from last
     * kRPC ping). Returns a very large number when unknown so callers
     * stay conservative (teams-pod off-hours guard).
     * </pre>
     */
    public void lastActivity(com.jervis.contracts.server.LastActivityRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.LastActivityResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getLastActivityMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerUserActivityService.
   */
  public static final class ServerUserActivityServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerUserActivityServiceBlockingV2Stub> {
    private ServerUserActivityServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerUserActivityServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerUserActivityServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Seconds since the client's UI was last active (inferred from last
     * kRPC ping). Returns a very large number when unknown so callers
     * stay conservative (teams-pod off-hours guard).
     * </pre>
     */
    public com.jervis.contracts.server.LastActivityResponse lastActivity(com.jervis.contracts.server.LastActivityRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getLastActivityMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerUserActivityService.
   */
  public static final class ServerUserActivityServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerUserActivityServiceBlockingStub> {
    private ServerUserActivityServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerUserActivityServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerUserActivityServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Seconds since the client's UI was last active (inferred from last
     * kRPC ping). Returns a very large number when unknown so callers
     * stay conservative (teams-pod off-hours guard).
     * </pre>
     */
    public com.jervis.contracts.server.LastActivityResponse lastActivity(com.jervis.contracts.server.LastActivityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getLastActivityMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerUserActivityService.
   */
  public static final class ServerUserActivityServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerUserActivityServiceFutureStub> {
    private ServerUserActivityServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerUserActivityServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerUserActivityServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Seconds since the client's UI was last active (inferred from last
     * kRPC ping). Returns a very large number when unknown so callers
     * stay conservative (teams-pod off-hours guard).
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.LastActivityResponse> lastActivity(
        com.jervis.contracts.server.LastActivityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getLastActivityMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LAST_ACTIVITY = 0;

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
        case METHODID_LAST_ACTIVITY:
          serviceImpl.lastActivity((com.jervis.contracts.server.LastActivityRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.LastActivityResponse>) responseObserver);
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
          getLastActivityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.LastActivityRequest,
              com.jervis.contracts.server.LastActivityResponse>(
                service, METHODID_LAST_ACTIVITY)))
        .build();
  }

  private static abstract class ServerUserActivityServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerUserActivityServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerO365ResourcesProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerUserActivityService");
    }
  }

  private static final class ServerUserActivityServiceFileDescriptorSupplier
      extends ServerUserActivityServiceBaseDescriptorSupplier {
    ServerUserActivityServiceFileDescriptorSupplier() {}
  }

  private static final class ServerUserActivityServiceMethodDescriptorSupplier
      extends ServerUserActivityServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerUserActivityServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerUserActivityServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerUserActivityServiceFileDescriptorSupplier())
              .addMethod(getLastActivityMethod())
              .build();
        }
      }
    }
    return result;
  }
}
