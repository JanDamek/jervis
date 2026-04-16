package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerMeetingRecordingBridgeService — inbound bridge for the
 * `service-meeting-attender` K8s pod. The attender captures meeting
 * audio on the user's behalf (§2B) and uploads PCM chunks here. Chunks
 * stay inline (base64, ~64 KiB PCM each — see docs/inter-service-contracts.md §2.3).
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerMeetingRecordingBridgeServiceGrpc {

  private ServerMeetingRecordingBridgeServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerMeetingRecordingBridgeService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.StartRecordingRequest,
      com.jervis.contracts.server.StartRecordingResponse> getStartRecordingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StartRecording",
      requestType = com.jervis.contracts.server.StartRecordingRequest.class,
      responseType = com.jervis.contracts.server.StartRecordingResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.StartRecordingRequest,
      com.jervis.contracts.server.StartRecordingResponse> getStartRecordingMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.StartRecordingRequest, com.jervis.contracts.server.StartRecordingResponse> getStartRecordingMethod;
    if ((getStartRecordingMethod = ServerMeetingRecordingBridgeServiceGrpc.getStartRecordingMethod) == null) {
      synchronized (ServerMeetingRecordingBridgeServiceGrpc.class) {
        if ((getStartRecordingMethod = ServerMeetingRecordingBridgeServiceGrpc.getStartRecordingMethod) == null) {
          ServerMeetingRecordingBridgeServiceGrpc.getStartRecordingMethod = getStartRecordingMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.StartRecordingRequest, com.jervis.contracts.server.StartRecordingResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StartRecording"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.StartRecordingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.StartRecordingResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingRecordingBridgeServiceMethodDescriptorSupplier("StartRecording"))
              .build();
        }
      }
    }
    return getStartRecordingMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.UploadChunkRequest,
      com.jervis.contracts.server.UploadChunkResponse> getUploadChunkMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UploadChunk",
      requestType = com.jervis.contracts.server.UploadChunkRequest.class,
      responseType = com.jervis.contracts.server.UploadChunkResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.UploadChunkRequest,
      com.jervis.contracts.server.UploadChunkResponse> getUploadChunkMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.UploadChunkRequest, com.jervis.contracts.server.UploadChunkResponse> getUploadChunkMethod;
    if ((getUploadChunkMethod = ServerMeetingRecordingBridgeServiceGrpc.getUploadChunkMethod) == null) {
      synchronized (ServerMeetingRecordingBridgeServiceGrpc.class) {
        if ((getUploadChunkMethod = ServerMeetingRecordingBridgeServiceGrpc.getUploadChunkMethod) == null) {
          ServerMeetingRecordingBridgeServiceGrpc.getUploadChunkMethod = getUploadChunkMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.UploadChunkRequest, com.jervis.contracts.server.UploadChunkResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UploadChunk"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UploadChunkRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.UploadChunkResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingRecordingBridgeServiceMethodDescriptorSupplier("UploadChunk"))
              .build();
        }
      }
    }
    return getUploadChunkMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.FinalizeRecordingRequest,
      com.jervis.contracts.server.FinalizeRecordingResponse> getFinalizeRecordingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FinalizeRecording",
      requestType = com.jervis.contracts.server.FinalizeRecordingRequest.class,
      responseType = com.jervis.contracts.server.FinalizeRecordingResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.FinalizeRecordingRequest,
      com.jervis.contracts.server.FinalizeRecordingResponse> getFinalizeRecordingMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.FinalizeRecordingRequest, com.jervis.contracts.server.FinalizeRecordingResponse> getFinalizeRecordingMethod;
    if ((getFinalizeRecordingMethod = ServerMeetingRecordingBridgeServiceGrpc.getFinalizeRecordingMethod) == null) {
      synchronized (ServerMeetingRecordingBridgeServiceGrpc.class) {
        if ((getFinalizeRecordingMethod = ServerMeetingRecordingBridgeServiceGrpc.getFinalizeRecordingMethod) == null) {
          ServerMeetingRecordingBridgeServiceGrpc.getFinalizeRecordingMethod = getFinalizeRecordingMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.FinalizeRecordingRequest, com.jervis.contracts.server.FinalizeRecordingResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FinalizeRecording"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.FinalizeRecordingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.FinalizeRecordingResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingRecordingBridgeServiceMethodDescriptorSupplier("FinalizeRecording"))
              .build();
        }
      }
    }
    return getFinalizeRecordingMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerMeetingRecordingBridgeServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingRecordingBridgeServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingRecordingBridgeServiceStub>() {
        @java.lang.Override
        public ServerMeetingRecordingBridgeServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingRecordingBridgeServiceStub(channel, callOptions);
        }
      };
    return ServerMeetingRecordingBridgeServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerMeetingRecordingBridgeServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingRecordingBridgeServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingRecordingBridgeServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerMeetingRecordingBridgeServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingRecordingBridgeServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerMeetingRecordingBridgeServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerMeetingRecordingBridgeServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingRecordingBridgeServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingRecordingBridgeServiceBlockingStub>() {
        @java.lang.Override
        public ServerMeetingRecordingBridgeServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingRecordingBridgeServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerMeetingRecordingBridgeServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerMeetingRecordingBridgeServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingRecordingBridgeServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingRecordingBridgeServiceFutureStub>() {
        @java.lang.Override
        public ServerMeetingRecordingBridgeServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingRecordingBridgeServiceFutureStub(channel, callOptions);
        }
      };
    return ServerMeetingRecordingBridgeServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerMeetingRecordingBridgeService — inbound bridge for the
   * `service-meeting-attender` K8s pod. The attender captures meeting
   * audio on the user's behalf (§2B) and uploads PCM chunks here. Chunks
   * stay inline (base64, ~64 KiB PCM each — see docs/inter-service-contracts.md §2.3).
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Creates the MeetingDocument up-front; subsequent chunk uploads
     * attach to the returned meeting_id.
     * </pre>
     */
    default void startRecording(com.jervis.contracts.server.StartRecordingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.StartRecordingResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStartRecordingMethod(), responseObserver);
    }

    /**
     * <pre>
     * Appends a base64 PCM frame. Returns the running chunk count.
     * </pre>
     */
    default void uploadChunk(com.jervis.contracts.server.UploadChunkRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UploadChunkResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUploadChunkMethod(), responseObserver);
    }

    /**
     * <pre>
     * Fixes the WAV header and transitions the meeting to UPLOADED.
     * </pre>
     */
    default void finalizeRecording(com.jervis.contracts.server.FinalizeRecordingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.FinalizeRecordingResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getFinalizeRecordingMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerMeetingRecordingBridgeService.
   * <pre>
   * ServerMeetingRecordingBridgeService — inbound bridge for the
   * `service-meeting-attender` K8s pod. The attender captures meeting
   * audio on the user's behalf (§2B) and uploads PCM chunks here. Chunks
   * stay inline (base64, ~64 KiB PCM each — see docs/inter-service-contracts.md §2.3).
   * </pre>
   */
  public static abstract class ServerMeetingRecordingBridgeServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerMeetingRecordingBridgeServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerMeetingRecordingBridgeService.
   * <pre>
   * ServerMeetingRecordingBridgeService — inbound bridge for the
   * `service-meeting-attender` K8s pod. The attender captures meeting
   * audio on the user's behalf (§2B) and uploads PCM chunks here. Chunks
   * stay inline (base64, ~64 KiB PCM each — see docs/inter-service-contracts.md §2.3).
   * </pre>
   */
  public static final class ServerMeetingRecordingBridgeServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerMeetingRecordingBridgeServiceStub> {
    private ServerMeetingRecordingBridgeServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingRecordingBridgeServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingRecordingBridgeServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Creates the MeetingDocument up-front; subsequent chunk uploads
     * attach to the returned meeting_id.
     * </pre>
     */
    public void startRecording(com.jervis.contracts.server.StartRecordingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.StartRecordingResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStartRecordingMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Appends a base64 PCM frame. Returns the running chunk count.
     * </pre>
     */
    public void uploadChunk(com.jervis.contracts.server.UploadChunkRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.UploadChunkResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUploadChunkMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Fixes the WAV header and transitions the meeting to UPLOADED.
     * </pre>
     */
    public void finalizeRecording(com.jervis.contracts.server.FinalizeRecordingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.FinalizeRecordingResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getFinalizeRecordingMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerMeetingRecordingBridgeService.
   * <pre>
   * ServerMeetingRecordingBridgeService — inbound bridge for the
   * `service-meeting-attender` K8s pod. The attender captures meeting
   * audio on the user's behalf (§2B) and uploads PCM chunks here. Chunks
   * stay inline (base64, ~64 KiB PCM each — see docs/inter-service-contracts.md §2.3).
   * </pre>
   */
  public static final class ServerMeetingRecordingBridgeServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerMeetingRecordingBridgeServiceBlockingV2Stub> {
    private ServerMeetingRecordingBridgeServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingRecordingBridgeServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingRecordingBridgeServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Creates the MeetingDocument up-front; subsequent chunk uploads
     * attach to the returned meeting_id.
     * </pre>
     */
    public com.jervis.contracts.server.StartRecordingResponse startRecording(com.jervis.contracts.server.StartRecordingRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStartRecordingMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Appends a base64 PCM frame. Returns the running chunk count.
     * </pre>
     */
    public com.jervis.contracts.server.UploadChunkResponse uploadChunk(com.jervis.contracts.server.UploadChunkRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUploadChunkMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Fixes the WAV header and transitions the meeting to UPLOADED.
     * </pre>
     */
    public com.jervis.contracts.server.FinalizeRecordingResponse finalizeRecording(com.jervis.contracts.server.FinalizeRecordingRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getFinalizeRecordingMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerMeetingRecordingBridgeService.
   * <pre>
   * ServerMeetingRecordingBridgeService — inbound bridge for the
   * `service-meeting-attender` K8s pod. The attender captures meeting
   * audio on the user's behalf (§2B) and uploads PCM chunks here. Chunks
   * stay inline (base64, ~64 KiB PCM each — see docs/inter-service-contracts.md §2.3).
   * </pre>
   */
  public static final class ServerMeetingRecordingBridgeServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerMeetingRecordingBridgeServiceBlockingStub> {
    private ServerMeetingRecordingBridgeServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingRecordingBridgeServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingRecordingBridgeServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Creates the MeetingDocument up-front; subsequent chunk uploads
     * attach to the returned meeting_id.
     * </pre>
     */
    public com.jervis.contracts.server.StartRecordingResponse startRecording(com.jervis.contracts.server.StartRecordingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStartRecordingMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Appends a base64 PCM frame. Returns the running chunk count.
     * </pre>
     */
    public com.jervis.contracts.server.UploadChunkResponse uploadChunk(com.jervis.contracts.server.UploadChunkRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUploadChunkMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Fixes the WAV header and transitions the meeting to UPLOADED.
     * </pre>
     */
    public com.jervis.contracts.server.FinalizeRecordingResponse finalizeRecording(com.jervis.contracts.server.FinalizeRecordingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getFinalizeRecordingMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerMeetingRecordingBridgeService.
   * <pre>
   * ServerMeetingRecordingBridgeService — inbound bridge for the
   * `service-meeting-attender` K8s pod. The attender captures meeting
   * audio on the user's behalf (§2B) and uploads PCM chunks here. Chunks
   * stay inline (base64, ~64 KiB PCM each — see docs/inter-service-contracts.md §2.3).
   * </pre>
   */
  public static final class ServerMeetingRecordingBridgeServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerMeetingRecordingBridgeServiceFutureStub> {
    private ServerMeetingRecordingBridgeServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingRecordingBridgeServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingRecordingBridgeServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Creates the MeetingDocument up-front; subsequent chunk uploads
     * attach to the returned meeting_id.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.StartRecordingResponse> startRecording(
        com.jervis.contracts.server.StartRecordingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStartRecordingMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Appends a base64 PCM frame. Returns the running chunk count.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.UploadChunkResponse> uploadChunk(
        com.jervis.contracts.server.UploadChunkRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUploadChunkMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Fixes the WAV header and transitions the meeting to UPLOADED.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.FinalizeRecordingResponse> finalizeRecording(
        com.jervis.contracts.server.FinalizeRecordingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getFinalizeRecordingMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_START_RECORDING = 0;
  private static final int METHODID_UPLOAD_CHUNK = 1;
  private static final int METHODID_FINALIZE_RECORDING = 2;

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
        case METHODID_START_RECORDING:
          serviceImpl.startRecording((com.jervis.contracts.server.StartRecordingRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.StartRecordingResponse>) responseObserver);
          break;
        case METHODID_UPLOAD_CHUNK:
          serviceImpl.uploadChunk((com.jervis.contracts.server.UploadChunkRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.UploadChunkResponse>) responseObserver);
          break;
        case METHODID_FINALIZE_RECORDING:
          serviceImpl.finalizeRecording((com.jervis.contracts.server.FinalizeRecordingRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.FinalizeRecordingResponse>) responseObserver);
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
          getStartRecordingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.StartRecordingRequest,
              com.jervis.contracts.server.StartRecordingResponse>(
                service, METHODID_START_RECORDING)))
        .addMethod(
          getUploadChunkMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.UploadChunkRequest,
              com.jervis.contracts.server.UploadChunkResponse>(
                service, METHODID_UPLOAD_CHUNK)))
        .addMethod(
          getFinalizeRecordingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.FinalizeRecordingRequest,
              com.jervis.contracts.server.FinalizeRecordingResponse>(
                service, METHODID_FINALIZE_RECORDING)))
        .build();
  }

  private static abstract class ServerMeetingRecordingBridgeServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerMeetingRecordingBridgeServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerMeetingRecordingBridgeProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerMeetingRecordingBridgeService");
    }
  }

  private static final class ServerMeetingRecordingBridgeServiceFileDescriptorSupplier
      extends ServerMeetingRecordingBridgeServiceBaseDescriptorSupplier {
    ServerMeetingRecordingBridgeServiceFileDescriptorSupplier() {}
  }

  private static final class ServerMeetingRecordingBridgeServiceMethodDescriptorSupplier
      extends ServerMeetingRecordingBridgeServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerMeetingRecordingBridgeServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerMeetingRecordingBridgeServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerMeetingRecordingBridgeServiceFileDescriptorSupplier())
              .addMethod(getStartRecordingMethod())
              .addMethod(getUploadChunkMethod())
              .addMethod(getFinalizeRecordingMethod())
              .build();
        }
      }
    }
    return result;
  }
}
