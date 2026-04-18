package com.jervis.contracts.whatsapp_browser;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * WhatsAppBrowserService — gRPC wrapper on top of the `jervis-whatsapp-browser`
 * pod. Kotlin server's ConnectionRpcImpl + WhatsAppPollingHandler dial
 * these RPCs instead of the former REST surface
 * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
 * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
 * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.0)",
    comments = "Source: jervis/whatsapp_browser/whatsapp.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class WhatsAppBrowserServiceGrpc {

  private WhatsAppBrowserServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.whatsapp_browser.WhatsAppBrowserService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getSessionStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SessionStatus",
      requestType = com.jervis.contracts.whatsapp_browser.WhatsAppRequest.class,
      responseType = com.jervis.contracts.whatsapp_browser.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getSessionStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse> getSessionStatusMethod;
    if ((getSessionStatusMethod = WhatsAppBrowserServiceGrpc.getSessionStatusMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getSessionStatusMethod = WhatsAppBrowserServiceGrpc.getSessionStatusMethod) == null) {
          WhatsAppBrowserServiceGrpc.getSessionStatusMethod = getSessionStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SessionStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.WhatsAppRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("SessionStatus"))
              .build();
        }
      }
    }
    return getSessionStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getSessionInitMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SessionInit",
      requestType = com.jervis.contracts.whatsapp_browser.WhatsAppRequest.class,
      responseType = com.jervis.contracts.whatsapp_browser.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getSessionInitMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse> getSessionInitMethod;
    if ((getSessionInitMethod = WhatsAppBrowserServiceGrpc.getSessionInitMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getSessionInitMethod = WhatsAppBrowserServiceGrpc.getSessionInitMethod) == null) {
          WhatsAppBrowserServiceGrpc.getSessionInitMethod = getSessionInitMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SessionInit"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.WhatsAppRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("SessionInit"))
              .build();
        }
      }
    }
    return getSessionInitMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getScrapeTriggerMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ScrapeTrigger",
      requestType = com.jervis.contracts.whatsapp_browser.WhatsAppRequest.class,
      responseType = com.jervis.contracts.whatsapp_browser.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getScrapeTriggerMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse> getScrapeTriggerMethod;
    if ((getScrapeTriggerMethod = WhatsAppBrowserServiceGrpc.getScrapeTriggerMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getScrapeTriggerMethod = WhatsAppBrowserServiceGrpc.getScrapeTriggerMethod) == null) {
          WhatsAppBrowserServiceGrpc.getScrapeTriggerMethod = getScrapeTriggerMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ScrapeTrigger"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.WhatsAppRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("ScrapeTrigger"))
              .build();
        }
      }
    }
    return getScrapeTriggerMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getScrapeLatestMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ScrapeLatest",
      requestType = com.jervis.contracts.whatsapp_browser.WhatsAppRequest.class,
      responseType = com.jervis.contracts.whatsapp_browser.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getScrapeLatestMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse> getScrapeLatestMethod;
    if ((getScrapeLatestMethod = WhatsAppBrowserServiceGrpc.getScrapeLatestMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getScrapeLatestMethod = WhatsAppBrowserServiceGrpc.getScrapeLatestMethod) == null) {
          WhatsAppBrowserServiceGrpc.getScrapeLatestMethod = getScrapeLatestMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ScrapeLatest"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.WhatsAppRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("ScrapeLatest"))
              .build();
        }
      }
    }
    return getScrapeLatestMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getVncTokenMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "VncToken",
      requestType = com.jervis.contracts.whatsapp_browser.WhatsAppRequest.class,
      responseType = com.jervis.contracts.whatsapp_browser.RawResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
      com.jervis.contracts.whatsapp_browser.RawResponse> getVncTokenMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse> getVncTokenMethod;
    if ((getVncTokenMethod = WhatsAppBrowserServiceGrpc.getVncTokenMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getVncTokenMethod = WhatsAppBrowserServiceGrpc.getVncTokenMethod) == null) {
          WhatsAppBrowserServiceGrpc.getVncTokenMethod = getVncTokenMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.WhatsAppRequest, com.jervis.contracts.whatsapp_browser.RawResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "VncToken"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.WhatsAppRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.RawResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("VncToken"))
              .build();
        }
      }
    }
    return getVncTokenMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static WhatsAppBrowserServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WhatsAppBrowserServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WhatsAppBrowserServiceStub>() {
        @java.lang.Override
        public WhatsAppBrowserServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WhatsAppBrowserServiceStub(channel, callOptions);
        }
      };
    return WhatsAppBrowserServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static WhatsAppBrowserServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WhatsAppBrowserServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WhatsAppBrowserServiceBlockingStub>() {
        @java.lang.Override
        public WhatsAppBrowserServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WhatsAppBrowserServiceBlockingStub(channel, callOptions);
        }
      };
    return WhatsAppBrowserServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static WhatsAppBrowserServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WhatsAppBrowserServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WhatsAppBrowserServiceFutureStub>() {
        @java.lang.Override
        public WhatsAppBrowserServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WhatsAppBrowserServiceFutureStub(channel, callOptions);
        }
      };
    return WhatsAppBrowserServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * WhatsAppBrowserService — gRPC wrapper on top of the `jervis-whatsapp-browser`
   * pod. Kotlin server's ConnectionRpcImpl + WhatsAppPollingHandler dial
   * these RPCs instead of the former REST surface
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void sessionStatus(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSessionStatusMethod(), responseObserver);
    }

    /**
     */
    default void sessionInit(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSessionInitMethod(), responseObserver);
    }

    /**
     */
    default void scrapeTrigger(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getScrapeTriggerMethod(), responseObserver);
    }

    /**
     */
    default void scrapeLatest(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getScrapeLatestMethod(), responseObserver);
    }

    /**
     */
    default void vncToken(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getVncTokenMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service WhatsAppBrowserService.
   * <pre>
   * WhatsAppBrowserService — gRPC wrapper on top of the `jervis-whatsapp-browser`
   * pod. Kotlin server's ConnectionRpcImpl + WhatsAppPollingHandler dial
   * these RPCs instead of the former REST surface
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
   * </pre>
   */
  public static abstract class WhatsAppBrowserServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return WhatsAppBrowserServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service WhatsAppBrowserService.
   * <pre>
   * WhatsAppBrowserService — gRPC wrapper on top of the `jervis-whatsapp-browser`
   * pod. Kotlin server's ConnectionRpcImpl + WhatsAppPollingHandler dial
   * these RPCs instead of the former REST surface
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
   * </pre>
   */
  public static final class WhatsAppBrowserServiceStub
      extends io.grpc.stub.AbstractAsyncStub<WhatsAppBrowserServiceStub> {
    private WhatsAppBrowserServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WhatsAppBrowserServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WhatsAppBrowserServiceStub(channel, callOptions);
    }

    /**
     */
    public void sessionStatus(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSessionStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sessionInit(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSessionInitMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void scrapeTrigger(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getScrapeTriggerMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void scrapeLatest(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getScrapeLatestMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void vncToken(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getVncTokenMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service WhatsAppBrowserService.
   * <pre>
   * WhatsAppBrowserService — gRPC wrapper on top of the `jervis-whatsapp-browser`
   * pod. Kotlin server's ConnectionRpcImpl + WhatsAppPollingHandler dial
   * these RPCs instead of the former REST surface
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
   * </pre>
   */
  public static final class WhatsAppBrowserServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<WhatsAppBrowserServiceBlockingStub> {
    private WhatsAppBrowserServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WhatsAppBrowserServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WhatsAppBrowserServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.RawResponse sessionStatus(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSessionStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.RawResponse sessionInit(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSessionInitMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.RawResponse scrapeTrigger(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getScrapeTriggerMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.RawResponse scrapeLatest(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getScrapeLatestMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.RawResponse vncToken(com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getVncTokenMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service WhatsAppBrowserService.
   * <pre>
   * WhatsAppBrowserService — gRPC wrapper on top of the `jervis-whatsapp-browser`
   * pod. Kotlin server's ConnectionRpcImpl + WhatsAppPollingHandler dial
   * these RPCs instead of the former REST surface
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — they're browser-facing.
   * </pre>
   */
  public static final class WhatsAppBrowserServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<WhatsAppBrowserServiceFutureStub> {
    private WhatsAppBrowserServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WhatsAppBrowserServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WhatsAppBrowserServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.RawResponse> sessionStatus(
        com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSessionStatusMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.RawResponse> sessionInit(
        com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSessionInitMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.RawResponse> scrapeTrigger(
        com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getScrapeTriggerMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.RawResponse> scrapeLatest(
        com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getScrapeLatestMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.RawResponse> vncToken(
        com.jervis.contracts.whatsapp_browser.WhatsAppRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getVncTokenMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SESSION_STATUS = 0;
  private static final int METHODID_SESSION_INIT = 1;
  private static final int METHODID_SCRAPE_TRIGGER = 2;
  private static final int METHODID_SCRAPE_LATEST = 3;
  private static final int METHODID_VNC_TOKEN = 4;

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
        case METHODID_SESSION_STATUS:
          serviceImpl.sessionStatus((com.jervis.contracts.whatsapp_browser.WhatsAppRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse>) responseObserver);
          break;
        case METHODID_SESSION_INIT:
          serviceImpl.sessionInit((com.jervis.contracts.whatsapp_browser.WhatsAppRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse>) responseObserver);
          break;
        case METHODID_SCRAPE_TRIGGER:
          serviceImpl.scrapeTrigger((com.jervis.contracts.whatsapp_browser.WhatsAppRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse>) responseObserver);
          break;
        case METHODID_SCRAPE_LATEST:
          serviceImpl.scrapeLatest((com.jervis.contracts.whatsapp_browser.WhatsAppRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse>) responseObserver);
          break;
        case METHODID_VNC_TOKEN:
          serviceImpl.vncToken((com.jervis.contracts.whatsapp_browser.WhatsAppRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.RawResponse>) responseObserver);
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
          getSessionStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
              com.jervis.contracts.whatsapp_browser.RawResponse>(
                service, METHODID_SESSION_STATUS)))
        .addMethod(
          getSessionInitMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
              com.jervis.contracts.whatsapp_browser.RawResponse>(
                service, METHODID_SESSION_INIT)))
        .addMethod(
          getScrapeTriggerMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
              com.jervis.contracts.whatsapp_browser.RawResponse>(
                service, METHODID_SCRAPE_TRIGGER)))
        .addMethod(
          getScrapeLatestMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
              com.jervis.contracts.whatsapp_browser.RawResponse>(
                service, METHODID_SCRAPE_LATEST)))
        .addMethod(
          getVncTokenMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.WhatsAppRequest,
              com.jervis.contracts.whatsapp_browser.RawResponse>(
                service, METHODID_VNC_TOKEN)))
        .build();
  }

  private static abstract class WhatsAppBrowserServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    WhatsAppBrowserServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.whatsapp_browser.WhatsAppBrowserProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("WhatsAppBrowserService");
    }
  }

  private static final class WhatsAppBrowserServiceFileDescriptorSupplier
      extends WhatsAppBrowserServiceBaseDescriptorSupplier {
    WhatsAppBrowserServiceFileDescriptorSupplier() {}
  }

  private static final class WhatsAppBrowserServiceMethodDescriptorSupplier
      extends WhatsAppBrowserServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    WhatsAppBrowserServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new WhatsAppBrowserServiceFileDescriptorSupplier())
              .addMethod(getSessionStatusMethod())
              .addMethod(getSessionInitMethod())
              .addMethod(getScrapeTriggerMethod())
              .addMethod(getScrapeLatestMethod())
              .addMethod(getVncTokenMethod())
              .build();
        }
      }
    }
    return result;
  }
}
