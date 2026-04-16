package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerGitService — git repository lifecycle (create via GitHub/GitLab
 * API, clone into the project workspace). Called by MCP tools.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerGitServiceGrpc {

  private ServerGitServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerGitService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateRepositoryRequest,
      com.jervis.contracts.server.CreateRepositoryResponse> getCreateRepositoryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateRepository",
      requestType = com.jervis.contracts.server.CreateRepositoryRequest.class,
      responseType = com.jervis.contracts.server.CreateRepositoryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateRepositoryRequest,
      com.jervis.contracts.server.CreateRepositoryResponse> getCreateRepositoryMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateRepositoryRequest, com.jervis.contracts.server.CreateRepositoryResponse> getCreateRepositoryMethod;
    if ((getCreateRepositoryMethod = ServerGitServiceGrpc.getCreateRepositoryMethod) == null) {
      synchronized (ServerGitServiceGrpc.class) {
        if ((getCreateRepositoryMethod = ServerGitServiceGrpc.getCreateRepositoryMethod) == null) {
          ServerGitServiceGrpc.getCreateRepositoryMethod = getCreateRepositoryMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateRepositoryRequest, com.jervis.contracts.server.CreateRepositoryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateRepository"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateRepositoryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateRepositoryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerGitServiceMethodDescriptorSupplier("CreateRepository"))
              .build();
        }
      }
    }
    return getCreateRepositoryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.InitWorkspaceRequest,
      com.jervis.contracts.server.InitWorkspaceResponse> getInitWorkspaceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "InitWorkspace",
      requestType = com.jervis.contracts.server.InitWorkspaceRequest.class,
      responseType = com.jervis.contracts.server.InitWorkspaceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.InitWorkspaceRequest,
      com.jervis.contracts.server.InitWorkspaceResponse> getInitWorkspaceMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.InitWorkspaceRequest, com.jervis.contracts.server.InitWorkspaceResponse> getInitWorkspaceMethod;
    if ((getInitWorkspaceMethod = ServerGitServiceGrpc.getInitWorkspaceMethod) == null) {
      synchronized (ServerGitServiceGrpc.class) {
        if ((getInitWorkspaceMethod = ServerGitServiceGrpc.getInitWorkspaceMethod) == null) {
          ServerGitServiceGrpc.getInitWorkspaceMethod = getInitWorkspaceMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.InitWorkspaceRequest, com.jervis.contracts.server.InitWorkspaceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "InitWorkspace"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.InitWorkspaceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.InitWorkspaceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerGitServiceMethodDescriptorSupplier("InitWorkspace"))
              .build();
        }
      }
    }
    return getInitWorkspaceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.WorkspaceStatusRequest,
      com.jervis.contracts.server.WorkspaceStatusResponse> getGetWorkspaceStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetWorkspaceStatus",
      requestType = com.jervis.contracts.server.WorkspaceStatusRequest.class,
      responseType = com.jervis.contracts.server.WorkspaceStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.WorkspaceStatusRequest,
      com.jervis.contracts.server.WorkspaceStatusResponse> getGetWorkspaceStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.WorkspaceStatusRequest, com.jervis.contracts.server.WorkspaceStatusResponse> getGetWorkspaceStatusMethod;
    if ((getGetWorkspaceStatusMethod = ServerGitServiceGrpc.getGetWorkspaceStatusMethod) == null) {
      synchronized (ServerGitServiceGrpc.class) {
        if ((getGetWorkspaceStatusMethod = ServerGitServiceGrpc.getGetWorkspaceStatusMethod) == null) {
          ServerGitServiceGrpc.getGetWorkspaceStatusMethod = getGetWorkspaceStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.WorkspaceStatusRequest, com.jervis.contracts.server.WorkspaceStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetWorkspaceStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WorkspaceStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WorkspaceStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerGitServiceMethodDescriptorSupplier("GetWorkspaceStatus"))
              .build();
        }
      }
    }
    return getGetWorkspaceStatusMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerGitServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGitServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGitServiceStub>() {
        @java.lang.Override
        public ServerGitServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGitServiceStub(channel, callOptions);
        }
      };
    return ServerGitServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerGitServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGitServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGitServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerGitServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGitServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerGitServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerGitServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGitServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGitServiceBlockingStub>() {
        @java.lang.Override
        public ServerGitServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGitServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerGitServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerGitServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerGitServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerGitServiceFutureStub>() {
        @java.lang.Override
        public ServerGitServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerGitServiceFutureStub(channel, callOptions);
        }
      };
    return ServerGitServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerGitService — git repository lifecycle (create via GitHub/GitLab
   * API, clone into the project workspace). Called by MCP tools.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void createRepository(com.jervis.contracts.server.CreateRepositoryRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateRepositoryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateRepositoryMethod(), responseObserver);
    }

    /**
     */
    default void initWorkspace(com.jervis.contracts.server.InitWorkspaceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.InitWorkspaceResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInitWorkspaceMethod(), responseObserver);
    }

    /**
     */
    default void getWorkspaceStatus(com.jervis.contracts.server.WorkspaceStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WorkspaceStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetWorkspaceStatusMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerGitService.
   * <pre>
   * ServerGitService — git repository lifecycle (create via GitHub/GitLab
   * API, clone into the project workspace). Called by MCP tools.
   * </pre>
   */
  public static abstract class ServerGitServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerGitServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerGitService.
   * <pre>
   * ServerGitService — git repository lifecycle (create via GitHub/GitLab
   * API, clone into the project workspace). Called by MCP tools.
   * </pre>
   */
  public static final class ServerGitServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerGitServiceStub> {
    private ServerGitServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGitServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGitServiceStub(channel, callOptions);
    }

    /**
     */
    public void createRepository(com.jervis.contracts.server.CreateRepositoryRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateRepositoryResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateRepositoryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void initWorkspace(com.jervis.contracts.server.InitWorkspaceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.InitWorkspaceResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInitWorkspaceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getWorkspaceStatus(com.jervis.contracts.server.WorkspaceStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WorkspaceStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetWorkspaceStatusMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerGitService.
   * <pre>
   * ServerGitService — git repository lifecycle (create via GitHub/GitLab
   * API, clone into the project workspace). Called by MCP tools.
   * </pre>
   */
  public static final class ServerGitServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerGitServiceBlockingV2Stub> {
    private ServerGitServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGitServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGitServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.CreateRepositoryResponse createRepository(com.jervis.contracts.server.CreateRepositoryRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateRepositoryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.InitWorkspaceResponse initWorkspace(com.jervis.contracts.server.InitWorkspaceRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInitWorkspaceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.WorkspaceStatusResponse getWorkspaceStatus(com.jervis.contracts.server.WorkspaceStatusRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetWorkspaceStatusMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerGitService.
   * <pre>
   * ServerGitService — git repository lifecycle (create via GitHub/GitLab
   * API, clone into the project workspace). Called by MCP tools.
   * </pre>
   */
  public static final class ServerGitServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerGitServiceBlockingStub> {
    private ServerGitServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGitServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGitServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.CreateRepositoryResponse createRepository(com.jervis.contracts.server.CreateRepositoryRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateRepositoryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.InitWorkspaceResponse initWorkspace(com.jervis.contracts.server.InitWorkspaceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInitWorkspaceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.WorkspaceStatusResponse getWorkspaceStatus(com.jervis.contracts.server.WorkspaceStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetWorkspaceStatusMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerGitService.
   * <pre>
   * ServerGitService — git repository lifecycle (create via GitHub/GitLab
   * API, clone into the project workspace). Called by MCP tools.
   * </pre>
   */
  public static final class ServerGitServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerGitServiceFutureStub> {
    private ServerGitServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerGitServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerGitServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CreateRepositoryResponse> createRepository(
        com.jervis.contracts.server.CreateRepositoryRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateRepositoryMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.InitWorkspaceResponse> initWorkspace(
        com.jervis.contracts.server.InitWorkspaceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInitWorkspaceMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.WorkspaceStatusResponse> getWorkspaceStatus(
        com.jervis.contracts.server.WorkspaceStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetWorkspaceStatusMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CREATE_REPOSITORY = 0;
  private static final int METHODID_INIT_WORKSPACE = 1;
  private static final int METHODID_GET_WORKSPACE_STATUS = 2;

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
        case METHODID_CREATE_REPOSITORY:
          serviceImpl.createRepository((com.jervis.contracts.server.CreateRepositoryRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateRepositoryResponse>) responseObserver);
          break;
        case METHODID_INIT_WORKSPACE:
          serviceImpl.initWorkspace((com.jervis.contracts.server.InitWorkspaceRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.InitWorkspaceResponse>) responseObserver);
          break;
        case METHODID_GET_WORKSPACE_STATUS:
          serviceImpl.getWorkspaceStatus((com.jervis.contracts.server.WorkspaceStatusRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.WorkspaceStatusResponse>) responseObserver);
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
          getCreateRepositoryMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateRepositoryRequest,
              com.jervis.contracts.server.CreateRepositoryResponse>(
                service, METHODID_CREATE_REPOSITORY)))
        .addMethod(
          getInitWorkspaceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.InitWorkspaceRequest,
              com.jervis.contracts.server.InitWorkspaceResponse>(
                service, METHODID_INIT_WORKSPACE)))
        .addMethod(
          getGetWorkspaceStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.WorkspaceStatusRequest,
              com.jervis.contracts.server.WorkspaceStatusResponse>(
                service, METHODID_GET_WORKSPACE_STATUS)))
        .build();
  }

  private static abstract class ServerGitServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerGitServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerGitProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerGitService");
    }
  }

  private static final class ServerGitServiceFileDescriptorSupplier
      extends ServerGitServiceBaseDescriptorSupplier {
    ServerGitServiceFileDescriptorSupplier() {}
  }

  private static final class ServerGitServiceMethodDescriptorSupplier
      extends ServerGitServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerGitServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerGitServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerGitServiceFileDescriptorSupplier())
              .addMethod(getCreateRepositoryMethod())
              .addMethod(getInitWorkspaceMethod())
              .addMethod(getGetWorkspaceStatusMethod())
              .build();
        }
      }
    }
    return result;
  }
}
