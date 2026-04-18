package com.jervis.contracts.tts;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * TtsService — text → audio synthesis. One-shot Speak returns a full
 * WAV blob; SpeakStream yields the audio sentence-by-sentence for
 * low-latency playback.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class TtsServiceGrpc {

  private TtsServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.tts.TtsService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.tts.SpeakRequest,
      com.jervis.contracts.tts.SpeakResponse> getSpeakMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Speak",
      requestType = com.jervis.contracts.tts.SpeakRequest.class,
      responseType = com.jervis.contracts.tts.SpeakResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.tts.SpeakRequest,
      com.jervis.contracts.tts.SpeakResponse> getSpeakMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.tts.SpeakRequest, com.jervis.contracts.tts.SpeakResponse> getSpeakMethod;
    if ((getSpeakMethod = TtsServiceGrpc.getSpeakMethod) == null) {
      synchronized (TtsServiceGrpc.class) {
        if ((getSpeakMethod = TtsServiceGrpc.getSpeakMethod) == null) {
          TtsServiceGrpc.getSpeakMethod = getSpeakMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.tts.SpeakRequest, com.jervis.contracts.tts.SpeakResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Speak"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.tts.SpeakRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.tts.SpeakResponse.getDefaultInstance()))
              .setSchemaDescriptor(new TtsServiceMethodDescriptorSupplier("Speak"))
              .build();
        }
      }
    }
    return getSpeakMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.tts.SpeakRequest,
      com.jervis.contracts.tts.AudioChunk> getSpeakStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SpeakStream",
      requestType = com.jervis.contracts.tts.SpeakRequest.class,
      responseType = com.jervis.contracts.tts.AudioChunk.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.tts.SpeakRequest,
      com.jervis.contracts.tts.AudioChunk> getSpeakStreamMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.tts.SpeakRequest, com.jervis.contracts.tts.AudioChunk> getSpeakStreamMethod;
    if ((getSpeakStreamMethod = TtsServiceGrpc.getSpeakStreamMethod) == null) {
      synchronized (TtsServiceGrpc.class) {
        if ((getSpeakStreamMethod = TtsServiceGrpc.getSpeakStreamMethod) == null) {
          TtsServiceGrpc.getSpeakStreamMethod = getSpeakStreamMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.tts.SpeakRequest, com.jervis.contracts.tts.AudioChunk>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SpeakStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.tts.SpeakRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.tts.AudioChunk.getDefaultInstance()))
              .setSchemaDescriptor(new TtsServiceMethodDescriptorSupplier("SpeakStream"))
              .build();
        }
      }
    }
    return getSpeakStreamMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static TtsServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TtsServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TtsServiceStub>() {
        @java.lang.Override
        public TtsServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TtsServiceStub(channel, callOptions);
        }
      };
    return TtsServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static TtsServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TtsServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TtsServiceBlockingV2Stub>() {
        @java.lang.Override
        public TtsServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TtsServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return TtsServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static TtsServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TtsServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TtsServiceBlockingStub>() {
        @java.lang.Override
        public TtsServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TtsServiceBlockingStub(channel, callOptions);
        }
      };
    return TtsServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static TtsServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TtsServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TtsServiceFutureStub>() {
        @java.lang.Override
        public TtsServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TtsServiceFutureStub(channel, callOptions);
        }
      };
    return TtsServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * TtsService — text → audio synthesis. One-shot Speak returns a full
   * WAV blob; SpeakStream yields the audio sentence-by-sentence for
   * low-latency playback.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void speak(com.jervis.contracts.tts.SpeakRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.tts.SpeakResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSpeakMethod(), responseObserver);
    }

    /**
     */
    default void speakStream(com.jervis.contracts.tts.SpeakRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.tts.AudioChunk> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSpeakStreamMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service TtsService.
   * <pre>
   * TtsService — text → audio synthesis. One-shot Speak returns a full
   * WAV blob; SpeakStream yields the audio sentence-by-sentence for
   * low-latency playback.
   * </pre>
   */
  public static abstract class TtsServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return TtsServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service TtsService.
   * <pre>
   * TtsService — text → audio synthesis. One-shot Speak returns a full
   * WAV blob; SpeakStream yields the audio sentence-by-sentence for
   * low-latency playback.
   * </pre>
   */
  public static final class TtsServiceStub
      extends io.grpc.stub.AbstractAsyncStub<TtsServiceStub> {
    private TtsServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TtsServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TtsServiceStub(channel, callOptions);
    }

    /**
     */
    public void speak(com.jervis.contracts.tts.SpeakRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.tts.SpeakResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSpeakMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void speakStream(com.jervis.contracts.tts.SpeakRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.tts.AudioChunk> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getSpeakStreamMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service TtsService.
   * <pre>
   * TtsService — text → audio synthesis. One-shot Speak returns a full
   * WAV blob; SpeakStream yields the audio sentence-by-sentence for
   * low-latency playback.
   * </pre>
   */
  public static final class TtsServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<TtsServiceBlockingV2Stub> {
    private TtsServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TtsServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TtsServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.tts.SpeakResponse speak(com.jervis.contracts.tts.SpeakRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSpeakMethod(), getCallOptions(), request);
    }

    /**
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, com.jervis.contracts.tts.AudioChunk>
        speakStream(com.jervis.contracts.tts.SpeakRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getSpeakStreamMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service TtsService.
   * <pre>
   * TtsService — text → audio synthesis. One-shot Speak returns a full
   * WAV blob; SpeakStream yields the audio sentence-by-sentence for
   * low-latency playback.
   * </pre>
   */
  public static final class TtsServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<TtsServiceBlockingStub> {
    private TtsServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TtsServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TtsServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.tts.SpeakResponse speak(com.jervis.contracts.tts.SpeakRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSpeakMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<com.jervis.contracts.tts.AudioChunk> speakStream(
        com.jervis.contracts.tts.SpeakRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getSpeakStreamMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service TtsService.
   * <pre>
   * TtsService — text → audio synthesis. One-shot Speak returns a full
   * WAV blob; SpeakStream yields the audio sentence-by-sentence for
   * low-latency playback.
   * </pre>
   */
  public static final class TtsServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<TtsServiceFutureStub> {
    private TtsServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TtsServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TtsServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.tts.SpeakResponse> speak(
        com.jervis.contracts.tts.SpeakRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSpeakMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SPEAK = 0;
  private static final int METHODID_SPEAK_STREAM = 1;

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
        case METHODID_SPEAK:
          serviceImpl.speak((com.jervis.contracts.tts.SpeakRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.tts.SpeakResponse>) responseObserver);
          break;
        case METHODID_SPEAK_STREAM:
          serviceImpl.speakStream((com.jervis.contracts.tts.SpeakRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.tts.AudioChunk>) responseObserver);
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
          getSpeakMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.tts.SpeakRequest,
              com.jervis.contracts.tts.SpeakResponse>(
                service, METHODID_SPEAK)))
        .addMethod(
          getSpeakStreamMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.jervis.contracts.tts.SpeakRequest,
              com.jervis.contracts.tts.AudioChunk>(
                service, METHODID_SPEAK_STREAM)))
        .build();
  }

  private static abstract class TtsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    TtsServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.tts.TtsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("TtsService");
    }
  }

  private static final class TtsServiceFileDescriptorSupplier
      extends TtsServiceBaseDescriptorSupplier {
    TtsServiceFileDescriptorSupplier() {}
  }

  private static final class TtsServiceMethodDescriptorSupplier
      extends TtsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    TtsServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (TtsServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new TtsServiceFileDescriptorSupplier())
              .addMethod(getSpeakMethod())
              .addMethod(getSpeakStreamMethod())
              .build();
        }
      }
    }
    return result;
  }
}
