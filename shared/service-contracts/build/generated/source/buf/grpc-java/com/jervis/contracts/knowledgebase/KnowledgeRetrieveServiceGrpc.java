package com.jervis.contracts.knowledgebase;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * KnowledgeRetrieveService — vector + graph hybrid retrieval over the KB.
 * Read-side RPCs only; ingestion lives in ingest.proto, graph walks live
 * in graph.proto.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class KnowledgeRetrieveServiceGrpc {

  private KnowledgeRetrieveServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.knowledgebase.KnowledgeRetrieveService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetrievalRequest,
      com.jervis.contracts.knowledgebase.EvidencePack> getRetrieveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Retrieve",
      requestType = com.jervis.contracts.knowledgebase.RetrievalRequest.class,
      responseType = com.jervis.contracts.knowledgebase.EvidencePack.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetrievalRequest,
      com.jervis.contracts.knowledgebase.EvidencePack> getRetrieveMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetrievalRequest, com.jervis.contracts.knowledgebase.EvidencePack> getRetrieveMethod;
    if ((getRetrieveMethod = KnowledgeRetrieveServiceGrpc.getRetrieveMethod) == null) {
      synchronized (KnowledgeRetrieveServiceGrpc.class) {
        if ((getRetrieveMethod = KnowledgeRetrieveServiceGrpc.getRetrieveMethod) == null) {
          KnowledgeRetrieveServiceGrpc.getRetrieveMethod = getRetrieveMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.RetrievalRequest, com.jervis.contracts.knowledgebase.EvidencePack>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Retrieve"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.RetrievalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.EvidencePack.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeRetrieveServiceMethodDescriptorSupplier("Retrieve"))
              .build();
        }
      }
    }
    return getRetrieveMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetrievalRequest,
      com.jervis.contracts.knowledgebase.EvidencePack> getRetrieveSimpleMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RetrieveSimple",
      requestType = com.jervis.contracts.knowledgebase.RetrievalRequest.class,
      responseType = com.jervis.contracts.knowledgebase.EvidencePack.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetrievalRequest,
      com.jervis.contracts.knowledgebase.EvidencePack> getRetrieveSimpleMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RetrievalRequest, com.jervis.contracts.knowledgebase.EvidencePack> getRetrieveSimpleMethod;
    if ((getRetrieveSimpleMethod = KnowledgeRetrieveServiceGrpc.getRetrieveSimpleMethod) == null) {
      synchronized (KnowledgeRetrieveServiceGrpc.class) {
        if ((getRetrieveSimpleMethod = KnowledgeRetrieveServiceGrpc.getRetrieveSimpleMethod) == null) {
          KnowledgeRetrieveServiceGrpc.getRetrieveSimpleMethod = getRetrieveSimpleMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.RetrievalRequest, com.jervis.contracts.knowledgebase.EvidencePack>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RetrieveSimple"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.RetrievalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.EvidencePack.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeRetrieveServiceMethodDescriptorSupplier("RetrieveSimple"))
              .build();
        }
      }
    }
    return getRetrieveSimpleMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.HybridRetrievalRequest,
      com.jervis.contracts.knowledgebase.HybridEvidencePack> getRetrieveHybridMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RetrieveHybrid",
      requestType = com.jervis.contracts.knowledgebase.HybridRetrievalRequest.class,
      responseType = com.jervis.contracts.knowledgebase.HybridEvidencePack.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.HybridRetrievalRequest,
      com.jervis.contracts.knowledgebase.HybridEvidencePack> getRetrieveHybridMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.HybridRetrievalRequest, com.jervis.contracts.knowledgebase.HybridEvidencePack> getRetrieveHybridMethod;
    if ((getRetrieveHybridMethod = KnowledgeRetrieveServiceGrpc.getRetrieveHybridMethod) == null) {
      synchronized (KnowledgeRetrieveServiceGrpc.class) {
        if ((getRetrieveHybridMethod = KnowledgeRetrieveServiceGrpc.getRetrieveHybridMethod) == null) {
          KnowledgeRetrieveServiceGrpc.getRetrieveHybridMethod = getRetrieveHybridMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.HybridRetrievalRequest, com.jervis.contracts.knowledgebase.HybridEvidencePack>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RetrieveHybrid"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.HybridRetrievalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.HybridEvidencePack.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeRetrieveServiceMethodDescriptorSupplier("RetrieveHybrid"))
              .build();
        }
      }
    }
    return getRetrieveHybridMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.TraversalRequest,
      com.jervis.contracts.knowledgebase.JoernAnalyzeResult> getAnalyzeCodeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AnalyzeCode",
      requestType = com.jervis.contracts.knowledgebase.TraversalRequest.class,
      responseType = com.jervis.contracts.knowledgebase.JoernAnalyzeResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.TraversalRequest,
      com.jervis.contracts.knowledgebase.JoernAnalyzeResult> getAnalyzeCodeMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.TraversalRequest, com.jervis.contracts.knowledgebase.JoernAnalyzeResult> getAnalyzeCodeMethod;
    if ((getAnalyzeCodeMethod = KnowledgeRetrieveServiceGrpc.getAnalyzeCodeMethod) == null) {
      synchronized (KnowledgeRetrieveServiceGrpc.class) {
        if ((getAnalyzeCodeMethod = KnowledgeRetrieveServiceGrpc.getAnalyzeCodeMethod) == null) {
          KnowledgeRetrieveServiceGrpc.getAnalyzeCodeMethod = getAnalyzeCodeMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.TraversalRequest, com.jervis.contracts.knowledgebase.JoernAnalyzeResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AnalyzeCode"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.TraversalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.JoernAnalyzeResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeRetrieveServiceMethodDescriptorSupplier("AnalyzeCode"))
              .build();
        }
      }
    }
    return getAnalyzeCodeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.JoernScanRequest,
      com.jervis.contracts.knowledgebase.JoernScanResult> getJoernScanMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "JoernScan",
      requestType = com.jervis.contracts.knowledgebase.JoernScanRequest.class,
      responseType = com.jervis.contracts.knowledgebase.JoernScanResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.JoernScanRequest,
      com.jervis.contracts.knowledgebase.JoernScanResult> getJoernScanMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.JoernScanRequest, com.jervis.contracts.knowledgebase.JoernScanResult> getJoernScanMethod;
    if ((getJoernScanMethod = KnowledgeRetrieveServiceGrpc.getJoernScanMethod) == null) {
      synchronized (KnowledgeRetrieveServiceGrpc.class) {
        if ((getJoernScanMethod = KnowledgeRetrieveServiceGrpc.getJoernScanMethod) == null) {
          KnowledgeRetrieveServiceGrpc.getJoernScanMethod = getJoernScanMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.JoernScanRequest, com.jervis.contracts.knowledgebase.JoernScanResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "JoernScan"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.JoernScanRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.JoernScanResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeRetrieveServiceMethodDescriptorSupplier("JoernScan"))
              .build();
        }
      }
    }
    return getJoernScanMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ListByKindRequest,
      com.jervis.contracts.knowledgebase.ChunkList> getListChunksByKindMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListChunksByKind",
      requestType = com.jervis.contracts.knowledgebase.ListByKindRequest.class,
      responseType = com.jervis.contracts.knowledgebase.ChunkList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ListByKindRequest,
      com.jervis.contracts.knowledgebase.ChunkList> getListChunksByKindMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ListByKindRequest, com.jervis.contracts.knowledgebase.ChunkList> getListChunksByKindMethod;
    if ((getListChunksByKindMethod = KnowledgeRetrieveServiceGrpc.getListChunksByKindMethod) == null) {
      synchronized (KnowledgeRetrieveServiceGrpc.class) {
        if ((getListChunksByKindMethod = KnowledgeRetrieveServiceGrpc.getListChunksByKindMethod) == null) {
          KnowledgeRetrieveServiceGrpc.getListChunksByKindMethod = getListChunksByKindMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ListByKindRequest, com.jervis.contracts.knowledgebase.ChunkList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListChunksByKind"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ListByKindRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ChunkList.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeRetrieveServiceMethodDescriptorSupplier("ListChunksByKind"))
              .build();
        }
      }
    }
    return getListChunksByKindMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static KnowledgeRetrieveServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeRetrieveServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeRetrieveServiceStub>() {
        @java.lang.Override
        public KnowledgeRetrieveServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeRetrieveServiceStub(channel, callOptions);
        }
      };
    return KnowledgeRetrieveServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static KnowledgeRetrieveServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeRetrieveServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeRetrieveServiceBlockingV2Stub>() {
        @java.lang.Override
        public KnowledgeRetrieveServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeRetrieveServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return KnowledgeRetrieveServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static KnowledgeRetrieveServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeRetrieveServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeRetrieveServiceBlockingStub>() {
        @java.lang.Override
        public KnowledgeRetrieveServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeRetrieveServiceBlockingStub(channel, callOptions);
        }
      };
    return KnowledgeRetrieveServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static KnowledgeRetrieveServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeRetrieveServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeRetrieveServiceFutureStub>() {
        @java.lang.Override
        public KnowledgeRetrieveServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeRetrieveServiceFutureStub(channel, callOptions);
        }
      };
    return KnowledgeRetrieveServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * KnowledgeRetrieveService — vector + graph hybrid retrieval over the KB.
   * Read-side RPCs only; ingestion lives in ingest.proto, graph walks live
   * in graph.proto.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void retrieve(com.jervis.contracts.knowledgebase.RetrievalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EvidencePack> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRetrieveMethod(), responseObserver);
    }

    /**
     */
    default void retrieveSimple(com.jervis.contracts.knowledgebase.RetrievalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EvidencePack> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRetrieveSimpleMethod(), responseObserver);
    }

    /**
     */
    default void retrieveHybrid(com.jervis.contracts.knowledgebase.HybridRetrievalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.HybridEvidencePack> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRetrieveHybridMethod(), responseObserver);
    }

    /**
     */
    default void analyzeCode(com.jervis.contracts.knowledgebase.TraversalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.JoernAnalyzeResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAnalyzeCodeMethod(), responseObserver);
    }

    /**
     */
    default void joernScan(com.jervis.contracts.knowledgebase.JoernScanRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.JoernScanResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getJoernScanMethod(), responseObserver);
    }

    /**
     */
    default void listChunksByKind(com.jervis.contracts.knowledgebase.ListByKindRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ChunkList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListChunksByKindMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service KnowledgeRetrieveService.
   * <pre>
   * KnowledgeRetrieveService — vector + graph hybrid retrieval over the KB.
   * Read-side RPCs only; ingestion lives in ingest.proto, graph walks live
   * in graph.proto.
   * </pre>
   */
  public static abstract class KnowledgeRetrieveServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return KnowledgeRetrieveServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service KnowledgeRetrieveService.
   * <pre>
   * KnowledgeRetrieveService — vector + graph hybrid retrieval over the KB.
   * Read-side RPCs only; ingestion lives in ingest.proto, graph walks live
   * in graph.proto.
   * </pre>
   */
  public static final class KnowledgeRetrieveServiceStub
      extends io.grpc.stub.AbstractAsyncStub<KnowledgeRetrieveServiceStub> {
    private KnowledgeRetrieveServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeRetrieveServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeRetrieveServiceStub(channel, callOptions);
    }

    /**
     */
    public void retrieve(com.jervis.contracts.knowledgebase.RetrievalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EvidencePack> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRetrieveMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void retrieveSimple(com.jervis.contracts.knowledgebase.RetrievalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EvidencePack> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRetrieveSimpleMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void retrieveHybrid(com.jervis.contracts.knowledgebase.HybridRetrievalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.HybridEvidencePack> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRetrieveHybridMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void analyzeCode(com.jervis.contracts.knowledgebase.TraversalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.JoernAnalyzeResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAnalyzeCodeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void joernScan(com.jervis.contracts.knowledgebase.JoernScanRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.JoernScanResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getJoernScanMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listChunksByKind(com.jervis.contracts.knowledgebase.ListByKindRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ChunkList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListChunksByKindMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service KnowledgeRetrieveService.
   * <pre>
   * KnowledgeRetrieveService — vector + graph hybrid retrieval over the KB.
   * Read-side RPCs only; ingestion lives in ingest.proto, graph walks live
   * in graph.proto.
   * </pre>
   */
  public static final class KnowledgeRetrieveServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeRetrieveServiceBlockingV2Stub> {
    private KnowledgeRetrieveServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeRetrieveServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeRetrieveServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.EvidencePack retrieve(com.jervis.contracts.knowledgebase.RetrievalRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRetrieveMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.EvidencePack retrieveSimple(com.jervis.contracts.knowledgebase.RetrievalRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRetrieveSimpleMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.HybridEvidencePack retrieveHybrid(com.jervis.contracts.knowledgebase.HybridRetrievalRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRetrieveHybridMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.JoernAnalyzeResult analyzeCode(com.jervis.contracts.knowledgebase.TraversalRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAnalyzeCodeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.JoernScanResult joernScan(com.jervis.contracts.knowledgebase.JoernScanRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getJoernScanMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ChunkList listChunksByKind(com.jervis.contracts.knowledgebase.ListByKindRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListChunksByKindMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service KnowledgeRetrieveService.
   * <pre>
   * KnowledgeRetrieveService — vector + graph hybrid retrieval over the KB.
   * Read-side RPCs only; ingestion lives in ingest.proto, graph walks live
   * in graph.proto.
   * </pre>
   */
  public static final class KnowledgeRetrieveServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeRetrieveServiceBlockingStub> {
    private KnowledgeRetrieveServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeRetrieveServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeRetrieveServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.EvidencePack retrieve(com.jervis.contracts.knowledgebase.RetrievalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRetrieveMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.EvidencePack retrieveSimple(com.jervis.contracts.knowledgebase.RetrievalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRetrieveSimpleMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.HybridEvidencePack retrieveHybrid(com.jervis.contracts.knowledgebase.HybridRetrievalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRetrieveHybridMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.JoernAnalyzeResult analyzeCode(com.jervis.contracts.knowledgebase.TraversalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAnalyzeCodeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.JoernScanResult joernScan(com.jervis.contracts.knowledgebase.JoernScanRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getJoernScanMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ChunkList listChunksByKind(com.jervis.contracts.knowledgebase.ListByKindRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListChunksByKindMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service KnowledgeRetrieveService.
   * <pre>
   * KnowledgeRetrieveService — vector + graph hybrid retrieval over the KB.
   * Read-side RPCs only; ingestion lives in ingest.proto, graph walks live
   * in graph.proto.
   * </pre>
   */
  public static final class KnowledgeRetrieveServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<KnowledgeRetrieveServiceFutureStub> {
    private KnowledgeRetrieveServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeRetrieveServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeRetrieveServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.EvidencePack> retrieve(
        com.jervis.contracts.knowledgebase.RetrievalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRetrieveMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.EvidencePack> retrieveSimple(
        com.jervis.contracts.knowledgebase.RetrievalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRetrieveSimpleMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.HybridEvidencePack> retrieveHybrid(
        com.jervis.contracts.knowledgebase.HybridRetrievalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRetrieveHybridMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.JoernAnalyzeResult> analyzeCode(
        com.jervis.contracts.knowledgebase.TraversalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAnalyzeCodeMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.JoernScanResult> joernScan(
        com.jervis.contracts.knowledgebase.JoernScanRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getJoernScanMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.ChunkList> listChunksByKind(
        com.jervis.contracts.knowledgebase.ListByKindRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListChunksByKindMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_RETRIEVE = 0;
  private static final int METHODID_RETRIEVE_SIMPLE = 1;
  private static final int METHODID_RETRIEVE_HYBRID = 2;
  private static final int METHODID_ANALYZE_CODE = 3;
  private static final int METHODID_JOERN_SCAN = 4;
  private static final int METHODID_LIST_CHUNKS_BY_KIND = 5;

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
        case METHODID_RETRIEVE:
          serviceImpl.retrieve((com.jervis.contracts.knowledgebase.RetrievalRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EvidencePack>) responseObserver);
          break;
        case METHODID_RETRIEVE_SIMPLE:
          serviceImpl.retrieveSimple((com.jervis.contracts.knowledgebase.RetrievalRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EvidencePack>) responseObserver);
          break;
        case METHODID_RETRIEVE_HYBRID:
          serviceImpl.retrieveHybrid((com.jervis.contracts.knowledgebase.HybridRetrievalRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.HybridEvidencePack>) responseObserver);
          break;
        case METHODID_ANALYZE_CODE:
          serviceImpl.analyzeCode((com.jervis.contracts.knowledgebase.TraversalRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.JoernAnalyzeResult>) responseObserver);
          break;
        case METHODID_JOERN_SCAN:
          serviceImpl.joernScan((com.jervis.contracts.knowledgebase.JoernScanRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.JoernScanResult>) responseObserver);
          break;
        case METHODID_LIST_CHUNKS_BY_KIND:
          serviceImpl.listChunksByKind((com.jervis.contracts.knowledgebase.ListByKindRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ChunkList>) responseObserver);
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
          getRetrieveMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.RetrievalRequest,
              com.jervis.contracts.knowledgebase.EvidencePack>(
                service, METHODID_RETRIEVE)))
        .addMethod(
          getRetrieveSimpleMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.RetrievalRequest,
              com.jervis.contracts.knowledgebase.EvidencePack>(
                service, METHODID_RETRIEVE_SIMPLE)))
        .addMethod(
          getRetrieveHybridMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.HybridRetrievalRequest,
              com.jervis.contracts.knowledgebase.HybridEvidencePack>(
                service, METHODID_RETRIEVE_HYBRID)))
        .addMethod(
          getAnalyzeCodeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.TraversalRequest,
              com.jervis.contracts.knowledgebase.JoernAnalyzeResult>(
                service, METHODID_ANALYZE_CODE)))
        .addMethod(
          getJoernScanMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.JoernScanRequest,
              com.jervis.contracts.knowledgebase.JoernScanResult>(
                service, METHODID_JOERN_SCAN)))
        .addMethod(
          getListChunksByKindMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ListByKindRequest,
              com.jervis.contracts.knowledgebase.ChunkList>(
                service, METHODID_LIST_CHUNKS_BY_KIND)))
        .build();
  }

  private static abstract class KnowledgeRetrieveServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    KnowledgeRetrieveServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.knowledgebase.KnowledgeRetrieveProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("KnowledgeRetrieveService");
    }
  }

  private static final class KnowledgeRetrieveServiceFileDescriptorSupplier
      extends KnowledgeRetrieveServiceBaseDescriptorSupplier {
    KnowledgeRetrieveServiceFileDescriptorSupplier() {}
  }

  private static final class KnowledgeRetrieveServiceMethodDescriptorSupplier
      extends KnowledgeRetrieveServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    KnowledgeRetrieveServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (KnowledgeRetrieveServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new KnowledgeRetrieveServiceFileDescriptorSupplier())
              .addMethod(getRetrieveMethod())
              .addMethod(getRetrieveSimpleMethod())
              .addMethod(getRetrieveHybridMethod())
              .addMethod(getAnalyzeCodeMethod())
              .addMethod(getJoernScanMethod())
              .addMethod(getListChunksByKindMethod())
              .build();
        }
      }
    }
    return result;
  }
}
