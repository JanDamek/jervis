package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorChatService — foreground chat pipeline. Chat is a
 * server-streaming RPC that yields agentic-loop events (token, tool_call,
 * tool_result, approval_request, done, error). Approve + Stop are unary
 * control RPCs over the same session.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorChatServiceGrpc {

  private OrchestratorChatServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorChatService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ChatRequest,
      com.jervis.contracts.orchestrator.ChatEvent> getChatMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Chat",
      requestType = com.jervis.contracts.orchestrator.ChatRequest.class,
      responseType = com.jervis.contracts.orchestrator.ChatEvent.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ChatRequest,
      com.jervis.contracts.orchestrator.ChatEvent> getChatMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ChatRequest, com.jervis.contracts.orchestrator.ChatEvent> getChatMethod;
    if ((getChatMethod = OrchestratorChatServiceGrpc.getChatMethod) == null) {
      synchronized (OrchestratorChatServiceGrpc.class) {
        if ((getChatMethod = OrchestratorChatServiceGrpc.getChatMethod) == null) {
          OrchestratorChatServiceGrpc.getChatMethod = getChatMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.ChatRequest, com.jervis.contracts.orchestrator.ChatEvent>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Chat"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ChatRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ChatEvent.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorChatServiceMethodDescriptorSupplier("Chat"))
              .build();
        }
      }
    }
    return getChatMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ApproveActionRequest,
      com.jervis.contracts.orchestrator.ApproveActionAck> getApproveActionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ApproveAction",
      requestType = com.jervis.contracts.orchestrator.ApproveActionRequest.class,
      responseType = com.jervis.contracts.orchestrator.ApproveActionAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ApproveActionRequest,
      com.jervis.contracts.orchestrator.ApproveActionAck> getApproveActionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ApproveActionRequest, com.jervis.contracts.orchestrator.ApproveActionAck> getApproveActionMethod;
    if ((getApproveActionMethod = OrchestratorChatServiceGrpc.getApproveActionMethod) == null) {
      synchronized (OrchestratorChatServiceGrpc.class) {
        if ((getApproveActionMethod = OrchestratorChatServiceGrpc.getApproveActionMethod) == null) {
          OrchestratorChatServiceGrpc.getApproveActionMethod = getApproveActionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.ApproveActionRequest, com.jervis.contracts.orchestrator.ApproveActionAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ApproveAction"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ApproveActionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ApproveActionAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorChatServiceMethodDescriptorSupplier("ApproveAction"))
              .build();
        }
      }
    }
    return getApproveActionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StopChatRequest,
      com.jervis.contracts.orchestrator.StopChatAck> getStopMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Stop",
      requestType = com.jervis.contracts.orchestrator.StopChatRequest.class,
      responseType = com.jervis.contracts.orchestrator.StopChatAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StopChatRequest,
      com.jervis.contracts.orchestrator.StopChatAck> getStopMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StopChatRequest, com.jervis.contracts.orchestrator.StopChatAck> getStopMethod;
    if ((getStopMethod = OrchestratorChatServiceGrpc.getStopMethod) == null) {
      synchronized (OrchestratorChatServiceGrpc.class) {
        if ((getStopMethod = OrchestratorChatServiceGrpc.getStopMethod) == null) {
          OrchestratorChatServiceGrpc.getStopMethod = getStopMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.StopChatRequest, com.jervis.contracts.orchestrator.StopChatAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Stop"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.StopChatRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.StopChatAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorChatServiceMethodDescriptorSupplier("Stop"))
              .build();
        }
      }
    }
    return getStopMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorChatServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorChatServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorChatServiceStub>() {
        @java.lang.Override
        public OrchestratorChatServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorChatServiceStub(channel, callOptions);
        }
      };
    return OrchestratorChatServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static OrchestratorChatServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorChatServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorChatServiceBlockingV2Stub>() {
        @java.lang.Override
        public OrchestratorChatServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorChatServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return OrchestratorChatServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorChatServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorChatServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorChatServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorChatServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorChatServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorChatServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorChatServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorChatServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorChatServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorChatServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorChatServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorChatServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorChatService — foreground chat pipeline. Chat is a
   * server-streaming RPC that yields agentic-loop events (token, tool_call,
   * tool_result, approval_request, done, error). Approve + Stop are unary
   * control RPCs over the same session.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void chat(com.jervis.contracts.orchestrator.ChatRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ChatEvent> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getChatMethod(), responseObserver);
    }

    /**
     */
    default void approveAction(com.jervis.contracts.orchestrator.ApproveActionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ApproveActionAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getApproveActionMethod(), responseObserver);
    }

    /**
     */
    default void stop(com.jervis.contracts.orchestrator.StopChatRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StopChatAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStopMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorChatService.
   * <pre>
   * OrchestratorChatService — foreground chat pipeline. Chat is a
   * server-streaming RPC that yields agentic-loop events (token, tool_call,
   * tool_result, approval_request, done, error). Approve + Stop are unary
   * control RPCs over the same session.
   * </pre>
   */
  public static abstract class OrchestratorChatServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorChatServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorChatService.
   * <pre>
   * OrchestratorChatService — foreground chat pipeline. Chat is a
   * server-streaming RPC that yields agentic-loop events (token, tool_call,
   * tool_result, approval_request, done, error). Approve + Stop are unary
   * control RPCs over the same session.
   * </pre>
   */
  public static final class OrchestratorChatServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorChatServiceStub> {
    private OrchestratorChatServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorChatServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorChatServiceStub(channel, callOptions);
    }

    /**
     */
    public void chat(com.jervis.contracts.orchestrator.ChatRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ChatEvent> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getChatMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void approveAction(com.jervis.contracts.orchestrator.ApproveActionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ApproveActionAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getApproveActionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void stop(com.jervis.contracts.orchestrator.StopChatRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StopChatAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorChatService.
   * <pre>
   * OrchestratorChatService — foreground chat pipeline. Chat is a
   * server-streaming RPC that yields agentic-loop events (token, tool_call,
   * tool_result, approval_request, done, error). Approve + Stop are unary
   * control RPCs over the same session.
   * </pre>
   */
  public static final class OrchestratorChatServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorChatServiceBlockingV2Stub> {
    private OrchestratorChatServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorChatServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorChatServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, com.jervis.contracts.orchestrator.ChatEvent>
        chat(com.jervis.contracts.orchestrator.ChatRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getChatMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.ApproveActionAck approveAction(com.jervis.contracts.orchestrator.ApproveActionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getApproveActionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.StopChatAck stop(com.jervis.contracts.orchestrator.StopChatRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStopMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service OrchestratorChatService.
   * <pre>
   * OrchestratorChatService — foreground chat pipeline. Chat is a
   * server-streaming RPC that yields agentic-loop events (token, tool_call,
   * tool_result, approval_request, done, error). Approve + Stop are unary
   * control RPCs over the same session.
   * </pre>
   */
  public static final class OrchestratorChatServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorChatServiceBlockingStub> {
    private OrchestratorChatServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorChatServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorChatServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public java.util.Iterator<com.jervis.contracts.orchestrator.ChatEvent> chat(
        com.jervis.contracts.orchestrator.ChatRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getChatMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.ApproveActionAck approveAction(com.jervis.contracts.orchestrator.ApproveActionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getApproveActionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.StopChatAck stop(com.jervis.contracts.orchestrator.StopChatRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStopMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorChatService.
   * <pre>
   * OrchestratorChatService — foreground chat pipeline. Chat is a
   * server-streaming RPC that yields agentic-loop events (token, tool_call,
   * tool_result, approval_request, done, error). Approve + Stop are unary
   * control RPCs over the same session.
   * </pre>
   */
  public static final class OrchestratorChatServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorChatServiceFutureStub> {
    private OrchestratorChatServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorChatServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorChatServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.ApproveActionAck> approveAction(
        com.jervis.contracts.orchestrator.ApproveActionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getApproveActionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.StopChatAck> stop(
        com.jervis.contracts.orchestrator.StopChatRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CHAT = 0;
  private static final int METHODID_APPROVE_ACTION = 1;
  private static final int METHODID_STOP = 2;

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
        case METHODID_CHAT:
          serviceImpl.chat((com.jervis.contracts.orchestrator.ChatRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ChatEvent>) responseObserver);
          break;
        case METHODID_APPROVE_ACTION:
          serviceImpl.approveAction((com.jervis.contracts.orchestrator.ApproveActionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ApproveActionAck>) responseObserver);
          break;
        case METHODID_STOP:
          serviceImpl.stop((com.jervis.contracts.orchestrator.StopChatRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StopChatAck>) responseObserver);
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
          getChatMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.ChatRequest,
              com.jervis.contracts.orchestrator.ChatEvent>(
                service, METHODID_CHAT)))
        .addMethod(
          getApproveActionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.ApproveActionRequest,
              com.jervis.contracts.orchestrator.ApproveActionAck>(
                service, METHODID_APPROVE_ACTION)))
        .addMethod(
          getStopMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.StopChatRequest,
              com.jervis.contracts.orchestrator.StopChatAck>(
                service, METHODID_STOP)))
        .build();
  }

  private static abstract class OrchestratorChatServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorChatServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorChatProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorChatService");
    }
  }

  private static final class OrchestratorChatServiceFileDescriptorSupplier
      extends OrchestratorChatServiceBaseDescriptorSupplier {
    OrchestratorChatServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorChatServiceMethodDescriptorSupplier
      extends OrchestratorChatServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorChatServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorChatServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorChatServiceFileDescriptorSupplier())
              .addMethod(getChatMethod())
              .addMethod(getApproveActionMethod())
              .addMethod(getStopMethod())
              .build();
        }
      }
    }
    return result;
  }
}
