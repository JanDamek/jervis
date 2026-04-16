package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerGuidelinesService is the gRPC surface for operational-
 * guidelines CRUD: coding/git/review/communication/approval/general
 * rules at GLOBAL / CLIENT / PROJECT scopes, merged for a caller's
 * context. The rule tree itself is large and all consumers (orchestrator,
 * correction, LLM router prompt-builder) treat it as opaque JSON —
 * serialization stays kotlinx.serialization against `shared/common-dto`
 * types. Proto-typing the full tree would duplicate the schema in two
 * places; instead we carry the serialized document body as a field and
 * let consumers parse it with their native JSON library.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerGuidelinesServiceGrpc {

  private ServerGuidelinesServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerGuidelinesService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetMergedRequest,
      com.jervis.contracts.server.GuidelinesPayload> getGetMergedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetMerged",
      requestType = com.jervis.contracts.server.GetMergedRequest.class,
      responseType = com.jervis.contracts.server.GuidelinesPayload.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetMergedRequest,
      com.jervis.contracts.server.GuidelinesPayload> getGetMergedMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetMergedRequest, com.jervis.contracts.server.GuidelinesPayload> getGetMergedMethod;
    if ((getGetMergedMethod = ServerGuidelinesServiceGrpc.getGetMergedMethod) == null) {
      synchronized (ServerGuidelinesServiceGrpc.class) {
        if ((getGetMergedMethod = ServerGuidelinesServiceGrpc.getGetMergedMethod) == null) {
          ServerGuidelinesServiceGrpc.getGetMergedMethod = getGetMergedMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetMergedRequest, com.jervis.contracts.server.GuidelinesPayload>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetMerged"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetMergedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GuidelinesPayload.getDefaultInstance()))
              .setSchemaDescriptor(new ServerGuidelinesServiceMethodDescriptorSupplier("GetMerged"))
              .build();
        }
      }
    }
    return getGetMergedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetRequest,
      com.jervis.contracts.server.GuidelinesPayload> getGetMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Get",
      requestType = com.jervis.contracts.server.GetRequest.class,
      responseType = com.jervis.contracts.server.GuidelinesPayload.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetRequest,
      com.jervis.contracts.server.GuidelinesPayload> getGetMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetRequest, com.jervis.contracts.server.GuidelinesPayload> getGetMethod;
    if ((getGetMethod = ServerGuidelinesServiceGrpc.getGetMethod) == null) {
      synchronized (ServerGuidelinesServiceGrpc.class) {
        if ((getGetMethod = ServerGuidelinesServiceGrpc.getGetMethod) == null) {
          ServerGuidelinesServiceGrpc.getGetMethod = getGetMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetRequest, com.jervis.contracts.server.GuidelinesPayload>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Get"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GuidelinesPayload.getDefaultInstance()))
              .setSchemaDescriptor(new ServerGuidelinesServiceMethodDescriptorSupplier("Get"))
              .build();
        }
      }
    }
    return getGetMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.SetRequest,
      com.jervis.contracts.server.GuidelinesPayload> getSetMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Set",
      requestType = com.jervis.contracts.server.SetRequest.class,
      responseType = com.jervis.contracts.server.GuidelinesPayload.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.SetRequest,
      com.jervis.contracts.server.GuidelinesPayload> getSetMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.SetRequest, com.jervis.contracts.server.GuidelinesPayload> getSetMethod;
    if ((getSetMethod = ServerGuidelinesServiceGrpc.getSetMethod) == null) {
      synchronized (ServerGuidelinesServiceGrpc.class) {
        if ((getSetMethod = ServerGuidelinesServiceGrpc.getSetMethod) == null) {
          ServerGuidelinesServiceGrpc.getSetMethod = getSetMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.SetRequest, com.jervis.contracts.server.GuidelinesPayload>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Set"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SetRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GuidelinesPayload.getDefaultInstance()))
              .setSchemaDescriptor(new ServerGuidelinesServiceMethodDescriptorSupplier("Set"))
              .build();
        }
      }
    }
    return getSetMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerGuidelinesServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGuidelinesServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGuidelinesServiceStub>() {
        @java.lang.Override
        public ServerGuidelinesServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGuidelinesServiceStub(channel, callOptions);
        }
      };
    return ServerGuidelinesServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerGuidelinesServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGuidelinesServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGuidelinesServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerGuidelinesServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGuidelinesServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerGuidelinesServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerGuidelinesServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGuidelinesServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGuidelinesServiceBlockingStub>() {
        @java.lang.Override
        public ServerGuidelinesServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGuidelinesServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerGuidelinesServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerGuidelinesServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGuidelinesServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGuidelinesServiceFutureStub>() {
        @java.lang.Override
        public ServerGuidelinesServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGuidelinesServiceFutureStub(channel, callOptions);
        }
      };
    return ServerGuidelinesServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerGuidelinesService is the gRPC surface for operational-
   * guidelines CRUD: coding/git/review/communication/approval/general
   * rules at GLOBAL / CLIENT / PROJECT scopes, merged for a caller's
   * context. The rule tree itself is large and all consumers (orchestrator,
   * correction, LLM router prompt-builder) treat it as opaque JSON —
   * serialization stays kotlinx.serialization against `shared/common-dto`
   * types. Proto-typing the full tree would duplicate the schema in two
   * places; instead we carry the serialized document body as a field and
   * let consumers parse it with their native JSON library.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Merged result for a client+project context (GLOBAL → CLIENT → PROJECT
     * deep-merge). Body is `MergedGuidelinesDto` encoded as JSON.
     * </pre>
     */
    default void getMerged(com.jervis.contracts.server.GetMergedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GuidelinesPayload> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMergedMethod(), responseObserver);
    }

    /**
     * <pre>
     * Raw (unmerged) document for one explicit scope. Body is
     * `GuidelinesDocumentDto` encoded as JSON.
     * </pre>
     */
    default void get(com.jervis.contracts.server.GetRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GuidelinesPayload> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMethod(), responseObserver);
    }

    /**
     * <pre>
     * Partial update of a scope — `update_json` is `GuidelinesUpdateRequest`
     * encoded as JSON. Response carries the updated `GuidelinesDocumentDto`.
     * </pre>
     */
    default void set(com.jervis.contracts.server.SetRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GuidelinesPayload> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSetMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerGuidelinesService.
   * <pre>
   * ServerGuidelinesService is the gRPC surface for operational-
   * guidelines CRUD: coding/git/review/communication/approval/general
   * rules at GLOBAL / CLIENT / PROJECT scopes, merged for a caller's
   * context. The rule tree itself is large and all consumers (orchestrator,
   * correction, LLM router prompt-builder) treat it as opaque JSON —
   * serialization stays kotlinx.serialization against `shared/common-dto`
   * types. Proto-typing the full tree would duplicate the schema in two
   * places; instead we carry the serialized document body as a field and
   * let consumers parse it with their native JSON library.
   * </pre>
   */
  public static abstract class ServerGuidelinesServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerGuidelinesServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerGuidelinesService.
   * <pre>
   * ServerGuidelinesService is the gRPC surface for operational-
   * guidelines CRUD: coding/git/review/communication/approval/general
   * rules at GLOBAL / CLIENT / PROJECT scopes, merged for a caller's
   * context. The rule tree itself is large and all consumers (orchestrator,
   * correction, LLM router prompt-builder) treat it as opaque JSON —
   * serialization stays kotlinx.serialization against `shared/common-dto`
   * types. Proto-typing the full tree would duplicate the schema in two
   * places; instead we carry the serialized document body as a field and
   * let consumers parse it with their native JSON library.
   * </pre>
   */
  public static final class ServerGuidelinesServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerGuidelinesServiceStub> {
    private ServerGuidelinesServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGuidelinesServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGuidelinesServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Merged result for a client+project context (GLOBAL → CLIENT → PROJECT
     * deep-merge). Body is `MergedGuidelinesDto` encoded as JSON.
     * </pre>
     */
    public void getMerged(com.jervis.contracts.server.GetMergedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GuidelinesPayload> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetMergedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Raw (unmerged) document for one explicit scope. Body is
     * `GuidelinesDocumentDto` encoded as JSON.
     * </pre>
     */
    public void get(com.jervis.contracts.server.GetRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GuidelinesPayload> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Partial update of a scope — `update_json` is `GuidelinesUpdateRequest`
     * encoded as JSON. Response carries the updated `GuidelinesDocumentDto`.
     * </pre>
     */
    public void set(com.jervis.contracts.server.SetRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GuidelinesPayload> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSetMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerGuidelinesService.
   * <pre>
   * ServerGuidelinesService is the gRPC surface for operational-
   * guidelines CRUD: coding/git/review/communication/approval/general
   * rules at GLOBAL / CLIENT / PROJECT scopes, merged for a caller's
   * context. The rule tree itself is large and all consumers (orchestrator,
   * correction, LLM router prompt-builder) treat it as opaque JSON —
   * serialization stays kotlinx.serialization against `shared/common-dto`
   * types. Proto-typing the full tree would duplicate the schema in two
   * places; instead we carry the serialized document body as a field and
   * let consumers parse it with their native JSON library.
   * </pre>
   */
  public static final class ServerGuidelinesServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerGuidelinesServiceBlockingV2Stub> {
    private ServerGuidelinesServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGuidelinesServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGuidelinesServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Merged result for a client+project context (GLOBAL → CLIENT → PROJECT
     * deep-merge). Body is `MergedGuidelinesDto` encoded as JSON.
     * </pre>
     */
    public com.jervis.contracts.server.GuidelinesPayload getMerged(com.jervis.contracts.server.GetMergedRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetMergedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Raw (unmerged) document for one explicit scope. Body is
     * `GuidelinesDocumentDto` encoded as JSON.
     * </pre>
     */
    public com.jervis.contracts.server.GuidelinesPayload get(com.jervis.contracts.server.GetRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Partial update of a scope — `update_json` is `GuidelinesUpdateRequest`
     * encoded as JSON. Response carries the updated `GuidelinesDocumentDto`.
     * </pre>
     */
    public com.jervis.contracts.server.GuidelinesPayload set(com.jervis.contracts.server.SetRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSetMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerGuidelinesService.
   * <pre>
   * ServerGuidelinesService is the gRPC surface for operational-
   * guidelines CRUD: coding/git/review/communication/approval/general
   * rules at GLOBAL / CLIENT / PROJECT scopes, merged for a caller's
   * context. The rule tree itself is large and all consumers (orchestrator,
   * correction, LLM router prompt-builder) treat it as opaque JSON —
   * serialization stays kotlinx.serialization against `shared/common-dto`
   * types. Proto-typing the full tree would duplicate the schema in two
   * places; instead we carry the serialized document body as a field and
   * let consumers parse it with their native JSON library.
   * </pre>
   */
  public static final class ServerGuidelinesServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerGuidelinesServiceBlockingStub> {
    private ServerGuidelinesServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGuidelinesServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGuidelinesServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Merged result for a client+project context (GLOBAL → CLIENT → PROJECT
     * deep-merge). Body is `MergedGuidelinesDto` encoded as JSON.
     * </pre>
     */
    public com.jervis.contracts.server.GuidelinesPayload getMerged(com.jervis.contracts.server.GetMergedRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetMergedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Raw (unmerged) document for one explicit scope. Body is
     * `GuidelinesDocumentDto` encoded as JSON.
     * </pre>
     */
    public com.jervis.contracts.server.GuidelinesPayload get(com.jervis.contracts.server.GetRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Partial update of a scope — `update_json` is `GuidelinesUpdateRequest`
     * encoded as JSON. Response carries the updated `GuidelinesDocumentDto`.
     * </pre>
     */
    public com.jervis.contracts.server.GuidelinesPayload set(com.jervis.contracts.server.SetRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSetMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerGuidelinesService.
   * <pre>
   * ServerGuidelinesService is the gRPC surface for operational-
   * guidelines CRUD: coding/git/review/communication/approval/general
   * rules at GLOBAL / CLIENT / PROJECT scopes, merged for a caller's
   * context. The rule tree itself is large and all consumers (orchestrator,
   * correction, LLM router prompt-builder) treat it as opaque JSON —
   * serialization stays kotlinx.serialization against `shared/common-dto`
   * types. Proto-typing the full tree would duplicate the schema in two
   * places; instead we carry the serialized document body as a field and
   * let consumers parse it with their native JSON library.
   * </pre>
   */
  public static final class ServerGuidelinesServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerGuidelinesServiceFutureStub> {
    private ServerGuidelinesServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGuidelinesServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGuidelinesServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Merged result for a client+project context (GLOBAL → CLIENT → PROJECT
     * deep-merge). Body is `MergedGuidelinesDto` encoded as JSON.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GuidelinesPayload> getMerged(
        com.jervis.contracts.server.GetMergedRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetMergedMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Raw (unmerged) document for one explicit scope. Body is
     * `GuidelinesDocumentDto` encoded as JSON.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GuidelinesPayload> get(
        com.jervis.contracts.server.GetRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Partial update of a scope — `update_json` is `GuidelinesUpdateRequest`
     * encoded as JSON. Response carries the updated `GuidelinesDocumentDto`.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GuidelinesPayload> set(
        com.jervis.contracts.server.SetRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSetMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_MERGED = 0;
  private static final int METHODID_GET = 1;
  private static final int METHODID_SET = 2;

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
        case METHODID_GET_MERGED:
          serviceImpl.getMerged((com.jervis.contracts.server.GetMergedRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GuidelinesPayload>) responseObserver);
          break;
        case METHODID_GET:
          serviceImpl.get((com.jervis.contracts.server.GetRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GuidelinesPayload>) responseObserver);
          break;
        case METHODID_SET:
          serviceImpl.set((com.jervis.contracts.server.SetRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GuidelinesPayload>) responseObserver);
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
          getGetMergedMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetMergedRequest,
              com.jervis.contracts.server.GuidelinesPayload>(
                service, METHODID_GET_MERGED)))
        .addMethod(
          getGetMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetRequest,
              com.jervis.contracts.server.GuidelinesPayload>(
                service, METHODID_GET)))
        .addMethod(
          getSetMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.SetRequest,
              com.jervis.contracts.server.GuidelinesPayload>(
                service, METHODID_SET)))
        .build();
  }

  private static abstract class ServerGuidelinesServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerGuidelinesServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerGuidelinesProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerGuidelinesService");
    }
  }

  private static final class ServerGuidelinesServiceFileDescriptorSupplier
      extends ServerGuidelinesServiceBaseDescriptorSupplier {
    ServerGuidelinesServiceFileDescriptorSupplier() {}
  }

  private static final class ServerGuidelinesServiceMethodDescriptorSupplier
      extends ServerGuidelinesServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerGuidelinesServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerGuidelinesServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerGuidelinesServiceFileDescriptorSupplier())
              .addMethod(getGetMergedMethod())
              .addMethod(getGetMethod())
              .addMethod(getSetMethod())
              .build();
        }
      }
    }
    return result;
  }
}
