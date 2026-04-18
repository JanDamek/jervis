package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorMeetingHelperService — live meeting assistance pipeline.
 * Kotlin server drives Start/Stop around a meeting recording, forwards
 * transcript chunks via Chunk, and polls Status. The orchestrator pushes
 * helper messages back over ServerMeetingHelperCallbacksService.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.0)",
    comments = "Source: jervis/orchestrator/meeting_helper.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorMeetingHelperServiceGrpc {

  private OrchestratorMeetingHelperServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorMeetingHelperService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StartHelperRequest,
      com.jervis.contracts.orchestrator.StartHelperResponse> getStartMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Start",
      requestType = com.jervis.contracts.orchestrator.StartHelperRequest.class,
      responseType = com.jervis.contracts.orchestrator.StartHelperResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StartHelperRequest,
      com.jervis.contracts.orchestrator.StartHelperResponse> getStartMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StartHelperRequest, com.jervis.contracts.orchestrator.StartHelperResponse> getStartMethod;
    if ((getStartMethod = OrchestratorMeetingHelperServiceGrpc.getStartMethod) == null) {
      synchronized (OrchestratorMeetingHelperServiceGrpc.class) {
        if ((getStartMethod = OrchestratorMeetingHelperServiceGrpc.getStartMethod) == null) {
          OrchestratorMeetingHelperServiceGrpc.getStartMethod = getStartMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.StartHelperRequest, com.jervis.contracts.orchestrator.StartHelperResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Start"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.StartHelperRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.StartHelperResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorMeetingHelperServiceMethodDescriptorSupplier("Start"))
              .build();
        }
      }
    }
    return getStartMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StopHelperRequest,
      com.jervis.contracts.orchestrator.StopHelperResponse> getStopMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Stop",
      requestType = com.jervis.contracts.orchestrator.StopHelperRequest.class,
      responseType = com.jervis.contracts.orchestrator.StopHelperResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StopHelperRequest,
      com.jervis.contracts.orchestrator.StopHelperResponse> getStopMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StopHelperRequest, com.jervis.contracts.orchestrator.StopHelperResponse> getStopMethod;
    if ((getStopMethod = OrchestratorMeetingHelperServiceGrpc.getStopMethod) == null) {
      synchronized (OrchestratorMeetingHelperServiceGrpc.class) {
        if ((getStopMethod = OrchestratorMeetingHelperServiceGrpc.getStopMethod) == null) {
          OrchestratorMeetingHelperServiceGrpc.getStopMethod = getStopMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.StopHelperRequest, com.jervis.contracts.orchestrator.StopHelperResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Stop"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.StopHelperRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.StopHelperResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorMeetingHelperServiceMethodDescriptorSupplier("Stop"))
              .build();
        }
      }
    }
    return getStopMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.HelperChunkRequest,
      com.jervis.contracts.orchestrator.HelperChunkResponse> getChunkMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Chunk",
      requestType = com.jervis.contracts.orchestrator.HelperChunkRequest.class,
      responseType = com.jervis.contracts.orchestrator.HelperChunkResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.HelperChunkRequest,
      com.jervis.contracts.orchestrator.HelperChunkResponse> getChunkMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.HelperChunkRequest, com.jervis.contracts.orchestrator.HelperChunkResponse> getChunkMethod;
    if ((getChunkMethod = OrchestratorMeetingHelperServiceGrpc.getChunkMethod) == null) {
      synchronized (OrchestratorMeetingHelperServiceGrpc.class) {
        if ((getChunkMethod = OrchestratorMeetingHelperServiceGrpc.getChunkMethod) == null) {
          OrchestratorMeetingHelperServiceGrpc.getChunkMethod = getChunkMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.HelperChunkRequest, com.jervis.contracts.orchestrator.HelperChunkResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Chunk"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.HelperChunkRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.HelperChunkResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorMeetingHelperServiceMethodDescriptorSupplier("Chunk"))
              .build();
        }
      }
    }
    return getChunkMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.HelperStatusRequest,
      com.jervis.contracts.orchestrator.HelperStatusResponse> getStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Status",
      requestType = com.jervis.contracts.orchestrator.HelperStatusRequest.class,
      responseType = com.jervis.contracts.orchestrator.HelperStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.HelperStatusRequest,
      com.jervis.contracts.orchestrator.HelperStatusResponse> getStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.HelperStatusRequest, com.jervis.contracts.orchestrator.HelperStatusResponse> getStatusMethod;
    if ((getStatusMethod = OrchestratorMeetingHelperServiceGrpc.getStatusMethod) == null) {
      synchronized (OrchestratorMeetingHelperServiceGrpc.class) {
        if ((getStatusMethod = OrchestratorMeetingHelperServiceGrpc.getStatusMethod) == null) {
          OrchestratorMeetingHelperServiceGrpc.getStatusMethod = getStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.HelperStatusRequest, com.jervis.contracts.orchestrator.HelperStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Status"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.HelperStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.HelperStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorMeetingHelperServiceMethodDescriptorSupplier("Status"))
              .build();
        }
      }
    }
    return getStatusMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorMeetingHelperServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorMeetingHelperServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorMeetingHelperServiceStub>() {
        @java.lang.Override
        public OrchestratorMeetingHelperServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorMeetingHelperServiceStub(channel, callOptions);
        }
      };
    return OrchestratorMeetingHelperServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorMeetingHelperServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorMeetingHelperServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorMeetingHelperServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorMeetingHelperServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorMeetingHelperServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorMeetingHelperServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorMeetingHelperServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorMeetingHelperServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorMeetingHelperServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorMeetingHelperServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorMeetingHelperServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorMeetingHelperServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorMeetingHelperService — live meeting assistance pipeline.
   * Kotlin server drives Start/Stop around a meeting recording, forwards
   * transcript chunks via Chunk, and polls Status. The orchestrator pushes
   * helper messages back over ServerMeetingHelperCallbacksService.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void start(com.jervis.contracts.orchestrator.StartHelperRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StartHelperResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStartMethod(), responseObserver);
    }

    /**
     */
    default void stop(com.jervis.contracts.orchestrator.StopHelperRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StopHelperResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStopMethod(), responseObserver);
    }

    /**
     */
    default void chunk(com.jervis.contracts.orchestrator.HelperChunkRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.HelperChunkResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getChunkMethod(), responseObserver);
    }

    /**
     */
    default void status(com.jervis.contracts.orchestrator.HelperStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.HelperStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStatusMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorMeetingHelperService.
   * <pre>
   * OrchestratorMeetingHelperService — live meeting assistance pipeline.
   * Kotlin server drives Start/Stop around a meeting recording, forwards
   * transcript chunks via Chunk, and polls Status. The orchestrator pushes
   * helper messages back over ServerMeetingHelperCallbacksService.
   * </pre>
   */
  public static abstract class OrchestratorMeetingHelperServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorMeetingHelperServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorMeetingHelperService.
   * <pre>
   * OrchestratorMeetingHelperService — live meeting assistance pipeline.
   * Kotlin server drives Start/Stop around a meeting recording, forwards
   * transcript chunks via Chunk, and polls Status. The orchestrator pushes
   * helper messages back over ServerMeetingHelperCallbacksService.
   * </pre>
   */
  public static final class OrchestratorMeetingHelperServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorMeetingHelperServiceStub> {
    private OrchestratorMeetingHelperServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorMeetingHelperServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorMeetingHelperServiceStub(channel, callOptions);
    }

    /**
     */
    public void start(com.jervis.contracts.orchestrator.StartHelperRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StartHelperResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStartMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void stop(com.jervis.contracts.orchestrator.StopHelperRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StopHelperResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void chunk(com.jervis.contracts.orchestrator.HelperChunkRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.HelperChunkResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getChunkMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void status(com.jervis.contracts.orchestrator.HelperStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.HelperStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStatusMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorMeetingHelperService.
   * <pre>
   * OrchestratorMeetingHelperService — live meeting assistance pipeline.
   * Kotlin server drives Start/Stop around a meeting recording, forwards
   * transcript chunks via Chunk, and polls Status. The orchestrator pushes
   * helper messages back over ServerMeetingHelperCallbacksService.
   * </pre>
   */
  public static final class OrchestratorMeetingHelperServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorMeetingHelperServiceBlockingStub> {
    private OrchestratorMeetingHelperServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorMeetingHelperServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorMeetingHelperServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.StartHelperResponse start(com.jervis.contracts.orchestrator.StartHelperRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStartMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.StopHelperResponse stop(com.jervis.contracts.orchestrator.StopHelperRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStopMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.HelperChunkResponse chunk(com.jervis.contracts.orchestrator.HelperChunkRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getChunkMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.HelperStatusResponse status(com.jervis.contracts.orchestrator.HelperStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStatusMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorMeetingHelperService.
   * <pre>
   * OrchestratorMeetingHelperService — live meeting assistance pipeline.
   * Kotlin server drives Start/Stop around a meeting recording, forwards
   * transcript chunks via Chunk, and polls Status. The orchestrator pushes
   * helper messages back over ServerMeetingHelperCallbacksService.
   * </pre>
   */
  public static final class OrchestratorMeetingHelperServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorMeetingHelperServiceFutureStub> {
    private OrchestratorMeetingHelperServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorMeetingHelperServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorMeetingHelperServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.StartHelperResponse> start(
        com.jervis.contracts.orchestrator.StartHelperRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStartMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.StopHelperResponse> stop(
        com.jervis.contracts.orchestrator.StopHelperRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.HelperChunkResponse> chunk(
        com.jervis.contracts.orchestrator.HelperChunkRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getChunkMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.HelperStatusResponse> status(
        com.jervis.contracts.orchestrator.HelperStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStatusMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_START = 0;
  private static final int METHODID_STOP = 1;
  private static final int METHODID_CHUNK = 2;
  private static final int METHODID_STATUS = 3;

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
        case METHODID_START:
          serviceImpl.start((com.jervis.contracts.orchestrator.StartHelperRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StartHelperResponse>) responseObserver);
          break;
        case METHODID_STOP:
          serviceImpl.stop((com.jervis.contracts.orchestrator.StopHelperRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.StopHelperResponse>) responseObserver);
          break;
        case METHODID_CHUNK:
          serviceImpl.chunk((com.jervis.contracts.orchestrator.HelperChunkRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.HelperChunkResponse>) responseObserver);
          break;
        case METHODID_STATUS:
          serviceImpl.status((com.jervis.contracts.orchestrator.HelperStatusRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.HelperStatusResponse>) responseObserver);
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
          getStartMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.StartHelperRequest,
              com.jervis.contracts.orchestrator.StartHelperResponse>(
                service, METHODID_START)))
        .addMethod(
          getStopMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.StopHelperRequest,
              com.jervis.contracts.orchestrator.StopHelperResponse>(
                service, METHODID_STOP)))
        .addMethod(
          getChunkMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.HelperChunkRequest,
              com.jervis.contracts.orchestrator.HelperChunkResponse>(
                service, METHODID_CHUNK)))
        .addMethod(
          getStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.HelperStatusRequest,
              com.jervis.contracts.orchestrator.HelperStatusResponse>(
                service, METHODID_STATUS)))
        .build();
  }

  private static abstract class OrchestratorMeetingHelperServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorMeetingHelperServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorMeetingHelperProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorMeetingHelperService");
    }
  }

  private static final class OrchestratorMeetingHelperServiceFileDescriptorSupplier
      extends OrchestratorMeetingHelperServiceBaseDescriptorSupplier {
    OrchestratorMeetingHelperServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorMeetingHelperServiceMethodDescriptorSupplier
      extends OrchestratorMeetingHelperServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorMeetingHelperServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorMeetingHelperServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorMeetingHelperServiceFileDescriptorSupplier())
              .addMethod(getStartMethod())
              .addMethod(getStopMethod())
              .addMethod(getChunkMethod())
              .addMethod(getStatusMethod())
              .build();
        }
      }
    }
    return result;
  }
}
