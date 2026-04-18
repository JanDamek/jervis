package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorDispatchService — fire-and-forget entry points for the
 * orchestrator's long-running flows (qualify + orchestrate). Both RPCs
 * now carry fully-typed request messages — no JSON passthrough. Shape
 * mirrors the Pydantic models on the Python side
 * (app/unified/qualification_handler.py::QualifyRequest,
 * app/models.py::OrchestrateRequest) and the Kotlin DTOs
 * (QualifyRequestDto, OrchestrateRequestDto).
 * Response stays typed: thread_id + status. Progress + completion flow
 * back via the Phase-1 ServerOrchestratorCallbackService callbacks.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorDispatchServiceGrpc {

  private OrchestratorDispatchServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorDispatchService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.QualifyRequest,
      com.jervis.contracts.orchestrator.DispatchAck> getQualifyMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Qualify",
      requestType = com.jervis.contracts.orchestrator.QualifyRequest.class,
      responseType = com.jervis.contracts.orchestrator.DispatchAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.QualifyRequest,
      com.jervis.contracts.orchestrator.DispatchAck> getQualifyMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.QualifyRequest, com.jervis.contracts.orchestrator.DispatchAck> getQualifyMethod;
    if ((getQualifyMethod = OrchestratorDispatchServiceGrpc.getQualifyMethod) == null) {
      synchronized (OrchestratorDispatchServiceGrpc.class) {
        if ((getQualifyMethod = OrchestratorDispatchServiceGrpc.getQualifyMethod) == null) {
          OrchestratorDispatchServiceGrpc.getQualifyMethod = getQualifyMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.QualifyRequest, com.jervis.contracts.orchestrator.DispatchAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Qualify"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.QualifyRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.DispatchAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorDispatchServiceMethodDescriptorSupplier("Qualify"))
              .build();
        }
      }
    }
    return getQualifyMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.OrchestrateRequest,
      com.jervis.contracts.orchestrator.DispatchAck> getOrchestrateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Orchestrate",
      requestType = com.jervis.contracts.orchestrator.OrchestrateRequest.class,
      responseType = com.jervis.contracts.orchestrator.DispatchAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.OrchestrateRequest,
      com.jervis.contracts.orchestrator.DispatchAck> getOrchestrateMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.OrchestrateRequest, com.jervis.contracts.orchestrator.DispatchAck> getOrchestrateMethod;
    if ((getOrchestrateMethod = OrchestratorDispatchServiceGrpc.getOrchestrateMethod) == null) {
      synchronized (OrchestratorDispatchServiceGrpc.class) {
        if ((getOrchestrateMethod = OrchestratorDispatchServiceGrpc.getOrchestrateMethod) == null) {
          OrchestratorDispatchServiceGrpc.getOrchestrateMethod = getOrchestrateMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.OrchestrateRequest, com.jervis.contracts.orchestrator.DispatchAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Orchestrate"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.OrchestrateRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.DispatchAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorDispatchServiceMethodDescriptorSupplier("Orchestrate"))
              .build();
        }
      }
    }
    return getOrchestrateMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorDispatchServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorDispatchServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorDispatchServiceStub>() {
        @java.lang.Override
        public OrchestratorDispatchServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorDispatchServiceStub(channel, callOptions);
        }
      };
    return OrchestratorDispatchServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static OrchestratorDispatchServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorDispatchServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorDispatchServiceBlockingV2Stub>() {
        @java.lang.Override
        public OrchestratorDispatchServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorDispatchServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return OrchestratorDispatchServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorDispatchServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorDispatchServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorDispatchServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorDispatchServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorDispatchServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorDispatchServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorDispatchServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorDispatchServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorDispatchServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorDispatchServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorDispatchServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorDispatchServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorDispatchService — fire-and-forget entry points for the
   * orchestrator's long-running flows (qualify + orchestrate). Both RPCs
   * now carry fully-typed request messages — no JSON passthrough. Shape
   * mirrors the Pydantic models on the Python side
   * (app/unified/qualification_handler.py::QualifyRequest,
   * app/models.py::OrchestrateRequest) and the Kotlin DTOs
   * (QualifyRequestDto, OrchestrateRequestDto).
   * Response stays typed: thread_id + status. Progress + completion flow
   * back via the Phase-1 ServerOrchestratorCallbackService callbacks.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void qualify(com.jervis.contracts.orchestrator.QualifyRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.DispatchAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQualifyMethod(), responseObserver);
    }

    /**
     */
    default void orchestrate(com.jervis.contracts.orchestrator.OrchestrateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.DispatchAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getOrchestrateMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorDispatchService.
   * <pre>
   * OrchestratorDispatchService — fire-and-forget entry points for the
   * orchestrator's long-running flows (qualify + orchestrate). Both RPCs
   * now carry fully-typed request messages — no JSON passthrough. Shape
   * mirrors the Pydantic models on the Python side
   * (app/unified/qualification_handler.py::QualifyRequest,
   * app/models.py::OrchestrateRequest) and the Kotlin DTOs
   * (QualifyRequestDto, OrchestrateRequestDto).
   * Response stays typed: thread_id + status. Progress + completion flow
   * back via the Phase-1 ServerOrchestratorCallbackService callbacks.
   * </pre>
   */
  public static abstract class OrchestratorDispatchServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorDispatchServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorDispatchService.
   * <pre>
   * OrchestratorDispatchService — fire-and-forget entry points for the
   * orchestrator's long-running flows (qualify + orchestrate). Both RPCs
   * now carry fully-typed request messages — no JSON passthrough. Shape
   * mirrors the Pydantic models on the Python side
   * (app/unified/qualification_handler.py::QualifyRequest,
   * app/models.py::OrchestrateRequest) and the Kotlin DTOs
   * (QualifyRequestDto, OrchestrateRequestDto).
   * Response stays typed: thread_id + status. Progress + completion flow
   * back via the Phase-1 ServerOrchestratorCallbackService callbacks.
   * </pre>
   */
  public static final class OrchestratorDispatchServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorDispatchServiceStub> {
    private OrchestratorDispatchServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorDispatchServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorDispatchServiceStub(channel, callOptions);
    }

    /**
     */
    public void qualify(com.jervis.contracts.orchestrator.QualifyRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.DispatchAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getQualifyMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void orchestrate(com.jervis.contracts.orchestrator.OrchestrateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.DispatchAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getOrchestrateMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorDispatchService.
   * <pre>
   * OrchestratorDispatchService — fire-and-forget entry points for the
   * orchestrator's long-running flows (qualify + orchestrate). Both RPCs
   * now carry fully-typed request messages — no JSON passthrough. Shape
   * mirrors the Pydantic models on the Python side
   * (app/unified/qualification_handler.py::QualifyRequest,
   * app/models.py::OrchestrateRequest) and the Kotlin DTOs
   * (QualifyRequestDto, OrchestrateRequestDto).
   * Response stays typed: thread_id + status. Progress + completion flow
   * back via the Phase-1 ServerOrchestratorCallbackService callbacks.
   * </pre>
   */
  public static final class OrchestratorDispatchServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorDispatchServiceBlockingV2Stub> {
    private OrchestratorDispatchServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorDispatchServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorDispatchServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.DispatchAck qualify(com.jervis.contracts.orchestrator.QualifyRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getQualifyMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.DispatchAck orchestrate(com.jervis.contracts.orchestrator.OrchestrateRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getOrchestrateMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service OrchestratorDispatchService.
   * <pre>
   * OrchestratorDispatchService — fire-and-forget entry points for the
   * orchestrator's long-running flows (qualify + orchestrate). Both RPCs
   * now carry fully-typed request messages — no JSON passthrough. Shape
   * mirrors the Pydantic models on the Python side
   * (app/unified/qualification_handler.py::QualifyRequest,
   * app/models.py::OrchestrateRequest) and the Kotlin DTOs
   * (QualifyRequestDto, OrchestrateRequestDto).
   * Response stays typed: thread_id + status. Progress + completion flow
   * back via the Phase-1 ServerOrchestratorCallbackService callbacks.
   * </pre>
   */
  public static final class OrchestratorDispatchServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorDispatchServiceBlockingStub> {
    private OrchestratorDispatchServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorDispatchServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorDispatchServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.DispatchAck qualify(com.jervis.contracts.orchestrator.QualifyRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQualifyMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.DispatchAck orchestrate(com.jervis.contracts.orchestrator.OrchestrateRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getOrchestrateMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorDispatchService.
   * <pre>
   * OrchestratorDispatchService — fire-and-forget entry points for the
   * orchestrator's long-running flows (qualify + orchestrate). Both RPCs
   * now carry fully-typed request messages — no JSON passthrough. Shape
   * mirrors the Pydantic models on the Python side
   * (app/unified/qualification_handler.py::QualifyRequest,
   * app/models.py::OrchestrateRequest) and the Kotlin DTOs
   * (QualifyRequestDto, OrchestrateRequestDto).
   * Response stays typed: thread_id + status. Progress + completion flow
   * back via the Phase-1 ServerOrchestratorCallbackService callbacks.
   * </pre>
   */
  public static final class OrchestratorDispatchServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorDispatchServiceFutureStub> {
    private OrchestratorDispatchServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorDispatchServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorDispatchServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.DispatchAck> qualify(
        com.jervis.contracts.orchestrator.QualifyRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getQualifyMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.DispatchAck> orchestrate(
        com.jervis.contracts.orchestrator.OrchestrateRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getOrchestrateMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_QUALIFY = 0;
  private static final int METHODID_ORCHESTRATE = 1;

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
        case METHODID_QUALIFY:
          serviceImpl.qualify((com.jervis.contracts.orchestrator.QualifyRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.DispatchAck>) responseObserver);
          break;
        case METHODID_ORCHESTRATE:
          serviceImpl.orchestrate((com.jervis.contracts.orchestrator.OrchestrateRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.DispatchAck>) responseObserver);
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
          getQualifyMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.QualifyRequest,
              com.jervis.contracts.orchestrator.DispatchAck>(
                service, METHODID_QUALIFY)))
        .addMethod(
          getOrchestrateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.OrchestrateRequest,
              com.jervis.contracts.orchestrator.DispatchAck>(
                service, METHODID_ORCHESTRATE)))
        .build();
  }

  private static abstract class OrchestratorDispatchServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorDispatchServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorDispatchProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorDispatchService");
    }
  }

  private static final class OrchestratorDispatchServiceFileDescriptorSupplier
      extends OrchestratorDispatchServiceBaseDescriptorSupplier {
    OrchestratorDispatchServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorDispatchServiceMethodDescriptorSupplier
      extends OrchestratorDispatchServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorDispatchServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorDispatchServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorDispatchServiceFileDescriptorSupplier())
              .addMethod(getQualifyMethod())
              .addMethod(getOrchestrateMethod())
              .build();
        }
      }
    }
    return result;
  }
}
