package com.jervis.contracts.knowledgebase;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * KnowledgeDocumentService — KB document upload / register / list / reindex.
 * Raw bytes travel over the blob side channel (PUT /blob/kb-doc/{hash});
 * the proto only carries the metadata + blob reference.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class KnowledgeDocumentServiceGrpc {

  private KnowledgeDocumentServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.knowledgebase.KnowledgeDocumentService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentUploadRequest,
      com.jervis.contracts.knowledgebase.Document> getUploadMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Upload",
      requestType = com.jervis.contracts.knowledgebase.DocumentUploadRequest.class,
      responseType = com.jervis.contracts.knowledgebase.Document.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentUploadRequest,
      com.jervis.contracts.knowledgebase.Document> getUploadMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentUploadRequest, com.jervis.contracts.knowledgebase.Document> getUploadMethod;
    if ((getUploadMethod = KnowledgeDocumentServiceGrpc.getUploadMethod) == null) {
      synchronized (KnowledgeDocumentServiceGrpc.class) {
        if ((getUploadMethod = KnowledgeDocumentServiceGrpc.getUploadMethod) == null) {
          KnowledgeDocumentServiceGrpc.getUploadMethod = getUploadMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.DocumentUploadRequest, com.jervis.contracts.knowledgebase.Document>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Upload"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentUploadRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.Document.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeDocumentServiceMethodDescriptorSupplier("Upload"))
              .build();
        }
      }
    }
    return getUploadMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentRegisterRequest,
      com.jervis.contracts.knowledgebase.Document> getRegisterMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Register",
      requestType = com.jervis.contracts.knowledgebase.DocumentRegisterRequest.class,
      responseType = com.jervis.contracts.knowledgebase.Document.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentRegisterRequest,
      com.jervis.contracts.knowledgebase.Document> getRegisterMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentRegisterRequest, com.jervis.contracts.knowledgebase.Document> getRegisterMethod;
    if ((getRegisterMethod = KnowledgeDocumentServiceGrpc.getRegisterMethod) == null) {
      synchronized (KnowledgeDocumentServiceGrpc.class) {
        if ((getRegisterMethod = KnowledgeDocumentServiceGrpc.getRegisterMethod) == null) {
          KnowledgeDocumentServiceGrpc.getRegisterMethod = getRegisterMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.DocumentRegisterRequest, com.jervis.contracts.knowledgebase.Document>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Register"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentRegisterRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.Document.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeDocumentServiceMethodDescriptorSupplier("Register"))
              .build();
        }
      }
    }
    return getRegisterMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentListRequest,
      com.jervis.contracts.knowledgebase.DocumentList> getListMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "List",
      requestType = com.jervis.contracts.knowledgebase.DocumentListRequest.class,
      responseType = com.jervis.contracts.knowledgebase.DocumentList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentListRequest,
      com.jervis.contracts.knowledgebase.DocumentList> getListMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentListRequest, com.jervis.contracts.knowledgebase.DocumentList> getListMethod;
    if ((getListMethod = KnowledgeDocumentServiceGrpc.getListMethod) == null) {
      synchronized (KnowledgeDocumentServiceGrpc.class) {
        if ((getListMethod = KnowledgeDocumentServiceGrpc.getListMethod) == null) {
          KnowledgeDocumentServiceGrpc.getListMethod = getListMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.DocumentListRequest, com.jervis.contracts.knowledgebase.DocumentList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "List"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentListRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentList.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeDocumentServiceMethodDescriptorSupplier("List"))
              .build();
        }
      }
    }
    return getListMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentId,
      com.jervis.contracts.knowledgebase.Document> getGetMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Get",
      requestType = com.jervis.contracts.knowledgebase.DocumentId.class,
      responseType = com.jervis.contracts.knowledgebase.Document.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentId,
      com.jervis.contracts.knowledgebase.Document> getGetMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentId, com.jervis.contracts.knowledgebase.Document> getGetMethod;
    if ((getGetMethod = KnowledgeDocumentServiceGrpc.getGetMethod) == null) {
      synchronized (KnowledgeDocumentServiceGrpc.class) {
        if ((getGetMethod = KnowledgeDocumentServiceGrpc.getGetMethod) == null) {
          KnowledgeDocumentServiceGrpc.getGetMethod = getGetMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.DocumentId, com.jervis.contracts.knowledgebase.Document>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Get"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentId.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.Document.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeDocumentServiceMethodDescriptorSupplier("Get"))
              .build();
        }
      }
    }
    return getGetMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentUpdateRequest,
      com.jervis.contracts.knowledgebase.Document> getUpdateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Update",
      requestType = com.jervis.contracts.knowledgebase.DocumentUpdateRequest.class,
      responseType = com.jervis.contracts.knowledgebase.Document.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentUpdateRequest,
      com.jervis.contracts.knowledgebase.Document> getUpdateMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentUpdateRequest, com.jervis.contracts.knowledgebase.Document> getUpdateMethod;
    if ((getUpdateMethod = KnowledgeDocumentServiceGrpc.getUpdateMethod) == null) {
      synchronized (KnowledgeDocumentServiceGrpc.class) {
        if ((getUpdateMethod = KnowledgeDocumentServiceGrpc.getUpdateMethod) == null) {
          KnowledgeDocumentServiceGrpc.getUpdateMethod = getUpdateMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.DocumentUpdateRequest, com.jervis.contracts.knowledgebase.Document>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Update"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentUpdateRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.Document.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeDocumentServiceMethodDescriptorSupplier("Update"))
              .build();
        }
      }
    }
    return getUpdateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentId,
      com.jervis.contracts.knowledgebase.DocumentAck> getDeleteMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Delete",
      requestType = com.jervis.contracts.knowledgebase.DocumentId.class,
      responseType = com.jervis.contracts.knowledgebase.DocumentAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentId,
      com.jervis.contracts.knowledgebase.DocumentAck> getDeleteMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentId, com.jervis.contracts.knowledgebase.DocumentAck> getDeleteMethod;
    if ((getDeleteMethod = KnowledgeDocumentServiceGrpc.getDeleteMethod) == null) {
      synchronized (KnowledgeDocumentServiceGrpc.class) {
        if ((getDeleteMethod = KnowledgeDocumentServiceGrpc.getDeleteMethod) == null) {
          KnowledgeDocumentServiceGrpc.getDeleteMethod = getDeleteMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.DocumentId, com.jervis.contracts.knowledgebase.DocumentAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Delete"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentId.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeDocumentServiceMethodDescriptorSupplier("Delete"))
              .build();
        }
      }
    }
    return getDeleteMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentId,
      com.jervis.contracts.knowledgebase.DocumentAck> getReindexMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Reindex",
      requestType = com.jervis.contracts.knowledgebase.DocumentId.class,
      responseType = com.jervis.contracts.knowledgebase.DocumentAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentId,
      com.jervis.contracts.knowledgebase.DocumentAck> getReindexMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentId, com.jervis.contracts.knowledgebase.DocumentAck> getReindexMethod;
    if ((getReindexMethod = KnowledgeDocumentServiceGrpc.getReindexMethod) == null) {
      synchronized (KnowledgeDocumentServiceGrpc.class) {
        if ((getReindexMethod = KnowledgeDocumentServiceGrpc.getReindexMethod) == null) {
          KnowledgeDocumentServiceGrpc.getReindexMethod = getReindexMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.DocumentId, com.jervis.contracts.knowledgebase.DocumentAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Reindex"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentId.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeDocumentServiceMethodDescriptorSupplier("Reindex"))
              .build();
        }
      }
    }
    return getReindexMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentExtractRequest,
      com.jervis.contracts.knowledgebase.DocumentExtractResult> getExtractTextMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ExtractText",
      requestType = com.jervis.contracts.knowledgebase.DocumentExtractRequest.class,
      responseType = com.jervis.contracts.knowledgebase.DocumentExtractResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentExtractRequest,
      com.jervis.contracts.knowledgebase.DocumentExtractResult> getExtractTextMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.DocumentExtractRequest, com.jervis.contracts.knowledgebase.DocumentExtractResult> getExtractTextMethod;
    if ((getExtractTextMethod = KnowledgeDocumentServiceGrpc.getExtractTextMethod) == null) {
      synchronized (KnowledgeDocumentServiceGrpc.class) {
        if ((getExtractTextMethod = KnowledgeDocumentServiceGrpc.getExtractTextMethod) == null) {
          KnowledgeDocumentServiceGrpc.getExtractTextMethod = getExtractTextMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.DocumentExtractRequest, com.jervis.contracts.knowledgebase.DocumentExtractResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ExtractText"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentExtractRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.DocumentExtractResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeDocumentServiceMethodDescriptorSupplier("ExtractText"))
              .build();
        }
      }
    }
    return getExtractTextMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static KnowledgeDocumentServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeDocumentServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeDocumentServiceStub>() {
        @java.lang.Override
        public KnowledgeDocumentServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeDocumentServiceStub(channel, callOptions);
        }
      };
    return KnowledgeDocumentServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static KnowledgeDocumentServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeDocumentServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeDocumentServiceBlockingV2Stub>() {
        @java.lang.Override
        public KnowledgeDocumentServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeDocumentServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return KnowledgeDocumentServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static KnowledgeDocumentServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeDocumentServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeDocumentServiceBlockingStub>() {
        @java.lang.Override
        public KnowledgeDocumentServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeDocumentServiceBlockingStub(channel, callOptions);
        }
      };
    return KnowledgeDocumentServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static KnowledgeDocumentServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeDocumentServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeDocumentServiceFutureStub>() {
        @java.lang.Override
        public KnowledgeDocumentServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeDocumentServiceFutureStub(channel, callOptions);
        }
      };
    return KnowledgeDocumentServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * KnowledgeDocumentService — KB document upload / register / list / reindex.
   * Raw bytes travel over the blob side channel (PUT /blob/kb-doc/{hash});
   * the proto only carries the metadata + blob reference.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void upload(com.jervis.contracts.knowledgebase.DocumentUploadRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUploadMethod(), responseObserver);
    }

    /**
     */
    default void register(com.jervis.contracts.knowledgebase.DocumentRegisterRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterMethod(), responseObserver);
    }

    /**
     */
    default void list(com.jervis.contracts.knowledgebase.DocumentListRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListMethod(), responseObserver);
    }

    /**
     */
    default void get(com.jervis.contracts.knowledgebase.DocumentId request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMethod(), responseObserver);
    }

    /**
     */
    default void update(com.jervis.contracts.knowledgebase.DocumentUpdateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateMethod(), responseObserver);
    }

    /**
     */
    default void delete(com.jervis.contracts.knowledgebase.DocumentId request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteMethod(), responseObserver);
    }

    /**
     */
    default void reindex(com.jervis.contracts.knowledgebase.DocumentId request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReindexMethod(), responseObserver);
    }

    /**
     */
    default void extractText(com.jervis.contracts.knowledgebase.DocumentExtractRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentExtractResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getExtractTextMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service KnowledgeDocumentService.
   * <pre>
   * KnowledgeDocumentService — KB document upload / register / list / reindex.
   * Raw bytes travel over the blob side channel (PUT /blob/kb-doc/{hash});
   * the proto only carries the metadata + blob reference.
   * </pre>
   */
  public static abstract class KnowledgeDocumentServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return KnowledgeDocumentServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service KnowledgeDocumentService.
   * <pre>
   * KnowledgeDocumentService — KB document upload / register / list / reindex.
   * Raw bytes travel over the blob side channel (PUT /blob/kb-doc/{hash});
   * the proto only carries the metadata + blob reference.
   * </pre>
   */
  public static final class KnowledgeDocumentServiceStub
      extends io.grpc.stub.AbstractAsyncStub<KnowledgeDocumentServiceStub> {
    private KnowledgeDocumentServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeDocumentServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeDocumentServiceStub(channel, callOptions);
    }

    /**
     */
    public void upload(com.jervis.contracts.knowledgebase.DocumentUploadRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUploadMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void register(com.jervis.contracts.knowledgebase.DocumentRegisterRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRegisterMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void list(com.jervis.contracts.knowledgebase.DocumentListRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void get(com.jervis.contracts.knowledgebase.DocumentId request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void update(com.jervis.contracts.knowledgebase.DocumentUpdateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void delete(com.jervis.contracts.knowledgebase.DocumentId request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void reindex(com.jervis.contracts.knowledgebase.DocumentId request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReindexMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void extractText(com.jervis.contracts.knowledgebase.DocumentExtractRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentExtractResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getExtractTextMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service KnowledgeDocumentService.
   * <pre>
   * KnowledgeDocumentService — KB document upload / register / list / reindex.
   * Raw bytes travel over the blob side channel (PUT /blob/kb-doc/{hash});
   * the proto only carries the metadata + blob reference.
   * </pre>
   */
  public static final class KnowledgeDocumentServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeDocumentServiceBlockingV2Stub> {
    private KnowledgeDocumentServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeDocumentServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeDocumentServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.Document upload(com.jervis.contracts.knowledgebase.DocumentUploadRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUploadMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.Document register(com.jervis.contracts.knowledgebase.DocumentRegisterRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRegisterMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.DocumentList list(com.jervis.contracts.knowledgebase.DocumentListRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.Document get(com.jervis.contracts.knowledgebase.DocumentId request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.Document update(com.jervis.contracts.knowledgebase.DocumentUpdateRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.DocumentAck delete(com.jervis.contracts.knowledgebase.DocumentId request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.DocumentAck reindex(com.jervis.contracts.knowledgebase.DocumentId request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReindexMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.DocumentExtractResult extractText(com.jervis.contracts.knowledgebase.DocumentExtractRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getExtractTextMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service KnowledgeDocumentService.
   * <pre>
   * KnowledgeDocumentService — KB document upload / register / list / reindex.
   * Raw bytes travel over the blob side channel (PUT /blob/kb-doc/{hash});
   * the proto only carries the metadata + blob reference.
   * </pre>
   */
  public static final class KnowledgeDocumentServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeDocumentServiceBlockingStub> {
    private KnowledgeDocumentServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeDocumentServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeDocumentServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.Document upload(com.jervis.contracts.knowledgebase.DocumentUploadRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUploadMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.Document register(com.jervis.contracts.knowledgebase.DocumentRegisterRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRegisterMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.DocumentList list(com.jervis.contracts.knowledgebase.DocumentListRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.Document get(com.jervis.contracts.knowledgebase.DocumentId request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.Document update(com.jervis.contracts.knowledgebase.DocumentUpdateRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.DocumentAck delete(com.jervis.contracts.knowledgebase.DocumentId request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.DocumentAck reindex(com.jervis.contracts.knowledgebase.DocumentId request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReindexMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.DocumentExtractResult extractText(com.jervis.contracts.knowledgebase.DocumentExtractRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getExtractTextMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service KnowledgeDocumentService.
   * <pre>
   * KnowledgeDocumentService — KB document upload / register / list / reindex.
   * Raw bytes travel over the blob side channel (PUT /blob/kb-doc/{hash});
   * the proto only carries the metadata + blob reference.
   * </pre>
   */
  public static final class KnowledgeDocumentServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<KnowledgeDocumentServiceFutureStub> {
    private KnowledgeDocumentServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeDocumentServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeDocumentServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.Document> upload(
        com.jervis.contracts.knowledgebase.DocumentUploadRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUploadMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.Document> register(
        com.jervis.contracts.knowledgebase.DocumentRegisterRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRegisterMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.DocumentList> list(
        com.jervis.contracts.knowledgebase.DocumentListRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.Document> get(
        com.jervis.contracts.knowledgebase.DocumentId request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.Document> update(
        com.jervis.contracts.knowledgebase.DocumentUpdateRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.DocumentAck> delete(
        com.jervis.contracts.knowledgebase.DocumentId request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.DocumentAck> reindex(
        com.jervis.contracts.knowledgebase.DocumentId request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReindexMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.DocumentExtractResult> extractText(
        com.jervis.contracts.knowledgebase.DocumentExtractRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getExtractTextMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_UPLOAD = 0;
  private static final int METHODID_REGISTER = 1;
  private static final int METHODID_LIST = 2;
  private static final int METHODID_GET = 3;
  private static final int METHODID_UPDATE = 4;
  private static final int METHODID_DELETE = 5;
  private static final int METHODID_REINDEX = 6;
  private static final int METHODID_EXTRACT_TEXT = 7;

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
        case METHODID_UPLOAD:
          serviceImpl.upload((com.jervis.contracts.knowledgebase.DocumentUploadRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document>) responseObserver);
          break;
        case METHODID_REGISTER:
          serviceImpl.register((com.jervis.contracts.knowledgebase.DocumentRegisterRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document>) responseObserver);
          break;
        case METHODID_LIST:
          serviceImpl.list((com.jervis.contracts.knowledgebase.DocumentListRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentList>) responseObserver);
          break;
        case METHODID_GET:
          serviceImpl.get((com.jervis.contracts.knowledgebase.DocumentId) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document>) responseObserver);
          break;
        case METHODID_UPDATE:
          serviceImpl.update((com.jervis.contracts.knowledgebase.DocumentUpdateRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.Document>) responseObserver);
          break;
        case METHODID_DELETE:
          serviceImpl.delete((com.jervis.contracts.knowledgebase.DocumentId) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentAck>) responseObserver);
          break;
        case METHODID_REINDEX:
          serviceImpl.reindex((com.jervis.contracts.knowledgebase.DocumentId) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentAck>) responseObserver);
          break;
        case METHODID_EXTRACT_TEXT:
          serviceImpl.extractText((com.jervis.contracts.knowledgebase.DocumentExtractRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.DocumentExtractResult>) responseObserver);
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
          getUploadMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.DocumentUploadRequest,
              com.jervis.contracts.knowledgebase.Document>(
                service, METHODID_UPLOAD)))
        .addMethod(
          getRegisterMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.DocumentRegisterRequest,
              com.jervis.contracts.knowledgebase.Document>(
                service, METHODID_REGISTER)))
        .addMethod(
          getListMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.DocumentListRequest,
              com.jervis.contracts.knowledgebase.DocumentList>(
                service, METHODID_LIST)))
        .addMethod(
          getGetMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.DocumentId,
              com.jervis.contracts.knowledgebase.Document>(
                service, METHODID_GET)))
        .addMethod(
          getUpdateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.DocumentUpdateRequest,
              com.jervis.contracts.knowledgebase.Document>(
                service, METHODID_UPDATE)))
        .addMethod(
          getDeleteMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.DocumentId,
              com.jervis.contracts.knowledgebase.DocumentAck>(
                service, METHODID_DELETE)))
        .addMethod(
          getReindexMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.DocumentId,
              com.jervis.contracts.knowledgebase.DocumentAck>(
                service, METHODID_REINDEX)))
        .addMethod(
          getExtractTextMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.DocumentExtractRequest,
              com.jervis.contracts.knowledgebase.DocumentExtractResult>(
                service, METHODID_EXTRACT_TEXT)))
        .build();
  }

  private static abstract class KnowledgeDocumentServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    KnowledgeDocumentServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.knowledgebase.KnowledgeDocumentProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("KnowledgeDocumentService");
    }
  }

  private static final class KnowledgeDocumentServiceFileDescriptorSupplier
      extends KnowledgeDocumentServiceBaseDescriptorSupplier {
    KnowledgeDocumentServiceFileDescriptorSupplier() {}
  }

  private static final class KnowledgeDocumentServiceMethodDescriptorSupplier
      extends KnowledgeDocumentServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    KnowledgeDocumentServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (KnowledgeDocumentServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new KnowledgeDocumentServiceFileDescriptorSupplier())
              .addMethod(getUploadMethod())
              .addMethod(getRegisterMethod())
              .addMethod(getListMethod())
              .addMethod(getGetMethod())
              .addMethod(getUpdateMethod())
              .addMethod(getDeleteMethod())
              .addMethod(getReindexMethod())
              .addMethod(getExtractTextMethod())
              .build();
        }
      }
    }
    return result;
  }
}
