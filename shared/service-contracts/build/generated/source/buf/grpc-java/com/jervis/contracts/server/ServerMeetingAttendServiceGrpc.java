package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerMeetingAttendService — approval flow for calendar-detected
 * meetings ("should Jervis attend?") + presence callbacks from the
 * browser pod. Consumed by MCP tools and the o365 browser pool agent.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerMeetingAttendServiceGrpc {

  private ServerMeetingAttendServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerMeetingAttendService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListUpcomingRequest,
      com.jervis.contracts.server.ListUpcomingResponse> getListUpcomingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListUpcoming",
      requestType = com.jervis.contracts.server.ListUpcomingRequest.class,
      responseType = com.jervis.contracts.server.ListUpcomingResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListUpcomingRequest,
      com.jervis.contracts.server.ListUpcomingResponse> getListUpcomingMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListUpcomingRequest, com.jervis.contracts.server.ListUpcomingResponse> getListUpcomingMethod;
    if ((getListUpcomingMethod = ServerMeetingAttendServiceGrpc.getListUpcomingMethod) == null) {
      synchronized (ServerMeetingAttendServiceGrpc.class) {
        if ((getListUpcomingMethod = ServerMeetingAttendServiceGrpc.getListUpcomingMethod) == null) {
          ServerMeetingAttendServiceGrpc.getListUpcomingMethod = getListUpcomingMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListUpcomingRequest, com.jervis.contracts.server.ListUpcomingResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListUpcoming"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListUpcomingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListUpcomingResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingAttendServiceMethodDescriptorSupplier("ListUpcoming"))
              .build();
        }
      }
    }
    return getListUpcomingMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.AttendDecisionRequest,
      com.jervis.contracts.server.AttendDecisionResponse> getApproveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Approve",
      requestType = com.jervis.contracts.server.AttendDecisionRequest.class,
      responseType = com.jervis.contracts.server.AttendDecisionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.AttendDecisionRequest,
      com.jervis.contracts.server.AttendDecisionResponse> getApproveMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.AttendDecisionRequest, com.jervis.contracts.server.AttendDecisionResponse> getApproveMethod;
    if ((getApproveMethod = ServerMeetingAttendServiceGrpc.getApproveMethod) == null) {
      synchronized (ServerMeetingAttendServiceGrpc.class) {
        if ((getApproveMethod = ServerMeetingAttendServiceGrpc.getApproveMethod) == null) {
          ServerMeetingAttendServiceGrpc.getApproveMethod = getApproveMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.AttendDecisionRequest, com.jervis.contracts.server.AttendDecisionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Approve"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AttendDecisionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AttendDecisionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingAttendServiceMethodDescriptorSupplier("Approve"))
              .build();
        }
      }
    }
    return getApproveMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.AttendDecisionRequest,
      com.jervis.contracts.server.AttendDecisionResponse> getDenyMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Deny",
      requestType = com.jervis.contracts.server.AttendDecisionRequest.class,
      responseType = com.jervis.contracts.server.AttendDecisionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.AttendDecisionRequest,
      com.jervis.contracts.server.AttendDecisionResponse> getDenyMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.AttendDecisionRequest, com.jervis.contracts.server.AttendDecisionResponse> getDenyMethod;
    if ((getDenyMethod = ServerMeetingAttendServiceGrpc.getDenyMethod) == null) {
      synchronized (ServerMeetingAttendServiceGrpc.class) {
        if ((getDenyMethod = ServerMeetingAttendServiceGrpc.getDenyMethod) == null) {
          ServerMeetingAttendServiceGrpc.getDenyMethod = getDenyMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.AttendDecisionRequest, com.jervis.contracts.server.AttendDecisionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Deny"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AttendDecisionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AttendDecisionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingAttendServiceMethodDescriptorSupplier("Deny"))
              .build();
        }
      }
    }
    return getDenyMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.PresenceRequest,
      com.jervis.contracts.server.PresenceResponse> getReportPresenceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReportPresence",
      requestType = com.jervis.contracts.server.PresenceRequest.class,
      responseType = com.jervis.contracts.server.PresenceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.PresenceRequest,
      com.jervis.contracts.server.PresenceResponse> getReportPresenceMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.PresenceRequest, com.jervis.contracts.server.PresenceResponse> getReportPresenceMethod;
    if ((getReportPresenceMethod = ServerMeetingAttendServiceGrpc.getReportPresenceMethod) == null) {
      synchronized (ServerMeetingAttendServiceGrpc.class) {
        if ((getReportPresenceMethod = ServerMeetingAttendServiceGrpc.getReportPresenceMethod) == null) {
          ServerMeetingAttendServiceGrpc.getReportPresenceMethod = getReportPresenceMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.PresenceRequest, com.jervis.contracts.server.PresenceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReportPresence"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PresenceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PresenceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingAttendServiceMethodDescriptorSupplier("ReportPresence"))
              .build();
        }
      }
    }
    return getReportPresenceMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerMeetingAttendServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAttendServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAttendServiceStub>() {
        @java.lang.Override
        public ServerMeetingAttendServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingAttendServiceStub(channel, callOptions);
        }
      };
    return ServerMeetingAttendServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerMeetingAttendServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAttendServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAttendServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerMeetingAttendServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingAttendServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerMeetingAttendServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerMeetingAttendServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAttendServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAttendServiceBlockingStub>() {
        @java.lang.Override
        public ServerMeetingAttendServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingAttendServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerMeetingAttendServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerMeetingAttendServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAttendServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAttendServiceFutureStub>() {
        @java.lang.Override
        public ServerMeetingAttendServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingAttendServiceFutureStub(channel, callOptions);
        }
      };
    return ServerMeetingAttendServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerMeetingAttendService — approval flow for calendar-detected
   * meetings ("should Jervis attend?") + presence callbacks from the
   * browser pod. Consumed by MCP tools and the o365 browser pool agent.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * CALENDAR_PROCESSING tasks in the next `hours_ahead` hours.
     * </pre>
     */
    default void listUpcoming(com.jervis.contracts.server.ListUpcomingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListUpcomingResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListUpcomingMethod(), responseObserver);
    }

    /**
     * <pre>
     * Approve / deny a meeting-attend task.
     * </pre>
     */
    default void approve(com.jervis.contracts.server.AttendDecisionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AttendDecisionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getApproveMethod(), responseObserver);
    }

    /**
     */
    default void deny(com.jervis.contracts.server.AttendDecisionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AttendDecisionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDenyMethod(), responseObserver);
    }

    /**
     * <pre>
     * Browser pod → server presence callback. present=true means the user
     * is in any meeting context (stage/prejoin/lobby).
     * </pre>
     */
    default void reportPresence(com.jervis.contracts.server.PresenceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PresenceResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReportPresenceMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerMeetingAttendService.
   * <pre>
   * ServerMeetingAttendService — approval flow for calendar-detected
   * meetings ("should Jervis attend?") + presence callbacks from the
   * browser pod. Consumed by MCP tools and the o365 browser pool agent.
   * </pre>
   */
  public static abstract class ServerMeetingAttendServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerMeetingAttendServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerMeetingAttendService.
   * <pre>
   * ServerMeetingAttendService — approval flow for calendar-detected
   * meetings ("should Jervis attend?") + presence callbacks from the
   * browser pod. Consumed by MCP tools and the o365 browser pool agent.
   * </pre>
   */
  public static final class ServerMeetingAttendServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerMeetingAttendServiceStub> {
    private ServerMeetingAttendServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingAttendServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingAttendServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * CALENDAR_PROCESSING tasks in the next `hours_ahead` hours.
     * </pre>
     */
    public void listUpcoming(com.jervis.contracts.server.ListUpcomingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListUpcomingResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListUpcomingMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Approve / deny a meeting-attend task.
     * </pre>
     */
    public void approve(com.jervis.contracts.server.AttendDecisionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AttendDecisionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getApproveMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void deny(com.jervis.contracts.server.AttendDecisionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AttendDecisionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDenyMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Browser pod → server presence callback. present=true means the user
     * is in any meeting context (stage/prejoin/lobby).
     * </pre>
     */
    public void reportPresence(com.jervis.contracts.server.PresenceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PresenceResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReportPresenceMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerMeetingAttendService.
   * <pre>
   * ServerMeetingAttendService — approval flow for calendar-detected
   * meetings ("should Jervis attend?") + presence callbacks from the
   * browser pod. Consumed by MCP tools and the o365 browser pool agent.
   * </pre>
   */
  public static final class ServerMeetingAttendServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerMeetingAttendServiceBlockingV2Stub> {
    private ServerMeetingAttendServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingAttendServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingAttendServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * CALENDAR_PROCESSING tasks in the next `hours_ahead` hours.
     * </pre>
     */
    public com.jervis.contracts.server.ListUpcomingResponse listUpcoming(com.jervis.contracts.server.ListUpcomingRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListUpcomingMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Approve / deny a meeting-attend task.
     * </pre>
     */
    public com.jervis.contracts.server.AttendDecisionResponse approve(com.jervis.contracts.server.AttendDecisionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getApproveMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AttendDecisionResponse deny(com.jervis.contracts.server.AttendDecisionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDenyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Browser pod → server presence callback. present=true means the user
     * is in any meeting context (stage/prejoin/lobby).
     * </pre>
     */
    public com.jervis.contracts.server.PresenceResponse reportPresence(com.jervis.contracts.server.PresenceRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReportPresenceMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerMeetingAttendService.
   * <pre>
   * ServerMeetingAttendService — approval flow for calendar-detected
   * meetings ("should Jervis attend?") + presence callbacks from the
   * browser pod. Consumed by MCP tools and the o365 browser pool agent.
   * </pre>
   */
  public static final class ServerMeetingAttendServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerMeetingAttendServiceBlockingStub> {
    private ServerMeetingAttendServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingAttendServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingAttendServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * CALENDAR_PROCESSING tasks in the next `hours_ahead` hours.
     * </pre>
     */
    public com.jervis.contracts.server.ListUpcomingResponse listUpcoming(com.jervis.contracts.server.ListUpcomingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListUpcomingMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Approve / deny a meeting-attend task.
     * </pre>
     */
    public com.jervis.contracts.server.AttendDecisionResponse approve(com.jervis.contracts.server.AttendDecisionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getApproveMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AttendDecisionResponse deny(com.jervis.contracts.server.AttendDecisionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDenyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Browser pod → server presence callback. present=true means the user
     * is in any meeting context (stage/prejoin/lobby).
     * </pre>
     */
    public com.jervis.contracts.server.PresenceResponse reportPresence(com.jervis.contracts.server.PresenceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReportPresenceMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerMeetingAttendService.
   * <pre>
   * ServerMeetingAttendService — approval flow for calendar-detected
   * meetings ("should Jervis attend?") + presence callbacks from the
   * browser pod. Consumed by MCP tools and the o365 browser pool agent.
   * </pre>
   */
  public static final class ServerMeetingAttendServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerMeetingAttendServiceFutureStub> {
    private ServerMeetingAttendServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingAttendServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingAttendServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * CALENDAR_PROCESSING tasks in the next `hours_ahead` hours.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListUpcomingResponse> listUpcoming(
        com.jervis.contracts.server.ListUpcomingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListUpcomingMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Approve / deny a meeting-attend task.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AttendDecisionResponse> approve(
        com.jervis.contracts.server.AttendDecisionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getApproveMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AttendDecisionResponse> deny(
        com.jervis.contracts.server.AttendDecisionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDenyMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Browser pod → server presence callback. present=true means the user
     * is in any meeting context (stage/prejoin/lobby).
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.PresenceResponse> reportPresence(
        com.jervis.contracts.server.PresenceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReportPresenceMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LIST_UPCOMING = 0;
  private static final int METHODID_APPROVE = 1;
  private static final int METHODID_DENY = 2;
  private static final int METHODID_REPORT_PRESENCE = 3;

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
        case METHODID_LIST_UPCOMING:
          serviceImpl.listUpcoming((com.jervis.contracts.server.ListUpcomingRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListUpcomingResponse>) responseObserver);
          break;
        case METHODID_APPROVE:
          serviceImpl.approve((com.jervis.contracts.server.AttendDecisionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AttendDecisionResponse>) responseObserver);
          break;
        case METHODID_DENY:
          serviceImpl.deny((com.jervis.contracts.server.AttendDecisionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AttendDecisionResponse>) responseObserver);
          break;
        case METHODID_REPORT_PRESENCE:
          serviceImpl.reportPresence((com.jervis.contracts.server.PresenceRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.PresenceResponse>) responseObserver);
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
          getListUpcomingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListUpcomingRequest,
              com.jervis.contracts.server.ListUpcomingResponse>(
                service, METHODID_LIST_UPCOMING)))
        .addMethod(
          getApproveMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.AttendDecisionRequest,
              com.jervis.contracts.server.AttendDecisionResponse>(
                service, METHODID_APPROVE)))
        .addMethod(
          getDenyMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.AttendDecisionRequest,
              com.jervis.contracts.server.AttendDecisionResponse>(
                service, METHODID_DENY)))
        .addMethod(
          getReportPresenceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.PresenceRequest,
              com.jervis.contracts.server.PresenceResponse>(
                service, METHODID_REPORT_PRESENCE)))
        .build();
  }

  private static abstract class ServerMeetingAttendServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerMeetingAttendServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerMeetingAttendProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerMeetingAttendService");
    }
  }

  private static final class ServerMeetingAttendServiceFileDescriptorSupplier
      extends ServerMeetingAttendServiceBaseDescriptorSupplier {
    ServerMeetingAttendServiceFileDescriptorSupplier() {}
  }

  private static final class ServerMeetingAttendServiceMethodDescriptorSupplier
      extends ServerMeetingAttendServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerMeetingAttendServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerMeetingAttendServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerMeetingAttendServiceFileDescriptorSupplier())
              .addMethod(getListUpcomingMethod())
              .addMethod(getApproveMethod())
              .addMethod(getDenyMethod())
              .addMethod(getReportPresenceMethod())
              .build();
        }
      }
    }
    return result;
  }
}
