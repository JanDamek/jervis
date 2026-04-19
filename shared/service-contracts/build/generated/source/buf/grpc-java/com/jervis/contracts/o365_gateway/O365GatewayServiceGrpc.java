package com.jervis.contracts.o365_gateway;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * O365GatewayService — one umbrella gRPC for the O365 gateway pod. It
 * fronts every Microsoft Graph call Jervis makes (Teams chats / channels,
 * Mail, Calendar, Online meetings, Drive, session). Graph-specific DTOs
 * are shared across RPC groups so the same `ChatMessage` is returned for
 * Teams chats and channel reads.
 * Typed RPCs land incrementally (slice V5a -&gt; Teams chats, V5b -&gt; channels,
 * etc.). The legacy Request / RequestBytes passthrough stays until every
 * group is typed; it is dropped in V5h.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.1)",
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

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListChatsRequest,
      com.jervis.contracts.o365_gateway.ListChatsResponse> getListChatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListChats",
      requestType = com.jervis.contracts.o365_gateway.ListChatsRequest.class,
      responseType = com.jervis.contracts.o365_gateway.ListChatsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListChatsRequest,
      com.jervis.contracts.o365_gateway.ListChatsResponse> getListChatsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListChatsRequest, com.jervis.contracts.o365_gateway.ListChatsResponse> getListChatsMethod;
    if ((getListChatsMethod = O365GatewayServiceGrpc.getListChatsMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getListChatsMethod = O365GatewayServiceGrpc.getListChatsMethod) == null) {
          O365GatewayServiceGrpc.getListChatsMethod = getListChatsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.ListChatsRequest, com.jervis.contracts.o365_gateway.ListChatsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListChats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListChatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListChatsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("ListChats"))
              .build();
        }
      }
    }
    return getListChatsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ReadChatRequest,
      com.jervis.contracts.o365_gateway.ListChatMessagesResponse> getReadChatMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReadChat",
      requestType = com.jervis.contracts.o365_gateway.ReadChatRequest.class,
      responseType = com.jervis.contracts.o365_gateway.ListChatMessagesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ReadChatRequest,
      com.jervis.contracts.o365_gateway.ListChatMessagesResponse> getReadChatMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ReadChatRequest, com.jervis.contracts.o365_gateway.ListChatMessagesResponse> getReadChatMethod;
    if ((getReadChatMethod = O365GatewayServiceGrpc.getReadChatMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getReadChatMethod = O365GatewayServiceGrpc.getReadChatMethod) == null) {
          O365GatewayServiceGrpc.getReadChatMethod = getReadChatMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.ReadChatRequest, com.jervis.contracts.o365_gateway.ListChatMessagesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReadChat"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ReadChatRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListChatMessagesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("ReadChat"))
              .build();
        }
      }
    }
    return getReadChatMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.SendChatMessageRequest,
      com.jervis.contracts.o365_gateway.ChatMessage> getSendChatMessageMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendChatMessage",
      requestType = com.jervis.contracts.o365_gateway.SendChatMessageRequest.class,
      responseType = com.jervis.contracts.o365_gateway.ChatMessage.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.SendChatMessageRequest,
      com.jervis.contracts.o365_gateway.ChatMessage> getSendChatMessageMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.SendChatMessageRequest, com.jervis.contracts.o365_gateway.ChatMessage> getSendChatMessageMethod;
    if ((getSendChatMessageMethod = O365GatewayServiceGrpc.getSendChatMessageMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getSendChatMessageMethod = O365GatewayServiceGrpc.getSendChatMessageMethod) == null) {
          O365GatewayServiceGrpc.getSendChatMessageMethod = getSendChatMessageMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.SendChatMessageRequest, com.jervis.contracts.o365_gateway.ChatMessage>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendChatMessage"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.SendChatMessageRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ChatMessage.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("SendChatMessage"))
              .build();
        }
      }
    }
    return getSendChatMessageMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListTeamsRequest,
      com.jervis.contracts.o365_gateway.ListTeamsResponse> getListTeamsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListTeams",
      requestType = com.jervis.contracts.o365_gateway.ListTeamsRequest.class,
      responseType = com.jervis.contracts.o365_gateway.ListTeamsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListTeamsRequest,
      com.jervis.contracts.o365_gateway.ListTeamsResponse> getListTeamsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListTeamsRequest, com.jervis.contracts.o365_gateway.ListTeamsResponse> getListTeamsMethod;
    if ((getListTeamsMethod = O365GatewayServiceGrpc.getListTeamsMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getListTeamsMethod = O365GatewayServiceGrpc.getListTeamsMethod) == null) {
          O365GatewayServiceGrpc.getListTeamsMethod = getListTeamsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.ListTeamsRequest, com.jervis.contracts.o365_gateway.ListTeamsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListTeams"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListTeamsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListTeamsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("ListTeams"))
              .build();
        }
      }
    }
    return getListTeamsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListChannelsRequest,
      com.jervis.contracts.o365_gateway.ListChannelsResponse> getListChannelsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListChannels",
      requestType = com.jervis.contracts.o365_gateway.ListChannelsRequest.class,
      responseType = com.jervis.contracts.o365_gateway.ListChannelsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListChannelsRequest,
      com.jervis.contracts.o365_gateway.ListChannelsResponse> getListChannelsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListChannelsRequest, com.jervis.contracts.o365_gateway.ListChannelsResponse> getListChannelsMethod;
    if ((getListChannelsMethod = O365GatewayServiceGrpc.getListChannelsMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getListChannelsMethod = O365GatewayServiceGrpc.getListChannelsMethod) == null) {
          O365GatewayServiceGrpc.getListChannelsMethod = getListChannelsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.ListChannelsRequest, com.jervis.contracts.o365_gateway.ListChannelsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListChannels"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListChannelsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListChannelsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("ListChannels"))
              .build();
        }
      }
    }
    return getListChannelsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ReadChannelRequest,
      com.jervis.contracts.o365_gateway.ListChannelMessagesResponse> getReadChannelMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReadChannel",
      requestType = com.jervis.contracts.o365_gateway.ReadChannelRequest.class,
      responseType = com.jervis.contracts.o365_gateway.ListChannelMessagesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ReadChannelRequest,
      com.jervis.contracts.o365_gateway.ListChannelMessagesResponse> getReadChannelMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ReadChannelRequest, com.jervis.contracts.o365_gateway.ListChannelMessagesResponse> getReadChannelMethod;
    if ((getReadChannelMethod = O365GatewayServiceGrpc.getReadChannelMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getReadChannelMethod = O365GatewayServiceGrpc.getReadChannelMethod) == null) {
          O365GatewayServiceGrpc.getReadChannelMethod = getReadChannelMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.ReadChannelRequest, com.jervis.contracts.o365_gateway.ListChannelMessagesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReadChannel"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ReadChannelRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListChannelMessagesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("ReadChannel"))
              .build();
        }
      }
    }
    return getReadChannelMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.SendChannelMessageRequest,
      com.jervis.contracts.o365_gateway.ChatMessage> getSendChannelMessageMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendChannelMessage",
      requestType = com.jervis.contracts.o365_gateway.SendChannelMessageRequest.class,
      responseType = com.jervis.contracts.o365_gateway.ChatMessage.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.SendChannelMessageRequest,
      com.jervis.contracts.o365_gateway.ChatMessage> getSendChannelMessageMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.SendChannelMessageRequest, com.jervis.contracts.o365_gateway.ChatMessage> getSendChannelMessageMethod;
    if ((getSendChannelMessageMethod = O365GatewayServiceGrpc.getSendChannelMessageMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getSendChannelMessageMethod = O365GatewayServiceGrpc.getSendChannelMessageMethod) == null) {
          O365GatewayServiceGrpc.getSendChannelMessageMethod = getSendChannelMessageMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.SendChannelMessageRequest, com.jervis.contracts.o365_gateway.ChatMessage>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendChannelMessage"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.SendChannelMessageRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ChatMessage.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("SendChannelMessage"))
              .build();
        }
      }
    }
    return getSendChannelMessageMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListMailRequest,
      com.jervis.contracts.o365_gateway.ListMailResponse> getListMailMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListMail",
      requestType = com.jervis.contracts.o365_gateway.ListMailRequest.class,
      responseType = com.jervis.contracts.o365_gateway.ListMailResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListMailRequest,
      com.jervis.contracts.o365_gateway.ListMailResponse> getListMailMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListMailRequest, com.jervis.contracts.o365_gateway.ListMailResponse> getListMailMethod;
    if ((getListMailMethod = O365GatewayServiceGrpc.getListMailMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getListMailMethod = O365GatewayServiceGrpc.getListMailMethod) == null) {
          O365GatewayServiceGrpc.getListMailMethod = getListMailMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.ListMailRequest, com.jervis.contracts.o365_gateway.ListMailResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListMail"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListMailRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListMailResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("ListMail"))
              .build();
        }
      }
    }
    return getListMailMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ReadMailRequest,
      com.jervis.contracts.o365_gateway.MailMessage> getReadMailMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReadMail",
      requestType = com.jervis.contracts.o365_gateway.ReadMailRequest.class,
      responseType = com.jervis.contracts.o365_gateway.MailMessage.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ReadMailRequest,
      com.jervis.contracts.o365_gateway.MailMessage> getReadMailMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ReadMailRequest, com.jervis.contracts.o365_gateway.MailMessage> getReadMailMethod;
    if ((getReadMailMethod = O365GatewayServiceGrpc.getReadMailMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getReadMailMethod = O365GatewayServiceGrpc.getReadMailMethod) == null) {
          O365GatewayServiceGrpc.getReadMailMethod = getReadMailMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.ReadMailRequest, com.jervis.contracts.o365_gateway.MailMessage>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReadMail"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ReadMailRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.MailMessage.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("ReadMail"))
              .build();
        }
      }
    }
    return getReadMailMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.SendMailRpcRequest,
      com.jervis.contracts.o365_gateway.SendMailAck> getSendMailMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendMail",
      requestType = com.jervis.contracts.o365_gateway.SendMailRpcRequest.class,
      responseType = com.jervis.contracts.o365_gateway.SendMailAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.SendMailRpcRequest,
      com.jervis.contracts.o365_gateway.SendMailAck> getSendMailMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.SendMailRpcRequest, com.jervis.contracts.o365_gateway.SendMailAck> getSendMailMethod;
    if ((getSendMailMethod = O365GatewayServiceGrpc.getSendMailMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getSendMailMethod = O365GatewayServiceGrpc.getSendMailMethod) == null) {
          O365GatewayServiceGrpc.getSendMailMethod = getSendMailMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.SendMailRpcRequest, com.jervis.contracts.o365_gateway.SendMailAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendMail"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.SendMailRpcRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.SendMailAck.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("SendMail"))
              .build();
        }
      }
    }
    return getSendMailMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListCalendarEventsRequest,
      com.jervis.contracts.o365_gateway.ListCalendarEventsResponse> getListCalendarEventsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListCalendarEvents",
      requestType = com.jervis.contracts.o365_gateway.ListCalendarEventsRequest.class,
      responseType = com.jervis.contracts.o365_gateway.ListCalendarEventsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListCalendarEventsRequest,
      com.jervis.contracts.o365_gateway.ListCalendarEventsResponse> getListCalendarEventsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.ListCalendarEventsRequest, com.jervis.contracts.o365_gateway.ListCalendarEventsResponse> getListCalendarEventsMethod;
    if ((getListCalendarEventsMethod = O365GatewayServiceGrpc.getListCalendarEventsMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getListCalendarEventsMethod = O365GatewayServiceGrpc.getListCalendarEventsMethod) == null) {
          O365GatewayServiceGrpc.getListCalendarEventsMethod = getListCalendarEventsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.ListCalendarEventsRequest, com.jervis.contracts.o365_gateway.ListCalendarEventsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListCalendarEvents"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListCalendarEventsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.ListCalendarEventsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("ListCalendarEvents"))
              .build();
        }
      }
    }
    return getListCalendarEventsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.CreateCalendarEventRequest,
      com.jervis.contracts.o365_gateway.CalendarEvent> getCreateCalendarEventMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateCalendarEvent",
      requestType = com.jervis.contracts.o365_gateway.CreateCalendarEventRequest.class,
      responseType = com.jervis.contracts.o365_gateway.CalendarEvent.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.CreateCalendarEventRequest,
      com.jervis.contracts.o365_gateway.CalendarEvent> getCreateCalendarEventMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.o365_gateway.CreateCalendarEventRequest, com.jervis.contracts.o365_gateway.CalendarEvent> getCreateCalendarEventMethod;
    if ((getCreateCalendarEventMethod = O365GatewayServiceGrpc.getCreateCalendarEventMethod) == null) {
      synchronized (O365GatewayServiceGrpc.class) {
        if ((getCreateCalendarEventMethod = O365GatewayServiceGrpc.getCreateCalendarEventMethod) == null) {
          O365GatewayServiceGrpc.getCreateCalendarEventMethod = getCreateCalendarEventMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.o365_gateway.CreateCalendarEventRequest, com.jervis.contracts.o365_gateway.CalendarEvent>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateCalendarEvent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.CreateCalendarEventRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.o365_gateway.CalendarEvent.getDefaultInstance()))
              .setSchemaDescriptor(new O365GatewayServiceMethodDescriptorSupplier("CreateCalendarEvent"))
              .build();
        }
      }
    }
    return getCreateCalendarEventMethod;
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
   * O365GatewayService — one umbrella gRPC for the O365 gateway pod. It
   * fronts every Microsoft Graph call Jervis makes (Teams chats / channels,
   * Mail, Calendar, Online meetings, Drive, session). Graph-specific DTOs
   * are shared across RPC groups so the same `ChatMessage` is returned for
   * Teams chats and channel reads.
   * Typed RPCs land incrementally (slice V5a -&gt; Teams chats, V5b -&gt; channels,
   * etc.). The legacy Request / RequestBytes passthrough stays until every
   * group is typed; it is dropped in V5h.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * === Legacy passthrough (drop at V5h) =====================================
     * </pre>
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

    /**
     * <pre>
     * === V5a - Teams chats ====================================================
     * </pre>
     */
    default void listChats(com.jervis.contracts.o365_gateway.ListChatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChatsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListChatsMethod(), responseObserver);
    }

    /**
     */
    default void readChat(com.jervis.contracts.o365_gateway.ReadChatRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChatMessagesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReadChatMethod(), responseObserver);
    }

    /**
     */
    default void sendChatMessage(com.jervis.contracts.o365_gateway.SendChatMessageRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ChatMessage> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendChatMessageMethod(), responseObserver);
    }

    /**
     * <pre>
     * === V5b - Teams teams / channels =========================================
     * </pre>
     */
    default void listTeams(com.jervis.contracts.o365_gateway.ListTeamsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListTeamsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListTeamsMethod(), responseObserver);
    }

    /**
     */
    default void listChannels(com.jervis.contracts.o365_gateway.ListChannelsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChannelsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListChannelsMethod(), responseObserver);
    }

    /**
     */
    default void readChannel(com.jervis.contracts.o365_gateway.ReadChannelRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChannelMessagesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReadChannelMethod(), responseObserver);
    }

    /**
     */
    default void sendChannelMessage(com.jervis.contracts.o365_gateway.SendChannelMessageRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ChatMessage> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendChannelMessageMethod(), responseObserver);
    }

    /**
     * <pre>
     * === V5c - Mail (Outlook) =================================================
     * </pre>
     */
    default void listMail(com.jervis.contracts.o365_gateway.ListMailRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListMailResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListMailMethod(), responseObserver);
    }

    /**
     */
    default void readMail(com.jervis.contracts.o365_gateway.ReadMailRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.MailMessage> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReadMailMethod(), responseObserver);
    }

    /**
     */
    default void sendMail(com.jervis.contracts.o365_gateway.SendMailRpcRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.SendMailAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendMailMethod(), responseObserver);
    }

    /**
     * <pre>
     * === V5d - Calendar =======================================================
     * </pre>
     */
    default void listCalendarEvents(com.jervis.contracts.o365_gateway.ListCalendarEventsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListCalendarEventsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListCalendarEventsMethod(), responseObserver);
    }

    /**
     */
    default void createCalendarEvent(com.jervis.contracts.o365_gateway.CreateCalendarEventRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.CalendarEvent> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateCalendarEventMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service O365GatewayService.
   * <pre>
   * O365GatewayService — one umbrella gRPC for the O365 gateway pod. It
   * fronts every Microsoft Graph call Jervis makes (Teams chats / channels,
   * Mail, Calendar, Online meetings, Drive, session). Graph-specific DTOs
   * are shared across RPC groups so the same `ChatMessage` is returned for
   * Teams chats and channel reads.
   * Typed RPCs land incrementally (slice V5a -&gt; Teams chats, V5b -&gt; channels,
   * etc.). The legacy Request / RequestBytes passthrough stays until every
   * group is typed; it is dropped in V5h.
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
   * O365GatewayService — one umbrella gRPC for the O365 gateway pod. It
   * fronts every Microsoft Graph call Jervis makes (Teams chats / channels,
   * Mail, Calendar, Online meetings, Drive, session). Graph-specific DTOs
   * are shared across RPC groups so the same `ChatMessage` is returned for
   * Teams chats and channel reads.
   * Typed RPCs land incrementally (slice V5a -&gt; Teams chats, V5b -&gt; channels,
   * etc.). The legacy Request / RequestBytes passthrough stays until every
   * group is typed; it is dropped in V5h.
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
     * <pre>
     * === Legacy passthrough (drop at V5h) =====================================
     * </pre>
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

    /**
     * <pre>
     * === V5a - Teams chats ====================================================
     * </pre>
     */
    public void listChats(com.jervis.contracts.o365_gateway.ListChatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChatsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListChatsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void readChat(com.jervis.contracts.o365_gateway.ReadChatRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChatMessagesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReadChatMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sendChatMessage(com.jervis.contracts.o365_gateway.SendChatMessageRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ChatMessage> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendChatMessageMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * === V5b - Teams teams / channels =========================================
     * </pre>
     */
    public void listTeams(com.jervis.contracts.o365_gateway.ListTeamsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListTeamsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListTeamsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listChannels(com.jervis.contracts.o365_gateway.ListChannelsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChannelsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListChannelsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void readChannel(com.jervis.contracts.o365_gateway.ReadChannelRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChannelMessagesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReadChannelMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sendChannelMessage(com.jervis.contracts.o365_gateway.SendChannelMessageRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ChatMessage> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendChannelMessageMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * === V5c - Mail (Outlook) =================================================
     * </pre>
     */
    public void listMail(com.jervis.contracts.o365_gateway.ListMailRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListMailResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListMailMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void readMail(com.jervis.contracts.o365_gateway.ReadMailRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.MailMessage> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReadMailMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sendMail(com.jervis.contracts.o365_gateway.SendMailRpcRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.SendMailAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendMailMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * === V5d - Calendar =======================================================
     * </pre>
     */
    public void listCalendarEvents(com.jervis.contracts.o365_gateway.ListCalendarEventsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListCalendarEventsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListCalendarEventsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createCalendarEvent(com.jervis.contracts.o365_gateway.CreateCalendarEventRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.CalendarEvent> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateCalendarEventMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service O365GatewayService.
   * <pre>
   * O365GatewayService — one umbrella gRPC for the O365 gateway pod. It
   * fronts every Microsoft Graph call Jervis makes (Teams chats / channels,
   * Mail, Calendar, Online meetings, Drive, session). Graph-specific DTOs
   * are shared across RPC groups so the same `ChatMessage` is returned for
   * Teams chats and channel reads.
   * Typed RPCs land incrementally (slice V5a -&gt; Teams chats, V5b -&gt; channels,
   * etc.). The legacy Request / RequestBytes passthrough stays until every
   * group is typed; it is dropped in V5h.
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
     * <pre>
     * === Legacy passthrough (drop at V5h) =====================================
     * </pre>
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

    /**
     * <pre>
     * === V5a - Teams chats ====================================================
     * </pre>
     */
    public com.jervis.contracts.o365_gateway.ListChatsResponse listChats(com.jervis.contracts.o365_gateway.ListChatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListChatsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.ListChatMessagesResponse readChat(com.jervis.contracts.o365_gateway.ReadChatRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReadChatMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.ChatMessage sendChatMessage(com.jervis.contracts.o365_gateway.SendChatMessageRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendChatMessageMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * === V5b - Teams teams / channels =========================================
     * </pre>
     */
    public com.jervis.contracts.o365_gateway.ListTeamsResponse listTeams(com.jervis.contracts.o365_gateway.ListTeamsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListTeamsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.ListChannelsResponse listChannels(com.jervis.contracts.o365_gateway.ListChannelsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListChannelsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.ListChannelMessagesResponse readChannel(com.jervis.contracts.o365_gateway.ReadChannelRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReadChannelMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.ChatMessage sendChannelMessage(com.jervis.contracts.o365_gateway.SendChannelMessageRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendChannelMessageMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * === V5c - Mail (Outlook) =================================================
     * </pre>
     */
    public com.jervis.contracts.o365_gateway.ListMailResponse listMail(com.jervis.contracts.o365_gateway.ListMailRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListMailMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.MailMessage readMail(com.jervis.contracts.o365_gateway.ReadMailRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReadMailMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.SendMailAck sendMail(com.jervis.contracts.o365_gateway.SendMailRpcRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendMailMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * === V5d - Calendar =======================================================
     * </pre>
     */
    public com.jervis.contracts.o365_gateway.ListCalendarEventsResponse listCalendarEvents(com.jervis.contracts.o365_gateway.ListCalendarEventsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListCalendarEventsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.o365_gateway.CalendarEvent createCalendarEvent(com.jervis.contracts.o365_gateway.CreateCalendarEventRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateCalendarEventMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service O365GatewayService.
   * <pre>
   * O365GatewayService — one umbrella gRPC for the O365 gateway pod. It
   * fronts every Microsoft Graph call Jervis makes (Teams chats / channels,
   * Mail, Calendar, Online meetings, Drive, session). Graph-specific DTOs
   * are shared across RPC groups so the same `ChatMessage` is returned for
   * Teams chats and channel reads.
   * Typed RPCs land incrementally (slice V5a -&gt; Teams chats, V5b -&gt; channels,
   * etc.). The legacy Request / RequestBytes passthrough stays until every
   * group is typed; it is dropped in V5h.
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
     * <pre>
     * === Legacy passthrough (drop at V5h) =====================================
     * </pre>
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

    /**
     * <pre>
     * === V5a - Teams chats ====================================================
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.ListChatsResponse> listChats(
        com.jervis.contracts.o365_gateway.ListChatsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListChatsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.ListChatMessagesResponse> readChat(
        com.jervis.contracts.o365_gateway.ReadChatRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReadChatMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.ChatMessage> sendChatMessage(
        com.jervis.contracts.o365_gateway.SendChatMessageRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendChatMessageMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * === V5b - Teams teams / channels =========================================
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.ListTeamsResponse> listTeams(
        com.jervis.contracts.o365_gateway.ListTeamsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListTeamsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.ListChannelsResponse> listChannels(
        com.jervis.contracts.o365_gateway.ListChannelsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListChannelsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.ListChannelMessagesResponse> readChannel(
        com.jervis.contracts.o365_gateway.ReadChannelRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReadChannelMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.ChatMessage> sendChannelMessage(
        com.jervis.contracts.o365_gateway.SendChannelMessageRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendChannelMessageMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * === V5c - Mail (Outlook) =================================================
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.ListMailResponse> listMail(
        com.jervis.contracts.o365_gateway.ListMailRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListMailMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.MailMessage> readMail(
        com.jervis.contracts.o365_gateway.ReadMailRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReadMailMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.SendMailAck> sendMail(
        com.jervis.contracts.o365_gateway.SendMailRpcRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendMailMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * === V5d - Calendar =======================================================
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.ListCalendarEventsResponse> listCalendarEvents(
        com.jervis.contracts.o365_gateway.ListCalendarEventsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListCalendarEventsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.o365_gateway.CalendarEvent> createCalendarEvent(
        com.jervis.contracts.o365_gateway.CreateCalendarEventRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateCalendarEventMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_REQUEST = 0;
  private static final int METHODID_REQUEST_BYTES = 1;
  private static final int METHODID_LIST_CHATS = 2;
  private static final int METHODID_READ_CHAT = 3;
  private static final int METHODID_SEND_CHAT_MESSAGE = 4;
  private static final int METHODID_LIST_TEAMS = 5;
  private static final int METHODID_LIST_CHANNELS = 6;
  private static final int METHODID_READ_CHANNEL = 7;
  private static final int METHODID_SEND_CHANNEL_MESSAGE = 8;
  private static final int METHODID_LIST_MAIL = 9;
  private static final int METHODID_READ_MAIL = 10;
  private static final int METHODID_SEND_MAIL = 11;
  private static final int METHODID_LIST_CALENDAR_EVENTS = 12;
  private static final int METHODID_CREATE_CALENDAR_EVENT = 13;

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
        case METHODID_LIST_CHATS:
          serviceImpl.listChats((com.jervis.contracts.o365_gateway.ListChatsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChatsResponse>) responseObserver);
          break;
        case METHODID_READ_CHAT:
          serviceImpl.readChat((com.jervis.contracts.o365_gateway.ReadChatRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChatMessagesResponse>) responseObserver);
          break;
        case METHODID_SEND_CHAT_MESSAGE:
          serviceImpl.sendChatMessage((com.jervis.contracts.o365_gateway.SendChatMessageRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ChatMessage>) responseObserver);
          break;
        case METHODID_LIST_TEAMS:
          serviceImpl.listTeams((com.jervis.contracts.o365_gateway.ListTeamsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListTeamsResponse>) responseObserver);
          break;
        case METHODID_LIST_CHANNELS:
          serviceImpl.listChannels((com.jervis.contracts.o365_gateway.ListChannelsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChannelsResponse>) responseObserver);
          break;
        case METHODID_READ_CHANNEL:
          serviceImpl.readChannel((com.jervis.contracts.o365_gateway.ReadChannelRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListChannelMessagesResponse>) responseObserver);
          break;
        case METHODID_SEND_CHANNEL_MESSAGE:
          serviceImpl.sendChannelMessage((com.jervis.contracts.o365_gateway.SendChannelMessageRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ChatMessage>) responseObserver);
          break;
        case METHODID_LIST_MAIL:
          serviceImpl.listMail((com.jervis.contracts.o365_gateway.ListMailRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListMailResponse>) responseObserver);
          break;
        case METHODID_READ_MAIL:
          serviceImpl.readMail((com.jervis.contracts.o365_gateway.ReadMailRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.MailMessage>) responseObserver);
          break;
        case METHODID_SEND_MAIL:
          serviceImpl.sendMail((com.jervis.contracts.o365_gateway.SendMailRpcRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.SendMailAck>) responseObserver);
          break;
        case METHODID_LIST_CALENDAR_EVENTS:
          serviceImpl.listCalendarEvents((com.jervis.contracts.o365_gateway.ListCalendarEventsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.ListCalendarEventsResponse>) responseObserver);
          break;
        case METHODID_CREATE_CALENDAR_EVENT:
          serviceImpl.createCalendarEvent((com.jervis.contracts.o365_gateway.CreateCalendarEventRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.o365_gateway.CalendarEvent>) responseObserver);
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
        .addMethod(
          getListChatsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.ListChatsRequest,
              com.jervis.contracts.o365_gateway.ListChatsResponse>(
                service, METHODID_LIST_CHATS)))
        .addMethod(
          getReadChatMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.ReadChatRequest,
              com.jervis.contracts.o365_gateway.ListChatMessagesResponse>(
                service, METHODID_READ_CHAT)))
        .addMethod(
          getSendChatMessageMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.SendChatMessageRequest,
              com.jervis.contracts.o365_gateway.ChatMessage>(
                service, METHODID_SEND_CHAT_MESSAGE)))
        .addMethod(
          getListTeamsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.ListTeamsRequest,
              com.jervis.contracts.o365_gateway.ListTeamsResponse>(
                service, METHODID_LIST_TEAMS)))
        .addMethod(
          getListChannelsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.ListChannelsRequest,
              com.jervis.contracts.o365_gateway.ListChannelsResponse>(
                service, METHODID_LIST_CHANNELS)))
        .addMethod(
          getReadChannelMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.ReadChannelRequest,
              com.jervis.contracts.o365_gateway.ListChannelMessagesResponse>(
                service, METHODID_READ_CHANNEL)))
        .addMethod(
          getSendChannelMessageMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.SendChannelMessageRequest,
              com.jervis.contracts.o365_gateway.ChatMessage>(
                service, METHODID_SEND_CHANNEL_MESSAGE)))
        .addMethod(
          getListMailMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.ListMailRequest,
              com.jervis.contracts.o365_gateway.ListMailResponse>(
                service, METHODID_LIST_MAIL)))
        .addMethod(
          getReadMailMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.ReadMailRequest,
              com.jervis.contracts.o365_gateway.MailMessage>(
                service, METHODID_READ_MAIL)))
        .addMethod(
          getSendMailMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.SendMailRpcRequest,
              com.jervis.contracts.o365_gateway.SendMailAck>(
                service, METHODID_SEND_MAIL)))
        .addMethod(
          getListCalendarEventsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.ListCalendarEventsRequest,
              com.jervis.contracts.o365_gateway.ListCalendarEventsResponse>(
                service, METHODID_LIST_CALENDAR_EVENTS)))
        .addMethod(
          getCreateCalendarEventMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.o365_gateway.CreateCalendarEventRequest,
              com.jervis.contracts.o365_gateway.CalendarEvent>(
                service, METHODID_CREATE_CALENDAR_EVENT)))
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
              .addMethod(getListChatsMethod())
              .addMethod(getReadChatMethod())
              .addMethod(getSendChatMessageMethod())
              .addMethod(getListTeamsMethod())
              .addMethod(getListChannelsMethod())
              .addMethod(getReadChannelMethod())
              .addMethod(getSendChannelMessageMethod())
              .addMethod(getListMailMethod())
              .addMethod(getReadMailMethod())
              .addMethod(getSendMailMethod())
              .addMethod(getListCalendarEventsMethod())
              .addMethod(getCreateCalendarEventMethod())
              .build();
        }
      }
    }
    return result;
  }
}
