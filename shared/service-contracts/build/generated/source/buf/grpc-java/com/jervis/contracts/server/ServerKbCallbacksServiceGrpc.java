package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerKbCallbacksService hosts the reverse callbacks the async KB
 * ingest pipeline pushes into the Kotlin server. KB-progress streams
 * step-level events during ingest; KB-done signals completion (either
 * success with extraction outputs or terminal error). The result tree
 * has intentionally wide legacy routing hints — see
 * memory/architecture-kb-no-qualification.md for why the server no
 * longer branches on them.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerKbCallbacksServiceGrpc {

  private ServerKbCallbacksServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerKbCallbacksService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.KbProgressRequest,
      com.jervis.contracts.server.AckResponse> getKbProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "KbProgress",
      requestType = com.jervis.contracts.server.KbProgressRequest.class,
      responseType = com.jervis.contracts.server.AckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.KbProgressRequest,
      com.jervis.contracts.server.AckResponse> getKbProgressMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.KbProgressRequest, com.jervis.contracts.server.AckResponse> getKbProgressMethod;
    if ((getKbProgressMethod = ServerKbCallbacksServiceGrpc.getKbProgressMethod) == null) {
      synchronized (ServerKbCallbacksServiceGrpc.class) {
        if ((getKbProgressMethod = ServerKbCallbacksServiceGrpc.getKbProgressMethod) == null) {
          ServerKbCallbacksServiceGrpc.getKbProgressMethod = getKbProgressMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.KbProgressRequest, com.jervis.contracts.server.AckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "KbProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.KbProgressRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerKbCallbacksServiceMethodDescriptorSupplier("KbProgress"))
              .build();
        }
      }
    }
    return getKbProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.KbDoneRequest,
      com.jervis.contracts.server.AckResponse> getKbDoneMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "KbDone",
      requestType = com.jervis.contracts.server.KbDoneRequest.class,
      responseType = com.jervis.contracts.server.AckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.KbDoneRequest,
      com.jervis.contracts.server.AckResponse> getKbDoneMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.KbDoneRequest, com.jervis.contracts.server.AckResponse> getKbDoneMethod;
    if ((getKbDoneMethod = ServerKbCallbacksServiceGrpc.getKbDoneMethod) == null) {
      synchronized (ServerKbCallbacksServiceGrpc.class) {
        if ((getKbDoneMethod = ServerKbCallbacksServiceGrpc.getKbDoneMethod) == null) {
          ServerKbCallbacksServiceGrpc.getKbDoneMethod = getKbDoneMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.KbDoneRequest, com.jervis.contracts.server.AckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "KbDone"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.KbDoneRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerKbCallbacksServiceMethodDescriptorSupplier("KbDone"))
              .build();
        }
      }
    }
    return getKbDoneMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerKbCallbacksServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerKbCallbacksServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerKbCallbacksServiceStub>() {
        @java.lang.Override
        public ServerKbCallbacksServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerKbCallbacksServiceStub(channel, callOptions);
        }
      };
    return ServerKbCallbacksServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerKbCallbacksServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerKbCallbacksServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerKbCallbacksServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerKbCallbacksServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerKbCallbacksServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerKbCallbacksServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerKbCallbacksServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerKbCallbacksServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerKbCallbacksServiceBlockingStub>() {
        @java.lang.Override
        public ServerKbCallbacksServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerKbCallbacksServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerKbCallbacksServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerKbCallbacksServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerKbCallbacksServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerKbCallbacksServiceFutureStub>() {
        @java.lang.Override
        public ServerKbCallbacksServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerKbCallbacksServiceFutureStub(channel, callOptions);
        }
      };
    return ServerKbCallbacksServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerKbCallbacksService hosts the reverse callbacks the async KB
   * ingest pipeline pushes into the Kotlin server. KB-progress streams
   * step-level events during ingest; KB-done signals completion (either
   * success with extraction outputs or terminal error). The result tree
   * has intentionally wide legacy routing hints — see
   * memory/architecture-kb-no-qualification.md for why the server no
   * longer branches on them.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void kbProgress(com.jervis.contracts.server.KbProgressRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getKbProgressMethod(), responseObserver);
    }

    /**
     */
    default void kbDone(com.jervis.contracts.server.KbDoneRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getKbDoneMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerKbCallbacksService.
   * <pre>
   * ServerKbCallbacksService hosts the reverse callbacks the async KB
   * ingest pipeline pushes into the Kotlin server. KB-progress streams
   * step-level events during ingest; KB-done signals completion (either
   * success with extraction outputs or terminal error). The result tree
   * has intentionally wide legacy routing hints — see
   * memory/architecture-kb-no-qualification.md for why the server no
   * longer branches on them.
   * </pre>
   */
  public static abstract class ServerKbCallbacksServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerKbCallbacksServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerKbCallbacksService.
   * <pre>
   * ServerKbCallbacksService hosts the reverse callbacks the async KB
   * ingest pipeline pushes into the Kotlin server. KB-progress streams
   * step-level events during ingest; KB-done signals completion (either
   * success with extraction outputs or terminal error). The result tree
   * has intentionally wide legacy routing hints — see
   * memory/architecture-kb-no-qualification.md for why the server no
   * longer branches on them.
   * </pre>
   */
  public static final class ServerKbCallbacksServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerKbCallbacksServiceStub> {
    private ServerKbCallbacksServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerKbCallbacksServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerKbCallbacksServiceStub(channel, callOptions);
    }

    /**
     */
    public void kbProgress(com.jervis.contracts.server.KbProgressRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getKbProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void kbDone(com.jervis.contracts.server.KbDoneRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getKbDoneMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerKbCallbacksService.
   * <pre>
   * ServerKbCallbacksService hosts the reverse callbacks the async KB
   * ingest pipeline pushes into the Kotlin server. KB-progress streams
   * step-level events during ingest; KB-done signals completion (either
   * success with extraction outputs or terminal error). The result tree
   * has intentionally wide legacy routing hints — see
   * memory/architecture-kb-no-qualification.md for why the server no
   * longer branches on them.
   * </pre>
   */
  public static final class ServerKbCallbacksServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerKbCallbacksServiceBlockingV2Stub> {
    private ServerKbCallbacksServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerKbCallbacksServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerKbCallbacksServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse kbProgress(com.jervis.contracts.server.KbProgressRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getKbProgressMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse kbDone(com.jervis.contracts.server.KbDoneRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getKbDoneMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerKbCallbacksService.
   * <pre>
   * ServerKbCallbacksService hosts the reverse callbacks the async KB
   * ingest pipeline pushes into the Kotlin server. KB-progress streams
   * step-level events during ingest; KB-done signals completion (either
   * success with extraction outputs or terminal error). The result tree
   * has intentionally wide legacy routing hints — see
   * memory/architecture-kb-no-qualification.md for why the server no
   * longer branches on them.
   * </pre>
   */
  public static final class ServerKbCallbacksServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerKbCallbacksServiceBlockingStub> {
    private ServerKbCallbacksServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerKbCallbacksServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerKbCallbacksServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse kbProgress(com.jervis.contracts.server.KbProgressRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getKbProgressMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AckResponse kbDone(com.jervis.contracts.server.KbDoneRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getKbDoneMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerKbCallbacksService.
   * <pre>
   * ServerKbCallbacksService hosts the reverse callbacks the async KB
   * ingest pipeline pushes into the Kotlin server. KB-progress streams
   * step-level events during ingest; KB-done signals completion (either
   * success with extraction outputs or terminal error). The result tree
   * has intentionally wide legacy routing hints — see
   * memory/architecture-kb-no-qualification.md for why the server no
   * longer branches on them.
   * </pre>
   */
  public static final class ServerKbCallbacksServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerKbCallbacksServiceFutureStub> {
    private ServerKbCallbacksServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerKbCallbacksServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerKbCallbacksServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AckResponse> kbProgress(
        com.jervis.contracts.server.KbProgressRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getKbProgressMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AckResponse> kbDone(
        com.jervis.contracts.server.KbDoneRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getKbDoneMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_KB_PROGRESS = 0;
  private static final int METHODID_KB_DONE = 1;

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
        case METHODID_KB_PROGRESS:
          serviceImpl.kbProgress((com.jervis.contracts.server.KbProgressRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse>) responseObserver);
          break;
        case METHODID_KB_DONE:
          serviceImpl.kbDone((com.jervis.contracts.server.KbDoneRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AckResponse>) responseObserver);
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
          getKbProgressMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.KbProgressRequest,
              com.jervis.contracts.server.AckResponse>(
                service, METHODID_KB_PROGRESS)))
        .addMethod(
          getKbDoneMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.KbDoneRequest,
              com.jervis.contracts.server.AckResponse>(
                service, METHODID_KB_DONE)))
        .build();
  }

  private static abstract class ServerKbCallbacksServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerKbCallbacksServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerKbCallbacksProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerKbCallbacksService");
    }
  }

  private static final class ServerKbCallbacksServiceFileDescriptorSupplier
      extends ServerKbCallbacksServiceBaseDescriptorSupplier {
    ServerKbCallbacksServiceFileDescriptorSupplier() {}
  }

  private static final class ServerKbCallbacksServiceMethodDescriptorSupplier
      extends ServerKbCallbacksServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerKbCallbacksServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerKbCallbacksServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerKbCallbacksServiceFileDescriptorSupplier())
              .addMethod(getKbProgressMethod())
              .addMethod(getKbDoneMethod())
              .build();
        }
      }
    }
    return result;
  }
}
