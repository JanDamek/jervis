package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerMeetingAloneService — "alone in meeting" chat bubble resolution
 * (product §10a). Called by the MCP tool layer when the user answers an
 * alone-check push with "Odejít" (Leave) or "Zůstat" (Stay).
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerMeetingAloneServiceGrpc {

  private ServerMeetingAloneServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerMeetingAloneService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.LeaveRequest,
      com.jervis.contracts.server.LeaveResponse> getLeaveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Leave",
      requestType = com.jervis.contracts.server.LeaveRequest.class,
      responseType = com.jervis.contracts.server.LeaveResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.LeaveRequest,
      com.jervis.contracts.server.LeaveResponse> getLeaveMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.LeaveRequest, com.jervis.contracts.server.LeaveResponse> getLeaveMethod;
    if ((getLeaveMethod = ServerMeetingAloneServiceGrpc.getLeaveMethod) == null) {
      synchronized (ServerMeetingAloneServiceGrpc.class) {
        if ((getLeaveMethod = ServerMeetingAloneServiceGrpc.getLeaveMethod) == null) {
          ServerMeetingAloneServiceGrpc.getLeaveMethod = getLeaveMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.LeaveRequest, com.jervis.contracts.server.LeaveResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Leave"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.LeaveRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.LeaveResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingAloneServiceMethodDescriptorSupplier("Leave"))
              .build();
        }
      }
    }
    return getLeaveMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.StayRequest,
      com.jervis.contracts.server.StayResponse> getStayMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Stay",
      requestType = com.jervis.contracts.server.StayRequest.class,
      responseType = com.jervis.contracts.server.StayResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.StayRequest,
      com.jervis.contracts.server.StayResponse> getStayMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.StayRequest, com.jervis.contracts.server.StayResponse> getStayMethod;
    if ((getStayMethod = ServerMeetingAloneServiceGrpc.getStayMethod) == null) {
      synchronized (ServerMeetingAloneServiceGrpc.class) {
        if ((getStayMethod = ServerMeetingAloneServiceGrpc.getStayMethod) == null) {
          ServerMeetingAloneServiceGrpc.getStayMethod = getStayMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.StayRequest, com.jervis.contracts.server.StayResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Stay"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.StayRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.StayResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingAloneServiceMethodDescriptorSupplier("Stay"))
              .build();
        }
      }
    }
    return getStayMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerMeetingAloneServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAloneServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAloneServiceStub>() {
        @java.lang.Override
        public ServerMeetingAloneServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingAloneServiceStub(channel, callOptions);
        }
      };
    return ServerMeetingAloneServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerMeetingAloneServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAloneServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAloneServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerMeetingAloneServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingAloneServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerMeetingAloneServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerMeetingAloneServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAloneServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAloneServiceBlockingStub>() {
        @java.lang.Override
        public ServerMeetingAloneServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingAloneServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerMeetingAloneServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerMeetingAloneServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAloneServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingAloneServiceFutureStub>() {
        @java.lang.Override
        public ServerMeetingAloneServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingAloneServiceFutureStub(channel, callOptions);
        }
      };
    return ServerMeetingAloneServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerMeetingAloneService — "alone in meeting" chat bubble resolution
   * (product §10a). Called by the MCP tool layer when the user answers an
   * alone-check push with "Odejít" (Leave) or "Zůstat" (Stay).
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Dispatch `leave_meeting` to the pod agent; stops recording +
     * clicks Leave in Teams + reports presence=false.
     * </pre>
     */
    default void leave(com.jervis.contracts.server.LeaveRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.LeaveResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getLeaveMethod(), responseObserver);
    }

    /**
     * <pre>
     * Suppress further alone-check pushes for `suppress_minutes`.
     * </pre>
     */
    default void stay(com.jervis.contracts.server.StayRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.StayResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStayMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerMeetingAloneService.
   * <pre>
   * ServerMeetingAloneService — "alone in meeting" chat bubble resolution
   * (product §10a). Called by the MCP tool layer when the user answers an
   * alone-check push with "Odejít" (Leave) or "Zůstat" (Stay).
   * </pre>
   */
  public static abstract class ServerMeetingAloneServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerMeetingAloneServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerMeetingAloneService.
   * <pre>
   * ServerMeetingAloneService — "alone in meeting" chat bubble resolution
   * (product §10a). Called by the MCP tool layer when the user answers an
   * alone-check push with "Odejít" (Leave) or "Zůstat" (Stay).
   * </pre>
   */
  public static final class ServerMeetingAloneServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerMeetingAloneServiceStub> {
    private ServerMeetingAloneServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingAloneServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingAloneServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Dispatch `leave_meeting` to the pod agent; stops recording +
     * clicks Leave in Teams + reports presence=false.
     * </pre>
     */
    public void leave(com.jervis.contracts.server.LeaveRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.LeaveResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getLeaveMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Suppress further alone-check pushes for `suppress_minutes`.
     * </pre>
     */
    public void stay(com.jervis.contracts.server.StayRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.StayResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStayMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerMeetingAloneService.
   * <pre>
   * ServerMeetingAloneService — "alone in meeting" chat bubble resolution
   * (product §10a). Called by the MCP tool layer when the user answers an
   * alone-check push with "Odejít" (Leave) or "Zůstat" (Stay).
   * </pre>
   */
  public static final class ServerMeetingAloneServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerMeetingAloneServiceBlockingV2Stub> {
    private ServerMeetingAloneServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingAloneServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingAloneServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Dispatch `leave_meeting` to the pod agent; stops recording +
     * clicks Leave in Teams + reports presence=false.
     * </pre>
     */
    public com.jervis.contracts.server.LeaveResponse leave(com.jervis.contracts.server.LeaveRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getLeaveMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Suppress further alone-check pushes for `suppress_minutes`.
     * </pre>
     */
    public com.jervis.contracts.server.StayResponse stay(com.jervis.contracts.server.StayRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStayMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerMeetingAloneService.
   * <pre>
   * ServerMeetingAloneService — "alone in meeting" chat bubble resolution
   * (product §10a). Called by the MCP tool layer when the user answers an
   * alone-check push with "Odejít" (Leave) or "Zůstat" (Stay).
   * </pre>
   */
  public static final class ServerMeetingAloneServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerMeetingAloneServiceBlockingStub> {
    private ServerMeetingAloneServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingAloneServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingAloneServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Dispatch `leave_meeting` to the pod agent; stops recording +
     * clicks Leave in Teams + reports presence=false.
     * </pre>
     */
    public com.jervis.contracts.server.LeaveResponse leave(com.jervis.contracts.server.LeaveRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getLeaveMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Suppress further alone-check pushes for `suppress_minutes`.
     * </pre>
     */
    public com.jervis.contracts.server.StayResponse stay(com.jervis.contracts.server.StayRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStayMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerMeetingAloneService.
   * <pre>
   * ServerMeetingAloneService — "alone in meeting" chat bubble resolution
   * (product §10a). Called by the MCP tool layer when the user answers an
   * alone-check push with "Odejít" (Leave) or "Zůstat" (Stay).
   * </pre>
   */
  public static final class ServerMeetingAloneServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerMeetingAloneServiceFutureStub> {
    private ServerMeetingAloneServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingAloneServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingAloneServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Dispatch `leave_meeting` to the pod agent; stops recording +
     * clicks Leave in Teams + reports presence=false.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.LeaveResponse> leave(
        com.jervis.contracts.server.LeaveRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getLeaveMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Suppress further alone-check pushes for `suppress_minutes`.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.StayResponse> stay(
        com.jervis.contracts.server.StayRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStayMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LEAVE = 0;
  private static final int METHODID_STAY = 1;

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
        case METHODID_LEAVE:
          serviceImpl.leave((com.jervis.contracts.server.LeaveRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.LeaveResponse>) responseObserver);
          break;
        case METHODID_STAY:
          serviceImpl.stay((com.jervis.contracts.server.StayRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.StayResponse>) responseObserver);
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
          getLeaveMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.LeaveRequest,
              com.jervis.contracts.server.LeaveResponse>(
                service, METHODID_LEAVE)))
        .addMethod(
          getStayMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.StayRequest,
              com.jervis.contracts.server.StayResponse>(
                service, METHODID_STAY)))
        .build();
  }

  private static abstract class ServerMeetingAloneServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerMeetingAloneServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerMeetingAloneProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerMeetingAloneService");
    }
  }

  private static final class ServerMeetingAloneServiceFileDescriptorSupplier
      extends ServerMeetingAloneServiceBaseDescriptorSupplier {
    ServerMeetingAloneServiceFileDescriptorSupplier() {}
  }

  private static final class ServerMeetingAloneServiceMethodDescriptorSupplier
      extends ServerMeetingAloneServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerMeetingAloneServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerMeetingAloneServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerMeetingAloneServiceFileDescriptorSupplier())
              .addMethod(getLeaveMethod())
              .addMethod(getStayMethod())
              .build();
        }
      }
    }
    return result;
  }
}
