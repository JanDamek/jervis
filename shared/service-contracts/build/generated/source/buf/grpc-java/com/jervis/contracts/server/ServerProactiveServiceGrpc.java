package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerProactiveService exposes scheduled proactive triggers — morning
 * briefing / overdue invoice check / weekly summary / VIP email alert.
 * Invoked by the Python orchestrator's scheduler; each RPC returns a
 * small typed result that callers log.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerProactiveServiceGrpc {

  private ServerProactiveServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerProactiveService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.MorningBriefingRequest,
      com.jervis.contracts.server.MorningBriefingResponse> getMorningBriefingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MorningBriefing",
      requestType = com.jervis.contracts.server.MorningBriefingRequest.class,
      responseType = com.jervis.contracts.server.MorningBriefingResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.MorningBriefingRequest,
      com.jervis.contracts.server.MorningBriefingResponse> getMorningBriefingMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.MorningBriefingRequest, com.jervis.contracts.server.MorningBriefingResponse> getMorningBriefingMethod;
    if ((getMorningBriefingMethod = ServerProactiveServiceGrpc.getMorningBriefingMethod) == null) {
      synchronized (ServerProactiveServiceGrpc.class) {
        if ((getMorningBriefingMethod = ServerProactiveServiceGrpc.getMorningBriefingMethod) == null) {
          ServerProactiveServiceGrpc.getMorningBriefingMethod = getMorningBriefingMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.MorningBriefingRequest, com.jervis.contracts.server.MorningBriefingResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MorningBriefing"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.MorningBriefingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.MorningBriefingResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProactiveServiceMethodDescriptorSupplier("MorningBriefing"))
              .build();
        }
      }
    }
    return getMorningBriefingMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.OverdueCheckRequest,
      com.jervis.contracts.server.OverdueCheckResponse> getOverdueCheckMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "OverdueCheck",
      requestType = com.jervis.contracts.server.OverdueCheckRequest.class,
      responseType = com.jervis.contracts.server.OverdueCheckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.OverdueCheckRequest,
      com.jervis.contracts.server.OverdueCheckResponse> getOverdueCheckMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.OverdueCheckRequest, com.jervis.contracts.server.OverdueCheckResponse> getOverdueCheckMethod;
    if ((getOverdueCheckMethod = ServerProactiveServiceGrpc.getOverdueCheckMethod) == null) {
      synchronized (ServerProactiveServiceGrpc.class) {
        if ((getOverdueCheckMethod = ServerProactiveServiceGrpc.getOverdueCheckMethod) == null) {
          ServerProactiveServiceGrpc.getOverdueCheckMethod = getOverdueCheckMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.OverdueCheckRequest, com.jervis.contracts.server.OverdueCheckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "OverdueCheck"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.OverdueCheckRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.OverdueCheckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProactiveServiceMethodDescriptorSupplier("OverdueCheck"))
              .build();
        }
      }
    }
    return getOverdueCheckMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.WeeklySummaryRequest,
      com.jervis.contracts.server.WeeklySummaryResponse> getWeeklySummaryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "WeeklySummary",
      requestType = com.jervis.contracts.server.WeeklySummaryRequest.class,
      responseType = com.jervis.contracts.server.WeeklySummaryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.WeeklySummaryRequest,
      com.jervis.contracts.server.WeeklySummaryResponse> getWeeklySummaryMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.WeeklySummaryRequest, com.jervis.contracts.server.WeeklySummaryResponse> getWeeklySummaryMethod;
    if ((getWeeklySummaryMethod = ServerProactiveServiceGrpc.getWeeklySummaryMethod) == null) {
      synchronized (ServerProactiveServiceGrpc.class) {
        if ((getWeeklySummaryMethod = ServerProactiveServiceGrpc.getWeeklySummaryMethod) == null) {
          ServerProactiveServiceGrpc.getWeeklySummaryMethod = getWeeklySummaryMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.WeeklySummaryRequest, com.jervis.contracts.server.WeeklySummaryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "WeeklySummary"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WeeklySummaryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WeeklySummaryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProactiveServiceMethodDescriptorSupplier("WeeklySummary"))
              .build();
        }
      }
    }
    return getWeeklySummaryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.VipAlertRequest,
      com.jervis.contracts.server.VipAlertResponse> getVipAlertMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "VipAlert",
      requestType = com.jervis.contracts.server.VipAlertRequest.class,
      responseType = com.jervis.contracts.server.VipAlertResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.VipAlertRequest,
      com.jervis.contracts.server.VipAlertResponse> getVipAlertMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.VipAlertRequest, com.jervis.contracts.server.VipAlertResponse> getVipAlertMethod;
    if ((getVipAlertMethod = ServerProactiveServiceGrpc.getVipAlertMethod) == null) {
      synchronized (ServerProactiveServiceGrpc.class) {
        if ((getVipAlertMethod = ServerProactiveServiceGrpc.getVipAlertMethod) == null) {
          ServerProactiveServiceGrpc.getVipAlertMethod = getVipAlertMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.VipAlertRequest, com.jervis.contracts.server.VipAlertResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "VipAlert"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.VipAlertRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.VipAlertResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProactiveServiceMethodDescriptorSupplier("VipAlert"))
              .build();
        }
      }
    }
    return getVipAlertMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerProactiveServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerProactiveServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerProactiveServiceStub>() {
        @java.lang.Override
        public ServerProactiveServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerProactiveServiceStub(channel, callOptions);
        }
      };
    return ServerProactiveServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerProactiveServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerProactiveServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerProactiveServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerProactiveServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerProactiveServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerProactiveServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerProactiveServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerProactiveServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerProactiveServiceBlockingStub>() {
        @java.lang.Override
        public ServerProactiveServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerProactiveServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerProactiveServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerProactiveServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerProactiveServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerProactiveServiceFutureStub>() {
        @java.lang.Override
        public ServerProactiveServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerProactiveServiceFutureStub(channel, callOptions);
        }
      };
    return ServerProactiveServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerProactiveService exposes scheduled proactive triggers — morning
   * briefing / overdue invoice check / weekly summary / VIP email alert.
   * Invoked by the Python orchestrator's scheduler; each RPC returns a
   * small typed result that callers log.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void morningBriefing(com.jervis.contracts.server.MorningBriefingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.MorningBriefingResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMorningBriefingMethod(), responseObserver);
    }

    /**
     */
    default void overdueCheck(com.jervis.contracts.server.OverdueCheckRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.OverdueCheckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getOverdueCheckMethod(), responseObserver);
    }

    /**
     */
    default void weeklySummary(com.jervis.contracts.server.WeeklySummaryRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WeeklySummaryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getWeeklySummaryMethod(), responseObserver);
    }

    /**
     */
    default void vipAlert(com.jervis.contracts.server.VipAlertRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.VipAlertResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getVipAlertMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerProactiveService.
   * <pre>
   * ServerProactiveService exposes scheduled proactive triggers — morning
   * briefing / overdue invoice check / weekly summary / VIP email alert.
   * Invoked by the Python orchestrator's scheduler; each RPC returns a
   * small typed result that callers log.
   * </pre>
   */
  public static abstract class ServerProactiveServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerProactiveServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerProactiveService.
   * <pre>
   * ServerProactiveService exposes scheduled proactive triggers — morning
   * briefing / overdue invoice check / weekly summary / VIP email alert.
   * Invoked by the Python orchestrator's scheduler; each RPC returns a
   * small typed result that callers log.
   * </pre>
   */
  public static final class ServerProactiveServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerProactiveServiceStub> {
    private ServerProactiveServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerProactiveServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerProactiveServiceStub(channel, callOptions);
    }

    /**
     */
    public void morningBriefing(com.jervis.contracts.server.MorningBriefingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.MorningBriefingResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMorningBriefingMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void overdueCheck(com.jervis.contracts.server.OverdueCheckRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.OverdueCheckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getOverdueCheckMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void weeklySummary(com.jervis.contracts.server.WeeklySummaryRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WeeklySummaryResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getWeeklySummaryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void vipAlert(com.jervis.contracts.server.VipAlertRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.VipAlertResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getVipAlertMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerProactiveService.
   * <pre>
   * ServerProactiveService exposes scheduled proactive triggers — morning
   * briefing / overdue invoice check / weekly summary / VIP email alert.
   * Invoked by the Python orchestrator's scheduler; each RPC returns a
   * small typed result that callers log.
   * </pre>
   */
  public static final class ServerProactiveServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerProactiveServiceBlockingV2Stub> {
    private ServerProactiveServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerProactiveServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerProactiveServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.MorningBriefingResponse morningBriefing(com.jervis.contracts.server.MorningBriefingRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getMorningBriefingMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.OverdueCheckResponse overdueCheck(com.jervis.contracts.server.OverdueCheckRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getOverdueCheckMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.WeeklySummaryResponse weeklySummary(com.jervis.contracts.server.WeeklySummaryRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getWeeklySummaryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.VipAlertResponse vipAlert(com.jervis.contracts.server.VipAlertRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getVipAlertMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerProactiveService.
   * <pre>
   * ServerProactiveService exposes scheduled proactive triggers — morning
   * briefing / overdue invoice check / weekly summary / VIP email alert.
   * Invoked by the Python orchestrator's scheduler; each RPC returns a
   * small typed result that callers log.
   * </pre>
   */
  public static final class ServerProactiveServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerProactiveServiceBlockingStub> {
    private ServerProactiveServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerProactiveServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerProactiveServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.MorningBriefingResponse morningBriefing(com.jervis.contracts.server.MorningBriefingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMorningBriefingMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.OverdueCheckResponse overdueCheck(com.jervis.contracts.server.OverdueCheckRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getOverdueCheckMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.WeeklySummaryResponse weeklySummary(com.jervis.contracts.server.WeeklySummaryRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getWeeklySummaryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.VipAlertResponse vipAlert(com.jervis.contracts.server.VipAlertRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getVipAlertMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerProactiveService.
   * <pre>
   * ServerProactiveService exposes scheduled proactive triggers — morning
   * briefing / overdue invoice check / weekly summary / VIP email alert.
   * Invoked by the Python orchestrator's scheduler; each RPC returns a
   * small typed result that callers log.
   * </pre>
   */
  public static final class ServerProactiveServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerProactiveServiceFutureStub> {
    private ServerProactiveServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerProactiveServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerProactiveServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.MorningBriefingResponse> morningBriefing(
        com.jervis.contracts.server.MorningBriefingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getMorningBriefingMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.OverdueCheckResponse> overdueCheck(
        com.jervis.contracts.server.OverdueCheckRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getOverdueCheckMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.WeeklySummaryResponse> weeklySummary(
        com.jervis.contracts.server.WeeklySummaryRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getWeeklySummaryMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.VipAlertResponse> vipAlert(
        com.jervis.contracts.server.VipAlertRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getVipAlertMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_MORNING_BRIEFING = 0;
  private static final int METHODID_OVERDUE_CHECK = 1;
  private static final int METHODID_WEEKLY_SUMMARY = 2;
  private static final int METHODID_VIP_ALERT = 3;

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
        case METHODID_MORNING_BRIEFING:
          serviceImpl.morningBriefing((com.jervis.contracts.server.MorningBriefingRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.MorningBriefingResponse>) responseObserver);
          break;
        case METHODID_OVERDUE_CHECK:
          serviceImpl.overdueCheck((com.jervis.contracts.server.OverdueCheckRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.OverdueCheckResponse>) responseObserver);
          break;
        case METHODID_WEEKLY_SUMMARY:
          serviceImpl.weeklySummary((com.jervis.contracts.server.WeeklySummaryRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.WeeklySummaryResponse>) responseObserver);
          break;
        case METHODID_VIP_ALERT:
          serviceImpl.vipAlert((com.jervis.contracts.server.VipAlertRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.VipAlertResponse>) responseObserver);
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
          getMorningBriefingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.MorningBriefingRequest,
              com.jervis.contracts.server.MorningBriefingResponse>(
                service, METHODID_MORNING_BRIEFING)))
        .addMethod(
          getOverdueCheckMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.OverdueCheckRequest,
              com.jervis.contracts.server.OverdueCheckResponse>(
                service, METHODID_OVERDUE_CHECK)))
        .addMethod(
          getWeeklySummaryMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.WeeklySummaryRequest,
              com.jervis.contracts.server.WeeklySummaryResponse>(
                service, METHODID_WEEKLY_SUMMARY)))
        .addMethod(
          getVipAlertMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.VipAlertRequest,
              com.jervis.contracts.server.VipAlertResponse>(
                service, METHODID_VIP_ALERT)))
        .build();
  }

  private static abstract class ServerProactiveServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerProactiveServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerProactiveProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerProactiveService");
    }
  }

  private static final class ServerProactiveServiceFileDescriptorSupplier
      extends ServerProactiveServiceBaseDescriptorSupplier {
    ServerProactiveServiceFileDescriptorSupplier() {}
  }

  private static final class ServerProactiveServiceMethodDescriptorSupplier
      extends ServerProactiveServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerProactiveServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerProactiveServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerProactiveServiceFileDescriptorSupplier())
              .addMethod(getMorningBriefingMethod())
              .addMethod(getOverdueCheckMethod())
              .addMethod(getWeeklySummaryMethod())
              .addMethod(getVipAlertMethod())
              .build();
        }
      }
    }
    return result;
  }
}
