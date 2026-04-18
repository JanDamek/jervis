package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerUrgencyService exposes urgency config, user presence lookup, and
 * task deadline bumping — feeds the orchestrator's urgency-aware
 * scheduling tools. Messages mirror com.jervis.dto.urgency.UrgencyConfigDto
 * / UserPresenceDto 1:1.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerUrgencyServiceGrpc {

  private ServerUrgencyServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerUrgencyService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetUrgencyConfigRequest,
      com.jervis.contracts.server.UrgencyConfig> getGetConfigMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetConfig",
      requestType = com.jervis.contracts.server.GetUrgencyConfigRequest.class,
      responseType = com.jervis.contracts.server.UrgencyConfig.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetUrgencyConfigRequest,
      com.jervis.contracts.server.UrgencyConfig> getGetConfigMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetUrgencyConfigRequest, com.jervis.contracts.server.UrgencyConfig> getGetConfigMethod;
    if ((getGetConfigMethod = ServerUrgencyServiceGrpc.getGetConfigMethod) == null) {
      synchronized (ServerUrgencyServiceGrpc.class) {
        if ((getGetConfigMethod = ServerUrgencyServiceGrpc.getGetConfigMethod) == null) {
          ServerUrgencyServiceGrpc.getGetConfigMethod = getGetConfigMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetUrgencyConfigRequest, com.jervis.contracts.server.UrgencyConfig>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetConfig"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetUrgencyConfigRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UrgencyConfig.getDefaultInstance()))
              .setSchemaDescriptor(new ServerUrgencyServiceMethodDescriptorSupplier("GetConfig"))
              .build();
        }
      }
    }
    return getGetConfigMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateUrgencyConfigRequest,
      com.jervis.contracts.server.UrgencyConfig> getUpdateConfigMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateConfig",
      requestType = com.jervis.contracts.server.UpdateUrgencyConfigRequest.class,
      responseType = com.jervis.contracts.server.UrgencyConfig.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateUrgencyConfigRequest,
      com.jervis.contracts.server.UrgencyConfig> getUpdateConfigMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateUrgencyConfigRequest, com.jervis.contracts.server.UrgencyConfig> getUpdateConfigMethod;
    if ((getUpdateConfigMethod = ServerUrgencyServiceGrpc.getUpdateConfigMethod) == null) {
      synchronized (ServerUrgencyServiceGrpc.class) {
        if ((getUpdateConfigMethod = ServerUrgencyServiceGrpc.getUpdateConfigMethod) == null) {
          ServerUrgencyServiceGrpc.getUpdateConfigMethod = getUpdateConfigMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.UpdateUrgencyConfigRequest, com.jervis.contracts.server.UrgencyConfig>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateConfig"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UpdateUrgencyConfigRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UrgencyConfig.getDefaultInstance()))
              .setSchemaDescriptor(new ServerUrgencyServiceMethodDescriptorSupplier("UpdateConfig"))
              .build();
        }
      }
    }
    return getUpdateConfigMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetUserPresenceRequest,
      com.jervis.contracts.server.UserPresence> getGetPresenceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetPresence",
      requestType = com.jervis.contracts.server.GetUserPresenceRequest.class,
      responseType = com.jervis.contracts.server.UserPresence.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetUserPresenceRequest,
      com.jervis.contracts.server.UserPresence> getGetPresenceMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetUserPresenceRequest, com.jervis.contracts.server.UserPresence> getGetPresenceMethod;
    if ((getGetPresenceMethod = ServerUrgencyServiceGrpc.getGetPresenceMethod) == null) {
      synchronized (ServerUrgencyServiceGrpc.class) {
        if ((getGetPresenceMethod = ServerUrgencyServiceGrpc.getGetPresenceMethod) == null) {
          ServerUrgencyServiceGrpc.getGetPresenceMethod = getGetPresenceMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetUserPresenceRequest, com.jervis.contracts.server.UserPresence>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetPresence"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetUserPresenceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UserPresence.getDefaultInstance()))
              .setSchemaDescriptor(new ServerUrgencyServiceMethodDescriptorSupplier("GetPresence"))
              .build();
        }
      }
    }
    return getGetPresenceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.BumpDeadlineRequest,
      com.jervis.contracts.server.BumpDeadlineResponse> getBumpDeadlineMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "BumpDeadline",
      requestType = com.jervis.contracts.server.BumpDeadlineRequest.class,
      responseType = com.jervis.contracts.server.BumpDeadlineResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.BumpDeadlineRequest,
      com.jervis.contracts.server.BumpDeadlineResponse> getBumpDeadlineMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.BumpDeadlineRequest, com.jervis.contracts.server.BumpDeadlineResponse> getBumpDeadlineMethod;
    if ((getBumpDeadlineMethod = ServerUrgencyServiceGrpc.getBumpDeadlineMethod) == null) {
      synchronized (ServerUrgencyServiceGrpc.class) {
        if ((getBumpDeadlineMethod = ServerUrgencyServiceGrpc.getBumpDeadlineMethod) == null) {
          ServerUrgencyServiceGrpc.getBumpDeadlineMethod = getBumpDeadlineMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.BumpDeadlineRequest, com.jervis.contracts.server.BumpDeadlineResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "BumpDeadline"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.BumpDeadlineRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.BumpDeadlineResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerUrgencyServiceMethodDescriptorSupplier("BumpDeadline"))
              .build();
        }
      }
    }
    return getBumpDeadlineMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerUrgencyServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerUrgencyServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerUrgencyServiceStub>() {
        @java.lang.Override
        public ServerUrgencyServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerUrgencyServiceStub(channel, callOptions);
        }
      };
    return ServerUrgencyServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerUrgencyServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerUrgencyServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerUrgencyServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerUrgencyServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerUrgencyServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerUrgencyServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerUrgencyServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerUrgencyServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerUrgencyServiceBlockingStub>() {
        @java.lang.Override
        public ServerUrgencyServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerUrgencyServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerUrgencyServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerUrgencyServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerUrgencyServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerUrgencyServiceFutureStub>() {
        @java.lang.Override
        public ServerUrgencyServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerUrgencyServiceFutureStub(channel, callOptions);
        }
      };
    return ServerUrgencyServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerUrgencyService exposes urgency config, user presence lookup, and
   * task deadline bumping — feeds the orchestrator's urgency-aware
   * scheduling tools. Messages mirror com.jervis.dto.urgency.UrgencyConfigDto
   * / UserPresenceDto 1:1.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void getConfig(com.jervis.contracts.server.GetUrgencyConfigRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UrgencyConfig> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetConfigMethod(), responseObserver);
    }

    /**
     */
    default void updateConfig(com.jervis.contracts.server.UpdateUrgencyConfigRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UrgencyConfig> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateConfigMethod(), responseObserver);
    }

    /**
     */
    default void getPresence(com.jervis.contracts.server.GetUserPresenceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UserPresence> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetPresenceMethod(), responseObserver);
    }

    /**
     */
    default void bumpDeadline(com.jervis.contracts.server.BumpDeadlineRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.BumpDeadlineResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getBumpDeadlineMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerUrgencyService.
   * <pre>
   * ServerUrgencyService exposes urgency config, user presence lookup, and
   * task deadline bumping — feeds the orchestrator's urgency-aware
   * scheduling tools. Messages mirror com.jervis.dto.urgency.UrgencyConfigDto
   * / UserPresenceDto 1:1.
   * </pre>
   */
  public static abstract class ServerUrgencyServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerUrgencyServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerUrgencyService.
   * <pre>
   * ServerUrgencyService exposes urgency config, user presence lookup, and
   * task deadline bumping — feeds the orchestrator's urgency-aware
   * scheduling tools. Messages mirror com.jervis.dto.urgency.UrgencyConfigDto
   * / UserPresenceDto 1:1.
   * </pre>
   */
  public static final class ServerUrgencyServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerUrgencyServiceStub> {
    private ServerUrgencyServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerUrgencyServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerUrgencyServiceStub(channel, callOptions);
    }

    /**
     */
    public void getConfig(com.jervis.contracts.server.GetUrgencyConfigRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UrgencyConfig> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetConfigMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void updateConfig(com.jervis.contracts.server.UpdateUrgencyConfigRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UrgencyConfig> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateConfigMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getPresence(com.jervis.contracts.server.GetUserPresenceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UserPresence> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetPresenceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void bumpDeadline(com.jervis.contracts.server.BumpDeadlineRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.BumpDeadlineResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getBumpDeadlineMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerUrgencyService.
   * <pre>
   * ServerUrgencyService exposes urgency config, user presence lookup, and
   * task deadline bumping — feeds the orchestrator's urgency-aware
   * scheduling tools. Messages mirror com.jervis.dto.urgency.UrgencyConfigDto
   * / UserPresenceDto 1:1.
   * </pre>
   */
  public static final class ServerUrgencyServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerUrgencyServiceBlockingV2Stub> {
    private ServerUrgencyServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerUrgencyServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerUrgencyServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.UrgencyConfig getConfig(com.jervis.contracts.server.GetUrgencyConfigRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetConfigMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UrgencyConfig updateConfig(com.jervis.contracts.server.UpdateUrgencyConfigRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateConfigMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UserPresence getPresence(com.jervis.contracts.server.GetUserPresenceRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetPresenceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.BumpDeadlineResponse bumpDeadline(com.jervis.contracts.server.BumpDeadlineRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getBumpDeadlineMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerUrgencyService.
   * <pre>
   * ServerUrgencyService exposes urgency config, user presence lookup, and
   * task deadline bumping — feeds the orchestrator's urgency-aware
   * scheduling tools. Messages mirror com.jervis.dto.urgency.UrgencyConfigDto
   * / UserPresenceDto 1:1.
   * </pre>
   */
  public static final class ServerUrgencyServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerUrgencyServiceBlockingStub> {
    private ServerUrgencyServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerUrgencyServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerUrgencyServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.UrgencyConfig getConfig(com.jervis.contracts.server.GetUrgencyConfigRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetConfigMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UrgencyConfig updateConfig(com.jervis.contracts.server.UpdateUrgencyConfigRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateConfigMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UserPresence getPresence(com.jervis.contracts.server.GetUserPresenceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetPresenceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.BumpDeadlineResponse bumpDeadline(com.jervis.contracts.server.BumpDeadlineRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getBumpDeadlineMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerUrgencyService.
   * <pre>
   * ServerUrgencyService exposes urgency config, user presence lookup, and
   * task deadline bumping — feeds the orchestrator's urgency-aware
   * scheduling tools. Messages mirror com.jervis.dto.urgency.UrgencyConfigDto
   * / UserPresenceDto 1:1.
   * </pre>
   */
  public static final class ServerUrgencyServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerUrgencyServiceFutureStub> {
    private ServerUrgencyServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerUrgencyServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerUrgencyServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.UrgencyConfig> getConfig(
        com.jervis.contracts.server.GetUrgencyConfigRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetConfigMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.UrgencyConfig> updateConfig(
        com.jervis.contracts.server.UpdateUrgencyConfigRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateConfigMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.UserPresence> getPresence(
        com.jervis.contracts.server.GetUserPresenceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetPresenceMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.BumpDeadlineResponse> bumpDeadline(
        com.jervis.contracts.server.BumpDeadlineRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getBumpDeadlineMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_CONFIG = 0;
  private static final int METHODID_UPDATE_CONFIG = 1;
  private static final int METHODID_GET_PRESENCE = 2;
  private static final int METHODID_BUMP_DEADLINE = 3;

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
        case METHODID_GET_CONFIG:
          serviceImpl.getConfig((com.jervis.contracts.server.GetUrgencyConfigRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.UrgencyConfig>) responseObserver);
          break;
        case METHODID_UPDATE_CONFIG:
          serviceImpl.updateConfig((com.jervis.contracts.server.UpdateUrgencyConfigRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.UrgencyConfig>) responseObserver);
          break;
        case METHODID_GET_PRESENCE:
          serviceImpl.getPresence((com.jervis.contracts.server.GetUserPresenceRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.UserPresence>) responseObserver);
          break;
        case METHODID_BUMP_DEADLINE:
          serviceImpl.bumpDeadline((com.jervis.contracts.server.BumpDeadlineRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.BumpDeadlineResponse>) responseObserver);
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
          getGetConfigMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetUrgencyConfigRequest,
              com.jervis.contracts.server.UrgencyConfig>(
                service, METHODID_GET_CONFIG)))
        .addMethod(
          getUpdateConfigMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.UpdateUrgencyConfigRequest,
              com.jervis.contracts.server.UrgencyConfig>(
                service, METHODID_UPDATE_CONFIG)))
        .addMethod(
          getGetPresenceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetUserPresenceRequest,
              com.jervis.contracts.server.UserPresence>(
                service, METHODID_GET_PRESENCE)))
        .addMethod(
          getBumpDeadlineMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.BumpDeadlineRequest,
              com.jervis.contracts.server.BumpDeadlineResponse>(
                service, METHODID_BUMP_DEADLINE)))
        .build();
  }

  private static abstract class ServerUrgencyServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerUrgencyServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerUrgencyProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerUrgencyService");
    }
  }

  private static final class ServerUrgencyServiceFileDescriptorSupplier
      extends ServerUrgencyServiceBaseDescriptorSupplier {
    ServerUrgencyServiceFileDescriptorSupplier() {}
  }

  private static final class ServerUrgencyServiceMethodDescriptorSupplier
      extends ServerUrgencyServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerUrgencyServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerUrgencyServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerUrgencyServiceFileDescriptorSupplier())
              .addMethod(getGetConfigMethod())
              .addMethod(getUpdateConfigMethod())
              .addMethod(getGetPresenceMethod())
              .addMethod(getBumpDeadlineMethod())
              .build();
        }
      }
    }
    return result;
  }
}
