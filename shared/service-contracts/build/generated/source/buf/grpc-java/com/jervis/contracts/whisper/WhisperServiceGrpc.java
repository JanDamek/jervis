package com.jervis.contracts.whisper;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * WhisperService — audio transcription with progress streaming.
 * Transcribe is unary-request → server-streaming-response: the request
 * carries the full audio bytes inline (64 MiB cap; multi-hour meeting
 * audio goes via blob side channel once that lands). Progress + result
 * events stream back over the response.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class WhisperServiceGrpc {

  private WhisperServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.whisper.WhisperService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whisper.TranscribeRequest,
      com.jervis.contracts.whisper.TranscribeEvent> getTranscribeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Transcribe",
      requestType = com.jervis.contracts.whisper.TranscribeRequest.class,
      responseType = com.jervis.contracts.whisper.TranscribeEvent.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whisper.TranscribeRequest,
      com.jervis.contracts.whisper.TranscribeEvent> getTranscribeMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whisper.TranscribeRequest, com.jervis.contracts.whisper.TranscribeEvent> getTranscribeMethod;
    if ((getTranscribeMethod = WhisperServiceGrpc.getTranscribeMethod) == null) {
      synchronized (WhisperServiceGrpc.class) {
        if ((getTranscribeMethod = WhisperServiceGrpc.getTranscribeMethod) == null) {
          WhisperServiceGrpc.getTranscribeMethod = getTranscribeMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whisper.TranscribeRequest, com.jervis.contracts.whisper.TranscribeEvent>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Transcribe"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whisper.TranscribeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whisper.TranscribeEvent.getDefaultInstance()))
              .setSchemaDescriptor(new WhisperServiceMethodDescriptorSupplier("Transcribe"))
              .build();
        }
      }
    }
    return getTranscribeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whisper.HealthRequest,
      com.jervis.contracts.whisper.HealthResponse> getHealthMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Health",
      requestType = com.jervis.contracts.whisper.HealthRequest.class,
      responseType = com.jervis.contracts.whisper.HealthResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whisper.HealthRequest,
      com.jervis.contracts.whisper.HealthResponse> getHealthMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whisper.HealthRequest, com.jervis.contracts.whisper.HealthResponse> getHealthMethod;
    if ((getHealthMethod = WhisperServiceGrpc.getHealthMethod) == null) {
      synchronized (WhisperServiceGrpc.class) {
        if ((getHealthMethod = WhisperServiceGrpc.getHealthMethod) == null) {
          WhisperServiceGrpc.getHealthMethod = getHealthMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whisper.HealthRequest, com.jervis.contracts.whisper.HealthResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Health"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whisper.HealthRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whisper.HealthResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhisperServiceMethodDescriptorSupplier("Health"))
              .build();
        }
      }
    }
    return getHealthMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whisper.GpuReleaseRequest,
      com.jervis.contracts.whisper.GpuReleaseResponse> getGpuReleaseMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GpuRelease",
      requestType = com.jervis.contracts.whisper.GpuReleaseRequest.class,
      responseType = com.jervis.contracts.whisper.GpuReleaseResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whisper.GpuReleaseRequest,
      com.jervis.contracts.whisper.GpuReleaseResponse> getGpuReleaseMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whisper.GpuReleaseRequest, com.jervis.contracts.whisper.GpuReleaseResponse> getGpuReleaseMethod;
    if ((getGpuReleaseMethod = WhisperServiceGrpc.getGpuReleaseMethod) == null) {
      synchronized (WhisperServiceGrpc.class) {
        if ((getGpuReleaseMethod = WhisperServiceGrpc.getGpuReleaseMethod) == null) {
          WhisperServiceGrpc.getGpuReleaseMethod = getGpuReleaseMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whisper.GpuReleaseRequest, com.jervis.contracts.whisper.GpuReleaseResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GpuRelease"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whisper.GpuReleaseRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whisper.GpuReleaseResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhisperServiceMethodDescriptorSupplier("GpuRelease"))
              .build();
        }
      }
    }
    return getGpuReleaseMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static WhisperServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WhisperServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WhisperServiceStub>() {
        @java.lang.Override
        public WhisperServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WhisperServiceStub(channel, callOptions);
        }
      };
    return WhisperServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static WhisperServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WhisperServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WhisperServiceBlockingV2Stub>() {
        @java.lang.Override
        public WhisperServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WhisperServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return WhisperServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static WhisperServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WhisperServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WhisperServiceBlockingStub>() {
        @java.lang.Override
        public WhisperServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WhisperServiceBlockingStub(channel, callOptions);
        }
      };
    return WhisperServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static WhisperServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WhisperServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WhisperServiceFutureStub>() {
        @java.lang.Override
        public WhisperServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WhisperServiceFutureStub(channel, callOptions);
        }
      };
    return WhisperServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * WhisperService — audio transcription with progress streaming.
   * Transcribe is unary-request → server-streaming-response: the request
   * carries the full audio bytes inline (64 MiB cap; multi-hour meeting
   * audio goes via blob side channel once that lands). Progress + result
   * events stream back over the response.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void transcribe(com.jervis.contracts.whisper.TranscribeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whisper.TranscribeEvent> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTranscribeMethod(), responseObserver);
    }

    /**
     */
    default void health(com.jervis.contracts.whisper.HealthRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whisper.HealthResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHealthMethod(), responseObserver);
    }

    /**
     */
    default void gpuRelease(com.jervis.contracts.whisper.GpuReleaseRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whisper.GpuReleaseResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGpuReleaseMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service WhisperService.
   * <pre>
   * WhisperService — audio transcription with progress streaming.
   * Transcribe is unary-request → server-streaming-response: the request
   * carries the full audio bytes inline (64 MiB cap; multi-hour meeting
   * audio goes via blob side channel once that lands). Progress + result
   * events stream back over the response.
   * </pre>
   */
  public static abstract class WhisperServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return WhisperServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service WhisperService.
   * <pre>
   * WhisperService — audio transcription with progress streaming.
   * Transcribe is unary-request → server-streaming-response: the request
   * carries the full audio bytes inline (64 MiB cap; multi-hour meeting
   * audio goes via blob side channel once that lands). Progress + result
   * events stream back over the response.
   * </pre>
   */
  public static final class WhisperServiceStub
      extends io.grpc.stub.AbstractAsyncStub<WhisperServiceStub> {
    private WhisperServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WhisperServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WhisperServiceStub(channel, callOptions);
    }

    /**
     */
    public void transcribe(com.jervis.contracts.whisper.TranscribeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whisper.TranscribeEvent> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getTranscribeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void health(com.jervis.contracts.whisper.HealthRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whisper.HealthResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void gpuRelease(com.jervis.contracts.whisper.GpuReleaseRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whisper.GpuReleaseResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGpuReleaseMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service WhisperService.
   * <pre>
   * WhisperService — audio transcription with progress streaming.
   * Transcribe is unary-request → server-streaming-response: the request
   * carries the full audio bytes inline (64 MiB cap; multi-hour meeting
   * audio goes via blob side channel once that lands). Progress + result
   * events stream back over the response.
   * </pre>
   */
  public static final class WhisperServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<WhisperServiceBlockingV2Stub> {
    private WhisperServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WhisperServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WhisperServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, com.jervis.contracts.whisper.TranscribeEvent>
        transcribe(com.jervis.contracts.whisper.TranscribeRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getTranscribeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whisper.HealthResponse health(com.jervis.contracts.whisper.HealthRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whisper.GpuReleaseResponse gpuRelease(com.jervis.contracts.whisper.GpuReleaseRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGpuReleaseMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service WhisperService.
   * <pre>
   * WhisperService — audio transcription with progress streaming.
   * Transcribe is unary-request → server-streaming-response: the request
   * carries the full audio bytes inline (64 MiB cap; multi-hour meeting
   * audio goes via blob side channel once that lands). Progress + result
   * events stream back over the response.
   * </pre>
   */
  public static final class WhisperServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<WhisperServiceBlockingStub> {
    private WhisperServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WhisperServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WhisperServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public java.util.Iterator<com.jervis.contracts.whisper.TranscribeEvent> transcribe(
        com.jervis.contracts.whisper.TranscribeRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getTranscribeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whisper.HealthResponse health(com.jervis.contracts.whisper.HealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whisper.GpuReleaseResponse gpuRelease(com.jervis.contracts.whisper.GpuReleaseRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGpuReleaseMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service WhisperService.
   * <pre>
   * WhisperService — audio transcription with progress streaming.
   * Transcribe is unary-request → server-streaming-response: the request
   * carries the full audio bytes inline (64 MiB cap; multi-hour meeting
   * audio goes via blob side channel once that lands). Progress + result
   * events stream back over the response.
   * </pre>
   */
  public static final class WhisperServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<WhisperServiceFutureStub> {
    private WhisperServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WhisperServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WhisperServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whisper.HealthResponse> health(
        com.jervis.contracts.whisper.HealthRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whisper.GpuReleaseResponse> gpuRelease(
        com.jervis.contracts.whisper.GpuReleaseRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGpuReleaseMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_TRANSCRIBE = 0;
  private static final int METHODID_HEALTH = 1;
  private static final int METHODID_GPU_RELEASE = 2;

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
        case METHODID_TRANSCRIBE:
          serviceImpl.transcribe((com.jervis.contracts.whisper.TranscribeRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whisper.TranscribeEvent>) responseObserver);
          break;
        case METHODID_HEALTH:
          serviceImpl.health((com.jervis.contracts.whisper.HealthRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whisper.HealthResponse>) responseObserver);
          break;
        case METHODID_GPU_RELEASE:
          serviceImpl.gpuRelease((com.jervis.contracts.whisper.GpuReleaseRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whisper.GpuReleaseResponse>) responseObserver);
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
          getTranscribeMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.jervis.contracts.whisper.TranscribeRequest,
              com.jervis.contracts.whisper.TranscribeEvent>(
                service, METHODID_TRANSCRIBE)))
        .addMethod(
          getHealthMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whisper.HealthRequest,
              com.jervis.contracts.whisper.HealthResponse>(
                service, METHODID_HEALTH)))
        .addMethod(
          getGpuReleaseMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whisper.GpuReleaseRequest,
              com.jervis.contracts.whisper.GpuReleaseResponse>(
                service, METHODID_GPU_RELEASE)))
        .build();
  }

  private static abstract class WhisperServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    WhisperServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.whisper.WhisperProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("WhisperService");
    }
  }

  private static final class WhisperServiceFileDescriptorSupplier
      extends WhisperServiceBaseDescriptorSupplier {
    WhisperServiceFileDescriptorSupplier() {}
  }

  private static final class WhisperServiceMethodDescriptorSupplier
      extends WhisperServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    WhisperServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (WhisperServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new WhisperServiceFileDescriptorSupplier())
              .addMethod(getTranscribeMethod())
              .addMethod(getHealthMethod())
              .addMethod(getGpuReleaseMethod())
              .build();
        }
      }
    }
    return result;
  }
}
