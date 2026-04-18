package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerVisualCaptureService — inbound bridge for the
 * `service-visual-capture` K8s pod. The pod pushes VLM analysis results
 * here (scene/whiteboard/screen OCR) and the server fans them into
 * MeetingHelperService for live UI push + KB storage. Snapshot + PTZ
 * proxy RPCs are typed 1:1 with the downstream VisualCaptureService on
 * the capture pod.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.0)",
    comments = "Source: jervis/server/visual_capture.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerVisualCaptureServiceGrpc {

  private ServerVisualCaptureServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerVisualCaptureService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.VisualResultRequest,
      com.jervis.contracts.server.VisualResultResponse> getPostResultMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PostResult",
      requestType = com.jervis.contracts.server.VisualResultRequest.class,
      responseType = com.jervis.contracts.server.VisualResultResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.VisualResultRequest,
      com.jervis.contracts.server.VisualResultResponse> getPostResultMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.VisualResultRequest, com.jervis.contracts.server.VisualResultResponse> getPostResultMethod;
    if ((getPostResultMethod = ServerVisualCaptureServiceGrpc.getPostResultMethod) == null) {
      synchronized (ServerVisualCaptureServiceGrpc.class) {
        if ((getPostResultMethod = ServerVisualCaptureServiceGrpc.getPostResultMethod) == null) {
          ServerVisualCaptureServiceGrpc.getPostResultMethod = getPostResultMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.VisualResultRequest, com.jervis.contracts.server.VisualResultResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PostResult"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.VisualResultRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.VisualResultResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerVisualCaptureServiceMethodDescriptorSupplier("PostResult"))
              .build();
        }
      }
    }
    return getPostResultMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ProxySnapshotRequest,
      com.jervis.contracts.server.ProxySnapshotResponse> getSnapshotMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Snapshot",
      requestType = com.jervis.contracts.server.ProxySnapshotRequest.class,
      responseType = com.jervis.contracts.server.ProxySnapshotResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ProxySnapshotRequest,
      com.jervis.contracts.server.ProxySnapshotResponse> getSnapshotMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ProxySnapshotRequest, com.jervis.contracts.server.ProxySnapshotResponse> getSnapshotMethod;
    if ((getSnapshotMethod = ServerVisualCaptureServiceGrpc.getSnapshotMethod) == null) {
      synchronized (ServerVisualCaptureServiceGrpc.class) {
        if ((getSnapshotMethod = ServerVisualCaptureServiceGrpc.getSnapshotMethod) == null) {
          ServerVisualCaptureServiceGrpc.getSnapshotMethod = getSnapshotMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ProxySnapshotRequest, com.jervis.contracts.server.ProxySnapshotResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Snapshot"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ProxySnapshotRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ProxySnapshotResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerVisualCaptureServiceMethodDescriptorSupplier("Snapshot"))
              .build();
        }
      }
    }
    return getSnapshotMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ProxyPtzRequest,
      com.jervis.contracts.server.ProxyPtzResponse> getPtzMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Ptz",
      requestType = com.jervis.contracts.server.ProxyPtzRequest.class,
      responseType = com.jervis.contracts.server.ProxyPtzResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ProxyPtzRequest,
      com.jervis.contracts.server.ProxyPtzResponse> getPtzMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ProxyPtzRequest, com.jervis.contracts.server.ProxyPtzResponse> getPtzMethod;
    if ((getPtzMethod = ServerVisualCaptureServiceGrpc.getPtzMethod) == null) {
      synchronized (ServerVisualCaptureServiceGrpc.class) {
        if ((getPtzMethod = ServerVisualCaptureServiceGrpc.getPtzMethod) == null) {
          ServerVisualCaptureServiceGrpc.getPtzMethod = getPtzMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ProxyPtzRequest, com.jervis.contracts.server.ProxyPtzResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Ptz"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ProxyPtzRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ProxyPtzResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerVisualCaptureServiceMethodDescriptorSupplier("Ptz"))
              .build();
        }
      }
    }
    return getPtzMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerVisualCaptureServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerVisualCaptureServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerVisualCaptureServiceStub>() {
        @java.lang.Override
        public ServerVisualCaptureServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerVisualCaptureServiceStub(channel, callOptions);
        }
      };
    return ServerVisualCaptureServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerVisualCaptureServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerVisualCaptureServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerVisualCaptureServiceBlockingStub>() {
        @java.lang.Override
        public ServerVisualCaptureServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerVisualCaptureServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerVisualCaptureServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerVisualCaptureServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerVisualCaptureServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerVisualCaptureServiceFutureStub>() {
        @java.lang.Override
        public ServerVisualCaptureServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerVisualCaptureServiceFutureStub(channel, callOptions);
        }
      };
    return ServerVisualCaptureServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerVisualCaptureService — inbound bridge for the
   * `service-visual-capture` K8s pod. The pod pushes VLM analysis results
   * here (scene/whiteboard/screen OCR) and the server fans them into
   * MeetingHelperService for live UI push + KB storage. Snapshot + PTZ
   * proxy RPCs are typed 1:1 with the downstream VisualCaptureService on
   * the capture pod.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void postResult(com.jervis.contracts.server.VisualResultRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.VisualResultResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPostResultMethod(), responseObserver);
    }

    /**
     */
    default void snapshot(com.jervis.contracts.server.ProxySnapshotRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProxySnapshotResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSnapshotMethod(), responseObserver);
    }

    /**
     */
    default void ptz(com.jervis.contracts.server.ProxyPtzRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProxyPtzResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPtzMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerVisualCaptureService.
   * <pre>
   * ServerVisualCaptureService — inbound bridge for the
   * `service-visual-capture` K8s pod. The pod pushes VLM analysis results
   * here (scene/whiteboard/screen OCR) and the server fans them into
   * MeetingHelperService for live UI push + KB storage. Snapshot + PTZ
   * proxy RPCs are typed 1:1 with the downstream VisualCaptureService on
   * the capture pod.
   * </pre>
   */
  public static abstract class ServerVisualCaptureServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerVisualCaptureServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerVisualCaptureService.
   * <pre>
   * ServerVisualCaptureService — inbound bridge for the
   * `service-visual-capture` K8s pod. The pod pushes VLM analysis results
   * here (scene/whiteboard/screen OCR) and the server fans them into
   * MeetingHelperService for live UI push + KB storage. Snapshot + PTZ
   * proxy RPCs are typed 1:1 with the downstream VisualCaptureService on
   * the capture pod.
   * </pre>
   */
  public static final class ServerVisualCaptureServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerVisualCaptureServiceStub> {
    private ServerVisualCaptureServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerVisualCaptureServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerVisualCaptureServiceStub(channel, callOptions);
    }

    /**
     */
    public void postResult(com.jervis.contracts.server.VisualResultRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.VisualResultResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPostResultMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void snapshot(com.jervis.contracts.server.ProxySnapshotRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProxySnapshotResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSnapshotMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ptz(com.jervis.contracts.server.ProxyPtzRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProxyPtzResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPtzMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerVisualCaptureService.
   * <pre>
   * ServerVisualCaptureService — inbound bridge for the
   * `service-visual-capture` K8s pod. The pod pushes VLM analysis results
   * here (scene/whiteboard/screen OCR) and the server fans them into
   * MeetingHelperService for live UI push + KB storage. Snapshot + PTZ
   * proxy RPCs are typed 1:1 with the downstream VisualCaptureService on
   * the capture pod.
   * </pre>
   */
  public static final class ServerVisualCaptureServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerVisualCaptureServiceBlockingStub> {
    private ServerVisualCaptureServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerVisualCaptureServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerVisualCaptureServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.VisualResultResponse postResult(com.jervis.contracts.server.VisualResultRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPostResultMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ProxySnapshotResponse snapshot(com.jervis.contracts.server.ProxySnapshotRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSnapshotMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ProxyPtzResponse ptz(com.jervis.contracts.server.ProxyPtzRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPtzMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerVisualCaptureService.
   * <pre>
   * ServerVisualCaptureService — inbound bridge for the
   * `service-visual-capture` K8s pod. The pod pushes VLM analysis results
   * here (scene/whiteboard/screen OCR) and the server fans them into
   * MeetingHelperService for live UI push + KB storage. Snapshot + PTZ
   * proxy RPCs are typed 1:1 with the downstream VisualCaptureService on
   * the capture pod.
   * </pre>
   */
  public static final class ServerVisualCaptureServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerVisualCaptureServiceFutureStub> {
    private ServerVisualCaptureServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerVisualCaptureServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerVisualCaptureServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.VisualResultResponse> postResult(
        com.jervis.contracts.server.VisualResultRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPostResultMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ProxySnapshotResponse> snapshot(
        com.jervis.contracts.server.ProxySnapshotRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSnapshotMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ProxyPtzResponse> ptz(
        com.jervis.contracts.server.ProxyPtzRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPtzMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_POST_RESULT = 0;
  private static final int METHODID_SNAPSHOT = 1;
  private static final int METHODID_PTZ = 2;

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
        case METHODID_POST_RESULT:
          serviceImpl.postResult((com.jervis.contracts.server.VisualResultRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.VisualResultResponse>) responseObserver);
          break;
        case METHODID_SNAPSHOT:
          serviceImpl.snapshot((com.jervis.contracts.server.ProxySnapshotRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProxySnapshotResponse>) responseObserver);
          break;
        case METHODID_PTZ:
          serviceImpl.ptz((com.jervis.contracts.server.ProxyPtzRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProxyPtzResponse>) responseObserver);
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
          getPostResultMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.VisualResultRequest,
              com.jervis.contracts.server.VisualResultResponse>(
                service, METHODID_POST_RESULT)))
        .addMethod(
          getSnapshotMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ProxySnapshotRequest,
              com.jervis.contracts.server.ProxySnapshotResponse>(
                service, METHODID_SNAPSHOT)))
        .addMethod(
          getPtzMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ProxyPtzRequest,
              com.jervis.contracts.server.ProxyPtzResponse>(
                service, METHODID_PTZ)))
        .build();
  }

  private static abstract class ServerVisualCaptureServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerVisualCaptureServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerVisualCaptureProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerVisualCaptureService");
    }
  }

  private static final class ServerVisualCaptureServiceFileDescriptorSupplier
      extends ServerVisualCaptureServiceBaseDescriptorSupplier {
    ServerVisualCaptureServiceFileDescriptorSupplier() {}
  }

  private static final class ServerVisualCaptureServiceMethodDescriptorSupplier
      extends ServerVisualCaptureServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerVisualCaptureServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerVisualCaptureServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerVisualCaptureServiceFileDescriptorSupplier())
              .addMethod(getPostResultMethod())
              .addMethod(getSnapshotMethod())
              .addMethod(getPtzMethod())
              .build();
        }
      }
    }
    return result;
  }
}
