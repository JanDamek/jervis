package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerTtsRulesService — the Kotlin server owns the MongoDB collection
 * `ttsRules` (acronym / strip / replace). XTTS calls GetForScope before
 * each SpeakStream to fetch the applicable rules; orchestrator + MCP use
 * the mutating RPCs to let the user edit via chat.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerTtsRulesServiceGrpc {

  private ServerTtsRulesServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerTtsRulesService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetForScopeRequest,
      com.jervis.contracts.server.TtsRuleList> getGetForScopeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetForScope",
      requestType = com.jervis.contracts.server.GetForScopeRequest.class,
      responseType = com.jervis.contracts.server.TtsRuleList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetForScopeRequest,
      com.jervis.contracts.server.TtsRuleList> getGetForScopeMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetForScopeRequest, com.jervis.contracts.server.TtsRuleList> getGetForScopeMethod;
    if ((getGetForScopeMethod = ServerTtsRulesServiceGrpc.getGetForScopeMethod) == null) {
      synchronized (ServerTtsRulesServiceGrpc.class) {
        if ((getGetForScopeMethod = ServerTtsRulesServiceGrpc.getGetForScopeMethod) == null) {
          ServerTtsRulesServiceGrpc.getGetForScopeMethod = getGetForScopeMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetForScopeRequest, com.jervis.contracts.server.TtsRuleList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetForScope"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetForScopeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TtsRuleList.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTtsRulesServiceMethodDescriptorSupplier("GetForScope"))
              .build();
        }
      }
    }
    return getGetForScopeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListTtsRulesRequest,
      com.jervis.contracts.server.TtsRuleList> getListMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "List",
      requestType = com.jervis.contracts.server.ListTtsRulesRequest.class,
      responseType = com.jervis.contracts.server.TtsRuleList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListTtsRulesRequest,
      com.jervis.contracts.server.TtsRuleList> getListMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListTtsRulesRequest, com.jervis.contracts.server.TtsRuleList> getListMethod;
    if ((getListMethod = ServerTtsRulesServiceGrpc.getListMethod) == null) {
      synchronized (ServerTtsRulesServiceGrpc.class) {
        if ((getListMethod = ServerTtsRulesServiceGrpc.getListMethod) == null) {
          ServerTtsRulesServiceGrpc.getListMethod = getListMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListTtsRulesRequest, com.jervis.contracts.server.TtsRuleList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "List"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListTtsRulesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TtsRuleList.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTtsRulesServiceMethodDescriptorSupplier("List"))
              .build();
        }
      }
    }
    return getListMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TtsRule,
      com.jervis.contracts.server.TtsRule> getAddMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Add",
      requestType = com.jervis.contracts.server.TtsRule.class,
      responseType = com.jervis.contracts.server.TtsRule.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TtsRule,
      com.jervis.contracts.server.TtsRule> getAddMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TtsRule, com.jervis.contracts.server.TtsRule> getAddMethod;
    if ((getAddMethod = ServerTtsRulesServiceGrpc.getAddMethod) == null) {
      synchronized (ServerTtsRulesServiceGrpc.class) {
        if ((getAddMethod = ServerTtsRulesServiceGrpc.getAddMethod) == null) {
          ServerTtsRulesServiceGrpc.getAddMethod = getAddMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TtsRule, com.jervis.contracts.server.TtsRule>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Add"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TtsRule.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TtsRule.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTtsRulesServiceMethodDescriptorSupplier("Add"))
              .build();
        }
      }
    }
    return getAddMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TtsRule,
      com.jervis.contracts.server.TtsRule> getUpdateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Update",
      requestType = com.jervis.contracts.server.TtsRule.class,
      responseType = com.jervis.contracts.server.TtsRule.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TtsRule,
      com.jervis.contracts.server.TtsRule> getUpdateMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TtsRule, com.jervis.contracts.server.TtsRule> getUpdateMethod;
    if ((getUpdateMethod = ServerTtsRulesServiceGrpc.getUpdateMethod) == null) {
      synchronized (ServerTtsRulesServiceGrpc.class) {
        if ((getUpdateMethod = ServerTtsRulesServiceGrpc.getUpdateMethod) == null) {
          ServerTtsRulesServiceGrpc.getUpdateMethod = getUpdateMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TtsRule, com.jervis.contracts.server.TtsRule>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Update"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TtsRule.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TtsRule.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTtsRulesServiceMethodDescriptorSupplier("Update"))
              .build();
        }
      }
    }
    return getUpdateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.DeleteTtsRuleRequest,
      com.jervis.contracts.server.DeleteTtsRuleResponse> getDeleteMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Delete",
      requestType = com.jervis.contracts.server.DeleteTtsRuleRequest.class,
      responseType = com.jervis.contracts.server.DeleteTtsRuleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.DeleteTtsRuleRequest,
      com.jervis.contracts.server.DeleteTtsRuleResponse> getDeleteMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.DeleteTtsRuleRequest, com.jervis.contracts.server.DeleteTtsRuleResponse> getDeleteMethod;
    if ((getDeleteMethod = ServerTtsRulesServiceGrpc.getDeleteMethod) == null) {
      synchronized (ServerTtsRulesServiceGrpc.class) {
        if ((getDeleteMethod = ServerTtsRulesServiceGrpc.getDeleteMethod) == null) {
          ServerTtsRulesServiceGrpc.getDeleteMethod = getDeleteMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.DeleteTtsRuleRequest, com.jervis.contracts.server.DeleteTtsRuleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Delete"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.DeleteTtsRuleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.DeleteTtsRuleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTtsRulesServiceMethodDescriptorSupplier("Delete"))
              .build();
        }
      }
    }
    return getDeleteMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.PreviewRequest,
      com.jervis.contracts.server.PreviewResponse> getPreviewMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Preview",
      requestType = com.jervis.contracts.server.PreviewRequest.class,
      responseType = com.jervis.contracts.server.PreviewResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.PreviewRequest,
      com.jervis.contracts.server.PreviewResponse> getPreviewMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.PreviewRequest, com.jervis.contracts.server.PreviewResponse> getPreviewMethod;
    if ((getPreviewMethod = ServerTtsRulesServiceGrpc.getPreviewMethod) == null) {
      synchronized (ServerTtsRulesServiceGrpc.class) {
        if ((getPreviewMethod = ServerTtsRulesServiceGrpc.getPreviewMethod) == null) {
          ServerTtsRulesServiceGrpc.getPreviewMethod = getPreviewMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.PreviewRequest, com.jervis.contracts.server.PreviewResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Preview"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PreviewRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PreviewResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTtsRulesServiceMethodDescriptorSupplier("Preview"))
              .build();
        }
      }
    }
    return getPreviewMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerTtsRulesServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTtsRulesServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTtsRulesServiceStub>() {
        @java.lang.Override
        public ServerTtsRulesServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTtsRulesServiceStub(channel, callOptions);
        }
      };
    return ServerTtsRulesServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerTtsRulesServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTtsRulesServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTtsRulesServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerTtsRulesServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTtsRulesServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerTtsRulesServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerTtsRulesServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTtsRulesServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTtsRulesServiceBlockingStub>() {
        @java.lang.Override
        public ServerTtsRulesServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTtsRulesServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerTtsRulesServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerTtsRulesServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTtsRulesServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTtsRulesServiceFutureStub>() {
        @java.lang.Override
        public ServerTtsRulesServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTtsRulesServiceFutureStub(channel, callOptions);
        }
      };
    return ServerTtsRulesServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerTtsRulesService — the Kotlin server owns the MongoDB collection
   * `ttsRules` (acronym / strip / replace). XTTS calls GetForScope before
   * each SpeakStream to fetch the applicable rules; orchestrator + MCP use
   * the mutating RPCs to let the user edit via chat.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * One-shot fetch of all rules that apply to (language, clientId?, projectId?).
     * Returned list is already sorted PROJECT &gt; CLIENT &gt; GLOBAL so the caller
     * can apply them in order.
     * </pre>
     */
    default void getForScope(com.jervis.contracts.server.GetForScopeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRuleList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetForScopeMethod(), responseObserver);
    }

    /**
     */
    default void list(com.jervis.contracts.server.ListTtsRulesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRuleList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListMethod(), responseObserver);
    }

    /**
     */
    default void add(com.jervis.contracts.server.TtsRule request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRule> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAddMethod(), responseObserver);
    }

    /**
     */
    default void update(com.jervis.contracts.server.TtsRule request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRule> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateMethod(), responseObserver);
    }

    /**
     */
    default void delete(com.jervis.contracts.server.DeleteTtsRuleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.DeleteTtsRuleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteMethod(), responseObserver);
    }

    /**
     * <pre>
     * Dry-run: apply all matching rules to `text` and return the output + the
     * rule hits for UI preview.
     * </pre>
     */
    default void preview(com.jervis.contracts.server.PreviewRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PreviewResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPreviewMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerTtsRulesService.
   * <pre>
   * ServerTtsRulesService — the Kotlin server owns the MongoDB collection
   * `ttsRules` (acronym / strip / replace). XTTS calls GetForScope before
   * each SpeakStream to fetch the applicable rules; orchestrator + MCP use
   * the mutating RPCs to let the user edit via chat.
   * </pre>
   */
  public static abstract class ServerTtsRulesServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerTtsRulesServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerTtsRulesService.
   * <pre>
   * ServerTtsRulesService — the Kotlin server owns the MongoDB collection
   * `ttsRules` (acronym / strip / replace). XTTS calls GetForScope before
   * each SpeakStream to fetch the applicable rules; orchestrator + MCP use
   * the mutating RPCs to let the user edit via chat.
   * </pre>
   */
  public static final class ServerTtsRulesServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerTtsRulesServiceStub> {
    private ServerTtsRulesServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTtsRulesServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTtsRulesServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * One-shot fetch of all rules that apply to (language, clientId?, projectId?).
     * Returned list is already sorted PROJECT &gt; CLIENT &gt; GLOBAL so the caller
     * can apply them in order.
     * </pre>
     */
    public void getForScope(com.jervis.contracts.server.GetForScopeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRuleList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetForScopeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void list(com.jervis.contracts.server.ListTtsRulesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRuleList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void add(com.jervis.contracts.server.TtsRule request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRule> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAddMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void update(com.jervis.contracts.server.TtsRule request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRule> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void delete(com.jervis.contracts.server.DeleteTtsRuleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.DeleteTtsRuleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Dry-run: apply all matching rules to `text` and return the output + the
     * rule hits for UI preview.
     * </pre>
     */
    public void preview(com.jervis.contracts.server.PreviewRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PreviewResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPreviewMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerTtsRulesService.
   * <pre>
   * ServerTtsRulesService — the Kotlin server owns the MongoDB collection
   * `ttsRules` (acronym / strip / replace). XTTS calls GetForScope before
   * each SpeakStream to fetch the applicable rules; orchestrator + MCP use
   * the mutating RPCs to let the user edit via chat.
   * </pre>
   */
  public static final class ServerTtsRulesServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerTtsRulesServiceBlockingV2Stub> {
    private ServerTtsRulesServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTtsRulesServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTtsRulesServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * One-shot fetch of all rules that apply to (language, clientId?, projectId?).
     * Returned list is already sorted PROJECT &gt; CLIENT &gt; GLOBAL so the caller
     * can apply them in order.
     * </pre>
     */
    public com.jervis.contracts.server.TtsRuleList getForScope(com.jervis.contracts.server.GetForScopeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetForScopeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TtsRuleList list(com.jervis.contracts.server.ListTtsRulesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TtsRule add(com.jervis.contracts.server.TtsRule request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAddMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TtsRule update(com.jervis.contracts.server.TtsRule request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.DeleteTtsRuleResponse delete(com.jervis.contracts.server.DeleteTtsRuleRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Dry-run: apply all matching rules to `text` and return the output + the
     * rule hits for UI preview.
     * </pre>
     */
    public com.jervis.contracts.server.PreviewResponse preview(com.jervis.contracts.server.PreviewRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPreviewMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerTtsRulesService.
   * <pre>
   * ServerTtsRulesService — the Kotlin server owns the MongoDB collection
   * `ttsRules` (acronym / strip / replace). XTTS calls GetForScope before
   * each SpeakStream to fetch the applicable rules; orchestrator + MCP use
   * the mutating RPCs to let the user edit via chat.
   * </pre>
   */
  public static final class ServerTtsRulesServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerTtsRulesServiceBlockingStub> {
    private ServerTtsRulesServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTtsRulesServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTtsRulesServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * One-shot fetch of all rules that apply to (language, clientId?, projectId?).
     * Returned list is already sorted PROJECT &gt; CLIENT &gt; GLOBAL so the caller
     * can apply them in order.
     * </pre>
     */
    public com.jervis.contracts.server.TtsRuleList getForScope(com.jervis.contracts.server.GetForScopeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetForScopeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TtsRuleList list(com.jervis.contracts.server.ListTtsRulesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TtsRule add(com.jervis.contracts.server.TtsRule request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAddMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TtsRule update(com.jervis.contracts.server.TtsRule request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.DeleteTtsRuleResponse delete(com.jervis.contracts.server.DeleteTtsRuleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Dry-run: apply all matching rules to `text` and return the output + the
     * rule hits for UI preview.
     * </pre>
     */
    public com.jervis.contracts.server.PreviewResponse preview(com.jervis.contracts.server.PreviewRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPreviewMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerTtsRulesService.
   * <pre>
   * ServerTtsRulesService — the Kotlin server owns the MongoDB collection
   * `ttsRules` (acronym / strip / replace). XTTS calls GetForScope before
   * each SpeakStream to fetch the applicable rules; orchestrator + MCP use
   * the mutating RPCs to let the user edit via chat.
   * </pre>
   */
  public static final class ServerTtsRulesServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerTtsRulesServiceFutureStub> {
    private ServerTtsRulesServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTtsRulesServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTtsRulesServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * One-shot fetch of all rules that apply to (language, clientId?, projectId?).
     * Returned list is already sorted PROJECT &gt; CLIENT &gt; GLOBAL so the caller
     * can apply them in order.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.TtsRuleList> getForScope(
        com.jervis.contracts.server.GetForScopeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetForScopeMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.TtsRuleList> list(
        com.jervis.contracts.server.ListTtsRulesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.TtsRule> add(
        com.jervis.contracts.server.TtsRule request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAddMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.TtsRule> update(
        com.jervis.contracts.server.TtsRule request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.DeleteTtsRuleResponse> delete(
        com.jervis.contracts.server.DeleteTtsRuleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Dry-run: apply all matching rules to `text` and return the output + the
     * rule hits for UI preview.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.PreviewResponse> preview(
        com.jervis.contracts.server.PreviewRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPreviewMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_FOR_SCOPE = 0;
  private static final int METHODID_LIST = 1;
  private static final int METHODID_ADD = 2;
  private static final int METHODID_UPDATE = 3;
  private static final int METHODID_DELETE = 4;
  private static final int METHODID_PREVIEW = 5;

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
        case METHODID_GET_FOR_SCOPE:
          serviceImpl.getForScope((com.jervis.contracts.server.GetForScopeRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRuleList>) responseObserver);
          break;
        case METHODID_LIST:
          serviceImpl.list((com.jervis.contracts.server.ListTtsRulesRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRuleList>) responseObserver);
          break;
        case METHODID_ADD:
          serviceImpl.add((com.jervis.contracts.server.TtsRule) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRule>) responseObserver);
          break;
        case METHODID_UPDATE:
          serviceImpl.update((com.jervis.contracts.server.TtsRule) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.TtsRule>) responseObserver);
          break;
        case METHODID_DELETE:
          serviceImpl.delete((com.jervis.contracts.server.DeleteTtsRuleRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.DeleteTtsRuleResponse>) responseObserver);
          break;
        case METHODID_PREVIEW:
          serviceImpl.preview((com.jervis.contracts.server.PreviewRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.PreviewResponse>) responseObserver);
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
          getGetForScopeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetForScopeRequest,
              com.jervis.contracts.server.TtsRuleList>(
                service, METHODID_GET_FOR_SCOPE)))
        .addMethod(
          getListMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListTtsRulesRequest,
              com.jervis.contracts.server.TtsRuleList>(
                service, METHODID_LIST)))
        .addMethod(
          getAddMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TtsRule,
              com.jervis.contracts.server.TtsRule>(
                service, METHODID_ADD)))
        .addMethod(
          getUpdateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TtsRule,
              com.jervis.contracts.server.TtsRule>(
                service, METHODID_UPDATE)))
        .addMethod(
          getDeleteMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.DeleteTtsRuleRequest,
              com.jervis.contracts.server.DeleteTtsRuleResponse>(
                service, METHODID_DELETE)))
        .addMethod(
          getPreviewMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.PreviewRequest,
              com.jervis.contracts.server.PreviewResponse>(
                service, METHODID_PREVIEW)))
        .build();
  }

  private static abstract class ServerTtsRulesServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerTtsRulesServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerTtsRulesProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerTtsRulesService");
    }
  }

  private static final class ServerTtsRulesServiceFileDescriptorSupplier
      extends ServerTtsRulesServiceBaseDescriptorSupplier {
    ServerTtsRulesServiceFileDescriptorSupplier() {}
  }

  private static final class ServerTtsRulesServiceMethodDescriptorSupplier
      extends ServerTtsRulesServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerTtsRulesServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerTtsRulesServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerTtsRulesServiceFileDescriptorSupplier())
              .addMethod(getGetForScopeMethod())
              .addMethod(getListMethod())
              .addMethod(getAddMethod())
              .addMethod(getUpdateMethod())
              .addMethod(getDeleteMethod())
              .addMethod(getPreviewMethod())
              .build();
        }
      }
    }
    return result;
  }
}
