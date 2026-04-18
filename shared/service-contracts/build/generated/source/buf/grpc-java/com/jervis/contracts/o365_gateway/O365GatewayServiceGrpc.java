package com.jervis.contracts.o365_gateway;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * O365GatewayService — thin pod-to-pod proxy on top of the jervis-o365-gateway
 * service. The orchestrator's Teams / Mail / Calendar / Drive tools dial
 * this one RPC; the gateway routes based on `path` and forwards the
 * (token-managed) Microsoft Graph call. Response bodies are returned as
 * JSON text (or binary via RequestBytes) exactly as the gateway receives
 * them from Graph, so tool code can keep the existing parsing.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.0)",
    comments = "Source: jervis/o365_gateway/gateway.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class O365GatewayServiceGrpc {

  private O365GatewayServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.o365_gateway.O365GatewayService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.O365Request,
      com.jervis.contracts.o365_gateway.O365Response> getRequestMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Request",
      requestType = com.jervis.contracts.o365_gateway.O365Request.class,
      responseType = com.jervis.contracts.o365_gateway.O365Response.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.O365Request,
      com.jervis.contracts.o365_gateway.O365Response> getRequestMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.O365Request, com.jervis.contracts.o365_gateway.O365Response> getRequestMethod;
    if ((getRequestMethod = O365GatewayServiceGrpc.getRequestMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getRequestMethod = O365GatewayServiceGrpc.getRequestMethod) == null) {
          O365GatewayServiceGrpc.getRequestMethod = getRequestMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.O365Request, com.jervis.contracts.o365_gateway.O365Response>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Request"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.O365Request.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.O365Response.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("Request"))
              .build();
        }
      }
    }
    return getRequestMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.O365Request,
      com.jervis.contracts.o365_gateway.O365BytesResponse> getRequestBytesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RequestBytes",
      requestType = com.jervis.contracts.o365_gateway.O365Request.class,
      responseType = com.jervis.contracts.o365_gateway.O365BytesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.O365Request,
      com.jervis.contracts.o365_gateway.O365BytesResponse> getRequestBytesMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.O365Request, com.jervis.contracts.o365_gateway.O365BytesResponse> getRequestBytesMethod;
    if ((getRequestBytesMethod = O365GatewayServiceGrpc.getRequestBytesMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getRequestBytesMethod = O365GatewayServiceGrpc.getRequestBytesMethod) == null) {
          O365GatewayServiceGrpc.getRequestBytesMethod = getRequestBytesMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.O365Request, com.jervis.contracts.o365_gateway.O365BytesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RequestBytes"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.O365Request.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.O365BytesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("RequestBytes"))
              .build();
        }
      }
    }
    return getRequestBytesMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static O365GatewayServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<O365GatewayServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<O365GatewayServiceStub>() {
        @java.lang.Override
        public O365GatewayServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new O365GatewayServiceStub(channel, callOptions);
        }
      };
    return O365GatewayServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static O365GatewayServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<O365GatewayServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<O365GatewayServiceBlockingStub>() {
        @java.lang.Override
        public O365GatewayServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new O365GatewayServiceBlockingStub(channel, callOptions);
        }
      };
    return O365GatewayServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static O365GatewayServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<O365GatewayServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<O365GatewayServiceFutureStub>() {
        @java.lang.Override
        public O365GatewayServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new O365GatewayServiceFutureStub(channel, callOptions);
        }
      };
    return O365GatewayServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * O365GatewayService — thin pod-to-pod proxy on top of the jervis-o365-gateway
   * service. The orchestrator's Teams / Mail / Calendar / Drive tools dial
   * this one RPC; the gateway routes based on `path` and forwards the
   * (token-managed) Microsoft Graph call. Response bodies are returned as
   * JSON text (or binary via RequestBytes) exactly as the gateway receives
   * them from Graph, so tool code can keep the existing parsing.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void request(com.jervis.contracts.o365_gateway.O365Request request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.O365Response> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRequestMethod(), responseObserver);
    }

    /**
     */
    default void requestBytes(com.jervis.contracts.o365_gateway.O365Request request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.O365BytesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRequestBytesMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service O365GatewayService.
   * <pre>
   * O365GatewayService — thin pod-to-pod proxy on top of the jervis-o365-gateway
   * service. The orchestrator's Teams / Mail / Calendar / Drive tools dial
   * this one RPC; the gateway routes based on `path` and forwards the
   * (token-managed) Microsoft Graph call. Response bodies are returned as
   * JSON text (or binary via RequestBytes) exactly as the gateway receives
   * them from Graph, so tool code can keep the existing parsing.
   * </pre>
   */
  public static abstract class O365GatewayServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return O365GatewayServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service O365GatewayService.
   * <pre>
   * O365GatewayService — thin pod-to-pod proxy on top of the jervis-o365-gateway
   * service. The orchestrator's Teams / Mail / Calendar / Drive tools dial
   * this one RPC; the gateway routes based on `path` and forwards the
   * (token-managed) Microsoft Graph call. Response bodies are returned as
   * JSON text (or binary via RequestBytes) exactly as the gateway receives
   * them from Graph, so tool code can keep the existing parsing.
   * </pre>
   */
  public static final class O365GatewayServiceStub
      extends io.grpc.stub.AbstractAsyncStub<O365GatewayServiceStub> {
    private O365GatewayServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected O365GatewayServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new O365GatewayServiceStub(channel, callOptions);
    }

    /**
     */
    public void request(com.jervis.contracts.o365_gateway.O365Request request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.O365Response> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRequestMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void requestBytes(com.jervis.contracts.o365_gateway.O365Request request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.O365BytesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRequestBytesMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service O365GatewayService.
   * <pre>
   * O365GatewayService — thin pod-to-pod proxy on top of the jervis-o365-gateway
   * service. The orchestrator's Teams / Mail / Calendar / Drive tools dial
   * this one RPC; the gateway routes based on `path` and forwards the
   * (token-managed) Microsoft Graph call. Response bodies are returned as
   * JSON text (or binary via RequestBytes) exactly as the gateway receives
   * them from Graph, so tool code can keep the existing parsing.
   * </pre>
   */
  public static final class O365GatewayServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<O365GatewayServiceBlockingStub> {
    private O365GatewayServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected O365GatewayServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new O365GatewayServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.O365Response request(com.jervis.contracts.o365_gateway.O365Request request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRequestMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.O365BytesResponse requestBytes(com.jervis.contracts.o365_gateway.O365Request request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRequestBytesMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service O365GatewayService.
   * <pre>
   * O365GatewayService — thin pod-to-pod proxy on top of the jervis-o365-gateway
   * service. The orchestrator's Teams / Mail / Calendar / Drive tools dial
   * this one RPC; the gateway routes based on `path` and forwards the
   * (token-managed) Microsoft Graph call. Response bodies are returned as
   * JSON text (or binary via RequestBytes) exactly as the gateway receives
   * them from Graph, so tool code can keep the existing parsing.
   * </pre>
   */
  public static final class O365GatewayServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<O365GatewayServiceFutureStub> {
    private O365GatewayServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected O365GatewayServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new O365GatewayServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.O365Response> request(
        com.jervis.contracts.o365_gateway.O365Request request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRequestMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.O365BytesResponse> requestBytes(
        com.jervis.contracts.o365_gateway.O365Request request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRequestBytesMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_REQUEST = 0;
  private static final int METHODID_REQUEST_BYTES = 1;

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
        case METHODID_REQUEST:
          serviceImpl.request((com.jervis.contracts.o365_gateway.O365Request) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.O365Response>) responseObserver);
          break;
        case METHODID_REQUEST_BYTES:
          serviceImpl.requestBytes((com.jervis.contracts.o365_gateway.O365Request) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.O365BytesResponse>) responseObserver);
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
          getRequestMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.O365Request,
              com.jervis.contracts.o365_gateway.O365Response>(
                service, METHODID_REQUEST)))
        .addMethod(
          getRequestBytesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.O365Request,
              com.jervis.contracts.o365_gateway.O365BytesResponse>(
                service, METHODID_REQUEST_BYTES)))
        .build();
  }

  private static abstract class O365GatewayServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    O365GatewayServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.o365_gateway.O365GatewayProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("O365GatewayService");
    }
  }

  private static final class O365GatewayServiceFileDescriptorSupplier
      extends O365GatewayServiceBaseDescriptorSupplier {
    O365GatewayServiceFileDescriptorSupplier() {}
  }

  private static final class O365GatewayServiceMethodDescriptorSupplier
      extends O365GatewayServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    O365GatewayServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (O365GatewayServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new O365GatewayServiceFileDescriptorSupplier())
              .addMethod(getRequestMethod())
              .addMethod(getRequestBytesMethod())
              .build();
        }
      }
    }
    return result;
  }
}
