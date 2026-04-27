package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerO365SessionService consolidates the three pod-to-server callbacks
 * emitted by `service-o365-browser-pool`:
 *   - SessionEvent — AWAITING_MFA / EXPIRED transitions (creates a USER_TASK
 *     when MFA is needed; EXPIRED is now log-only because the pod self-heals).
 *   - CapabilitiesDiscovered — after tab probing, the pod reports which
 *     services (chat/email/calendar) actually exist; server flips the
 *     connection to VALID and pushes a UI snackbar + FCM/APNs notice.
 *   - Notify — kind-aware browser event (urgent_message, meeting_invite,
 *     mfa, auth_request, meeting_alone_check, error, …). Dedup is applied
 *     server-side for urgent_message; meeting_alone_check is suppressed
 *     when the user already answered "Zůstat".
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerO365SessionServiceGrpc {

  private ServerO365SessionServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerO365SessionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.SessionEventRequest,
      com.jervis.contracts.server.SessionEventResponse> getSessionEventMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SessionEvent",
      requestType = com.jervis.contracts.server.SessionEventRequest.class,
      responseType = com.jervis.contracts.server.SessionEventResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.SessionEventRequest,
      com.jervis.contracts.server.SessionEventResponse> getSessionEventMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.SessionEventRequest, com.jervis.contracts.server.SessionEventResponse> getSessionEventMethod;
    if ((getSessionEventMethod = ServerO365SessionServiceGrpc.getSessionEventMethod) == null) {
      synchronized (ServerO365SessionServiceGrpc.class) {
        if ((getSessionEventMethod = ServerO365SessionServiceGrpc.getSessionEventMethod) == null) {
          ServerO365SessionServiceGrpc.getSessionEventMethod = getSessionEventMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.SessionEventRequest, com.jervis.contracts.server.SessionEventResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SessionEvent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SessionEventRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SessionEventResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerO365SessionServiceMethodDescriptorSupplier("SessionEvent"))
              .build();
        }
      }
    }
    return getSessionEventMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CapabilitiesDiscoveredRequest,
      com.jervis.contracts.server.CapabilitiesDiscoveredResponse> getCapabilitiesDiscoveredMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CapabilitiesDiscovered",
      requestType = com.jervis.contracts.server.CapabilitiesDiscoveredRequest.class,
      responseType = com.jervis.contracts.server.CapabilitiesDiscoveredResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CapabilitiesDiscoveredRequest,
      com.jervis.contracts.server.CapabilitiesDiscoveredResponse> getCapabilitiesDiscoveredMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CapabilitiesDiscoveredRequest, com.jervis.contracts.server.CapabilitiesDiscoveredResponse> getCapabilitiesDiscoveredMethod;
    if ((getCapabilitiesDiscoveredMethod = ServerO365SessionServiceGrpc.getCapabilitiesDiscoveredMethod) == null) {
      synchronized (ServerO365SessionServiceGrpc.class) {
        if ((getCapabilitiesDiscoveredMethod = ServerO365SessionServiceGrpc.getCapabilitiesDiscoveredMethod) == null) {
          ServerO365SessionServiceGrpc.getCapabilitiesDiscoveredMethod = getCapabilitiesDiscoveredMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CapabilitiesDiscoveredRequest, com.jervis.contracts.server.CapabilitiesDiscoveredResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CapabilitiesDiscovered"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CapabilitiesDiscoveredRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CapabilitiesDiscoveredResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerO365SessionServiceMethodDescriptorSupplier("CapabilitiesDiscovered"))
              .build();
        }
      }
    }
    return getCapabilitiesDiscoveredMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.NotifyRequest,
      com.jervis.contracts.server.NotifyResponse> getNotifyMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Notify",
      requestType = com.jervis.contracts.server.NotifyRequest.class,
      responseType = com.jervis.contracts.server.NotifyResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.NotifyRequest,
      com.jervis.contracts.server.NotifyResponse> getNotifyMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.NotifyRequest, com.jervis.contracts.server.NotifyResponse> getNotifyMethod;
    if ((getNotifyMethod = ServerO365SessionServiceGrpc.getNotifyMethod) == null) {
      synchronized (ServerO365SessionServiceGrpc.class) {
        if ((getNotifyMethod = ServerO365SessionServiceGrpc.getNotifyMethod) == null) {
          ServerO365SessionServiceGrpc.getNotifyMethod = getNotifyMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.NotifyRequest, com.jervis.contracts.server.NotifyResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Notify"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.NotifyRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.NotifyResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerO365SessionServiceMethodDescriptorSupplier("Notify"))
              .build();
        }
      }
    }
    return getNotifyMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.AcquireLoginConsentRequest,
      com.jervis.contracts.server.AcquireLoginConsentResponse> getAcquireLoginConsentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AcquireLoginConsent",
      requestType = com.jervis.contracts.server.AcquireLoginConsentRequest.class,
      responseType = com.jervis.contracts.server.AcquireLoginConsentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.AcquireLoginConsentRequest,
      com.jervis.contracts.server.AcquireLoginConsentResponse> getAcquireLoginConsentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.AcquireLoginConsentRequest, com.jervis.contracts.server.AcquireLoginConsentResponse> getAcquireLoginConsentMethod;
    if ((getAcquireLoginConsentMethod = ServerO365SessionServiceGrpc.getAcquireLoginConsentMethod) == null) {
      synchronized (ServerO365SessionServiceGrpc.class) {
        if ((getAcquireLoginConsentMethod = ServerO365SessionServiceGrpc.getAcquireLoginConsentMethod) == null) {
          ServerO365SessionServiceGrpc.getAcquireLoginConsentMethod = getAcquireLoginConsentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.AcquireLoginConsentRequest, com.jervis.contracts.server.AcquireLoginConsentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AcquireLoginConsent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AcquireLoginConsentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AcquireLoginConsentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerO365SessionServiceMethodDescriptorSupplier("AcquireLoginConsent"))
              .build();
        }
      }
    }
    return getAcquireLoginConsentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.WaitLoginConsentRequest,
      com.jervis.contracts.server.WaitLoginConsentResponse> getWaitLoginConsentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "WaitLoginConsent",
      requestType = com.jervis.contracts.server.WaitLoginConsentRequest.class,
      responseType = com.jervis.contracts.server.WaitLoginConsentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.WaitLoginConsentRequest,
      com.jervis.contracts.server.WaitLoginConsentResponse> getWaitLoginConsentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.WaitLoginConsentRequest, com.jervis.contracts.server.WaitLoginConsentResponse> getWaitLoginConsentMethod;
    if ((getWaitLoginConsentMethod = ServerO365SessionServiceGrpc.getWaitLoginConsentMethod) == null) {
      synchronized (ServerO365SessionServiceGrpc.class) {
        if ((getWaitLoginConsentMethod = ServerO365SessionServiceGrpc.getWaitLoginConsentMethod) == null) {
          ServerO365SessionServiceGrpc.getWaitLoginConsentMethod = getWaitLoginConsentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.WaitLoginConsentRequest, com.jervis.contracts.server.WaitLoginConsentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "WaitLoginConsent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WaitLoginConsentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.WaitLoginConsentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerO365SessionServiceMethodDescriptorSupplier("WaitLoginConsent"))
              .build();
        }
      }
    }
    return getWaitLoginConsentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ReleaseLoginConsentRequest,
      com.jervis.contracts.server.ReleaseLoginConsentResponse> getReleaseLoginConsentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReleaseLoginConsent",
      requestType = com.jervis.contracts.server.ReleaseLoginConsentRequest.class,
      responseType = com.jervis.contracts.server.ReleaseLoginConsentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ReleaseLoginConsentRequest,
      com.jervis.contracts.server.ReleaseLoginConsentResponse> getReleaseLoginConsentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ReleaseLoginConsentRequest, com.jervis.contracts.server.ReleaseLoginConsentResponse> getReleaseLoginConsentMethod;
    if ((getReleaseLoginConsentMethod = ServerO365SessionServiceGrpc.getReleaseLoginConsentMethod) == null) {
      synchronized (ServerO365SessionServiceGrpc.class) {
        if ((getReleaseLoginConsentMethod = ServerO365SessionServiceGrpc.getReleaseLoginConsentMethod) == null) {
          ServerO365SessionServiceGrpc.getReleaseLoginConsentMethod = getReleaseLoginConsentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ReleaseLoginConsentRequest, com.jervis.contracts.server.ReleaseLoginConsentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReleaseLoginConsent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ReleaseLoginConsentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ReleaseLoginConsentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerO365SessionServiceMethodDescriptorSupplier("ReleaseLoginConsent"))
              .build();
        }
      }
    }
    return getReleaseLoginConsentMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerO365SessionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerO365SessionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerO365SessionServiceStub>() {
        @java.lang.Override
        public ServerO365SessionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerO365SessionServiceStub(channel, callOptions);
        }
      };
    return ServerO365SessionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerO365SessionServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerO365SessionServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerO365SessionServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerO365SessionServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerO365SessionServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerO365SessionServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerO365SessionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerO365SessionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerO365SessionServiceBlockingStub>() {
        @java.lang.Override
        public ServerO365SessionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerO365SessionServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerO365SessionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerO365SessionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerO365SessionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerO365SessionServiceFutureStub>() {
        @java.lang.Override
        public ServerO365SessionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerO365SessionServiceFutureStub(channel, callOptions);
        }
      };
    return ServerO365SessionServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerO365SessionService consolidates the three pod-to-server callbacks
   * emitted by `service-o365-browser-pool`:
   *   - SessionEvent — AWAITING_MFA / EXPIRED transitions (creates a USER_TASK
   *     when MFA is needed; EXPIRED is now log-only because the pod self-heals).
   *   - CapabilitiesDiscovered — after tab probing, the pod reports which
   *     services (chat/email/calendar) actually exist; server flips the
   *     connection to VALID and pushes a UI snackbar + FCM/APNs notice.
   *   - Notify — kind-aware browser event (urgent_message, meeting_invite,
   *     mfa, auth_request, meeting_alone_check, error, …). Dedup is applied
   *     server-side for urgent_message; meeting_alone_check is suppressed
   *     when the user already answered "Zůstat".
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void sessionEvent(com.jervis.contracts.server.SessionEventRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SessionEventResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSessionEventMethod(), responseObserver);
    }

    /**
     */
    default void capabilitiesDiscovered(com.jervis.contracts.server.CapabilitiesDiscoveredRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CapabilitiesDiscoveredResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCapabilitiesDiscoveredMethod(), responseObserver);
    }

    /**
     */
    default void notify(com.jervis.contracts.server.NotifyRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.NotifyResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getNotifyMethod(), responseObserver);
    }

    /**
     * <pre>
     * Login Consent Semaphore (global singleton across ALL connections):
     * pod MUST call AcquireLoginConsent before any login attempt that
     * could trigger an MFA push. Server pushes a notification to user's
     * devices with action buttons [Now / Defer 15min / Defer 1h / Cancel].
     * Pod long-polls WaitLoginConsent until status=granted (proceed) or
     * declined (go to ERROR). After login completes — success OR failure
     * — pod MUST call ReleaseLoginConsent so the next pod in queue can
     * get its turn. Only ONE consent push lives on user's devices at a
     * time; queued pods wait silently.
     * </pre>
     */
    default void acquireLoginConsent(com.jervis.contracts.server.AcquireLoginConsentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AcquireLoginConsentResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAcquireLoginConsentMethod(), responseObserver);
    }

    /**
     */
    default void waitLoginConsent(com.jervis.contracts.server.WaitLoginConsentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WaitLoginConsentResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getWaitLoginConsentMethod(), responseObserver);
    }

    /**
     */
    default void releaseLoginConsent(com.jervis.contracts.server.ReleaseLoginConsentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ReleaseLoginConsentResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReleaseLoginConsentMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerO365SessionService.
   * <pre>
   * ServerO365SessionService consolidates the three pod-to-server callbacks
   * emitted by `service-o365-browser-pool`:
   *   - SessionEvent — AWAITING_MFA / EXPIRED transitions (creates a USER_TASK
   *     when MFA is needed; EXPIRED is now log-only because the pod self-heals).
   *   - CapabilitiesDiscovered — after tab probing, the pod reports which
   *     services (chat/email/calendar) actually exist; server flips the
   *     connection to VALID and pushes a UI snackbar + FCM/APNs notice.
   *   - Notify — kind-aware browser event (urgent_message, meeting_invite,
   *     mfa, auth_request, meeting_alone_check, error, …). Dedup is applied
   *     server-side for urgent_message; meeting_alone_check is suppressed
   *     when the user already answered "Zůstat".
   * </pre>
   */
  public static abstract class ServerO365SessionServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerO365SessionServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerO365SessionService.
   * <pre>
   * ServerO365SessionService consolidates the three pod-to-server callbacks
   * emitted by `service-o365-browser-pool`:
   *   - SessionEvent — AWAITING_MFA / EXPIRED transitions (creates a USER_TASK
   *     when MFA is needed; EXPIRED is now log-only because the pod self-heals).
   *   - CapabilitiesDiscovered — after tab probing, the pod reports which
   *     services (chat/email/calendar) actually exist; server flips the
   *     connection to VALID and pushes a UI snackbar + FCM/APNs notice.
   *   - Notify — kind-aware browser event (urgent_message, meeting_invite,
   *     mfa, auth_request, meeting_alone_check, error, …). Dedup is applied
   *     server-side for urgent_message; meeting_alone_check is suppressed
   *     when the user already answered "Zůstat".
   * </pre>
   */
  public static final class ServerO365SessionServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerO365SessionServiceStub> {
    private ServerO365SessionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerO365SessionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerO365SessionServiceStub(channel, callOptions);
    }

    /**
     */
    public void sessionEvent(com.jervis.contracts.server.SessionEventRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SessionEventResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSessionEventMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void capabilitiesDiscovered(com.jervis.contracts.server.CapabilitiesDiscoveredRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CapabilitiesDiscoveredResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCapabilitiesDiscoveredMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void notify(com.jervis.contracts.server.NotifyRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.NotifyResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getNotifyMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Login Consent Semaphore (global singleton across ALL connections):
     * pod MUST call AcquireLoginConsent before any login attempt that
     * could trigger an MFA push. Server pushes a notification to user's
     * devices with action buttons [Now / Defer 15min / Defer 1h / Cancel].
     * Pod long-polls WaitLoginConsent until status=granted (proceed) or
     * declined (go to ERROR). After login completes — success OR failure
     * — pod MUST call ReleaseLoginConsent so the next pod in queue can
     * get its turn. Only ONE consent push lives on user's devices at a
     * time; queued pods wait silently.
     * </pre>
     */
    public void acquireLoginConsent(com.jervis.contracts.server.AcquireLoginConsentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AcquireLoginConsentResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAcquireLoginConsentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void waitLoginConsent(com.jervis.contracts.server.WaitLoginConsentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.WaitLoginConsentResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getWaitLoginConsentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void releaseLoginConsent(com.jervis.contracts.server.ReleaseLoginConsentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ReleaseLoginConsentResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReleaseLoginConsentMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerO365SessionService.
   * <pre>
   * ServerO365SessionService consolidates the three pod-to-server callbacks
   * emitted by `service-o365-browser-pool`:
   *   - SessionEvent — AWAITING_MFA / EXPIRED transitions (creates a USER_TASK
   *     when MFA is needed; EXPIRED is now log-only because the pod self-heals).
   *   - CapabilitiesDiscovered — after tab probing, the pod reports which
   *     services (chat/email/calendar) actually exist; server flips the
   *     connection to VALID and pushes a UI snackbar + FCM/APNs notice.
   *   - Notify — kind-aware browser event (urgent_message, meeting_invite,
   *     mfa, auth_request, meeting_alone_check, error, …). Dedup is applied
   *     server-side for urgent_message; meeting_alone_check is suppressed
   *     when the user already answered "Zůstat".
   * </pre>
   */
  public static final class ServerO365SessionServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerO365SessionServiceBlockingV2Stub> {
    private ServerO365SessionServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerO365SessionServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerO365SessionServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.SessionEventResponse sessionEvent(com.jervis.contracts.server.SessionEventRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSessionEventMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CapabilitiesDiscoveredResponse capabilitiesDiscovered(com.jervis.contracts.server.CapabilitiesDiscoveredRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCapabilitiesDiscoveredMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.NotifyResponse notify(com.jervis.contracts.server.NotifyRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getNotifyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Login Consent Semaphore (global singleton across ALL connections):
     * pod MUST call AcquireLoginConsent before any login attempt that
     * could trigger an MFA push. Server pushes a notification to user's
     * devices with action buttons [Now / Defer 15min / Defer 1h / Cancel].
     * Pod long-polls WaitLoginConsent until status=granted (proceed) or
     * declined (go to ERROR). After login completes — success OR failure
     * — pod MUST call ReleaseLoginConsent so the next pod in queue can
     * get its turn. Only ONE consent push lives on user's devices at a
     * time; queued pods wait silently.
     * </pre>
     */
    public com.jervis.contracts.server.AcquireLoginConsentResponse acquireLoginConsent(com.jervis.contracts.server.AcquireLoginConsentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAcquireLoginConsentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.WaitLoginConsentResponse waitLoginConsent(com.jervis.contracts.server.WaitLoginConsentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getWaitLoginConsentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ReleaseLoginConsentResponse releaseLoginConsent(com.jervis.contracts.server.ReleaseLoginConsentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReleaseLoginConsentMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerO365SessionService.
   * <pre>
   * ServerO365SessionService consolidates the three pod-to-server callbacks
   * emitted by `service-o365-browser-pool`:
   *   - SessionEvent — AWAITING_MFA / EXPIRED transitions (creates a USER_TASK
   *     when MFA is needed; EXPIRED is now log-only because the pod self-heals).
   *   - CapabilitiesDiscovered — after tab probing, the pod reports which
   *     services (chat/email/calendar) actually exist; server flips the
   *     connection to VALID and pushes a UI snackbar + FCM/APNs notice.
   *   - Notify — kind-aware browser event (urgent_message, meeting_invite,
   *     mfa, auth_request, meeting_alone_check, error, …). Dedup is applied
   *     server-side for urgent_message; meeting_alone_check is suppressed
   *     when the user already answered "Zůstat".
   * </pre>
   */
  public static final class ServerO365SessionServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerO365SessionServiceBlockingStub> {
    private ServerO365SessionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerO365SessionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerO365SessionServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.SessionEventResponse sessionEvent(com.jervis.contracts.server.SessionEventRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSessionEventMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CapabilitiesDiscoveredResponse capabilitiesDiscovered(com.jervis.contracts.server.CapabilitiesDiscoveredRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCapabilitiesDiscoveredMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.NotifyResponse notify(com.jervis.contracts.server.NotifyRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getNotifyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Login Consent Semaphore (global singleton across ALL connections):
     * pod MUST call AcquireLoginConsent before any login attempt that
     * could trigger an MFA push. Server pushes a notification to user's
     * devices with action buttons [Now / Defer 15min / Defer 1h / Cancel].
     * Pod long-polls WaitLoginConsent until status=granted (proceed) or
     * declined (go to ERROR). After login completes — success OR failure
     * — pod MUST call ReleaseLoginConsent so the next pod in queue can
     * get its turn. Only ONE consent push lives on user's devices at a
     * time; queued pods wait silently.
     * </pre>
     */
    public com.jervis.contracts.server.AcquireLoginConsentResponse acquireLoginConsent(com.jervis.contracts.server.AcquireLoginConsentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAcquireLoginConsentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.WaitLoginConsentResponse waitLoginConsent(com.jervis.contracts.server.WaitLoginConsentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getWaitLoginConsentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ReleaseLoginConsentResponse releaseLoginConsent(com.jervis.contracts.server.ReleaseLoginConsentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReleaseLoginConsentMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerO365SessionService.
   * <pre>
   * ServerO365SessionService consolidates the three pod-to-server callbacks
   * emitted by `service-o365-browser-pool`:
   *   - SessionEvent — AWAITING_MFA / EXPIRED transitions (creates a USER_TASK
   *     when MFA is needed; EXPIRED is now log-only because the pod self-heals).
   *   - CapabilitiesDiscovered — after tab probing, the pod reports which
   *     services (chat/email/calendar) actually exist; server flips the
   *     connection to VALID and pushes a UI snackbar + FCM/APNs notice.
   *   - Notify — kind-aware browser event (urgent_message, meeting_invite,
   *     mfa, auth_request, meeting_alone_check, error, …). Dedup is applied
   *     server-side for urgent_message; meeting_alone_check is suppressed
   *     when the user already answered "Zůstat".
   * </pre>
   */
  public static final class ServerO365SessionServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerO365SessionServiceFutureStub> {
    private ServerO365SessionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerO365SessionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerO365SessionServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.SessionEventResponse> sessionEvent(
        com.jervis.contracts.server.SessionEventRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSessionEventMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CapabilitiesDiscoveredResponse> capabilitiesDiscovered(
        com.jervis.contracts.server.CapabilitiesDiscoveredRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCapabilitiesDiscoveredMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.NotifyResponse> notify(
        com.jervis.contracts.server.NotifyRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getNotifyMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Login Consent Semaphore (global singleton across ALL connections):
     * pod MUST call AcquireLoginConsent before any login attempt that
     * could trigger an MFA push. Server pushes a notification to user's
     * devices with action buttons [Now / Defer 15min / Defer 1h / Cancel].
     * Pod long-polls WaitLoginConsent until status=granted (proceed) or
     * declined (go to ERROR). After login completes — success OR failure
     * — pod MUST call ReleaseLoginConsent so the next pod in queue can
     * get its turn. Only ONE consent push lives on user's devices at a
     * time; queued pods wait silently.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AcquireLoginConsentResponse> acquireLoginConsent(
        com.jervis.contracts.server.AcquireLoginConsentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAcquireLoginConsentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.WaitLoginConsentResponse> waitLoginConsent(
        com.jervis.contracts.server.WaitLoginConsentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getWaitLoginConsentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ReleaseLoginConsentResponse> releaseLoginConsent(
        com.jervis.contracts.server.ReleaseLoginConsentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReleaseLoginConsentMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SESSION_EVENT = 0;
  private static final int METHODID_CAPABILITIES_DISCOVERED = 1;
  private static final int METHODID_NOTIFY = 2;
  private static final int METHODID_ACQUIRE_LOGIN_CONSENT = 3;
  private static final int METHODID_WAIT_LOGIN_CONSENT = 4;
  private static final int METHODID_RELEASE_LOGIN_CONSENT = 5;

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
        case METHODID_SESSION_EVENT:
          serviceImpl.sessionEvent((com.jervis.contracts.server.SessionEventRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.SessionEventResponse>) responseObserver);
          break;
        case METHODID_CAPABILITIES_DISCOVERED:
          serviceImpl.capabilitiesDiscovered((com.jervis.contracts.server.CapabilitiesDiscoveredRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CapabilitiesDiscoveredResponse>) responseObserver);
          break;
        case METHODID_NOTIFY:
          serviceImpl.notify((com.jervis.contracts.server.NotifyRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.NotifyResponse>) responseObserver);
          break;
        case METHODID_ACQUIRE_LOGIN_CONSENT:
          serviceImpl.acquireLoginConsent((com.jervis.contracts.server.AcquireLoginConsentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AcquireLoginConsentResponse>) responseObserver);
          break;
        case METHODID_WAIT_LOGIN_CONSENT:
          serviceImpl.waitLoginConsent((com.jervis.contracts.server.WaitLoginConsentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.WaitLoginConsentResponse>) responseObserver);
          break;
        case METHODID_RELEASE_LOGIN_CONSENT:
          serviceImpl.releaseLoginConsent((com.jervis.contracts.server.ReleaseLoginConsentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ReleaseLoginConsentResponse>) responseObserver);
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
          getSessionEventMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.SessionEventRequest,
              com.jervis.contracts.server.SessionEventResponse>(
                service, METHODID_SESSION_EVENT)))
        .addMethod(
          getCapabilitiesDiscoveredMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CapabilitiesDiscoveredRequest,
              com.jervis.contracts.server.CapabilitiesDiscoveredResponse>(
                service, METHODID_CAPABILITIES_DISCOVERED)))
        .addMethod(
          getNotifyMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.NotifyRequest,
              com.jervis.contracts.server.NotifyResponse>(
                service, METHODID_NOTIFY)))
        .addMethod(
          getAcquireLoginConsentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.AcquireLoginConsentRequest,
              com.jervis.contracts.server.AcquireLoginConsentResponse>(
                service, METHODID_ACQUIRE_LOGIN_CONSENT)))
        .addMethod(
          getWaitLoginConsentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.WaitLoginConsentRequest,
              com.jervis.contracts.server.WaitLoginConsentResponse>(
                service, METHODID_WAIT_LOGIN_CONSENT)))
        .addMethod(
          getReleaseLoginConsentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ReleaseLoginConsentRequest,
              com.jervis.contracts.server.ReleaseLoginConsentResponse>(
                service, METHODID_RELEASE_LOGIN_CONSENT)))
        .build();
  }

  private static abstract class ServerO365SessionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerO365SessionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerO365SessionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerO365SessionService");
    }
  }

  private static final class ServerO365SessionServiceFileDescriptorSupplier
      extends ServerO365SessionServiceBaseDescriptorSupplier {
    ServerO365SessionServiceFileDescriptorSupplier() {}
  }

  private static final class ServerO365SessionServiceMethodDescriptorSupplier
      extends ServerO365SessionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerO365SessionServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerO365SessionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerO365SessionServiceFileDescriptorSupplier())
              .addMethod(getSessionEventMethod())
              .addMethod(getCapabilitiesDiscoveredMethod())
              .addMethod(getNotifyMethod())
              .addMethod(getAcquireLoginConsentMethod())
              .addMethod(getWaitLoginConsentMethod())
              .addMethod(getReleaseLoginConsentMethod())
              .build();
        }
      }
    }
    return result;
  }
}
