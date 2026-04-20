package com.jervis.contracts.visual_capture;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * VisualCaptureService — ONVIF camera + RTSP capture + VLM analysis.
 * Fully-typed surface — no passthrough JSON. Kotlin server's
 * ServerVisualCaptureGrpcImpl forwards Snapshot/PtzGoto requests from
 * the orchestrator through this RPC.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class VisualCaptureServiceGrpc {

  private VisualCaptureServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.visual_capture.VisualCaptureService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.visual_capture.SnapshotRequest,
      com.jervis.contracts.visual_capture.SnapshotResponse> getSnapshotMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Snapshot",
      requestType = com.jervis.contracts.visual_capture.SnapshotRequest.class,
      responseType = com.jervis.contracts.visual_capture.SnapshotResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.visual_capture.SnapshotRequest,
      com.jervis.contracts.visual_capture.SnapshotResponse> getSnapshotMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.visual_capture.SnapshotRequest, com.jervis.contracts.visual_capture.SnapshotResponse> getSnapshotMethod;
    if ((getSnapshotMethod = VisualCaptureServiceGrpc.getSnapshotMethod) == null) {
      synchronized (VisualCaptureServiceGrpc.class) {
        if ((getSnapshotMethod = VisualCaptureServiceGrpc.getSnapshotMethod) == null) {
          VisualCaptureServiceGrpc.getSnapshotMethod = getSnapshotMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.visual_capture.SnapshotRequest, com.jervis.contracts.visual_capture.SnapshotResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Snapshot"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.visual_capture.SnapshotRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.visual_capture.SnapshotResponse.getDefaultInstance()))
              .setSchemaDescriptor(new VisualCaptureServiceMethodDescriptorSupplier("Snapshot"))
              .build();
        }
      }
    }
    return getSnapshotMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.visual_capture.PtzGotoRequest,
      com.jervis.contracts.visual_capture.PtzGotoResponse> getPtzGotoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PtzGoto",
      requestType = com.jervis.contracts.visual_capture.PtzGotoRequest.class,
      responseType = com.jervis.contracts.visual_capture.PtzGotoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.visual_capture.PtzGotoRequest,
      com.jervis.contracts.visual_capture.PtzGotoResponse> getPtzGotoMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.visual_capture.PtzGotoRequest, com.jervis.contracts.visual_capture.PtzGotoResponse> getPtzGotoMethod;
    if ((getPtzGotoMethod = VisualCaptureServiceGrpc.getPtzGotoMethod) == null) {
      synchronized (VisualCaptureServiceGrpc.class) {
        if ((getPtzGotoMethod = VisualCaptureServiceGrpc.getPtzGotoMethod) == null) {
          VisualCaptureServiceGrpc.getPtzGotoMethod = getPtzGotoMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.visual_capture.PtzGotoRequest, com.jervis.contracts.visual_capture.PtzGotoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PtzGoto"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.visual_capture.PtzGotoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.visual_capture.PtzGotoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new VisualCaptureServiceMethodDescriptorSupplier("PtzGoto"))
              .build();
        }
      }
    }
    return getPtzGotoMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.visual_capture.PtzPresetsRequest,
      com.jervis.contracts.visual_capture.PtzPresetsResponse> getPtzPresetsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PtzPresets",
      requestType = com.jervis.contracts.visual_capture.PtzPresetsRequest.class,
      responseType = com.jervis.contracts.visual_capture.PtzPresetsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.visual_capture.PtzPresetsRequest,
      com.jervis.contracts.visual_capture.PtzPresetsResponse> getPtzPresetsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.visual_capture.PtzPresetsRequest, com.jervis.contracts.visual_capture.PtzPresetsResponse> getPtzPresetsMethod;
    if ((getPtzPresetsMethod = VisualCaptureServiceGrpc.getPtzPresetsMethod) == null) {
      synchronized (VisualCaptureServiceGrpc.class) {
        if ((getPtzPresetsMethod = VisualCaptureServiceGrpc.getPtzPresetsMethod) == null) {
          VisualCaptureServiceGrpc.getPtzPresetsMethod = getPtzPresetsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.visual_capture.PtzPresetsRequest, com.jervis.contracts.visual_capture.PtzPresetsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PtzPresets"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.visual_capture.PtzPresetsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.visual_capture.PtzPresetsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new VisualCaptureServiceMethodDescriptorSupplier("PtzPresets"))
              .build();
        }
      }
    }
    return getPtzPresetsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static VisualCaptureServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<VisualCaptureServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<VisualCaptureServiceStub>() {
        @java.lang.Override
        public VisualCaptureServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new VisualCaptureServiceStub(channel, callOptions);
        }
      };
    return VisualCaptureServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static VisualCaptureServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<VisualCaptureServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<VisualCaptureServiceBlockingV2Stub>() {
        @java.lang.Override
        public VisualCaptureServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new VisualCaptureServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return VisualCaptureServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static VisualCaptureServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<VisualCaptureServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<VisualCaptureServiceBlockingStub>() {
        @java.lang.Override
        public VisualCaptureServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new VisualCaptureServiceBlockingStub(channel, callOptions);
        }
      };
    return VisualCaptureServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static VisualCaptureServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<VisualCaptureServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<VisualCaptureServiceFutureStub>() {
        @java.lang.Override
        public VisualCaptureServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new VisualCaptureServiceFutureStub(channel, callOptions);
        }
      };
    return VisualCaptureServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * VisualCaptureService — ONVIF camera + RTSP capture + VLM analysis.
   * Fully-typed surface — no passthrough JSON. Kotlin server's
   * ServerVisualCaptureGrpcImpl forwards Snapshot/PtzGoto requests from
   * the orchestrator through this RPC.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void snapshot(com.jervis.contracts.visual_capture.SnapshotRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.visual_capture.SnapshotResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSnapshotMethod(), responseObserver);
    }

    /**
     */
    default void ptzGoto(com.jervis.contracts.visual_capture.PtzGotoRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.visual_capture.PtzGotoResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPtzGotoMethod(), responseObserver);
    }

    /**
     */
    default void ptzPresets(com.jervis.contracts.visual_capture.PtzPresetsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.visual_capture.PtzPresetsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPtzPresetsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service VisualCaptureService.
   * <pre>
   * VisualCaptureService — ONVIF camera + RTSP capture + VLM analysis.
   * Fully-typed surface — no passthrough JSON. Kotlin server's
   * ServerVisualCaptureGrpcImpl forwards Snapshot/PtzGoto requests from
   * the orchestrator through this RPC.
   * </pre>
   */
  public static abstract class VisualCaptureServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return VisualCaptureServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service VisualCaptureService.
   * <pre>
   * VisualCaptureService — ONVIF camera + RTSP capture + VLM analysis.
   * Fully-typed surface — no passthrough JSON. Kotlin server's
   * ServerVisualCaptureGrpcImpl forwards Snapshot/PtzGoto requests from
   * the orchestrator through this RPC.
   * </pre>
   */
  public static final class VisualCaptureServiceStub
      extends io.grpc.stub.AbstractAsyncStub<VisualCaptureServiceStub> {
    private VisualCaptureServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected VisualCaptureServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new VisualCaptureServiceStub(channel, callOptions);
    }

    /**
     */
    public void snapshot(com.jervis.contracts.visual_capture.SnapshotRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.visual_capture.SnapshotResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSnapshotMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ptzGoto(com.jervis.contracts.visual_capture.PtzGotoRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.visual_capture.PtzGotoResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPtzGotoMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ptzPresets(com.jervis.contracts.visual_capture.PtzPresetsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.visual_capture.PtzPresetsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPtzPresetsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service VisualCaptureService.
   * <pre>
   * VisualCaptureService — ONVIF camera + RTSP capture + VLM analysis.
   * Fully-typed surface — no passthrough JSON. Kotlin server's
   * ServerVisualCaptureGrpcImpl forwards Snapshot/PtzGoto requests from
   * the orchestrator through this RPC.
   * </pre>
   */
  public static final class VisualCaptureServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<VisualCaptureServiceBlockingV2Stub> {
    private VisualCaptureServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected VisualCaptureServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new VisualCaptureServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.visual_capture.SnapshotResponse snapshot(com.jervis.contracts.visual_capture.SnapshotRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSnapshotMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.visual_capture.PtzGotoResponse ptzGoto(com.jervis.contracts.visual_capture.PtzGotoRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPtzGotoMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.visual_capture.PtzPresetsResponse ptzPresets(com.jervis.contracts.visual_capture.PtzPresetsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPtzPresetsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service VisualCaptureService.
   * <pre>
   * VisualCaptureService — ONVIF camera + RTSP capture + VLM analysis.
   * Fully-typed surface — no passthrough JSON. Kotlin server's
   * ServerVisualCaptureGrpcImpl forwards Snapshot/PtzGoto requests from
   * the orchestrator through this RPC.
   * </pre>
   */
  public static final class VisualCaptureServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<VisualCaptureServiceBlockingStub> {
    private VisualCaptureServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected VisualCaptureServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new VisualCaptureServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.visual_capture.SnapshotResponse snapshot(com.jervis.contracts.visual_capture.SnapshotRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSnapshotMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.visual_capture.PtzGotoResponse ptzGoto(com.jervis.contracts.visual_capture.PtzGotoRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPtzGotoMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.visual_capture.PtzPresetsResponse ptzPresets(com.jervis.contracts.visual_capture.PtzPresetsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPtzPresetsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service VisualCaptureService.
   * <pre>
   * VisualCaptureService — ONVIF camera + RTSP capture + VLM analysis.
   * Fully-typed surface — no passthrough JSON. Kotlin server's
   * ServerVisualCaptureGrpcImpl forwards Snapshot/PtzGoto requests from
   * the orchestrator through this RPC.
   * </pre>
   */
  public static final class VisualCaptureServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<VisualCaptureServiceFutureStub> {
    private VisualCaptureServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected VisualCaptureServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new VisualCaptureServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.visual_capture.SnapshotResponse> snapshot(
        com.jervis.contracts.visual_capture.SnapshotRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSnapshotMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.visual_capture.PtzGotoResponse> ptzGoto(
        com.jervis.contracts.visual_capture.PtzGotoRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPtzGotoMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.visual_capture.PtzPresetsResponse> ptzPresets(
        com.jervis.contracts.visual_capture.PtzPresetsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPtzPresetsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SNAPSHOT = 0;
  private static final int METHODID_PTZ_GOTO = 1;
  private static final int METHODID_PTZ_PRESETS = 2;

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
        case METHODID_SNAPSHOT:
          serviceImpl.snapshot((com.jervis.contracts.visual_capture.SnapshotRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.visual_capture.SnapshotResponse>) responseObserver);
          break;
        case METHODID_PTZ_GOTO:
          serviceImpl.ptzGoto((com.jervis.contracts.visual_capture.PtzGotoRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.visual_capture.PtzGotoResponse>) responseObserver);
          break;
        case METHODID_PTZ_PRESETS:
          serviceImpl.ptzPresets((com.jervis.contracts.visual_capture.PtzPresetsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.visual_capture.PtzPresetsResponse>) responseObserver);
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
          getSnapshotMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.visual_capture.SnapshotRequest,
              com.jervis.contracts.visual_capture.SnapshotResponse>(
                service, METHODID_SNAPSHOT)))
        .addMethod(
          getPtzGotoMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.visual_capture.PtzGotoRequest,
              com.jervis.contracts.visual_capture.PtzGotoResponse>(
                service, METHODID_PTZ_GOTO)))
        .addMethod(
          getPtzPresetsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.visual_capture.PtzPresetsRequest,
              com.jervis.contracts.visual_capture.PtzPresetsResponse>(
                service, METHODID_PTZ_PRESETS)))
        .build();
  }

  private static abstract class VisualCaptureServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    VisualCaptureServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.visual_capture.VisualCaptureProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("VisualCaptureService");
    }
  }

  private static final class VisualCaptureServiceFileDescriptorSupplier
      extends VisualCaptureServiceBaseDescriptorSupplier {
    VisualCaptureServiceFileDescriptorSupplier() {}
  }

  private static final class VisualCaptureServiceMethodDescriptorSupplier
      extends VisualCaptureServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    VisualCaptureServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (VisualCaptureServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new VisualCaptureServiceFileDescriptorSupplier())
              .addMethod(getSnapshotMethod())
              .addMethod(getPtzGotoMethod())
              .addMethod(getPtzPresetsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
