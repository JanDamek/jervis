package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorVoiceService — post-transcription voice pipeline. Process
 * streams the intent-classified agentic reply. Hint returns a KB-based
 * live-assist hint (single unary response).
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorVoiceServiceGrpc {

  private OrchestratorVoiceServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorVoiceService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.VoiceProcessRequest,
      com.jervis.contracts.orchestrator.VoiceStreamEvent> getProcessMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Process",
      requestType = com.jervis.contracts.orchestrator.VoiceProcessRequest.class,
      responseType = com.jervis.contracts.orchestrator.VoiceStreamEvent.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.VoiceProcessRequest,
      com.jervis.contracts.orchestrator.VoiceStreamEvent> getProcessMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.VoiceProcessRequest, com.jervis.contracts.orchestrator.VoiceStreamEvent> getProcessMethod;
    if ((getProcessMethod = OrchestratorVoiceServiceGrpc.getProcessMethod) == null) {
      synchronized (OrchestratorVoiceServiceGrpc.class) {
        if ((getProcessMethod = OrchestratorVoiceServiceGrpc.getProcessMethod) == null) {
          OrchestratorVoiceServiceGrpc.getProcessMethod = getProcessMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.VoiceProcessRequest, com.jervis.contracts.orchestrator.VoiceStreamEvent>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Process"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.VoiceProcessRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.VoiceStreamEvent.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorVoiceServiceMethodDescriptorSupplier("Process"))
              .build();
        }
      }
    }
    return getProcessMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.VoiceHintRequest,
      com.jervis.contracts.orchestrator.VoiceHintResponse> getHintMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Hint",
      requestType = com.jervis.contracts.orchestrator.VoiceHintRequest.class,
      responseType = com.jervis.contracts.orchestrator.VoiceHintResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.VoiceHintRequest,
      com.jervis.contracts.orchestrator.VoiceHintResponse> getHintMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.VoiceHintRequest, com.jervis.contracts.orchestrator.VoiceHintResponse> getHintMethod;
    if ((getHintMethod = OrchestratorVoiceServiceGrpc.getHintMethod) == null) {
      synchronized (OrchestratorVoiceServiceGrpc.class) {
        if ((getHintMethod = OrchestratorVoiceServiceGrpc.getHintMethod) == null) {
          OrchestratorVoiceServiceGrpc.getHintMethod = getHintMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.VoiceHintRequest, com.jervis.contracts.orchestrator.VoiceHintResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Hint"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.VoiceHintRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.VoiceHintResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorVoiceServiceMethodDescriptorSupplier("Hint"))
              .build();
        }
      }
    }
    return getHintMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorVoiceServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorVoiceServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorVoiceServiceStub>() {
        @java.lang.Override
        public OrchestratorVoiceServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorVoiceServiceStub(channel, callOptions);
        }
      };
    return OrchestratorVoiceServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static OrchestratorVoiceServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorVoiceServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorVoiceServiceBlockingV2Stub>() {
        @java.lang.Override
        public OrchestratorVoiceServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorVoiceServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return OrchestratorVoiceServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorVoiceServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorVoiceServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorVoiceServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorVoiceServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorVoiceServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorVoiceServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorVoiceServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorVoiceServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorVoiceServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorVoiceServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorVoiceServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorVoiceServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorVoiceService — post-transcription voice pipeline. Process
   * streams the intent-classified agentic reply. Hint returns a KB-based
   * live-assist hint (single unary response).
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void process(com.jervis.contracts.orchestrator.VoiceProcessRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VoiceStreamEvent> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getProcessMethod(), responseObserver);
    }

    /**
     */
    default void hint(com.jervis.contracts.orchestrator.VoiceHintRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VoiceHintResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHintMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorVoiceService.
   * <pre>
   * OrchestratorVoiceService — post-transcription voice pipeline. Process
   * streams the intent-classified agentic reply. Hint returns a KB-based
   * live-assist hint (single unary response).
   * </pre>
   */
  public static abstract class OrchestratorVoiceServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorVoiceServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorVoiceService.
   * <pre>
   * OrchestratorVoiceService — post-transcription voice pipeline. Process
   * streams the intent-classified agentic reply. Hint returns a KB-based
   * live-assist hint (single unary response).
   * </pre>
   */
  public static final class OrchestratorVoiceServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorVoiceServiceStub> {
    private OrchestratorVoiceServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorVoiceServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorVoiceServiceStub(channel, callOptions);
    }

    /**
     */
    public void process(com.jervis.contracts.orchestrator.VoiceProcessRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VoiceStreamEvent> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getProcessMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void hint(com.jervis.contracts.orchestrator.VoiceHintRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VoiceHintResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHintMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorVoiceService.
   * <pre>
   * OrchestratorVoiceService — post-transcription voice pipeline. Process
   * streams the intent-classified agentic reply. Hint returns a KB-based
   * live-assist hint (single unary response).
   * </pre>
   */
  public static final class OrchestratorVoiceServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorVoiceServiceBlockingV2Stub> {
    private OrchestratorVoiceServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorVoiceServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorVoiceServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, com.jervis.contracts.orchestrator.VoiceStreamEvent>
        process(com.jervis.contracts.orchestrator.VoiceProcessRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getProcessMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.VoiceHintResponse hint(com.jervis.contracts.orchestrator.VoiceHintRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getHintMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service OrchestratorVoiceService.
   * <pre>
   * OrchestratorVoiceService — post-transcription voice pipeline. Process
   * streams the intent-classified agentic reply. Hint returns a KB-based
   * live-assist hint (single unary response).
   * </pre>
   */
  public static final class OrchestratorVoiceServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorVoiceServiceBlockingStub> {
    private OrchestratorVoiceServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorVoiceServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorVoiceServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public java.util.Iterator<com.jervis.contracts.orchestrator.VoiceStreamEvent> process(
        com.jervis.contracts.orchestrator.VoiceProcessRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getProcessMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.VoiceHintResponse hint(com.jervis.contracts.orchestrator.VoiceHintRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHintMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorVoiceService.
   * <pre>
   * OrchestratorVoiceService — post-transcription voice pipeline. Process
   * streams the intent-classified agentic reply. Hint returns a KB-based
   * live-assist hint (single unary response).
   * </pre>
   */
  public static final class OrchestratorVoiceServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorVoiceServiceFutureStub> {
    private OrchestratorVoiceServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorVoiceServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorVoiceServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.VoiceHintResponse> hint(
        com.jervis.contracts.orchestrator.VoiceHintRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHintMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_PROCESS = 0;
  private static final int METHODID_HINT = 1;

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
        case METHODID_PROCESS:
          serviceImpl.process((com.jervis.contracts.orchestrator.VoiceProcessRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VoiceStreamEvent>) responseObserver);
          break;
        case METHODID_HINT:
          serviceImpl.hint((com.jervis.contracts.orchestrator.VoiceHintRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.VoiceHintResponse>) responseObserver);
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
          getProcessMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.VoiceProcessRequest,
              com.jervis.contracts.orchestrator.VoiceStreamEvent>(
                service, METHODID_PROCESS)))
        .addMethod(
          getHintMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.VoiceHintRequest,
              com.jervis.contracts.orchestrator.VoiceHintResponse>(
                service, METHODID_HINT)))
        .build();
  }

  private static abstract class OrchestratorVoiceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorVoiceServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorVoiceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorVoiceService");
    }
  }

  private static final class OrchestratorVoiceServiceFileDescriptorSupplier
      extends OrchestratorVoiceServiceBaseDescriptorSupplier {
    OrchestratorVoiceServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorVoiceServiceMethodDescriptorSupplier
      extends OrchestratorVoiceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorVoiceServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorVoiceServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorVoiceServiceFileDescriptorSupplier())
              .addMethod(getProcessMethod())
              .addMethod(getHintMethod())
              .build();
        }
      }
    }
    return result;
  }
}
