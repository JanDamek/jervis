package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerMeetingHelperCallbacksService — reverse path the orchestrator's
 * meeting-helper pipeline uses to publish helper messages (translations,
 * suggestions, predicted questions, visual insights) back to the Kotlin
 * server, which fans them out to the meeting's WebSocket/RPC subscribers.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.0)",
    comments = "Source: jervis/server/meeting_helper_callbacks.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerMeetingHelperCallbacksServiceGrpc {

  private ServerMeetingHelperCallbacksServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerMeetingHelperCallbacksService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.HelperPushRequest,
      com.jervis.contracts.server.HelperPushAck> getPushMessageMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PushMessage",
      requestType = com.jervis.contracts.server.HelperPushRequest.class,
      responseType = com.jervis.contracts.server.HelperPushAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.HelperPushRequest,
      com.jervis.contracts.server.HelperPushAck> getPushMessageMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.HelperPushRequest, com.jervis.contracts.server.HelperPushAck> getPushMessageMethod;
    if ((getPushMessageMethod = ServerMeetingHelperCallbacksServiceGrpc.getPushMessageMethod) == null) {
      synchronized (ServerMeetingHelperCallbacksServiceGrpc.class) {
        if ((getPushMessageMethod = ServerMeetingHelperCallbacksServiceGrpc.getPushMessageMethod) == null) {
          ServerMeetingHelperCallbacksServiceGrpc.getPushMessageMethod = getPushMessageMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.HelperPushRequest, com.jervis.contracts.server.HelperPushAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PushMessage"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.HelperPushRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.HelperPushAck.getDefaultInstance()))
              .setSchemaDescriptor(new ServerMeetingHelperCallbacksServiceMethodDescriptorSupplier("PushMessage"))
              .build();
        }
      }
    }
    return getPushMessageMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerMeetingHelperCallbacksServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingHelperCallbacksServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingHelperCallbacksServiceStub>() {
        @java.lang.Override
        public ServerMeetingHelperCallbacksServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingHelperCallbacksServiceStub(channel, callOptions);
        }
      };
    return ServerMeetingHelperCallbacksServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerMeetingHelperCallbacksServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingHelperCallbacksServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingHelperCallbacksServiceBlockingStub>() {
        @java.lang.Override
        public ServerMeetingHelperCallbacksServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingHelperCallbacksServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerMeetingHelperCallbacksServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerMeetingHelperCallbacksServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerMeetingHelperCallbacksServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerMeetingHelperCallbacksServiceFutureStub>() {
        @java.lang.Override
        public ServerMeetingHelperCallbacksServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerMeetingHelperCallbacksServiceFutureStub(channel, callOptions);
        }
      };
    return ServerMeetingHelperCallbacksServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerMeetingHelperCallbacksService — reverse path the orchestrator's
   * meeting-helper pipeline uses to publish helper messages (translations,
   * suggestions, predicted questions, visual insights) back to the Kotlin
   * server, which fans them out to the meeting's WebSocket/RPC subscribers.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void pushMessage(com.jervis.contracts.server.HelperPushRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.HelperPushAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPushMessageMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerMeetingHelperCallbacksService.
   * <pre>
   * ServerMeetingHelperCallbacksService — reverse path the orchestrator's
   * meeting-helper pipeline uses to publish helper messages (translations,
   * suggestions, predicted questions, visual insights) back to the Kotlin
   * server, which fans them out to the meeting's WebSocket/RPC subscribers.
   * </pre>
   */
  public static abstract class ServerMeetingHelperCallbacksServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerMeetingHelperCallbacksServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerMeetingHelperCallbacksService.
   * <pre>
   * ServerMeetingHelperCallbacksService — reverse path the orchestrator's
   * meeting-helper pipeline uses to publish helper messages (translations,
   * suggestions, predicted questions, visual insights) back to the Kotlin
   * server, which fans them out to the meeting's WebSocket/RPC subscribers.
   * </pre>
   */
  public static final class ServerMeetingHelperCallbacksServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerMeetingHelperCallbacksServiceStub> {
    private ServerMeetingHelperCallbacksServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingHelperCallbacksServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingHelperCallbacksServiceStub(channel, callOptions);
    }

    /**
     */
    public void pushMessage(com.jervis.contracts.server.HelperPushRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.HelperPushAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPushMessageMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerMeetingHelperCallbacksService.
   * <pre>
   * ServerMeetingHelperCallbacksService — reverse path the orchestrator's
   * meeting-helper pipeline uses to publish helper messages (translations,
   * suggestions, predicted questions, visual insights) back to the Kotlin
   * server, which fans them out to the meeting's WebSocket/RPC subscribers.
   * </pre>
   */
  public static final class ServerMeetingHelperCallbacksServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerMeetingHelperCallbacksServiceBlockingStub> {
    private ServerMeetingHelperCallbacksServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingHelperCallbacksServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingHelperCallbacksServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.HelperPushAck pushMessage(com.jervis.contracts.server.HelperPushRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPushMessageMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerMeetingHelperCallbacksService.
   * <pre>
   * ServerMeetingHelperCallbacksService — reverse path the orchestrator's
   * meeting-helper pipeline uses to publish helper messages (translations,
   * suggestions, predicted questions, visual insights) back to the Kotlin
   * server, which fans them out to the meeting's WebSocket/RPC subscribers.
   * </pre>
   */
  public static final class ServerMeetingHelperCallbacksServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerMeetingHelperCallbacksServiceFutureStub> {
    private ServerMeetingHelperCallbacksServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerMeetingHelperCallbacksServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerMeetingHelperCallbacksServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.HelperPushAck> pushMessage(
        com.jervis.contracts.server.HelperPushRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPushMessageMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_PUSH_MESSAGE = 0;

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
        case METHODID_PUSH_MESSAGE:
          serviceImpl.pushMessage((com.jervis.contracts.server.HelperPushRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.HelperPushAck>) responseObserver);
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
          getPushMessageMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.HelperPushRequest,
              com.jervis.contracts.server.HelperPushAck>(
                service, METHODID_PUSH_MESSAGE)))
        .build();
  }

  private static abstract class ServerMeetingHelperCallbacksServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerMeetingHelperCallbacksServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerMeetingHelperCallbacksProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerMeetingHelperCallbacksService");
    }
  }

  private static final class ServerMeetingHelperCallbacksServiceFileDescriptorSupplier
      extends ServerMeetingHelperCallbacksServiceBaseDescriptorSupplier {
    ServerMeetingHelperCallbacksServiceFileDescriptorSupplier() {}
  }

  private static final class ServerMeetingHelperCallbacksServiceMethodDescriptorSupplier
      extends ServerMeetingHelperCallbacksServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerMeetingHelperCallbacksServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerMeetingHelperCallbacksServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerMeetingHelperCallbacksServiceFileDescriptorSupplier())
              .addMethod(getPushMessageMethod())
              .build();
        }
      }
    }
    return result;
  }
}
