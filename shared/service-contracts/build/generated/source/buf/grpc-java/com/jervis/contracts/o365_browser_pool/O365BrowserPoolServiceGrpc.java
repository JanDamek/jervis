package com.jervis.contracts.o365_browser_pool;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * O365BrowserPoolService — typed wrapper over the per-client browser pod.
 * Replaces the former REST surface that was consumed by the Kotlin
 * server (/health, /session/{cid}, /session/{cid}/init,
 * /session/{cid}/mfa, /instruction/{cid}).
 * VNC flow: the browser talks HTTP/WS to the Kotlin server only (external
 * boundary, guideline §10). The server validates a single-use token
 * against MongoDB, opens a StreamVnc bidi stream to the pod, and pipes
 * raw RFB bytes between the browser WebSocket and the gRPC stream.
 * The pod never sees the token — the first client-&gt;server frame carries
 * `client_id` to pick the Xvfb session, subsequent frames are raw bytes.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class O365BrowserPoolServiceGrpc {

  private O365BrowserPoolServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.o365_browser_pool.O365BrowserPoolService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.HealthRequest,
      com.jervis.contracts.o365_browser_pool.HealthResponse> getHealthMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Health",
      requestType = com.jervis.contracts.o365_browser_pool.HealthRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.HealthResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.HealthRequest,
      com.jervis.contracts.o365_browser_pool.HealthResponse> getHealthMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.HealthRequest, com.jervis.contracts.o365_browser_pool.HealthResponse> getHealthMethod;
    if ((getHealthMethod = O365BrowserPoolServiceGrpc.getHealthMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getHealthMethod = O365BrowserPoolServiceGrpc.getHealthMethod) == null) {
          O365BrowserPoolServiceGrpc.getHealthMethod = getHealthMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.HealthRequest, com.jervis.contracts.o365_browser_pool.HealthResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Health"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.HealthRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.HealthResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("Health"))
              .build();
        }
      }
    }
    return getHealthMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.SessionRef,
      com.jervis.contracts.o365_browser_pool.SessionStatus> getGetSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSession",
      requestType = com.jervis.contracts.o365_browser_pool.SessionRef.class,
      responseType = com.jervis.contracts.o365_browser_pool.SessionStatus.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.SessionRef,
      com.jervis.contracts.o365_browser_pool.SessionStatus> getGetSessionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.SessionRef, com.jervis.contracts.o365_browser_pool.SessionStatus> getGetSessionMethod;
    if ((getGetSessionMethod = O365BrowserPoolServiceGrpc.getGetSessionMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getGetSessionMethod = O365BrowserPoolServiceGrpc.getGetSessionMethod) == null) {
          O365BrowserPoolServiceGrpc.getGetSessionMethod = getGetSessionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.SessionRef, com.jervis.contracts.o365_browser_pool.SessionStatus>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.SessionRef.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.SessionStatus.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("GetSession"))
              .build();
        }
      }
    }
    return getGetSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.InitSessionRequest,
      com.jervis.contracts.o365_browser_pool.InitSessionResponse> getInitSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "InitSession",
      requestType = com.jervis.contracts.o365_browser_pool.InitSessionRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.InitSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.InitSessionRequest,
      com.jervis.contracts.o365_browser_pool.InitSessionResponse> getInitSessionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.InitSessionRequest, com.jervis.contracts.o365_browser_pool.InitSessionResponse> getInitSessionMethod;
    if ((getInitSessionMethod = O365BrowserPoolServiceGrpc.getInitSessionMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getInitSessionMethod = O365BrowserPoolServiceGrpc.getInitSessionMethod) == null) {
          O365BrowserPoolServiceGrpc.getInitSessionMethod = getInitSessionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.InitSessionRequest, com.jervis.contracts.o365_browser_pool.InitSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "InitSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.InitSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.InitSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("InitSession"))
              .build();
        }
      }
    }
    return getInitSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.SubmitMfaRequest,
      com.jervis.contracts.o365_browser_pool.InitSessionResponse> getSubmitMfaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SubmitMfa",
      requestType = com.jervis.contracts.o365_browser_pool.SubmitMfaRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.InitSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.SubmitMfaRequest,
      com.jervis.contracts.o365_browser_pool.InitSessionResponse> getSubmitMfaMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.SubmitMfaRequest, com.jervis.contracts.o365_browser_pool.InitSessionResponse> getSubmitMfaMethod;
    if ((getSubmitMfaMethod = O365BrowserPoolServiceGrpc.getSubmitMfaMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getSubmitMfaMethod = O365BrowserPoolServiceGrpc.getSubmitMfaMethod) == null) {
          O365BrowserPoolServiceGrpc.getSubmitMfaMethod = getSubmitMfaMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.SubmitMfaRequest, com.jervis.contracts.o365_browser_pool.InitSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SubmitMfa"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.SubmitMfaRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.InitSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("SubmitMfa"))
              .build();
        }
      }
    }
    return getSubmitMfaMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.InstructionRequest,
      com.jervis.contracts.o365_browser_pool.InstructionResponse> getPushInstructionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PushInstruction",
      requestType = com.jervis.contracts.o365_browser_pool.InstructionRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.InstructionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.InstructionRequest,
      com.jervis.contracts.o365_browser_pool.InstructionResponse> getPushInstructionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.InstructionRequest, com.jervis.contracts.o365_browser_pool.InstructionResponse> getPushInstructionMethod;
    if ((getPushInstructionMethod = O365BrowserPoolServiceGrpc.getPushInstructionMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getPushInstructionMethod = O365BrowserPoolServiceGrpc.getPushInstructionMethod) == null) {
          O365BrowserPoolServiceGrpc.getPushInstructionMethod = getPushInstructionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.InstructionRequest, com.jervis.contracts.o365_browser_pool.InstructionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PushInstruction"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.InstructionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.InstructionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("PushInstruction"))
              .build();
        }
      }
    }
    return getPushInstructionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.VncFrame,
      com.jervis.contracts.o365_browser_pool.VncFrame> getStreamVncMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamVnc",
      requestType = com.jervis.contracts.o365_browser_pool.VncFrame.class,
      responseType = com.jervis.contracts.o365_browser_pool.VncFrame.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.VncFrame,
      com.jervis.contracts.o365_browser_pool.VncFrame> getStreamVncMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.VncFrame, com.jervis.contracts.o365_browser_pool.VncFrame> getStreamVncMethod;
    if ((getStreamVncMethod = O365BrowserPoolServiceGrpc.getStreamVncMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getStreamVncMethod = O365BrowserPoolServiceGrpc.getStreamVncMethod) == null) {
          O365BrowserPoolServiceGrpc.getStreamVncMethod = getStreamVncMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.VncFrame, com.jervis.contracts.o365_browser_pool.VncFrame>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamVnc"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.VncFrame.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.VncFrame.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("StreamVnc"))
              .build();
        }
      }
    }
    return getStreamVncMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static O365BrowserPoolServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<O365BrowserPoolServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<O365BrowserPoolServiceStub>() {
        @java.lang.Override
        public O365BrowserPoolServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new O365BrowserPoolServiceStub(channel, callOptions);
        }
      };
    return O365BrowserPoolServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static O365BrowserPoolServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<O365BrowserPoolServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<O365BrowserPoolServiceBlockingV2Stub>() {
        @java.lang.Override
        public O365BrowserPoolServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new O365BrowserPoolServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return O365BrowserPoolServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static O365BrowserPoolServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<O365BrowserPoolServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<O365BrowserPoolServiceBlockingStub>() {
        @java.lang.Override
        public O365BrowserPoolServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new O365BrowserPoolServiceBlockingStub(channel, callOptions);
        }
      };
    return O365BrowserPoolServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static O365BrowserPoolServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<O365BrowserPoolServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<O365BrowserPoolServiceFutureStub>() {
        @java.lang.Override
        public O365BrowserPoolServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new O365BrowserPoolServiceFutureStub(channel, callOptions);
        }
      };
    return O365BrowserPoolServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * O365BrowserPoolService — typed wrapper over the per-client browser pod.
   * Replaces the former REST surface that was consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /instruction/{cid}).
   * VNC flow: the browser talks HTTP/WS to the Kotlin server only (external
   * boundary, guideline §10). The server validates a single-use token
   * against MongoDB, opens a StreamVnc bidi stream to the pod, and pipes
   * raw RFB bytes between the browser WebSocket and the gRPC stream.
   * The pod never sees the token — the first client-&gt;server frame carries
   * `client_id` to pick the Xvfb session, subsequent frames are raw bytes.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void health(com.jervis.contracts.o365_browser_pool.HealthRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.HealthResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHealthMethod(), responseObserver);
    }

    /**
     */
    default void getSession(com.jervis.contracts.o365_browser_pool.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.SessionStatus> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetSessionMethod(), responseObserver);
    }

    /**
     */
    default void initSession(com.jervis.contracts.o365_browser_pool.InitSessionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.InitSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInitSessionMethod(), responseObserver);
    }

    /**
     */
    default void submitMfa(com.jervis.contracts.o365_browser_pool.SubmitMfaRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.InitSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSubmitMfaMethod(), responseObserver);
    }

    /**
     */
    default void pushInstruction(com.jervis.contracts.o365_browser_pool.InstructionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.InstructionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPushInstructionMethod(), responseObserver);
    }

    /**
     */
    default io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.VncFrame> streamVnc(
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.VncFrame> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamVncMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service O365BrowserPoolService.
   * <pre>
   * O365BrowserPoolService — typed wrapper over the per-client browser pod.
   * Replaces the former REST surface that was consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /instruction/{cid}).
   * VNC flow: the browser talks HTTP/WS to the Kotlin server only (external
   * boundary, guideline §10). The server validates a single-use token
   * against MongoDB, opens a StreamVnc bidi stream to the pod, and pipes
   * raw RFB bytes between the browser WebSocket and the gRPC stream.
   * The pod never sees the token — the first client-&gt;server frame carries
   * `client_id` to pick the Xvfb session, subsequent frames are raw bytes.
   * </pre>
   */
  public static abstract class O365BrowserPoolServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return O365BrowserPoolServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service O365BrowserPoolService.
   * <pre>
   * O365BrowserPoolService — typed wrapper over the per-client browser pod.
   * Replaces the former REST surface that was consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /instruction/{cid}).
   * VNC flow: the browser talks HTTP/WS to the Kotlin server only (external
   * boundary, guideline §10). The server validates a single-use token
   * against MongoDB, opens a StreamVnc bidi stream to the pod, and pipes
   * raw RFB bytes between the browser WebSocket and the gRPC stream.
   * The pod never sees the token — the first client-&gt;server frame carries
   * `client_id` to pick the Xvfb session, subsequent frames are raw bytes.
   * </pre>
   */
  public static final class O365BrowserPoolServiceStub
      extends io.grpc.stub.AbstractAsyncStub<O365BrowserPoolServiceStub> {
    private O365BrowserPoolServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected O365BrowserPoolServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new O365BrowserPoolServiceStub(channel, callOptions);
    }

    /**
     */
    public void health(com.jervis.contracts.o365_browser_pool.HealthRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.HealthResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getSession(com.jervis.contracts.o365_browser_pool.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.SessionStatus> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void initSession(com.jervis.contracts.o365_browser_pool.InitSessionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.InitSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInitSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void submitMfa(com.jervis.contracts.o365_browser_pool.SubmitMfaRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.InitSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSubmitMfaMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void pushInstruction(com.jervis.contracts.o365_browser_pool.InstructionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.InstructionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPushInstructionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.VncFrame> streamVnc(
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.VncFrame> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getStreamVncMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service O365BrowserPoolService.
   * <pre>
   * O365BrowserPoolService — typed wrapper over the per-client browser pod.
   * Replaces the former REST surface that was consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /instruction/{cid}).
   * VNC flow: the browser talks HTTP/WS to the Kotlin server only (external
   * boundary, guideline §10). The server validates a single-use token
   * against MongoDB, opens a StreamVnc bidi stream to the pod, and pipes
   * raw RFB bytes between the browser WebSocket and the gRPC stream.
   * The pod never sees the token — the first client-&gt;server frame carries
   * `client_id` to pick the Xvfb session, subsequent frames are raw bytes.
   * </pre>
   */
  public static final class O365BrowserPoolServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<O365BrowserPoolServiceBlockingV2Stub> {
    private O365BrowserPoolServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected O365BrowserPoolServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new O365BrowserPoolServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.HealthResponse health(com.jervis.contracts.o365_browser_pool.HealthRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.SessionStatus getSession(com.jervis.contracts.o365_browser_pool.SessionRef request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.InitSessionResponse initSession(com.jervis.contracts.o365_browser_pool.InitSessionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInitSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.InitSessionResponse submitMfa(com.jervis.contracts.o365_browser_pool.SubmitMfaRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSubmitMfaMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.InstructionResponse pushInstruction(com.jervis.contracts.o365_browser_pool.InstructionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPushInstructionMethod(), getCallOptions(), request);
    }

    /**
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<com.jervis.contracts.o365_browser_pool.VncFrame, com.jervis.contracts.o365_browser_pool.VncFrame>
        streamVnc() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getStreamVncMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service O365BrowserPoolService.
   * <pre>
   * O365BrowserPoolService — typed wrapper over the per-client browser pod.
   * Replaces the former REST surface that was consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /instruction/{cid}).
   * VNC flow: the browser talks HTTP/WS to the Kotlin server only (external
   * boundary, guideline §10). The server validates a single-use token
   * against MongoDB, opens a StreamVnc bidi stream to the pod, and pipes
   * raw RFB bytes between the browser WebSocket and the gRPC stream.
   * The pod never sees the token — the first client-&gt;server frame carries
   * `client_id` to pick the Xvfb session, subsequent frames are raw bytes.
   * </pre>
   */
  public static final class O365BrowserPoolServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<O365BrowserPoolServiceBlockingStub> {
    private O365BrowserPoolServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected O365BrowserPoolServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new O365BrowserPoolServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.HealthResponse health(com.jervis.contracts.o365_browser_pool.HealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.SessionStatus getSession(com.jervis.contracts.o365_browser_pool.SessionRef request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.InitSessionResponse initSession(com.jervis.contracts.o365_browser_pool.InitSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInitSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.InitSessionResponse submitMfa(com.jervis.contracts.o365_browser_pool.SubmitMfaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSubmitMfaMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.InstructionResponse pushInstruction(com.jervis.contracts.o365_browser_pool.InstructionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPushInstructionMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service O365BrowserPoolService.
   * <pre>
   * O365BrowserPoolService — typed wrapper over the per-client browser pod.
   * Replaces the former REST surface that was consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /instruction/{cid}).
   * VNC flow: the browser talks HTTP/WS to the Kotlin server only (external
   * boundary, guideline §10). The server validates a single-use token
   * against MongoDB, opens a StreamVnc bidi stream to the pod, and pipes
   * raw RFB bytes between the browser WebSocket and the gRPC stream.
   * The pod never sees the token — the first client-&gt;server frame carries
   * `client_id` to pick the Xvfb session, subsequent frames are raw bytes.
   * </pre>
   */
  public static final class O365BrowserPoolServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<O365BrowserPoolServiceFutureStub> {
    private O365BrowserPoolServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected O365BrowserPoolServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new O365BrowserPoolServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.HealthResponse> health(
        com.jervis.contracts.o365_browser_pool.HealthRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.SessionStatus> getSession(
        com.jervis.contracts.o365_browser_pool.SessionRef request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetSessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.InitSessionResponse> initSession(
        com.jervis.contracts.o365_browser_pool.InitSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInitSessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.InitSessionResponse> submitMfa(
        com.jervis.contracts.o365_browser_pool.SubmitMfaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSubmitMfaMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.InstructionResponse> pushInstruction(
        com.jervis.contracts.o365_browser_pool.InstructionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPushInstructionMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_HEALTH = 0;
  private static final int METHODID_GET_SESSION = 1;
  private static final int METHODID_INIT_SESSION = 2;
  private static final int METHODID_SUBMIT_MFA = 3;
  private static final int METHODID_PUSH_INSTRUCTION = 4;
  private static final int METHODID_STREAM_VNC = 5;

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
        case METHODID_HEALTH:
          serviceImpl.health((com.jervis.contracts.o365_browser_pool.HealthRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.HealthResponse>) responseObserver);
          break;
        case METHODID_GET_SESSION:
          serviceImpl.getSession((com.jervis.contracts.o365_browser_pool.SessionRef) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.SessionStatus>) responseObserver);
          break;
        case METHODID_INIT_SESSION:
          serviceImpl.initSession((com.jervis.contracts.o365_browser_pool.InitSessionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.InitSessionResponse>) responseObserver);
          break;
        case METHODID_SUBMIT_MFA:
          serviceImpl.submitMfa((com.jervis.contracts.o365_browser_pool.SubmitMfaRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.InitSessionResponse>) responseObserver);
          break;
        case METHODID_PUSH_INSTRUCTION:
          serviceImpl.pushInstruction((com.jervis.contracts.o365_browser_pool.InstructionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.InstructionResponse>) responseObserver);
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
        case METHODID_STREAM_VNC:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamVnc(
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.VncFrame>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getHealthMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.HealthRequest,
              com.jervis.contracts.o365_browser_pool.HealthResponse>(
                service, METHODID_HEALTH)))
        .addMethod(
          getGetSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.SessionRef,
              com.jervis.contracts.o365_browser_pool.SessionStatus>(
                service, METHODID_GET_SESSION)))
        .addMethod(
          getInitSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.InitSessionRequest,
              com.jervis.contracts.o365_browser_pool.InitSessionResponse>(
                service, METHODID_INIT_SESSION)))
        .addMethod(
          getSubmitMfaMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.SubmitMfaRequest,
              com.jervis.contracts.o365_browser_pool.InitSessionResponse>(
                service, METHODID_SUBMIT_MFA)))
        .addMethod(
          getPushInstructionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.InstructionRequest,
              com.jervis.contracts.o365_browser_pool.InstructionResponse>(
                service, METHODID_PUSH_INSTRUCTION)))
        .addMethod(
          getStreamVncMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.VncFrame,
              com.jervis.contracts.o365_browser_pool.VncFrame>(
                service, METHODID_STREAM_VNC)))
        .build();
  }

  private static abstract class O365BrowserPoolServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    O365BrowserPoolServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.o365_browser_pool.O365BrowserPoolProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("O365BrowserPoolService");
    }
  }

  private static final class O365BrowserPoolServiceFileDescriptorSupplier
      extends O365BrowserPoolServiceBaseDescriptorSupplier {
    O365BrowserPoolServiceFileDescriptorSupplier() {}
  }

  private static final class O365BrowserPoolServiceMethodDescriptorSupplier
      extends O365BrowserPoolServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    O365BrowserPoolServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (O365BrowserPoolServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new O365BrowserPoolServiceFileDescriptorSupplier())
              .addMethod(getHealthMethod())
              .addMethod(getGetSessionMethod())
              .addMethod(getInitSessionMethod())
              .addMethod(getSubmitMfaMethod())
              .addMethod(getPushInstructionMethod())
              .addMethod(getStreamVncMethod())
              .build();
        }
      }
    }
    return result;
  }
}
