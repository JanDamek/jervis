package com.jervis.contracts.meeting_attender;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * MeetingAttenderService — dispatch + lifecycle for the K8s
 * `jervis-meeting-attender` pod. Replaces the POST /attend + POST /stop
 * REST surface; the Kotlin MeetingRecordingDispatcher dials Attend for
 * approved calendar-driven meetings and Stop when the user cancels.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.0)",
    comments = "Source: jervis/meeting_attender/attender.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class MeetingAttenderServiceGrpc {

  private MeetingAttenderServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.meeting_attender.MeetingAttenderService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.meeting_attender.AttendRequest,
      com.jervis.contracts.meeting_attender.AttendResponse> getAttendMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Attend",
      requestType = com.jervis.contracts.meeting_attender.AttendRequest.class,
      responseType = com.jervis.contracts.meeting_attender.AttendResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.meeting_attender.AttendRequest,
      com.jervis.contracts.meeting_attender.AttendResponse> getAttendMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.meeting_attender.AttendRequest, com.jervis.contracts.meeting_attender.AttendResponse> getAttendMethod;
    if ((getAttendMethod = MeetingAttenderServiceGrpc.getAttendMethod) == null) {
      synchronized (MeetingAttenderServiceGrpc.class) {
        if ((getAttendMethod = MeetingAttenderServiceGrpc.getAttendMethod) == null) {
          MeetingAttenderServiceGrpc.getAttendMethod = getAttendMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.meeting_attender.AttendRequest, com.jervis.contracts.meeting_attender.AttendResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Attend"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.meeting_attender.AttendRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.meeting_attender.AttendResponse.getDefaultInstance()))
              .setSchemaDescriptor(new MeetingAttenderServiceMethodDescriptorSupplier("Attend"))
              .build();
        }
      }
    }
    return getAttendMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.meeting_attender.StopRequest,
      com.jervis.contracts.meeting_attender.StopResponse> getStopMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Stop",
      requestType = com.jervis.contracts.meeting_attender.StopRequest.class,
      responseType = com.jervis.contracts.meeting_attender.StopResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.meeting_attender.StopRequest,
      com.jervis.contracts.meeting_attender.StopResponse> getStopMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.meeting_attender.StopRequest, com.jervis.contracts.meeting_attender.StopResponse> getStopMethod;
    if ((getStopMethod = MeetingAttenderServiceGrpc.getStopMethod) == null) {
      synchronized (MeetingAttenderServiceGrpc.class) {
        if ((getStopMethod = MeetingAttenderServiceGrpc.getStopMethod) == null) {
          MeetingAttenderServiceGrpc.getStopMethod = getStopMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.meeting_attender.StopRequest, com.jervis.contracts.meeting_attender.StopResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Stop"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.meeting_attender.StopRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.meeting_attender.StopResponse.getDefaultInstance()))
              .setSchemaDescriptor(new MeetingAttenderServiceMethodDescriptorSupplier("Stop"))
              .build();
        }
      }
    }
    return getStopMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static MeetingAttenderServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MeetingAttenderServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MeetingAttenderServiceStub>() {
        @java.lang.Override
        public MeetingAttenderServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MeetingAttenderServiceStub(channel, callOptions);
        }
      };
    return MeetingAttenderServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static MeetingAttenderServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MeetingAttenderServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MeetingAttenderServiceBlockingStub>() {
        @java.lang.Override
        public MeetingAttenderServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MeetingAttenderServiceBlockingStub(channel, callOptions);
        }
      };
    return MeetingAttenderServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static MeetingAttenderServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MeetingAttenderServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MeetingAttenderServiceFutureStub>() {
        @java.lang.Override
        public MeetingAttenderServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MeetingAttenderServiceFutureStub(channel, callOptions);
        }
      };
    return MeetingAttenderServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * MeetingAttenderService — dispatch + lifecycle for the K8s
   * `jervis-meeting-attender` pod. Replaces the POST /attend + POST /stop
   * REST surface; the Kotlin MeetingRecordingDispatcher dials Attend for
   * approved calendar-driven meetings and Stop when the user cancels.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void attend(com.jervis.contracts.meeting_attender.AttendRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.meeting_attender.AttendResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAttendMethod(), responseObserver);
    }

    /**
     */
    default void stop(com.jervis.contracts.meeting_attender.StopRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.meeting_attender.StopResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStopMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service MeetingAttenderService.
   * <pre>
   * MeetingAttenderService — dispatch + lifecycle for the K8s
   * `jervis-meeting-attender` pod. Replaces the POST /attend + POST /stop
   * REST surface; the Kotlin MeetingRecordingDispatcher dials Attend for
   * approved calendar-driven meetings and Stop when the user cancels.
   * </pre>
   */
  public static abstract class MeetingAttenderServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return MeetingAttenderServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service MeetingAttenderService.
   * <pre>
   * MeetingAttenderService — dispatch + lifecycle for the K8s
   * `jervis-meeting-attender` pod. Replaces the POST /attend + POST /stop
   * REST surface; the Kotlin MeetingRecordingDispatcher dials Attend for
   * approved calendar-driven meetings and Stop when the user cancels.
   * </pre>
   */
  public static final class MeetingAttenderServiceStub
      extends io.grpc.stub.AbstractAsyncStub<MeetingAttenderServiceStub> {
    private MeetingAttenderServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MeetingAttenderServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MeetingAttenderServiceStub(channel, callOptions);
    }

    /**
     */
    public void attend(com.jervis.contracts.meeting_attender.AttendRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.meeting_attender.AttendResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAttendMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void stop(com.jervis.contracts.meeting_attender.StopRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.meeting_attender.StopResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service MeetingAttenderService.
   * <pre>
   * MeetingAttenderService — dispatch + lifecycle for the K8s
   * `jervis-meeting-attender` pod. Replaces the POST /attend + POST /stop
   * REST surface; the Kotlin MeetingRecordingDispatcher dials Attend for
   * approved calendar-driven meetings and Stop when the user cancels.
   * </pre>
   */
  public static final class MeetingAttenderServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<MeetingAttenderServiceBlockingStub> {
    private MeetingAttenderServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MeetingAttenderServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MeetingAttenderServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.meeting_attender.AttendResponse attend(com.jervis.contracts.meeting_attender.AttendRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAttendMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.meeting_attender.StopResponse stop(com.jervis.contracts.meeting_attender.StopRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStopMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service MeetingAttenderService.
   * <pre>
   * MeetingAttenderService — dispatch + lifecycle for the K8s
   * `jervis-meeting-attender` pod. Replaces the POST /attend + POST /stop
   * REST surface; the Kotlin MeetingRecordingDispatcher dials Attend for
   * approved calendar-driven meetings and Stop when the user cancels.
   * </pre>
   */
  public static final class MeetingAttenderServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<MeetingAttenderServiceFutureStub> {
    private MeetingAttenderServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MeetingAttenderServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MeetingAttenderServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.meeting_attender.AttendResponse> attend(
        com.jervis.contracts.meeting_attender.AttendRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAttendMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.meeting_attender.StopResponse> stop(
        com.jervis.contracts.meeting_attender.StopRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_ATTEND = 0;
  private static final int METHODID_STOP = 1;

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
        case METHODID_ATTEND:
          serviceImpl.attend((com.jervis.contracts.meeting_attender.AttendRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.meeting_attender.AttendResponse>) responseObserver);
          break;
        case METHODID_STOP:
          serviceImpl.stop((com.jervis.contracts.meeting_attender.StopRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.meeting_attender.StopResponse>) responseObserver);
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
          getAttendMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.meeting_attender.AttendRequest,
              com.jervis.contracts.meeting_attender.AttendResponse>(
                service, METHODID_ATTEND)))
        .addMethod(
          getStopMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.meeting_attender.StopRequest,
              com.jervis.contracts.meeting_attender.StopResponse>(
                service, METHODID_STOP)))
        .build();
  }

  private static abstract class MeetingAttenderServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    MeetingAttenderServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.meeting_attender.MeetingAttenderProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("MeetingAttenderService");
    }
  }

  private static final class MeetingAttenderServiceFileDescriptorSupplier
      extends MeetingAttenderServiceBaseDescriptorSupplier {
    MeetingAttenderServiceFileDescriptorSupplier() {}
  }

  private static final class MeetingAttenderServiceMethodDescriptorSupplier
      extends MeetingAttenderServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    MeetingAttenderServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (MeetingAttenderServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new MeetingAttenderServiceFileDescriptorSupplier())
              .addMethod(getAttendMethod())
              .addMethod(getStopMethod())
              .build();
        }
      }
    }
    return result;
  }
}
