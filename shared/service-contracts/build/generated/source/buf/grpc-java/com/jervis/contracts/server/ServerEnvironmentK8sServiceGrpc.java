package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerEnvironmentK8sService exposes per-namespace Kubernetes inspection
 * ops (pods, deployments, logs, scale/restart) used by the MCP server's
 * K8s tools. The EnvironmentResourceService already owns the allow-list
 * guard; pod logs are a plain text passthrough so the response stays a
 * string field.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerEnvironmentK8sServiceGrpc {

  private ServerEnvironmentK8sServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerEnvironmentK8sService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListNamespaceResourcesRequest,
      com.jervis.contracts.server.ListNamespaceResourcesResponse> getListNamespaceResourcesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListNamespaceResources",
      requestType = com.jervis.contracts.server.ListNamespaceResourcesRequest.class,
      responseType = com.jervis.contracts.server.ListNamespaceResourcesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListNamespaceResourcesRequest,
      com.jervis.contracts.server.ListNamespaceResourcesResponse> getListNamespaceResourcesMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListNamespaceResourcesRequest, com.jervis.contracts.server.ListNamespaceResourcesResponse> getListNamespaceResourcesMethod;
    if ((getListNamespaceResourcesMethod = ServerEnvironmentK8sServiceGrpc.getListNamespaceResourcesMethod) == null) {
      synchronized (ServerEnvironmentK8sServiceGrpc.class) {
        if ((getListNamespaceResourcesMethod = ServerEnvironmentK8sServiceGrpc.getListNamespaceResourcesMethod) == null) {
          ServerEnvironmentK8sServiceGrpc.getListNamespaceResourcesMethod = getListNamespaceResourcesMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListNamespaceResourcesRequest, com.jervis.contracts.server.ListNamespaceResourcesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListNamespaceResources"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListNamespaceResourcesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListNamespaceResourcesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentK8sServiceMethodDescriptorSupplier("ListNamespaceResources"))
              .build();
        }
      }
    }
    return getListNamespaceResourcesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetPodLogsRequest,
      com.jervis.contracts.server.GetPodLogsResponse> getGetPodLogsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetPodLogs",
      requestType = com.jervis.contracts.server.GetPodLogsRequest.class,
      responseType = com.jervis.contracts.server.GetPodLogsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetPodLogsRequest,
      com.jervis.contracts.server.GetPodLogsResponse> getGetPodLogsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetPodLogsRequest, com.jervis.contracts.server.GetPodLogsResponse> getGetPodLogsMethod;
    if ((getGetPodLogsMethod = ServerEnvironmentK8sServiceGrpc.getGetPodLogsMethod) == null) {
      synchronized (ServerEnvironmentK8sServiceGrpc.class) {
        if ((getGetPodLogsMethod = ServerEnvironmentK8sServiceGrpc.getGetPodLogsMethod) == null) {
          ServerEnvironmentK8sServiceGrpc.getGetPodLogsMethod = getGetPodLogsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetPodLogsRequest, com.jervis.contracts.server.GetPodLogsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetPodLogs"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetPodLogsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetPodLogsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentK8sServiceMethodDescriptorSupplier("GetPodLogs"))
              .build();
        }
      }
    }
    return getGetPodLogsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetDeploymentStatusRequest,
      com.jervis.contracts.server.GetDeploymentStatusResponse> getGetDeploymentStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetDeploymentStatus",
      requestType = com.jervis.contracts.server.GetDeploymentStatusRequest.class,
      responseType = com.jervis.contracts.server.GetDeploymentStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetDeploymentStatusRequest,
      com.jervis.contracts.server.GetDeploymentStatusResponse> getGetDeploymentStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetDeploymentStatusRequest, com.jervis.contracts.server.GetDeploymentStatusResponse> getGetDeploymentStatusMethod;
    if ((getGetDeploymentStatusMethod = ServerEnvironmentK8sServiceGrpc.getGetDeploymentStatusMethod) == null) {
      synchronized (ServerEnvironmentK8sServiceGrpc.class) {
        if ((getGetDeploymentStatusMethod = ServerEnvironmentK8sServiceGrpc.getGetDeploymentStatusMethod) == null) {
          ServerEnvironmentK8sServiceGrpc.getGetDeploymentStatusMethod = getGetDeploymentStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetDeploymentStatusRequest, com.jervis.contracts.server.GetDeploymentStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetDeploymentStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetDeploymentStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetDeploymentStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentK8sServiceMethodDescriptorSupplier("GetDeploymentStatus"))
              .build();
        }
      }
    }
    return getGetDeploymentStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ScaleDeploymentRequest,
      com.jervis.contracts.server.ScaleDeploymentResponse> getScaleDeploymentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ScaleDeployment",
      requestType = com.jervis.contracts.server.ScaleDeploymentRequest.class,
      responseType = com.jervis.contracts.server.ScaleDeploymentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ScaleDeploymentRequest,
      com.jervis.contracts.server.ScaleDeploymentResponse> getScaleDeploymentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ScaleDeploymentRequest, com.jervis.contracts.server.ScaleDeploymentResponse> getScaleDeploymentMethod;
    if ((getScaleDeploymentMethod = ServerEnvironmentK8sServiceGrpc.getScaleDeploymentMethod) == null) {
      synchronized (ServerEnvironmentK8sServiceGrpc.class) {
        if ((getScaleDeploymentMethod = ServerEnvironmentK8sServiceGrpc.getScaleDeploymentMethod) == null) {
          ServerEnvironmentK8sServiceGrpc.getScaleDeploymentMethod = getScaleDeploymentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ScaleDeploymentRequest, com.jervis.contracts.server.ScaleDeploymentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ScaleDeployment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ScaleDeploymentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ScaleDeploymentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentK8sServiceMethodDescriptorSupplier("ScaleDeployment"))
              .build();
        }
      }
    }
    return getScaleDeploymentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.RestartDeploymentRequest,
      com.jervis.contracts.server.RestartDeploymentResponse> getRestartDeploymentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RestartDeployment",
      requestType = com.jervis.contracts.server.RestartDeploymentRequest.class,
      responseType = com.jervis.contracts.server.RestartDeploymentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.RestartDeploymentRequest,
      com.jervis.contracts.server.RestartDeploymentResponse> getRestartDeploymentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.RestartDeploymentRequest, com.jervis.contracts.server.RestartDeploymentResponse> getRestartDeploymentMethod;
    if ((getRestartDeploymentMethod = ServerEnvironmentK8sServiceGrpc.getRestartDeploymentMethod) == null) {
      synchronized (ServerEnvironmentK8sServiceGrpc.class) {
        if ((getRestartDeploymentMethod = ServerEnvironmentK8sServiceGrpc.getRestartDeploymentMethod) == null) {
          ServerEnvironmentK8sServiceGrpc.getRestartDeploymentMethod = getRestartDeploymentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.RestartDeploymentRequest, com.jervis.contracts.server.RestartDeploymentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RestartDeployment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.RestartDeploymentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.RestartDeploymentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentK8sServiceMethodDescriptorSupplier("RestartDeployment"))
              .build();
        }
      }
    }
    return getRestartDeploymentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetNamespaceStatusRequest,
      com.jervis.contracts.server.GetNamespaceStatusResponse> getGetNamespaceStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetNamespaceStatus",
      requestType = com.jervis.contracts.server.GetNamespaceStatusRequest.class,
      responseType = com.jervis.contracts.server.GetNamespaceStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetNamespaceStatusRequest,
      com.jervis.contracts.server.GetNamespaceStatusResponse> getGetNamespaceStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetNamespaceStatusRequest, com.jervis.contracts.server.GetNamespaceStatusResponse> getGetNamespaceStatusMethod;
    if ((getGetNamespaceStatusMethod = ServerEnvironmentK8sServiceGrpc.getGetNamespaceStatusMethod) == null) {
      synchronized (ServerEnvironmentK8sServiceGrpc.class) {
        if ((getGetNamespaceStatusMethod = ServerEnvironmentK8sServiceGrpc.getGetNamespaceStatusMethod) == null) {
          ServerEnvironmentK8sServiceGrpc.getGetNamespaceStatusMethod = getGetNamespaceStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetNamespaceStatusRequest, com.jervis.contracts.server.GetNamespaceStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetNamespaceStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetNamespaceStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetNamespaceStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentK8sServiceMethodDescriptorSupplier("GetNamespaceStatus"))
              .build();
        }
      }
    }
    return getGetNamespaceStatusMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerEnvironmentK8sServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentK8sServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentK8sServiceStub>() {
        @java.lang.Override
        public ServerEnvironmentK8sServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerEnvironmentK8sServiceStub(channel, callOptions);
        }
      };
    return ServerEnvironmentK8sServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerEnvironmentK8sServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentK8sServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentK8sServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerEnvironmentK8sServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerEnvironmentK8sServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerEnvironmentK8sServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerEnvironmentK8sServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentK8sServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentK8sServiceBlockingStub>() {
        @java.lang.Override
        public ServerEnvironmentK8sServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerEnvironmentK8sServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerEnvironmentK8sServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerEnvironmentK8sServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentK8sServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentK8sServiceFutureStub>() {
        @java.lang.Override
        public ServerEnvironmentK8sServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerEnvironmentK8sServiceFutureStub(channel, callOptions);
        }
      };
    return ServerEnvironmentK8sServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerEnvironmentK8sService exposes per-namespace Kubernetes inspection
   * ops (pods, deployments, logs, scale/restart) used by the MCP server's
   * K8s tools. The EnvironmentResourceService already owns the allow-list
   * guard; pod logs are a plain text passthrough so the response stays a
   * string field.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void listNamespaceResources(com.jervis.contracts.server.ListNamespaceResourcesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListNamespaceResourcesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListNamespaceResourcesMethod(), responseObserver);
    }

    /**
     */
    default void getPodLogs(com.jervis.contracts.server.GetPodLogsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetPodLogsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetPodLogsMethod(), responseObserver);
    }

    /**
     */
    default void getDeploymentStatus(com.jervis.contracts.server.GetDeploymentStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetDeploymentStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetDeploymentStatusMethod(), responseObserver);
    }

    /**
     */
    default void scaleDeployment(com.jervis.contracts.server.ScaleDeploymentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ScaleDeploymentResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getScaleDeploymentMethod(), responseObserver);
    }

    /**
     */
    default void restartDeployment(com.jervis.contracts.server.RestartDeploymentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.RestartDeploymentResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRestartDeploymentMethod(), responseObserver);
    }

    /**
     */
    default void getNamespaceStatus(com.jervis.contracts.server.GetNamespaceStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetNamespaceStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetNamespaceStatusMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerEnvironmentK8sService.
   * <pre>
   * ServerEnvironmentK8sService exposes per-namespace Kubernetes inspection
   * ops (pods, deployments, logs, scale/restart) used by the MCP server's
   * K8s tools. The EnvironmentResourceService already owns the allow-list
   * guard; pod logs are a plain text passthrough so the response stays a
   * string field.
   * </pre>
   */
  public static abstract class ServerEnvironmentK8sServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerEnvironmentK8sServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerEnvironmentK8sService.
   * <pre>
   * ServerEnvironmentK8sService exposes per-namespace Kubernetes inspection
   * ops (pods, deployments, logs, scale/restart) used by the MCP server's
   * K8s tools. The EnvironmentResourceService already owns the allow-list
   * guard; pod logs are a plain text passthrough so the response stays a
   * string field.
   * </pre>
   */
  public static final class ServerEnvironmentK8sServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerEnvironmentK8sServiceStub> {
    private ServerEnvironmentK8sServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerEnvironmentK8sServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerEnvironmentK8sServiceStub(channel, callOptions);
    }

    /**
     */
    public void listNamespaceResources(com.jervis.contracts.server.ListNamespaceResourcesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListNamespaceResourcesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListNamespaceResourcesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getPodLogs(com.jervis.contracts.server.GetPodLogsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetPodLogsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetPodLogsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getDeploymentStatus(com.jervis.contracts.server.GetDeploymentStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetDeploymentStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetDeploymentStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void scaleDeployment(com.jervis.contracts.server.ScaleDeploymentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ScaleDeploymentResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getScaleDeploymentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void restartDeployment(com.jervis.contracts.server.RestartDeploymentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.RestartDeploymentResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRestartDeploymentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getNamespaceStatus(com.jervis.contracts.server.GetNamespaceStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetNamespaceStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetNamespaceStatusMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerEnvironmentK8sService.
   * <pre>
   * ServerEnvironmentK8sService exposes per-namespace Kubernetes inspection
   * ops (pods, deployments, logs, scale/restart) used by the MCP server's
   * K8s tools. The EnvironmentResourceService already owns the allow-list
   * guard; pod logs are a plain text passthrough so the response stays a
   * string field.
   * </pre>
   */
  public static final class ServerEnvironmentK8sServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerEnvironmentK8sServiceBlockingV2Stub> {
    private ServerEnvironmentK8sServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerEnvironmentK8sServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerEnvironmentK8sServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ListNamespaceResourcesResponse listNamespaceResources(com.jervis.contracts.server.ListNamespaceResourcesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListNamespaceResourcesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetPodLogsResponse getPodLogs(com.jervis.contracts.server.GetPodLogsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetPodLogsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetDeploymentStatusResponse getDeploymentStatus(com.jervis.contracts.server.GetDeploymentStatusRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetDeploymentStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ScaleDeploymentResponse scaleDeployment(com.jervis.contracts.server.ScaleDeploymentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getScaleDeploymentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.RestartDeploymentResponse restartDeployment(com.jervis.contracts.server.RestartDeploymentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRestartDeploymentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetNamespaceStatusResponse getNamespaceStatus(com.jervis.contracts.server.GetNamespaceStatusRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetNamespaceStatusMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerEnvironmentK8sService.
   * <pre>
   * ServerEnvironmentK8sService exposes per-namespace Kubernetes inspection
   * ops (pods, deployments, logs, scale/restart) used by the MCP server's
   * K8s tools. The EnvironmentResourceService already owns the allow-list
   * guard; pod logs are a plain text passthrough so the response stays a
   * string field.
   * </pre>
   */
  public static final class ServerEnvironmentK8sServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerEnvironmentK8sServiceBlockingStub> {
    private ServerEnvironmentK8sServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerEnvironmentK8sServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerEnvironmentK8sServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ListNamespaceResourcesResponse listNamespaceResources(com.jervis.contracts.server.ListNamespaceResourcesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListNamespaceResourcesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetPodLogsResponse getPodLogs(com.jervis.contracts.server.GetPodLogsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetPodLogsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetDeploymentStatusResponse getDeploymentStatus(com.jervis.contracts.server.GetDeploymentStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetDeploymentStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ScaleDeploymentResponse scaleDeployment(com.jervis.contracts.server.ScaleDeploymentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getScaleDeploymentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.RestartDeploymentResponse restartDeployment(com.jervis.contracts.server.RestartDeploymentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRestartDeploymentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetNamespaceStatusResponse getNamespaceStatus(com.jervis.contracts.server.GetNamespaceStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetNamespaceStatusMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerEnvironmentK8sService.
   * <pre>
   * ServerEnvironmentK8sService exposes per-namespace Kubernetes inspection
   * ops (pods, deployments, logs, scale/restart) used by the MCP server's
   * K8s tools. The EnvironmentResourceService already owns the allow-list
   * guard; pod logs are a plain text passthrough so the response stays a
   * string field.
   * </pre>
   */
  public static final class ServerEnvironmentK8sServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerEnvironmentK8sServiceFutureStub> {
    private ServerEnvironmentK8sServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerEnvironmentK8sServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerEnvironmentK8sServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListNamespaceResourcesResponse> listNamespaceResources(
        com.jervis.contracts.server.ListNamespaceResourcesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListNamespaceResourcesMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetPodLogsResponse> getPodLogs(
        com.jervis.contracts.server.GetPodLogsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetPodLogsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetDeploymentStatusResponse> getDeploymentStatus(
        com.jervis.contracts.server.GetDeploymentStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetDeploymentStatusMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ScaleDeploymentResponse> scaleDeployment(
        com.jervis.contracts.server.ScaleDeploymentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getScaleDeploymentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.RestartDeploymentResponse> restartDeployment(
        com.jervis.contracts.server.RestartDeploymentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRestartDeploymentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetNamespaceStatusResponse> getNamespaceStatus(
        com.jervis.contracts.server.GetNamespaceStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetNamespaceStatusMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LIST_NAMESPACE_RESOURCES = 0;
  private static final int METHODID_GET_POD_LOGS = 1;
  private static final int METHODID_GET_DEPLOYMENT_STATUS = 2;
  private static final int METHODID_SCALE_DEPLOYMENT = 3;
  private static final int METHODID_RESTART_DEPLOYMENT = 4;
  private static final int METHODID_GET_NAMESPACE_STATUS = 5;

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
        case METHODID_LIST_NAMESPACE_RESOURCES:
          serviceImpl.listNamespaceResources((com.jervis.contracts.server.ListNamespaceResourcesRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListNamespaceResourcesResponse>) responseObserver);
          break;
        case METHODID_GET_POD_LOGS:
          serviceImpl.getPodLogs((com.jervis.contracts.server.GetPodLogsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetPodLogsResponse>) responseObserver);
          break;
        case METHODID_GET_DEPLOYMENT_STATUS:
          serviceImpl.getDeploymentStatus((com.jervis.contracts.server.GetDeploymentStatusRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetDeploymentStatusResponse>) responseObserver);
          break;
        case METHODID_SCALE_DEPLOYMENT:
          serviceImpl.scaleDeployment((com.jervis.contracts.server.ScaleDeploymentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ScaleDeploymentResponse>) responseObserver);
          break;
        case METHODID_RESTART_DEPLOYMENT:
          serviceImpl.restartDeployment((com.jervis.contracts.server.RestartDeploymentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.RestartDeploymentResponse>) responseObserver);
          break;
        case METHODID_GET_NAMESPACE_STATUS:
          serviceImpl.getNamespaceStatus((com.jervis.contracts.server.GetNamespaceStatusRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetNamespaceStatusResponse>) responseObserver);
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
          getListNamespaceResourcesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListNamespaceResourcesRequest,
              com.jervis.contracts.server.ListNamespaceResourcesResponse>(
                service, METHODID_LIST_NAMESPACE_RESOURCES)))
        .addMethod(
          getGetPodLogsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetPodLogsRequest,
              com.jervis.contracts.server.GetPodLogsResponse>(
                service, METHODID_GET_POD_LOGS)))
        .addMethod(
          getGetDeploymentStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetDeploymentStatusRequest,
              com.jervis.contracts.server.GetDeploymentStatusResponse>(
                service, METHODID_GET_DEPLOYMENT_STATUS)))
        .addMethod(
          getScaleDeploymentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ScaleDeploymentRequest,
              com.jervis.contracts.server.ScaleDeploymentResponse>(
                service, METHODID_SCALE_DEPLOYMENT)))
        .addMethod(
          getRestartDeploymentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.RestartDeploymentRequest,
              com.jervis.contracts.server.RestartDeploymentResponse>(
                service, METHODID_RESTART_DEPLOYMENT)))
        .addMethod(
          getGetNamespaceStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetNamespaceStatusRequest,
              com.jervis.contracts.server.GetNamespaceStatusResponse>(
                service, METHODID_GET_NAMESPACE_STATUS)))
        .build();
  }

  private static abstract class ServerEnvironmentK8sServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerEnvironmentK8sServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerEnvironmentK8sProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerEnvironmentK8sService");
    }
  }

  private static final class ServerEnvironmentK8sServiceFileDescriptorSupplier
      extends ServerEnvironmentK8sServiceBaseDescriptorSupplier {
    ServerEnvironmentK8sServiceFileDescriptorSupplier() {}
  }

  private static final class ServerEnvironmentK8sServiceMethodDescriptorSupplier
      extends ServerEnvironmentK8sServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerEnvironmentK8sServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerEnvironmentK8sServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerEnvironmentK8sServiceFileDescriptorSupplier())
              .addMethod(getListNamespaceResourcesMethod())
              .addMethod(getGetPodLogsMethod())
              .addMethod(getGetDeploymentStatusMethod())
              .addMethod(getScaleDeploymentMethod())
              .addMethod(getRestartDeploymentMethod())
              .addMethod(getGetNamespaceStatusMethod())
              .build();
        }
      }
    }
    return result;
  }
}
