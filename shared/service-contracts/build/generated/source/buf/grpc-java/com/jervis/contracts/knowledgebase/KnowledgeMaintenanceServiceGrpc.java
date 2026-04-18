package com.jervis.contracts.knowledgebase;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * KnowledgeMaintenanceService — cursor-driven maintenance batches + retag
 * operations. RunBatch is called by Kotlin BackgroundEngine during GPU idle
 * time; RetagProject + RetagGroup are invoked during project merge / group
 * reassignment flows. Progress for long-running RunBatch streams back via
 * ServerKbCallbacksService.KbProgress on the callback channel.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class KnowledgeMaintenanceServiceGrpc {

  private KnowledgeMaintenanceServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.knowledgebase.KnowledgeMaintenanceService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.MaintenanceBatchRequest,
      com.jervis.contracts.knowledgebase.MaintenanceBatchResult> getRunBatchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RunBatch",
      requestType = com.jervis.contracts.knowledgebase.MaintenanceBatchRequest.class,
      responseType = com.jervis.contracts.knowledgebase.MaintenanceBatchResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.MaintenanceBatchRequest,
      com.jervis.contracts.knowledgebase.MaintenanceBatchResult> getRunBatchMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.MaintenanceBatchRequest, com.jervis.contracts.knowledgebase.MaintenanceBatchResult> getRunBatchMethod;
    if ((getRunBatchMethod = KnowledgeMaintenanceServiceGrpc.getRunBatchMethod) == null) {
      synchronized (KnowledgeMaintenanceServiceGrpc.class) {
        if ((getRunBatchMethod = KnowledgeMaintenanceServiceGrpc.getRunBatchMethod) == null) {
          KnowledgeMaintenanceServiceGrpc.getRunBatchMethod = getRunBatchMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.MaintenanceBatchRequest, com.jervis.contracts.knowledgebase.MaintenanceBatchResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RunBatch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.MaintenanceBatchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.MaintenanceBatchResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeMaintenanceServiceMethodDescriptorSupplier("RunBatch"))
              .build();
        }
      }
    }
    return getRunBatchMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetagProjectRequest,
      com.jervis.contracts.knowledgebase.RetagResult> getRetagProjectMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RetagProject",
      requestType = com.jervis.contracts.knowledgebase.RetagProjectRequest.class,
      responseType = com.jervis.contracts.knowledgebase.RetagResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetagProjectRequest,
      com.jervis.contracts.knowledgebase.RetagResult> getRetagProjectMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetagProjectRequest, com.jervis.contracts.knowledgebase.RetagResult> getRetagProjectMethod;
    if ((getRetagProjectMethod = KnowledgeMaintenanceServiceGrpc.getRetagProjectMethod) == null) {
      synchronized (KnowledgeMaintenanceServiceGrpc.class) {
        if ((getRetagProjectMethod = KnowledgeMaintenanceServiceGrpc.getRetagProjectMethod) == null) {
          KnowledgeMaintenanceServiceGrpc.getRetagProjectMethod = getRetagProjectMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.RetagProjectRequest, com.jervis.contracts.knowledgebase.RetagResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RetagProject"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.RetagProjectRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.RetagResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeMaintenanceServiceMethodDescriptorSupplier("RetagProject"))
              .build();
        }
      }
    }
    return getRetagProjectMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetagGroupRequest,
      com.jervis.contracts.knowledgebase.RetagResult> getRetagGroupMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RetagGroup",
      requestType = com.jervis.contracts.knowledgebase.RetagGroupRequest.class,
      responseType = com.jervis.contracts.knowledgebase.RetagResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetagGroupRequest,
      com.jervis.contracts.knowledgebase.RetagResult> getRetagGroupMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetagGroupRequest, com.jervis.contracts.knowledgebase.RetagResult> getRetagGroupMethod;
    if ((getRetagGroupMethod = KnowledgeMaintenanceServiceGrpc.getRetagGroupMethod) == null) {
      synchronized (KnowledgeMaintenanceServiceGrpc.class) {
        if ((getRetagGroupMethod = KnowledgeMaintenanceServiceGrpc.getRetagGroupMethod) == null) {
          KnowledgeMaintenanceServiceGrpc.getRetagGroupMethod = getRetagGroupMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.RetagGroupRequest, com.jervis.contracts.knowledgebase.RetagResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RetagGroup"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.RetagGroupRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.RetagResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeMaintenanceServiceMethodDescriptorSupplier("RetagGroup"))
              .build();
        }
      }
    }
    return getRetagGroupMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static KnowledgeMaintenanceServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeMaintenanceServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeMaintenanceServiceStub>() {
        @java.lang.Override
        public KnowledgeMaintenanceServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeMaintenanceServiceStub(channel, callOptions);
        }
      };
    return KnowledgeMaintenanceServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static KnowledgeMaintenanceServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeMaintenanceServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeMaintenanceServiceBlockingV2Stub>() {
        @java.lang.Override
        public KnowledgeMaintenanceServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeMaintenanceServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return KnowledgeMaintenanceServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static KnowledgeMaintenanceServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeMaintenanceServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeMaintenanceServiceBlockingStub>() {
        @java.lang.Override
        public KnowledgeMaintenanceServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeMaintenanceServiceBlockingStub(channel, callOptions);
        }
      };
    return KnowledgeMaintenanceServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static KnowledgeMaintenanceServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeMaintenanceServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeMaintenanceServiceFutureStub>() {
        @java.lang.Override
        public KnowledgeMaintenanceServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeMaintenanceServiceFutureStub(channel, callOptions);
        }
      };
    return KnowledgeMaintenanceServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * KnowledgeMaintenanceService — cursor-driven maintenance batches + retag
   * operations. RunBatch is called by Kotlin BackgroundEngine during GPU idle
   * time; RetagProject + RetagGroup are invoked during project merge / group
   * reassignment flows. Progress for long-running RunBatch streams back via
   * ServerKbCallbacksService.KbProgress on the callback channel.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void runBatch(com.jervis.contracts.knowledgebase.MaintenanceBatchRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.MaintenanceBatchResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRunBatchMethod(), responseObserver);
    }

    /**
     */
    default void retagProject(com.jervis.contracts.knowledgebase.RetagProjectRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.RetagResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRetagProjectMethod(), responseObserver);
    }

    /**
     */
    default void retagGroup(com.jervis.contracts.knowledgebase.RetagGroupRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.RetagResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRetagGroupMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service KnowledgeMaintenanceService.
   * <pre>
   * KnowledgeMaintenanceService — cursor-driven maintenance batches + retag
   * operations. RunBatch is called by Kotlin BackgroundEngine during GPU idle
   * time; RetagProject + RetagGroup are invoked during project merge / group
   * reassignment flows. Progress for long-running RunBatch streams back via
   * ServerKbCallbacksService.KbProgress on the callback channel.
   * </pre>
   */
  public static abstract class KnowledgeMaintenanceServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return KnowledgeMaintenanceServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service KnowledgeMaintenanceService.
   * <pre>
   * KnowledgeMaintenanceService — cursor-driven maintenance batches + retag
   * operations. RunBatch is called by Kotlin BackgroundEngine during GPU idle
   * time; RetagProject + RetagGroup are invoked during project merge / group
   * reassignment flows. Progress for long-running RunBatch streams back via
   * ServerKbCallbacksService.KbProgress on the callback channel.
   * </pre>
   */
  public static final class KnowledgeMaintenanceServiceStub
      extends io.grpc.stub.AbstractAsyncStub<KnowledgeMaintenanceServiceStub> {
    private KnowledgeMaintenanceServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeMaintenanceServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeMaintenanceServiceStub(channel, callOptions);
    }

    /**
     */
    public void runBatch(com.jervis.contracts.knowledgebase.MaintenanceBatchRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.MaintenanceBatchResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRunBatchMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void retagProject(com.jervis.contracts.knowledgebase.RetagProjectRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.RetagResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRetagProjectMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void retagGroup(com.jervis.contracts.knowledgebase.RetagGroupRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.RetagResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRetagGroupMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service KnowledgeMaintenanceService.
   * <pre>
   * KnowledgeMaintenanceService — cursor-driven maintenance batches + retag
   * operations. RunBatch is called by Kotlin BackgroundEngine during GPU idle
   * time; RetagProject + RetagGroup are invoked during project merge / group
   * reassignment flows. Progress for long-running RunBatch streams back via
   * ServerKbCallbacksService.KbProgress on the callback channel.
   * </pre>
   */
  public static final class KnowledgeMaintenanceServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeMaintenanceServiceBlockingV2Stub> {
    private KnowledgeMaintenanceServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeMaintenanceServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeMaintenanceServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.MaintenanceBatchResult runBatch(com.jervis.contracts.knowledgebase.MaintenanceBatchRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRunBatchMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.RetagResult retagProject(com.jervis.contracts.knowledgebase.RetagProjectRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRetagProjectMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.RetagResult retagGroup(com.jervis.contracts.knowledgebase.RetagGroupRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRetagGroupMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service KnowledgeMaintenanceService.
   * <pre>
   * KnowledgeMaintenanceService — cursor-driven maintenance batches + retag
   * operations. RunBatch is called by Kotlin BackgroundEngine during GPU idle
   * time; RetagProject + RetagGroup are invoked during project merge / group
   * reassignment flows. Progress for long-running RunBatch streams back via
   * ServerKbCallbacksService.KbProgress on the callback channel.
   * </pre>
   */
  public static final class KnowledgeMaintenanceServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeMaintenanceServiceBlockingStub> {
    private KnowledgeMaintenanceServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeMaintenanceServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeMaintenanceServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.MaintenanceBatchResult runBatch(com.jervis.contracts.knowledgebase.MaintenanceBatchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRunBatchMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.RetagResult retagProject(com.jervis.contracts.knowledgebase.RetagProjectRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRetagProjectMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.RetagResult retagGroup(com.jervis.contracts.knowledgebase.RetagGroupRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRetagGroupMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service KnowledgeMaintenanceService.
   * <pre>
   * KnowledgeMaintenanceService — cursor-driven maintenance batches + retag
   * operations. RunBatch is called by Kotlin BackgroundEngine during GPU idle
   * time; RetagProject + RetagGroup are invoked during project merge / group
   * reassignment flows. Progress for long-running RunBatch streams back via
   * ServerKbCallbacksService.KbProgress on the callback channel.
   * </pre>
   */
  public static final class KnowledgeMaintenanceServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<KnowledgeMaintenanceServiceFutureStub> {
    private KnowledgeMaintenanceServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeMaintenanceServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeMaintenanceServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.MaintenanceBatchResult> runBatch(
        com.jervis.contracts.knowledgebase.MaintenanceBatchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRunBatchMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.RetagResult> retagProject(
        com.jervis.contracts.knowledgebase.RetagProjectRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRetagProjectMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.RetagResult> retagGroup(
        com.jervis.contracts.knowledgebase.RetagGroupRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRetagGroupMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_RUN_BATCH = 0;
  private static final int METHODID_RETAG_PROJECT = 1;
  private static final int METHODID_RETAG_GROUP = 2;

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
        case METHODID_RUN_BATCH:
          serviceImpl.runBatch((com.jervis.contracts.knowledgebase.MaintenanceBatchRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.MaintenanceBatchResult>) responseObserver);
          break;
        case METHODID_RETAG_PROJECT:
          serviceImpl.retagProject((com.jervis.contracts.knowledgebase.RetagProjectRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.RetagResult>) responseObserver);
          break;
        case METHODID_RETAG_GROUP:
          serviceImpl.retagGroup((com.jervis.contracts.knowledgebase.RetagGroupRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.RetagResult>) responseObserver);
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
          getRunBatchMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.MaintenanceBatchRequest,
              com.jervis.contracts.knowledgebase.MaintenanceBatchResult>(
                service, METHODID_RUN_BATCH)))
        .addMethod(
          getRetagProjectMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.RetagProjectRequest,
              com.jervis.contracts.knowledgebase.RetagResult>(
                service, METHODID_RETAG_PROJECT)))
        .addMethod(
          getRetagGroupMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.RetagGroupRequest,
              com.jervis.contracts.knowledgebase.RetagResult>(
                service, METHODID_RETAG_GROUP)))
        .build();
  }

  private static abstract class KnowledgeMaintenanceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    KnowledgeMaintenanceServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.knowledgebase.KnowledgeMaintenanceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("KnowledgeMaintenanceService");
    }
  }

  private static final class KnowledgeMaintenanceServiceFileDescriptorSupplier
      extends KnowledgeMaintenanceServiceBaseDescriptorSupplier {
    KnowledgeMaintenanceServiceFileDescriptorSupplier() {}
  }

  private static final class KnowledgeMaintenanceServiceMethodDescriptorSupplier
      extends KnowledgeMaintenanceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    KnowledgeMaintenanceServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (KnowledgeMaintenanceServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new KnowledgeMaintenanceServiceFileDescriptorSupplier())
              .addMethod(getRunBatchMethod())
              .addMethod(getRetagProjectMethod())
              .addMethod(getRetagGroupMethod())
              .build();
        }
      }
    }
    return result;
  }
}
