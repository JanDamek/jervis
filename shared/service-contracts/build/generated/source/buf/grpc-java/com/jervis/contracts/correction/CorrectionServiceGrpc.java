package com.jervis.contracts.correction;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * CorrectionService — transcript correction service (KB-stored rules +
 * Ollama GPU). Fully-typed surface — no passthrough JSON. RPC names and
 * field shapes mirror the retired REST routes 1:1 so the Python agent +
 * Kotlin caller can both reach the same semantics without per-message
 * JSON hand-parsing.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class CorrectionServiceGrpc {

  private CorrectionServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.correction.CorrectionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.correction.SubmitCorrectionRequest,
      com.jervis.contracts.correction.SubmitCorrectionResponse> getSubmitCorrectionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SubmitCorrection",
      requestType = com.jervis.contracts.correction.SubmitCorrectionRequest.class,
      responseType = com.jervis.contracts.correction.SubmitCorrectionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.correction.SubmitCorrectionRequest,
      com.jervis.contracts.correction.SubmitCorrectionResponse> getSubmitCorrectionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.correction.SubmitCorrectionRequest, com.jervis.contracts.correction.SubmitCorrectionResponse> getSubmitCorrectionMethod;
    if ((getSubmitCorrectionMethod = CorrectionServiceGrpc.getSubmitCorrectionMethod) == null) {
      synchronized (CorrectionServiceGrpc.class) {
        if ((getSubmitCorrectionMethod = CorrectionServiceGrpc.getSubmitCorrectionMethod) == null) {
          CorrectionServiceGrpc.getSubmitCorrectionMethod = getSubmitCorrectionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.correction.SubmitCorrectionRequest, com.jervis.contracts.correction.SubmitCorrectionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SubmitCorrection"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.SubmitCorrectionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.SubmitCorrectionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CorrectionServiceMethodDescriptorSupplier("SubmitCorrection"))
              .build();
        }
      }
    }
    return getSubmitCorrectionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.correction.CorrectTranscriptRequest,
      com.jervis.contracts.correction.CorrectResult> getCorrectTranscriptMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CorrectTranscript",
      requestType = com.jervis.contracts.correction.CorrectTranscriptRequest.class,
      responseType = com.jervis.contracts.correction.CorrectResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.correction.CorrectTranscriptRequest,
      com.jervis.contracts.correction.CorrectResult> getCorrectTranscriptMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.correction.CorrectTranscriptRequest, com.jervis.contracts.correction.CorrectResult> getCorrectTranscriptMethod;
    if ((getCorrectTranscriptMethod = CorrectionServiceGrpc.getCorrectTranscriptMethod) == null) {
      synchronized (CorrectionServiceGrpc.class) {
        if ((getCorrectTranscriptMethod = CorrectionServiceGrpc.getCorrectTranscriptMethod) == null) {
          CorrectionServiceGrpc.getCorrectTranscriptMethod = getCorrectTranscriptMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.correction.CorrectTranscriptRequest, com.jervis.contracts.correction.CorrectResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CorrectTranscript"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.CorrectTranscriptRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.CorrectResult.getDefaultInstance()))
              .setSchemaDescriptor(new CorrectionServiceMethodDescriptorSupplier("CorrectTranscript"))
              .build();
        }
      }
    }
    return getCorrectTranscriptMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.correction.ListCorrectionsRequest,
      com.jervis.contracts.correction.ListCorrectionsResponse> getListCorrectionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListCorrections",
      requestType = com.jervis.contracts.correction.ListCorrectionsRequest.class,
      responseType = com.jervis.contracts.correction.ListCorrectionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.correction.ListCorrectionsRequest,
      com.jervis.contracts.correction.ListCorrectionsResponse> getListCorrectionsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.correction.ListCorrectionsRequest, com.jervis.contracts.correction.ListCorrectionsResponse> getListCorrectionsMethod;
    if ((getListCorrectionsMethod = CorrectionServiceGrpc.getListCorrectionsMethod) == null) {
      synchronized (CorrectionServiceGrpc.class) {
        if ((getListCorrectionsMethod = CorrectionServiceGrpc.getListCorrectionsMethod) == null) {
          CorrectionServiceGrpc.getListCorrectionsMethod = getListCorrectionsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.correction.ListCorrectionsRequest, com.jervis.contracts.correction.ListCorrectionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListCorrections"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.ListCorrectionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.ListCorrectionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CorrectionServiceMethodDescriptorSupplier("ListCorrections"))
              .build();
        }
      }
    }
    return getListCorrectionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.correction.AnswerCorrectionsRequest,
      com.jervis.contracts.correction.AnswerCorrectionsResponse> getAnswerCorrectionQuestionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AnswerCorrectionQuestions",
      requestType = com.jervis.contracts.correction.AnswerCorrectionsRequest.class,
      responseType = com.jervis.contracts.correction.AnswerCorrectionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.correction.AnswerCorrectionsRequest,
      com.jervis.contracts.correction.AnswerCorrectionsResponse> getAnswerCorrectionQuestionsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.correction.AnswerCorrectionsRequest, com.jervis.contracts.correction.AnswerCorrectionsResponse> getAnswerCorrectionQuestionsMethod;
    if ((getAnswerCorrectionQuestionsMethod = CorrectionServiceGrpc.getAnswerCorrectionQuestionsMethod) == null) {
      synchronized (CorrectionServiceGrpc.class) {
        if ((getAnswerCorrectionQuestionsMethod = CorrectionServiceGrpc.getAnswerCorrectionQuestionsMethod) == null) {
          CorrectionServiceGrpc.getAnswerCorrectionQuestionsMethod = getAnswerCorrectionQuestionsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.correction.AnswerCorrectionsRequest, com.jervis.contracts.correction.AnswerCorrectionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AnswerCorrectionQuestions"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.AnswerCorrectionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.AnswerCorrectionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CorrectionServiceMethodDescriptorSupplier("AnswerCorrectionQuestions"))
              .build();
        }
      }
    }
    return getAnswerCorrectionQuestionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.correction.CorrectWithInstructionRequest,
      com.jervis.contracts.correction.CorrectWithInstructionResponse> getCorrectWithInstructionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CorrectWithInstruction",
      requestType = com.jervis.contracts.correction.CorrectWithInstructionRequest.class,
      responseType = com.jervis.contracts.correction.CorrectWithInstructionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.correction.CorrectWithInstructionRequest,
      com.jervis.contracts.correction.CorrectWithInstructionResponse> getCorrectWithInstructionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.correction.CorrectWithInstructionRequest, com.jervis.contracts.correction.CorrectWithInstructionResponse> getCorrectWithInstructionMethod;
    if ((getCorrectWithInstructionMethod = CorrectionServiceGrpc.getCorrectWithInstructionMethod) == null) {
      synchronized (CorrectionServiceGrpc.class) {
        if ((getCorrectWithInstructionMethod = CorrectionServiceGrpc.getCorrectWithInstructionMethod) == null) {
          CorrectionServiceGrpc.getCorrectWithInstructionMethod = getCorrectWithInstructionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.correction.CorrectWithInstructionRequest, com.jervis.contracts.correction.CorrectWithInstructionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CorrectWithInstruction"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.CorrectWithInstructionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.CorrectWithInstructionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CorrectionServiceMethodDescriptorSupplier("CorrectWithInstruction"))
              .build();
        }
      }
    }
    return getCorrectWithInstructionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.correction.CorrectTargetedRequest,
      com.jervis.contracts.correction.CorrectResult> getCorrectTargetedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CorrectTargeted",
      requestType = com.jervis.contracts.correction.CorrectTargetedRequest.class,
      responseType = com.jervis.contracts.correction.CorrectResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.correction.CorrectTargetedRequest,
      com.jervis.contracts.correction.CorrectResult> getCorrectTargetedMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.correction.CorrectTargetedRequest, com.jervis.contracts.correction.CorrectResult> getCorrectTargetedMethod;
    if ((getCorrectTargetedMethod = CorrectionServiceGrpc.getCorrectTargetedMethod) == null) {
      synchronized (CorrectionServiceGrpc.class) {
        if ((getCorrectTargetedMethod = CorrectionServiceGrpc.getCorrectTargetedMethod) == null) {
          CorrectionServiceGrpc.getCorrectTargetedMethod = getCorrectTargetedMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.correction.CorrectTargetedRequest, com.jervis.contracts.correction.CorrectResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CorrectTargeted"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.CorrectTargetedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.CorrectResult.getDefaultInstance()))
              .setSchemaDescriptor(new CorrectionServiceMethodDescriptorSupplier("CorrectTargeted"))
              .build();
        }
      }
    }
    return getCorrectTargetedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.correction.DeleteCorrectionRequest,
      com.jervis.contracts.correction.DeleteCorrectionResponse> getDeleteCorrectionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteCorrection",
      requestType = com.jervis.contracts.correction.DeleteCorrectionRequest.class,
      responseType = com.jervis.contracts.correction.DeleteCorrectionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.correction.DeleteCorrectionRequest,
      com.jervis.contracts.correction.DeleteCorrectionResponse> getDeleteCorrectionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.correction.DeleteCorrectionRequest, com.jervis.contracts.correction.DeleteCorrectionResponse> getDeleteCorrectionMethod;
    if ((getDeleteCorrectionMethod = CorrectionServiceGrpc.getDeleteCorrectionMethod) == null) {
      synchronized (CorrectionServiceGrpc.class) {
        if ((getDeleteCorrectionMethod = CorrectionServiceGrpc.getDeleteCorrectionMethod) == null) {
          CorrectionServiceGrpc.getDeleteCorrectionMethod = getDeleteCorrectionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.correction.DeleteCorrectionRequest, com.jervis.contracts.correction.DeleteCorrectionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteCorrection"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.DeleteCorrectionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.correction.DeleteCorrectionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CorrectionServiceMethodDescriptorSupplier("DeleteCorrection"))
              .build();
        }
      }
    }
    return getDeleteCorrectionMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static CorrectionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CorrectionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CorrectionServiceStub>() {
        @java.lang.Override
        public CorrectionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CorrectionServiceStub(channel, callOptions);
        }
      };
    return CorrectionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static CorrectionServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CorrectionServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CorrectionServiceBlockingV2Stub>() {
        @java.lang.Override
        public CorrectionServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CorrectionServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return CorrectionServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static CorrectionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CorrectionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CorrectionServiceBlockingStub>() {
        @java.lang.Override
        public CorrectionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CorrectionServiceBlockingStub(channel, callOptions);
        }
      };
    return CorrectionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static CorrectionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CorrectionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CorrectionServiceFutureStub>() {
        @java.lang.Override
        public CorrectionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CorrectionServiceFutureStub(channel, callOptions);
        }
      };
    return CorrectionServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * CorrectionService — transcript correction service (KB-stored rules +
   * Ollama GPU). Fully-typed surface — no passthrough JSON. RPC names and
   * field shapes mirror the retired REST routes 1:1 so the Python agent +
   * Kotlin caller can both reach the same semantics without per-message
   * JSON hand-parsing.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void submitCorrection(com.jervis.contracts.correction.SubmitCorrectionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.SubmitCorrectionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSubmitCorrectionMethod(), responseObserver);
    }

    /**
     */
    default void correctTranscript(com.jervis.contracts.correction.CorrectTranscriptRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.CorrectResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCorrectTranscriptMethod(), responseObserver);
    }

    /**
     */
    default void listCorrections(com.jervis.contracts.correction.ListCorrectionsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.ListCorrectionsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListCorrectionsMethod(), responseObserver);
    }

    /**
     */
    default void answerCorrectionQuestions(com.jervis.contracts.correction.AnswerCorrectionsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.AnswerCorrectionsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAnswerCorrectionQuestionsMethod(), responseObserver);
    }

    /**
     */
    default void correctWithInstruction(com.jervis.contracts.correction.CorrectWithInstructionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.CorrectWithInstructionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCorrectWithInstructionMethod(), responseObserver);
    }

    /**
     */
    default void correctTargeted(com.jervis.contracts.correction.CorrectTargetedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.CorrectResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCorrectTargetedMethod(), responseObserver);
    }

    /**
     */
    default void deleteCorrection(com.jervis.contracts.correction.DeleteCorrectionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.DeleteCorrectionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteCorrectionMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service CorrectionService.
   * <pre>
   * CorrectionService — transcript correction service (KB-stored rules +
   * Ollama GPU). Fully-typed surface — no passthrough JSON. RPC names and
   * field shapes mirror the retired REST routes 1:1 so the Python agent +
   * Kotlin caller can both reach the same semantics without per-message
   * JSON hand-parsing.
   * </pre>
   */
  public static abstract class CorrectionServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return CorrectionServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service CorrectionService.
   * <pre>
   * CorrectionService — transcript correction service (KB-stored rules +
   * Ollama GPU). Fully-typed surface — no passthrough JSON. RPC names and
   * field shapes mirror the retired REST routes 1:1 so the Python agent +
   * Kotlin caller can both reach the same semantics without per-message
   * JSON hand-parsing.
   * </pre>
   */
  public static final class CorrectionServiceStub
      extends io.grpc.stub.AbstractAsyncStub<CorrectionServiceStub> {
    private CorrectionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CorrectionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CorrectionServiceStub(channel, callOptions);
    }

    /**
     */
    public void submitCorrection(com.jervis.contracts.correction.SubmitCorrectionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.SubmitCorrectionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSubmitCorrectionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void correctTranscript(com.jervis.contracts.correction.CorrectTranscriptRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.CorrectResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCorrectTranscriptMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listCorrections(com.jervis.contracts.correction.ListCorrectionsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.ListCorrectionsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListCorrectionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void answerCorrectionQuestions(com.jervis.contracts.correction.AnswerCorrectionsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.AnswerCorrectionsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAnswerCorrectionQuestionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void correctWithInstruction(com.jervis.contracts.correction.CorrectWithInstructionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.CorrectWithInstructionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCorrectWithInstructionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void correctTargeted(com.jervis.contracts.correction.CorrectTargetedRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.CorrectResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCorrectTargetedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void deleteCorrection(com.jervis.contracts.correction.DeleteCorrectionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.correction.DeleteCorrectionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteCorrectionMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service CorrectionService.
   * <pre>
   * CorrectionService — transcript correction service (KB-stored rules +
   * Ollama GPU). Fully-typed surface — no passthrough JSON. RPC names and
   * field shapes mirror the retired REST routes 1:1 so the Python agent +
   * Kotlin caller can both reach the same semantics without per-message
   * JSON hand-parsing.
   * </pre>
   */
  public static final class CorrectionServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<CorrectionServiceBlockingV2Stub> {
    private CorrectionServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CorrectionServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CorrectionServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.correction.SubmitCorrectionResponse submitCorrection(com.jervis.contracts.correction.SubmitCorrectionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSubmitCorrectionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.CorrectResult correctTranscript(com.jervis.contracts.correction.CorrectTranscriptRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCorrectTranscriptMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.ListCorrectionsResponse listCorrections(com.jervis.contracts.correction.ListCorrectionsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListCorrectionsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.AnswerCorrectionsResponse answerCorrectionQuestions(com.jervis.contracts.correction.AnswerCorrectionsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAnswerCorrectionQuestionsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.CorrectWithInstructionResponse correctWithInstruction(com.jervis.contracts.correction.CorrectWithInstructionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCorrectWithInstructionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.CorrectResult correctTargeted(com.jervis.contracts.correction.CorrectTargetedRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCorrectTargetedMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.DeleteCorrectionResponse deleteCorrection(com.jervis.contracts.correction.DeleteCorrectionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteCorrectionMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service CorrectionService.
   * <pre>
   * CorrectionService — transcript correction service (KB-stored rules +
   * Ollama GPU). Fully-typed surface — no passthrough JSON. RPC names and
   * field shapes mirror the retired REST routes 1:1 so the Python agent +
   * Kotlin caller can both reach the same semantics without per-message
   * JSON hand-parsing.
   * </pre>
   */
  public static final class CorrectionServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<CorrectionServiceBlockingStub> {
    private CorrectionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CorrectionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CorrectionServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.correction.SubmitCorrectionResponse submitCorrection(com.jervis.contracts.correction.SubmitCorrectionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSubmitCorrectionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.CorrectResult correctTranscript(com.jervis.contracts.correction.CorrectTranscriptRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCorrectTranscriptMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.ListCorrectionsResponse listCorrections(com.jervis.contracts.correction.ListCorrectionsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListCorrectionsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.AnswerCorrectionsResponse answerCorrectionQuestions(com.jervis.contracts.correction.AnswerCorrectionsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAnswerCorrectionQuestionsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.CorrectWithInstructionResponse correctWithInstruction(com.jervis.contracts.correction.CorrectWithInstructionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCorrectWithInstructionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.CorrectResult correctTargeted(com.jervis.contracts.correction.CorrectTargetedRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCorrectTargetedMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.correction.DeleteCorrectionResponse deleteCorrection(com.jervis.contracts.correction.DeleteCorrectionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteCorrectionMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service CorrectionService.
   * <pre>
   * CorrectionService — transcript correction service (KB-stored rules +
   * Ollama GPU). Fully-typed surface — no passthrough JSON. RPC names and
   * field shapes mirror the retired REST routes 1:1 so the Python agent +
   * Kotlin caller can both reach the same semantics without per-message
   * JSON hand-parsing.
   * </pre>
   */
  public static final class CorrectionServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<CorrectionServiceFutureStub> {
    private CorrectionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CorrectionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CorrectionServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.correction.SubmitCorrectionResponse> submitCorrection(
        com.jervis.contracts.correction.SubmitCorrectionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSubmitCorrectionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.correction.CorrectResult> correctTranscript(
        com.jervis.contracts.correction.CorrectTranscriptRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCorrectTranscriptMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.correction.ListCorrectionsResponse> listCorrections(
        com.jervis.contracts.correction.ListCorrectionsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListCorrectionsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.correction.AnswerCorrectionsResponse> answerCorrectionQuestions(
        com.jervis.contracts.correction.AnswerCorrectionsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAnswerCorrectionQuestionsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.correction.CorrectWithInstructionResponse> correctWithInstruction(
        com.jervis.contracts.correction.CorrectWithInstructionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCorrectWithInstructionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.correction.CorrectResult> correctTargeted(
        com.jervis.contracts.correction.CorrectTargetedRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCorrectTargetedMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.correction.DeleteCorrectionResponse> deleteCorrection(
        com.jervis.contracts.correction.DeleteCorrectionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteCorrectionMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SUBMIT_CORRECTION = 0;
  private static final int METHODID_CORRECT_TRANSCRIPT = 1;
  private static final int METHODID_LIST_CORRECTIONS = 2;
  private static final int METHODID_ANSWER_CORRECTION_QUESTIONS = 3;
  private static final int METHODID_CORRECT_WITH_INSTRUCTION = 4;
  private static final int METHODID_CORRECT_TARGETED = 5;
  private static final int METHODID_DELETE_CORRECTION = 6;

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
        case METHODID_SUBMIT_CORRECTION:
          serviceImpl.submitCorrection((com.jervis.contracts.correction.SubmitCorrectionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.correction.SubmitCorrectionResponse>) responseObserver);
          break;
        case METHODID_CORRECT_TRANSCRIPT:
          serviceImpl.correctTranscript((com.jervis.contracts.correction.CorrectTranscriptRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.correction.CorrectResult>) responseObserver);
          break;
        case METHODID_LIST_CORRECTIONS:
          serviceImpl.listCorrections((com.jervis.contracts.correction.ListCorrectionsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.correction.ListCorrectionsResponse>) responseObserver);
          break;
        case METHODID_ANSWER_CORRECTION_QUESTIONS:
          serviceImpl.answerCorrectionQuestions((com.jervis.contracts.correction.AnswerCorrectionsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.correction.AnswerCorrectionsResponse>) responseObserver);
          break;
        case METHODID_CORRECT_WITH_INSTRUCTION:
          serviceImpl.correctWithInstruction((com.jervis.contracts.correction.CorrectWithInstructionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.correction.CorrectWithInstructionResponse>) responseObserver);
          break;
        case METHODID_CORRECT_TARGETED:
          serviceImpl.correctTargeted((com.jervis.contracts.correction.CorrectTargetedRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.correction.CorrectResult>) responseObserver);
          break;
        case METHODID_DELETE_CORRECTION:
          serviceImpl.deleteCorrection((com.jervis.contracts.correction.DeleteCorrectionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.correction.DeleteCorrectionResponse>) responseObserver);
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
          getSubmitCorrectionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.correction.SubmitCorrectionRequest,
              com.jervis.contracts.correction.SubmitCorrectionResponse>(
                service, METHODID_SUBMIT_CORRECTION)))
        .addMethod(
          getCorrectTranscriptMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.correction.CorrectTranscriptRequest,
              com.jervis.contracts.correction.CorrectResult>(
                service, METHODID_CORRECT_TRANSCRIPT)))
        .addMethod(
          getListCorrectionsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.correction.ListCorrectionsRequest,
              com.jervis.contracts.correction.ListCorrectionsResponse>(
                service, METHODID_LIST_CORRECTIONS)))
        .addMethod(
          getAnswerCorrectionQuestionsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.correction.AnswerCorrectionsRequest,
              com.jervis.contracts.correction.AnswerCorrectionsResponse>(
                service, METHODID_ANSWER_CORRECTION_QUESTIONS)))
        .addMethod(
          getCorrectWithInstructionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.correction.CorrectWithInstructionRequest,
              com.jervis.contracts.correction.CorrectWithInstructionResponse>(
                service, METHODID_CORRECT_WITH_INSTRUCTION)))
        .addMethod(
          getCorrectTargetedMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.correction.CorrectTargetedRequest,
              com.jervis.contracts.correction.CorrectResult>(
                service, METHODID_CORRECT_TARGETED)))
        .addMethod(
          getDeleteCorrectionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.correction.DeleteCorrectionRequest,
              com.jervis.contracts.correction.DeleteCorrectionResponse>(
                service, METHODID_DELETE_CORRECTION)))
        .build();
  }

  private static abstract class CorrectionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    CorrectionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.correction.CorrectionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("CorrectionService");
    }
  }

  private static final class CorrectionServiceFileDescriptorSupplier
      extends CorrectionServiceBaseDescriptorSupplier {
    CorrectionServiceFileDescriptorSupplier() {}
  }

  private static final class CorrectionServiceMethodDescriptorSupplier
      extends CorrectionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    CorrectionServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (CorrectionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new CorrectionServiceFileDescriptorSupplier())
              .addMethod(getSubmitCorrectionMethod())
              .addMethod(getCorrectTranscriptMethod())
              .addMethod(getListCorrectionsMethod())
              .addMethod(getAnswerCorrectionQuestionsMethod())
              .addMethod(getCorrectWithInstructionMethod())
              .addMethod(getCorrectTargetedMethod())
              .addMethod(getDeleteCorrectionMethod())
              .build();
        }
      }
    }
    return result;
  }
}
