package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorProposalService — Python-owned write surface for the
 * Claude CLI proposal lifecycle. The MCP server delegates to this
 * service (rather than calling Kotlin directly) so the embed + dedup
 * logic lives in one place. The orchestrator then forwards to the
 * Kotlin ServerTaskProposalService for the actual Mongo write.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorProposalServiceGrpc {

  private OrchestratorProposalServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorProposalService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ProposeTaskRequest,
      com.jervis.contracts.orchestrator.ProposeTaskResponse> getProposeTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ProposeTask",
      requestType = com.jervis.contracts.orchestrator.ProposeTaskRequest.class,
      responseType = com.jervis.contracts.orchestrator.ProposeTaskResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ProposeTaskRequest,
      com.jervis.contracts.orchestrator.ProposeTaskResponse> getProposeTaskMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.ProposeTaskRequest, com.jervis.contracts.orchestrator.ProposeTaskResponse> getProposeTaskMethod;
    if ((getProposeTaskMethod = OrchestratorProposalServiceGrpc.getProposeTaskMethod) == null) {
      synchronized (OrchestratorProposalServiceGrpc.class) {
        if ((getProposeTaskMethod = OrchestratorProposalServiceGrpc.getProposeTaskMethod) == null) {
          OrchestratorProposalServiceGrpc.getProposeTaskMethod = getProposeTaskMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.ProposeTaskRequest, com.jervis.contracts.orchestrator.ProposeTaskResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ProposeTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ProposeTaskRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ProposeTaskResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorProposalServiceMethodDescriptorSupplier("ProposeTask"))
              .build();
        }
      }
    }
    return getProposeTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.UpdateProposedTaskRequest,
      com.jervis.contracts.orchestrator.UpdateProposedTaskResponse> getUpdateProposedTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateProposedTask",
      requestType = com.jervis.contracts.orchestrator.UpdateProposedTaskRequest.class,
      responseType = com.jervis.contracts.orchestrator.UpdateProposedTaskResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.UpdateProposedTaskRequest,
      com.jervis.contracts.orchestrator.UpdateProposedTaskResponse> getUpdateProposedTaskMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.UpdateProposedTaskRequest, com.jervis.contracts.orchestrator.UpdateProposedTaskResponse> getUpdateProposedTaskMethod;
    if ((getUpdateProposedTaskMethod = OrchestratorProposalServiceGrpc.getUpdateProposedTaskMethod) == null) {
      synchronized (OrchestratorProposalServiceGrpc.class) {
        if ((getUpdateProposedTaskMethod = OrchestratorProposalServiceGrpc.getUpdateProposedTaskMethod) == null) {
          OrchestratorProposalServiceGrpc.getUpdateProposedTaskMethod = getUpdateProposedTaskMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.UpdateProposedTaskRequest, com.jervis.contracts.orchestrator.UpdateProposedTaskResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateProposedTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.UpdateProposedTaskRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.UpdateProposedTaskResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorProposalServiceMethodDescriptorSupplier("UpdateProposedTask"))
              .build();
        }
      }
    }
    return getUpdateProposedTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.TaskIdRequest,
      com.jervis.contracts.orchestrator.ProposalActionResponse> getSendForApprovalMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendForApproval",
      requestType = com.jervis.contracts.orchestrator.TaskIdRequest.class,
      responseType = com.jervis.contracts.orchestrator.ProposalActionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.TaskIdRequest,
      com.jervis.contracts.orchestrator.ProposalActionResponse> getSendForApprovalMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.TaskIdRequest, com.jervis.contracts.orchestrator.ProposalActionResponse> getSendForApprovalMethod;
    if ((getSendForApprovalMethod = OrchestratorProposalServiceGrpc.getSendForApprovalMethod) == null) {
      synchronized (OrchestratorProposalServiceGrpc.class) {
        if ((getSendForApprovalMethod = OrchestratorProposalServiceGrpc.getSendForApprovalMethod) == null) {
          OrchestratorProposalServiceGrpc.getSendForApprovalMethod = getSendForApprovalMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.TaskIdRequest, com.jervis.contracts.orchestrator.ProposalActionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendForApproval"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.TaskIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.ProposalActionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorProposalServiceMethodDescriptorSupplier("SendForApproval"))
              .build();
        }
      }
    }
    return getSendForApprovalMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorProposalServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorProposalServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorProposalServiceStub>() {
        @java.lang.Override
        public OrchestratorProposalServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorProposalServiceStub(channel, callOptions);
        }
      };
    return OrchestratorProposalServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static OrchestratorProposalServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorProposalServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorProposalServiceBlockingV2Stub>() {
        @java.lang.Override
        public OrchestratorProposalServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorProposalServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return OrchestratorProposalServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorProposalServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorProposalServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorProposalServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorProposalServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorProposalServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorProposalServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorProposalServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorProposalServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorProposalServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorProposalServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorProposalServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorProposalServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorProposalService — Python-owned write surface for the
   * Claude CLI proposal lifecycle. The MCP server delegates to this
   * service (rather than calling Kotlin directly) so the embed + dedup
   * logic lives in one place. The orchestrator then forwards to the
   * Kotlin ServerTaskProposalService for the actual Mongo write.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Embed title+description, run a 3-tier dedup against recent
     * proposals authored by the same scope, and INSERT a DRAFT proposal.
     * Returns the new task_id on success, or a hint when a near-duplicate
     * already exists.
     * </pre>
     */
    default void proposeTask(com.jervis.contracts.orchestrator.ProposeTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ProposeTaskResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getProposeTaskMethod(), responseObserver);
    }

    /**
     * <pre>
     * Re-embed title/description if changed and forward to Kotlin
     * UpdateProposal. Stage-guarded server-side (DRAFT/REJECTED only).
     * </pre>
     */
    default void updateProposedTask(com.jervis.contracts.orchestrator.UpdateProposedTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.UpdateProposedTaskResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateProposedTaskMethod(), responseObserver);
    }

    /**
     * <pre>
     * Plain forward to Kotlin SendForApproval — no orchestrator-side
     * logic beyond audit logging.
     * </pre>
     */
    default void sendForApproval(com.jervis.contracts.orchestrator.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ProposalActionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendForApprovalMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorProposalService.
   * <pre>
   * OrchestratorProposalService — Python-owned write surface for the
   * Claude CLI proposal lifecycle. The MCP server delegates to this
   * service (rather than calling Kotlin directly) so the embed + dedup
   * logic lives in one place. The orchestrator then forwards to the
   * Kotlin ServerTaskProposalService for the actual Mongo write.
   * </pre>
   */
  public static abstract class OrchestratorProposalServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorProposalServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorProposalService.
   * <pre>
   * OrchestratorProposalService — Python-owned write surface for the
   * Claude CLI proposal lifecycle. The MCP server delegates to this
   * service (rather than calling Kotlin directly) so the embed + dedup
   * logic lives in one place. The orchestrator then forwards to the
   * Kotlin ServerTaskProposalService for the actual Mongo write.
   * </pre>
   */
  public static final class OrchestratorProposalServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorProposalServiceStub> {
    private OrchestratorProposalServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorProposalServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorProposalServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Embed title+description, run a 3-tier dedup against recent
     * proposals authored by the same scope, and INSERT a DRAFT proposal.
     * Returns the new task_id on success, or a hint when a near-duplicate
     * already exists.
     * </pre>
     */
    public void proposeTask(com.jervis.contracts.orchestrator.ProposeTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ProposeTaskResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getProposeTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Re-embed title/description if changed and forward to Kotlin
     * UpdateProposal. Stage-guarded server-side (DRAFT/REJECTED only).
     * </pre>
     */
    public void updateProposedTask(com.jervis.contracts.orchestrator.UpdateProposedTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.UpdateProposedTaskResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateProposedTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Plain forward to Kotlin SendForApproval — no orchestrator-side
     * logic beyond audit logging.
     * </pre>
     */
    public void sendForApproval(com.jervis.contracts.orchestrator.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ProposalActionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendForApprovalMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorProposalService.
   * <pre>
   * OrchestratorProposalService — Python-owned write surface for the
   * Claude CLI proposal lifecycle. The MCP server delegates to this
   * service (rather than calling Kotlin directly) so the embed + dedup
   * logic lives in one place. The orchestrator then forwards to the
   * Kotlin ServerTaskProposalService for the actual Mongo write.
   * </pre>
   */
  public static final class OrchestratorProposalServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorProposalServiceBlockingV2Stub> {
    private OrchestratorProposalServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorProposalServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorProposalServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Embed title+description, run a 3-tier dedup against recent
     * proposals authored by the same scope, and INSERT a DRAFT proposal.
     * Returns the new task_id on success, or a hint when a near-duplicate
     * already exists.
     * </pre>
     */
    public com.jervis.contracts.orchestrator.ProposeTaskResponse proposeTask(com.jervis.contracts.orchestrator.ProposeTaskRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getProposeTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Re-embed title/description if changed and forward to Kotlin
     * UpdateProposal. Stage-guarded server-side (DRAFT/REJECTED only).
     * </pre>
     */
    public com.jervis.contracts.orchestrator.UpdateProposedTaskResponse updateProposedTask(com.jervis.contracts.orchestrator.UpdateProposedTaskRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateProposedTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Plain forward to Kotlin SendForApproval — no orchestrator-side
     * logic beyond audit logging.
     * </pre>
     */
    public com.jervis.contracts.orchestrator.ProposalActionResponse sendForApproval(com.jervis.contracts.orchestrator.TaskIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSendForApprovalMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service OrchestratorProposalService.
   * <pre>
   * OrchestratorProposalService — Python-owned write surface for the
   * Claude CLI proposal lifecycle. The MCP server delegates to this
   * service (rather than calling Kotlin directly) so the embed + dedup
   * logic lives in one place. The orchestrator then forwards to the
   * Kotlin ServerTaskProposalService for the actual Mongo write.
   * </pre>
   */
  public static final class OrchestratorProposalServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorProposalServiceBlockingStub> {
    private OrchestratorProposalServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorProposalServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorProposalServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Embed title+description, run a 3-tier dedup against recent
     * proposals authored by the same scope, and INSERT a DRAFT proposal.
     * Returns the new task_id on success, or a hint when a near-duplicate
     * already exists.
     * </pre>
     */
    public com.jervis.contracts.orchestrator.ProposeTaskResponse proposeTask(com.jervis.contracts.orchestrator.ProposeTaskRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getProposeTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Re-embed title/description if changed and forward to Kotlin
     * UpdateProposal. Stage-guarded server-side (DRAFT/REJECTED only).
     * </pre>
     */
    public com.jervis.contracts.orchestrator.UpdateProposedTaskResponse updateProposedTask(com.jervis.contracts.orchestrator.UpdateProposedTaskRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateProposedTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Plain forward to Kotlin SendForApproval — no orchestrator-side
     * logic beyond audit logging.
     * </pre>
     */
    public com.jervis.contracts.orchestrator.ProposalActionResponse sendForApproval(com.jervis.contracts.orchestrator.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendForApprovalMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorProposalService.
   * <pre>
   * OrchestratorProposalService — Python-owned write surface for the
   * Claude CLI proposal lifecycle. The MCP server delegates to this
   * service (rather than calling Kotlin directly) so the embed + dedup
   * logic lives in one place. The orchestrator then forwards to the
   * Kotlin ServerTaskProposalService for the actual Mongo write.
   * </pre>
   */
  public static final class OrchestratorProposalServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorProposalServiceFutureStub> {
    private OrchestratorProposalServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorProposalServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorProposalServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Embed title+description, run a 3-tier dedup against recent
     * proposals authored by the same scope, and INSERT a DRAFT proposal.
     * Returns the new task_id on success, or a hint when a near-duplicate
     * already exists.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.ProposeTaskResponse> proposeTask(
        com.jervis.contracts.orchestrator.ProposeTaskRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getProposeTaskMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Re-embed title/description if changed and forward to Kotlin
     * UpdateProposal. Stage-guarded server-side (DRAFT/REJECTED only).
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.UpdateProposedTaskResponse> updateProposedTask(
        com.jervis.contracts.orchestrator.UpdateProposedTaskRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateProposedTaskMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Plain forward to Kotlin SendForApproval — no orchestrator-side
     * logic beyond audit logging.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.ProposalActionResponse> sendForApproval(
        com.jervis.contracts.orchestrator.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendForApprovalMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_PROPOSE_TASK = 0;
  private static final int METHODID_UPDATE_PROPOSED_TASK = 1;
  private static final int METHODID_SEND_FOR_APPROVAL = 2;

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
        case METHODID_PROPOSE_TASK:
          serviceImpl.proposeTask((com.jervis.contracts.orchestrator.ProposeTaskRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ProposeTaskResponse>) responseObserver);
          break;
        case METHODID_UPDATE_PROPOSED_TASK:
          serviceImpl.updateProposedTask((com.jervis.contracts.orchestrator.UpdateProposedTaskRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.UpdateProposedTaskResponse>) responseObserver);
          break;
        case METHODID_SEND_FOR_APPROVAL:
          serviceImpl.sendForApproval((com.jervis.contracts.orchestrator.TaskIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.ProposalActionResponse>) responseObserver);
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
          getProposeTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.ProposeTaskRequest,
              com.jervis.contracts.orchestrator.ProposeTaskResponse>(
                service, METHODID_PROPOSE_TASK)))
        .addMethod(
          getUpdateProposedTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.UpdateProposedTaskRequest,
              com.jervis.contracts.orchestrator.UpdateProposedTaskResponse>(
                service, METHODID_UPDATE_PROPOSED_TASK)))
        .addMethod(
          getSendForApprovalMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.TaskIdRequest,
              com.jervis.contracts.orchestrator.ProposalActionResponse>(
                service, METHODID_SEND_FOR_APPROVAL)))
        .build();
  }

  private static abstract class OrchestratorProposalServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorProposalServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorProposalProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorProposalService");
    }
  }

  private static final class OrchestratorProposalServiceFileDescriptorSupplier
      extends OrchestratorProposalServiceBaseDescriptorSupplier {
    OrchestratorProposalServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorProposalServiceMethodDescriptorSupplier
      extends OrchestratorProposalServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorProposalServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorProposalServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorProposalServiceFileDescriptorSupplier())
              .addMethod(getProposeTaskMethod())
              .addMethod(getUpdateProposedTaskMethod())
              .addMethod(getSendForApprovalMethod())
              .build();
        }
      }
    }
    return result;
  }
}
