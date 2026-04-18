package com.jervis.contracts.orchestrator;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * OrchestratorCompanionService — Claude companion dispatch + persistent
 * sessions. Adhoc dispatch maps to the existing FastAPI /companion/adhoc
 * route; session RPCs carry live transcript events; StreamSession is the
 * first server-streaming RPC in the orchestrator surface.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class OrchestratorCompanionServiceGrpc {

  private OrchestratorCompanionServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.orchestrator.OrchestratorCompanionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.AdhocRequest,
      com.jervis.contracts.orchestrator.AdhocAck> getAdhocMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Adhoc",
      requestType = com.jervis.contracts.orchestrator.AdhocRequest.class,
      responseType = com.jervis.contracts.orchestrator.AdhocAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.AdhocRequest,
      com.jervis.contracts.orchestrator.AdhocAck> getAdhocMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.AdhocRequest, com.jervis.contracts.orchestrator.AdhocAck> getAdhocMethod;
    if ((getAdhocMethod = OrchestratorCompanionServiceGrpc.getAdhocMethod) == null) {
      synchronized (OrchestratorCompanionServiceGrpc.class) {
        if ((getAdhocMethod = OrchestratorCompanionServiceGrpc.getAdhocMethod) == null) {
          OrchestratorCompanionServiceGrpc.getAdhocMethod = getAdhocMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.AdhocRequest, com.jervis.contracts.orchestrator.AdhocAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Adhoc"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.AdhocRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.AdhocAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorCompanionServiceMethodDescriptorSupplier("Adhoc"))
              .build();
        }
      }
    }
    return getAdhocMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.AdhocStatusRequest,
      com.jervis.contracts.orchestrator.AdhocStatusResponse> getAdhocStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AdhocStatus",
      requestType = com.jervis.contracts.orchestrator.AdhocStatusRequest.class,
      responseType = com.jervis.contracts.orchestrator.AdhocStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.AdhocStatusRequest,
      com.jervis.contracts.orchestrator.AdhocStatusResponse> getAdhocStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.AdhocStatusRequest, com.jervis.contracts.orchestrator.AdhocStatusResponse> getAdhocStatusMethod;
    if ((getAdhocStatusMethod = OrchestratorCompanionServiceGrpc.getAdhocStatusMethod) == null) {
      synchronized (OrchestratorCompanionServiceGrpc.class) {
        if ((getAdhocStatusMethod = OrchestratorCompanionServiceGrpc.getAdhocStatusMethod) == null) {
          OrchestratorCompanionServiceGrpc.getAdhocStatusMethod = getAdhocStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.AdhocStatusRequest, com.jervis.contracts.orchestrator.AdhocStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AdhocStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.AdhocStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.AdhocStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorCompanionServiceMethodDescriptorSupplier("AdhocStatus"))
              .build();
        }
      }
    }
    return getAdhocStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.SessionStartRequest,
      com.jervis.contracts.orchestrator.SessionStartResponse> getStartSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StartSession",
      requestType = com.jervis.contracts.orchestrator.SessionStartRequest.class,
      responseType = com.jervis.contracts.orchestrator.SessionStartResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.SessionStartRequest,
      com.jervis.contracts.orchestrator.SessionStartResponse> getStartSessionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.SessionStartRequest, com.jervis.contracts.orchestrator.SessionStartResponse> getStartSessionMethod;
    if ((getStartSessionMethod = OrchestratorCompanionServiceGrpc.getStartSessionMethod) == null) {
      synchronized (OrchestratorCompanionServiceGrpc.class) {
        if ((getStartSessionMethod = OrchestratorCompanionServiceGrpc.getStartSessionMethod) == null) {
          OrchestratorCompanionServiceGrpc.getStartSessionMethod = getStartSessionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.SessionStartRequest, com.jervis.contracts.orchestrator.SessionStartResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StartSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.SessionStartRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.SessionStartResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorCompanionServiceMethodDescriptorSupplier("StartSession"))
              .build();
        }
      }
    }
    return getStartSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.SessionEventRequest,
      com.jervis.contracts.orchestrator.SessionEventAck> getSessionEventMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SessionEvent",
      requestType = com.jervis.contracts.orchestrator.SessionEventRequest.class,
      responseType = com.jervis.contracts.orchestrator.SessionEventAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.SessionEventRequest,
      com.jervis.contracts.orchestrator.SessionEventAck> getSessionEventMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.SessionEventRequest, com.jervis.contracts.orchestrator.SessionEventAck> getSessionEventMethod;
    if ((getSessionEventMethod = OrchestratorCompanionServiceGrpc.getSessionEventMethod) == null) {
      synchronized (OrchestratorCompanionServiceGrpc.class) {
        if ((getSessionEventMethod = OrchestratorCompanionServiceGrpc.getSessionEventMethod) == null) {
          OrchestratorCompanionServiceGrpc.getSessionEventMethod = getSessionEventMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.SessionEventRequest, com.jervis.contracts.orchestrator.SessionEventAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SessionEvent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.SessionEventRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.SessionEventAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorCompanionServiceMethodDescriptorSupplier("SessionEvent"))
              .build();
        }
      }
    }
    return getSessionEventMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.SessionRef,
      com.jervis.contracts.orchestrator.SessionAck> getStopSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StopSession",
      requestType = com.jervis.contracts.orchestrator.SessionRef.class,
      responseType = com.jervis.contracts.orchestrator.SessionAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.SessionRef,
      com.jervis.contracts.orchestrator.SessionAck> getStopSessionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.SessionRef, com.jervis.contracts.orchestrator.SessionAck> getStopSessionMethod;
    if ((getStopSessionMethod = OrchestratorCompanionServiceGrpc.getStopSessionMethod) == null) {
      synchronized (OrchestratorCompanionServiceGrpc.class) {
        if ((getStopSessionMethod = OrchestratorCompanionServiceGrpc.getStopSessionMethod) == null) {
          OrchestratorCompanionServiceGrpc.getStopSessionMethod = getStopSessionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.SessionRef, com.jervis.contracts.orchestrator.SessionAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StopSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.SessionRef.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.SessionAck.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorCompanionServiceMethodDescriptorSupplier("StopSession"))
              .build();
        }
      }
    }
    return getStopSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StreamSessionRequest,
      com.jervis.contracts.orchestrator.OutboxEvent> getStreamSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamSession",
      requestType = com.jervis.contracts.orchestrator.StreamSessionRequest.class,
      responseType = com.jervis.contracts.orchestrator.OutboxEvent.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StreamSessionRequest,
      com.jervis.contracts.orchestrator.OutboxEvent> getStreamSessionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.orchestrator.StreamSessionRequest, com.jervis.contracts.orchestrator.OutboxEvent> getStreamSessionMethod;
    if ((getStreamSessionMethod = OrchestratorCompanionServiceGrpc.getStreamSessionMethod) == null) {
      synchronized (OrchestratorCompanionServiceGrpc.class) {
        if ((getStreamSessionMethod = OrchestratorCompanionServiceGrpc.getStreamSessionMethod) == null) {
          OrchestratorCompanionServiceGrpc.getStreamSessionMethod = getStreamSessionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.orchestrator.StreamSessionRequest, com.jervis.contracts.orchestrator.OutboxEvent>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.StreamSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.orchestrator.OutboxEvent.getDefaultInstance()))
              .setSchemaDescriptor(new OrchestratorCompanionServiceMethodDescriptorSupplier("StreamSession"))
              .build();
        }
      }
    }
    return getStreamSessionMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OrchestratorCompanionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorCompanionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorCompanionServiceStub>() {
        @java.lang.Override
        public OrchestratorCompanionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorCompanionServiceStub(channel, callOptions);
        }
      };
    return OrchestratorCompanionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static OrchestratorCompanionServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorCompanionServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorCompanionServiceBlockingV2Stub>() {
        @java.lang.Override
        public OrchestratorCompanionServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorCompanionServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return OrchestratorCompanionServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OrchestratorCompanionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorCompanionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorCompanionServiceBlockingStub>() {
        @java.lang.Override
        public OrchestratorCompanionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorCompanionServiceBlockingStub(channel, callOptions);
        }
      };
    return OrchestratorCompanionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OrchestratorCompanionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OrchestratorCompanionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OrchestratorCompanionServiceFutureStub>() {
        @java.lang.Override
        public OrchestratorCompanionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OrchestratorCompanionServiceFutureStub(channel, callOptions);
        }
      };
    return OrchestratorCompanionServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * OrchestratorCompanionService — Claude companion dispatch + persistent
   * sessions. Adhoc dispatch maps to the existing FastAPI /companion/adhoc
   * route; session RPCs carry live transcript events; StreamSession is the
   * first server-streaming RPC in the orchestrator surface.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void adhoc(com.jervis.contracts.orchestrator.AdhocRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.AdhocAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAdhocMethod(), responseObserver);
    }

    /**
     */
    default void adhocStatus(com.jervis.contracts.orchestrator.AdhocStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.AdhocStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAdhocStatusMethod(), responseObserver);
    }

    /**
     */
    default void startSession(com.jervis.contracts.orchestrator.SessionStartRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionStartResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStartSessionMethod(), responseObserver);
    }

    /**
     */
    default void sessionEvent(com.jervis.contracts.orchestrator.SessionEventRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionEventAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSessionEventMethod(), responseObserver);
    }

    /**
     */
    default void stopSession(com.jervis.contracts.orchestrator.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStopSessionMethod(), responseObserver);
    }

    /**
     */
    default void streamSession(com.jervis.contracts.orchestrator.StreamSessionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.OutboxEvent> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStreamSessionMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OrchestratorCompanionService.
   * <pre>
   * OrchestratorCompanionService — Claude companion dispatch + persistent
   * sessions. Adhoc dispatch maps to the existing FastAPI /companion/adhoc
   * route; session RPCs carry live transcript events; StreamSession is the
   * first server-streaming RPC in the orchestrator surface.
   * </pre>
   */
  public static abstract class OrchestratorCompanionServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OrchestratorCompanionServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OrchestratorCompanionService.
   * <pre>
   * OrchestratorCompanionService — Claude companion dispatch + persistent
   * sessions. Adhoc dispatch maps to the existing FastAPI /companion/adhoc
   * route; session RPCs carry live transcript events; StreamSession is the
   * first server-streaming RPC in the orchestrator surface.
   * </pre>
   */
  public static final class OrchestratorCompanionServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OrchestratorCompanionServiceStub> {
    private OrchestratorCompanionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorCompanionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorCompanionServiceStub(channel, callOptions);
    }

    /**
     */
    public void adhoc(com.jervis.contracts.orchestrator.AdhocRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.AdhocAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAdhocMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void adhocStatus(com.jervis.contracts.orchestrator.AdhocStatusRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.AdhocStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAdhocStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void startSession(com.jervis.contracts.orchestrator.SessionStartRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionStartResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStartSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sessionEvent(com.jervis.contracts.orchestrator.SessionEventRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionEventAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSessionEventMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void stopSession(com.jervis.contracts.orchestrator.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStopSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void streamSession(com.jervis.contracts.orchestrator.StreamSessionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.OutboxEvent> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getStreamSessionMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OrchestratorCompanionService.
   * <pre>
   * OrchestratorCompanionService — Claude companion dispatch + persistent
   * sessions. Adhoc dispatch maps to the existing FastAPI /companion/adhoc
   * route; session RPCs carry live transcript events; StreamSession is the
   * first server-streaming RPC in the orchestrator surface.
   * </pre>
   */
  public static final class OrchestratorCompanionServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorCompanionServiceBlockingV2Stub> {
    private OrchestratorCompanionServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorCompanionServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorCompanionServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.AdhocAck adhoc(com.jervis.contracts.orchestrator.AdhocRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAdhocMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.AdhocStatusResponse adhocStatus(com.jervis.contracts.orchestrator.AdhocStatusRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAdhocStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.SessionStartResponse startSession(com.jervis.contracts.orchestrator.SessionStartRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStartSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.SessionEventAck sessionEvent(com.jervis.contracts.orchestrator.SessionEventRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSessionEventMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.SessionAck stopSession(com.jervis.contracts.orchestrator.SessionRef request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStopSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, com.jervis.contracts.orchestrator.OutboxEvent>
        streamSession(com.jervis.contracts.orchestrator.StreamSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getStreamSessionMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service OrchestratorCompanionService.
   * <pre>
   * OrchestratorCompanionService — Claude companion dispatch + persistent
   * sessions. Adhoc dispatch maps to the existing FastAPI /companion/adhoc
   * route; session RPCs carry live transcript events; StreamSession is the
   * first server-streaming RPC in the orchestrator surface.
   * </pre>
   */
  public static final class OrchestratorCompanionServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OrchestratorCompanionServiceBlockingStub> {
    private OrchestratorCompanionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorCompanionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorCompanionServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.AdhocAck adhoc(com.jervis.contracts.orchestrator.AdhocRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAdhocMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.AdhocStatusResponse adhocStatus(com.jervis.contracts.orchestrator.AdhocStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAdhocStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.SessionStartResponse startSession(com.jervis.contracts.orchestrator.SessionStartRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStartSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.SessionEventAck sessionEvent(com.jervis.contracts.orchestrator.SessionEventRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSessionEventMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.orchestrator.SessionAck stopSession(com.jervis.contracts.orchestrator.SessionRef request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStopSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<com.jervis.contracts.orchestrator.OutboxEvent> streamSession(
        com.jervis.contracts.orchestrator.StreamSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getStreamSessionMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OrchestratorCompanionService.
   * <pre>
   * OrchestratorCompanionService — Claude companion dispatch + persistent
   * sessions. Adhoc dispatch maps to the existing FastAPI /companion/adhoc
   * route; session RPCs carry live transcript events; StreamSession is the
   * first server-streaming RPC in the orchestrator surface.
   * </pre>
   */
  public static final class OrchestratorCompanionServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OrchestratorCompanionServiceFutureStub> {
    private OrchestratorCompanionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OrchestratorCompanionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OrchestratorCompanionServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.AdhocAck> adhoc(
        com.jervis.contracts.orchestrator.AdhocRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAdhocMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.AdhocStatusResponse> adhocStatus(
        com.jervis.contracts.orchestrator.AdhocStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAdhocStatusMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.SessionStartResponse> startSession(
        com.jervis.contracts.orchestrator.SessionStartRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStartSessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.SessionEventAck> sessionEvent(
        com.jervis.contracts.orchestrator.SessionEventRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSessionEventMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.orchestrator.SessionAck> stopSession(
        com.jervis.contracts.orchestrator.SessionRef request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStopSessionMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_ADHOC = 0;
  private static final int METHODID_ADHOC_STATUS = 1;
  private static final int METHODID_START_SESSION = 2;
  private static final int METHODID_SESSION_EVENT = 3;
  private static final int METHODID_STOP_SESSION = 4;
  private static final int METHODID_STREAM_SESSION = 5;

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
        case METHODID_ADHOC:
          serviceImpl.adhoc((com.jervis.contracts.orchestrator.AdhocRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.AdhocAck>) responseObserver);
          break;
        case METHODID_ADHOC_STATUS:
          serviceImpl.adhocStatus((com.jervis.contracts.orchestrator.AdhocStatusRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.AdhocStatusResponse>) responseObserver);
          break;
        case METHODID_START_SESSION:
          serviceImpl.startSession((com.jervis.contracts.orchestrator.SessionStartRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionStartResponse>) responseObserver);
          break;
        case METHODID_SESSION_EVENT:
          serviceImpl.sessionEvent((com.jervis.contracts.orchestrator.SessionEventRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionEventAck>) responseObserver);
          break;
        case METHODID_STOP_SESSION:
          serviceImpl.stopSession((com.jervis.contracts.orchestrator.SessionRef) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.SessionAck>) responseObserver);
          break;
        case METHODID_STREAM_SESSION:
          serviceImpl.streamSession((com.jervis.contracts.orchestrator.StreamSessionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.orchestrator.OutboxEvent>) responseObserver);
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
          getAdhocMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.AdhocRequest,
              com.jervis.contracts.orchestrator.AdhocAck>(
                service, METHODID_ADHOC)))
        .addMethod(
          getAdhocStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.AdhocStatusRequest,
              com.jervis.contracts.orchestrator.AdhocStatusResponse>(
                service, METHODID_ADHOC_STATUS)))
        .addMethod(
          getStartSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.SessionStartRequest,
              com.jervis.contracts.orchestrator.SessionStartResponse>(
                service, METHODID_START_SESSION)))
        .addMethod(
          getSessionEventMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.SessionEventRequest,
              com.jervis.contracts.orchestrator.SessionEventAck>(
                service, METHODID_SESSION_EVENT)))
        .addMethod(
          getStopSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.SessionRef,
              com.jervis.contracts.orchestrator.SessionAck>(
                service, METHODID_STOP_SESSION)))
        .addMethod(
          getStreamSessionMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.jervis.contracts.orchestrator.StreamSessionRequest,
              com.jervis.contracts.orchestrator.OutboxEvent>(
                service, METHODID_STREAM_SESSION)))
        .build();
  }

  private static abstract class OrchestratorCompanionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OrchestratorCompanionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.orchestrator.OrchestratorCompanionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OrchestratorCompanionService");
    }
  }

  private static final class OrchestratorCompanionServiceFileDescriptorSupplier
      extends OrchestratorCompanionServiceBaseDescriptorSupplier {
    OrchestratorCompanionServiceFileDescriptorSupplier() {}
  }

  private static final class OrchestratorCompanionServiceMethodDescriptorSupplier
      extends OrchestratorCompanionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OrchestratorCompanionServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OrchestratorCompanionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OrchestratorCompanionServiceFileDescriptorSupplier())
              .addMethod(getAdhocMethod())
              .addMethod(getAdhocStatusMethod())
              .addMethod(getStartSessionMethod())
              .addMethod(getSessionEventMethod())
              .addMethod(getStopSessionMethod())
              .addMethod(getStreamSessionMethod())
              .build();
        }
      }
    }
    return result;
  }
}
