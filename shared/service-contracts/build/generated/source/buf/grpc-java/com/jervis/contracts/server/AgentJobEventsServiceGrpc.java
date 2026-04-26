package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * AgentJobEventsService — server-streaming push of AgentJobRecord state
 * changes. Subscribers (typically the Python orchestrator's per-client
 * session loops) receive one message per state transition emitted by
 * `AgentJobDispatcher.saveAndEmit` / `AgentJobWatcher`. The orchestrator
 * then injects `[agent-update] ...` system messages into the LLM session
 * queue so the chat model sees state changes without polling.
 * Pairs with NotificationRpcImpl.subscribeToEvents (kRPC, UI-side) —
 * the gRPC bridge filters that broadcast for AgentJobStateChanged events
 * only and re-emits them as proto messages.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class AgentJobEventsServiceGrpc {

  private AgentJobEventsServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.AgentJobEventsService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.AgentJobEventsSubscribeRequest,
      com.jervis.contracts.server.AgentJobStateChangedEvent> getSubscribeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Subscribe",
      requestType = com.jervis.contracts.server.AgentJobEventsSubscribeRequest.class,
      responseType = com.jervis.contracts.server.AgentJobStateChangedEvent.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.AgentJobEventsSubscribeRequest,
      com.jervis.contracts.server.AgentJobStateChangedEvent> getSubscribeMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.AgentJobEventsSubscribeRequest, com.jervis.contracts.server.AgentJobStateChangedEvent> getSubscribeMethod;
    if ((getSubscribeMethod = AgentJobEventsServiceGrpc.getSubscribeMethod) == null) {
      synchronized (AgentJobEventsServiceGrpc.class) {
        if ((getSubscribeMethod = AgentJobEventsServiceGrpc.getSubscribeMethod) == null) {
          AgentJobEventsServiceGrpc.getSubscribeMethod = getSubscribeMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.AgentJobEventsSubscribeRequest, com.jervis.contracts.server.AgentJobStateChangedEvent>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Subscribe"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AgentJobEventsSubscribeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AgentJobStateChangedEvent.getDefaultInstance()))
              .setSchemaDescriptor(new AgentJobEventsServiceMethodDescriptorSupplier("Subscribe"))
              .build();
        }
      }
    }
    return getSubscribeMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static AgentJobEventsServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AgentJobEventsServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AgentJobEventsServiceStub>() {
        @java.lang.Override
        public AgentJobEventsServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AgentJobEventsServiceStub(channel, callOptions);
        }
      };
    return AgentJobEventsServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static AgentJobEventsServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AgentJobEventsServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AgentJobEventsServiceBlockingV2Stub>() {
        @java.lang.Override
        public AgentJobEventsServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AgentJobEventsServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return AgentJobEventsServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static AgentJobEventsServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AgentJobEventsServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AgentJobEventsServiceBlockingStub>() {
        @java.lang.Override
        public AgentJobEventsServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AgentJobEventsServiceBlockingStub(channel, callOptions);
        }
      };
    return AgentJobEventsServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static AgentJobEventsServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AgentJobEventsServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AgentJobEventsServiceFutureStub>() {
        @java.lang.Override
        public AgentJobEventsServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AgentJobEventsServiceFutureStub(channel, callOptions);
        }
      };
    return AgentJobEventsServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * AgentJobEventsService — server-streaming push of AgentJobRecord state
   * changes. Subscribers (typically the Python orchestrator's per-client
   * session loops) receive one message per state transition emitted by
   * `AgentJobDispatcher.saveAndEmit` / `AgentJobWatcher`. The orchestrator
   * then injects `[agent-update] ...` system messages into the LLM session
   * queue so the chat model sees state changes without polling.
   * Pairs with NotificationRpcImpl.subscribeToEvents (kRPC, UI-side) —
   * the gRPC bridge filters that broadcast for AgentJobStateChanged events
   * only and re-emits them as proto messages.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Long-lived server stream. The server sends one message per emit,
     * and never completes the call from its side. Clients reconnect with
     * exponential backoff on transport drops (no polling fallback).
     * </pre>
     */
    default void subscribe(com.jervis.contracts.server.AgentJobEventsSubscribeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AgentJobStateChangedEvent> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSubscribeMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service AgentJobEventsService.
   * <pre>
   * AgentJobEventsService — server-streaming push of AgentJobRecord state
   * changes. Subscribers (typically the Python orchestrator's per-client
   * session loops) receive one message per state transition emitted by
   * `AgentJobDispatcher.saveAndEmit` / `AgentJobWatcher`. The orchestrator
   * then injects `[agent-update] ...` system messages into the LLM session
   * queue so the chat model sees state changes without polling.
   * Pairs with NotificationRpcImpl.subscribeToEvents (kRPC, UI-side) —
   * the gRPC bridge filters that broadcast for AgentJobStateChanged events
   * only and re-emits them as proto messages.
   * </pre>
   */
  public static abstract class AgentJobEventsServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return AgentJobEventsServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service AgentJobEventsService.
   * <pre>
   * AgentJobEventsService — server-streaming push of AgentJobRecord state
   * changes. Subscribers (typically the Python orchestrator's per-client
   * session loops) receive one message per state transition emitted by
   * `AgentJobDispatcher.saveAndEmit` / `AgentJobWatcher`. The orchestrator
   * then injects `[agent-update] ...` system messages into the LLM session
   * queue so the chat model sees state changes without polling.
   * Pairs with NotificationRpcImpl.subscribeToEvents (kRPC, UI-side) —
   * the gRPC bridge filters that broadcast for AgentJobStateChanged events
   * only and re-emits them as proto messages.
   * </pre>
   */
  public static final class AgentJobEventsServiceStub
      extends io.grpc.stub.AbstractAsyncStub<AgentJobEventsServiceStub> {
    private AgentJobEventsServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AgentJobEventsServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AgentJobEventsServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Long-lived server stream. The server sends one message per emit,
     * and never completes the call from its side. Clients reconnect with
     * exponential backoff on transport drops (no polling fallback).
     * </pre>
     */
    public void subscribe(com.jervis.contracts.server.AgentJobEventsSubscribeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AgentJobStateChangedEvent> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getSubscribeMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service AgentJobEventsService.
   * <pre>
   * AgentJobEventsService — server-streaming push of AgentJobRecord state
   * changes. Subscribers (typically the Python orchestrator's per-client
   * session loops) receive one message per state transition emitted by
   * `AgentJobDispatcher.saveAndEmit` / `AgentJobWatcher`. The orchestrator
   * then injects `[agent-update] ...` system messages into the LLM session
   * queue so the chat model sees state changes without polling.
   * Pairs with NotificationRpcImpl.subscribeToEvents (kRPC, UI-side) —
   * the gRPC bridge filters that broadcast for AgentJobStateChanged events
   * only and re-emits them as proto messages.
   * </pre>
   */
  public static final class AgentJobEventsServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<AgentJobEventsServiceBlockingV2Stub> {
    private AgentJobEventsServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AgentJobEventsServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AgentJobEventsServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Long-lived server stream. The server sends one message per emit,
     * and never completes the call from its side. Clients reconnect with
     * exponential backoff on transport drops (no polling fallback).
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, com.jervis.contracts.server.AgentJobStateChangedEvent>
        subscribe(com.jervis.contracts.server.AgentJobEventsSubscribeRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getSubscribeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service AgentJobEventsService.
   * <pre>
   * AgentJobEventsService — server-streaming push of AgentJobRecord state
   * changes. Subscribers (typically the Python orchestrator's per-client
   * session loops) receive one message per state transition emitted by
   * `AgentJobDispatcher.saveAndEmit` / `AgentJobWatcher`. The orchestrator
   * then injects `[agent-update] ...` system messages into the LLM session
   * queue so the chat model sees state changes without polling.
   * Pairs with NotificationRpcImpl.subscribeToEvents (kRPC, UI-side) —
   * the gRPC bridge filters that broadcast for AgentJobStateChanged events
   * only and re-emits them as proto messages.
   * </pre>
   */
  public static final class AgentJobEventsServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<AgentJobEventsServiceBlockingStub> {
    private AgentJobEventsServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AgentJobEventsServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AgentJobEventsServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Long-lived server stream. The server sends one message per emit,
     * and never completes the call from its side. Clients reconnect with
     * exponential backoff on transport drops (no polling fallback).
     * </pre>
     */
    public java.util.Iterator<com.jervis.contracts.server.AgentJobStateChangedEvent> subscribe(
        com.jervis.contracts.server.AgentJobEventsSubscribeRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getSubscribeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service AgentJobEventsService.
   * <pre>
   * AgentJobEventsService — server-streaming push of AgentJobRecord state
   * changes. Subscribers (typically the Python orchestrator's per-client
   * session loops) receive one message per state transition emitted by
   * `AgentJobDispatcher.saveAndEmit` / `AgentJobWatcher`. The orchestrator
   * then injects `[agent-update] ...` system messages into the LLM session
   * queue so the chat model sees state changes without polling.
   * Pairs with NotificationRpcImpl.subscribeToEvents (kRPC, UI-side) —
   * the gRPC bridge filters that broadcast for AgentJobStateChanged events
   * only and re-emits them as proto messages.
   * </pre>
   */
  public static final class AgentJobEventsServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<AgentJobEventsServiceFutureStub> {
    private AgentJobEventsServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AgentJobEventsServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AgentJobEventsServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_SUBSCRIBE = 0;

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
        case METHODID_SUBSCRIBE:
          serviceImpl.subscribe((com.jervis.contracts.server.AgentJobEventsSubscribeRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AgentJobStateChangedEvent>) responseObserver);
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
          getSubscribeMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.jervis.contracts.server.AgentJobEventsSubscribeRequest,
              com.jervis.contracts.server.AgentJobStateChangedEvent>(
                service, METHODID_SUBSCRIBE)))
        .build();
  }

  private static abstract class AgentJobEventsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    AgentJobEventsServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerAgentJobEventsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("AgentJobEventsService");
    }
  }

  private static final class AgentJobEventsServiceFileDescriptorSupplier
      extends AgentJobEventsServiceBaseDescriptorSupplier {
    AgentJobEventsServiceFileDescriptorSupplier() {}
  }

  private static final class AgentJobEventsServiceMethodDescriptorSupplier
      extends AgentJobEventsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    AgentJobEventsServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (AgentJobEventsServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new AgentJobEventsServiceFileDescriptorSupplier())
              .addMethod(getSubscribeMethod())
              .build();
        }
      }
    }
    return result;
  }
}
