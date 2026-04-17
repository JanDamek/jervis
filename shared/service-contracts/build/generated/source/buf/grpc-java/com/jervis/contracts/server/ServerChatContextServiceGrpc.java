package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerChatContextService provides runtime context the Python chat
 * handler injects into the LLM system prompt: clients+projects listing,
 * pending user tasks summary, unclassified meetings count, user
 * timezone. Read-only endpoints with small response payloads.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerChatContextServiceGrpc {

  private ServerChatContextServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerChatContextService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ClientsProjectsRequest,
      com.jervis.contracts.server.ClientsProjectsResponse> getListClientsProjectsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListClientsProjects",
      requestType = com.jervis.contracts.server.ClientsProjectsRequest.class,
      responseType = com.jervis.contracts.server.ClientsProjectsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ClientsProjectsRequest,
      com.jervis.contracts.server.ClientsProjectsResponse> getListClientsProjectsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ClientsProjectsRequest, com.jervis.contracts.server.ClientsProjectsResponse> getListClientsProjectsMethod;
    if ((getListClientsProjectsMethod = ServerChatContextServiceGrpc.getListClientsProjectsMethod) == null) {
      synchronized (ServerChatContextServiceGrpc.class) {
        if ((getListClientsProjectsMethod = ServerChatContextServiceGrpc.getListClientsProjectsMethod) == null) {
          ServerChatContextServiceGrpc.getListClientsProjectsMethod = getListClientsProjectsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ClientsProjectsRequest, com.jervis.contracts.server.ClientsProjectsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListClientsProjects"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ClientsProjectsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ClientsProjectsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerChatContextServiceMethodDescriptorSupplier("ListClientsProjects"))
              .build();
        }
      }
    }
    return getListClientsProjectsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.PendingUserTasksRequest,
      com.jervis.contracts.server.PendingUserTasksResponse> getPendingUserTasksSummaryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PendingUserTasksSummary",
      requestType = com.jervis.contracts.server.PendingUserTasksRequest.class,
      responseType = com.jervis.contracts.server.PendingUserTasksResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.PendingUserTasksRequest,
      com.jervis.contracts.server.PendingUserTasksResponse> getPendingUserTasksSummaryMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.PendingUserTasksRequest, com.jervis.contracts.server.PendingUserTasksResponse> getPendingUserTasksSummaryMethod;
    if ((getPendingUserTasksSummaryMethod = ServerChatContextServiceGrpc.getPendingUserTasksSummaryMethod) == null) {
      synchronized (ServerChatContextServiceGrpc.class) {
        if ((getPendingUserTasksSummaryMethod = ServerChatContextServiceGrpc.getPendingUserTasksSummaryMethod) == null) {
          ServerChatContextServiceGrpc.getPendingUserTasksSummaryMethod = getPendingUserTasksSummaryMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.PendingUserTasksRequest, com.jervis.contracts.server.PendingUserTasksResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PendingUserTasksSummary"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PendingUserTasksRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PendingUserTasksResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerChatContextServiceMethodDescriptorSupplier("PendingUserTasksSummary"))
              .build();
        }
      }
    }
    return getPendingUserTasksSummaryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.UnclassifiedCountRequest,
      com.jervis.contracts.server.UnclassifiedCountResponse> getUnclassifiedMeetingsCountMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UnclassifiedMeetingsCount",
      requestType = com.jervis.contracts.server.UnclassifiedCountRequest.class,
      responseType = com.jervis.contracts.server.UnclassifiedCountResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.UnclassifiedCountRequest,
      com.jervis.contracts.server.UnclassifiedCountResponse> getUnclassifiedMeetingsCountMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.UnclassifiedCountRequest, com.jervis.contracts.server.UnclassifiedCountResponse> getUnclassifiedMeetingsCountMethod;
    if ((getUnclassifiedMeetingsCountMethod = ServerChatContextServiceGrpc.getUnclassifiedMeetingsCountMethod) == null) {
      synchronized (ServerChatContextServiceGrpc.class) {
        if ((getUnclassifiedMeetingsCountMethod = ServerChatContextServiceGrpc.getUnclassifiedMeetingsCountMethod) == null) {
          ServerChatContextServiceGrpc.getUnclassifiedMeetingsCountMethod = getUnclassifiedMeetingsCountMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.UnclassifiedCountRequest, com.jervis.contracts.server.UnclassifiedCountResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UnclassifiedMeetingsCount"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UnclassifiedCountRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UnclassifiedCountResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerChatContextServiceMethodDescriptorSupplier("UnclassifiedMeetingsCount"))
              .build();
        }
      }
    }
    return getUnclassifiedMeetingsCountMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.UserTimezoneRequest,
      com.jervis.contracts.server.UserTimezoneResponse> getGetUserTimezoneMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetUserTimezone",
      requestType = com.jervis.contracts.server.UserTimezoneRequest.class,
      responseType = com.jervis.contracts.server.UserTimezoneResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.UserTimezoneRequest,
      com.jervis.contracts.server.UserTimezoneResponse> getGetUserTimezoneMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.UserTimezoneRequest, com.jervis.contracts.server.UserTimezoneResponse> getGetUserTimezoneMethod;
    if ((getGetUserTimezoneMethod = ServerChatContextServiceGrpc.getGetUserTimezoneMethod) == null) {
      synchronized (ServerChatContextServiceGrpc.class) {
        if ((getGetUserTimezoneMethod = ServerChatContextServiceGrpc.getGetUserTimezoneMethod) == null) {
          ServerChatContextServiceGrpc.getGetUserTimezoneMethod = getGetUserTimezoneMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.UserTimezoneRequest, com.jervis.contracts.server.UserTimezoneResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetUserTimezone"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UserTimezoneRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UserTimezoneResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerChatContextServiceMethodDescriptorSupplier("GetUserTimezone"))
              .build();
        }
      }
    }
    return getGetUserTimezoneMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ActiveChatTopicsRequest,
      com.jervis.contracts.server.ActiveChatTopicsResponse> getGetActiveChatTopicsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetActiveChatTopics",
      requestType = com.jervis.contracts.server.ActiveChatTopicsRequest.class,
      responseType = com.jervis.contracts.server.ActiveChatTopicsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ActiveChatTopicsRequest,
      com.jervis.contracts.server.ActiveChatTopicsResponse> getGetActiveChatTopicsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ActiveChatTopicsRequest, com.jervis.contracts.server.ActiveChatTopicsResponse> getGetActiveChatTopicsMethod;
    if ((getGetActiveChatTopicsMethod = ServerChatContextServiceGrpc.getGetActiveChatTopicsMethod) == null) {
      synchronized (ServerChatContextServiceGrpc.class) {
        if ((getGetActiveChatTopicsMethod = ServerChatContextServiceGrpc.getGetActiveChatTopicsMethod) == null) {
          ServerChatContextServiceGrpc.getGetActiveChatTopicsMethod = getGetActiveChatTopicsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ActiveChatTopicsRequest, com.jervis.contracts.server.ActiveChatTopicsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetActiveChatTopics"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ActiveChatTopicsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ActiveChatTopicsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerChatContextServiceMethodDescriptorSupplier("GetActiveChatTopics"))
              .build();
        }
      }
    }
    return getGetActiveChatTopicsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerChatContextServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerChatContextServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerChatContextServiceStub>() {
        @java.lang.Override
        public ServerChatContextServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerChatContextServiceStub(channel, callOptions);
        }
      };
    return ServerChatContextServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerChatContextServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerChatContextServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerChatContextServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerChatContextServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerChatContextServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerChatContextServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerChatContextServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerChatContextServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerChatContextServiceBlockingStub>() {
        @java.lang.Override
        public ServerChatContextServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerChatContextServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerChatContextServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerChatContextServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerChatContextServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerChatContextServiceFutureStub>() {
        @java.lang.Override
        public ServerChatContextServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerChatContextServiceFutureStub(channel, callOptions);
        }
      };
    return ServerChatContextServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerChatContextService provides runtime context the Python chat
   * handler injects into the LLM system prompt: clients+projects listing,
   * pending user tasks summary, unclassified meetings count, user
   * timezone. Read-only endpoints with small response payloads.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void listClientsProjects(com.jervis.contracts.server.ClientsProjectsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ClientsProjectsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListClientsProjectsMethod(), responseObserver);
    }

    /**
     */
    default void pendingUserTasksSummary(com.jervis.contracts.server.PendingUserTasksRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PendingUserTasksResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPendingUserTasksSummaryMethod(), responseObserver);
    }

    /**
     */
    default void unclassifiedMeetingsCount(com.jervis.contracts.server.UnclassifiedCountRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UnclassifiedCountResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnclassifiedMeetingsCountMethod(), responseObserver);
    }

    /**
     */
    default void getUserTimezone(com.jervis.contracts.server.UserTimezoneRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UserTimezoneResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetUserTimezoneMethod(), responseObserver);
    }

    /**
     * <pre>
     * Active chat session topics — last-N messages of the active chat so the
     * qualification agent knows what the user is currently working on.
     * </pre>
     */
    default void getActiveChatTopics(com.jervis.contracts.server.ActiveChatTopicsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ActiveChatTopicsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetActiveChatTopicsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerChatContextService.
   * <pre>
   * ServerChatContextService provides runtime context the Python chat
   * handler injects into the LLM system prompt: clients+projects listing,
   * pending user tasks summary, unclassified meetings count, user
   * timezone. Read-only endpoints with small response payloads.
   * </pre>
   */
  public static abstract class ServerChatContextServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerChatContextServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerChatContextService.
   * <pre>
   * ServerChatContextService provides runtime context the Python chat
   * handler injects into the LLM system prompt: clients+projects listing,
   * pending user tasks summary, unclassified meetings count, user
   * timezone. Read-only endpoints with small response payloads.
   * </pre>
   */
  public static final class ServerChatContextServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerChatContextServiceStub> {
    private ServerChatContextServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerChatContextServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerChatContextServiceStub(channel, callOptions);
    }

    /**
     */
    public void listClientsProjects(com.jervis.contracts.server.ClientsProjectsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ClientsProjectsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListClientsProjectsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void pendingUserTasksSummary(com.jervis.contracts.server.PendingUserTasksRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PendingUserTasksResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPendingUserTasksSummaryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void unclassifiedMeetingsCount(com.jervis.contracts.server.UnclassifiedCountRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UnclassifiedCountResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUnclassifiedMeetingsCountMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getUserTimezone(com.jervis.contracts.server.UserTimezoneRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UserTimezoneResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetUserTimezoneMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Active chat session topics — last-N messages of the active chat so the
     * qualification agent knows what the user is currently working on.
     * </pre>
     */
    public void getActiveChatTopics(com.jervis.contracts.server.ActiveChatTopicsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ActiveChatTopicsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetActiveChatTopicsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerChatContextService.
   * <pre>
   * ServerChatContextService provides runtime context the Python chat
   * handler injects into the LLM system prompt: clients+projects listing,
   * pending user tasks summary, unclassified meetings count, user
   * timezone. Read-only endpoints with small response payloads.
   * </pre>
   */
  public static final class ServerChatContextServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerChatContextServiceBlockingV2Stub> {
    private ServerChatContextServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerChatContextServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerChatContextServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ClientsProjectsResponse listClientsProjects(com.jervis.contracts.server.ClientsProjectsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListClientsProjectsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PendingUserTasksResponse pendingUserTasksSummary(com.jervis.contracts.server.PendingUserTasksRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPendingUserTasksSummaryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UnclassifiedCountResponse unclassifiedMeetingsCount(com.jervis.contracts.server.UnclassifiedCountRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUnclassifiedMeetingsCountMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UserTimezoneResponse getUserTimezone(com.jervis.contracts.server.UserTimezoneRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetUserTimezoneMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Active chat session topics — last-N messages of the active chat so the
     * qualification agent knows what the user is currently working on.
     * </pre>
     */
    public com.jervis.contracts.server.ActiveChatTopicsResponse getActiveChatTopics(com.jervis.contracts.server.ActiveChatTopicsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetActiveChatTopicsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerChatContextService.
   * <pre>
   * ServerChatContextService provides runtime context the Python chat
   * handler injects into the LLM system prompt: clients+projects listing,
   * pending user tasks summary, unclassified meetings count, user
   * timezone. Read-only endpoints with small response payloads.
   * </pre>
   */
  public static final class ServerChatContextServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerChatContextServiceBlockingStub> {
    private ServerChatContextServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerChatContextServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerChatContextServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ClientsProjectsResponse listClientsProjects(com.jervis.contracts.server.ClientsProjectsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListClientsProjectsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PendingUserTasksResponse pendingUserTasksSummary(com.jervis.contracts.server.PendingUserTasksRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPendingUserTasksSummaryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UnclassifiedCountResponse unclassifiedMeetingsCount(com.jervis.contracts.server.UnclassifiedCountRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnclassifiedMeetingsCountMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UserTimezoneResponse getUserTimezone(com.jervis.contracts.server.UserTimezoneRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetUserTimezoneMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Active chat session topics — last-N messages of the active chat so the
     * qualification agent knows what the user is currently working on.
     * </pre>
     */
    public com.jervis.contracts.server.ActiveChatTopicsResponse getActiveChatTopics(com.jervis.contracts.server.ActiveChatTopicsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetActiveChatTopicsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerChatContextService.
   * <pre>
   * ServerChatContextService provides runtime context the Python chat
   * handler injects into the LLM system prompt: clients+projects listing,
   * pending user tasks summary, unclassified meetings count, user
   * timezone. Read-only endpoints with small response payloads.
   * </pre>
   */
  public static final class ServerChatContextServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerChatContextServiceFutureStub> {
    private ServerChatContextServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerChatContextServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerChatContextServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ClientsProjectsResponse> listClientsProjects(
        com.jervis.contracts.server.ClientsProjectsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListClientsProjectsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.PendingUserTasksResponse> pendingUserTasksSummary(
        com.jervis.contracts.server.PendingUserTasksRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPendingUserTasksSummaryMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.UnclassifiedCountResponse> unclassifiedMeetingsCount(
        com.jervis.contracts.server.UnclassifiedCountRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUnclassifiedMeetingsCountMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.UserTimezoneResponse> getUserTimezone(
        com.jervis.contracts.server.UserTimezoneRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetUserTimezoneMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Active chat session topics — last-N messages of the active chat so the
     * qualification agent knows what the user is currently working on.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ActiveChatTopicsResponse> getActiveChatTopics(
        com.jervis.contracts.server.ActiveChatTopicsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetActiveChatTopicsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LIST_CLIENTS_PROJECTS = 0;
  private static final int METHODID_PENDING_USER_TASKS_SUMMARY = 1;
  private static final int METHODID_UNCLASSIFIED_MEETINGS_COUNT = 2;
  private static final int METHODID_GET_USER_TIMEZONE = 3;
  private static final int METHODID_GET_ACTIVE_CHAT_TOPICS = 4;

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
        case METHODID_LIST_CLIENTS_PROJECTS:
          serviceImpl.listClientsProjects((com.jervis.contracts.server.ClientsProjectsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ClientsProjectsResponse>) responseObserver);
          break;
        case METHODID_PENDING_USER_TASKS_SUMMARY:
          serviceImpl.pendingUserTasksSummary((com.jervis.contracts.server.PendingUserTasksRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.PendingUserTasksResponse>) responseObserver);
          break;
        case METHODID_UNCLASSIFIED_MEETINGS_COUNT:
          serviceImpl.unclassifiedMeetingsCount((com.jervis.contracts.server.UnclassifiedCountRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.UnclassifiedCountResponse>) responseObserver);
          break;
        case METHODID_GET_USER_TIMEZONE:
          serviceImpl.getUserTimezone((com.jervis.contracts.server.UserTimezoneRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.UserTimezoneResponse>) responseObserver);
          break;
        case METHODID_GET_ACTIVE_CHAT_TOPICS:
          serviceImpl.getActiveChatTopics((com.jervis.contracts.server.ActiveChatTopicsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ActiveChatTopicsResponse>) responseObserver);
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
          getListClientsProjectsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ClientsProjectsRequest,
              com.jervis.contracts.server.ClientsProjectsResponse>(
                service, METHODID_LIST_CLIENTS_PROJECTS)))
        .addMethod(
          getPendingUserTasksSummaryMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.PendingUserTasksRequest,
              com.jervis.contracts.server.PendingUserTasksResponse>(
                service, METHODID_PENDING_USER_TASKS_SUMMARY)))
        .addMethod(
          getUnclassifiedMeetingsCountMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.UnclassifiedCountRequest,
              com.jervis.contracts.server.UnclassifiedCountResponse>(
                service, METHODID_UNCLASSIFIED_MEETINGS_COUNT)))
        .addMethod(
          getGetUserTimezoneMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.UserTimezoneRequest,
              com.jervis.contracts.server.UserTimezoneResponse>(
                service, METHODID_GET_USER_TIMEZONE)))
        .addMethod(
          getGetActiveChatTopicsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ActiveChatTopicsRequest,
              com.jervis.contracts.server.ActiveChatTopicsResponse>(
                service, METHODID_GET_ACTIVE_CHAT_TOPICS)))
        .build();
  }

  private static abstract class ServerChatContextServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerChatContextServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerChatContextProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerChatContextService");
    }
  }

  private static final class ServerChatContextServiceFileDescriptorSupplier
      extends ServerChatContextServiceBaseDescriptorSupplier {
    ServerChatContextServiceFileDescriptorSupplier() {}
  }

  private static final class ServerChatContextServiceMethodDescriptorSupplier
      extends ServerChatContextServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerChatContextServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerChatContextServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerChatContextServiceFileDescriptorSupplier())
              .addMethod(getListClientsProjectsMethod())
              .addMethod(getPendingUserTasksSummaryMethod())
              .addMethod(getUnclassifiedMeetingsCountMethod())
              .addMethod(getGetUserTimezoneMethod())
              .addMethod(getGetActiveChatTopicsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
