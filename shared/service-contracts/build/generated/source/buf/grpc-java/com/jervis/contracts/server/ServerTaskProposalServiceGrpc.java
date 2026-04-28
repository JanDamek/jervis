package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerTaskProposalService is the Kotlin-owned surface for the
 * Claude CLI proposal lifecycle. The MCP tools `propose_task`,
 * `update_proposed_task`, `send_for_approval`, `task_approve`,
 * `task_reject` ultimately delegate to these RPCs (via the
 * orchestrator's OrchestratorProposalService for create/update — which
 * also runs embedding + dedup — and directly here for approve/reject).
 * The server is the single writer for `tasks` documents; proposal
 * stage transitions are atomic via MongoTemplate findOneAndUpdate
 * guarded by the current `proposalStage` so out-of-order calls return
 * INVALID_STATE rather than racing.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerTaskProposalServiceGrpc {

  private ServerTaskProposalServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerTaskProposalService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.InsertProposalRequest,
      com.jervis.contracts.server.InsertProposalResponse> getInsertProposalMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "InsertProposal",
      requestType = com.jervis.contracts.server.InsertProposalRequest.class,
      responseType = com.jervis.contracts.server.InsertProposalResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.InsertProposalRequest,
      com.jervis.contracts.server.InsertProposalResponse> getInsertProposalMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.InsertProposalRequest, com.jervis.contracts.server.InsertProposalResponse> getInsertProposalMethod;
    if ((getInsertProposalMethod = ServerTaskProposalServiceGrpc.getInsertProposalMethod) == null) {
      synchronized (ServerTaskProposalServiceGrpc.class) {
        if ((getInsertProposalMethod = ServerTaskProposalServiceGrpc.getInsertProposalMethod) == null) {
          ServerTaskProposalServiceGrpc.getInsertProposalMethod = getInsertProposalMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.InsertProposalRequest, com.jervis.contracts.server.InsertProposalResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "InsertProposal"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.InsertProposalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.InsertProposalResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskProposalServiceMethodDescriptorSupplier("InsertProposal"))
              .build();
        }
      }
    }
    return getInsertProposalMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateProposalRequest,
      com.jervis.contracts.server.UpdateProposalResponse> getUpdateProposalMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateProposal",
      requestType = com.jervis.contracts.server.UpdateProposalRequest.class,
      responseType = com.jervis.contracts.server.UpdateProposalResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateProposalRequest,
      com.jervis.contracts.server.UpdateProposalResponse> getUpdateProposalMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.UpdateProposalRequest, com.jervis.contracts.server.UpdateProposalResponse> getUpdateProposalMethod;
    if ((getUpdateProposalMethod = ServerTaskProposalServiceGrpc.getUpdateProposalMethod) == null) {
      synchronized (ServerTaskProposalServiceGrpc.class) {
        if ((getUpdateProposalMethod = ServerTaskProposalServiceGrpc.getUpdateProposalMethod) == null) {
          ServerTaskProposalServiceGrpc.getUpdateProposalMethod = getUpdateProposalMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.UpdateProposalRequest, com.jervis.contracts.server.UpdateProposalResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateProposal"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UpdateProposalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UpdateProposalResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskProposalServiceMethodDescriptorSupplier("UpdateProposal"))
              .build();
        }
      }
    }
    return getUpdateProposalMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.ProposalActionResponse> getSendForApprovalMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendForApproval",
      requestType = com.jervis.contracts.server.TaskIdRequest.class,
      responseType = com.jervis.contracts.server.ProposalActionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.ProposalActionResponse> getSendForApprovalMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.ProposalActionResponse> getSendForApprovalMethod;
    if ((getSendForApprovalMethod = ServerTaskProposalServiceGrpc.getSendForApprovalMethod) == null) {
      synchronized (ServerTaskProposalServiceGrpc.class) {
        if ((getSendForApprovalMethod = ServerTaskProposalServiceGrpc.getSendForApprovalMethod) == null) {
          ServerTaskProposalServiceGrpc.getSendForApprovalMethod = getSendForApprovalMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.ProposalActionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendForApproval"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ProposalActionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskProposalServiceMethodDescriptorSupplier("SendForApproval"))
              .build();
        }
      }
    }
    return getSendForApprovalMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.ProposalActionResponse> getApproveTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ApproveTask",
      requestType = com.jervis.contracts.server.TaskIdRequest.class,
      responseType = com.jervis.contracts.server.ProposalActionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.ProposalActionResponse> getApproveTaskMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.ProposalActionResponse> getApproveTaskMethod;
    if ((getApproveTaskMethod = ServerTaskProposalServiceGrpc.getApproveTaskMethod) == null) {
      synchronized (ServerTaskProposalServiceGrpc.class) {
        if ((getApproveTaskMethod = ServerTaskProposalServiceGrpc.getApproveTaskMethod) == null) {
          ServerTaskProposalServiceGrpc.getApproveTaskMethod = getApproveTaskMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.ProposalActionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ApproveTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ProposalActionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskProposalServiceMethodDescriptorSupplier("ApproveTask"))
              .build();
        }
      }
    }
    return getApproveTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.RejectTaskRequest,
      com.jervis.contracts.server.ProposalActionResponse> getRejectTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RejectTask",
      requestType = com.jervis.contracts.server.RejectTaskRequest.class,
      responseType = com.jervis.contracts.server.ProposalActionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.RejectTaskRequest,
      com.jervis.contracts.server.ProposalActionResponse> getRejectTaskMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.RejectTaskRequest, com.jervis.contracts.server.ProposalActionResponse> getRejectTaskMethod;
    if ((getRejectTaskMethod = ServerTaskProposalServiceGrpc.getRejectTaskMethod) == null) {
      synchronized (ServerTaskProposalServiceGrpc.class) {
        if ((getRejectTaskMethod = ServerTaskProposalServiceGrpc.getRejectTaskMethod) == null) {
          ServerTaskProposalServiceGrpc.getRejectTaskMethod = getRejectTaskMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.RejectTaskRequest, com.jervis.contracts.server.ProposalActionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RejectTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.RejectTaskRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ProposalActionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskProposalServiceMethodDescriptorSupplier("RejectTask"))
              .build();
        }
      }
    }
    return getRejectTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.DedupRequest,
      com.jervis.contracts.server.DedupResponse> getListPendingProposalsForDedupMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListPendingProposalsForDedup",
      requestType = com.jervis.contracts.server.DedupRequest.class,
      responseType = com.jervis.contracts.server.DedupResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.DedupRequest,
      com.jervis.contracts.server.DedupResponse> getListPendingProposalsForDedupMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.DedupRequest, com.jervis.contracts.server.DedupResponse> getListPendingProposalsForDedupMethod;
    if ((getListPendingProposalsForDedupMethod = ServerTaskProposalServiceGrpc.getListPendingProposalsForDedupMethod) == null) {
      synchronized (ServerTaskProposalServiceGrpc.class) {
        if ((getListPendingProposalsForDedupMethod = ServerTaskProposalServiceGrpc.getListPendingProposalsForDedupMethod) == null) {
          ServerTaskProposalServiceGrpc.getListPendingProposalsForDedupMethod = getListPendingProposalsForDedupMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.DedupRequest, com.jervis.contracts.server.DedupResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListPendingProposalsForDedup"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.DedupRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.DedupResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskProposalServiceMethodDescriptorSupplier("ListPendingProposalsForDedup"))
              .build();
        }
      }
    }
    return getListPendingProposalsForDedupMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerTaskProposalServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTaskProposalServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTaskProposalServiceStub>() {
        @java.lang.Override
        public ServerTaskProposalServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTaskProposalServiceStub(channel, callOptions);
        }
      };
    return ServerTaskProposalServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerTaskProposalServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTaskProposalServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTaskProposalServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerTaskProposalServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTaskProposalServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerTaskProposalServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerTaskProposalServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTaskProposalServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTaskProposalServiceBlockingStub>() {
        @java.lang.Override
        public ServerTaskProposalServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTaskProposalServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerTaskProposalServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerTaskProposalServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTaskProposalServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTaskProposalServiceFutureStub>() {
        @java.lang.Override
        public ServerTaskProposalServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTaskProposalServiceFutureStub(channel, callOptions);
        }
      };
    return ServerTaskProposalServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerTaskProposalService is the Kotlin-owned surface for the
   * Claude CLI proposal lifecycle. The MCP tools `propose_task`,
   * `update_proposed_task`, `send_for_approval`, `task_approve`,
   * `task_reject` ultimately delegate to these RPCs (via the
   * orchestrator's OrchestratorProposalService for create/update — which
   * also runs embedding + dedup — and directly here for approve/reject).
   * The server is the single writer for `tasks` documents; proposal
   * stage transitions are atomic via MongoTemplate findOneAndUpdate
   * guarded by the current `proposalStage` so out-of-order calls return
   * INVALID_STATE rather than racing.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Insert a fresh proposal in DRAFT state.
     * </pre>
     */
    default void insertProposal(com.jervis.contracts.server.InsertProposalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.InsertProposalResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInsertProposalMethod(), responseObserver);
    }

    /**
     * <pre>
     * Update mutable fields of a DRAFT or REJECTED proposal (atomic
     * findOneAndUpdate filtered by stage). AWAITING_APPROVAL / APPROVED
     * proposals are immutable; the call returns INVALID_STATE.
     * </pre>
     */
    default void updateProposal(com.jervis.contracts.server.UpdateProposalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UpdateProposalResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateProposalMethod(), responseObserver);
    }

    /**
     * <pre>
     * DRAFT → AWAITING_APPROVAL transition. Idempotent on terminal states
     * returns INVALID_STATE.
     * </pre>
     */
    default void sendForApproval(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProposalActionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendForApprovalMethod(), responseObserver);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → APPROVED + state=QUEUED. Used by UI on
     * user approve action.
     * </pre>
     */
    default void approveTask(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProposalActionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getApproveTaskMethod(), responseObserver);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → REJECTED + proposalRejectionReason set. Used by
     * UI on user reject action.
     * </pre>
     */
    default void rejectTask(com.jervis.contracts.server.RejectTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProposalActionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRejectTaskMethod(), responseObserver);
    }

    /**
     * <pre>
     * List recent (last 7 days) DRAFT/AWAITING_APPROVAL proposals
     * authored by the same `proposed_by` scope so the orchestrator can
     * run a cosine-similarity dedup check before InsertProposal. Empty
     * `project_id` matches all projects under the client.
     * </pre>
     */
    default void listPendingProposalsForDedup(com.jervis.contracts.server.DedupRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.DedupResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListPendingProposalsForDedupMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerTaskProposalService.
   * <pre>
   * ServerTaskProposalService is the Kotlin-owned surface for the
   * Claude CLI proposal lifecycle. The MCP tools `propose_task`,
   * `update_proposed_task`, `send_for_approval`, `task_approve`,
   * `task_reject` ultimately delegate to these RPCs (via the
   * orchestrator's OrchestratorProposalService for create/update — which
   * also runs embedding + dedup — and directly here for approve/reject).
   * The server is the single writer for `tasks` documents; proposal
   * stage transitions are atomic via MongoTemplate findOneAndUpdate
   * guarded by the current `proposalStage` so out-of-order calls return
   * INVALID_STATE rather than racing.
   * </pre>
   */
  public static abstract class ServerTaskProposalServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerTaskProposalServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerTaskProposalService.
   * <pre>
   * ServerTaskProposalService is the Kotlin-owned surface for the
   * Claude CLI proposal lifecycle. The MCP tools `propose_task`,
   * `update_proposed_task`, `send_for_approval`, `task_approve`,
   * `task_reject` ultimately delegate to these RPCs (via the
   * orchestrator's OrchestratorProposalService for create/update — which
   * also runs embedding + dedup — and directly here for approve/reject).
   * The server is the single writer for `tasks` documents; proposal
   * stage transitions are atomic via MongoTemplate findOneAndUpdate
   * guarded by the current `proposalStage` so out-of-order calls return
   * INVALID_STATE rather than racing.
   * </pre>
   */
  public static final class ServerTaskProposalServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerTaskProposalServiceStub> {
    private ServerTaskProposalServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTaskProposalServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTaskProposalServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Insert a fresh proposal in DRAFT state.
     * </pre>
     */
    public void insertProposal(com.jervis.contracts.server.InsertProposalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.InsertProposalResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInsertProposalMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Update mutable fields of a DRAFT or REJECTED proposal (atomic
     * findOneAndUpdate filtered by stage). AWAITING_APPROVAL / APPROVED
     * proposals are immutable; the call returns INVALID_STATE.
     * </pre>
     */
    public void updateProposal(com.jervis.contracts.server.UpdateProposalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UpdateProposalResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateProposalMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * DRAFT → AWAITING_APPROVAL transition. Idempotent on terminal states
     * returns INVALID_STATE.
     * </pre>
     */
    public void sendForApproval(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProposalActionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendForApprovalMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → APPROVED + state=QUEUED. Used by UI on
     * user approve action.
     * </pre>
     */
    public void approveTask(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProposalActionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getApproveTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → REJECTED + proposalRejectionReason set. Used by
     * UI on user reject action.
     * </pre>
     */
    public void rejectTask(com.jervis.contracts.server.RejectTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProposalActionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRejectTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * List recent (last 7 days) DRAFT/AWAITING_APPROVAL proposals
     * authored by the same `proposed_by` scope so the orchestrator can
     * run a cosine-similarity dedup check before InsertProposal. Empty
     * `project_id` matches all projects under the client.
     * </pre>
     */
    public void listPendingProposalsForDedup(com.jervis.contracts.server.DedupRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.DedupResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListPendingProposalsForDedupMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerTaskProposalService.
   * <pre>
   * ServerTaskProposalService is the Kotlin-owned surface for the
   * Claude CLI proposal lifecycle. The MCP tools `propose_task`,
   * `update_proposed_task`, `send_for_approval`, `task_approve`,
   * `task_reject` ultimately delegate to these RPCs (via the
   * orchestrator's OrchestratorProposalService for create/update — which
   * also runs embedding + dedup — and directly here for approve/reject).
   * The server is the single writer for `tasks` documents; proposal
   * stage transitions are atomic via MongoTemplate findOneAndUpdate
   * guarded by the current `proposalStage` so out-of-order calls return
   * INVALID_STATE rather than racing.
   * </pre>
   */
  public static final class ServerTaskProposalServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerTaskProposalServiceBlockingV2Stub> {
    private ServerTaskProposalServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTaskProposalServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTaskProposalServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Insert a fresh proposal in DRAFT state.
     * </pre>
     */
    public com.jervis.contracts.server.InsertProposalResponse insertProposal(com.jervis.contracts.server.InsertProposalRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInsertProposalMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Update mutable fields of a DRAFT or REJECTED proposal (atomic
     * findOneAndUpdate filtered by stage). AWAITING_APPROVAL / APPROVED
     * proposals are immutable; the call returns INVALID_STATE.
     * </pre>
     */
    public com.jervis.contracts.server.UpdateProposalResponse updateProposal(com.jervis.contracts.server.UpdateProposalRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateProposalMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * DRAFT → AWAITING_APPROVAL transition. Idempotent on terminal states
     * returns INVALID_STATE.
     * </pre>
     */
    public com.jervis.contracts.server.ProposalActionResponse sendForApproval(com.jervis.contracts.server.TaskIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSendForApprovalMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → APPROVED + state=QUEUED. Used by UI on
     * user approve action.
     * </pre>
     */
    public com.jervis.contracts.server.ProposalActionResponse approveTask(com.jervis.contracts.server.TaskIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getApproveTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → REJECTED + proposalRejectionReason set. Used by
     * UI on user reject action.
     * </pre>
     */
    public com.jervis.contracts.server.ProposalActionResponse rejectTask(com.jervis.contracts.server.RejectTaskRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRejectTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * List recent (last 7 days) DRAFT/AWAITING_APPROVAL proposals
     * authored by the same `proposed_by` scope so the orchestrator can
     * run a cosine-similarity dedup check before InsertProposal. Empty
     * `project_id` matches all projects under the client.
     * </pre>
     */
    public com.jervis.contracts.server.DedupResponse listPendingProposalsForDedup(com.jervis.contracts.server.DedupRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListPendingProposalsForDedupMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerTaskProposalService.
   * <pre>
   * ServerTaskProposalService is the Kotlin-owned surface for the
   * Claude CLI proposal lifecycle. The MCP tools `propose_task`,
   * `update_proposed_task`, `send_for_approval`, `task_approve`,
   * `task_reject` ultimately delegate to these RPCs (via the
   * orchestrator's OrchestratorProposalService for create/update — which
   * also runs embedding + dedup — and directly here for approve/reject).
   * The server is the single writer for `tasks` documents; proposal
   * stage transitions are atomic via MongoTemplate findOneAndUpdate
   * guarded by the current `proposalStage` so out-of-order calls return
   * INVALID_STATE rather than racing.
   * </pre>
   */
  public static final class ServerTaskProposalServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerTaskProposalServiceBlockingStub> {
    private ServerTaskProposalServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTaskProposalServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTaskProposalServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Insert a fresh proposal in DRAFT state.
     * </pre>
     */
    public com.jervis.contracts.server.InsertProposalResponse insertProposal(com.jervis.contracts.server.InsertProposalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInsertProposalMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Update mutable fields of a DRAFT or REJECTED proposal (atomic
     * findOneAndUpdate filtered by stage). AWAITING_APPROVAL / APPROVED
     * proposals are immutable; the call returns INVALID_STATE.
     * </pre>
     */
    public com.jervis.contracts.server.UpdateProposalResponse updateProposal(com.jervis.contracts.server.UpdateProposalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateProposalMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * DRAFT → AWAITING_APPROVAL transition. Idempotent on terminal states
     * returns INVALID_STATE.
     * </pre>
     */
    public com.jervis.contracts.server.ProposalActionResponse sendForApproval(com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendForApprovalMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → APPROVED + state=QUEUED. Used by UI on
     * user approve action.
     * </pre>
     */
    public com.jervis.contracts.server.ProposalActionResponse approveTask(com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getApproveTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → REJECTED + proposalRejectionReason set. Used by
     * UI on user reject action.
     * </pre>
     */
    public com.jervis.contracts.server.ProposalActionResponse rejectTask(com.jervis.contracts.server.RejectTaskRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRejectTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * List recent (last 7 days) DRAFT/AWAITING_APPROVAL proposals
     * authored by the same `proposed_by` scope so the orchestrator can
     * run a cosine-similarity dedup check before InsertProposal. Empty
     * `project_id` matches all projects under the client.
     * </pre>
     */
    public com.jervis.contracts.server.DedupResponse listPendingProposalsForDedup(com.jervis.contracts.server.DedupRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListPendingProposalsForDedupMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerTaskProposalService.
   * <pre>
   * ServerTaskProposalService is the Kotlin-owned surface for the
   * Claude CLI proposal lifecycle. The MCP tools `propose_task`,
   * `update_proposed_task`, `send_for_approval`, `task_approve`,
   * `task_reject` ultimately delegate to these RPCs (via the
   * orchestrator's OrchestratorProposalService for create/update — which
   * also runs embedding + dedup — and directly here for approve/reject).
   * The server is the single writer for `tasks` documents; proposal
   * stage transitions are atomic via MongoTemplate findOneAndUpdate
   * guarded by the current `proposalStage` so out-of-order calls return
   * INVALID_STATE rather than racing.
   * </pre>
   */
  public static final class ServerTaskProposalServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerTaskProposalServiceFutureStub> {
    private ServerTaskProposalServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTaskProposalServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTaskProposalServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Insert a fresh proposal in DRAFT state.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.InsertProposalResponse> insertProposal(
        com.jervis.contracts.server.InsertProposalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInsertProposalMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Update mutable fields of a DRAFT or REJECTED proposal (atomic
     * findOneAndUpdate filtered by stage). AWAITING_APPROVAL / APPROVED
     * proposals are immutable; the call returns INVALID_STATE.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.UpdateProposalResponse> updateProposal(
        com.jervis.contracts.server.UpdateProposalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateProposalMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * DRAFT → AWAITING_APPROVAL transition. Idempotent on terminal states
     * returns INVALID_STATE.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ProposalActionResponse> sendForApproval(
        com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendForApprovalMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → APPROVED + state=QUEUED. Used by UI on
     * user approve action.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ProposalActionResponse> approveTask(
        com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getApproveTaskMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * AWAITING_APPROVAL → REJECTED + proposalRejectionReason set. Used by
     * UI on user reject action.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ProposalActionResponse> rejectTask(
        com.jervis.contracts.server.RejectTaskRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRejectTaskMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * List recent (last 7 days) DRAFT/AWAITING_APPROVAL proposals
     * authored by the same `proposed_by` scope so the orchestrator can
     * run a cosine-similarity dedup check before InsertProposal. Empty
     * `project_id` matches all projects under the client.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.DedupResponse> listPendingProposalsForDedup(
        com.jervis.contracts.server.DedupRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListPendingProposalsForDedupMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_INSERT_PROPOSAL = 0;
  private static final int METHODID_UPDATE_PROPOSAL = 1;
  private static final int METHODID_SEND_FOR_APPROVAL = 2;
  private static final int METHODID_APPROVE_TASK = 3;
  private static final int METHODID_REJECT_TASK = 4;
  private static final int METHODID_LIST_PENDING_PROPOSALS_FOR_DEDUP = 5;

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
        case METHODID_INSERT_PROPOSAL:
          serviceImpl.insertProposal((com.jervis.contracts.server.InsertProposalRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.InsertProposalResponse>) responseObserver);
          break;
        case METHODID_UPDATE_PROPOSAL:
          serviceImpl.updateProposal((com.jervis.contracts.server.UpdateProposalRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.UpdateProposalResponse>) responseObserver);
          break;
        case METHODID_SEND_FOR_APPROVAL:
          serviceImpl.sendForApproval((com.jervis.contracts.server.TaskIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProposalActionResponse>) responseObserver);
          break;
        case METHODID_APPROVE_TASK:
          serviceImpl.approveTask((com.jervis.contracts.server.TaskIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProposalActionResponse>) responseObserver);
          break;
        case METHODID_REJECT_TASK:
          serviceImpl.rejectTask((com.jervis.contracts.server.RejectTaskRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ProposalActionResponse>) responseObserver);
          break;
        case METHODID_LIST_PENDING_PROPOSALS_FOR_DEDUP:
          serviceImpl.listPendingProposalsForDedup((com.jervis.contracts.server.DedupRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.DedupResponse>) responseObserver);
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
          getInsertProposalMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.InsertProposalRequest,
              com.jervis.contracts.server.InsertProposalResponse>(
                service, METHODID_INSERT_PROPOSAL)))
        .addMethod(
          getUpdateProposalMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.UpdateProposalRequest,
              com.jervis.contracts.server.UpdateProposalResponse>(
                service, METHODID_UPDATE_PROPOSAL)))
        .addMethod(
          getSendForApprovalMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TaskIdRequest,
              com.jervis.contracts.server.ProposalActionResponse>(
                service, METHODID_SEND_FOR_APPROVAL)))
        .addMethod(
          getApproveTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TaskIdRequest,
              com.jervis.contracts.server.ProposalActionResponse>(
                service, METHODID_APPROVE_TASK)))
        .addMethod(
          getRejectTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.RejectTaskRequest,
              com.jervis.contracts.server.ProposalActionResponse>(
                service, METHODID_REJECT_TASK)))
        .addMethod(
          getListPendingProposalsForDedupMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.DedupRequest,
              com.jervis.contracts.server.DedupResponse>(
                service, METHODID_LIST_PENDING_PROPOSALS_FOR_DEDUP)))
        .build();
  }

  private static abstract class ServerTaskProposalServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerTaskProposalServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerTaskProposalProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerTaskProposalService");
    }
  }

  private static final class ServerTaskProposalServiceFileDescriptorSupplier
      extends ServerTaskProposalServiceBaseDescriptorSupplier {
    ServerTaskProposalServiceFileDescriptorSupplier() {}
  }

  private static final class ServerTaskProposalServiceMethodDescriptorSupplier
      extends ServerTaskProposalServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerTaskProposalServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerTaskProposalServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerTaskProposalServiceFileDescriptorSupplier())
              .addMethod(getInsertProposalMethod())
              .addMethod(getUpdateProposalMethod())
              .addMethod(getSendForApprovalMethod())
              .addMethod(getApproveTaskMethod())
              .addMethod(getRejectTaskMethod())
              .addMethod(getListPendingProposalsForDedupMethod())
              .build();
        }
      }
    }
    return result;
  }
}
