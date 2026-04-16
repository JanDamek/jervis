package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Two tiny read-only services bundled under the "o365 resources" banner —
 * they share the domain (O365 discovery / user presence) and are both
 * called from the o365 browser pool agent.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerO365DiscoveredResourcesServiceGrpc {

  private ServerO365DiscoveredResourcesServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerO365DiscoveredResourcesService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListDiscoveredRequest,
      com.jervis.contracts.server.ListDiscoveredResponse> getListDiscoveredMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListDiscovered",
      requestType = com.jervis.contracts.server.ListDiscoveredRequest.class,
      responseType = com.jervis.contracts.server.ListDiscoveredResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListDiscoveredRequest,
      com.jervis.contracts.server.ListDiscoveredResponse> getListDiscoveredMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListDiscoveredRequest, com.jervis.contracts.server.ListDiscoveredResponse> getListDiscoveredMethod;
    if ((getListDiscoveredMethod = ServerO365DiscoveredResourcesServiceGrpc.getListDiscoveredMethod) == null) {
      synchronized (ServerO365DiscoveredResourcesServiceGrpc.class) {
        if ((getListDiscoveredMethod = ServerO365DiscoveredResourcesServiceGrpc.getListDiscoveredMethod) == null) {
          ServerO365DiscoveredResourcesServiceGrpc.getListDiscoveredMethod = getListDiscoveredMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListDiscoveredRequest, com.jervis.contracts.server.ListDiscoveredResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListDiscovered"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListDiscoveredRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListDiscoveredResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerO365DiscoveredResourcesServiceMethodDescriptorSupplier("ListDiscovered"))
              .build();
        }
      }
    }
    return getListDiscoveredMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerO365DiscoveredResourcesServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerO365DiscoveredResourcesServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerO365DiscoveredResourcesServiceStub>() {
        @java.lang.Override
        public ServerO365DiscoveredResourcesServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerO365DiscoveredResourcesServiceStub(channel, callOptions);
        }
      };
    return ServerO365DiscoveredResourcesServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerO365DiscoveredResourcesServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerO365DiscoveredResourcesServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerO365DiscoveredResourcesServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerO365DiscoveredResourcesServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerO365DiscoveredResourcesServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerO365DiscoveredResourcesServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerO365DiscoveredResourcesServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerO365DiscoveredResourcesServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerO365DiscoveredResourcesServiceBlockingStub>() {
        @java.lang.Override
        public ServerO365DiscoveredResourcesServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerO365DiscoveredResourcesServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerO365DiscoveredResourcesServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerO365DiscoveredResourcesServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerO365DiscoveredResourcesServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerO365DiscoveredResourcesServiceFutureStub>() {
        @java.lang.Override
        public ServerO365DiscoveredResourcesServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerO365DiscoveredResourcesServiceFutureStub(channel, callOptions);
        }
      };
    return ServerO365DiscoveredResourcesServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Two tiny read-only services bundled under the "o365 resources" banner —
   * they share the domain (O365 discovery / user presence) and are both
   * called from the o365 browser pool agent.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void listDiscovered(com.jervis.contracts.server.ListDiscoveredRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListDiscoveredResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListDiscoveredMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerO365DiscoveredResourcesService.
   * <pre>
   * Two tiny read-only services bundled under the "o365 resources" banner —
   * they share the domain (O365 discovery / user presence) and are both
   * called from the o365 browser pool agent.
   * </pre>
   */
  public static abstract class ServerO365DiscoveredResourcesServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerO365DiscoveredResourcesServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerO365DiscoveredResourcesService.
   * <pre>
   * Two tiny read-only services bundled under the "o365 resources" banner —
   * they share the domain (O365 discovery / user presence) and are both
   * called from the o365 browser pool agent.
   * </pre>
   */
  public static final class ServerO365DiscoveredResourcesServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerO365DiscoveredResourcesServiceStub> {
    private ServerO365DiscoveredResourcesServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerO365DiscoveredResourcesServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerO365DiscoveredResourcesServiceStub(channel, callOptions);
    }

    /**
     */
    public void listDiscovered(com.jervis.contracts.server.ListDiscoveredRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListDiscoveredResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListDiscoveredMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerO365DiscoveredResourcesService.
   * <pre>
   * Two tiny read-only services bundled under the "o365 resources" banner —
   * they share the domain (O365 discovery / user presence) and are both
   * called from the o365 browser pool agent.
   * </pre>
   */
  public static final class ServerO365DiscoveredResourcesServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerO365DiscoveredResourcesServiceBlockingV2Stub> {
    private ServerO365DiscoveredResourcesServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerO365DiscoveredResourcesServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerO365DiscoveredResourcesServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ListDiscoveredResponse listDiscovered(com.jervis.contracts.server.ListDiscoveredRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListDiscoveredMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerO365DiscoveredResourcesService.
   * <pre>
   * Two tiny read-only services bundled under the "o365 resources" banner —
   * they share the domain (O365 discovery / user presence) and are both
   * called from the o365 browser pool agent.
   * </pre>
   */
  public static final class ServerO365DiscoveredResourcesServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerO365DiscoveredResourcesServiceBlockingStub> {
    private ServerO365DiscoveredResourcesServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerO365DiscoveredResourcesServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerO365DiscoveredResourcesServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ListDiscoveredResponse listDiscovered(com.jervis.contracts.server.ListDiscoveredRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListDiscoveredMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerO365DiscoveredResourcesService.
   * <pre>
   * Two tiny read-only services bundled under the "o365 resources" banner —
   * they share the domain (O365 discovery / user presence) and are both
   * called from the o365 browser pool agent.
   * </pre>
   */
  public static final class ServerO365DiscoveredResourcesServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerO365DiscoveredResourcesServiceFutureStub> {
    private ServerO365DiscoveredResourcesServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerO365DiscoveredResourcesServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerO365DiscoveredResourcesServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListDiscoveredResponse> listDiscovered(
        com.jervis.contracts.server.ListDiscoveredRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListDiscoveredMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LIST_DISCOVERED = 0;

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
        case METHODID_LIST_DISCOVERED:
          serviceImpl.listDiscovered((com.jervis.contracts.server.ListDiscoveredRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListDiscoveredResponse>) responseObserver);
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
          getListDiscoveredMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListDiscoveredRequest,
              com.jervis.contracts.server.ListDiscoveredResponse>(
                service, METHODID_LIST_DISCOVERED)))
        .build();
  }

  private static abstract class ServerO365DiscoveredResourcesServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerO365DiscoveredResourcesServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerO365ResourcesProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerO365DiscoveredResourcesService");
    }
  }

  private static final class ServerO365DiscoveredResourcesServiceFileDescriptorSupplier
      extends ServerO365DiscoveredResourcesServiceBaseDescriptorSupplier {
    ServerO365DiscoveredResourcesServiceFileDescriptorSupplier() {}
  }

  private static final class ServerO365DiscoveredResourcesServiceMethodDescriptorSupplier
      extends ServerO365DiscoveredResourcesServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerO365DiscoveredResourcesServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerO365DiscoveredResourcesServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerO365DiscoveredResourcesServiceFileDescriptorSupplier())
              .addMethod(getListDiscoveredMethod())
              .build();
        }
      }
    }
    return result;
  }
}
