package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerForegroundService owns the foreground/chat GPU reservation
 * signal. The orchestrator calls ForegroundStart when interactive chat
 * begins (reserve local GPU away from BackgroundEngine) and ForegroundEnd
 * when it completes. ChatOnCloud signals that the current chat went to
 * cloud LLM — GPU stays free for background work.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerForegroundServiceGrpc {

  private ServerForegroundServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerForegroundService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ForegroundStartRequest,
      com.jervis.contracts.server.ForegroundResponse> getForegroundStartMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ForegroundStart",
      requestType = com.jervis.contracts.server.ForegroundStartRequest.class,
      responseType = com.jervis.contracts.server.ForegroundResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ForegroundStartRequest,
      com.jervis.contracts.server.ForegroundResponse> getForegroundStartMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ForegroundStartRequest, com.jervis.contracts.server.ForegroundResponse> getForegroundStartMethod;
    if ((getForegroundStartMethod = ServerForegroundServiceGrpc.getForegroundStartMethod) == null) {
      synchronized (ServerForegroundServiceGrpc.class) {
        if ((getForegroundStartMethod = ServerForegroundServiceGrpc.getForegroundStartMethod) == null) {
          ServerForegroundServiceGrpc.getForegroundStartMethod = getForegroundStartMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ForegroundStartRequest, com.jervis.contracts.server.ForegroundResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ForegroundStart"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ForegroundStartRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ForegroundResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerForegroundServiceMethodDescriptorSupplier("ForegroundStart"))
              .build();
        }
      }
    }
    return getForegroundStartMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ForegroundEndRequest,
      com.jervis.contracts.server.ForegroundResponse> getForegroundEndMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ForegroundEnd",
      requestType = com.jervis.contracts.server.ForegroundEndRequest.class,
      responseType = com.jervis.contracts.server.ForegroundResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ForegroundEndRequest,
      com.jervis.contracts.server.ForegroundResponse> getForegroundEndMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ForegroundEndRequest, com.jervis.contracts.server.ForegroundResponse> getForegroundEndMethod;
    if ((getForegroundEndMethod = ServerForegroundServiceGrpc.getForegroundEndMethod) == null) {
      synchronized (ServerForegroundServiceGrpc.class) {
        if ((getForegroundEndMethod = ServerForegroundServiceGrpc.getForegroundEndMethod) == null) {
          ServerForegroundServiceGrpc.getForegroundEndMethod = getForegroundEndMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ForegroundEndRequest, com.jervis.contracts.server.ForegroundResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ForegroundEnd"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ForegroundEndRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ForegroundResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerForegroundServiceMethodDescriptorSupplier("ForegroundEnd"))
              .build();
        }
      }
    }
    return getForegroundEndMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ChatOnCloudRequest,
      com.jervis.contracts.server.ForegroundResponse> getChatOnCloudMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ChatOnCloud",
      requestType = com.jervis.contracts.server.ChatOnCloudRequest.class,
      responseType = com.jervis.contracts.server.ForegroundResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ChatOnCloudRequest,
      com.jervis.contracts.server.ForegroundResponse> getChatOnCloudMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ChatOnCloudRequest, com.jervis.contracts.server.ForegroundResponse> getChatOnCloudMethod;
    if ((getChatOnCloudMethod = ServerForegroundServiceGrpc.getChatOnCloudMethod) == null) {
      synchronized (ServerForegroundServiceGrpc.class) {
        if ((getChatOnCloudMethod = ServerForegroundServiceGrpc.getChatOnCloudMethod) == null) {
          ServerForegroundServiceGrpc.getChatOnCloudMethod = getChatOnCloudMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ChatOnCloudRequest, com.jervis.contracts.server.ForegroundResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ChatOnCloud"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ChatOnCloudRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ForegroundResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerForegroundServiceMethodDescriptorSupplier("ChatOnCloud"))
              .build();
        }
      }
    }
    return getChatOnCloudMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerForegroundServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerForegroundServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerForegroundServiceStub>() {
        @java.lang.Override
        public ServerForegroundServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerForegroundServiceStub(channel, callOptions);
        }
      };
    return ServerForegroundServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerForegroundServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerForegroundServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerForegroundServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerForegroundServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerForegroundServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerForegroundServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerForegroundServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerForegroundServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerForegroundServiceBlockingStub>() {
        @java.lang.Override
        public ServerForegroundServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerForegroundServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerForegroundServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerForegroundServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerForegroundServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerForegroundServiceFutureStub>() {
        @java.lang.Override
        public ServerForegroundServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerForegroundServiceFutureStub(channel, callOptions);
        }
      };
    return ServerForegroundServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerForegroundService owns the foreground/chat GPU reservation
   * signal. The orchestrator calls ForegroundStart when interactive chat
   * begins (reserve local GPU away from BackgroundEngine) and ForegroundEnd
   * when it completes. ChatOnCloud signals that the current chat went to
   * cloud LLM — GPU stays free for background work.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void foregroundStart(com.jervis.contracts.server.ForegroundStartRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ForegroundResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getForegroundStartMethod(), responseObserver);
    }

    /**
     */
    default void foregroundEnd(com.jervis.contracts.server.ForegroundEndRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ForegroundResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getForegroundEndMethod(), responseObserver);
    }

    /**
     */
    default void chatOnCloud(com.jervis.contracts.server.ChatOnCloudRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ForegroundResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getChatOnCloudMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerForegroundService.
   * <pre>
   * ServerForegroundService owns the foreground/chat GPU reservation
   * signal. The orchestrator calls ForegroundStart when interactive chat
   * begins (reserve local GPU away from BackgroundEngine) and ForegroundEnd
   * when it completes. ChatOnCloud signals that the current chat went to
   * cloud LLM — GPU stays free for background work.
   * </pre>
   */
  public static abstract class ServerForegroundServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerForegroundServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerForegroundService.
   * <pre>
   * ServerForegroundService owns the foreground/chat GPU reservation
   * signal. The orchestrator calls ForegroundStart when interactive chat
   * begins (reserve local GPU away from BackgroundEngine) and ForegroundEnd
   * when it completes. ChatOnCloud signals that the current chat went to
   * cloud LLM — GPU stays free for background work.
   * </pre>
   */
  public static final class ServerForegroundServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerForegroundServiceStub> {
    private ServerForegroundServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerForegroundServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerForegroundServiceStub(channel, callOptions);
    }

    /**
     */
    public void foregroundStart(com.jervis.contracts.server.ForegroundStartRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ForegroundResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getForegroundStartMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void foregroundEnd(com.jervis.contracts.server.ForegroundEndRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ForegroundResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getForegroundEndMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void chatOnCloud(com.jervis.contracts.server.ChatOnCloudRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ForegroundResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getChatOnCloudMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerForegroundService.
   * <pre>
   * ServerForegroundService owns the foreground/chat GPU reservation
   * signal. The orchestrator calls ForegroundStart when interactive chat
   * begins (reserve local GPU away from BackgroundEngine) and ForegroundEnd
   * when it completes. ChatOnCloud signals that the current chat went to
   * cloud LLM — GPU stays free for background work.
   * </pre>
   */
  public static final class ServerForegroundServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerForegroundServiceBlockingV2Stub> {
    private ServerForegroundServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerForegroundServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerForegroundServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ForegroundResponse foregroundStart(com.jervis.contracts.server.ForegroundStartRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getForegroundStartMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ForegroundResponse foregroundEnd(com.jervis.contracts.server.ForegroundEndRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getForegroundEndMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ForegroundResponse chatOnCloud(com.jervis.contracts.server.ChatOnCloudRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getChatOnCloudMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerForegroundService.
   * <pre>
   * ServerForegroundService owns the foreground/chat GPU reservation
   * signal. The orchestrator calls ForegroundStart when interactive chat
   * begins (reserve local GPU away from BackgroundEngine) and ForegroundEnd
   * when it completes. ChatOnCloud signals that the current chat went to
   * cloud LLM — GPU stays free for background work.
   * </pre>
   */
  public static final class ServerForegroundServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerForegroundServiceBlockingStub> {
    private ServerForegroundServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerForegroundServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerForegroundServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ForegroundResponse foregroundStart(com.jervis.contracts.server.ForegroundStartRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getForegroundStartMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ForegroundResponse foregroundEnd(com.jervis.contracts.server.ForegroundEndRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getForegroundEndMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ForegroundResponse chatOnCloud(com.jervis.contracts.server.ChatOnCloudRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getChatOnCloudMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerForegroundService.
   * <pre>
   * ServerForegroundService owns the foreground/chat GPU reservation
   * signal. The orchestrator calls ForegroundStart when interactive chat
   * begins (reserve local GPU away from BackgroundEngine) and ForegroundEnd
   * when it completes. ChatOnCloud signals that the current chat went to
   * cloud LLM — GPU stays free for background work.
   * </pre>
   */
  public static final class ServerForegroundServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerForegroundServiceFutureStub> {
    private ServerForegroundServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerForegroundServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerForegroundServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ForegroundResponse> foregroundStart(
        com.jervis.contracts.server.ForegroundStartRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getForegroundStartMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ForegroundResponse> foregroundEnd(
        com.jervis.contracts.server.ForegroundEndRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getForegroundEndMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ForegroundResponse> chatOnCloud(
        com.jervis.contracts.server.ChatOnCloudRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getChatOnCloudMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_FOREGROUND_START = 0;
  private static final int METHODID_FOREGROUND_END = 1;
  private static final int METHODID_CHAT_ON_CLOUD = 2;

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
        case METHODID_FOREGROUND_START:
          serviceImpl.foregroundStart((com.jervis.contracts.server.ForegroundStartRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ForegroundResponse>) responseObserver);
          break;
        case METHODID_FOREGROUND_END:
          serviceImpl.foregroundEnd((com.jervis.contracts.server.ForegroundEndRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ForegroundResponse>) responseObserver);
          break;
        case METHODID_CHAT_ON_CLOUD:
          serviceImpl.chatOnCloud((com.jervis.contracts.server.ChatOnCloudRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ForegroundResponse>) responseObserver);
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
          getForegroundStartMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ForegroundStartRequest,
              com.jervis.contracts.server.ForegroundResponse>(
                service, METHODID_FOREGROUND_START)))
        .addMethod(
          getForegroundEndMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ForegroundEndRequest,
              com.jervis.contracts.server.ForegroundResponse>(
                service, METHODID_FOREGROUND_END)))
        .addMethod(
          getChatOnCloudMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ChatOnCloudRequest,
              com.jervis.contracts.server.ForegroundResponse>(
                service, METHODID_CHAT_ON_CLOUD)))
        .build();
  }

  private static abstract class ServerForegroundServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerForegroundServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerForegroundProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerForegroundService");
    }
  }

  private static final class ServerForegroundServiceFileDescriptorSupplier
      extends ServerForegroundServiceBaseDescriptorSupplier {
    ServerForegroundServiceFileDescriptorSupplier() {}
  }

  private static final class ServerForegroundServiceMethodDescriptorSupplier
      extends ServerForegroundServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerForegroundServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerForegroundServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerForegroundServiceFileDescriptorSupplier())
              .addMethod(getForegroundStartMethod())
              .addMethod(getForegroundEndMethod())
              .addMethod(getChatOnCloudMethod())
              .build();
        }
      }
    }
    return result;
  }
}
