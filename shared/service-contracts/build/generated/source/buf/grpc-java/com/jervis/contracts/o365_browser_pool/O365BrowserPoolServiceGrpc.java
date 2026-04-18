package com.jervis.contracts.o365_browser_pool;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * O365BrowserPoolService — gRPC passthrough on top of the per-client
 * browser pod. Replaces the former REST surface consumed by the Kotlin
 * server (/health, /session/{cid}, /session/{cid}/init,
 * /session/{cid}/mfa, /session/{cid}/rediscover, /scrape/{cid}/discover,
 * /vnc-token/{cid}, /instruction/{cid}). The VNC proxy routes
 * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.0)",
    comments = "Source: jervis/o365_browser_pool/pool.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class O365BrowserPoolServiceGrpc {

  private O365BrowserPoolServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.o365_browser_pool.O365BrowserPoolService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.HealthRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getHealthMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Health",
      requestType = com.jervis.contracts.o365_browser_pool.HealthRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.HealthRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getHealthMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.HealthRequest, com.jervis.contracts.o365_browser_pool.RawResponse> getHealthMethod;
    if ((getHealthMethod = O365BrowserPoolServiceGrpc.getHealthMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getHealthMethod = O365BrowserPoolServiceGrpc.getHealthMethod) == null) {
          O365BrowserPoolServiceGrpc.getHealthMethod = getHealthMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.HealthRequest, com.jervis.contracts.o365_browser_pool.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Health"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.HealthRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("Health"))
              .build();
        }
      }
    }
    return getHealthMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSessionStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SessionStatus",
      requestType = com.jervis.contracts.o365_browser_pool.PodRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSessionStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse> getSessionStatusMethod;
    if ((getSessionStatusMethod = O365BrowserPoolServiceGrpc.getSessionStatusMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getSessionStatusMethod = O365BrowserPoolServiceGrpc.getSessionStatusMethod) == null) {
          O365BrowserPoolServiceGrpc.getSessionStatusMethod = getSessionStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SessionStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.PodRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("SessionStatus"))
              .build();
        }
      }
    }
    return getSessionStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSessionInitMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SessionInit",
      requestType = com.jervis.contracts.o365_browser_pool.PodRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSessionInitMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse> getSessionInitMethod;
    if ((getSessionInitMethod = O365BrowserPoolServiceGrpc.getSessionInitMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getSessionInitMethod = O365BrowserPoolServiceGrpc.getSessionInitMethod) == null) {
          O365BrowserPoolServiceGrpc.getSessionInitMethod = getSessionInitMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SessionInit"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.PodRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("SessionInit"))
              .build();
        }
      }
    }
    return getSessionInitMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSessionMfaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SessionMfa",
      requestType = com.jervis.contracts.o365_browser_pool.PodRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSessionMfaMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse> getSessionMfaMethod;
    if ((getSessionMfaMethod = O365BrowserPoolServiceGrpc.getSessionMfaMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getSessionMfaMethod = O365BrowserPoolServiceGrpc.getSessionMfaMethod) == null) {
          O365BrowserPoolServiceGrpc.getSessionMfaMethod = getSessionMfaMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SessionMfa"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.PodRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("SessionMfa"))
              .build();
        }
      }
    }
    return getSessionMfaMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSessionRediscoverMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SessionRediscover",
      requestType = com.jervis.contracts.o365_browser_pool.PodRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSessionRediscoverMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse> getSessionRediscoverMethod;
    if ((getSessionRediscoverMethod = O365BrowserPoolServiceGrpc.getSessionRediscoverMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getSessionRediscoverMethod = O365BrowserPoolServiceGrpc.getSessionRediscoverMethod) == null) {
          O365BrowserPoolServiceGrpc.getSessionRediscoverMethod = getSessionRediscoverMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SessionRediscover"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.PodRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("SessionRediscover"))
              .build();
        }
      }
    }
    return getSessionRediscoverMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getScrapeDiscoverMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ScrapeDiscover",
      requestType = com.jervis.contracts.o365_browser_pool.PodRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getScrapeDiscoverMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse> getScrapeDiscoverMethod;
    if ((getScrapeDiscoverMethod = O365BrowserPoolServiceGrpc.getScrapeDiscoverMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getScrapeDiscoverMethod = O365BrowserPoolServiceGrpc.getScrapeDiscoverMethod) == null) {
          O365BrowserPoolServiceGrpc.getScrapeDiscoverMethod = getScrapeDiscoverMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ScrapeDiscover"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.PodRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("ScrapeDiscover"))
              .build();
        }
      }
    }
    return getScrapeDiscoverMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getVncTokenMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "VncToken",
      requestType = com.jervis.contracts.o365_browser_pool.PodRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getVncTokenMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse> getVncTokenMethod;
    if ((getVncTokenMethod = O365BrowserPoolServiceGrpc.getVncTokenMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getVncTokenMethod = O365BrowserPoolServiceGrpc.getVncTokenMethod) == null) {
          O365BrowserPoolServiceGrpc.getVncTokenMethod = getVncTokenMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "VncToken"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.PodRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("VncToken"))
              .build();
        }
      }
    }
    return getVncTokenMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSendInstructionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendInstruction",
      requestType = com.jervis.contracts.o365_browser_pool.PodRequest.class,
      responseType = com.jervis.contracts.o365_browser_pool.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest,
      com.jervis.contracts.o365_browser_pool.RawResponse> getSendInstructionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse> getSendInstructionMethod;
    if ((getSendInstructionMethod = O365BrowserPoolServiceGrpc.getSendInstructionMethod) == null) {
      synchronized (O365BrowserPoolServiceGrpc.class) {
        if ((getSendInstructionMethod = O365BrowserPoolServiceGrpc.getSendInstructionMethod) == null) {
          O365BrowserPoolServiceGrpc.getSendInstructionMethod = getSendInstructionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_browser_pool.PodRequest, com.jervis.contracts.o365_browser_pool.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendInstruction"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.PodRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_browser_pool.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365BrowserPoolServiceMethodDescriptorSupplier("SendInstruction"))
              .build();
        }
      }
    }
    return getSendInstructionMethod;
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
   * O365BrowserPoolService — gRPC passthrough on top of the per-client
   * browser pod. Replaces the former REST surface consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /session/{cid}/rediscover, /scrape/{cid}/discover,
   * /vnc-token/{cid}, /instruction/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void health(com.jervis.contracts.o365_browser_pool.HealthRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHealthMethod(), responseObserver);
    }

    /**
     */
    default void sessionStatus(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSessionStatusMethod(), responseObserver);
    }

    /**
     */
    default void sessionInit(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSessionInitMethod(), responseObserver);
    }

    /**
     */
    default void sessionMfa(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSessionMfaMethod(), responseObserver);
    }

    /**
     */
    default void sessionRediscover(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSessionRediscoverMethod(), responseObserver);
    }

    /**
     */
    default void scrapeDiscover(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getScrapeDiscoverMethod(), responseObserver);
    }

    /**
     */
    default void vncToken(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getVncTokenMethod(), responseObserver);
    }

    /**
     */
    default void sendInstruction(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendInstructionMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service O365BrowserPoolService.
   * <pre>
   * O365BrowserPoolService — gRPC passthrough on top of the per-client
   * browser pod. Replaces the former REST surface consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /session/{cid}/rediscover, /scrape/{cid}/discover,
   * /vnc-token/{cid}, /instruction/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
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
   * O365BrowserPoolService — gRPC passthrough on top of the per-client
   * browser pod. Replaces the former REST surface consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /session/{cid}/rediscover, /scrape/{cid}/discover,
   * /vnc-token/{cid}, /instruction/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
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
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sessionStatus(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSessionStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sessionInit(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSessionInitMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sessionMfa(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSessionMfaMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sessionRediscover(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSessionRediscoverMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void scrapeDiscover(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getScrapeDiscoverMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void vncToken(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getVncTokenMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sendInstruction(com.jervis.contracts.o365_browser_pool.PodRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendInstructionMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service O365BrowserPoolService.
   * <pre>
   * O365BrowserPoolService — gRPC passthrough on top of the per-client
   * browser pod. Replaces the former REST surface consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /session/{cid}/rediscover, /scrape/{cid}/discover,
   * /vnc-token/{cid}, /instruction/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
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
    public com.jervis.contracts.o365_browser_pool.RawResponse health(com.jervis.contracts.o365_browser_pool.HealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.RawResponse sessionStatus(com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSessionStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.RawResponse sessionInit(com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSessionInitMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.RawResponse sessionMfa(com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSessionMfaMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.RawResponse sessionRediscover(com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSessionRediscoverMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.RawResponse scrapeDiscover(com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getScrapeDiscoverMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.RawResponse vncToken(com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getVncTokenMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_browser_pool.RawResponse sendInstruction(com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendInstructionMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service O365BrowserPoolService.
   * <pre>
   * O365BrowserPoolService — gRPC passthrough on top of the per-client
   * browser pod. Replaces the former REST surface consumed by the Kotlin
   * server (/health, /session/{cid}, /session/{cid}/init,
   * /session/{cid}/mfa, /session/{cid}/rediscover, /scrape/{cid}/discover,
   * /vnc-token/{cid}, /instruction/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
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
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.RawResponse> health(
        com.jervis.contracts.o365_browser_pool.HealthRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.RawResponse> sessionStatus(
        com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSessionStatusMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.RawResponse> sessionInit(
        com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSessionInitMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.RawResponse> sessionMfa(
        com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSessionMfaMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.RawResponse> sessionRediscover(
        com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSessionRediscoverMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.RawResponse> scrapeDiscover(
        com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getScrapeDiscoverMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.RawResponse> vncToken(
        com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getVncTokenMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_browser_pool.RawResponse> sendInstruction(
        com.jervis.contracts.o365_browser_pool.PodRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendInstructionMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_HEALTH = 0;
  private static final int METHODID_SESSION_STATUS = 1;
  private static final int METHODID_SESSION_INIT = 2;
  private static final int METHODID_SESSION_MFA = 3;
  private static final int METHODID_SESSION_REDISCOVER = 4;
  private static final int METHODID_SCRAPE_DISCOVER = 5;
  private static final int METHODID_VNC_TOKEN = 6;
  private static final int METHODID_SEND_INSTRUCTION = 7;

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
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse>) responseObserver);
          break;
        case METHODID_SESSION_STATUS:
          serviceImpl.sessionStatus((com.jervis.contracts.o365_browser_pool.PodRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse>) responseObserver);
          break;
        case METHODID_SESSION_INIT:
          serviceImpl.sessionInit((com.jervis.contracts.o365_browser_pool.PodRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse>) responseObserver);
          break;
        case METHODID_SESSION_MFA:
          serviceImpl.sessionMfa((com.jervis.contracts.o365_browser_pool.PodRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse>) responseObserver);
          break;
        case METHODID_SESSION_REDISCOVER:
          serviceImpl.sessionRediscover((com.jervis.contracts.o365_browser_pool.PodRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse>) responseObserver);
          break;
        case METHODID_SCRAPE_DISCOVER:
          serviceImpl.scrapeDiscover((com.jervis.contracts.o365_browser_pool.PodRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse>) responseObserver);
          break;
        case METHODID_VNC_TOKEN:
          serviceImpl.vncToken((com.jervis.contracts.o365_browser_pool.PodRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse>) responseObserver);
          break;
        case METHODID_SEND_INSTRUCTION:
          serviceImpl.sendInstruction((com.jervis.contracts.o365_browser_pool.PodRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_browser_pool.RawResponse>) responseObserver);
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
          getHealthMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.HealthRequest,
              com.jervis.contracts.o365_browser_pool.RawResponse>(
                service, METHODID_HEALTH)))
        .addMethod(
          getSessionStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.PodRequest,
              com.jervis.contracts.o365_browser_pool.RawResponse>(
                service, METHODID_SESSION_STATUS)))
        .addMethod(
          getSessionInitMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.PodRequest,
              com.jervis.contracts.o365_browser_pool.RawResponse>(
                service, METHODID_SESSION_INIT)))
        .addMethod(
          getSessionMfaMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.PodRequest,
              com.jervis.contracts.o365_browser_pool.RawResponse>(
                service, METHODID_SESSION_MFA)))
        .addMethod(
          getSessionRediscoverMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.PodRequest,
              com.jervis.contracts.o365_browser_pool.RawResponse>(
                service, METHODID_SESSION_REDISCOVER)))
        .addMethod(
          getScrapeDiscoverMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.PodRequest,
              com.jervis.contracts.o365_browser_pool.RawResponse>(
                service, METHODID_SCRAPE_DISCOVER)))
        .addMethod(
          getVncTokenMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.PodRequest,
              com.jervis.contracts.o365_browser_pool.RawResponse>(
                service, METHODID_VNC_TOKEN)))
        .addMethod(
          getSendInstructionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_browser_pool.PodRequest,
              com.jervis.contracts.o365_browser_pool.RawResponse>(
                service, METHODID_SEND_INSTRUCTION)))
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
              .addMethod(getSessionStatusMethod())
              .addMethod(getSessionInitMethod())
              .addMethod(getSessionMfaMethod())
              .addMethod(getSessionRediscoverMethod())
              .addMethod(getScrapeDiscoverMethod())
              .addMethod(getVncTokenMethod())
              .addMethod(getSendInstructionMethod())
              .build();
        }
      }
    }
    return result;
  }
}
