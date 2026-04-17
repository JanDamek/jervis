package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerMeetingsService — read-side surface for meeting data consumed by
 * the orchestrator's chat tools (transcript retrieval, unclassified
 * listing). Classify / attend / recording / video / alone lifecycle
 * RPCs land in their own sibling services as those routing files
 * migrate.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerMeetingsServiceGrpc {

  private ServerMeetingsServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerMeetingsService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetTranscriptRequest,
      com.jervis.contracts.server.GetTranscriptResponse> getGetTranscriptMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTranscript",
      requestType = com.jervis.contracts.server.GetTranscriptRequest.class,
      responseType = com.jervis.contracts.server.GetTranscriptResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetTranscriptRequest,
      com.jervis.contracts.server.GetTranscriptResponse> getGetTranscriptMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetTranscriptRequest, com.jervis.contracts.server.GetTranscriptResponse> getGetTranscriptMethod;
    if ((getGetTranscriptMethod = ServerMeetingsServiceGrpc.getGetTranscriptMethod) == null) {
      synchronized (ServerMeetingsServiceGrpc.class) {
        if ((getGetTranscriptMethod = ServerMeetingsServiceGrpc.getGetTranscriptMethod) == null) {
          ServerMeetingsServiceGrpc.getGetTranscriptMethod = getGetTranscriptMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetTranscriptRequest, com.jervis.contracts.server.GetTranscriptResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTranscript"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetTranscriptRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetTranscriptResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingsServiceMethodDescriptorSupplier("GetTranscript"))
              .build();
        }
      }
    }
    return getGetTranscriptMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListMeetingsRequest,
      com.jervis.contracts.server.ListMeetingsResponse> getListMeetingsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListMeetings",
      requestType = com.jervis.contracts.server.ListMeetingsRequest.class,
      responseType = com.jervis.contracts.server.ListMeetingsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListMeetingsRequest,
      com.jervis.contracts.server.ListMeetingsResponse> getListMeetingsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListMeetingsRequest, com.jervis.contracts.server.ListMeetingsResponse> getListMeetingsMethod;
    if ((getListMeetingsMethod = ServerMeetingsServiceGrpc.getListMeetingsMethod) == null) {
      synchronized (ServerMeetingsServiceGrpc.class) {
        if ((getListMeetingsMethod = ServerMeetingsServiceGrpc.getListMeetingsMethod) == null) {
          ServerMeetingsServiceGrpc.getListMeetingsMethod = getListMeetingsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListMeetingsRequest, com.jervis.contracts.server.ListMeetingsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListMeetings"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListMeetingsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListMeetingsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingsServiceMethodDescriptorSupplier("ListMeetings"))
              .build();
        }
      }
    }
    return getListMeetingsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListUnclassifiedRequest,
      com.jervis.contracts.server.ListUnclassifiedResponse> getListUnclassifiedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListUnclassified",
      requestType = com.jervis.contracts.server.ListUnclassifiedRequest.class,
      responseType = com.jervis.contracts.server.ListUnclassifiedResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListUnclassifiedRequest,
      com.jervis.contracts.server.ListUnclassifiedResponse> getListUnclassifiedMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListUnclassifiedRequest, com.jervis.contracts.server.ListUnclassifiedResponse> getListUnclassifiedMethod;
    if ((getListUnclassifiedMethod = ServerMeetingsServiceGrpc.getListUnclassifiedMethod) == null) {
      synchronized (ServerMeetingsServiceGrpc.class) {
        if ((getListUnclassifiedMethod = ServerMeetingsServiceGrpc.getListUnclassifiedMethod) == null) {
          ServerMeetingsServiceGrpc.getListUnclassifiedMethod = getListUnclassifiedMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListUnclassifiedRequest, com.jervis.contracts.server.ListUnclassifiedResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListUnclassified"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListUnclassifiedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListUnclassifiedResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingsServiceMethodDescriptorSupplier("ListUnclassified"))
              .build();
        }
      }
    }
    return getListUnclassifiedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ClassifyMeetingRequest,
      com.jervis.contracts.server.ClassifyMeetingResponse> getClassifyMeetingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ClassifyMeeting",
      requestType = com.jervis.contracts.server.ClassifyMeetingRequest.class,
      responseType = com.jervis.contracts.server.ClassifyMeetingResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ClassifyMeetingRequest,
      com.jervis.contracts.server.ClassifyMeetingResponse> getClassifyMeetingMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ClassifyMeetingRequest, com.jervis.contracts.server.ClassifyMeetingResponse> getClassifyMeetingMethod;
    if ((getClassifyMeetingMethod = ServerMeetingsServiceGrpc.getClassifyMeetingMethod) == null) {
      synchronized (ServerMeetingsServiceGrpc.class) {
        if ((getClassifyMeetingMethod = ServerMeetingsServiceGrpc.getClassifyMeetingMethod) == null) {
          ServerMeetingsServiceGrpc.getClassifyMeetingMethod = getClassifyMeetingMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ClassifyMeetingRequest, com.jervis.contracts.server.ClassifyMeetingResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ClassifyMeeting"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ClassifyMeetingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ClassifyMeetingResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingsServiceMethodDescriptorSupplier("ClassifyMeeting"))
              .build();
        }
      }
    }
    return getClassifyMeetingMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerMeetingsServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingsServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingsServiceStub>() {
        @java.lang.Override
        public ServerMeetingsServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingsServiceStub(channel, callOptions);
        }
      };
    return ServerMeetingsServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerMeetingsServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingsServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingsServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerMeetingsServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingsServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerMeetingsServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerMeetingsServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingsServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingsServiceBlockingStub>() {
        @java.lang.Override
        public ServerMeetingsServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingsServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerMeetingsServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerMeetingsServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingsServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingsServiceFutureStub>() {
        @java.lang.Override
        public ServerMeetingsServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingsServiceFutureStub(channel, callOptions);
        }
      };
    return ServerMeetingsServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerMeetingsService — read-side surface for meeting data consumed by
   * the orchestrator's chat tools (transcript retrieval, unclassified
   * listing). Classify / attend / recording / video / alone lifecycle
   * RPCs land in their own sibling services as those routing files
   * migrate.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Transcript (corrected preferred, raw fallback, segments fallback).
     * </pre>
     */
    default void getTranscript(com.jervis.contracts.server.GetTranscriptRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetTranscriptResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTranscriptMethod(), responseObserver);
    }

    /**
     * <pre>
     * List meetings with optional filters. `state` is the string form of
     * MeetingStateEnum; empty means no filter.
     * </pre>
     */
    default void listMeetings(com.jervis.contracts.server.ListMeetingsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListMeetingsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListMeetingsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Shortcut used by the orchestrator's system-prompt builder.
     * </pre>
     */
    default void listUnclassified(com.jervis.contracts.server.ListUnclassifiedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListUnclassifiedResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListUnclassifiedMethod(), responseObserver);
    }

    /**
     * <pre>
     * Re-classify an already-ingested meeting — orchestrator tool entry.
     * </pre>
     */
    default void classifyMeeting(com.jervis.contracts.server.ClassifyMeetingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ClassifyMeetingResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getClassifyMeetingMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerMeetingsService.
   * <pre>
   * ServerMeetingsService — read-side surface for meeting data consumed by
   * the orchestrator's chat tools (transcript retrieval, unclassified
   * listing). Classify / attend / recording / video / alone lifecycle
   * RPCs land in their own sibling services as those routing files
   * migrate.
   * </pre>
   */
  public static abstract class ServerMeetingsServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerMeetingsServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerMeetingsService.
   * <pre>
   * ServerMeetingsService — read-side surface for meeting data consumed by
   * the orchestrator's chat tools (transcript retrieval, unclassified
   * listing). Classify / attend / recording / video / alone lifecycle
   * RPCs land in their own sibling services as those routing files
   * migrate.
   * </pre>
   */
  public static final class ServerMeetingsServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerMeetingsServiceStub> {
    private ServerMeetingsServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingsServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingsServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Transcript (corrected preferred, raw fallback, segments fallback).
     * </pre>
     */
    public void getTranscript(com.jervis.contracts.server.GetTranscriptRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetTranscriptResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTranscriptMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * List meetings with optional filters. `state` is the string form of
     * MeetingStateEnum; empty means no filter.
     * </pre>
     */
    public void listMeetings(com.jervis.contracts.server.ListMeetingsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListMeetingsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListMeetingsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Shortcut used by the orchestrator's system-prompt builder.
     * </pre>
     */
    public void listUnclassified(com.jervis.contracts.server.ListUnclassifiedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListUnclassifiedResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListUnclassifiedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Re-classify an already-ingested meeting — orchestrator tool entry.
     * </pre>
     */
    public void classifyMeeting(com.jervis.contracts.server.ClassifyMeetingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ClassifyMeetingResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getClassifyMeetingMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerMeetingsService.
   * <pre>
   * ServerMeetingsService — read-side surface for meeting data consumed by
   * the orchestrator's chat tools (transcript retrieval, unclassified
   * listing). Classify / attend / recording / video / alone lifecycle
   * RPCs land in their own sibling services as those routing files
   * migrate.
   * </pre>
   */
  public static final class ServerMeetingsServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerMeetingsServiceBlockingV2Stub> {
    private ServerMeetingsServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingsServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingsServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Transcript (corrected preferred, raw fallback, segments fallback).
     * </pre>
     */
    public com.jervis.contracts.server.GetTranscriptResponse getTranscript(com.jervis.contracts.server.GetTranscriptRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetTranscriptMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * List meetings with optional filters. `state` is the string form of
     * MeetingStateEnum; empty means no filter.
     * </pre>
     */
    public com.jervis.contracts.server.ListMeetingsResponse listMeetings(com.jervis.contracts.server.ListMeetingsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListMeetingsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Shortcut used by the orchestrator's system-prompt builder.
     * </pre>
     */
    public com.jervis.contracts.server.ListUnclassifiedResponse listUnclassified(com.jervis.contracts.server.ListUnclassifiedRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListUnclassifiedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Re-classify an already-ingested meeting — orchestrator tool entry.
     * </pre>
     */
    public com.jervis.contracts.server.ClassifyMeetingResponse classifyMeeting(com.jervis.contracts.server.ClassifyMeetingRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getClassifyMeetingMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerMeetingsService.
   * <pre>
   * ServerMeetingsService — read-side surface for meeting data consumed by
   * the orchestrator's chat tools (transcript retrieval, unclassified
   * listing). Classify / attend / recording / video / alone lifecycle
   * RPCs land in their own sibling services as those routing files
   * migrate.
   * </pre>
   */
  public static final class ServerMeetingsServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerMeetingsServiceBlockingStub> {
    private ServerMeetingsServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingsServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingsServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Transcript (corrected preferred, raw fallback, segments fallback).
     * </pre>
     */
    public com.jervis.contracts.server.GetTranscriptResponse getTranscript(com.jervis.contracts.server.GetTranscriptRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTranscriptMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * List meetings with optional filters. `state` is the string form of
     * MeetingStateEnum; empty means no filter.
     * </pre>
     */
    public com.jervis.contracts.server.ListMeetingsResponse listMeetings(com.jervis.contracts.server.ListMeetingsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListMeetingsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Shortcut used by the orchestrator's system-prompt builder.
     * </pre>
     */
    public com.jervis.contracts.server.ListUnclassifiedResponse listUnclassified(com.jervis.contracts.server.ListUnclassifiedRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListUnclassifiedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Re-classify an already-ingested meeting — orchestrator tool entry.
     * </pre>
     */
    public com.jervis.contracts.server.ClassifyMeetingResponse classifyMeeting(com.jervis.contracts.server.ClassifyMeetingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getClassifyMeetingMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerMeetingsService.
   * <pre>
   * ServerMeetingsService — read-side surface for meeting data consumed by
   * the orchestrator's chat tools (transcript retrieval, unclassified
   * listing). Classify / attend / recording / video / alone lifecycle
   * RPCs land in their own sibling services as those routing files
   * migrate.
   * </pre>
   */
  public static final class ServerMeetingsServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerMeetingsServiceFutureStub> {
    private ServerMeetingsServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingsServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingsServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Transcript (corrected preferred, raw fallback, segments fallback).
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetTranscriptResponse> getTranscript(
        com.jervis.contracts.server.GetTranscriptRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTranscriptMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * List meetings with optional filters. `state` is the string form of
     * MeetingStateEnum; empty means no filter.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListMeetingsResponse> listMeetings(
        com.jervis.contracts.server.ListMeetingsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListMeetingsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Shortcut used by the orchestrator's system-prompt builder.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListUnclassifiedResponse> listUnclassified(
        com.jervis.contracts.server.ListUnclassifiedRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListUnclassifiedMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Re-classify an already-ingested meeting — orchestrator tool entry.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ClassifyMeetingResponse> classifyMeeting(
        com.jervis.contracts.server.ClassifyMeetingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getClassifyMeetingMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_TRANSCRIPT = 0;
  private static final int METHODID_LIST_MEETINGS = 1;
  private static final int METHODID_LIST_UNCLASSIFIED = 2;
  private static final int METHODID_CLASSIFY_MEETING = 3;

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
        case METHODID_GET_TRANSCRIPT:
          serviceImpl.getTranscript((com.jervis.contracts.server.GetTranscriptRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetTranscriptResponse>) responseObserver);
          break;
        case METHODID_LIST_MEETINGS:
          serviceImpl.listMeetings((com.jervis.contracts.server.ListMeetingsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListMeetingsResponse>) responseObserver);
          break;
        case METHODID_LIST_UNCLASSIFIED:
          serviceImpl.listUnclassified((com.jervis.contracts.server.ListUnclassifiedRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListUnclassifiedResponse>) responseObserver);
          break;
        case METHODID_CLASSIFY_MEETING:
          serviceImpl.classifyMeeting((com.jervis.contracts.server.ClassifyMeetingRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ClassifyMeetingResponse>) responseObserver);
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
          getGetTranscriptMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetTranscriptRequest,
              com.jervis.contracts.server.GetTranscriptResponse>(
                service, METHODID_GET_TRANSCRIPT)))
        .addMethod(
          getListMeetingsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListMeetingsRequest,
              com.jervis.contracts.server.ListMeetingsResponse>(
                service, METHODID_LIST_MEETINGS)))
        .addMethod(
          getListUnclassifiedMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListUnclassifiedRequest,
              com.jervis.contracts.server.ListUnclassifiedResponse>(
                service, METHODID_LIST_UNCLASSIFIED)))
        .addMethod(
          getClassifyMeetingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ClassifyMeetingRequest,
              com.jervis.contracts.server.ClassifyMeetingResponse>(
                service, METHODID_CLASSIFY_MEETING)))
        .build();
  }

  private static abstract class ServerMeetingsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerMeetingsServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerMeetingsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerMeetingsService");
    }
  }

  private static final class ServerMeetingsServiceFileDescriptorSupplier
      extends ServerMeetingsServiceBaseDescriptorSupplier {
    ServerMeetingsServiceFileDescriptorSupplier() {}
  }

  private static final class ServerMeetingsServiceMethodDescriptorSupplier
      extends ServerMeetingsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerMeetingsServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerMeetingsServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerMeetingsServiceFileDescriptorSupplier())
              .addMethod(getGetTranscriptMethod())
              .addMethod(getListMeetingsMethod())
              .addMethod(getListUnclassifiedMethod())
              .addMethod(getClassifyMeetingMethod())
              .build();
        }
      }
    }
    return result;
  }
}
