package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerAgentJobService is the Kotlin-owned surface for the
 * AgentJobRecord lifecycle. Claude calls it via MCP tools
 * (`dispatch_agent_job`, `get_agent_job_status`, `abort_agent_job`)
 * which are Python shims over this gRPC service. The server is the
 * only writer for the `agent_job_records` collection — dispatch,
 * status reads, and abort all flow through here.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerAgentJobServiceGrpc {

  private ServerAgentJobServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerAgentJobService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.DispatchAgentJobRequest,
      com.jervis.contracts.server.DispatchAgentJobResponse> getDispatchAgentJobMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DispatchAgentJob",
      requestType = com.jervis.contracts.server.DispatchAgentJobRequest.class,
      responseType = com.jervis.contracts.server.DispatchAgentJobResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.DispatchAgentJobRequest,
      com.jervis.contracts.server.DispatchAgentJobResponse> getDispatchAgentJobMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.DispatchAgentJobRequest, com.jervis.contracts.server.DispatchAgentJobResponse> getDispatchAgentJobMethod;
    if ((getDispatchAgentJobMethod = ServerAgentJobServiceGrpc.getDispatchAgentJobMethod) == null) {
      synchronized (ServerAgentJobServiceGrpc.class) {
        if ((getDispatchAgentJobMethod = ServerAgentJobServiceGrpc.getDispatchAgentJobMethod) == null) {
          ServerAgentJobServiceGrpc.getDispatchAgentJobMethod = getDispatchAgentJobMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.DispatchAgentJobRequest, com.jervis.contracts.server.DispatchAgentJobResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DispatchAgentJob"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.DispatchAgentJobRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.DispatchAgentJobResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerAgentJobServiceMethodDescriptorSupplier("DispatchAgentJob"))
              .build();
        }
      }
    }
    return getDispatchAgentJobMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.AgentJobIdRequest,
      com.jervis.contracts.server.GetAgentJobStatusResponse> getGetAgentJobStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetAgentJobStatus",
      requestType = com.jervis.contracts.server.AgentJobIdRequest.class,
      responseType = com.jervis.contracts.server.GetAgentJobStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.AgentJobIdRequest,
      com.jervis.contracts.server.GetAgentJobStatusResponse> getGetAgentJobStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.AgentJobIdRequest, com.jervis.contracts.server.GetAgentJobStatusResponse> getGetAgentJobStatusMethod;
    if ((getGetAgentJobStatusMethod = ServerAgentJobServiceGrpc.getGetAgentJobStatusMethod) == null) {
      synchronized (ServerAgentJobServiceGrpc.class) {
        if ((getGetAgentJobStatusMethod = ServerAgentJobServiceGrpc.getGetAgentJobStatusMethod) == null) {
          ServerAgentJobServiceGrpc.getGetAgentJobStatusMethod = getGetAgentJobStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.AgentJobIdRequest, com.jervis.contracts.server.GetAgentJobStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetAgentJobStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AgentJobIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetAgentJobStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerAgentJobServiceMethodDescriptorSupplier("GetAgentJobStatus"))
              .build();
        }
      }
    }
    return getGetAgentJobStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.AbortAgentJobRequest,
      com.jervis.contracts.server.AbortAgentJobResponse> getAbortAgentJobMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AbortAgentJob",
      requestType = com.jervis.contracts.server.AbortAgentJobRequest.class,
      responseType = com.jervis.contracts.server.AbortAgentJobResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.AbortAgentJobRequest,
      com.jervis.contracts.server.AbortAgentJobResponse> getAbortAgentJobMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.AbortAgentJobRequest, com.jervis.contracts.server.AbortAgentJobResponse> getAbortAgentJobMethod;
    if ((getAbortAgentJobMethod = ServerAgentJobServiceGrpc.getAbortAgentJobMethod) == null) {
      synchronized (ServerAgentJobServiceGrpc.class) {
        if ((getAbortAgentJobMethod = ServerAgentJobServiceGrpc.getAbortAgentJobMethod) == null) {
          ServerAgentJobServiceGrpc.getAbortAgentJobMethod = getAbortAgentJobMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.AbortAgentJobRequest, com.jervis.contracts.server.AbortAgentJobResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AbortAgentJob"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AbortAgentJobRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AbortAgentJobResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerAgentJobServiceMethodDescriptorSupplier("AbortAgentJob"))
              .build();
        }
      }
    }
    return getAbortAgentJobMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ReportAgentDoneRequest,
      com.jervis.contracts.server.ReportAgentDoneResponse> getReportAgentDoneMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReportAgentDone",
      requestType = com.jervis.contracts.server.ReportAgentDoneRequest.class,
      responseType = com.jervis.contracts.server.ReportAgentDoneResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ReportAgentDoneRequest,
      com.jervis.contracts.server.ReportAgentDoneResponse> getReportAgentDoneMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ReportAgentDoneRequest, com.jervis.contracts.server.ReportAgentDoneResponse> getReportAgentDoneMethod;
    if ((getReportAgentDoneMethod = ServerAgentJobServiceGrpc.getReportAgentDoneMethod) == null) {
      synchronized (ServerAgentJobServiceGrpc.class) {
        if ((getReportAgentDoneMethod = ServerAgentJobServiceGrpc.getReportAgentDoneMethod) == null) {
          ServerAgentJobServiceGrpc.getReportAgentDoneMethod = getReportAgentDoneMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ReportAgentDoneRequest, com.jervis.contracts.server.ReportAgentDoneResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReportAgentDone"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ReportAgentDoneRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ReportAgentDoneResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerAgentJobServiceMethodDescriptorSupplier("ReportAgentDone"))
              .build();
        }
      }
    }
    return getReportAgentDoneMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerAgentJobServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerAgentJobServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerAgentJobServiceStub>() {
        @java.lang.Override
        public ServerAgentJobServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerAgentJobServiceStub(channel, callOptions);
        }
      };
    return ServerAgentJobServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerAgentJobServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerAgentJobServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerAgentJobServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerAgentJobServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerAgentJobServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerAgentJobServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerAgentJobServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerAgentJobServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerAgentJobServiceBlockingStub>() {
        @java.lang.Override
        public ServerAgentJobServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerAgentJobServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerAgentJobServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerAgentJobServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerAgentJobServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerAgentJobServiceFutureStub>() {
        @java.lang.Override
        public ServerAgentJobServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerAgentJobServiceFutureStub(channel, callOptions);
        }
      };
    return ServerAgentJobServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerAgentJobService is the Kotlin-owned surface for the
   * AgentJobRecord lifecycle. Claude calls it via MCP tools
   * (`dispatch_agent_job`, `get_agent_job_status`, `abort_agent_job`)
   * which are Python shims over this gRPC service. The server is the
   * only writer for the `agent_job_records` collection — dispatch,
   * status reads, and abort all flow through here.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void dispatchAgentJob(com.jervis.contracts.server.DispatchAgentJobRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.DispatchAgentJobResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDispatchAgentJobMethod(), responseObserver);
    }

    /**
     */
    default void getAgentJobStatus(com.jervis.contracts.server.AgentJobIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetAgentJobStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetAgentJobStatusMethod(), responseObserver);
    }

    /**
     */
    default void abortAgentJob(com.jervis.contracts.server.AbortAgentJobRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AbortAgentJobResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAbortAgentJobMethod(), responseObserver);
    }

    /**
     * <pre>
     * Agent-initiated terminal transition (push from the in-pod agent via
     * MCP `report_agent_done`). Idempotent: second caller on a terminal
     * record returns the current state instead of a second transition.
     * </pre>
     */
    default void reportAgentDone(com.jervis.contracts.server.ReportAgentDoneRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ReportAgentDoneResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReportAgentDoneMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerAgentJobService.
   * <pre>
   * ServerAgentJobService is the Kotlin-owned surface for the
   * AgentJobRecord lifecycle. Claude calls it via MCP tools
   * (`dispatch_agent_job`, `get_agent_job_status`, `abort_agent_job`)
   * which are Python shims over this gRPC service. The server is the
   * only writer for the `agent_job_records` collection — dispatch,
   * status reads, and abort all flow through here.
   * </pre>
   */
  public static abstract class ServerAgentJobServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerAgentJobServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerAgentJobService.
   * <pre>
   * ServerAgentJobService is the Kotlin-owned surface for the
   * AgentJobRecord lifecycle. Claude calls it via MCP tools
   * (`dispatch_agent_job`, `get_agent_job_status`, `abort_agent_job`)
   * which are Python shims over this gRPC service. The server is the
   * only writer for the `agent_job_records` collection — dispatch,
   * status reads, and abort all flow through here.
   * </pre>
   */
  public static final class ServerAgentJobServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerAgentJobServiceStub> {
    private ServerAgentJobServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerAgentJobServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerAgentJobServiceStub(channel, callOptions);
    }

    /**
     */
    public void dispatchAgentJob(com.jervis.contracts.server.DispatchAgentJobRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.DispatchAgentJobResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDispatchAgentJobMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getAgentJobStatus(com.jervis.contracts.server.AgentJobIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetAgentJobStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetAgentJobStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void abortAgentJob(com.jervis.contracts.server.AbortAgentJobRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AbortAgentJobResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAbortAgentJobMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Agent-initiated terminal transition (push from the in-pod agent via
     * MCP `report_agent_done`). Idempotent: second caller on a terminal
     * record returns the current state instead of a second transition.
     * </pre>
     */
    public void reportAgentDone(com.jervis.contracts.server.ReportAgentDoneRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ReportAgentDoneResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReportAgentDoneMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerAgentJobService.
   * <pre>
   * ServerAgentJobService is the Kotlin-owned surface for the
   * AgentJobRecord lifecycle. Claude calls it via MCP tools
   * (`dispatch_agent_job`, `get_agent_job_status`, `abort_agent_job`)
   * which are Python shims over this gRPC service. The server is the
   * only writer for the `agent_job_records` collection — dispatch,
   * status reads, and abort all flow through here.
   * </pre>
   */
  public static final class ServerAgentJobServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerAgentJobServiceBlockingV2Stub> {
    private ServerAgentJobServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerAgentJobServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerAgentJobServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.DispatchAgentJobResponse dispatchAgentJob(com.jervis.contracts.server.DispatchAgentJobRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDispatchAgentJobMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetAgentJobStatusResponse getAgentJobStatus(com.jervis.contracts.server.AgentJobIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetAgentJobStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AbortAgentJobResponse abortAgentJob(com.jervis.contracts.server.AbortAgentJobRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAbortAgentJobMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Agent-initiated terminal transition (push from the in-pod agent via
     * MCP `report_agent_done`). Idempotent: second caller on a terminal
     * record returns the current state instead of a second transition.
     * </pre>
     */
    public com.jervis.contracts.server.ReportAgentDoneResponse reportAgentDone(com.jervis.contracts.server.ReportAgentDoneRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReportAgentDoneMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerAgentJobService.
   * <pre>
   * ServerAgentJobService is the Kotlin-owned surface for the
   * AgentJobRecord lifecycle. Claude calls it via MCP tools
   * (`dispatch_agent_job`, `get_agent_job_status`, `abort_agent_job`)
   * which are Python shims over this gRPC service. The server is the
   * only writer for the `agent_job_records` collection — dispatch,
   * status reads, and abort all flow through here.
   * </pre>
   */
  public static final class ServerAgentJobServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerAgentJobServiceBlockingStub> {
    private ServerAgentJobServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerAgentJobServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerAgentJobServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.DispatchAgentJobResponse dispatchAgentJob(com.jervis.contracts.server.DispatchAgentJobRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDispatchAgentJobMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetAgentJobStatusResponse getAgentJobStatus(com.jervis.contracts.server.AgentJobIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetAgentJobStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AbortAgentJobResponse abortAgentJob(com.jervis.contracts.server.AbortAgentJobRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAbortAgentJobMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Agent-initiated terminal transition (push from the in-pod agent via
     * MCP `report_agent_done`). Idempotent: second caller on a terminal
     * record returns the current state instead of a second transition.
     * </pre>
     */
    public com.jervis.contracts.server.ReportAgentDoneResponse reportAgentDone(com.jervis.contracts.server.ReportAgentDoneRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReportAgentDoneMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerAgentJobService.
   * <pre>
   * ServerAgentJobService is the Kotlin-owned surface for the
   * AgentJobRecord lifecycle. Claude calls it via MCP tools
   * (`dispatch_agent_job`, `get_agent_job_status`, `abort_agent_job`)
   * which are Python shims over this gRPC service. The server is the
   * only writer for the `agent_job_records` collection — dispatch,
   * status reads, and abort all flow through here.
   * </pre>
   */
  public static final class ServerAgentJobServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerAgentJobServiceFutureStub> {
    private ServerAgentJobServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerAgentJobServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerAgentJobServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.DispatchAgentJobResponse> dispatchAgentJob(
        com.jervis.contracts.server.DispatchAgentJobRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDispatchAgentJobMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetAgentJobStatusResponse> getAgentJobStatus(
        com.jervis.contracts.server.AgentJobIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetAgentJobStatusMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AbortAgentJobResponse> abortAgentJob(
        com.jervis.contracts.server.AbortAgentJobRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAbortAgentJobMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Agent-initiated terminal transition (push from the in-pod agent via
     * MCP `report_agent_done`). Idempotent: second caller on a terminal
     * record returns the current state instead of a second transition.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ReportAgentDoneResponse> reportAgentDone(
        com.jervis.contracts.server.ReportAgentDoneRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReportAgentDoneMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_DISPATCH_AGENT_JOB = 0;
  private static final int METHODID_GET_AGENT_JOB_STATUS = 1;
  private static final int METHODID_ABORT_AGENT_JOB = 2;
  private static final int METHODID_REPORT_AGENT_DONE = 3;

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
        case METHODID_DISPATCH_AGENT_JOB:
          serviceImpl.dispatchAgentJob((com.jervis.contracts.server.DispatchAgentJobRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.DispatchAgentJobResponse>) responseObserver);
          break;
        case METHODID_GET_AGENT_JOB_STATUS:
          serviceImpl.getAgentJobStatus((com.jervis.contracts.server.AgentJobIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetAgentJobStatusResponse>) responseObserver);
          break;
        case METHODID_ABORT_AGENT_JOB:
          serviceImpl.abortAgentJob((com.jervis.contracts.server.AbortAgentJobRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AbortAgentJobResponse>) responseObserver);
          break;
        case METHODID_REPORT_AGENT_DONE:
          serviceImpl.reportAgentDone((com.jervis.contracts.server.ReportAgentDoneRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ReportAgentDoneResponse>) responseObserver);
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
          getDispatchAgentJobMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.DispatchAgentJobRequest,
              com.jervis.contracts.server.DispatchAgentJobResponse>(
                service, METHODID_DISPATCH_AGENT_JOB)))
        .addMethod(
          getGetAgentJobStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.AgentJobIdRequest,
              com.jervis.contracts.server.GetAgentJobStatusResponse>(
                service, METHODID_GET_AGENT_JOB_STATUS)))
        .addMethod(
          getAbortAgentJobMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.AbortAgentJobRequest,
              com.jervis.contracts.server.AbortAgentJobResponse>(
                service, METHODID_ABORT_AGENT_JOB)))
        .addMethod(
          getReportAgentDoneMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ReportAgentDoneRequest,
              com.jervis.contracts.server.ReportAgentDoneResponse>(
                service, METHODID_REPORT_AGENT_DONE)))
        .build();
  }

  private static abstract class ServerAgentJobServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerAgentJobServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerAgentJobProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerAgentJobService");
    }
  }

  private static final class ServerAgentJobServiceFileDescriptorSupplier
      extends ServerAgentJobServiceBaseDescriptorSupplier {
    ServerAgentJobServiceFileDescriptorSupplier() {}
  }

  private static final class ServerAgentJobServiceMethodDescriptorSupplier
      extends ServerAgentJobServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerAgentJobServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerAgentJobServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerAgentJobServiceFileDescriptorSupplier())
              .addMethod(getDispatchAgentJobMethod())
              .addMethod(getGetAgentJobStatusMethod())
              .addMethod(getAbortAgentJobMethod())
              .addMethod(getReportAgentDoneMethod())
              .build();
        }
      }
    }
    return result;
  }
}
