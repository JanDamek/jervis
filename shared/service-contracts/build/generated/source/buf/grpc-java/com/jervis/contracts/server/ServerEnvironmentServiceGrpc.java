package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerEnvironmentService covers the full `environments` CRUD +
 * provisioning surface used by MCP and orchestrator tools. Responses
 * mirror com.jervis.dto.environment.EnvironmentDto / EnvironmentStatusDto /
 * ComponentTemplateDto 1:1 — no JSON passthrough on the wire.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerEnvironmentServiceGrpc {

  private ServerEnvironmentServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerEnvironmentService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListEnvironmentsRequest,
      com.jervis.contracts.server.EnvironmentList> getListEnvironmentsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListEnvironments",
      requestType = com.jervis.contracts.server.ListEnvironmentsRequest.class,
      responseType = com.jervis.contracts.server.EnvironmentList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListEnvironmentsRequest,
      com.jervis.contracts.server.EnvironmentList> getListEnvironmentsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListEnvironmentsRequest, com.jervis.contracts.server.EnvironmentList> getListEnvironmentsMethod;
    if ((getListEnvironmentsMethod = ServerEnvironmentServiceGrpc.getListEnvironmentsMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getListEnvironmentsMethod = ServerEnvironmentServiceGrpc.getListEnvironmentsMethod) == null) {
          ServerEnvironmentServiceGrpc.getListEnvironmentsMethod = getListEnvironmentsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListEnvironmentsRequest, com.jervis.contracts.server.EnvironmentList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListEnvironments"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListEnvironmentsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.EnvironmentList.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("ListEnvironments"))
              .build();
        }
      }
    }
    return getListEnvironmentsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetEnvironmentRequest,
      com.jervis.contracts.server.Environment> getGetEnvironmentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetEnvironment",
      requestType = com.jervis.contracts.server.GetEnvironmentRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetEnvironmentRequest,
      com.jervis.contracts.server.Environment> getGetEnvironmentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetEnvironmentRequest, com.jervis.contracts.server.Environment> getGetEnvironmentMethod;
    if ((getGetEnvironmentMethod = ServerEnvironmentServiceGrpc.getGetEnvironmentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getGetEnvironmentMethod = ServerEnvironmentServiceGrpc.getGetEnvironmentMethod) == null) {
          ServerEnvironmentServiceGrpc.getGetEnvironmentMethod = getGetEnvironmentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetEnvironmentRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetEnvironment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetEnvironmentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("GetEnvironment"))
              .build();
        }
      }
    }
    return getGetEnvironmentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateEnvironmentRequest,
      com.jervis.contracts.server.Environment> getCreateEnvironmentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateEnvironment",
      requestType = com.jervis.contracts.server.CreateEnvironmentRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateEnvironmentRequest,
      com.jervis.contracts.server.Environment> getCreateEnvironmentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateEnvironmentRequest, com.jervis.contracts.server.Environment> getCreateEnvironmentMethod;
    if ((getCreateEnvironmentMethod = ServerEnvironmentServiceGrpc.getCreateEnvironmentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getCreateEnvironmentMethod = ServerEnvironmentServiceGrpc.getCreateEnvironmentMethod) == null) {
          ServerEnvironmentServiceGrpc.getCreateEnvironmentMethod = getCreateEnvironmentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateEnvironmentRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateEnvironment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateEnvironmentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("CreateEnvironment"))
              .build();
        }
      }
    }
    return getCreateEnvironmentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.DeleteEnvironmentRequest,
      com.jervis.contracts.server.DeleteEnvironmentResponse> getDeleteEnvironmentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteEnvironment",
      requestType = com.jervis.contracts.server.DeleteEnvironmentRequest.class,
      responseType = com.jervis.contracts.server.DeleteEnvironmentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.DeleteEnvironmentRequest,
      com.jervis.contracts.server.DeleteEnvironmentResponse> getDeleteEnvironmentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.DeleteEnvironmentRequest, com.jervis.contracts.server.DeleteEnvironmentResponse> getDeleteEnvironmentMethod;
    if ((getDeleteEnvironmentMethod = ServerEnvironmentServiceGrpc.getDeleteEnvironmentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getDeleteEnvironmentMethod = ServerEnvironmentServiceGrpc.getDeleteEnvironmentMethod) == null) {
          ServerEnvironmentServiceGrpc.getDeleteEnvironmentMethod = getDeleteEnvironmentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.DeleteEnvironmentRequest, com.jervis.contracts.server.DeleteEnvironmentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteEnvironment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.DeleteEnvironmentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.DeleteEnvironmentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("DeleteEnvironment"))
              .build();
        }
      }
    }
    return getDeleteEnvironmentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.AddComponentRequest,
      com.jervis.contracts.server.Environment> getAddComponentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AddComponent",
      requestType = com.jervis.contracts.server.AddComponentRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.AddComponentRequest,
      com.jervis.contracts.server.Environment> getAddComponentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.AddComponentRequest, com.jervis.contracts.server.Environment> getAddComponentMethod;
    if ((getAddComponentMethod = ServerEnvironmentServiceGrpc.getAddComponentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getAddComponentMethod = ServerEnvironmentServiceGrpc.getAddComponentMethod) == null) {
          ServerEnvironmentServiceGrpc.getAddComponentMethod = getAddComponentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.AddComponentRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AddComponent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AddComponentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("AddComponent"))
              .build();
        }
      }
    }
    return getAddComponentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ConfigureComponentRequest,
      com.jervis.contracts.server.Environment> getConfigureComponentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ConfigureComponent",
      requestType = com.jervis.contracts.server.ConfigureComponentRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ConfigureComponentRequest,
      com.jervis.contracts.server.Environment> getConfigureComponentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ConfigureComponentRequest, com.jervis.contracts.server.Environment> getConfigureComponentMethod;
    if ((getConfigureComponentMethod = ServerEnvironmentServiceGrpc.getConfigureComponentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getConfigureComponentMethod = ServerEnvironmentServiceGrpc.getConfigureComponentMethod) == null) {
          ServerEnvironmentServiceGrpc.getConfigureComponentMethod = getConfigureComponentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ConfigureComponentRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ConfigureComponent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ConfigureComponentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("ConfigureComponent"))
              .build();
        }
      }
    }
    return getConfigureComponentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.Environment> getDeployEnvironmentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeployEnvironment",
      requestType = com.jervis.contracts.server.EnvironmentIdRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.Environment> getDeployEnvironmentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.Environment> getDeployEnvironmentMethod;
    if ((getDeployEnvironmentMethod = ServerEnvironmentServiceGrpc.getDeployEnvironmentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getDeployEnvironmentMethod = ServerEnvironmentServiceGrpc.getDeployEnvironmentMethod) == null) {
          ServerEnvironmentServiceGrpc.getDeployEnvironmentMethod = getDeployEnvironmentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeployEnvironment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.EnvironmentIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("DeployEnvironment"))
              .build();
        }
      }
    }
    return getDeployEnvironmentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.Environment> getStopEnvironmentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StopEnvironment",
      requestType = com.jervis.contracts.server.EnvironmentIdRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.Environment> getStopEnvironmentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.Environment> getStopEnvironmentMethod;
    if ((getStopEnvironmentMethod = ServerEnvironmentServiceGrpc.getStopEnvironmentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getStopEnvironmentMethod = ServerEnvironmentServiceGrpc.getStopEnvironmentMethod) == null) {
          ServerEnvironmentServiceGrpc.getStopEnvironmentMethod = getStopEnvironmentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StopEnvironment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.EnvironmentIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("StopEnvironment"))
              .build();
        }
      }
    }
    return getStopEnvironmentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.Environment> getSyncEnvironmentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SyncEnvironment",
      requestType = com.jervis.contracts.server.EnvironmentIdRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.Environment> getSyncEnvironmentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.Environment> getSyncEnvironmentMethod;
    if ((getSyncEnvironmentMethod = ServerEnvironmentServiceGrpc.getSyncEnvironmentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getSyncEnvironmentMethod = ServerEnvironmentServiceGrpc.getSyncEnvironmentMethod) == null) {
          ServerEnvironmentServiceGrpc.getSyncEnvironmentMethod = getSyncEnvironmentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SyncEnvironment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.EnvironmentIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("SyncEnvironment"))
              .build();
        }
      }
    }
    return getSyncEnvironmentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.EnvironmentStatus> getGetEnvironmentStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetEnvironmentStatus",
      requestType = com.jervis.contracts.server.EnvironmentIdRequest.class,
      responseType = com.jervis.contracts.server.EnvironmentStatus.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.EnvironmentStatus> getGetEnvironmentStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.EnvironmentStatus> getGetEnvironmentStatusMethod;
    if ((getGetEnvironmentStatusMethod = ServerEnvironmentServiceGrpc.getGetEnvironmentStatusMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getGetEnvironmentStatusMethod = ServerEnvironmentServiceGrpc.getGetEnvironmentStatusMethod) == null) {
          ServerEnvironmentServiceGrpc.getGetEnvironmentStatusMethod = getGetEnvironmentStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.EnvironmentStatus>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetEnvironmentStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.EnvironmentIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.EnvironmentStatus.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("GetEnvironmentStatus"))
              .build();
        }
      }
    }
    return getGetEnvironmentStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CloneEnvironmentRequest,
      com.jervis.contracts.server.Environment> getCloneEnvironmentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CloneEnvironment",
      requestType = com.jervis.contracts.server.CloneEnvironmentRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CloneEnvironmentRequest,
      com.jervis.contracts.server.Environment> getCloneEnvironmentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CloneEnvironmentRequest, com.jervis.contracts.server.Environment> getCloneEnvironmentMethod;
    if ((getCloneEnvironmentMethod = ServerEnvironmentServiceGrpc.getCloneEnvironmentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getCloneEnvironmentMethod = ServerEnvironmentServiceGrpc.getCloneEnvironmentMethod) == null) {
          ServerEnvironmentServiceGrpc.getCloneEnvironmentMethod = getCloneEnvironmentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CloneEnvironmentRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CloneEnvironment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CloneEnvironmentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("CloneEnvironment"))
              .build();
        }
      }
    }
    return getCloneEnvironmentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.AddPropertyMappingRequest,
      com.jervis.contracts.server.Environment> getAddPropertyMappingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AddPropertyMapping",
      requestType = com.jervis.contracts.server.AddPropertyMappingRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.AddPropertyMappingRequest,
      com.jervis.contracts.server.Environment> getAddPropertyMappingMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.AddPropertyMappingRequest, com.jervis.contracts.server.Environment> getAddPropertyMappingMethod;
    if ((getAddPropertyMappingMethod = ServerEnvironmentServiceGrpc.getAddPropertyMappingMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getAddPropertyMappingMethod = ServerEnvironmentServiceGrpc.getAddPropertyMappingMethod) == null) {
          ServerEnvironmentServiceGrpc.getAddPropertyMappingMethod = getAddPropertyMappingMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.AddPropertyMappingRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AddPropertyMapping"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AddPropertyMappingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("AddPropertyMapping"))
              .build();
        }
      }
    }
    return getAddPropertyMappingMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse> getAutoSuggestPropertyMappingsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AutoSuggestPropertyMappings",
      requestType = com.jervis.contracts.server.EnvironmentIdRequest.class,
      responseType = com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse> getAutoSuggestPropertyMappingsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse> getAutoSuggestPropertyMappingsMethod;
    if ((getAutoSuggestPropertyMappingsMethod = ServerEnvironmentServiceGrpc.getAutoSuggestPropertyMappingsMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getAutoSuggestPropertyMappingsMethod = ServerEnvironmentServiceGrpc.getAutoSuggestPropertyMappingsMethod) == null) {
          ServerEnvironmentServiceGrpc.getAutoSuggestPropertyMappingsMethod = getAutoSuggestPropertyMappingsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AutoSuggestPropertyMappings"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.EnvironmentIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("AutoSuggestPropertyMappings"))
              .build();
        }
      }
    }
    return getAutoSuggestPropertyMappingsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.DiscoverNamespaceRequest,
      com.jervis.contracts.server.Environment> getDiscoverNamespaceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DiscoverNamespace",
      requestType = com.jervis.contracts.server.DiscoverNamespaceRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.DiscoverNamespaceRequest,
      com.jervis.contracts.server.Environment> getDiscoverNamespaceMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.DiscoverNamespaceRequest, com.jervis.contracts.server.Environment> getDiscoverNamespaceMethod;
    if ((getDiscoverNamespaceMethod = ServerEnvironmentServiceGrpc.getDiscoverNamespaceMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getDiscoverNamespaceMethod = ServerEnvironmentServiceGrpc.getDiscoverNamespaceMethod) == null) {
          ServerEnvironmentServiceGrpc.getDiscoverNamespaceMethod = getDiscoverNamespaceMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.DiscoverNamespaceRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DiscoverNamespace"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.DiscoverNamespaceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("DiscoverNamespace"))
              .build();
        }
      }
    }
    return getDiscoverNamespaceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ReplicateEnvironmentRequest,
      com.jervis.contracts.server.Environment> getReplicateEnvironmentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReplicateEnvironment",
      requestType = com.jervis.contracts.server.ReplicateEnvironmentRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ReplicateEnvironmentRequest,
      com.jervis.contracts.server.Environment> getReplicateEnvironmentMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ReplicateEnvironmentRequest, com.jervis.contracts.server.Environment> getReplicateEnvironmentMethod;
    if ((getReplicateEnvironmentMethod = ServerEnvironmentServiceGrpc.getReplicateEnvironmentMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getReplicateEnvironmentMethod = ServerEnvironmentServiceGrpc.getReplicateEnvironmentMethod) == null) {
          ServerEnvironmentServiceGrpc.getReplicateEnvironmentMethod = getReplicateEnvironmentMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ReplicateEnvironmentRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReplicateEnvironment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ReplicateEnvironmentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("ReplicateEnvironment"))
              .build();
        }
      }
    }
    return getReplicateEnvironmentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.Environment> getSyncFromK8sMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SyncFromK8s",
      requestType = com.jervis.contracts.server.EnvironmentIdRequest.class,
      responseType = com.jervis.contracts.server.Environment.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest,
      com.jervis.contracts.server.Environment> getSyncFromK8sMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.Environment> getSyncFromK8sMethod;
    if ((getSyncFromK8sMethod = ServerEnvironmentServiceGrpc.getSyncFromK8sMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getSyncFromK8sMethod = ServerEnvironmentServiceGrpc.getSyncFromK8sMethod) == null) {
          ServerEnvironmentServiceGrpc.getSyncFromK8sMethod = getSyncFromK8sMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.EnvironmentIdRequest, com.jervis.contracts.server.Environment>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SyncFromK8s"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.EnvironmentIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.Environment.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("SyncFromK8s"))
              .build();
        }
      }
    }
    return getSyncFromK8sMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.ListComponentTemplatesRequest,
      com.jervis.contracts.server.ComponentTemplateList> getListComponentTemplatesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListComponentTemplates",
      requestType = com.jervis.contracts.server.ListComponentTemplatesRequest.class,
      responseType = com.jervis.contracts.server.ComponentTemplateList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.ListComponentTemplatesRequest,
      com.jervis.contracts.server.ComponentTemplateList> getListComponentTemplatesMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.ListComponentTemplatesRequest, com.jervis.contracts.server.ComponentTemplateList> getListComponentTemplatesMethod;
    if ((getListComponentTemplatesMethod = ServerEnvironmentServiceGrpc.getListComponentTemplatesMethod) == null) {
      synchronized (ServerEnvironmentServiceGrpc.class) {
        if ((getListComponentTemplatesMethod = ServerEnvironmentServiceGrpc.getListComponentTemplatesMethod) == null) {
          ServerEnvironmentServiceGrpc.getListComponentTemplatesMethod = getListComponentTemplatesMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.ListComponentTemplatesRequest, com.jervis.contracts.server.ComponentTemplateList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListComponentTemplates"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ListComponentTemplatesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.ComponentTemplateList.getDefaultInstance()))
              .setSchemaDescriptor(new ServerEnvironmentServiceMethodDescriptorSupplier("ListComponentTemplates"))
              .build();
        }
      }
    }
    return getListComponentTemplatesMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerEnvironmentServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentServiceStub>() {
        @java.lang.Override
        public ServerEnvironmentServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerEnvironmentServiceStub(channel, callOptions);
        }
      };
    return ServerEnvironmentServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerEnvironmentServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerEnvironmentServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerEnvironmentServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerEnvironmentServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerEnvironmentServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentServiceBlockingStub>() {
        @java.lang.Override
        public ServerEnvironmentServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerEnvironmentServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerEnvironmentServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerEnvironmentServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerEnvironmentServiceFutureStub>() {
        @java.lang.Override
        public ServerEnvironmentServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerEnvironmentServiceFutureStub(channel, callOptions);
        }
      };
    return ServerEnvironmentServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerEnvironmentService covers the full `environments` CRUD +
   * provisioning surface used by MCP and orchestrator tools. Responses
   * mirror com.jervis.dto.environment.EnvironmentDto / EnvironmentStatusDto /
   * ComponentTemplateDto 1:1 — no JSON passthrough on the wire.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void listEnvironments(com.jervis.contracts.server.ListEnvironmentsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.EnvironmentList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListEnvironmentsMethod(), responseObserver);
    }

    /**
     */
    default void getEnvironment(com.jervis.contracts.server.GetEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetEnvironmentMethod(), responseObserver);
    }

    /**
     */
    default void createEnvironment(com.jervis.contracts.server.CreateEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateEnvironmentMethod(), responseObserver);
    }

    /**
     */
    default void deleteEnvironment(com.jervis.contracts.server.DeleteEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.DeleteEnvironmentResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteEnvironmentMethod(), responseObserver);
    }

    /**
     */
    default void addComponent(com.jervis.contracts.server.AddComponentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAddComponentMethod(), responseObserver);
    }

    /**
     */
    default void configureComponent(com.jervis.contracts.server.ConfigureComponentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getConfigureComponentMethod(), responseObserver);
    }

    /**
     */
    default void deployEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeployEnvironmentMethod(), responseObserver);
    }

    /**
     */
    default void stopEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStopEnvironmentMethod(), responseObserver);
    }

    /**
     */
    default void syncEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSyncEnvironmentMethod(), responseObserver);
    }

    /**
     */
    default void getEnvironmentStatus(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.EnvironmentStatus> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetEnvironmentStatusMethod(), responseObserver);
    }

    /**
     */
    default void cloneEnvironment(com.jervis.contracts.server.CloneEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCloneEnvironmentMethod(), responseObserver);
    }

    /**
     */
    default void addPropertyMapping(com.jervis.contracts.server.AddPropertyMappingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAddPropertyMappingMethod(), responseObserver);
    }

    /**
     */
    default void autoSuggestPropertyMappings(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAutoSuggestPropertyMappingsMethod(), responseObserver);
    }

    /**
     */
    default void discoverNamespace(com.jervis.contracts.server.DiscoverNamespaceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDiscoverNamespaceMethod(), responseObserver);
    }

    /**
     */
    default void replicateEnvironment(com.jervis.contracts.server.ReplicateEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReplicateEnvironmentMethod(), responseObserver);
    }

    /**
     */
    default void syncFromK8s(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSyncFromK8sMethod(), responseObserver);
    }

    /**
     */
    default void listComponentTemplates(com.jervis.contracts.server.ListComponentTemplatesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ComponentTemplateList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListComponentTemplatesMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerEnvironmentService.
   * <pre>
   * ServerEnvironmentService covers the full `environments` CRUD +
   * provisioning surface used by MCP and orchestrator tools. Responses
   * mirror com.jervis.dto.environment.EnvironmentDto / EnvironmentStatusDto /
   * ComponentTemplateDto 1:1 — no JSON passthrough on the wire.
   * </pre>
   */
  public static abstract class ServerEnvironmentServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerEnvironmentServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerEnvironmentService.
   * <pre>
   * ServerEnvironmentService covers the full `environments` CRUD +
   * provisioning surface used by MCP and orchestrator tools. Responses
   * mirror com.jervis.dto.environment.EnvironmentDto / EnvironmentStatusDto /
   * ComponentTemplateDto 1:1 — no JSON passthrough on the wire.
   * </pre>
   */
  public static final class ServerEnvironmentServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerEnvironmentServiceStub> {
    private ServerEnvironmentServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerEnvironmentServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerEnvironmentServiceStub(channel, callOptions);
    }

    /**
     */
    public void listEnvironments(com.jervis.contracts.server.ListEnvironmentsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.EnvironmentList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListEnvironmentsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getEnvironment(com.jervis.contracts.server.GetEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetEnvironmentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createEnvironment(com.jervis.contracts.server.CreateEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateEnvironmentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void deleteEnvironment(com.jervis.contracts.server.DeleteEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.DeleteEnvironmentResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteEnvironmentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void addComponent(com.jervis.contracts.server.AddComponentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAddComponentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void configureComponent(com.jervis.contracts.server.ConfigureComponentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getConfigureComponentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void deployEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeployEnvironmentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void stopEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStopEnvironmentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void syncEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSyncEnvironmentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getEnvironmentStatus(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.EnvironmentStatus> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetEnvironmentStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void cloneEnvironment(com.jervis.contracts.server.CloneEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCloneEnvironmentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void addPropertyMapping(com.jervis.contracts.server.AddPropertyMappingRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAddPropertyMappingMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void autoSuggestPropertyMappings(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAutoSuggestPropertyMappingsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void discoverNamespace(com.jervis.contracts.server.DiscoverNamespaceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDiscoverNamespaceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void replicateEnvironment(com.jervis.contracts.server.ReplicateEnvironmentRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReplicateEnvironmentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void syncFromK8s(com.jervis.contracts.server.EnvironmentIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSyncFromK8sMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listComponentTemplates(com.jervis.contracts.server.ListComponentTemplatesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.ComponentTemplateList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListComponentTemplatesMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerEnvironmentService.
   * <pre>
   * ServerEnvironmentService covers the full `environments` CRUD +
   * provisioning surface used by MCP and orchestrator tools. Responses
   * mirror com.jervis.dto.environment.EnvironmentDto / EnvironmentStatusDto /
   * ComponentTemplateDto 1:1 — no JSON passthrough on the wire.
   * </pre>
   */
  public static final class ServerEnvironmentServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerEnvironmentServiceBlockingV2Stub> {
    private ServerEnvironmentServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerEnvironmentServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerEnvironmentServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.EnvironmentList listEnvironments(com.jervis.contracts.server.ListEnvironmentsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListEnvironmentsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment getEnvironment(com.jervis.contracts.server.GetEnvironmentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment createEnvironment(com.jervis.contracts.server.CreateEnvironmentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.DeleteEnvironmentResponse deleteEnvironment(com.jervis.contracts.server.DeleteEnvironmentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment addComponent(com.jervis.contracts.server.AddComponentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAddComponentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment configureComponent(com.jervis.contracts.server.ConfigureComponentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getConfigureComponentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment deployEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeployEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment stopEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStopEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment syncEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSyncEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.EnvironmentStatus getEnvironmentStatus(com.jervis.contracts.server.EnvironmentIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetEnvironmentStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment cloneEnvironment(com.jervis.contracts.server.CloneEnvironmentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCloneEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment addPropertyMapping(com.jervis.contracts.server.AddPropertyMappingRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAddPropertyMappingMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse autoSuggestPropertyMappings(com.jervis.contracts.server.EnvironmentIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getAutoSuggestPropertyMappingsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment discoverNamespace(com.jervis.contracts.server.DiscoverNamespaceRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDiscoverNamespaceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment replicateEnvironment(com.jervis.contracts.server.ReplicateEnvironmentRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReplicateEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment syncFromK8s(com.jervis.contracts.server.EnvironmentIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSyncFromK8sMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ComponentTemplateList listComponentTemplates(com.jervis.contracts.server.ListComponentTemplatesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListComponentTemplatesMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerEnvironmentService.
   * <pre>
   * ServerEnvironmentService covers the full `environments` CRUD +
   * provisioning surface used by MCP and orchestrator tools. Responses
   * mirror com.jervis.dto.environment.EnvironmentDto / EnvironmentStatusDto /
   * ComponentTemplateDto 1:1 — no JSON passthrough on the wire.
   * </pre>
   */
  public static final class ServerEnvironmentServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerEnvironmentServiceBlockingStub> {
    private ServerEnvironmentServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerEnvironmentServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerEnvironmentServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.EnvironmentList listEnvironments(com.jervis.contracts.server.ListEnvironmentsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListEnvironmentsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment getEnvironment(com.jervis.contracts.server.GetEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment createEnvironment(com.jervis.contracts.server.CreateEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.DeleteEnvironmentResponse deleteEnvironment(com.jervis.contracts.server.DeleteEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment addComponent(com.jervis.contracts.server.AddComponentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAddComponentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment configureComponent(com.jervis.contracts.server.ConfigureComponentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getConfigureComponentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment deployEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeployEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment stopEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStopEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment syncEnvironment(com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSyncEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.EnvironmentStatus getEnvironmentStatus(com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetEnvironmentStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment cloneEnvironment(com.jervis.contracts.server.CloneEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCloneEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment addPropertyMapping(com.jervis.contracts.server.AddPropertyMappingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAddPropertyMappingMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse autoSuggestPropertyMappings(com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAutoSuggestPropertyMappingsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment discoverNamespace(com.jervis.contracts.server.DiscoverNamespaceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDiscoverNamespaceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment replicateEnvironment(com.jervis.contracts.server.ReplicateEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReplicateEnvironmentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.Environment syncFromK8s(com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSyncFromK8sMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.ComponentTemplateList listComponentTemplates(com.jervis.contracts.server.ListComponentTemplatesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListComponentTemplatesMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerEnvironmentService.
   * <pre>
   * ServerEnvironmentService covers the full `environments` CRUD +
   * provisioning surface used by MCP and orchestrator tools. Responses
   * mirror com.jervis.dto.environment.EnvironmentDto / EnvironmentStatusDto /
   * ComponentTemplateDto 1:1 — no JSON passthrough on the wire.
   * </pre>
   */
  public static final class ServerEnvironmentServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerEnvironmentServiceFutureStub> {
    private ServerEnvironmentServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerEnvironmentServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerEnvironmentServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.EnvironmentList> listEnvironments(
        com.jervis.contracts.server.ListEnvironmentsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListEnvironmentsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> getEnvironment(
        com.jervis.contracts.server.GetEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetEnvironmentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> createEnvironment(
        com.jervis.contracts.server.CreateEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateEnvironmentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.DeleteEnvironmentResponse> deleteEnvironment(
        com.jervis.contracts.server.DeleteEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteEnvironmentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> addComponent(
        com.jervis.contracts.server.AddComponentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAddComponentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> configureComponent(
        com.jervis.contracts.server.ConfigureComponentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getConfigureComponentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> deployEnvironment(
        com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeployEnvironmentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> stopEnvironment(
        com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStopEnvironmentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> syncEnvironment(
        com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSyncEnvironmentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.EnvironmentStatus> getEnvironmentStatus(
        com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetEnvironmentStatusMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> cloneEnvironment(
        com.jervis.contracts.server.CloneEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCloneEnvironmentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> addPropertyMapping(
        com.jervis.contracts.server.AddPropertyMappingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAddPropertyMappingMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse> autoSuggestPropertyMappings(
        com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAutoSuggestPropertyMappingsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> discoverNamespace(
        com.jervis.contracts.server.DiscoverNamespaceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDiscoverNamespaceMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> replicateEnvironment(
        com.jervis.contracts.server.ReplicateEnvironmentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReplicateEnvironmentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.Environment> syncFromK8s(
        com.jervis.contracts.server.EnvironmentIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSyncFromK8sMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.ComponentTemplateList> listComponentTemplates(
        com.jervis.contracts.server.ListComponentTemplatesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListComponentTemplatesMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LIST_ENVIRONMENTS = 0;
  private static final int METHODID_GET_ENVIRONMENT = 1;
  private static final int METHODID_CREATE_ENVIRONMENT = 2;
  private static final int METHODID_DELETE_ENVIRONMENT = 3;
  private static final int METHODID_ADD_COMPONENT = 4;
  private static final int METHODID_CONFIGURE_COMPONENT = 5;
  private static final int METHODID_DEPLOY_ENVIRONMENT = 6;
  private static final int METHODID_STOP_ENVIRONMENT = 7;
  private static final int METHODID_SYNC_ENVIRONMENT = 8;
  private static final int METHODID_GET_ENVIRONMENT_STATUS = 9;
  private static final int METHODID_CLONE_ENVIRONMENT = 10;
  private static final int METHODID_ADD_PROPERTY_MAPPING = 11;
  private static final int METHODID_AUTO_SUGGEST_PROPERTY_MAPPINGS = 12;
  private static final int METHODID_DISCOVER_NAMESPACE = 13;
  private static final int METHODID_REPLICATE_ENVIRONMENT = 14;
  private static final int METHODID_SYNC_FROM_K8S = 15;
  private static final int METHODID_LIST_COMPONENT_TEMPLATES = 16;

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
        case METHODID_LIST_ENVIRONMENTS:
          serviceImpl.listEnvironments((com.jervis.contracts.server.ListEnvironmentsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.EnvironmentList>) responseObserver);
          break;
        case METHODID_GET_ENVIRONMENT:
          serviceImpl.getEnvironment((com.jervis.contracts.server.GetEnvironmentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_CREATE_ENVIRONMENT:
          serviceImpl.createEnvironment((com.jervis.contracts.server.CreateEnvironmentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_DELETE_ENVIRONMENT:
          serviceImpl.deleteEnvironment((com.jervis.contracts.server.DeleteEnvironmentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.DeleteEnvironmentResponse>) responseObserver);
          break;
        case METHODID_ADD_COMPONENT:
          serviceImpl.addComponent((com.jervis.contracts.server.AddComponentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_CONFIGURE_COMPONENT:
          serviceImpl.configureComponent((com.jervis.contracts.server.ConfigureComponentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_DEPLOY_ENVIRONMENT:
          serviceImpl.deployEnvironment((com.jervis.contracts.server.EnvironmentIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_STOP_ENVIRONMENT:
          serviceImpl.stopEnvironment((com.jervis.contracts.server.EnvironmentIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_SYNC_ENVIRONMENT:
          serviceImpl.syncEnvironment((com.jervis.contracts.server.EnvironmentIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_GET_ENVIRONMENT_STATUS:
          serviceImpl.getEnvironmentStatus((com.jervis.contracts.server.EnvironmentIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.EnvironmentStatus>) responseObserver);
          break;
        case METHODID_CLONE_ENVIRONMENT:
          serviceImpl.cloneEnvironment((com.jervis.contracts.server.CloneEnvironmentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_ADD_PROPERTY_MAPPING:
          serviceImpl.addPropertyMapping((com.jervis.contracts.server.AddPropertyMappingRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_AUTO_SUGGEST_PROPERTY_MAPPINGS:
          serviceImpl.autoSuggestPropertyMappings((com.jervis.contracts.server.EnvironmentIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse>) responseObserver);
          break;
        case METHODID_DISCOVER_NAMESPACE:
          serviceImpl.discoverNamespace((com.jervis.contracts.server.DiscoverNamespaceRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_REPLICATE_ENVIRONMENT:
          serviceImpl.replicateEnvironment((com.jervis.contracts.server.ReplicateEnvironmentRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_SYNC_FROM_K8S:
          serviceImpl.syncFromK8s((com.jervis.contracts.server.EnvironmentIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.Environment>) responseObserver);
          break;
        case METHODID_LIST_COMPONENT_TEMPLATES:
          serviceImpl.listComponentTemplates((com.jervis.contracts.server.ListComponentTemplatesRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.ComponentTemplateList>) responseObserver);
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
          getListEnvironmentsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListEnvironmentsRequest,
              com.jervis.contracts.server.EnvironmentList>(
                service, METHODID_LIST_ENVIRONMENTS)))
        .addMethod(
          getGetEnvironmentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetEnvironmentRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_GET_ENVIRONMENT)))
        .addMethod(
          getCreateEnvironmentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateEnvironmentRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_CREATE_ENVIRONMENT)))
        .addMethod(
          getDeleteEnvironmentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.DeleteEnvironmentRequest,
              com.jervis.contracts.server.DeleteEnvironmentResponse>(
                service, METHODID_DELETE_ENVIRONMENT)))
        .addMethod(
          getAddComponentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.AddComponentRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_ADD_COMPONENT)))
        .addMethod(
          getConfigureComponentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ConfigureComponentRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_CONFIGURE_COMPONENT)))
        .addMethod(
          getDeployEnvironmentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.EnvironmentIdRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_DEPLOY_ENVIRONMENT)))
        .addMethod(
          getStopEnvironmentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.EnvironmentIdRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_STOP_ENVIRONMENT)))
        .addMethod(
          getSyncEnvironmentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.EnvironmentIdRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_SYNC_ENVIRONMENT)))
        .addMethod(
          getGetEnvironmentStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.EnvironmentIdRequest,
              com.jervis.contracts.server.EnvironmentStatus>(
                service, METHODID_GET_ENVIRONMENT_STATUS)))
        .addMethod(
          getCloneEnvironmentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CloneEnvironmentRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_CLONE_ENVIRONMENT)))
        .addMethod(
          getAddPropertyMappingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.AddPropertyMappingRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_ADD_PROPERTY_MAPPING)))
        .addMethod(
          getAutoSuggestPropertyMappingsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.EnvironmentIdRequest,
              com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse>(
                service, METHODID_AUTO_SUGGEST_PROPERTY_MAPPINGS)))
        .addMethod(
          getDiscoverNamespaceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.DiscoverNamespaceRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_DISCOVER_NAMESPACE)))
        .addMethod(
          getReplicateEnvironmentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ReplicateEnvironmentRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_REPLICATE_ENVIRONMENT)))
        .addMethod(
          getSyncFromK8sMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.EnvironmentIdRequest,
              com.jervis.contracts.server.Environment>(
                service, METHODID_SYNC_FROM_K8S)))
        .addMethod(
          getListComponentTemplatesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.ListComponentTemplatesRequest,
              com.jervis.contracts.server.ComponentTemplateList>(
                service, METHODID_LIST_COMPONENT_TEMPLATES)))
        .build();
  }

  private static abstract class ServerEnvironmentServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerEnvironmentServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerEnvironmentProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerEnvironmentService");
    }
  }

  private static final class ServerEnvironmentServiceFileDescriptorSupplier
      extends ServerEnvironmentServiceBaseDescriptorSupplier {
    ServerEnvironmentServiceFileDescriptorSupplier() {}
  }

  private static final class ServerEnvironmentServiceMethodDescriptorSupplier
      extends ServerEnvironmentServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerEnvironmentServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerEnvironmentServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerEnvironmentServiceFileDescriptorSupplier())
              .addMethod(getListEnvironmentsMethod())
              .addMethod(getGetEnvironmentMethod())
              .addMethod(getCreateEnvironmentMethod())
              .addMethod(getDeleteEnvironmentMethod())
              .addMethod(getAddComponentMethod())
              .addMethod(getConfigureComponentMethod())
              .addMethod(getDeployEnvironmentMethod())
              .addMethod(getStopEnvironmentMethod())
              .addMethod(getSyncEnvironmentMethod())
              .addMethod(getGetEnvironmentStatusMethod())
              .addMethod(getCloneEnvironmentMethod())
              .addMethod(getAddPropertyMappingMethod())
              .addMethod(getAutoSuggestPropertyMappingsMethod())
              .addMethod(getDiscoverNamespaceMethod())
              .addMethod(getReplicateEnvironmentMethod())
              .addMethod(getSyncFromK8sMethod())
              .addMethod(getListComponentTemplatesMethod())
              .build();
        }
      }
    }
    return result;
  }
}
