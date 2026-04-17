package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerFinanceService exposes the per-client financial surface consumed
 * by the orchestrator's chat tools (summary, list records, record payment,
 * list contracts). Contract creation and overdue re-scan are not migrated
 * because they have no Python consumer.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerFinanceServiceGrpc {

  private ServerFinanceServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerFinanceService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetFinancialSummaryRequest,
      com.jervis.contracts.server.GetFinancialSummaryResponse> getGetSummaryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSummary",
      requestType = com.jervis.contracts.server.GetFinancialSummaryRequest.class,
      responseType = com.jervis.contracts.server.GetFinancialSummaryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetFinancialSummaryRequest,
      com.jervis.contracts.server.GetFinancialSummaryResponse> getGetSummaryMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetFinancialSummaryRequest, com.jervis.contracts.server.GetFinancialSummaryResponse> getGetSummaryMethod;
    if ((getGetSummaryMethod = ServerFinanceServiceGrpc.getGetSummaryMethod) == null) {
      synchronized (ServerFinanceServiceGrpc.class) {
        if ((getGetSummaryMethod = ServerFinanceServiceGrpc.getGetSummaryMethod) == null) {
          ServerFinanceServiceGrpc.getGetSummaryMethod = getGetSummaryMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetFinancialSummaryRequest, com.jervis.contracts.server.GetFinancialSummaryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSummary"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetFinancialSummaryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetFinancialSummaryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerFinanceServiceMethodDescriptorSupplier("GetSummary"))
              .build();
        }
      }
    }
    return getGetSummaryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListFinancialRecordsRequest,
      com.jervis.contracts.server.ListFinancialRecordsResponse> getListRecordsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListRecords",
      requestType = com.jervis.contracts.server.ListFinancialRecordsRequest.class,
      responseType = com.jervis.contracts.server.ListFinancialRecordsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListFinancialRecordsRequest,
      com.jervis.contracts.server.ListFinancialRecordsResponse> getListRecordsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListFinancialRecordsRequest, com.jervis.contracts.server.ListFinancialRecordsResponse> getListRecordsMethod;
    if ((getListRecordsMethod = ServerFinanceServiceGrpc.getListRecordsMethod) == null) {
      synchronized (ServerFinanceServiceGrpc.class) {
        if ((getListRecordsMethod = ServerFinanceServiceGrpc.getListRecordsMethod) == null) {
          ServerFinanceServiceGrpc.getListRecordsMethod = getListRecordsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListFinancialRecordsRequest, com.jervis.contracts.server.ListFinancialRecordsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListRecords"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListFinancialRecordsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListFinancialRecordsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerFinanceServiceMethodDescriptorSupplier("ListRecords"))
              .build();
        }
      }
    }
    return getListRecordsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateFinancialRecordRequest,
      com.jervis.contracts.server.CreateFinancialRecordResponse> getCreateRecordMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateRecord",
      requestType = com.jervis.contracts.server.CreateFinancialRecordRequest.class,
      responseType = com.jervis.contracts.server.CreateFinancialRecordResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateFinancialRecordRequest,
      com.jervis.contracts.server.CreateFinancialRecordResponse> getCreateRecordMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateFinancialRecordRequest, com.jervis.contracts.server.CreateFinancialRecordResponse> getCreateRecordMethod;
    if ((getCreateRecordMethod = ServerFinanceServiceGrpc.getCreateRecordMethod) == null) {
      synchronized (ServerFinanceServiceGrpc.class) {
        if ((getCreateRecordMethod = ServerFinanceServiceGrpc.getCreateRecordMethod) == null) {
          ServerFinanceServiceGrpc.getCreateRecordMethod = getCreateRecordMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateFinancialRecordRequest, com.jervis.contracts.server.CreateFinancialRecordResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateRecord"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateFinancialRecordRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateFinancialRecordResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerFinanceServiceMethodDescriptorSupplier("CreateRecord"))
              .build();
        }
      }
    }
    return getCreateRecordMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListContractsRequest,
      com.jervis.contracts.server.ListContractsResponse> getListContractsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListContracts",
      requestType = com.jervis.contracts.server.ListContractsRequest.class,
      responseType = com.jervis.contracts.server.ListContractsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListContractsRequest,
      com.jervis.contracts.server.ListContractsResponse> getListContractsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListContractsRequest, com.jervis.contracts.server.ListContractsResponse> getListContractsMethod;
    if ((getListContractsMethod = ServerFinanceServiceGrpc.getListContractsMethod) == null) {
      synchronized (ServerFinanceServiceGrpc.class) {
        if ((getListContractsMethod = ServerFinanceServiceGrpc.getListContractsMethod) == null) {
          ServerFinanceServiceGrpc.getListContractsMethod = getListContractsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListContractsRequest, com.jervis.contracts.server.ListContractsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListContracts"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListContractsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListContractsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerFinanceServiceMethodDescriptorSupplier("ListContracts"))
              .build();
        }
      }
    }
    return getListContractsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerFinanceServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerFinanceServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerFinanceServiceStub>() {
        @java.lang.Override
        public ServerFinanceServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerFinanceServiceStub(channel, callOptions);
        }
      };
    return ServerFinanceServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerFinanceServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerFinanceServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerFinanceServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerFinanceServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerFinanceServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerFinanceServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerFinanceServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerFinanceServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerFinanceServiceBlockingStub>() {
        @java.lang.Override
        public ServerFinanceServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerFinanceServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerFinanceServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerFinanceServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerFinanceServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerFinanceServiceFutureStub>() {
        @java.lang.Override
        public ServerFinanceServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerFinanceServiceFutureStub(channel, callOptions);
        }
      };
    return ServerFinanceServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerFinanceService exposes the per-client financial surface consumed
   * by the orchestrator's chat tools (summary, list records, record payment,
   * list contracts). Contract creation and overdue re-scan are not migrated
   * because they have no Python consumer.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void getSummary(com.jervis.contracts.server.GetFinancialSummaryRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetFinancialSummaryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetSummaryMethod(), responseObserver);
    }

    /**
     */
    default void listRecords(com.jervis.contracts.server.ListFinancialRecordsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListFinancialRecordsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListRecordsMethod(), responseObserver);
    }

    /**
     */
    default void createRecord(com.jervis.contracts.server.CreateFinancialRecordRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateFinancialRecordResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateRecordMethod(), responseObserver);
    }

    /**
     */
    default void listContracts(com.jervis.contracts.server.ListContractsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListContractsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListContractsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerFinanceService.
   * <pre>
   * ServerFinanceService exposes the per-client financial surface consumed
   * by the orchestrator's chat tools (summary, list records, record payment,
   * list contracts). Contract creation and overdue re-scan are not migrated
   * because they have no Python consumer.
   * </pre>
   */
  public static abstract class ServerFinanceServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerFinanceServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerFinanceService.
   * <pre>
   * ServerFinanceService exposes the per-client financial surface consumed
   * by the orchestrator's chat tools (summary, list records, record payment,
   * list contracts). Contract creation and overdue re-scan are not migrated
   * because they have no Python consumer.
   * </pre>
   */
  public static final class ServerFinanceServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerFinanceServiceStub> {
    private ServerFinanceServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerFinanceServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerFinanceServiceStub(channel, callOptions);
    }

    /**
     */
    public void getSummary(com.jervis.contracts.server.GetFinancialSummaryRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetFinancialSummaryResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetSummaryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listRecords(com.jervis.contracts.server.ListFinancialRecordsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListFinancialRecordsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListRecordsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createRecord(com.jervis.contracts.server.CreateFinancialRecordRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateFinancialRecordResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateRecordMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listContracts(com.jervis.contracts.server.ListContractsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListContractsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListContractsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerFinanceService.
   * <pre>
   * ServerFinanceService exposes the per-client financial surface consumed
   * by the orchestrator's chat tools (summary, list records, record payment,
   * list contracts). Contract creation and overdue re-scan are not migrated
   * because they have no Python consumer.
   * </pre>
   */
  public static final class ServerFinanceServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerFinanceServiceBlockingV2Stub> {
    private ServerFinanceServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerFinanceServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerFinanceServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.GetFinancialSummaryResponse getSummary(com.jervis.contracts.server.GetFinancialSummaryRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetSummaryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListFinancialRecordsResponse listRecords(com.jervis.contracts.server.ListFinancialRecordsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListRecordsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateFinancialRecordResponse createRecord(com.jervis.contracts.server.CreateFinancialRecordRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateRecordMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListContractsResponse listContracts(com.jervis.contracts.server.ListContractsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListContractsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerFinanceService.
   * <pre>
   * ServerFinanceService exposes the per-client financial surface consumed
   * by the orchestrator's chat tools (summary, list records, record payment,
   * list contracts). Contract creation and overdue re-scan are not migrated
   * because they have no Python consumer.
   * </pre>
   */
  public static final class ServerFinanceServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerFinanceServiceBlockingStub> {
    private ServerFinanceServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerFinanceServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerFinanceServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.GetFinancialSummaryResponse getSummary(com.jervis.contracts.server.GetFinancialSummaryRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSummaryMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListFinancialRecordsResponse listRecords(com.jervis.contracts.server.ListFinancialRecordsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListRecordsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateFinancialRecordResponse createRecord(com.jervis.contracts.server.CreateFinancialRecordRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateRecordMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ListContractsResponse listContracts(com.jervis.contracts.server.ListContractsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListContractsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerFinanceService.
   * <pre>
   * ServerFinanceService exposes the per-client financial surface consumed
   * by the orchestrator's chat tools (summary, list records, record payment,
   * list contracts). Contract creation and overdue re-scan are not migrated
   * because they have no Python consumer.
   * </pre>
   */
  public static final class ServerFinanceServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerFinanceServiceFutureStub> {
    private ServerFinanceServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerFinanceServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerFinanceServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetFinancialSummaryResponse> getSummary(
        com.jervis.contracts.server.GetFinancialSummaryRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetSummaryMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListFinancialRecordsResponse> listRecords(
        com.jervis.contracts.server.ListFinancialRecordsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListRecordsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CreateFinancialRecordResponse> createRecord(
        com.jervis.contracts.server.CreateFinancialRecordRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateRecordMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ListContractsResponse> listContracts(
        com.jervis.contracts.server.ListContractsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListContractsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_SUMMARY = 0;
  private static final int METHODID_LIST_RECORDS = 1;
  private static final int METHODID_CREATE_RECORD = 2;
  private static final int METHODID_LIST_CONTRACTS = 3;

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
        case METHODID_GET_SUMMARY:
          serviceImpl.getSummary((com.jervis.contracts.server.GetFinancialSummaryRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetFinancialSummaryResponse>) responseObserver);
          break;
        case METHODID_LIST_RECORDS:
          serviceImpl.listRecords((com.jervis.contracts.server.ListFinancialRecordsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListFinancialRecordsResponse>) responseObserver);
          break;
        case METHODID_CREATE_RECORD:
          serviceImpl.createRecord((com.jervis.contracts.server.CreateFinancialRecordRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateFinancialRecordResponse>) responseObserver);
          break;
        case METHODID_LIST_CONTRACTS:
          serviceImpl.listContracts((com.jervis.contracts.server.ListContractsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ListContractsResponse>) responseObserver);
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
          getGetSummaryMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetFinancialSummaryRequest,
              com.jervis.contracts.server.GetFinancialSummaryResponse>(
                service, METHODID_GET_SUMMARY)))
        .addMethod(
          getListRecordsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListFinancialRecordsRequest,
              com.jervis.contracts.server.ListFinancialRecordsResponse>(
                service, METHODID_LIST_RECORDS)))
        .addMethod(
          getCreateRecordMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateFinancialRecordRequest,
              com.jervis.contracts.server.CreateFinancialRecordResponse>(
                service, METHODID_CREATE_RECORD)))
        .addMethod(
          getListContractsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListContractsRequest,
              com.jervis.contracts.server.ListContractsResponse>(
                service, METHODID_LIST_CONTRACTS)))
        .build();
  }

  private static abstract class ServerFinanceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerFinanceServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerFinanceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerFinanceService");
    }
  }

  private static final class ServerFinanceServiceFileDescriptorSupplier
      extends ServerFinanceServiceBaseDescriptorSupplier {
    ServerFinanceServiceFileDescriptorSupplier() {}
  }

  private static final class ServerFinanceServiceMethodDescriptorSupplier
      extends ServerFinanceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerFinanceServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerFinanceServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerFinanceServiceFileDescriptorSupplier())
              .addMethod(getGetSummaryMethod())
              .addMethod(getListRecordsMethod())
              .addMethod(getCreateRecordMethod())
              .addMethod(getListContractsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
