package com.jervis.contracts.document_extraction;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * DocumentExtractionService — takes any binary content (PDF, DOCX, XLSX,
 * HTML, images) and returns clean plain text. Replaces the former
 * `POST /extract` and `POST /extract-base64` FastAPI routes; the callers
 * now ride a single unary RPC with the bytes inline (64 MiB cap — for
 * anything larger the blob side channel is the answer).
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class DocumentExtractionServiceGrpc {

  private DocumentExtractionServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.document_extraction.DocumentExtractionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.document_extraction.ExtractRequest,
      com.jervis.contracts.document_extraction.ExtractResponse> getExtractMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Extract",
      requestType = com.jervis.contracts.document_extraction.ExtractRequest.class,
      responseType = com.jervis.contracts.document_extraction.ExtractResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.document_extraction.ExtractRequest,
      com.jervis.contracts.document_extraction.ExtractResponse> getExtractMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.document_extraction.ExtractRequest, com.jervis.contracts.document_extraction.ExtractResponse> getExtractMethod;
    if ((getExtractMethod = DocumentExtractionServiceGrpc.getExtractMethod) == null) {
      synchronized (DocumentExtractionServiceGrpc.class) {
        if ((getExtractMethod = DocumentExtractionServiceGrpc.getExtractMethod) == null) {
          DocumentExtractionServiceGrpc.getExtractMethod = getExtractMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.document_extraction.ExtractRequest, com.jervis.contracts.document_extraction.ExtractResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Extract"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.document_extraction.ExtractRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.document_extraction.ExtractResponse.getDefaultInstance()))
              .setSchemaDescriptor(new DocumentExtractionServiceMethodDescriptorSupplier("Extract"))
              .build();
        }
      }
    }
    return getExtractMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.document_extraction.HealthRequest,
      com.jervis.contracts.document_extraction.HealthResponse> getHealthMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Health",
      requestType = com.jervis.contracts.document_extraction.HealthRequest.class,
      responseType = com.jervis.contracts.document_extraction.HealthResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.document_extraction.HealthRequest,
      com.jervis.contracts.document_extraction.HealthResponse> getHealthMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.document_extraction.HealthRequest, com.jervis.contracts.document_extraction.HealthResponse> getHealthMethod;
    if ((getHealthMethod = DocumentExtractionServiceGrpc.getHealthMethod) == null) {
      synchronized (DocumentExtractionServiceGrpc.class) {
        if ((getHealthMethod = DocumentExtractionServiceGrpc.getHealthMethod) == null) {
          DocumentExtractionServiceGrpc.getHealthMethod = getHealthMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.document_extraction.HealthRequest, com.jervis.contracts.document_extraction.HealthResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Health"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.document_extraction.HealthRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.document_extraction.HealthResponse.getDefaultInstance()))
              .setSchemaDescriptor(new DocumentExtractionServiceMethodDescriptorSupplier("Health"))
              .build();
        }
      }
    }
    return getHealthMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static DocumentExtractionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DocumentExtractionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DocumentExtractionServiceStub>() {
        @java.lang.Override
        public DocumentExtractionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DocumentExtractionServiceStub(channel, callOptions);
        }
      };
    return DocumentExtractionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static DocumentExtractionServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DocumentExtractionServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DocumentExtractionServiceBlockingV2Stub>() {
        @java.lang.Override
        public DocumentExtractionServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DocumentExtractionServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return DocumentExtractionServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static DocumentExtractionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DocumentExtractionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DocumentExtractionServiceBlockingStub>() {
        @java.lang.Override
        public DocumentExtractionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DocumentExtractionServiceBlockingStub(channel, callOptions);
        }
      };
    return DocumentExtractionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static DocumentExtractionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DocumentExtractionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DocumentExtractionServiceFutureStub>() {
        @java.lang.Override
        public DocumentExtractionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DocumentExtractionServiceFutureStub(channel, callOptions);
        }
      };
    return DocumentExtractionServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * DocumentExtractionService — takes any binary content (PDF, DOCX, XLSX,
   * HTML, images) and returns clean plain text. Replaces the former
   * `POST /extract` and `POST /extract-base64` FastAPI routes; the callers
   * now ride a single unary RPC with the bytes inline (64 MiB cap — for
   * anything larger the blob side channel is the answer).
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void extract(com.jervis.contracts.document_extraction.ExtractRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.document_extraction.ExtractResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getExtractMethod(), responseObserver);
    }

    /**
     */
    default void health(com.jervis.contracts.document_extraction.HealthRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.document_extraction.HealthResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHealthMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service DocumentExtractionService.
   * <pre>
   * DocumentExtractionService — takes any binary content (PDF, DOCX, XLSX,
   * HTML, images) and returns clean plain text. Replaces the former
   * `POST /extract` and `POST /extract-base64` FastAPI routes; the callers
   * now ride a single unary RPC with the bytes inline (64 MiB cap — for
   * anything larger the blob side channel is the answer).
   * </pre>
   */
  public static abstract class DocumentExtractionServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return DocumentExtractionServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service DocumentExtractionService.
   * <pre>
   * DocumentExtractionService — takes any binary content (PDF, DOCX, XLSX,
   * HTML, images) and returns clean plain text. Replaces the former
   * `POST /extract` and `POST /extract-base64` FastAPI routes; the callers
   * now ride a single unary RPC with the bytes inline (64 MiB cap — for
   * anything larger the blob side channel is the answer).
   * </pre>
   */
  public static final class DocumentExtractionServiceStub
      extends io.grpc.stub.AbstractAsyncStub<DocumentExtractionServiceStub> {
    private DocumentExtractionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DocumentExtractionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DocumentExtractionServiceStub(channel, callOptions);
    }

    /**
     */
    public void extract(com.jervis.contracts.document_extraction.ExtractRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.document_extraction.ExtractResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getExtractMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void health(com.jervis.contracts.document_extraction.HealthRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.document_extraction.HealthResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service DocumentExtractionService.
   * <pre>
   * DocumentExtractionService — takes any binary content (PDF, DOCX, XLSX,
   * HTML, images) and returns clean plain text. Replaces the former
   * `POST /extract` and `POST /extract-base64` FastAPI routes; the callers
   * now ride a single unary RPC with the bytes inline (64 MiB cap — for
   * anything larger the blob side channel is the answer).
   * </pre>
   */
  public static final class DocumentExtractionServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<DocumentExtractionServiceBlockingV2Stub> {
    private DocumentExtractionServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DocumentExtractionServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DocumentExtractionServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.document_extraction.ExtractResponse extract(com.jervis.contracts.document_extraction.ExtractRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getExtractMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.document_extraction.HealthResponse health(com.jervis.contracts.document_extraction.HealthRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service DocumentExtractionService.
   * <pre>
   * DocumentExtractionService — takes any binary content (PDF, DOCX, XLSX,
   * HTML, images) and returns clean plain text. Replaces the former
   * `POST /extract` and `POST /extract-base64` FastAPI routes; the callers
   * now ride a single unary RPC with the bytes inline (64 MiB cap — for
   * anything larger the blob side channel is the answer).
   * </pre>
   */
  public static final class DocumentExtractionServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<DocumentExtractionServiceBlockingStub> {
    private DocumentExtractionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DocumentExtractionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DocumentExtractionServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.document_extraction.ExtractResponse extract(com.jervis.contracts.document_extraction.ExtractRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getExtractMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.document_extraction.HealthResponse health(com.jervis.contracts.document_extraction.HealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service DocumentExtractionService.
   * <pre>
   * DocumentExtractionService — takes any binary content (PDF, DOCX, XLSX,
   * HTML, images) and returns clean plain text. Replaces the former
   * `POST /extract` and `POST /extract-base64` FastAPI routes; the callers
   * now ride a single unary RPC with the bytes inline (64 MiB cap — for
   * anything larger the blob side channel is the answer).
   * </pre>
   */
  public static final class DocumentExtractionServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<DocumentExtractionServiceFutureStub> {
    private DocumentExtractionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DocumentExtractionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DocumentExtractionServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.document_extraction.ExtractResponse> extract(
        com.jervis.contracts.document_extraction.ExtractRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getExtractMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.document_extraction.HealthResponse> health(
        com.jervis.contracts.document_extraction.HealthRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_EXTRACT = 0;
  private static final int METHODID_HEALTH = 1;

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
        case METHODID_EXTRACT:
          serviceImpl.extract((com.jervis.contracts.document_extraction.ExtractRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.document_extraction.ExtractResponse>) responseObserver);
          break;
        case METHODID_HEALTH:
          serviceImpl.health((com.jervis.contracts.document_extraction.HealthRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.document_extraction.HealthResponse>) responseObserver);
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
          getExtractMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.document_extraction.ExtractRequest,
              com.jervis.contracts.document_extraction.ExtractResponse>(
                service, METHODID_EXTRACT)))
        .addMethod(
          getHealthMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.document_extraction.HealthRequest,
              com.jervis.contracts.document_extraction.HealthResponse>(
                service, METHODID_HEALTH)))
        .build();
  }

  private static abstract class DocumentExtractionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    DocumentExtractionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.document_extraction.DocumentExtractionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("DocumentExtractionService");
    }
  }

  private static final class DocumentExtractionServiceFileDescriptorSupplier
      extends DocumentExtractionServiceBaseDescriptorSupplier {
    DocumentExtractionServiceFileDescriptorSupplier() {}
  }

  private static final class DocumentExtractionServiceMethodDescriptorSupplier
      extends DocumentExtractionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    DocumentExtractionServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (DocumentExtractionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new DocumentExtractionServiceFileDescriptorSupplier())
              .addMethod(getExtractMethod())
              .addMethod(getHealthMethod())
              .build();
        }
      }
    }
    return result;
  }
}
