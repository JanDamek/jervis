package com.jervis.contracts.whatsapp_browser;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * WhatsAppBrowserService — typed wrapper over the jervis-whatsapp-browser
 * pod. Replaces the former REST surface consumed by the Kotlin server
 * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
 * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
 * (/vnc-login, /vnc-auth) stay HTTP — browser-facing, not pod-to-pod.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class WhatsAppBrowserServiceGrpc {

  private WhatsAppBrowserServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.whatsapp_browser.WhatsAppBrowserService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.SessionRef,
      com.jervis.contracts.whatsapp_browser.SessionStatus> getGetSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSession",
      requestType = com.jervis.contracts.whatsapp_browser.SessionRef.class,
      responseType = com.jervis.contracts.whatsapp_browser.SessionStatus.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.SessionRef,
      com.jervis.contracts.whatsapp_browser.SessionStatus> getGetSessionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.SessionRef, com.jervis.contracts.whatsapp_browser.SessionStatus> getGetSessionMethod;
    if ((getGetSessionMethod = WhatsAppBrowserServiceGrpc.getGetSessionMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getGetSessionMethod = WhatsAppBrowserServiceGrpc.getGetSessionMethod) == null) {
          WhatsAppBrowserServiceGrpc.getGetSessionMethod = getGetSessionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.SessionRef, com.jervis.contracts.whatsapp_browser.SessionStatus>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.SessionRef.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.SessionStatus.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("GetSession"))
              .build();
        }
      }
    }
    return getGetSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.InitSessionRequest,
      com.jervis.contracts.whatsapp_browser.InitSessionResponse> getInitSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "InitSession",
      requestType = com.jervis.contracts.whatsapp_browser.InitSessionRequest.class,
      responseType = com.jervis.contracts.whatsapp_browser.InitSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.InitSessionRequest,
      com.jervis.contracts.whatsapp_browser.InitSessionResponse> getInitSessionMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.InitSessionRequest, com.jervis.contracts.whatsapp_browser.InitSessionResponse> getInitSessionMethod;
    if ((getInitSessionMethod = WhatsAppBrowserServiceGrpc.getInitSessionMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getInitSessionMethod = WhatsAppBrowserServiceGrpc.getInitSessionMethod) == null) {
          WhatsAppBrowserServiceGrpc.getInitSessionMethod = getInitSessionMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.InitSessionRequest, com.jervis.contracts.whatsapp_browser.InitSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "InitSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.InitSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.InitSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("InitSession"))
              .build();
        }
      }
    }
    return getInitSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest,
      com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse> getTriggerScrapeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "TriggerScrape",
      requestType = com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest.class,
      responseType = com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest,
      com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse> getTriggerScrapeMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest, com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse> getTriggerScrapeMethod;
    if ((getTriggerScrapeMethod = WhatsAppBrowserServiceGrpc.getTriggerScrapeMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getTriggerScrapeMethod = WhatsAppBrowserServiceGrpc.getTriggerScrapeMethod) == null) {
          WhatsAppBrowserServiceGrpc.getTriggerScrapeMethod = getTriggerScrapeMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest, com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "TriggerScrape"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("TriggerScrape"))
              .build();
        }
      }
    }
    return getTriggerScrapeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.SessionRef,
      com.jervis.contracts.whatsapp_browser.LatestScrapeResponse> getGetLatestScrapeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetLatestScrape",
      requestType = com.jervis.contracts.whatsapp_browser.SessionRef.class,
      responseType = com.jervis.contracts.whatsapp_browser.LatestScrapeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.SessionRef,
      com.jervis.contracts.whatsapp_browser.LatestScrapeResponse> getGetLatestScrapeMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.SessionRef, com.jervis.contracts.whatsapp_browser.LatestScrapeResponse> getGetLatestScrapeMethod;
    if ((getGetLatestScrapeMethod = WhatsAppBrowserServiceGrpc.getGetLatestScrapeMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getGetLatestScrapeMethod = WhatsAppBrowserServiceGrpc.getGetLatestScrapeMethod) == null) {
          WhatsAppBrowserServiceGrpc.getGetLatestScrapeMethod = getGetLatestScrapeMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.SessionRef, com.jervis.contracts.whatsapp_browser.LatestScrapeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetLatestScrape"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.SessionRef.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.LatestScrapeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("GetLatestScrape"))
              .build();
        }
      }
    }
    return getGetLatestScrapeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.SessionRef,
      com.jervis.contracts.whatsapp_browser.VncTokenResponse> getCreateVncTokenMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateVncToken",
      requestType = com.jervis.contracts.whatsapp_browser.SessionRef.class,
      responseType = com.jervis.contracts.whatsapp_browser.VncTokenResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.SessionRef,
      com.jervis.contracts.whatsapp_browser.VncTokenResponse> getCreateVncTokenMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.whatsapp_browser.SessionRef, com.jervis.contracts.whatsapp_browser.VncTokenResponse> getCreateVncTokenMethod;
    if ((getCreateVncTokenMethod = WhatsAppBrowserServiceGrpc.getCreateVncTokenMethod) == null) {
      synchronized (WhatsAppBrowserServiceGrpc.class) {
        if ((getCreateVncTokenMethod = WhatsAppBrowserServiceGrpc.getCreateVncTokenMethod) == null) {
          WhatsAppBrowserServiceGrpc.getCreateVncTokenMethod = getCreateVncTokenMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.whatsapp_browser.SessionRef, com.jervis.contracts.whatsapp_browser.VncTokenResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateVncToken"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.SessionRef.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.whatsapp_browser.VncTokenResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WhatsAppBrowserServiceMethodDescriptorSupplier("CreateVncToken"))
              .build();
        }
      }
    }
    return getCreateVncTokenMethod;
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
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static WhatsAppBrowserServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WhatsAppBrowserServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WhatsAppBrowserServiceBlockingV2Stub>() {
        @java.lang.Override
        public WhatsAppBrowserServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WhatsAppBrowserServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return WhatsAppBrowserServiceBlockingV2Stub.newStub(factory, channel);
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
   * WhatsAppBrowserService — typed wrapper over the jervis-whatsapp-browser
   * pod. Replaces the former REST surface consumed by the Kotlin server
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — browser-facing, not pod-to-pod.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void getSession(com.jervis.contracts.whatsapp_browser.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.SessionStatus> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetSessionMethod(), responseObserver);
    }

    /**
     */
    default void initSession(com.jervis.contracts.whatsapp_browser.InitSessionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.InitSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInitSessionMethod(), responseObserver);
    }

    /**
     */
    default void triggerScrape(com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTriggerScrapeMethod(), responseObserver);
    }

    /**
     */
    default void getLatestScrape(com.jervis.contracts.whatsapp_browser.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.LatestScrapeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetLatestScrapeMethod(), responseObserver);
    }

    /**
     */
    default void createVncToken(com.jervis.contracts.whatsapp_browser.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.VncTokenResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateVncTokenMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service WhatsAppBrowserService.
   * <pre>
   * WhatsAppBrowserService — typed wrapper over the jervis-whatsapp-browser
   * pod. Replaces the former REST surface consumed by the Kotlin server
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — browser-facing, not pod-to-pod.
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
   * WhatsAppBrowserService — typed wrapper over the jervis-whatsapp-browser
   * pod. Replaces the former REST surface consumed by the Kotlin server
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — browser-facing, not pod-to-pod.
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
    public void getSession(com.jervis.contracts.whatsapp_browser.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.SessionStatus> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void initSession(com.jervis.contracts.whatsapp_browser.InitSessionRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.InitSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInitSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void triggerScrape(com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTriggerScrapeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getLatestScrape(com.jervis.contracts.whatsapp_browser.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.LatestScrapeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetLatestScrapeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createVncToken(com.jervis.contracts.whatsapp_browser.SessionRef request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.VncTokenResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateVncTokenMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service WhatsAppBrowserService.
   * <pre>
   * WhatsAppBrowserService — typed wrapper over the jervis-whatsapp-browser
   * pod. Replaces the former REST surface consumed by the Kotlin server
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — browser-facing, not pod-to-pod.
   * </pre>
   */
  public static final class WhatsAppBrowserServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<WhatsAppBrowserServiceBlockingV2Stub> {
    private WhatsAppBrowserServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WhatsAppBrowserServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WhatsAppBrowserServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.SessionStatus getSession(com.jervis.contracts.whatsapp_browser.SessionRef request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.InitSessionResponse initSession(com.jervis.contracts.whatsapp_browser.InitSessionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInitSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse triggerScrape(com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getTriggerScrapeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.LatestScrapeResponse getLatestScrape(com.jervis.contracts.whatsapp_browser.SessionRef request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetLatestScrapeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.VncTokenResponse createVncToken(com.jervis.contracts.whatsapp_browser.SessionRef request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateVncTokenMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service WhatsAppBrowserService.
   * <pre>
   * WhatsAppBrowserService — typed wrapper over the jervis-whatsapp-browser
   * pod. Replaces the former REST surface consumed by the Kotlin server
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — browser-facing, not pod-to-pod.
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
    public com.jervis.contracts.whatsapp_browser.SessionStatus getSession(com.jervis.contracts.whatsapp_browser.SessionRef request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.InitSessionResponse initSession(com.jervis.contracts.whatsapp_browser.InitSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInitSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse triggerScrape(com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTriggerScrapeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.LatestScrapeResponse getLatestScrape(com.jervis.contracts.whatsapp_browser.SessionRef request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetLatestScrapeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.whatsapp_browser.VncTokenResponse createVncToken(com.jervis.contracts.whatsapp_browser.SessionRef request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateVncTokenMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service WhatsAppBrowserService.
   * <pre>
   * WhatsAppBrowserService — typed wrapper over the jervis-whatsapp-browser
   * pod. Replaces the former REST surface consumed by the Kotlin server
   * (/session/{cid}, /session/{cid}/init, /scrape/{cid}/trigger,
   * /scrape/{cid}/latest, /vnc-token/{cid}). The VNC proxy routes
   * (/vnc-login, /vnc-auth) stay HTTP — browser-facing, not pod-to-pod.
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
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.SessionStatus> getSession(
        com.jervis.contracts.whatsapp_browser.SessionRef request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetSessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.InitSessionResponse> initSession(
        com.jervis.contracts.whatsapp_browser.InitSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInitSessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse> triggerScrape(
        com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTriggerScrapeMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.LatestScrapeResponse> getLatestScrape(
        com.jervis.contracts.whatsapp_browser.SessionRef request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetLatestScrapeMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.whatsapp_browser.VncTokenResponse> createVncToken(
        com.jervis.contracts.whatsapp_browser.SessionRef request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateVncTokenMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_SESSION = 0;
  private static final int METHODID_INIT_SESSION = 1;
  private static final int METHODID_TRIGGER_SCRAPE = 2;
  private static final int METHODID_GET_LATEST_SCRAPE = 3;
  private static final int METHODID_CREATE_VNC_TOKEN = 4;

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
        case METHODID_GET_SESSION:
          serviceImpl.getSession((com.jervis.contracts.whatsapp_browser.SessionRef) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.SessionStatus>) responseObserver);
          break;
        case METHODID_INIT_SESSION:
          serviceImpl.initSession((com.jervis.contracts.whatsapp_browser.InitSessionRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.InitSessionResponse>) responseObserver);
          break;
        case METHODID_TRIGGER_SCRAPE:
          serviceImpl.triggerScrape((com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse>) responseObserver);
          break;
        case METHODID_GET_LATEST_SCRAPE:
          serviceImpl.getLatestScrape((com.jervis.contracts.whatsapp_browser.SessionRef) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.LatestScrapeResponse>) responseObserver);
          break;
        case METHODID_CREATE_VNC_TOKEN:
          serviceImpl.createVncToken((com.jervis.contracts.whatsapp_browser.SessionRef) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.whatsapp_browser.VncTokenResponse>) responseObserver);
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
          getGetSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.SessionRef,
              com.jervis.contracts.whatsapp_browser.SessionStatus>(
                service, METHODID_GET_SESSION)))
        .addMethod(
          getInitSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.InitSessionRequest,
              com.jervis.contracts.whatsapp_browser.InitSessionResponse>(
                service, METHODID_INIT_SESSION)))
        .addMethod(
          getTriggerScrapeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest,
              com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse>(
                service, METHODID_TRIGGER_SCRAPE)))
        .addMethod(
          getGetLatestScrapeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.SessionRef,
              com.jervis.contracts.whatsapp_browser.LatestScrapeResponse>(
                service, METHODID_GET_LATEST_SCRAPE)))
        .addMethod(
          getCreateVncTokenMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.whatsapp_browser.SessionRef,
              com.jervis.contracts.whatsapp_browser.VncTokenResponse>(
                service, METHODID_CREATE_VNC_TOKEN)))
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
              .addMethod(getGetSessionMethod())
              .addMethod(getInitSessionMethod())
              .addMethod(getTriggerScrapeMethod())
              .addMethod(getGetLatestScrapeMethod())
              .addMethod(getCreateVncTokenMethod())
              .build();
        }
      }
    }
    return result;
  }
}
