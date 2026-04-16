package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerWhatsappSessionService — inbound callbacks from the WhatsApp
 * browser pod: session state transitions (QR scan needed, session
 * expired) and one-shot capability discovery.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerWhatsappSessionServiceGrpc {

  private ServerWhatsappSessionServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerWhatsappSessionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.WhatsappSessionEventRequest,
      com.jervis.contracts.server.WhatsappSessionEventResponse> getSessionEventMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SessionEvent",
      requestType = com.jervis.contracts.server.WhatsappSessionEventRequest.class,
      responseType = com.jervis.contracts.server.WhatsappSessionEventResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.WhatsappSessionEventRequest,
      com.jervis.contracts.server.WhatsappSessionEventResponse> getSessionEventMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.WhatsappSessionEventRequest, com.jervis.contracts.server.WhatsappSessionEventResponse> getSessionEventMethod;
    if ((getSessionEventMethod = ServerWhatsappSessionServiceGrpc.getSessionEventMethod) == null) {
      synchronized (ServerWhatsappSessionServiceGrpc.class) {
        if ((getSessionEventMethod = ServerWhatsappSessionServiceGrpc.getSessionEventMethod) == null) {
          ServerWhatsappSessionServiceGrpc.getSessionEventMethod = getSessionEventMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.WhatsappSessionEventRequest, com.jervis.contracts.server.WhatsappSessionEventResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SessionEvent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WhatsappSessionEventRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WhatsappSessionEventResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerWhatsappSessionServiceMethodDescriptorSupplier("SessionEvent"))
              .build();
        }
      }
    }
    return getSessionEventMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.WhatsappCapabilitiesRequest,
      com.jervis.contracts.server.WhatsappCapabilitiesResponse> getCapabilitiesDiscoveredMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CapabilitiesDiscovered",
      requestType = com.jervis.contracts.server.WhatsappCapabilitiesRequest.class,
      responseType = com.jervis.contracts.server.WhatsappCapabilitiesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.WhatsappCapabilitiesRequest,
      com.jervis.contracts.server.WhatsappCapabilitiesResponse> getCapabilitiesDiscoveredMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.WhatsappCapabilitiesRequest, com.jervis.contracts.server.WhatsappCapabilitiesResponse> getCapabilitiesDiscoveredMethod;
    if ((getCapabilitiesDiscoveredMethod = ServerWhatsappSessionServiceGrpc.getCapabilitiesDiscoveredMethod) == null) {
      synchronized (ServerWhatsappSessionServiceGrpc.class) {
        if ((getCapabilitiesDiscoveredMethod = ServerWhatsappSessionServiceGrpc.getCapabilitiesDiscoveredMethod) == null) {
          ServerWhatsappSessionServiceGrpc.getCapabilitiesDiscoveredMethod = getCapabilitiesDiscoveredMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.WhatsappCapabilitiesRequest, com.jervis.contracts.server.WhatsappCapabilitiesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CapabilitiesDiscovered"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WhatsappCapabilitiesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WhatsappCapabilitiesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerWhatsappSessionServiceMethodDescriptorSupplier("CapabilitiesDiscovered"))
              .build();
        }
      }
    }
    return getCapabilitiesDiscoveredMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerWhatsappSessionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerWhatsappSessionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerWhatsappSessionServiceStub>() {
        @java.lang.Override
        public ServerWhatsappSessionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerWhatsappSessionServiceStub(channel, callOptions);
        }
      };
    return ServerWhatsappSessionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerWhatsappSessionServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerWhatsappSessionServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerWhatsappSessionServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerWhatsappSessionServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerWhatsappSessionServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerWhatsappSessionServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerWhatsappSessionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerWhatsappSessionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerWhatsappSessionServiceBlockingStub>() {
        @java.lang.Override
        public ServerWhatsappSessionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerWhatsappSessionServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerWhatsappSessionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerWhatsappSessionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerWhatsappSessionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerWhatsappSessionServiceFutureStub>() {
        @java.lang.Override
        public ServerWhatsappSessionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerWhatsappSessionServiceFutureStub(channel, callOptions);
        }
      };
    return ServerWhatsappSessionServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerWhatsappSessionService — inbound callbacks from the WhatsApp
   * browser pod: session state transitions (QR scan needed, session
   * expired) and one-shot capability discovery.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void sessionEvent(com.jervis.contracts.server.WhatsappSessionEventRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WhatsappSessionEventResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSessionEventMethod(), responseObserver);
    }

    /**
     */
    default void capabilitiesDiscovered(com.jervis.contracts.server.WhatsappCapabilitiesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WhatsappCapabilitiesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCapabilitiesDiscoveredMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerWhatsappSessionService.
   * <pre>
   * ServerWhatsappSessionService — inbound callbacks from the WhatsApp
   * browser pod: session state transitions (QR scan needed, session
   * expired) and one-shot capability discovery.
   * </pre>
   */
  public static abstract class ServerWhatsappSessionServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerWhatsappSessionServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerWhatsappSessionService.
   * <pre>
   * ServerWhatsappSessionService — inbound callbacks from the WhatsApp
   * browser pod: session state transitions (QR scan needed, session
   * expired) and one-shot capability discovery.
   * </pre>
   */
  public static final class ServerWhatsappSessionServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerWhatsappSessionServiceStub> {
    private ServerWhatsappSessionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerWhatsappSessionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerWhatsappSessionServiceStub(channel, callOptions);
    }

    /**
     */
    public void sessionEvent(com.jervis.contracts.server.WhatsappSessionEventRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WhatsappSessionEventResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSessionEventMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void capabilitiesDiscovered(com.jervis.contracts.server.WhatsappCapabilitiesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WhatsappCapabilitiesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCapabilitiesDiscoveredMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerWhatsappSessionService.
   * <pre>
   * ServerWhatsappSessionService — inbound callbacks from the WhatsApp
   * browser pod: session state transitions (QR scan needed, session
   * expired) and one-shot capability discovery.
   * </pre>
   */
  public static final class ServerWhatsappSessionServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerWhatsappSessionServiceBlockingV2Stub> {
    private ServerWhatsappSessionServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerWhatsappSessionServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerWhatsappSessionServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.WhatsappSessionEventResponse sessionEvent(com.jervis.contracts.server.WhatsappSessionEventRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSessionEventMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.WhatsappCapabilitiesResponse capabilitiesDiscovered(com.jervis.contracts.server.WhatsappCapabilitiesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCapabilitiesDiscoveredMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerWhatsappSessionService.
   * <pre>
   * ServerWhatsappSessionService — inbound callbacks from the WhatsApp
   * browser pod: session state transitions (QR scan needed, session
   * expired) and one-shot capability discovery.
   * </pre>
   */
  public static final class ServerWhatsappSessionServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerWhatsappSessionServiceBlockingStub> {
    private ServerWhatsappSessionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerWhatsappSessionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerWhatsappSessionServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.WhatsappSessionEventResponse sessionEvent(com.jervis.contracts.server.WhatsappSessionEventRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSessionEventMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.WhatsappCapabilitiesResponse capabilitiesDiscovered(com.jervis.contracts.server.WhatsappCapabilitiesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCapabilitiesDiscoveredMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerWhatsappSessionService.
   * <pre>
   * ServerWhatsappSessionService — inbound callbacks from the WhatsApp
   * browser pod: session state transitions (QR scan needed, session
   * expired) and one-shot capability discovery.
   * </pre>
   */
  public static final class ServerWhatsappSessionServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerWhatsappSessionServiceFutureStub> {
    private ServerWhatsappSessionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerWhatsappSessionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerWhatsappSessionServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.WhatsappSessionEventResponse> sessionEvent(
        com.jervis.contracts.server.WhatsappSessionEventRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSessionEventMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.WhatsappCapabilitiesResponse> capabilitiesDiscovered(
        com.jervis.contracts.server.WhatsappCapabilitiesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCapabilitiesDiscoveredMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SESSION_EVENT = 0;
  private static final int METHODID_CAPABILITIES_DISCOVERED = 1;

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
        case METHODID_SESSION_EVENT:
          serviceImpl.sessionEvent((com.jervis.contracts.server.WhatsappSessionEventRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.WhatsappSessionEventResponse>) responseObserver);
          break;
        case METHODID_CAPABILITIES_DISCOVERED:
          serviceImpl.capabilitiesDiscovered((com.jervis.contracts.server.WhatsappCapabilitiesRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.WhatsappCapabilitiesResponse>) responseObserver);
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
          getSessionEventMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.WhatsappSessionEventRequest,
              com.jervis.contracts.server.WhatsappSessionEventResponse>(
                service, METHODID_SESSION_EVENT)))
        .addMethod(
          getCapabilitiesDiscoveredMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.WhatsappCapabilitiesRequest,
              com.jervis.contracts.server.WhatsappCapabilitiesResponse>(
                service, METHODID_CAPABILITIES_DISCOVERED)))
        .build();
  }

  private static abstract class ServerWhatsappSessionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerWhatsappSessionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerWhatsappSessionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerWhatsappSessionService");
    }
  }

  private static final class ServerWhatsappSessionServiceFileDescriptorSupplier
      extends ServerWhatsappSessionServiceBaseDescriptorSupplier {
    ServerWhatsappSessionServiceFileDescriptorSupplier() {}
  }

  private static final class ServerWhatsappSessionServiceMethodDescriptorSupplier
      extends ServerWhatsappSessionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerWhatsappSessionServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerWhatsappSessionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerWhatsappSessionServiceFileDescriptorSupplier())
              .addMethod(getSessionEventMethod())
              .addMethod(getCapabilitiesDiscoveredMethod())
              .build();
        }
      }
    }
    return result;
  }
}
