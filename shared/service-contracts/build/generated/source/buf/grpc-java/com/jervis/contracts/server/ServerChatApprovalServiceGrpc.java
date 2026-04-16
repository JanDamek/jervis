package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerChatApprovalService — fan-out for pending chat approvals and
 * resolved-approval dismissals. Called fire-and-forget by the orchestrator
 * whenever `ApprovalRequiredInterrupt` is raised or resolved.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerChatApprovalServiceGrpc {

  private ServerChatApprovalServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerChatApprovalService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ApprovalBroadcastRequest,
      com.jervis.contracts.server.ApprovalBroadcastResponse> getBroadcastMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Broadcast",
      requestType = com.jervis.contracts.server.ApprovalBroadcastRequest.class,
      responseType = com.jervis.contracts.server.ApprovalBroadcastResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ApprovalBroadcastRequest,
      com.jervis.contracts.server.ApprovalBroadcastResponse> getBroadcastMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ApprovalBroadcastRequest, com.jervis.contracts.server.ApprovalBroadcastResponse> getBroadcastMethod;
    if ((getBroadcastMethod = ServerChatApprovalServiceGrpc.getBroadcastMethod) == null) {
      synchronized (ServerChatApprovalServiceGrpc.class) {
        if ((getBroadcastMethod = ServerChatApprovalServiceGrpc.getBroadcastMethod) == null) {
          ServerChatApprovalServiceGrpc.getBroadcastMethod = getBroadcastMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ApprovalBroadcastRequest, com.jervis.contracts.server.ApprovalBroadcastResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Broadcast"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ApprovalBroadcastRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ApprovalBroadcastResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerChatApprovalServiceMethodDescriptorSupplier("Broadcast"))
              .build();
        }
      }
    }
    return getBroadcastMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ApprovalResolvedRequest,
      com.jervis.contracts.server.ApprovalResolvedResponse> getResolvedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Resolved",
      requestType = com.jervis.contracts.server.ApprovalResolvedRequest.class,
      responseType = com.jervis.contracts.server.ApprovalResolvedResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ApprovalResolvedRequest,
      com.jervis.contracts.server.ApprovalResolvedResponse> getResolvedMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ApprovalResolvedRequest, com.jervis.contracts.server.ApprovalResolvedResponse> getResolvedMethod;
    if ((getResolvedMethod = ServerChatApprovalServiceGrpc.getResolvedMethod) == null) {
      synchronized (ServerChatApprovalServiceGrpc.class) {
        if ((getResolvedMethod = ServerChatApprovalServiceGrpc.getResolvedMethod) == null) {
          ServerChatApprovalServiceGrpc.getResolvedMethod = getResolvedMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ApprovalResolvedRequest, com.jervis.contracts.server.ApprovalResolvedResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Resolved"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ApprovalResolvedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ApprovalResolvedResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerChatApprovalServiceMethodDescriptorSupplier("Resolved"))
              .build();
        }
      }
    }
    return getResolvedMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerChatApprovalServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerChatApprovalServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerChatApprovalServiceStub>() {
        @java.lang.Override
        public ServerChatApprovalServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerChatApprovalServiceStub(channel, callOptions);
        }
      };
    return ServerChatApprovalServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerChatApprovalServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerChatApprovalServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerChatApprovalServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerChatApprovalServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerChatApprovalServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerChatApprovalServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerChatApprovalServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerChatApprovalServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerChatApprovalServiceBlockingStub>() {
        @java.lang.Override
        public ServerChatApprovalServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerChatApprovalServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerChatApprovalServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerChatApprovalServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerChatApprovalServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerChatApprovalServiceFutureStub>() {
        @java.lang.Override
        public ServerChatApprovalServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerChatApprovalServiceFutureStub(channel, callOptions);
        }
      };
    return ServerChatApprovalServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerChatApprovalService — fan-out for pending chat approvals and
   * resolved-approval dismissals. Called fire-and-forget by the orchestrator
   * whenever `ApprovalRequiredInterrupt` is raised or resolved.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Emit kRPC event + FCM + APNs push so all of the user's devices
     * show an approval dialog.
     * </pre>
     */
    default void broadcast(com.jervis.contracts.server.ApprovalBroadcastRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ApprovalBroadcastResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getBroadcastMethod(), responseObserver);
    }

    /**
     * <pre>
     * Tell other devices their dialog is stale (first-wins dedupe).
     * </pre>
     */
    default void resolved(com.jervis.contracts.server.ApprovalResolvedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ApprovalResolvedResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getResolvedMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerChatApprovalService.
   * <pre>
   * ServerChatApprovalService — fan-out for pending chat approvals and
   * resolved-approval dismissals. Called fire-and-forget by the orchestrator
   * whenever `ApprovalRequiredInterrupt` is raised or resolved.
   * </pre>
   */
  public static abstract class ServerChatApprovalServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerChatApprovalServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerChatApprovalService.
   * <pre>
   * ServerChatApprovalService — fan-out for pending chat approvals and
   * resolved-approval dismissals. Called fire-and-forget by the orchestrator
   * whenever `ApprovalRequiredInterrupt` is raised or resolved.
   * </pre>
   */
  public static final class ServerChatApprovalServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerChatApprovalServiceStub> {
    private ServerChatApprovalServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerChatApprovalServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerChatApprovalServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Emit kRPC event + FCM + APNs push so all of the user's devices
     * show an approval dialog.
     * </pre>
     */
    public void broadcast(com.jervis.contracts.server.ApprovalBroadcastRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ApprovalBroadcastResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getBroadcastMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Tell other devices their dialog is stale (first-wins dedupe).
     * </pre>
     */
    public void resolved(com.jervis.contracts.server.ApprovalResolvedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ApprovalResolvedResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getResolvedMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerChatApprovalService.
   * <pre>
   * ServerChatApprovalService — fan-out for pending chat approvals and
   * resolved-approval dismissals. Called fire-and-forget by the orchestrator
   * whenever `ApprovalRequiredInterrupt` is raised or resolved.
   * </pre>
   */
  public static final class ServerChatApprovalServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerChatApprovalServiceBlockingV2Stub> {
    private ServerChatApprovalServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerChatApprovalServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerChatApprovalServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Emit kRPC event + FCM + APNs push so all of the user's devices
     * show an approval dialog.
     * </pre>
     */
    public com.jervis.contracts.server.ApprovalBroadcastResponse broadcast(com.jervis.contracts.server.ApprovalBroadcastRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getBroadcastMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Tell other devices their dialog is stale (first-wins dedupe).
     * </pre>
     */
    public com.jervis.contracts.server.ApprovalResolvedResponse resolved(com.jervis.contracts.server.ApprovalResolvedRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getResolvedMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerChatApprovalService.
   * <pre>
   * ServerChatApprovalService — fan-out for pending chat approvals and
   * resolved-approval dismissals. Called fire-and-forget by the orchestrator
   * whenever `ApprovalRequiredInterrupt` is raised or resolved.
   * </pre>
   */
  public static final class ServerChatApprovalServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerChatApprovalServiceBlockingStub> {
    private ServerChatApprovalServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerChatApprovalServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerChatApprovalServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Emit kRPC event + FCM + APNs push so all of the user's devices
     * show an approval dialog.
     * </pre>
     */
    public com.jervis.contracts.server.ApprovalBroadcastResponse broadcast(com.jervis.contracts.server.ApprovalBroadcastRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getBroadcastMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Tell other devices their dialog is stale (first-wins dedupe).
     * </pre>
     */
    public com.jervis.contracts.server.ApprovalResolvedResponse resolved(com.jervis.contracts.server.ApprovalResolvedRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getResolvedMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerChatApprovalService.
   * <pre>
   * ServerChatApprovalService — fan-out for pending chat approvals and
   * resolved-approval dismissals. Called fire-and-forget by the orchestrator
   * whenever `ApprovalRequiredInterrupt` is raised or resolved.
   * </pre>
   */
  public static final class ServerChatApprovalServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerChatApprovalServiceFutureStub> {
    private ServerChatApprovalServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerChatApprovalServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerChatApprovalServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Emit kRPC event + FCM + APNs push so all of the user's devices
     * show an approval dialog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ApprovalBroadcastResponse> broadcast(
        com.jervis.contracts.server.ApprovalBroadcastRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getBroadcastMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Tell other devices their dialog is stale (first-wins dedupe).
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ApprovalResolvedResponse> resolved(
        com.jervis.contracts.server.ApprovalResolvedRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getResolvedMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_BROADCAST = 0;
  private static final int METHODID_RESOLVED = 1;

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
        case METHODID_BROADCAST:
          serviceImpl.broadcast((com.jervis.contracts.server.ApprovalBroadcastRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ApprovalBroadcastResponse>) responseObserver);
          break;
        case METHODID_RESOLVED:
          serviceImpl.resolved((com.jervis.contracts.server.ApprovalResolvedRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ApprovalResolvedResponse>) responseObserver);
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
          getBroadcastMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ApprovalBroadcastRequest,
              com.jervis.contracts.server.ApprovalBroadcastResponse>(
                service, METHODID_BROADCAST)))
        .addMethod(
          getResolvedMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ApprovalResolvedRequest,
              com.jervis.contracts.server.ApprovalResolvedResponse>(
                service, METHODID_RESOLVED)))
        .build();
  }

  private static abstract class ServerChatApprovalServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerChatApprovalServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerChatApprovalProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerChatApprovalService");
    }
  }

  private static final class ServerChatApprovalServiceFileDescriptorSupplier
      extends ServerChatApprovalServiceBaseDescriptorSupplier {
    ServerChatApprovalServiceFileDescriptorSupplier() {}
  }

  private static final class ServerChatApprovalServiceMethodDescriptorSupplier
      extends ServerChatApprovalServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerChatApprovalServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerChatApprovalServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerChatApprovalServiceFileDescriptorSupplier())
              .addMethod(getBroadcastMethod())
              .addMethod(getResolvedMethod())
              .build();
        }
      }
    }
    return result;
  }
}
