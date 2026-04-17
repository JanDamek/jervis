package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerProjectManagementService is the pod-to-pod surface for creating
 * and listing clients, projects and connections. List responses carry the
 * full DTO tree as JSON (`items_json`) because Python callers only read
 * a handful of surface fields (id, name, provider, capabilities). Create
 * responses stay typed — Python confirms id/name/provider only.
 * The `project-advisor/archetypes` endpoint had no Python consumer and is
 * dropped; recommendations remain available as typed JSON (the orchestrator
 * formats deeply-nested fields for the LLM prompt).
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerProjectManagementServiceGrpc {

  private ServerProjectManagementServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerProjectManagementService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListClientsRequest,
      com.jervis.contracts.server.ListClientsResponse> getListClientsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListClients",
      requestType = com.jervis.contracts.server.ListClientsRequest.class,
      responseType = com.jervis.contracts.server.ListClientsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListClientsRequest,
      com.jervis.contracts.server.ListClientsResponse> getListClientsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListClientsRequest, com.jervis.contracts.server.ListClientsResponse> getListClientsMethod;
    if ((getListClientsMethod = ServerProjectManagementServiceGrpc.getListClientsMethod) == null) {
      synchronized (ServerProjectManagementServiceGrpc.class) {
        if ((getListClientsMethod = ServerProjectManagementServiceGrpc.getListClientsMethod) == null) {
          ServerProjectManagementServiceGrpc.getListClientsMethod = getListClientsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListClientsRequest, com.jervis.contracts.server.ListClientsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListClients"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListClientsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListClientsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProjectManagementServiceMethodDescriptorSupplier("ListClients"))
              .build();
        }
      }
    }
    return getListClientsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateClientRequest,
      com.jervis.contracts.server.CreateClientResponse> getCreateClientMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateClient",
      requestType = com.jervis.contracts.server.CreateClientRequest.class,
      responseType = com.jervis.contracts.server.CreateClientResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateClientRequest,
      com.jervis.contracts.server.CreateClientResponse> getCreateClientMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateClientRequest, com.jervis.contracts.server.CreateClientResponse> getCreateClientMethod;
    if ((getCreateClientMethod = ServerProjectManagementServiceGrpc.getCreateClientMethod) == null) {
      synchronized (ServerProjectManagementServiceGrpc.class) {
        if ((getCreateClientMethod = ServerProjectManagementServiceGrpc.getCreateClientMethod) == null) {
          ServerProjectManagementServiceGrpc.getCreateClientMethod = getCreateClientMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateClientRequest, com.jervis.contracts.server.CreateClientResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateClient"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateClientRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateClientResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProjectManagementServiceMethodDescriptorSupplier("CreateClient"))
              .build();
        }
      }
    }
    return getCreateClientMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListProjectsRequest,
      com.jervis.contracts.server.ListProjectsResponse> getListProjectsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListProjects",
      requestType = com.jervis.contracts.server.ListProjectsRequest.class,
      responseType = com.jervis.contracts.server.ListProjectsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListProjectsRequest,
      com.jervis.contracts.server.ListProjectsResponse> getListProjectsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListProjectsRequest, com.jervis.contracts.server.ListProjectsResponse> getListProjectsMethod;
    if ((getListProjectsMethod = ServerProjectManagementServiceGrpc.getListProjectsMethod) == null) {
      synchronized (ServerProjectManagementServiceGrpc.class) {
        if ((getListProjectsMethod = ServerProjectManagementServiceGrpc.getListProjectsMethod) == null) {
          ServerProjectManagementServiceGrpc.getListProjectsMethod = getListProjectsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListProjectsRequest, com.jervis.contracts.server.ListProjectsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListProjects"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListProjectsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListProjectsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProjectManagementServiceMethodDescriptorSupplier("ListProjects"))
              .build();
        }
      }
    }
    return getListProjectsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateProjectRequest,
      com.jervis.contracts.server.CreateProjectResponse> getCreateProjectMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateProject",
      requestType = com.jervis.contracts.server.CreateProjectRequest.class,
      responseType = com.jervis.contracts.server.CreateProjectResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateProjectRequest,
      com.jervis.contracts.server.CreateProjectResponse> getCreateProjectMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateProjectRequest, com.jervis.contracts.server.CreateProjectResponse> getCreateProjectMethod;
    if ((getCreateProjectMethod = ServerProjectManagementServiceGrpc.getCreateProjectMethod) == null) {
      synchronized (ServerProjectManagementServiceGrpc.class) {
        if ((getCreateProjectMethod = ServerProjectManagementServiceGrpc.getCreateProjectMethod) == null) {
          ServerProjectManagementServiceGrpc.getCreateProjectMethod = getCreateProjectMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateProjectRequest, com.jervis.contracts.server.CreateProjectResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateProject"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateProjectRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateProjectResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProjectManagementServiceMethodDescriptorSupplier("CreateProject"))
              .build();
        }
      }
    }
    return getCreateProjectMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateProjectRequest,
      com.jervis.contracts.server.UpdateProjectResponse> getUpdateProjectMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateProject",
      requestType = com.jervis.contracts.server.UpdateProjectRequest.class,
      responseType = com.jervis.contracts.server.UpdateProjectResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateProjectRequest,
      com.jervis.contracts.server.UpdateProjectResponse> getUpdateProjectMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateProjectRequest, com.jervis.contracts.server.UpdateProjectResponse> getUpdateProjectMethod;
    if ((getUpdateProjectMethod = ServerProjectManagementServiceGrpc.getUpdateProjectMethod) == null) {
      synchronized (ServerProjectManagementServiceGrpc.class) {
        if ((getUpdateProjectMethod = ServerProjectManagementServiceGrpc.getUpdateProjectMethod) == null) {
          ServerProjectManagementServiceGrpc.getUpdateProjectMethod = getUpdateProjectMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.UpdateProjectRequest, com.jervis.contracts.server.UpdateProjectResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateProject"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UpdateProjectRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UpdateProjectResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProjectManagementServiceMethodDescriptorSupplier("UpdateProject"))
              .build();
        }
      }
    }
    return getUpdateProjectMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListConnectionsRequest,
      com.jervis.contracts.server.ListConnectionsResponse> getListConnectionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListConnections",
      requestType = com.jervis.contracts.server.ListConnectionsRequest.class,
      responseType = com.jervis.contracts.server.ListConnectionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListConnectionsRequest,
      com.jervis.contracts.server.ListConnectionsResponse> getListConnectionsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListConnectionsRequest, com.jervis.contracts.server.ListConnectionsResponse> getListConnectionsMethod;
    if ((getListConnectionsMethod = ServerProjectManagementServiceGrpc.getListConnectionsMethod) == null) {
      synchronized (ServerProjectManagementServiceGrpc.class) {
        if ((getListConnectionsMethod = ServerProjectManagementServiceGrpc.getListConnectionsMethod) == null) {
          ServerProjectManagementServiceGrpc.getListConnectionsMethod = getListConnectionsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListConnectionsRequest, com.jervis.contracts.server.ListConnectionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListConnections"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListConnectionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListConnectionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProjectManagementServiceMethodDescriptorSupplier("ListConnections"))
              .build();
        }
      }
    }
    return getListConnectionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateConnectionRequest,
      com.jervis.contracts.server.CreateConnectionResponse> getCreateConnectionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateConnection",
      requestType = com.jervis.contracts.server.CreateConnectionRequest.class,
      responseType = com.jervis.contracts.server.CreateConnectionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateConnectionRequest,
      com.jervis.contracts.server.CreateConnectionResponse> getCreateConnectionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateConnectionRequest, com.jervis.contracts.server.CreateConnectionResponse> getCreateConnectionMethod;
    if ((getCreateConnectionMethod = ServerProjectManagementServiceGrpc.getCreateConnectionMethod) == null) {
      synchronized (ServerProjectManagementServiceGrpc.class) {
        if ((getCreateConnectionMethod = ServerProjectManagementServiceGrpc.getCreateConnectionMethod) == null) {
          ServerProjectManagementServiceGrpc.getCreateConnectionMethod = getCreateConnectionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateConnectionRequest, com.jervis.contracts.server.CreateConnectionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateConnection"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateConnectionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateConnectionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProjectManagementServiceMethodDescriptorSupplier("CreateConnection"))
              .build();
        }
      }
    }
    return getCreateConnectionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetStackRecommendationsRequest,
      com.jervis.contracts.server.GetStackRecommendationsResponse> getGetStackRecommendationsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetStackRecommendations",
      requestType = com.jervis.contracts.server.GetStackRecommendationsRequest.class,
      responseType = com.jervis.contracts.server.GetStackRecommendationsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetStackRecommendationsRequest,
      com.jervis.contracts.server.GetStackRecommendationsResponse> getGetStackRecommendationsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetStackRecommendationsRequest, com.jervis.contracts.server.GetStackRecommendationsResponse> getGetStackRecommendationsMethod;
    if ((getGetStackRecommendationsMethod = ServerProjectManagementServiceGrpc.getGetStackRecommendationsMethod) == null) {
      synchronized (ServerProjectManagementServiceGrpc.class) {
        if ((getGetStackRecommendationsMethod = ServerProjectManagementServiceGrpc.getGetStackRecommendationsMethod) == null) {
          ServerProjectManagementServiceGrpc.getGetStackRecommendationsMethod = getGetStackRecommendationsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetStackRecommendationsRequest, com.jervis.contracts.server.GetStackRecommendationsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetStackRecommendations"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetStackRecommendationsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetStackRecommendationsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerProjectManagementServiceMethodDescriptorSupplier("GetStackRecommendations"))
              .build();
        }
      }
    }
    return getGetStackRecommendationsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerProjectManagementServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerProjectManagementServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerProjectManagementServiceStub>() {
        @java.lang.Override
        public ServerProjectManagementServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerProjectManagementServiceStub(channel, callOptions);
        }
      };
    return ServerProjectManagementServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerProjectManagementServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerProjectManagementServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerProjectManagementServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerProjectManagementServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerProjectManagementServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerProjectManagementServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerProjectManagementServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerProjectManagementServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerProjectManagementServiceBlockingStub>() {
        @java.lang.Override
        public ServerProjectManagementServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerProjectManagementServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerProjectManagementServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerProjectManagementServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerProjectManagementServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerProjectManagementServiceFutureStub>() {
        @java.lang.Override
        public ServerProjectManagementServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerProjectManagementServiceFutureStub(channel, callOptions);
        }
      };
    return ServerProjectManagementServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerProjectManagementService is the pod-to-pod surface for creating
   * and listing clients, projects and connections. List responses carry the
   * full DTO tree as JSON (`items_json`) because Python callers only read
   * a handful of surface fields (id, name, provider, capabilities). Create
   * responses stay typed — Python confirms id/name/provider only.
   * The `project-advisor/archetypes` endpoint had no Python consumer and is
   * dropped; recommendations remain available as typed JSON (the orchestrator
   * formats deeply-nested fields for the LLM prompt).
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void listClients(com.jervis.contracts.server.ListClientsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListClientsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListClientsMethod(), responseObserver);
    }

    /**
     */
    default void createClient(com.jervis.contracts.server.CreateClientRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateClientResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateClientMethod(), responseObserver);
    }

    /**
     */
    default void listProjects(com.jervis.contracts.server.ListProjectsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListProjectsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListProjectsMethod(), responseObserver);
    }

    /**
     */
    default void createProject(com.jervis.contracts.server.CreateProjectRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateProjectResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateProjectMethod(), responseObserver);
    }

    /**
     */
    default void updateProject(com.jervis.contracts.server.UpdateProjectRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UpdateProjectResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateProjectMethod(), responseObserver);
    }

    /**
     */
    default void listConnections(com.jervis.contracts.server.ListConnectionsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListConnectionsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListConnectionsMethod(), responseObserver);
    }

    /**
     */
    default void createConnection(com.jervis.contracts.server.CreateConnectionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateConnectionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateConnectionMethod(), responseObserver);
    }

    /**
     */
    default void getStackRecommendations(com.jervis.contracts.server.GetStackRecommendationsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetStackRecommendationsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetStackRecommendationsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerProjectManagementService.
   * <pre>
   * ServerProjectManagementService is the pod-to-pod surface for creating
   * and listing clients, projects and connections. List responses carry the
   * full DTO tree as JSON (`items_json`) because Python callers only read
   * a handful of surface fields (id, name, provider, capabilities). Create
   * responses stay typed — Python confirms id/name/provider only.
   * The `project-advisor/archetypes` endpoint had no Python consumer and is
   * dropped; recommendations remain available as typed JSON (the orchestrator
   * formats deeply-nested fields for the LLM prompt).
   * </pre>
   */
  public static abstract class ServerProjectManagementServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerProjectManagementServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerProjectManagementService.
   * <pre>
   * ServerProjectManagementService is the pod-to-pod surface for creating
   * and listing clients, projects and connections. List responses carry the
   * full DTO tree as JSON (`items_json`) because Python callers only read
   * a handful of surface fields (id, name, provider, capabilities). Create
   * responses stay typed — Python confirms id/name/provider only.
   * The `project-advisor/archetypes` endpoint had no Python consumer and is
   * dropped; recommendations remain available as typed JSON (the orchestrator
   * formats deeply-nested fields for the LLM prompt).
   * </pre>
   */
  public static final class ServerProjectManagementServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerProjectManagementServiceStub> {
    private ServerProjectManagementServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerProjectManagementServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerProjectManagementServiceStub(channel, callOptions);
    }

    /**
     */
    public void listClients(com.jervis.contracts.server.ListClientsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListClientsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListClientsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createClient(com.jervis.contracts.server.CreateClientRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateClientResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateClientMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listProjects(com.jervis.contracts.server.ListProjectsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListProjectsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListProjectsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createProject(com.jervis.contracts.server.CreateProjectRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateProjectResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateProjectMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void updateProject(com.jervis.contracts.server.UpdateProjectRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UpdateProjectResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateProjectMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listConnections(com.jervis.contracts.server.ListConnectionsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListConnectionsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListConnectionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createConnection(com.jervis.contracts.server.CreateConnectionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateConnectionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateConnectionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getStackRecommendations(com.jervis.contracts.server.GetStackRecommendationsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetStackRecommendationsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetStackRecommendationsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerProjectManagementService.
   * <pre>
   * ServerProjectManagementService is the pod-to-pod surface for creating
   * and listing clients, projects and connections. List responses carry the
   * full DTO tree as JSON (`items_json`) because Python callers only read
   * a handful of surface fields (id, name, provider, capabilities). Create
   * responses stay typed — Python confirms id/name/provider only.
   * The `project-advisor/archetypes` endpoint had no Python consumer and is
   * dropped; recommendations remain available as typed JSON (the orchestrator
   * formats deeply-nested fields for the LLM prompt).
   * </pre>
   */
  public static final class ServerProjectManagementServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerProjectManagementServiceBlockingV2Stub> {
    private ServerProjectManagementServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerProjectManagementServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerProjectManagementServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ListClientsResponse listClients(com.jervis.contracts.server.ListClientsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListClientsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateClientResponse createClient(com.jervis.contracts.server.CreateClientRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateClientMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListProjectsResponse listProjects(com.jervis.contracts.server.ListProjectsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListProjectsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateProjectResponse createProject(com.jervis.contracts.server.CreateProjectRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateProjectMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UpdateProjectResponse updateProject(com.jervis.contracts.server.UpdateProjectRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateProjectMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListConnectionsResponse listConnections(com.jervis.contracts.server.ListConnectionsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListConnectionsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateConnectionResponse createConnection(com.jervis.contracts.server.CreateConnectionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateConnectionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetStackRecommendationsResponse getStackRecommendations(com.jervis.contracts.server.GetStackRecommendationsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetStackRecommendationsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerProjectManagementService.
   * <pre>
   * ServerProjectManagementService is the pod-to-pod surface for creating
   * and listing clients, projects and connections. List responses carry the
   * full DTO tree as JSON (`items_json`) because Python callers only read
   * a handful of surface fields (id, name, provider, capabilities). Create
   * responses stay typed — Python confirms id/name/provider only.
   * The `project-advisor/archetypes` endpoint had no Python consumer and is
   * dropped; recommendations remain available as typed JSON (the orchestrator
   * formats deeply-nested fields for the LLM prompt).
   * </pre>
   */
  public static final class ServerProjectManagementServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerProjectManagementServiceBlockingStub> {
    private ServerProjectManagementServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerProjectManagementServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerProjectManagementServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.ListClientsResponse listClients(com.jervis.contracts.server.ListClientsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListClientsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateClientResponse createClient(com.jervis.contracts.server.CreateClientRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateClientMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListProjectsResponse listProjects(com.jervis.contracts.server.ListProjectsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListProjectsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateProjectResponse createProject(com.jervis.contracts.server.CreateProjectRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateProjectMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.UpdateProjectResponse updateProject(com.jervis.contracts.server.UpdateProjectRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateProjectMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListConnectionsResponse listConnections(com.jervis.contracts.server.ListConnectionsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListConnectionsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateConnectionResponse createConnection(com.jervis.contracts.server.CreateConnectionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateConnectionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetStackRecommendationsResponse getStackRecommendations(com.jervis.contracts.server.GetStackRecommendationsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetStackRecommendationsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerProjectManagementService.
   * <pre>
   * ServerProjectManagementService is the pod-to-pod surface for creating
   * and listing clients, projects and connections. List responses carry the
   * full DTO tree as JSON (`items_json`) because Python callers only read
   * a handful of surface fields (id, name, provider, capabilities). Create
   * responses stay typed — Python confirms id/name/provider only.
   * The `project-advisor/archetypes` endpoint had no Python consumer and is
   * dropped; recommendations remain available as typed JSON (the orchestrator
   * formats deeply-nested fields for the LLM prompt).
   * </pre>
   */
  public static final class ServerProjectManagementServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerProjectManagementServiceFutureStub> {
    private ServerProjectManagementServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerProjectManagementServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerProjectManagementServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListClientsResponse> listClients(
        com.jervis.contracts.server.ListClientsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListClientsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CreateClientResponse> createClient(
        com.jervis.contracts.server.CreateClientRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateClientMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListProjectsResponse> listProjects(
        com.jervis.contracts.server.ListProjectsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListProjectsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CreateProjectResponse> createProject(
        com.jervis.contracts.server.CreateProjectRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateProjectMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.UpdateProjectResponse> updateProject(
        com.jervis.contracts.server.UpdateProjectRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateProjectMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListConnectionsResponse> listConnections(
        com.jervis.contracts.server.ListConnectionsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListConnectionsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CreateConnectionResponse> createConnection(
        com.jervis.contracts.server.CreateConnectionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateConnectionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetStackRecommendationsResponse> getStackRecommendations(
        com.jervis.contracts.server.GetStackRecommendationsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetStackRecommendationsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LIST_CLIENTS = 0;
  private static final int METHODID_CREATE_CLIENT = 1;
  private static final int METHODID_LIST_PROJECTS = 2;
  private static final int METHODID_CREATE_PROJECT = 3;
  private static final int METHODID_UPDATE_PROJECT = 4;
  private static final int METHODID_LIST_CONNECTIONS = 5;
  private static final int METHODID_CREATE_CONNECTION = 6;
  private static final int METHODID_GET_STACK_RECOMMENDATIONS = 7;

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
        case METHODID_LIST_CLIENTS:
          serviceImpl.listClients((com.jervis.contracts.server.ListClientsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListClientsResponse>) responseObserver);
          break;
        case METHODID_CREATE_CLIENT:
          serviceImpl.createClient((com.jervis.contracts.server.CreateClientRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateClientResponse>) responseObserver);
          break;
        case METHODID_LIST_PROJECTS:
          serviceImpl.listProjects((com.jervis.contracts.server.ListProjectsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListProjectsResponse>) responseObserver);
          break;
        case METHODID_CREATE_PROJECT:
          serviceImpl.createProject((com.jervis.contracts.server.CreateProjectRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateProjectResponse>) responseObserver);
          break;
        case METHODID_UPDATE_PROJECT:
          serviceImpl.updateProject((com.jervis.contracts.server.UpdateProjectRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.UpdateProjectResponse>) responseObserver);
          break;
        case METHODID_LIST_CONNECTIONS:
          serviceImpl.listConnections((com.jervis.contracts.server.ListConnectionsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListConnectionsResponse>) responseObserver);
          break;
        case METHODID_CREATE_CONNECTION:
          serviceImpl.createConnection((com.jervis.contracts.server.CreateConnectionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateConnectionResponse>) responseObserver);
          break;
        case METHODID_GET_STACK_RECOMMENDATIONS:
          serviceImpl.getStackRecommendations((com.jervis.contracts.server.GetStackRecommendationsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetStackRecommendationsResponse>) responseObserver);
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
          getListClientsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListClientsRequest,
              com.jervis.contracts.server.ListClientsResponse>(
                service, METHODID_LIST_CLIENTS)))
        .addMethod(
          getCreateClientMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateClientRequest,
              com.jervis.contracts.server.CreateClientResponse>(
                service, METHODID_CREATE_CLIENT)))
        .addMethod(
          getListProjectsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListProjectsRequest,
              com.jervis.contracts.server.ListProjectsResponse>(
                service, METHODID_LIST_PROJECTS)))
        .addMethod(
          getCreateProjectMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateProjectRequest,
              com.jervis.contracts.server.CreateProjectResponse>(
                service, METHODID_CREATE_PROJECT)))
        .addMethod(
          getUpdateProjectMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.UpdateProjectRequest,
              com.jervis.contracts.server.UpdateProjectResponse>(
                service, METHODID_UPDATE_PROJECT)))
        .addMethod(
          getListConnectionsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListConnectionsRequest,
              com.jervis.contracts.server.ListConnectionsResponse>(
                service, METHODID_LIST_CONNECTIONS)))
        .addMethod(
          getCreateConnectionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateConnectionRequest,
              com.jervis.contracts.server.CreateConnectionResponse>(
                service, METHODID_CREATE_CONNECTION)))
        .addMethod(
          getGetStackRecommendationsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetStackRecommendationsRequest,
              com.jervis.contracts.server.GetStackRecommendationsResponse>(
                service, METHODID_GET_STACK_RECOMMENDATIONS)))
        .build();
  }

  private static abstract class ServerProjectManagementServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerProjectManagementServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerProjectManagementProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerProjectManagementService");
    }
  }

  private static final class ServerProjectManagementServiceFileDescriptorSupplier
      extends ServerProjectManagementServiceBaseDescriptorSupplier {
    ServerProjectManagementServiceFileDescriptorSupplier() {}
  }

  private static final class ServerProjectManagementServiceMethodDescriptorSupplier
      extends ServerProjectManagementServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerProjectManagementServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerProjectManagementServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerProjectManagementServiceFileDescriptorSupplier())
              .addMethod(getListClientsMethod())
              .addMethod(getCreateClientMethod())
              .addMethod(getListProjectsMethod())
              .addMethod(getCreateProjectMethod())
              .addMethod(getUpdateProjectMethod())
              .addMethod(getListConnectionsMethod())
              .addMethod(getCreateConnectionMethod())
              .addMethod(getGetStackRecommendationsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
