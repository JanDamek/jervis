package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerBugTrackerService centralizes issue CRUD across GitHub, GitLab and
 * Jira. The server resolves the project's BUGTRACKER connection (falling
 * back to REPOSITORY) and dispatches to the right vendor client, so Python
 * callers stay provider-agnostic.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerBugTrackerServiceGrpc {

  private ServerBugTrackerServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerBugTrackerService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateIssueRequest,
      com.jervis.contracts.server.IssueResponse> getCreateIssueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateIssue",
      requestType = com.jervis.contracts.server.CreateIssueRequest.class,
      responseType = com.jervis.contracts.server.IssueResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateIssueRequest,
      com.jervis.contracts.server.IssueResponse> getCreateIssueMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateIssueRequest, com.jervis.contracts.server.IssueResponse> getCreateIssueMethod;
    if ((getCreateIssueMethod = ServerBugTrackerServiceGrpc.getCreateIssueMethod) == null) {
      synchronized (ServerBugTrackerServiceGrpc.class) {
        if ((getCreateIssueMethod = ServerBugTrackerServiceGrpc.getCreateIssueMethod) == null) {
          ServerBugTrackerServiceGrpc.getCreateIssueMethod = getCreateIssueMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateIssueRequest, com.jervis.contracts.server.IssueResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateIssue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateIssueRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.IssueResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerBugTrackerServiceMethodDescriptorSupplier("CreateIssue"))
              .build();
        }
      }
    }
    return getCreateIssueMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.AddIssueCommentRequest,
      com.jervis.contracts.server.CommentResponse> getAddIssueCommentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AddIssueComment",
      requestType = com.jervis.contracts.server.AddIssueCommentRequest.class,
      responseType = com.jervis.contracts.server.CommentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.AddIssueCommentRequest,
      com.jervis.contracts.server.CommentResponse> getAddIssueCommentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.AddIssueCommentRequest, com.jervis.contracts.server.CommentResponse> getAddIssueCommentMethod;
    if ((getAddIssueCommentMethod = ServerBugTrackerServiceGrpc.getAddIssueCommentMethod) == null) {
      synchronized (ServerBugTrackerServiceGrpc.class) {
        if ((getAddIssueCommentMethod = ServerBugTrackerServiceGrpc.getAddIssueCommentMethod) == null) {
          ServerBugTrackerServiceGrpc.getAddIssueCommentMethod = getAddIssueCommentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.AddIssueCommentRequest, com.jervis.contracts.server.CommentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AddIssueComment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AddIssueCommentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CommentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerBugTrackerServiceMethodDescriptorSupplier("AddIssueComment"))
              .build();
        }
      }
    }
    return getAddIssueCommentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateIssueRequest,
      com.jervis.contracts.server.IssueResponse> getUpdateIssueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateIssue",
      requestType = com.jervis.contracts.server.UpdateIssueRequest.class,
      responseType = com.jervis.contracts.server.IssueResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateIssueRequest,
      com.jervis.contracts.server.IssueResponse> getUpdateIssueMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateIssueRequest, com.jervis.contracts.server.IssueResponse> getUpdateIssueMethod;
    if ((getUpdateIssueMethod = ServerBugTrackerServiceGrpc.getUpdateIssueMethod) == null) {
      synchronized (ServerBugTrackerServiceGrpc.class) {
        if ((getUpdateIssueMethod = ServerBugTrackerServiceGrpc.getUpdateIssueMethod) == null) {
          ServerBugTrackerServiceGrpc.getUpdateIssueMethod = getUpdateIssueMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.UpdateIssueRequest, com.jervis.contracts.server.IssueResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateIssue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UpdateIssueRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.IssueResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerBugTrackerServiceMethodDescriptorSupplier("UpdateIssue"))
              .build();
        }
      }
    }
    return getUpdateIssueMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListIssuesRequest,
      com.jervis.contracts.server.ListIssuesResponse> getListIssuesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListIssues",
      requestType = com.jervis.contracts.server.ListIssuesRequest.class,
      responseType = com.jervis.contracts.server.ListIssuesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListIssuesRequest,
      com.jervis.contracts.server.ListIssuesResponse> getListIssuesMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListIssuesRequest, com.jervis.contracts.server.ListIssuesResponse> getListIssuesMethod;
    if ((getListIssuesMethod = ServerBugTrackerServiceGrpc.getListIssuesMethod) == null) {
      synchronized (ServerBugTrackerServiceGrpc.class) {
        if ((getListIssuesMethod = ServerBugTrackerServiceGrpc.getListIssuesMethod) == null) {
          ServerBugTrackerServiceGrpc.getListIssuesMethod = getListIssuesMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListIssuesRequest, com.jervis.contracts.server.ListIssuesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListIssues"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListIssuesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListIssuesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerBugTrackerServiceMethodDescriptorSupplier("ListIssues"))
              .build();
        }
      }
    }
    return getListIssuesMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerBugTrackerServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerBugTrackerServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerBugTrackerServiceStub>() {
        @java.lang.Override
        public ServerBugTrackerServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerBugTrackerServiceStub(channel, callOptions);
        }
      };
    return ServerBugTrackerServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerBugTrackerServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerBugTrackerServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerBugTrackerServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerBugTrackerServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerBugTrackerServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerBugTrackerServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerBugTrackerServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerBugTrackerServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerBugTrackerServiceBlockingStub>() {
        @java.lang.Override
        public ServerBugTrackerServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerBugTrackerServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerBugTrackerServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerBugTrackerServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerBugTrackerServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerBugTrackerServiceFutureStub>() {
        @java.lang.Override
        public ServerBugTrackerServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerBugTrackerServiceFutureStub(channel, callOptions);
        }
      };
    return ServerBugTrackerServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerBugTrackerService centralizes issue CRUD across GitHub, GitLab and
   * Jira. The server resolves the project's BUGTRACKER connection (falling
   * back to REPOSITORY) and dispatches to the right vendor client, so Python
   * callers stay provider-agnostic.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void createIssue(com.jervis.contracts.server.CreateIssueRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.IssueResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateIssueMethod(), responseObserver);
    }

    /**
     */
    default void addIssueComment(com.jervis.contracts.server.AddIssueCommentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CommentResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAddIssueCommentMethod(), responseObserver);
    }

    /**
     */
    default void updateIssue(com.jervis.contracts.server.UpdateIssueRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.IssueResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateIssueMethod(), responseObserver);
    }

    /**
     */
    default void listIssues(com.jervis.contracts.server.ListIssuesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListIssuesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListIssuesMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerBugTrackerService.
   * <pre>
   * ServerBugTrackerService centralizes issue CRUD across GitHub, GitLab and
   * Jira. The server resolves the project's BUGTRACKER connection (falling
   * back to REPOSITORY) and dispatches to the right vendor client, so Python
   * callers stay provider-agnostic.
   * </pre>
   */
  public static abstract class ServerBugTrackerServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerBugTrackerServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerBugTrackerService.
   * <pre>
   * ServerBugTrackerService centralizes issue CRUD across GitHub, GitLab and
   * Jira. The server resolves the project's BUGTRACKER connection (falling
   * back to REPOSITORY) and dispatches to the right vendor client, so Python
   * callers stay provider-agnostic.
   * </pre>
   */
  public static final class ServerBugTrackerServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerBugTrackerServiceStub> {
    private ServerBugTrackerServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerBugTrackerServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerBugTrackerServiceStub(channel, callOptions);
    }

    /**
     */
    public void createIssue(com.jervis.contracts.server.CreateIssueRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.IssueResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateIssueMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void addIssueComment(com.jervis.contracts.server.AddIssueCommentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CommentResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAddIssueCommentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void updateIssue(com.jervis.contracts.server.UpdateIssueRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.IssueResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateIssueMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listIssues(com.jervis.contracts.server.ListIssuesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListIssuesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListIssuesMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerBugTrackerService.
   * <pre>
   * ServerBugTrackerService centralizes issue CRUD across GitHub, GitLab and
   * Jira. The server resolves the project's BUGTRACKER connection (falling
   * back to REPOSITORY) and dispatches to the right vendor client, so Python
   * callers stay provider-agnostic.
   * </pre>
   */
  public static final class ServerBugTrackerServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerBugTrackerServiceBlockingV2Stub> {
    private ServerBugTrackerServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerBugTrackerServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerBugTrackerServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.IssueResponse createIssue(com.jervis.contracts.server.CreateIssueRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateIssueMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CommentResponse addIssueComment(com.jervis.contracts.server.AddIssueCommentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAddIssueCommentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.IssueResponse updateIssue(com.jervis.contracts.server.UpdateIssueRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateIssueMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListIssuesResponse listIssues(com.jervis.contracts.server.ListIssuesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListIssuesMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerBugTrackerService.
   * <pre>
   * ServerBugTrackerService centralizes issue CRUD across GitHub, GitLab and
   * Jira. The server resolves the project's BUGTRACKER connection (falling
   * back to REPOSITORY) and dispatches to the right vendor client, so Python
   * callers stay provider-agnostic.
   * </pre>
   */
  public static final class ServerBugTrackerServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerBugTrackerServiceBlockingStub> {
    private ServerBugTrackerServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerBugTrackerServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerBugTrackerServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.IssueResponse createIssue(com.jervis.contracts.server.CreateIssueRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateIssueMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CommentResponse addIssueComment(com.jervis.contracts.server.AddIssueCommentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAddIssueCommentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.IssueResponse updateIssue(com.jervis.contracts.server.UpdateIssueRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateIssueMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListIssuesResponse listIssues(com.jervis.contracts.server.ListIssuesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListIssuesMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerBugTrackerService.
   * <pre>
   * ServerBugTrackerService centralizes issue CRUD across GitHub, GitLab and
   * Jira. The server resolves the project's BUGTRACKER connection (falling
   * back to REPOSITORY) and dispatches to the right vendor client, so Python
   * callers stay provider-agnostic.
   * </pre>
   */
  public static final class ServerBugTrackerServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerBugTrackerServiceFutureStub> {
    private ServerBugTrackerServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerBugTrackerServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerBugTrackerServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.IssueResponse> createIssue(
        com.jervis.contracts.server.CreateIssueRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateIssueMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CommentResponse> addIssueComment(
        com.jervis.contracts.server.AddIssueCommentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAddIssueCommentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.IssueResponse> updateIssue(
        com.jervis.contracts.server.UpdateIssueRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateIssueMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListIssuesResponse> listIssues(
        com.jervis.contracts.server.ListIssuesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListIssuesMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CREATE_ISSUE = 0;
  private static final int METHODID_ADD_ISSUE_COMMENT = 1;
  private static final int METHODID_UPDATE_ISSUE = 2;
  private static final int METHODID_LIST_ISSUES = 3;

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
        case METHODID_CREATE_ISSUE:
          serviceImpl.createIssue((com.jervis.contracts.server.CreateIssueRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.IssueResponse>) responseObserver);
          break;
        case METHODID_ADD_ISSUE_COMMENT:
          serviceImpl.addIssueComment((com.jervis.contracts.server.AddIssueCommentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CommentResponse>) responseObserver);
          break;
        case METHODID_UPDATE_ISSUE:
          serviceImpl.updateIssue((com.jervis.contracts.server.UpdateIssueRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.IssueResponse>) responseObserver);
          break;
        case METHODID_LIST_ISSUES:
          serviceImpl.listIssues((com.jervis.contracts.server.ListIssuesRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListIssuesResponse>) responseObserver);
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
          getCreateIssueMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateIssueRequest,
              com.jervis.contracts.server.IssueResponse>(
                service, METHODID_CREATE_ISSUE)))
        .addMethod(
          getAddIssueCommentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.AddIssueCommentRequest,
              com.jervis.contracts.server.CommentResponse>(
                service, METHODID_ADD_ISSUE_COMMENT)))
        .addMethod(
          getUpdateIssueMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.UpdateIssueRequest,
              com.jervis.contracts.server.IssueResponse>(
                service, METHODID_UPDATE_ISSUE)))
        .addMethod(
          getListIssuesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListIssuesRequest,
              com.jervis.contracts.server.ListIssuesResponse>(
                service, METHODID_LIST_ISSUES)))
        .build();
  }

  private static abstract class ServerBugTrackerServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerBugTrackerServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerBugTrackerProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerBugTrackerService");
    }
  }

  private static final class ServerBugTrackerServiceFileDescriptorSupplier
      extends ServerBugTrackerServiceBaseDescriptorSupplier {
    ServerBugTrackerServiceFileDescriptorSupplier() {}
  }

  private static final class ServerBugTrackerServiceMethodDescriptorSupplier
      extends ServerBugTrackerServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerBugTrackerServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerBugTrackerServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerBugTrackerServiceFileDescriptorSupplier())
              .addMethod(getCreateIssueMethod())
              .addMethod(getAddIssueCommentMethod())
              .addMethod(getUpdateIssueMethod())
              .addMethod(getListIssuesMethod())
              .build();
        }
      }
    }
    return result;
  }
}
