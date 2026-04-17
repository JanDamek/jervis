package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerMergeRequestService owns MR/PR lifecycle on the task surface:
 * created by the coding pipeline after a successful job, diffed by the
 * code review pipeline, commented on (summary + inline file:line) by
 * reviewers. Vendor dispatch (GitHub vs GitLab) lives on the Kotlin side
 * — Python speaks to a single stub.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerMergeRequestServiceGrpc {

  private ServerMergeRequestServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerMergeRequestService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ResolveReviewLanguageRequest,
      com.jervis.contracts.server.ResolveReviewLanguageResponse> getResolveReviewLanguageMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ResolveReviewLanguage",
      requestType = com.jervis.contracts.server.ResolveReviewLanguageRequest.class,
      responseType = com.jervis.contracts.server.ResolveReviewLanguageResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ResolveReviewLanguageRequest,
      com.jervis.contracts.server.ResolveReviewLanguageResponse> getResolveReviewLanguageMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ResolveReviewLanguageRequest, com.jervis.contracts.server.ResolveReviewLanguageResponse> getResolveReviewLanguageMethod;
    if ((getResolveReviewLanguageMethod = ServerMergeRequestServiceGrpc.getResolveReviewLanguageMethod) == null) {
      synchronized (ServerMergeRequestServiceGrpc.class) {
        if ((getResolveReviewLanguageMethod = ServerMergeRequestServiceGrpc.getResolveReviewLanguageMethod) == null) {
          ServerMergeRequestServiceGrpc.getResolveReviewLanguageMethod = getResolveReviewLanguageMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ResolveReviewLanguageRequest, com.jervis.contracts.server.ResolveReviewLanguageResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ResolveReviewLanguage"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ResolveReviewLanguageRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ResolveReviewLanguageResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMergeRequestServiceMethodDescriptorSupplier("ResolveReviewLanguage"))
              .build();
        }
      }
    }
    return getResolveReviewLanguageMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateMergeRequestRequest,
      com.jervis.contracts.server.CreateMergeRequestResponse> getCreateMergeRequestMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateMergeRequest",
      requestType = com.jervis.contracts.server.CreateMergeRequestRequest.class,
      responseType = com.jervis.contracts.server.CreateMergeRequestResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateMergeRequestRequest,
      com.jervis.contracts.server.CreateMergeRequestResponse> getCreateMergeRequestMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateMergeRequestRequest, com.jervis.contracts.server.CreateMergeRequestResponse> getCreateMergeRequestMethod;
    if ((getCreateMergeRequestMethod = ServerMergeRequestServiceGrpc.getCreateMergeRequestMethod) == null) {
      synchronized (ServerMergeRequestServiceGrpc.class) {
        if ((getCreateMergeRequestMethod = ServerMergeRequestServiceGrpc.getCreateMergeRequestMethod) == null) {
          ServerMergeRequestServiceGrpc.getCreateMergeRequestMethod = getCreateMergeRequestMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateMergeRequestRequest, com.jervis.contracts.server.CreateMergeRequestResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateMergeRequest"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateMergeRequestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateMergeRequestResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMergeRequestServiceMethodDescriptorSupplier("CreateMergeRequest"))
              .build();
        }
      }
    }
    return getCreateMergeRequestMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetMergeRequestDiffRequest,
      com.jervis.contracts.server.GetMergeRequestDiffResponse> getGetMergeRequestDiffMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetMergeRequestDiff",
      requestType = com.jervis.contracts.server.GetMergeRequestDiffRequest.class,
      responseType = com.jervis.contracts.server.GetMergeRequestDiffResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetMergeRequestDiffRequest,
      com.jervis.contracts.server.GetMergeRequestDiffResponse> getGetMergeRequestDiffMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetMergeRequestDiffRequest, com.jervis.contracts.server.GetMergeRequestDiffResponse> getGetMergeRequestDiffMethod;
    if ((getGetMergeRequestDiffMethod = ServerMergeRequestServiceGrpc.getGetMergeRequestDiffMethod) == null) {
      synchronized (ServerMergeRequestServiceGrpc.class) {
        if ((getGetMergeRequestDiffMethod = ServerMergeRequestServiceGrpc.getGetMergeRequestDiffMethod) == null) {
          ServerMergeRequestServiceGrpc.getGetMergeRequestDiffMethod = getGetMergeRequestDiffMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetMergeRequestDiffRequest, com.jervis.contracts.server.GetMergeRequestDiffResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetMergeRequestDiff"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetMergeRequestDiffRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetMergeRequestDiffResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMergeRequestServiceMethodDescriptorSupplier("GetMergeRequestDiff"))
              .build();
        }
      }
    }
    return getGetMergeRequestDiffMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.PostMrCommentRequest,
      com.jervis.contracts.server.PostMrCommentResponse> getPostMrCommentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PostMrComment",
      requestType = com.jervis.contracts.server.PostMrCommentRequest.class,
      responseType = com.jervis.contracts.server.PostMrCommentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.PostMrCommentRequest,
      com.jervis.contracts.server.PostMrCommentResponse> getPostMrCommentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.PostMrCommentRequest, com.jervis.contracts.server.PostMrCommentResponse> getPostMrCommentMethod;
    if ((getPostMrCommentMethod = ServerMergeRequestServiceGrpc.getPostMrCommentMethod) == null) {
      synchronized (ServerMergeRequestServiceGrpc.class) {
        if ((getPostMrCommentMethod = ServerMergeRequestServiceGrpc.getPostMrCommentMethod) == null) {
          ServerMergeRequestServiceGrpc.getPostMrCommentMethod = getPostMrCommentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.PostMrCommentRequest, com.jervis.contracts.server.PostMrCommentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PostMrComment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PostMrCommentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PostMrCommentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMergeRequestServiceMethodDescriptorSupplier("PostMrComment"))
              .build();
        }
      }
    }
    return getPostMrCommentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.PostMrInlineCommentsRequest,
      com.jervis.contracts.server.PostMrInlineCommentsResponse> getPostMrInlineCommentsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PostMrInlineComments",
      requestType = com.jervis.contracts.server.PostMrInlineCommentsRequest.class,
      responseType = com.jervis.contracts.server.PostMrInlineCommentsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.PostMrInlineCommentsRequest,
      com.jervis.contracts.server.PostMrInlineCommentsResponse> getPostMrInlineCommentsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.PostMrInlineCommentsRequest, com.jervis.contracts.server.PostMrInlineCommentsResponse> getPostMrInlineCommentsMethod;
    if ((getPostMrInlineCommentsMethod = ServerMergeRequestServiceGrpc.getPostMrInlineCommentsMethod) == null) {
      synchronized (ServerMergeRequestServiceGrpc.class) {
        if ((getPostMrInlineCommentsMethod = ServerMergeRequestServiceGrpc.getPostMrInlineCommentsMethod) == null) {
          ServerMergeRequestServiceGrpc.getPostMrInlineCommentsMethod = getPostMrInlineCommentsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.PostMrInlineCommentsRequest, com.jervis.contracts.server.PostMrInlineCommentsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PostMrInlineComments"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PostMrInlineCommentsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PostMrInlineCommentsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMergeRequestServiceMethodDescriptorSupplier("PostMrInlineComments"))
              .build();
        }
      }
    }
    return getPostMrInlineCommentsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerMergeRequestServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMergeRequestServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMergeRequestServiceStub>() {
        @java.lang.Override
        public ServerMergeRequestServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMergeRequestServiceStub(channel, callOptions);
        }
      };
    return ServerMergeRequestServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerMergeRequestServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMergeRequestServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMergeRequestServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerMergeRequestServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMergeRequestServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerMergeRequestServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerMergeRequestServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMergeRequestServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMergeRequestServiceBlockingStub>() {
        @java.lang.Override
        public ServerMergeRequestServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMergeRequestServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerMergeRequestServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerMergeRequestServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMergeRequestServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMergeRequestServiceFutureStub>() {
        @java.lang.Override
        public ServerMergeRequestServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMergeRequestServiceFutureStub(channel, callOptions);
        }
      };
    return ServerMergeRequestServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerMergeRequestService owns MR/PR lifecycle on the task surface:
   * created by the coding pipeline after a successful job, diffed by the
   * code review pipeline, commented on (summary + inline file:line) by
   * reviewers. Vendor dispatch (GitHub vs GitLab) lives on the Kotlin side
   * — Python speaks to a single stub.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void resolveReviewLanguage(com.jervis.contracts.server.ResolveReviewLanguageRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ResolveReviewLanguageResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getResolveReviewLanguageMethod(), responseObserver);
    }

    /**
     */
    default void createMergeRequest(com.jervis.contracts.server.CreateMergeRequestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateMergeRequestResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateMergeRequestMethod(), responseObserver);
    }

    /**
     */
    default void getMergeRequestDiff(com.jervis.contracts.server.GetMergeRequestDiffRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetMergeRequestDiffResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMergeRequestDiffMethod(), responseObserver);
    }

    /**
     */
    default void postMrComment(com.jervis.contracts.server.PostMrCommentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PostMrCommentResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPostMrCommentMethod(), responseObserver);
    }

    /**
     */
    default void postMrInlineComments(com.jervis.contracts.server.PostMrInlineCommentsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PostMrInlineCommentsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPostMrInlineCommentsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerMergeRequestService.
   * <pre>
   * ServerMergeRequestService owns MR/PR lifecycle on the task surface:
   * created by the coding pipeline after a successful job, diffed by the
   * code review pipeline, commented on (summary + inline file:line) by
   * reviewers. Vendor dispatch (GitHub vs GitLab) lives on the Kotlin side
   * — Python speaks to a single stub.
   * </pre>
   */
  public static abstract class ServerMergeRequestServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerMergeRequestServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerMergeRequestService.
   * <pre>
   * ServerMergeRequestService owns MR/PR lifecycle on the task surface:
   * created by the coding pipeline after a successful job, diffed by the
   * code review pipeline, commented on (summary + inline file:line) by
   * reviewers. Vendor dispatch (GitHub vs GitLab) lives on the Kotlin side
   * — Python speaks to a single stub.
   * </pre>
   */
  public static final class ServerMergeRequestServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerMergeRequestServiceStub> {
    private ServerMergeRequestServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMergeRequestServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMergeRequestServiceStub(channel, callOptions);
    }

    /**
     */
    public void resolveReviewLanguage(com.jervis.contracts.server.ResolveReviewLanguageRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ResolveReviewLanguageResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getResolveReviewLanguageMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createMergeRequest(com.jervis.contracts.server.CreateMergeRequestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateMergeRequestResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateMergeRequestMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getMergeRequestDiff(com.jervis.contracts.server.GetMergeRequestDiffRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetMergeRequestDiffResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetMergeRequestDiffMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void postMrComment(com.jervis.contracts.server.PostMrCommentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PostMrCommentResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPostMrCommentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void postMrInlineComments(com.jervis.contracts.server.PostMrInlineCommentsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PostMrInlineCommentsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPostMrInlineCommentsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerMergeRequestService.
   * <pre>
   * ServerMergeRequestService owns MR/PR lifecycle on the task surface:
   * created by the coding pipeline after a successful job, diffed by the
   * code review pipeline, commented on (summary + inline file:line) by
   * reviewers. Vendor dispatch (GitHub vs GitLab) lives on the Kotlin side
   * — Python speaks to a single stub.
   * </pre>
   */
  public static final class ServerMergeRequestServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerMergeRequestServiceBlockingV2Stub> {
    private ServerMergeRequestServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMergeRequestServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMergeRequestServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ResolveReviewLanguageResponse resolveReviewLanguage(com.jervis.contracts.server.ResolveReviewLanguageRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getResolveReviewLanguageMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateMergeRequestResponse createMergeRequest(com.jervis.contracts.server.CreateMergeRequestRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateMergeRequestMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetMergeRequestDiffResponse getMergeRequestDiff(com.jervis.contracts.server.GetMergeRequestDiffRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetMergeRequestDiffMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PostMrCommentResponse postMrComment(com.jervis.contracts.server.PostMrCommentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPostMrCommentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PostMrInlineCommentsResponse postMrInlineComments(com.jervis.contracts.server.PostMrInlineCommentsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPostMrInlineCommentsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerMergeRequestService.
   * <pre>
   * ServerMergeRequestService owns MR/PR lifecycle on the task surface:
   * created by the coding pipeline after a successful job, diffed by the
   * code review pipeline, commented on (summary + inline file:line) by
   * reviewers. Vendor dispatch (GitHub vs GitLab) lives on the Kotlin side
   * — Python speaks to a single stub.
   * </pre>
   */
  public static final class ServerMergeRequestServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerMergeRequestServiceBlockingStub> {
    private ServerMergeRequestServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMergeRequestServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMergeRequestServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ResolveReviewLanguageResponse resolveReviewLanguage(com.jervis.contracts.server.ResolveReviewLanguageRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getResolveReviewLanguageMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateMergeRequestResponse createMergeRequest(com.jervis.contracts.server.CreateMergeRequestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateMergeRequestMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetMergeRequestDiffResponse getMergeRequestDiff(com.jervis.contracts.server.GetMergeRequestDiffRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetMergeRequestDiffMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PostMrCommentResponse postMrComment(com.jervis.contracts.server.PostMrCommentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPostMrCommentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PostMrInlineCommentsResponse postMrInlineComments(com.jervis.contracts.server.PostMrInlineCommentsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPostMrInlineCommentsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerMergeRequestService.
   * <pre>
   * ServerMergeRequestService owns MR/PR lifecycle on the task surface:
   * created by the coding pipeline after a successful job, diffed by the
   * code review pipeline, commented on (summary + inline file:line) by
   * reviewers. Vendor dispatch (GitHub vs GitLab) lives on the Kotlin side
   * — Python speaks to a single stub.
   * </pre>
   */
  public static final class ServerMergeRequestServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerMergeRequestServiceFutureStub> {
    private ServerMergeRequestServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMergeRequestServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMergeRequestServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ResolveReviewLanguageResponse> resolveReviewLanguage(
        com.jervis.contracts.server.ResolveReviewLanguageRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getResolveReviewLanguageMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CreateMergeRequestResponse> createMergeRequest(
        com.jervis.contracts.server.CreateMergeRequestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateMergeRequestMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetMergeRequestDiffResponse> getMergeRequestDiff(
        com.jervis.contracts.server.GetMergeRequestDiffRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetMergeRequestDiffMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.PostMrCommentResponse> postMrComment(
        com.jervis.contracts.server.PostMrCommentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPostMrCommentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.PostMrInlineCommentsResponse> postMrInlineComments(
        com.jervis.contracts.server.PostMrInlineCommentsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPostMrInlineCommentsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_RESOLVE_REVIEW_LANGUAGE = 0;
  private static final int METHODID_CREATE_MERGE_REQUEST = 1;
  private static final int METHODID_GET_MERGE_REQUEST_DIFF = 2;
  private static final int METHODID_POST_MR_COMMENT = 3;
  private static final int METHODID_POST_MR_INLINE_COMMENTS = 4;

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
        case METHODID_RESOLVE_REVIEW_LANGUAGE:
          serviceImpl.resolveReviewLanguage((com.jervis.contracts.server.ResolveReviewLanguageRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ResolveReviewLanguageResponse>) responseObserver);
          break;
        case METHODID_CREATE_MERGE_REQUEST:
          serviceImpl.createMergeRequest((com.jervis.contracts.server.CreateMergeRequestRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateMergeRequestResponse>) responseObserver);
          break;
        case METHODID_GET_MERGE_REQUEST_DIFF:
          serviceImpl.getMergeRequestDiff((com.jervis.contracts.server.GetMergeRequestDiffRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetMergeRequestDiffResponse>) responseObserver);
          break;
        case METHODID_POST_MR_COMMENT:
          serviceImpl.postMrComment((com.jervis.contracts.server.PostMrCommentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.PostMrCommentResponse>) responseObserver);
          break;
        case METHODID_POST_MR_INLINE_COMMENTS:
          serviceImpl.postMrInlineComments((com.jervis.contracts.server.PostMrInlineCommentsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.PostMrInlineCommentsResponse>) responseObserver);
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
          getResolveReviewLanguageMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ResolveReviewLanguageRequest,
              com.jervis.contracts.server.ResolveReviewLanguageResponse>(
                service, METHODID_RESOLVE_REVIEW_LANGUAGE)))
        .addMethod(
          getCreateMergeRequestMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateMergeRequestRequest,
              com.jervis.contracts.server.CreateMergeRequestResponse>(
                service, METHODID_CREATE_MERGE_REQUEST)))
        .addMethod(
          getGetMergeRequestDiffMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetMergeRequestDiffRequest,
              com.jervis.contracts.server.GetMergeRequestDiffResponse>(
                service, METHODID_GET_MERGE_REQUEST_DIFF)))
        .addMethod(
          getPostMrCommentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.PostMrCommentRequest,
              com.jervis.contracts.server.PostMrCommentResponse>(
                service, METHODID_POST_MR_COMMENT)))
        .addMethod(
          getPostMrInlineCommentsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.PostMrInlineCommentsRequest,
              com.jervis.contracts.server.PostMrInlineCommentsResponse>(
                service, METHODID_POST_MR_INLINE_COMMENTS)))
        .build();
  }

  private static abstract class ServerMergeRequestServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerMergeRequestServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerMergeRequestProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerMergeRequestService");
    }
  }

  private static final class ServerMergeRequestServiceFileDescriptorSupplier
      extends ServerMergeRequestServiceBaseDescriptorSupplier {
    ServerMergeRequestServiceFileDescriptorSupplier() {}
  }

  private static final class ServerMergeRequestServiceMethodDescriptorSupplier
      extends ServerMergeRequestServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerMergeRequestServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerMergeRequestServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerMergeRequestServiceFileDescriptorSupplier())
              .addMethod(getResolveReviewLanguageMethod())
              .addMethod(getCreateMergeRequestMethod())
              .addMethod(getGetMergeRequestDiffMethod())
              .addMethod(getPostMrCommentMethod())
              .addMethod(getPostMrInlineCommentsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
