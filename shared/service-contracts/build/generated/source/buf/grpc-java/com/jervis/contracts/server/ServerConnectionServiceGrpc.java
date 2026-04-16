package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Connection-lifecycle RPCs — currently just off-hours relogin approval.
 * Kept as a standalone service because the domain is clearly "what does
 * the server do on behalf of connections"; more lifecycle endpoints
 * (health check, capabilities refresh, manual relogin trigger) land here
 * as they migrate.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerConnectionServiceGrpc {

  private ServerConnectionServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerConnectionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ApproveReloginRequest,
      com.jervis.contracts.server.ApproveReloginResponse> getApproveReloginMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ApproveRelogin",
      requestType = com.jervis.contracts.server.ApproveReloginRequest.class,
      responseType = com.jervis.contracts.server.ApproveReloginResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ApproveReloginRequest,
      com.jervis.contracts.server.ApproveReloginResponse> getApproveReloginMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ApproveReloginRequest, com.jervis.contracts.server.ApproveReloginResponse> getApproveReloginMethod;
    if ((getApproveReloginMethod = ServerConnectionServiceGrpc.getApproveReloginMethod) == null) {
      synchronized (ServerConnectionServiceGrpc.class) {
        if ((getApproveReloginMethod = ServerConnectionServiceGrpc.getApproveReloginMethod) == null) {
          ServerConnectionServiceGrpc.getApproveReloginMethod = getApproveReloginMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ApproveReloginRequest, com.jervis.contracts.server.ApproveReloginResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ApproveRelogin"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ApproveReloginRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ApproveReloginResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerConnectionServiceMethodDescriptorSupplier("ApproveRelogin"))
              .build();
        }
      }
    }
    return getApproveReloginMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerConnectionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerConnectionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerConnectionServiceStub>() {
        @java.lang.Override
        public ServerConnectionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerConnectionServiceStub(channel, callOptions);
        }
      };
    return ServerConnectionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerConnectionServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerConnectionServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerConnectionServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerConnectionServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerConnectionServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerConnectionServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerConnectionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerConnectionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerConnectionServiceBlockingStub>() {
        @java.lang.Override
        public ServerConnectionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerConnectionServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerConnectionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerConnectionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerConnectionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerConnectionServiceFutureStub>() {
        @java.lang.Override
        public ServerConnectionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerConnectionServiceFutureStub(channel, callOptions);
        }
      };
    return ServerConnectionServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Connection-lifecycle RPCs — currently just off-hours relogin approval.
   * Kept as a standalone service because the domain is clearly "what does
   * the server do on behalf of connections"; more lifecycle endpoints
   * (health check, capabilities refresh, manual relogin trigger) land here
   * as they migrate.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Dispatch `approve_relogin` to the pod agent for the given connection.
     * </pre>
     */
    default void approveRelogin(com.jervis.contracts.server.ApproveReloginRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ApproveReloginResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getApproveReloginMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerConnectionService.
   * <pre>
   * Connection-lifecycle RPCs — currently just off-hours relogin approval.
   * Kept as a standalone service because the domain is clearly "what does
   * the server do on behalf of connections"; more lifecycle endpoints
   * (health check, capabilities refresh, manual relogin trigger) land here
   * as they migrate.
   * </pre>
   */
  public static abstract class ServerConnectionServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerConnectionServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerConnectionService.
   * <pre>
   * Connection-lifecycle RPCs — currently just off-hours relogin approval.
   * Kept as a standalone service because the domain is clearly "what does
   * the server do on behalf of connections"; more lifecycle endpoints
   * (health check, capabilities refresh, manual relogin trigger) land here
   * as they migrate.
   * </pre>
   */
  public static final class ServerConnectionServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerConnectionServiceStub> {
    private ServerConnectionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerConnectionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerConnectionServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Dispatch `approve_relogin` to the pod agent for the given connection.
     * </pre>
     */
    public void approveRelogin(com.jervis.contracts.server.ApproveReloginRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ApproveReloginResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getApproveReloginMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerConnectionService.
   * <pre>
   * Connection-lifecycle RPCs — currently just off-hours relogin approval.
   * Kept as a standalone service because the domain is clearly "what does
   * the server do on behalf of connections"; more lifecycle endpoints
   * (health check, capabilities refresh, manual relogin trigger) land here
   * as they migrate.
   * </pre>
   */
  public static final class ServerConnectionServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerConnectionServiceBlockingV2Stub> {
    private ServerConnectionServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerConnectionServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerConnectionServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Dispatch `approve_relogin` to the pod agent for the given connection.
     * </pre>
     */
    public com.jervis.contracts.server.ApproveReloginResponse approveRelogin(com.jervis.contracts.server.ApproveReloginRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getApproveReloginMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerConnectionService.
   * <pre>
   * Connection-lifecycle RPCs — currently just off-hours relogin approval.
   * Kept as a standalone service because the domain is clearly "what does
   * the server do on behalf of connections"; more lifecycle endpoints
   * (health check, capabilities refresh, manual relogin trigger) land here
   * as they migrate.
   * </pre>
   */
  public static final class ServerConnectionServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerConnectionServiceBlockingStub> {
    private ServerConnectionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerConnectionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerConnectionServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Dispatch `approve_relogin` to the pod agent for the given connection.
     * </pre>
     */
    public com.jervis.contracts.server.ApproveReloginResponse approveRelogin(com.jervis.contracts.server.ApproveReloginRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getApproveReloginMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerConnectionService.
   * <pre>
   * Connection-lifecycle RPCs — currently just off-hours relogin approval.
   * Kept as a standalone service because the domain is clearly "what does
   * the server do on behalf of connections"; more lifecycle endpoints
   * (health check, capabilities refresh, manual relogin trigger) land here
   * as they migrate.
   * </pre>
   */
  public static final class ServerConnectionServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerConnectionServiceFutureStub> {
    private ServerConnectionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerConnectionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerConnectionServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Dispatch `approve_relogin` to the pod agent for the given connection.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ApproveReloginResponse> approveRelogin(
        com.jervis.contracts.server.ApproveReloginRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getApproveReloginMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_APPROVE_RELOGIN = 0;

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
        case METHODID_APPROVE_RELOGIN:
          serviceImpl.approveRelogin((com.jervis.contracts.server.ApproveReloginRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ApproveReloginResponse>) responseObserver);
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
          getApproveReloginMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ApproveReloginRequest,
              com.jervis.contracts.server.ApproveReloginResponse>(
                service, METHODID_APPROVE_RELOGIN)))
        .build();
  }

  private static abstract class ServerConnectionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerConnectionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerConnectionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerConnectionService");
    }
  }

  private static final class ServerConnectionServiceFileDescriptorSupplier
      extends ServerConnectionServiceBaseDescriptorSupplier {
    ServerConnectionServiceFileDescriptorSupplier() {}
  }

  private static final class ServerConnectionServiceMethodDescriptorSupplier
      extends ServerConnectionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerConnectionServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerConnectionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerConnectionServiceFileDescriptorSupplier())
              .addMethod(getApproveReloginMethod())
              .build();
        }
      }
    }
    return result;
  }
}
