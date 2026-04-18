package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorJobLogsService — live K8s Job pod log streaming.
 * Replaces the former FastAPI GET /job-logs/{task_id} SSE endpoint.
 * The Kotlin server's kRPC JobLogsRpcImpl dials StreamLogs and
 * forwards the JobLogEvent flow to the UI.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.0)",
    comments = "Source: jervis/orchestrator/job_logs.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorJobLogsServiceGrpc {

  private OrchestratorJobLogsServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorJobLogsService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.JobLogsRequest,
      com.jervis.contracts.orchestrator.JobLogEvent> getStreamLogsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamLogs",
      requestType = com.jervis.contracts.orchestrator.JobLogsRequest.class,
      responseType = com.jervis.contracts.orchestrator.JobLogEvent.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.JobLogsRequest,
      com.jervis.contracts.orchestrator.JobLogEvent> getStreamLogsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.JobLogsRequest, com.jervis.contracts.orchestrator.JobLogEvent> getStreamLogsMethod;
    if ((getStreamLogsMethod = OrchestratorJobLogsServiceGrpc.getStreamLogsMethod) == null) {
      synchronized (OrchestratorJobLogsServiceGrpc.class) {
        if ((getStreamLogsMethod = OrchestratorJobLogsServiceGrpc.getStreamLogsMethod) == null) {
          OrchestratorJobLogsServiceGrpc.getStreamLogsMethod = getStreamLogsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.JobLogsRequest, com.jervis.contracts.orchestrator.JobLogEvent>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamLogs"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.JobLogsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.JobLogEvent.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorJobLogsServiceMethodDescriptorSupplier("StreamLogs"))
              .build();
        }
      }
    }
    return getStreamLogsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorJobLogsServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorJobLogsServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorJobLogsServiceStub>() {
        @java.lang.Override
        public OrchestratorJobLogsServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorJobLogsServiceStub(channel, callOptions);
        }
      };
    return OrchestratorJobLogsServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorJobLogsServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorJobLogsServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorJobLogsServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorJobLogsServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorJobLogsServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorJobLogsServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorJobLogsServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorJobLogsServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorJobLogsServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorJobLogsServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorJobLogsServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorJobLogsServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorJobLogsService — live K8s Job pod log streaming.
   * Replaces the former FastAPI GET /job-logs/{task_id} SSE endpoint.
   * The Kotlin server's kRPC JobLogsRpcImpl dials StreamLogs and
   * forwards the JobLogEvent flow to the UI.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void streamLogs(com.jervis.contracts.orchestrator.JobLogsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.JobLogEvent> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStreamLogsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorJobLogsService.
   * <pre>
   * OrchestratorJobLogsService — live K8s Job pod log streaming.
   * Replaces the former FastAPI GET /job-logs/{task_id} SSE endpoint.
   * The Kotlin server's kRPC JobLogsRpcImpl dials StreamLogs and
   * forwards the JobLogEvent flow to the UI.
   * </pre>
   */
  public static abstract class OrchestratorJobLogsServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorJobLogsServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorJobLogsService.
   * <pre>
   * OrchestratorJobLogsService — live K8s Job pod log streaming.
   * Replaces the former FastAPI GET /job-logs/{task_id} SSE endpoint.
   * The Kotlin server's kRPC JobLogsRpcImpl dials StreamLogs and
   * forwards the JobLogEvent flow to the UI.
   * </pre>
   */
  public static final class OrchestratorJobLogsServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorJobLogsServiceStub> {
    private OrchestratorJobLogsServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorJobLogsServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorJobLogsServiceStub(channel, callOptions);
    }

    /**
     */
    public void streamLogs(com.jervis.contracts.orchestrator.JobLogsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.JobLogEvent> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getStreamLogsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorJobLogsService.
   * <pre>
   * OrchestratorJobLogsService — live K8s Job pod log streaming.
   * Replaces the former FastAPI GET /job-logs/{task_id} SSE endpoint.
   * The Kotlin server's kRPC JobLogsRpcImpl dials StreamLogs and
   * forwards the JobLogEvent flow to the UI.
   * </pre>
   */
  public static final class OrchestratorJobLogsServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorJobLogsServiceBlockingStub> {
    private OrchestratorJobLogsServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorJobLogsServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorJobLogsServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public java.util.Iterator<com.jervis.contracts.orchestrator.JobLogEvent> streamLogs(
        com.jervis.contracts.orchestrator.JobLogsRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getStreamLogsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorJobLogsService.
   * <pre>
   * OrchestratorJobLogsService — live K8s Job pod log streaming.
   * Replaces the former FastAPI GET /job-logs/{task_id} SSE endpoint.
   * The Kotlin server's kRPC JobLogsRpcImpl dials StreamLogs and
   * forwards the JobLogEvent flow to the UI.
   * </pre>
   */
  public static final class OrchestratorJobLogsServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorJobLogsServiceFutureStub> {
    private OrchestratorJobLogsServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorJobLogsServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorJobLogsServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_STREAM_LOGS = 0;

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
        case METHODID_STREAM_LOGS:
          serviceImpl.streamLogs((com.jervis.contracts.orchestrator.JobLogsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.JobLogEvent>) responseObserver);
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
          getStreamLogsMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.JobLogsRequest,
              com.jervis.contracts.orchestrator.JobLogEvent>(
                service, METHODID_STREAM_LOGS)))
        .build();
  }

  private static abstract class OrchestratorJobLogsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorJobLogsServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorJobLogsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorJobLogsService");
    }
  }

  private static final class OrchestratorJobLogsServiceFileDescriptorSupplier
      extends OrchestratorJobLogsServiceBaseDescriptorSupplier {
    OrchestratorJobLogsServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorJobLogsServiceMethodDescriptorSupplier
      extends OrchestratorJobLogsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorJobLogsServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorJobLogsServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorJobLogsServiceFileDescriptorSupplier())
              .addMethod(getStreamLogsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
