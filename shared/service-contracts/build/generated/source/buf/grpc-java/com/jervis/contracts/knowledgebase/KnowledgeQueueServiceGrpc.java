package com.jervis.contracts.knowledgebase;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * KnowledgeQueueService — visibility into the asynchronous LLM extraction
 * queue. Read-only for external consumers; items are added via
 * KnowledgeIngestService.IngestQueue.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class KnowledgeQueueServiceGrpc {

  private KnowledgeQueueServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.knowledgebase.KnowledgeQueueService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.QueueListRequest,
      com.jervis.contracts.knowledgebase.QueueList> getListQueueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListQueue",
      requestType = com.jervis.contracts.knowledgebase.QueueListRequest.class,
      responseType = com.jervis.contracts.knowledgebase.QueueList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.QueueListRequest,
      com.jervis.contracts.knowledgebase.QueueList> getListQueueMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.QueueListRequest, com.jervis.contracts.knowledgebase.QueueList> getListQueueMethod;
    if ((getListQueueMethod = KnowledgeQueueServiceGrpc.getListQueueMethod) == null) {
      synchronized (KnowledgeQueueServiceGrpc.class) {
        if ((getListQueueMethod = KnowledgeQueueServiceGrpc.getListQueueMethod) == null) {
          KnowledgeQueueServiceGrpc.getListQueueMethod = getListQueueMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.QueueListRequest, com.jervis.contracts.knowledgebase.QueueList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListQueue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.QueueListRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.QueueList.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeQueueServiceMethodDescriptorSupplier("ListQueue"))
              .build();
        }
      }
    }
    return getListQueueMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static KnowledgeQueueServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeQueueServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeQueueServiceStub>() {
        @java.lang.Override
        public KnowledgeQueueServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeQueueServiceStub(channel, callOptions);
        }
      };
    return KnowledgeQueueServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static KnowledgeQueueServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeQueueServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeQueueServiceBlockingV2Stub>() {
        @java.lang.Override
        public KnowledgeQueueServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeQueueServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return KnowledgeQueueServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static KnowledgeQueueServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeQueueServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeQueueServiceBlockingStub>() {
        @java.lang.Override
        public KnowledgeQueueServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeQueueServiceBlockingStub(channel, callOptions);
        }
      };
    return KnowledgeQueueServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static KnowledgeQueueServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeQueueServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeQueueServiceFutureStub>() {
        @java.lang.Override
        public KnowledgeQueueServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeQueueServiceFutureStub(channel, callOptions);
        }
      };
    return KnowledgeQueueServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * KnowledgeQueueService — visibility into the asynchronous LLM extraction
   * queue. Read-only for external consumers; items are added via
   * KnowledgeIngestService.IngestQueue.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void listQueue(com.jervis.contracts.knowledgebase.QueueListRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.QueueList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListQueueMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service KnowledgeQueueService.
   * <pre>
   * KnowledgeQueueService — visibility into the asynchronous LLM extraction
   * queue. Read-only for external consumers; items are added via
   * KnowledgeIngestService.IngestQueue.
   * </pre>
   */
  public static abstract class KnowledgeQueueServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return KnowledgeQueueServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service KnowledgeQueueService.
   * <pre>
   * KnowledgeQueueService — visibility into the asynchronous LLM extraction
   * queue. Read-only for external consumers; items are added via
   * KnowledgeIngestService.IngestQueue.
   * </pre>
   */
  public static final class KnowledgeQueueServiceStub
      extends io.grpc.stub.AbstractAsyncStub<KnowledgeQueueServiceStub> {
    private KnowledgeQueueServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeQueueServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeQueueServiceStub(channel, callOptions);
    }

    /**
     */
    public void listQueue(com.jervis.contracts.knowledgebase.QueueListRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.QueueList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListQueueMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service KnowledgeQueueService.
   * <pre>
   * KnowledgeQueueService — visibility into the asynchronous LLM extraction
   * queue. Read-only for external consumers; items are added via
   * KnowledgeIngestService.IngestQueue.
   * </pre>
   */
  public static final class KnowledgeQueueServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeQueueServiceBlockingV2Stub> {
    private KnowledgeQueueServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeQueueServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeQueueServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.QueueList listQueue(com.jervis.contracts.knowledgebase.QueueListRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListQueueMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service KnowledgeQueueService.
   * <pre>
   * KnowledgeQueueService — visibility into the asynchronous LLM extraction
   * queue. Read-only for external consumers; items are added via
   * KnowledgeIngestService.IngestQueue.
   * </pre>
   */
  public static final class KnowledgeQueueServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeQueueServiceBlockingStub> {
    private KnowledgeQueueServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeQueueServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeQueueServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.QueueList listQueue(com.jervis.contracts.knowledgebase.QueueListRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListQueueMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service KnowledgeQueueService.
   * <pre>
   * KnowledgeQueueService — visibility into the asynchronous LLM extraction
   * queue. Read-only for external consumers; items are added via
   * KnowledgeIngestService.IngestQueue.
   * </pre>
   */
  public static final class KnowledgeQueueServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<KnowledgeQueueServiceFutureStub> {
    private KnowledgeQueueServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeQueueServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeQueueServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.QueueList> listQueue(
        com.jervis.contracts.knowledgebase.QueueListRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListQueueMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LIST_QUEUE = 0;

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
        case METHODID_LIST_QUEUE:
          serviceImpl.listQueue((com.jervis.contracts.knowledgebase.QueueListRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.QueueList>) responseObserver);
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
          getListQueueMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.QueueListRequest,
              com.jervis.contracts.knowledgebase.QueueList>(
                service, METHODID_LIST_QUEUE)))
        .build();
  }

  private static abstract class KnowledgeQueueServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    KnowledgeQueueServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.knowledgebase.KnowledgeQueueProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("KnowledgeQueueService");
    }
  }

  private static final class KnowledgeQueueServiceFileDescriptorSupplier
      extends KnowledgeQueueServiceBaseDescriptorSupplier {
    KnowledgeQueueServiceFileDescriptorSupplier() {}
  }

  private static final class KnowledgeQueueServiceMethodDescriptorSupplier
      extends KnowledgeQueueServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    KnowledgeQueueServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (KnowledgeQueueServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new KnowledgeQueueServiceFileDescriptorSupplier())
              .addMethod(getListQueueMethod())
              .build();
        }
      }
    }
    return result;
  }
}
