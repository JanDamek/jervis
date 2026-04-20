package com.jervis.contracts.router;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * RouterInferenceService is the single entry point for all local Jervis
 * modules needing LLM / VLM / embedding inference. It supersedes the
 * former `/api/chat`, `/api/generate`, `/api/embeddings`, `/api/embed`
 * FastAPI routes — no internal module may talk to the router over REST.
 * The router itself still dispatches outward to the Ollama / OpenRouter
 * vendors over HTTP (vendor contracts), but that is strictly egress and
 * never visible on the router's input surface.
 * Routing policy (capability + tier + priority + deadline) stays owned
 * by the router; callers pass the semantic intent via RequestContext
 * (see `jervis/common/types.proto`) and a best-effort `model_hint`. The
 * router substitutes the concrete model and backend per `decide_route`
 * in `service-ollama-router/app/router_core.py`.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class RouterInferenceServiceGrpc {

  private RouterInferenceServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.router.RouterInferenceService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.ChatRequest,
      com.jervis.contracts.router.ChatChunk> getChatMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Chat",
      requestType = com.jervis.contracts.router.ChatRequest.class,
      responseType = com.jervis.contracts.router.ChatChunk.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.ChatRequest,
      com.jervis.contracts.router.ChatChunk> getChatMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.ChatRequest, com.jervis.contracts.router.ChatChunk> getChatMethod;
    if ((getChatMethod = RouterInferenceServiceGrpc.getChatMethod) == null) {
      synchronized (RouterInferenceServiceGrpc.class) {
        if ((getChatMethod = RouterInferenceServiceGrpc.getChatMethod) == null) {
          RouterInferenceServiceGrpc.getChatMethod = getChatMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.ChatRequest, com.jervis.contracts.router.ChatChunk>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Chat"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ChatRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.ChatChunk.getDefaultInstance()))
              .setSchemaDescriptor(new RouterInferenceServiceMethodDescriptorSupplier("Chat"))
              .build();
        }
      }
    }
    return getChatMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.GenerateRequest,
      com.jervis.contracts.router.GenerateChunk> getGenerateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Generate",
      requestType = com.jervis.contracts.router.GenerateRequest.class,
      responseType = com.jervis.contracts.router.GenerateChunk.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.GenerateRequest,
      com.jervis.contracts.router.GenerateChunk> getGenerateMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.GenerateRequest, com.jervis.contracts.router.GenerateChunk> getGenerateMethod;
    if ((getGenerateMethod = RouterInferenceServiceGrpc.getGenerateMethod) == null) {
      synchronized (RouterInferenceServiceGrpc.class) {
        if ((getGenerateMethod = RouterInferenceServiceGrpc.getGenerateMethod) == null) {
          RouterInferenceServiceGrpc.getGenerateMethod = getGenerateMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.GenerateRequest, com.jervis.contracts.router.GenerateChunk>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Generate"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.GenerateRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.GenerateChunk.getDefaultInstance()))
              .setSchemaDescriptor(new RouterInferenceServiceMethodDescriptorSupplier("Generate"))
              .build();
        }
      }
    }
    return getGenerateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.router.EmbedRequest,
      com.jervis.contracts.router.EmbedResponse> getEmbedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Embed",
      requestType = com.jervis.contracts.router.EmbedRequest.class,
      responseType = com.jervis.contracts.router.EmbedResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.router.EmbedRequest,
      com.jervis.contracts.router.EmbedResponse> getEmbedMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.router.EmbedRequest, com.jervis.contracts.router.EmbedResponse> getEmbedMethod;
    if ((getEmbedMethod = RouterInferenceServiceGrpc.getEmbedMethod) == null) {
      synchronized (RouterInferenceServiceGrpc.class) {
        if ((getEmbedMethod = RouterInferenceServiceGrpc.getEmbedMethod) == null) {
          RouterInferenceServiceGrpc.getEmbedMethod = getEmbedMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.router.EmbedRequest, com.jervis.contracts.router.EmbedResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Embed"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.EmbedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.router.EmbedResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouterInferenceServiceMethodDescriptorSupplier("Embed"))
              .build();
        }
      }
    }
    return getEmbedMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RouterInferenceServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouterInferenceServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouterInferenceServiceStub>() {
        @java.lang.Override
        public RouterInferenceServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouterInferenceServiceStub(channel, callOptions);
        }
      };
    return RouterInferenceServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static RouterInferenceServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouterInferenceServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouterInferenceServiceBlockingV2Stub>() {
        @java.lang.Override
        public RouterInferenceServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouterInferenceServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return RouterInferenceServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RouterInferenceServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouterInferenceServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouterInferenceServiceBlockingStub>() {
        @java.lang.Override
        public RouterInferenceServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouterInferenceServiceBlockingStub(channel, callOptions);
        }
      };
    return RouterInferenceServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RouterInferenceServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouterInferenceServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouterInferenceServiceFutureStub>() {
        @java.lang.Override
        public RouterInferenceServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouterInferenceServiceFutureStub(channel, callOptions);
        }
      };
    return RouterInferenceServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * RouterInferenceService is the single entry point for all local Jervis
   * modules needing LLM / VLM / embedding inference. It supersedes the
   * former `/api/chat`, `/api/generate`, `/api/embeddings`, `/api/embed`
   * FastAPI routes — no internal module may talk to the router over REST.
   * The router itself still dispatches outward to the Ollama / OpenRouter
   * vendors over HTTP (vendor contracts), but that is strictly egress and
   * never visible on the router's input surface.
   * Routing policy (capability + tier + priority + deadline) stays owned
   * by the router; callers pass the semantic intent via RequestContext
   * (see `jervis/common/types.proto`) and a best-effort `model_hint`. The
   * router substitutes the concrete model and backend per `decide_route`
   * in `service-ollama-router/app/router_core.py`.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Streaming chat completion. Server emits incremental chunks; the
     * final chunk carries `done=true` plus token usage + finish_reason.
     * Tool calls (if any) are delivered either incrementally or once on
     * the final chunk — consumer MUST accumulate across chunks.
     * </pre>
     */
    default void chat(com.jervis.contracts.router.ChatRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ChatChunk> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getChatMethod(), responseObserver);
    }

    /**
     * <pre>
     * Single-prompt generate — used primarily by VLM (image input) and
     * legacy non-chat prompts (KB embedding-like, simple completions).
     * Server-streamed for keep-alive parity with Chat.
     * </pre>
     */
    default void generate(com.jervis.contracts.router.GenerateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.GenerateChunk> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGenerateMethod(), responseObserver);
    }

    /**
     * <pre>
     * Embeddings — unary. Returns one vector per input string in order.
     * </pre>
     */
    default void embed(com.jervis.contracts.router.EmbedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.EmbedResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getEmbedMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RouterInferenceService.
   * <pre>
   * RouterInferenceService is the single entry point for all local Jervis
   * modules needing LLM / VLM / embedding inference. It supersedes the
   * former `/api/chat`, `/api/generate`, `/api/embeddings`, `/api/embed`
   * FastAPI routes — no internal module may talk to the router over REST.
   * The router itself still dispatches outward to the Ollama / OpenRouter
   * vendors over HTTP (vendor contracts), but that is strictly egress and
   * never visible on the router's input surface.
   * Routing policy (capability + tier + priority + deadline) stays owned
   * by the router; callers pass the semantic intent via RequestContext
   * (see `jervis/common/types.proto`) and a best-effort `model_hint`. The
   * router substitutes the concrete model and backend per `decide_route`
   * in `service-ollama-router/app/router_core.py`.
   * </pre>
   */
  public static abstract class RouterInferenceServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RouterInferenceServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RouterInferenceService.
   * <pre>
   * RouterInferenceService is the single entry point for all local Jervis
   * modules needing LLM / VLM / embedding inference. It supersedes the
   * former `/api/chat`, `/api/generate`, `/api/embeddings`, `/api/embed`
   * FastAPI routes — no internal module may talk to the router over REST.
   * The router itself still dispatches outward to the Ollama / OpenRouter
   * vendors over HTTP (vendor contracts), but that is strictly egress and
   * never visible on the router's input surface.
   * Routing policy (capability + tier + priority + deadline) stays owned
   * by the router; callers pass the semantic intent via RequestContext
   * (see `jervis/common/types.proto`) and a best-effort `model_hint`. The
   * router substitutes the concrete model and backend per `decide_route`
   * in `service-ollama-router/app/router_core.py`.
   * </pre>
   */
  public static final class RouterInferenceServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RouterInferenceServiceStub> {
    private RouterInferenceServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouterInferenceServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouterInferenceServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Streaming chat completion. Server emits incremental chunks; the
     * final chunk carries `done=true` plus token usage + finish_reason.
     * Tool calls (if any) are delivered either incrementally or once on
     * the final chunk — consumer MUST accumulate across chunks.
     * </pre>
     */
    public void chat(com.jervis.contracts.router.ChatRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.ChatChunk> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getChatMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Single-prompt generate — used primarily by VLM (image input) and
     * legacy non-chat prompts (KB embedding-like, simple completions).
     * Server-streamed for keep-alive parity with Chat.
     * </pre>
     */
    public void generate(com.jervis.contracts.router.GenerateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.GenerateChunk> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGenerateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Embeddings — unary. Returns one vector per input string in order.
     * </pre>
     */
    public void embed(com.jervis.contracts.router.EmbedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.router.EmbedResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getEmbedMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RouterInferenceService.
   * <pre>
   * RouterInferenceService is the single entry point for all local Jervis
   * modules needing LLM / VLM / embedding inference. It supersedes the
   * former `/api/chat`, `/api/generate`, `/api/embeddings`, `/api/embed`
   * FastAPI routes — no internal module may talk to the router over REST.
   * The router itself still dispatches outward to the Ollama / OpenRouter
   * vendors over HTTP (vendor contracts), but that is strictly egress and
   * never visible on the router's input surface.
   * Routing policy (capability + tier + priority + deadline) stays owned
   * by the router; callers pass the semantic intent via RequestContext
   * (see `jervis/common/types.proto`) and a best-effort `model_hint`. The
   * router substitutes the concrete model and backend per `decide_route`
   * in `service-ollama-router/app/router_core.py`.
   * </pre>
   */
  public static final class RouterInferenceServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<RouterInferenceServiceBlockingV2Stub> {
    private RouterInferenceServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouterInferenceServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouterInferenceServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Streaming chat completion. Server emits incremental chunks; the
     * final chunk carries `done=true` plus token usage + finish_reason.
     * Tool calls (if any) are delivered either incrementally or once on
     * the final chunk — consumer MUST accumulate across chunks.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, com.jervis.contracts.router.ChatChunk>
        chat(com.jervis.contracts.router.ChatRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getChatMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Single-prompt generate — used primarily by VLM (image input) and
     * legacy non-chat prompts (KB embedding-like, simple completions).
     * Server-streamed for keep-alive parity with Chat.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, com.jervis.contracts.router.GenerateChunk>
        generate(com.jervis.contracts.router.GenerateRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getGenerateMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Embeddings — unary. Returns one vector per input string in order.
     * </pre>
     */
    public com.jervis.contracts.router.EmbedResponse embed(com.jervis.contracts.router.EmbedRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getEmbedMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service RouterInferenceService.
   * <pre>
   * RouterInferenceService is the single entry point for all local Jervis
   * modules needing LLM / VLM / embedding inference. It supersedes the
   * former `/api/chat`, `/api/generate`, `/api/embeddings`, `/api/embed`
   * FastAPI routes — no internal module may talk to the router over REST.
   * The router itself still dispatches outward to the Ollama / OpenRouter
   * vendors over HTTP (vendor contracts), but that is strictly egress and
   * never visible on the router's input surface.
   * Routing policy (capability + tier + priority + deadline) stays owned
   * by the router; callers pass the semantic intent via RequestContext
   * (see `jervis/common/types.proto`) and a best-effort `model_hint`. The
   * router substitutes the concrete model and backend per `decide_route`
   * in `service-ollama-router/app/router_core.py`.
   * </pre>
   */
  public static final class RouterInferenceServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RouterInferenceServiceBlockingStub> {
    private RouterInferenceServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouterInferenceServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouterInferenceServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Streaming chat completion. Server emits incremental chunks; the
     * final chunk carries `done=true` plus token usage + finish_reason.
     * Tool calls (if any) are delivered either incrementally or once on
     * the final chunk — consumer MUST accumulate across chunks.
     * </pre>
     */
    public java.util.Iterator<com.jervis.contracts.router.ChatChunk> chat(
        com.jervis.contracts.router.ChatRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getChatMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Single-prompt generate — used primarily by VLM (image input) and
     * legacy non-chat prompts (KB embedding-like, simple completions).
     * Server-streamed for keep-alive parity with Chat.
     * </pre>
     */
    public java.util.Iterator<com.jervis.contracts.router.GenerateChunk> generate(
        com.jervis.contracts.router.GenerateRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGenerateMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Embeddings — unary. Returns one vector per input string in order.
     * </pre>
     */
    public com.jervis.contracts.router.EmbedResponse embed(com.jervis.contracts.router.EmbedRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getEmbedMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RouterInferenceService.
   * <pre>
   * RouterInferenceService is the single entry point for all local Jervis
   * modules needing LLM / VLM / embedding inference. It supersedes the
   * former `/api/chat`, `/api/generate`, `/api/embeddings`, `/api/embed`
   * FastAPI routes — no internal module may talk to the router over REST.
   * The router itself still dispatches outward to the Ollama / OpenRouter
   * vendors over HTTP (vendor contracts), but that is strictly egress and
   * never visible on the router's input surface.
   * Routing policy (capability + tier + priority + deadline) stays owned
   * by the router; callers pass the semantic intent via RequestContext
   * (see `jervis/common/types.proto`) and a best-effort `model_hint`. The
   * router substitutes the concrete model and backend per `decide_route`
   * in `service-ollama-router/app/router_core.py`.
   * </pre>
   */
  public static final class RouterInferenceServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RouterInferenceServiceFutureStub> {
    private RouterInferenceServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouterInferenceServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouterInferenceServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Embeddings — unary. Returns one vector per input string in order.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.router.EmbedResponse> embed(
        com.jervis.contracts.router.EmbedRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getEmbedMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CHAT = 0;
  private static final int METHODID_GENERATE = 1;
  private static final int METHODID_EMBED = 2;

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
          serviceImpl.chat((com.jervis.contracts.router.ChatRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.ChatChunk>) responseObserver);
          break;
        case METHODID_GENERATE:
          serviceImpl.generate((com.jervis.contracts.router.GenerateRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.GenerateChunk>) responseObserver);
          break;
        case METHODID_EMBED:
          serviceImpl.embed((com.jervis.contracts.router.EmbedRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.router.EmbedResponse>) responseObserver);
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
              com.jervis.contracts.router.ChatRequest,
              com.jervis.contracts.router.ChatChunk>(
                service, METHODID_CHAT)))
        .addMethod(
          getGenerateMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.jervis.contracts.router.GenerateRequest,
              com.jervis.contracts.router.GenerateChunk>(
                service, METHODID_GENERATE)))
        .addMethod(
          getEmbedMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.router.EmbedRequest,
              com.jervis.contracts.router.EmbedResponse>(
                service, METHODID_EMBED)))
        .build();
  }

  private static abstract class RouterInferenceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RouterInferenceServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.router.RouterInferenceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RouterInferenceService");
    }
  }

  private static final class RouterInferenceServiceFileDescriptorSupplier
      extends RouterInferenceServiceBaseDescriptorSupplier {
    RouterInferenceServiceFileDescriptorSupplier() {}
  }

  private static final class RouterInferenceServiceMethodDescriptorSupplier
      extends RouterInferenceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RouterInferenceServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (RouterInferenceServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RouterInferenceServiceFileDescriptorSupplier())
              .addMethod(getChatMethod())
              .addMethod(getGenerateMethod())
              .addMethod(getEmbedMethod())
              .build();
        }
      }
    }
    return result;
  }
}
