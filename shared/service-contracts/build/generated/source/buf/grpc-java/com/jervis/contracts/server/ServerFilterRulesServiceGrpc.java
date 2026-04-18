package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerFilterRulesService exposes the filtering-rules CRUD surface used
 * by the orchestrator agent's tool set. Rule documents mirror the
 * kotlinx-serialized `shared/common-dto` FilteringRule typed 1:1.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerFilterRulesServiceGrpc {

  private ServerFilterRulesServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerFilterRulesService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateFilterRuleRequest,
      com.jervis.contracts.server.FilterRule> getCreateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Create",
      requestType = com.jervis.contracts.server.CreateFilterRuleRequest.class,
      responseType = com.jervis.contracts.server.FilterRule.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateFilterRuleRequest,
      com.jervis.contracts.server.FilterRule> getCreateMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateFilterRuleRequest, com.jervis.contracts.server.FilterRule> getCreateMethod;
    if ((getCreateMethod = ServerFilterRulesServiceGrpc.getCreateMethod) == null) {
      synchronized (ServerFilterRulesServiceGrpc.class) {
        if ((getCreateMethod = ServerFilterRulesServiceGrpc.getCreateMethod) == null) {
          ServerFilterRulesServiceGrpc.getCreateMethod = getCreateMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateFilterRuleRequest, com.jervis.contracts.server.FilterRule>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Create"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateFilterRuleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.FilterRule.getDefaultInstance()))
              .setSchemaDescriptor(new ServerFilterRulesServiceMethodDescriptorSupplier("Create"))
              .build();
        }
      }
    }
    return getCreateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListFilterRulesRequest,
      com.jervis.contracts.server.FilterRuleList> getListMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "List",
      requestType = com.jervis.contracts.server.ListFilterRulesRequest.class,
      responseType = com.jervis.contracts.server.FilterRuleList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListFilterRulesRequest,
      com.jervis.contracts.server.FilterRuleList> getListMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListFilterRulesRequest, com.jervis.contracts.server.FilterRuleList> getListMethod;
    if ((getListMethod = ServerFilterRulesServiceGrpc.getListMethod) == null) {
      synchronized (ServerFilterRulesServiceGrpc.class) {
        if ((getListMethod = ServerFilterRulesServiceGrpc.getListMethod) == null) {
          ServerFilterRulesServiceGrpc.getListMethod = getListMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListFilterRulesRequest, com.jervis.contracts.server.FilterRuleList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "List"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListFilterRulesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.FilterRuleList.getDefaultInstance()))
              .setSchemaDescriptor(new ServerFilterRulesServiceMethodDescriptorSupplier("List"))
              .build();
        }
      }
    }
    return getListMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.RemoveFilterRuleRequest,
      com.jervis.contracts.server.RemoveFilterRuleResponse> getRemoveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Remove",
      requestType = com.jervis.contracts.server.RemoveFilterRuleRequest.class,
      responseType = com.jervis.contracts.server.RemoveFilterRuleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.RemoveFilterRuleRequest,
      com.jervis.contracts.server.RemoveFilterRuleResponse> getRemoveMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.RemoveFilterRuleRequest, com.jervis.contracts.server.RemoveFilterRuleResponse> getRemoveMethod;
    if ((getRemoveMethod = ServerFilterRulesServiceGrpc.getRemoveMethod) == null) {
      synchronized (ServerFilterRulesServiceGrpc.class) {
        if ((getRemoveMethod = ServerFilterRulesServiceGrpc.getRemoveMethod) == null) {
          ServerFilterRulesServiceGrpc.getRemoveMethod = getRemoveMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.RemoveFilterRuleRequest, com.jervis.contracts.server.RemoveFilterRuleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Remove"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.RemoveFilterRuleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.RemoveFilterRuleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerFilterRulesServiceMethodDescriptorSupplier("Remove"))
              .build();
        }
      }
    }
    return getRemoveMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerFilterRulesServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerFilterRulesServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerFilterRulesServiceStub>() {
        @java.lang.Override
        public ServerFilterRulesServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerFilterRulesServiceStub(channel, callOptions);
        }
      };
    return ServerFilterRulesServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerFilterRulesServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerFilterRulesServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerFilterRulesServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerFilterRulesServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerFilterRulesServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerFilterRulesServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerFilterRulesServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerFilterRulesServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerFilterRulesServiceBlockingStub>() {
        @java.lang.Override
        public ServerFilterRulesServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerFilterRulesServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerFilterRulesServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerFilterRulesServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerFilterRulesServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerFilterRulesServiceFutureStub>() {
        @java.lang.Override
        public ServerFilterRulesServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerFilterRulesServiceFutureStub(channel, callOptions);
        }
      };
    return ServerFilterRulesServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerFilterRulesService exposes the filtering-rules CRUD surface used
   * by the orchestrator agent's tool set. Rule documents mirror the
   * kotlinx-serialized `shared/common-dto` FilteringRule typed 1:1.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void create(com.jervis.contracts.server.CreateFilterRuleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.FilterRule> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateMethod(), responseObserver);
    }

    /**
     */
    default void list(com.jervis.contracts.server.ListFilterRulesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.FilterRuleList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListMethod(), responseObserver);
    }

    /**
     */
    default void remove(com.jervis.contracts.server.RemoveFilterRuleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.RemoveFilterRuleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRemoveMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerFilterRulesService.
   * <pre>
   * ServerFilterRulesService exposes the filtering-rules CRUD surface used
   * by the orchestrator agent's tool set. Rule documents mirror the
   * kotlinx-serialized `shared/common-dto` FilteringRule typed 1:1.
   * </pre>
   */
  public static abstract class ServerFilterRulesServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerFilterRulesServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerFilterRulesService.
   * <pre>
   * ServerFilterRulesService exposes the filtering-rules CRUD surface used
   * by the orchestrator agent's tool set. Rule documents mirror the
   * kotlinx-serialized `shared/common-dto` FilteringRule typed 1:1.
   * </pre>
   */
  public static final class ServerFilterRulesServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerFilterRulesServiceStub> {
    private ServerFilterRulesServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerFilterRulesServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerFilterRulesServiceStub(channel, callOptions);
    }

    /**
     */
    public void create(com.jervis.contracts.server.CreateFilterRuleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.FilterRule> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void list(com.jervis.contracts.server.ListFilterRulesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.FilterRuleList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void remove(com.jervis.contracts.server.RemoveFilterRuleRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.RemoveFilterRuleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRemoveMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerFilterRulesService.
   * <pre>
   * ServerFilterRulesService exposes the filtering-rules CRUD surface used
   * by the orchestrator agent's tool set. Rule documents mirror the
   * kotlinx-serialized `shared/common-dto` FilteringRule typed 1:1.
   * </pre>
   */
  public static final class ServerFilterRulesServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerFilterRulesServiceBlockingV2Stub> {
    private ServerFilterRulesServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerFilterRulesServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerFilterRulesServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.FilterRule create(com.jervis.contracts.server.CreateFilterRuleRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.FilterRuleList list(com.jervis.contracts.server.ListFilterRulesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.RemoveFilterRuleResponse remove(com.jervis.contracts.server.RemoveFilterRuleRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRemoveMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerFilterRulesService.
   * <pre>
   * ServerFilterRulesService exposes the filtering-rules CRUD surface used
   * by the orchestrator agent's tool set. Rule documents mirror the
   * kotlinx-serialized `shared/common-dto` FilteringRule typed 1:1.
   * </pre>
   */
  public static final class ServerFilterRulesServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerFilterRulesServiceBlockingStub> {
    private ServerFilterRulesServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerFilterRulesServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerFilterRulesServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.FilterRule create(com.jervis.contracts.server.CreateFilterRuleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.FilterRuleList list(com.jervis.contracts.server.ListFilterRulesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.RemoveFilterRuleResponse remove(com.jervis.contracts.server.RemoveFilterRuleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRemoveMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerFilterRulesService.
   * <pre>
   * ServerFilterRulesService exposes the filtering-rules CRUD surface used
   * by the orchestrator agent's tool set. Rule documents mirror the
   * kotlinx-serialized `shared/common-dto` FilteringRule typed 1:1.
   * </pre>
   */
  public static final class ServerFilterRulesServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerFilterRulesServiceFutureStub> {
    private ServerFilterRulesServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerFilterRulesServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerFilterRulesServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.FilterRule> create(
        com.jervis.contracts.server.CreateFilterRuleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.FilterRuleList> list(
        com.jervis.contracts.server.ListFilterRulesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.RemoveFilterRuleResponse> remove(
        com.jervis.contracts.server.RemoveFilterRuleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRemoveMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CREATE = 0;
  private static final int METHODID_LIST = 1;
  private static final int METHODID_REMOVE = 2;

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
        case METHODID_CREATE:
          serviceImpl.create((com.jervis.contracts.server.CreateFilterRuleRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.FilterRule>) responseObserver);
          break;
        case METHODID_LIST:
          serviceImpl.list((com.jervis.contracts.server.ListFilterRulesRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.FilterRuleList>) responseObserver);
          break;
        case METHODID_REMOVE:
          serviceImpl.remove((com.jervis.contracts.server.RemoveFilterRuleRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.RemoveFilterRuleResponse>) responseObserver);
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
          getCreateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateFilterRuleRequest,
              com.jervis.contracts.server.FilterRule>(
                service, METHODID_CREATE)))
        .addMethod(
          getListMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListFilterRulesRequest,
              com.jervis.contracts.server.FilterRuleList>(
                service, METHODID_LIST)))
        .addMethod(
          getRemoveMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.RemoveFilterRuleRequest,
              com.jervis.contracts.server.RemoveFilterRuleResponse>(
                service, METHODID_REMOVE)))
        .build();
  }

  private static abstract class ServerFilterRulesServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerFilterRulesServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerFilterRulesProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerFilterRulesService");
    }
  }

  private static final class ServerFilterRulesServiceFileDescriptorSupplier
      extends ServerFilterRulesServiceBaseDescriptorSupplier {
    ServerFilterRulesServiceFileDescriptorSupplier() {}
  }

  private static final class ServerFilterRulesServiceMethodDescriptorSupplier
      extends ServerFilterRulesServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerFilterRulesServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerFilterRulesServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerFilterRulesServiceFileDescriptorSupplier())
              .addMethod(getCreateMethod())
              .addMethod(getListMethod())
              .addMethod(getRemoveMethod())
              .build();
        }
      }
    }
    return result;
  }
}
