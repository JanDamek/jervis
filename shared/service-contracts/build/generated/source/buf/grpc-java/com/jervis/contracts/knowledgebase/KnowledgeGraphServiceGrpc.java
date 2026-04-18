package com.jervis.contracts.knowledgebase;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * KnowledgeGraphService — graph traversal, node lookup, alias resolution
 * and Thought Map RPCs. Writes live in ingest.proto / maintenance.proto.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class KnowledgeGraphServiceGrpc {

  private KnowledgeGraphServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.knowledgebase.KnowledgeGraphService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.TraversalRequest,
      com.jervis.contracts.knowledgebase.GraphNodeList> getTraverseMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Traverse",
      requestType = com.jervis.contracts.knowledgebase.TraversalRequest.class,
      responseType = com.jervis.contracts.knowledgebase.GraphNodeList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.TraversalRequest,
      com.jervis.contracts.knowledgebase.GraphNodeList> getTraverseMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.TraversalRequest, com.jervis.contracts.knowledgebase.GraphNodeList> getTraverseMethod;
    if ((getTraverseMethod = KnowledgeGraphServiceGrpc.getTraverseMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getTraverseMethod = KnowledgeGraphServiceGrpc.getTraverseMethod) == null) {
          KnowledgeGraphServiceGrpc.getTraverseMethod = getTraverseMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.TraversalRequest, com.jervis.contracts.knowledgebase.GraphNodeList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Traverse"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.TraversalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.GraphNodeList.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("Traverse"))
              .build();
        }
      }
    }
    return getTraverseMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GetNodeRequest,
      com.jervis.contracts.knowledgebase.GraphNode> getGetNodeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetNode",
      requestType = com.jervis.contracts.knowledgebase.GetNodeRequest.class,
      responseType = com.jervis.contracts.knowledgebase.GraphNode.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GetNodeRequest,
      com.jervis.contracts.knowledgebase.GraphNode> getGetNodeMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GetNodeRequest, com.jervis.contracts.knowledgebase.GraphNode> getGetNodeMethod;
    if ((getGetNodeMethod = KnowledgeGraphServiceGrpc.getGetNodeMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getGetNodeMethod = KnowledgeGraphServiceGrpc.getGetNodeMethod) == null) {
          KnowledgeGraphServiceGrpc.getGetNodeMethod = getGetNodeMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.GetNodeRequest, com.jervis.contracts.knowledgebase.GraphNode>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetNode"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.GetNodeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.GraphNode.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("GetNode"))
              .build();
        }
      }
    }
    return getGetNodeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.SearchNodesRequest,
      com.jervis.contracts.knowledgebase.GraphNodeList> getSearchNodesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SearchNodes",
      requestType = com.jervis.contracts.knowledgebase.SearchNodesRequest.class,
      responseType = com.jervis.contracts.knowledgebase.GraphNodeList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.SearchNodesRequest,
      com.jervis.contracts.knowledgebase.GraphNodeList> getSearchNodesMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.SearchNodesRequest, com.jervis.contracts.knowledgebase.GraphNodeList> getSearchNodesMethod;
    if ((getSearchNodesMethod = KnowledgeGraphServiceGrpc.getSearchNodesMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getSearchNodesMethod = KnowledgeGraphServiceGrpc.getSearchNodesMethod) == null) {
          KnowledgeGraphServiceGrpc.getSearchNodesMethod = getSearchNodesMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.SearchNodesRequest, com.jervis.contracts.knowledgebase.GraphNodeList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SearchNodes"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.SearchNodesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.GraphNodeList.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("SearchNodes"))
              .build();
        }
      }
    }
    return getSearchNodesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GetNodeRequest,
      com.jervis.contracts.knowledgebase.EvidencePack> getGetNodeEvidenceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetNodeEvidence",
      requestType = com.jervis.contracts.knowledgebase.GetNodeRequest.class,
      responseType = com.jervis.contracts.knowledgebase.EvidencePack.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GetNodeRequest,
      com.jervis.contracts.knowledgebase.EvidencePack> getGetNodeEvidenceMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GetNodeRequest, com.jervis.contracts.knowledgebase.EvidencePack> getGetNodeEvidenceMethod;
    if ((getGetNodeEvidenceMethod = KnowledgeGraphServiceGrpc.getGetNodeEvidenceMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getGetNodeEvidenceMethod = KnowledgeGraphServiceGrpc.getGetNodeEvidenceMethod) == null) {
          KnowledgeGraphServiceGrpc.getGetNodeEvidenceMethod = getGetNodeEvidenceMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.GetNodeRequest, com.jervis.contracts.knowledgebase.EvidencePack>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetNodeEvidence"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.GetNodeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.EvidencePack.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("GetNodeEvidence"))
              .build();
        }
      }
    }
    return getGetNodeEvidenceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest,
      com.jervis.contracts.knowledgebase.EntityList> getListQueryEntitiesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListQueryEntities",
      requestType = com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest.class,
      responseType = com.jervis.contracts.knowledgebase.EntityList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest,
      com.jervis.contracts.knowledgebase.EntityList> getListQueryEntitiesMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest, com.jervis.contracts.knowledgebase.EntityList> getListQueryEntitiesMethod;
    if ((getListQueryEntitiesMethod = KnowledgeGraphServiceGrpc.getListQueryEntitiesMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getListQueryEntitiesMethod = KnowledgeGraphServiceGrpc.getListQueryEntitiesMethod) == null) {
          KnowledgeGraphServiceGrpc.getListQueryEntitiesMethod = getListQueryEntitiesMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest, com.jervis.contracts.knowledgebase.EntityList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListQueryEntities"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.EntityList.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("ListQueryEntities"))
              .build();
        }
      }
    }
    return getListQueryEntitiesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ResolveAliasRequest,
      com.jervis.contracts.knowledgebase.AliasResolveResult> getResolveAliasMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ResolveAlias",
      requestType = com.jervis.contracts.knowledgebase.ResolveAliasRequest.class,
      responseType = com.jervis.contracts.knowledgebase.AliasResolveResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ResolveAliasRequest,
      com.jervis.contracts.knowledgebase.AliasResolveResult> getResolveAliasMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ResolveAliasRequest, com.jervis.contracts.knowledgebase.AliasResolveResult> getResolveAliasMethod;
    if ((getResolveAliasMethod = KnowledgeGraphServiceGrpc.getResolveAliasMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getResolveAliasMethod = KnowledgeGraphServiceGrpc.getResolveAliasMethod) == null) {
          KnowledgeGraphServiceGrpc.getResolveAliasMethod = getResolveAliasMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ResolveAliasRequest, com.jervis.contracts.knowledgebase.AliasResolveResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ResolveAlias"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ResolveAliasRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.AliasResolveResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("ResolveAlias"))
              .build();
        }
      }
    }
    return getResolveAliasMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ListAliasesRequest,
      com.jervis.contracts.knowledgebase.AliasList> getListAliasesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListAliases",
      requestType = com.jervis.contracts.knowledgebase.ListAliasesRequest.class,
      responseType = com.jervis.contracts.knowledgebase.AliasList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ListAliasesRequest,
      com.jervis.contracts.knowledgebase.AliasList> getListAliasesMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ListAliasesRequest, com.jervis.contracts.knowledgebase.AliasList> getListAliasesMethod;
    if ((getListAliasesMethod = KnowledgeGraphServiceGrpc.getListAliasesMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getListAliasesMethod = KnowledgeGraphServiceGrpc.getListAliasesMethod) == null) {
          KnowledgeGraphServiceGrpc.getListAliasesMethod = getListAliasesMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ListAliasesRequest, com.jervis.contracts.knowledgebase.AliasList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListAliases"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ListAliasesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.AliasList.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("ListAliases"))
              .build();
        }
      }
    }
    return getListAliasesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.AliasStatsRequest,
      com.jervis.contracts.knowledgebase.AliasStats> getGetAliasStatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetAliasStats",
      requestType = com.jervis.contracts.knowledgebase.AliasStatsRequest.class,
      responseType = com.jervis.contracts.knowledgebase.AliasStats.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.AliasStatsRequest,
      com.jervis.contracts.knowledgebase.AliasStats> getGetAliasStatsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.AliasStatsRequest, com.jervis.contracts.knowledgebase.AliasStats> getGetAliasStatsMethod;
    if ((getGetAliasStatsMethod = KnowledgeGraphServiceGrpc.getGetAliasStatsMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getGetAliasStatsMethod = KnowledgeGraphServiceGrpc.getGetAliasStatsMethod) == null) {
          KnowledgeGraphServiceGrpc.getGetAliasStatsMethod = getGetAliasStatsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.AliasStatsRequest, com.jervis.contracts.knowledgebase.AliasStats>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetAliasStats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.AliasStatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.AliasStats.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("GetAliasStats"))
              .build();
        }
      }
    }
    return getGetAliasStatsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RegisterAliasRequest,
      com.jervis.contracts.knowledgebase.AliasAck> getRegisterAliasMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterAlias",
      requestType = com.jervis.contracts.knowledgebase.RegisterAliasRequest.class,
      responseType = com.jervis.contracts.knowledgebase.AliasAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RegisterAliasRequest,
      com.jervis.contracts.knowledgebase.AliasAck> getRegisterAliasMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.RegisterAliasRequest, com.jervis.contracts.knowledgebase.AliasAck> getRegisterAliasMethod;
    if ((getRegisterAliasMethod = KnowledgeGraphServiceGrpc.getRegisterAliasMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getRegisterAliasMethod = KnowledgeGraphServiceGrpc.getRegisterAliasMethod) == null) {
          KnowledgeGraphServiceGrpc.getRegisterAliasMethod = getRegisterAliasMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.RegisterAliasRequest, com.jervis.contracts.knowledgebase.AliasAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterAlias"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.RegisterAliasRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.AliasAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("RegisterAlias"))
              .build();
        }
      }
    }
    return getRegisterAliasMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.MergeAliasRequest,
      com.jervis.contracts.knowledgebase.AliasAck> getMergeAliasMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MergeAlias",
      requestType = com.jervis.contracts.knowledgebase.MergeAliasRequest.class,
      responseType = com.jervis.contracts.knowledgebase.AliasAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.MergeAliasRequest,
      com.jervis.contracts.knowledgebase.AliasAck> getMergeAliasMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.MergeAliasRequest, com.jervis.contracts.knowledgebase.AliasAck> getMergeAliasMethod;
    if ((getMergeAliasMethod = KnowledgeGraphServiceGrpc.getMergeAliasMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getMergeAliasMethod = KnowledgeGraphServiceGrpc.getMergeAliasMethod) == null) {
          KnowledgeGraphServiceGrpc.getMergeAliasMethod = getMergeAliasMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.MergeAliasRequest, com.jervis.contracts.knowledgebase.AliasAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MergeAlias"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.MergeAliasRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.AliasAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("MergeAlias"))
              .build();
        }
      }
    }
    return getMergeAliasMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtTraversalRequest,
      com.jervis.contracts.knowledgebase.ThoughtTraversalResult> getThoughtTraverseMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ThoughtTraverse",
      requestType = com.jervis.contracts.knowledgebase.ThoughtTraversalRequest.class,
      responseType = com.jervis.contracts.knowledgebase.ThoughtTraversalResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtTraversalRequest,
      com.jervis.contracts.knowledgebase.ThoughtTraversalResult> getThoughtTraverseMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtTraversalRequest, com.jervis.contracts.knowledgebase.ThoughtTraversalResult> getThoughtTraverseMethod;
    if ((getThoughtTraverseMethod = KnowledgeGraphServiceGrpc.getThoughtTraverseMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getThoughtTraverseMethod = KnowledgeGraphServiceGrpc.getThoughtTraverseMethod) == null) {
          KnowledgeGraphServiceGrpc.getThoughtTraverseMethod = getThoughtTraverseMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ThoughtTraversalRequest, com.jervis.contracts.knowledgebase.ThoughtTraversalResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ThoughtTraverse"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtTraversalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtTraversalResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("ThoughtTraverse"))
              .build();
        }
      }
    }
    return getThoughtTraverseMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtReinforceRequest,
      com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtReinforceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ThoughtReinforce",
      requestType = com.jervis.contracts.knowledgebase.ThoughtReinforceRequest.class,
      responseType = com.jervis.contracts.knowledgebase.ThoughtAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtReinforceRequest,
      com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtReinforceMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtReinforceRequest, com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtReinforceMethod;
    if ((getThoughtReinforceMethod = KnowledgeGraphServiceGrpc.getThoughtReinforceMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getThoughtReinforceMethod = KnowledgeGraphServiceGrpc.getThoughtReinforceMethod) == null) {
          KnowledgeGraphServiceGrpc.getThoughtReinforceMethod = getThoughtReinforceMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ThoughtReinforceRequest, com.jervis.contracts.knowledgebase.ThoughtAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ThoughtReinforce"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtReinforceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("ThoughtReinforce"))
              .build();
        }
      }
    }
    return getThoughtReinforceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtCreateRequest,
      com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtCreateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ThoughtCreate",
      requestType = com.jervis.contracts.knowledgebase.ThoughtCreateRequest.class,
      responseType = com.jervis.contracts.knowledgebase.ThoughtAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtCreateRequest,
      com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtCreateMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtCreateRequest, com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtCreateMethod;
    if ((getThoughtCreateMethod = KnowledgeGraphServiceGrpc.getThoughtCreateMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getThoughtCreateMethod = KnowledgeGraphServiceGrpc.getThoughtCreateMethod) == null) {
          KnowledgeGraphServiceGrpc.getThoughtCreateMethod = getThoughtCreateMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ThoughtCreateRequest, com.jervis.contracts.knowledgebase.ThoughtAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ThoughtCreate"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtCreateRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("ThoughtCreate"))
              .build();
        }
      }
    }
    return getThoughtCreateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest,
      com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtBootstrapMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ThoughtBootstrap",
      requestType = com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest.class,
      responseType = com.jervis.contracts.knowledgebase.ThoughtAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest,
      com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtBootstrapMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest, com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtBootstrapMethod;
    if ((getThoughtBootstrapMethod = KnowledgeGraphServiceGrpc.getThoughtBootstrapMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getThoughtBootstrapMethod = KnowledgeGraphServiceGrpc.getThoughtBootstrapMethod) == null) {
          KnowledgeGraphServiceGrpc.getThoughtBootstrapMethod = getThoughtBootstrapMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest, com.jervis.contracts.knowledgebase.ThoughtAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ThoughtBootstrap"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("ThoughtBootstrap"))
              .build();
        }
      }
    }
    return getThoughtBootstrapMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest,
      com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtMaintainMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ThoughtMaintain",
      requestType = com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest.class,
      responseType = com.jervis.contracts.knowledgebase.ThoughtAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest,
      com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtMaintainMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest, com.jervis.contracts.knowledgebase.ThoughtAck> getThoughtMaintainMethod;
    if ((getThoughtMaintainMethod = KnowledgeGraphServiceGrpc.getThoughtMaintainMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getThoughtMaintainMethod = KnowledgeGraphServiceGrpc.getThoughtMaintainMethod) == null) {
          KnowledgeGraphServiceGrpc.getThoughtMaintainMethod = getThoughtMaintainMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest, com.jervis.contracts.knowledgebase.ThoughtAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ThoughtMaintain"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("ThoughtMaintain"))
              .build();
        }
      }
    }
    return getThoughtMaintainMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtStatsRequest,
      com.jervis.contracts.knowledgebase.ThoughtStatsResult> getThoughtStatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ThoughtStats",
      requestType = com.jervis.contracts.knowledgebase.ThoughtStatsRequest.class,
      responseType = com.jervis.contracts.knowledgebase.ThoughtStatsResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtStatsRequest,
      com.jervis.contracts.knowledgebase.ThoughtStatsResult> getThoughtStatsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.ThoughtStatsRequest, com.jervis.contracts.knowledgebase.ThoughtStatsResult> getThoughtStatsMethod;
    if ((getThoughtStatsMethod = KnowledgeGraphServiceGrpc.getThoughtStatsMethod) == null) {
      synchronized (KnowledgeGraphServiceGrpc.class) {
        if ((getThoughtStatsMethod = KnowledgeGraphServiceGrpc.getThoughtStatsMethod) == null) {
          KnowledgeGraphServiceGrpc.getThoughtStatsMethod = getThoughtStatsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.ThoughtStatsRequest, com.jervis.contracts.knowledgebase.ThoughtStatsResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ThoughtStats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtStatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.ThoughtStatsResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeGraphServiceMethodDescriptorSupplier("ThoughtStats"))
              .build();
        }
      }
    }
    return getThoughtStatsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static KnowledgeGraphServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeGraphServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeGraphServiceStub>() {
        @java.lang.Override
        public KnowledgeGraphServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeGraphServiceStub(channel, callOptions);
        }
      };
    return KnowledgeGraphServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static KnowledgeGraphServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeGraphServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeGraphServiceBlockingV2Stub>() {
        @java.lang.Override
        public KnowledgeGraphServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeGraphServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return KnowledgeGraphServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static KnowledgeGraphServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeGraphServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeGraphServiceBlockingStub>() {
        @java.lang.Override
        public KnowledgeGraphServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeGraphServiceBlockingStub(channel, callOptions);
        }
      };
    return KnowledgeGraphServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static KnowledgeGraphServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeGraphServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeGraphServiceFutureStub>() {
        @java.lang.Override
        public KnowledgeGraphServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeGraphServiceFutureStub(channel, callOptions);
        }
      };
    return KnowledgeGraphServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * KnowledgeGraphService — graph traversal, node lookup, alias resolution
   * and Thought Map RPCs. Writes live in ingest.proto / maintenance.proto.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void traverse(com.jervis.contracts.knowledgebase.TraversalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GraphNodeList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTraverseMethod(), responseObserver);
    }

    /**
     */
    default void getNode(com.jervis.contracts.knowledgebase.GetNodeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GraphNode> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetNodeMethod(), responseObserver);
    }

    /**
     */
    default void searchNodes(com.jervis.contracts.knowledgebase.SearchNodesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GraphNodeList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSearchNodesMethod(), responseObserver);
    }

    /**
     */
    default void getNodeEvidence(com.jervis.contracts.knowledgebase.GetNodeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EvidencePack> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetNodeEvidenceMethod(), responseObserver);
    }

    /**
     */
    default void listQueryEntities(com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EntityList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListQueryEntitiesMethod(), responseObserver);
    }

    /**
     */
    default void resolveAlias(com.jervis.contracts.knowledgebase.ResolveAliasRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasResolveResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getResolveAliasMethod(), responseObserver);
    }

    /**
     */
    default void listAliases(com.jervis.contracts.knowledgebase.ListAliasesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListAliasesMethod(), responseObserver);
    }

    /**
     */
    default void getAliasStats(com.jervis.contracts.knowledgebase.AliasStatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasStats> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetAliasStatsMethod(), responseObserver);
    }

    /**
     */
    default void registerAlias(com.jervis.contracts.knowledgebase.RegisterAliasRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterAliasMethod(), responseObserver);
    }

    /**
     */
    default void mergeAlias(com.jervis.contracts.knowledgebase.MergeAliasRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMergeAliasMethod(), responseObserver);
    }

    /**
     */
    default void thoughtTraverse(com.jervis.contracts.knowledgebase.ThoughtTraversalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtTraversalResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getThoughtTraverseMethod(), responseObserver);
    }

    /**
     */
    default void thoughtReinforce(com.jervis.contracts.knowledgebase.ThoughtReinforceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getThoughtReinforceMethod(), responseObserver);
    }

    /**
     */
    default void thoughtCreate(com.jervis.contracts.knowledgebase.ThoughtCreateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getThoughtCreateMethod(), responseObserver);
    }

    /**
     */
    default void thoughtBootstrap(com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getThoughtBootstrapMethod(), responseObserver);
    }

    /**
     */
    default void thoughtMaintain(com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getThoughtMaintainMethod(), responseObserver);
    }

    /**
     */
    default void thoughtStats(com.jervis.contracts.knowledgebase.ThoughtStatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtStatsResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getThoughtStatsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service KnowledgeGraphService.
   * <pre>
   * KnowledgeGraphService — graph traversal, node lookup, alias resolution
   * and Thought Map RPCs. Writes live in ingest.proto / maintenance.proto.
   * </pre>
   */
  public static abstract class KnowledgeGraphServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return KnowledgeGraphServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service KnowledgeGraphService.
   * <pre>
   * KnowledgeGraphService — graph traversal, node lookup, alias resolution
   * and Thought Map RPCs. Writes live in ingest.proto / maintenance.proto.
   * </pre>
   */
  public static final class KnowledgeGraphServiceStub
      extends io.grpc.stub.AbstractAsyncStub<KnowledgeGraphServiceStub> {
    private KnowledgeGraphServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeGraphServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeGraphServiceStub(channel, callOptions);
    }

    /**
     */
    public void traverse(com.jervis.contracts.knowledgebase.TraversalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GraphNodeList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTraverseMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getNode(com.jervis.contracts.knowledgebase.GetNodeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GraphNode> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetNodeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void searchNodes(com.jervis.contracts.knowledgebase.SearchNodesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GraphNodeList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSearchNodesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getNodeEvidence(com.jervis.contracts.knowledgebase.GetNodeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EvidencePack> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetNodeEvidenceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listQueryEntities(com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EntityList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListQueryEntitiesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void resolveAlias(com.jervis.contracts.knowledgebase.ResolveAliasRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasResolveResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getResolveAliasMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listAliases(com.jervis.contracts.knowledgebase.ListAliasesRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListAliasesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getAliasStats(com.jervis.contracts.knowledgebase.AliasStatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasStats> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetAliasStatsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void registerAlias(com.jervis.contracts.knowledgebase.RegisterAliasRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRegisterAliasMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void mergeAlias(com.jervis.contracts.knowledgebase.MergeAliasRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMergeAliasMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void thoughtTraverse(com.jervis.contracts.knowledgebase.ThoughtTraversalRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtTraversalResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getThoughtTraverseMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void thoughtReinforce(com.jervis.contracts.knowledgebase.ThoughtReinforceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getThoughtReinforceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void thoughtCreate(com.jervis.contracts.knowledgebase.ThoughtCreateRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getThoughtCreateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void thoughtBootstrap(com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getThoughtBootstrapMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void thoughtMaintain(com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getThoughtMaintainMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void thoughtStats(com.jervis.contracts.knowledgebase.ThoughtStatsRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtStatsResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getThoughtStatsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service KnowledgeGraphService.
   * <pre>
   * KnowledgeGraphService — graph traversal, node lookup, alias resolution
   * and Thought Map RPCs. Writes live in ingest.proto / maintenance.proto.
   * </pre>
   */
  public static final class KnowledgeGraphServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeGraphServiceBlockingV2Stub> {
    private KnowledgeGraphServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeGraphServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeGraphServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GraphNodeList traverse(com.jervis.contracts.knowledgebase.TraversalRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getTraverseMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GraphNode getNode(com.jervis.contracts.knowledgebase.GetNodeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetNodeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GraphNodeList searchNodes(com.jervis.contracts.knowledgebase.SearchNodesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSearchNodesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.EvidencePack getNodeEvidence(com.jervis.contracts.knowledgebase.GetNodeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetNodeEvidenceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.EntityList listQueryEntities(com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListQueryEntitiesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasResolveResult resolveAlias(com.jervis.contracts.knowledgebase.ResolveAliasRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getResolveAliasMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasList listAliases(com.jervis.contracts.knowledgebase.ListAliasesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getListAliasesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasStats getAliasStats(com.jervis.contracts.knowledgebase.AliasStatsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetAliasStatsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasAck registerAlias(com.jervis.contracts.knowledgebase.RegisterAliasRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRegisterAliasMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasAck mergeAlias(com.jervis.contracts.knowledgebase.MergeAliasRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getMergeAliasMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtTraversalResult thoughtTraverse(com.jervis.contracts.knowledgebase.ThoughtTraversalRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getThoughtTraverseMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtAck thoughtReinforce(com.jervis.contracts.knowledgebase.ThoughtReinforceRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getThoughtReinforceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtAck thoughtCreate(com.jervis.contracts.knowledgebase.ThoughtCreateRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getThoughtCreateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtAck thoughtBootstrap(com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getThoughtBootstrapMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtAck thoughtMaintain(com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getThoughtMaintainMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtStatsResult thoughtStats(com.jervis.contracts.knowledgebase.ThoughtStatsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getThoughtStatsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service KnowledgeGraphService.
   * <pre>
   * KnowledgeGraphService — graph traversal, node lookup, alias resolution
   * and Thought Map RPCs. Writes live in ingest.proto / maintenance.proto.
   * </pre>
   */
  public static final class KnowledgeGraphServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeGraphServiceBlockingStub> {
    private KnowledgeGraphServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeGraphServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeGraphServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GraphNodeList traverse(com.jervis.contracts.knowledgebase.TraversalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTraverseMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GraphNode getNode(com.jervis.contracts.knowledgebase.GetNodeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetNodeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GraphNodeList searchNodes(com.jervis.contracts.knowledgebase.SearchNodesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSearchNodesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.EvidencePack getNodeEvidence(com.jervis.contracts.knowledgebase.GetNodeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetNodeEvidenceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.EntityList listQueryEntities(com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListQueryEntitiesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasResolveResult resolveAlias(com.jervis.contracts.knowledgebase.ResolveAliasRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getResolveAliasMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasList listAliases(com.jervis.contracts.knowledgebase.ListAliasesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListAliasesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasStats getAliasStats(com.jervis.contracts.knowledgebase.AliasStatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetAliasStatsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasAck registerAlias(com.jervis.contracts.knowledgebase.RegisterAliasRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRegisterAliasMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AliasAck mergeAlias(com.jervis.contracts.knowledgebase.MergeAliasRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMergeAliasMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtTraversalResult thoughtTraverse(com.jervis.contracts.knowledgebase.ThoughtTraversalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getThoughtTraverseMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtAck thoughtReinforce(com.jervis.contracts.knowledgebase.ThoughtReinforceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getThoughtReinforceMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtAck thoughtCreate(com.jervis.contracts.knowledgebase.ThoughtCreateRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getThoughtCreateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtAck thoughtBootstrap(com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getThoughtBootstrapMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtAck thoughtMaintain(com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getThoughtMaintainMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.ThoughtStatsResult thoughtStats(com.jervis.contracts.knowledgebase.ThoughtStatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getThoughtStatsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service KnowledgeGraphService.
   * <pre>
   * KnowledgeGraphService — graph traversal, node lookup, alias resolution
   * and Thought Map RPCs. Writes live in ingest.proto / maintenance.proto.
   * </pre>
   */
  public static final class KnowledgeGraphServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<KnowledgeGraphServiceFutureStub> {
    private KnowledgeGraphServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeGraphServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeGraphServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.GraphNodeList> traverse(
        com.jervis.contracts.knowledgebase.TraversalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTraverseMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.GraphNode> getNode(
        com.jervis.contracts.knowledgebase.GetNodeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetNodeMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.GraphNodeList> searchNodes(
        com.jervis.contracts.knowledgebase.SearchNodesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSearchNodesMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.EvidencePack> getNodeEvidence(
        com.jervis.contracts.knowledgebase.GetNodeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetNodeEvidenceMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.EntityList> listQueryEntities(
        com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListQueryEntitiesMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.AliasResolveResult> resolveAlias(
        com.jervis.contracts.knowledgebase.ResolveAliasRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getResolveAliasMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.AliasList> listAliases(
        com.jervis.contracts.knowledgebase.ListAliasesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListAliasesMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.AliasStats> getAliasStats(
        com.jervis.contracts.knowledgebase.AliasStatsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetAliasStatsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.AliasAck> registerAlias(
        com.jervis.contracts.knowledgebase.RegisterAliasRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRegisterAliasMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.AliasAck> mergeAlias(
        com.jervis.contracts.knowledgebase.MergeAliasRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getMergeAliasMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.ThoughtTraversalResult> thoughtTraverse(
        com.jervis.contracts.knowledgebase.ThoughtTraversalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getThoughtTraverseMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.ThoughtAck> thoughtReinforce(
        com.jervis.contracts.knowledgebase.ThoughtReinforceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getThoughtReinforceMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.ThoughtAck> thoughtCreate(
        com.jervis.contracts.knowledgebase.ThoughtCreateRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getThoughtCreateMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.ThoughtAck> thoughtBootstrap(
        com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getThoughtBootstrapMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.ThoughtAck> thoughtMaintain(
        com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getThoughtMaintainMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.ThoughtStatsResult> thoughtStats(
        com.jervis.contracts.knowledgebase.ThoughtStatsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getThoughtStatsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_TRAVERSE = 0;
  private static final int METHODID_GET_NODE = 1;
  private static final int METHODID_SEARCH_NODES = 2;
  private static final int METHODID_GET_NODE_EVIDENCE = 3;
  private static final int METHODID_LIST_QUERY_ENTITIES = 4;
  private static final int METHODID_RESOLVE_ALIAS = 5;
  private static final int METHODID_LIST_ALIASES = 6;
  private static final int METHODID_GET_ALIAS_STATS = 7;
  private static final int METHODID_REGISTER_ALIAS = 8;
  private static final int METHODID_MERGE_ALIAS = 9;
  private static final int METHODID_THOUGHT_TRAVERSE = 10;
  private static final int METHODID_THOUGHT_REINFORCE = 11;
  private static final int METHODID_THOUGHT_CREATE = 12;
  private static final int METHODID_THOUGHT_BOOTSTRAP = 13;
  private static final int METHODID_THOUGHT_MAINTAIN = 14;
  private static final int METHODID_THOUGHT_STATS = 15;

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
        case METHODID_TRAVERSE:
          serviceImpl.traverse((com.jervis.contracts.knowledgebase.TraversalRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GraphNodeList>) responseObserver);
          break;
        case METHODID_GET_NODE:
          serviceImpl.getNode((com.jervis.contracts.knowledgebase.GetNodeRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GraphNode>) responseObserver);
          break;
        case METHODID_SEARCH_NODES:
          serviceImpl.searchNodes((com.jervis.contracts.knowledgebase.SearchNodesRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GraphNodeList>) responseObserver);
          break;
        case METHODID_GET_NODE_EVIDENCE:
          serviceImpl.getNodeEvidence((com.jervis.contracts.knowledgebase.GetNodeRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EvidencePack>) responseObserver);
          break;
        case METHODID_LIST_QUERY_ENTITIES:
          serviceImpl.listQueryEntities((com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.EntityList>) responseObserver);
          break;
        case METHODID_RESOLVE_ALIAS:
          serviceImpl.resolveAlias((com.jervis.contracts.knowledgebase.ResolveAliasRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasResolveResult>) responseObserver);
          break;
        case METHODID_LIST_ALIASES:
          serviceImpl.listAliases((com.jervis.contracts.knowledgebase.ListAliasesRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasList>) responseObserver);
          break;
        case METHODID_GET_ALIAS_STATS:
          serviceImpl.getAliasStats((com.jervis.contracts.knowledgebase.AliasStatsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasStats>) responseObserver);
          break;
        case METHODID_REGISTER_ALIAS:
          serviceImpl.registerAlias((com.jervis.contracts.knowledgebase.RegisterAliasRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasAck>) responseObserver);
          break;
        case METHODID_MERGE_ALIAS:
          serviceImpl.mergeAlias((com.jervis.contracts.knowledgebase.MergeAliasRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AliasAck>) responseObserver);
          break;
        case METHODID_THOUGHT_TRAVERSE:
          serviceImpl.thoughtTraverse((com.jervis.contracts.knowledgebase.ThoughtTraversalRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtTraversalResult>) responseObserver);
          break;
        case METHODID_THOUGHT_REINFORCE:
          serviceImpl.thoughtReinforce((com.jervis.contracts.knowledgebase.ThoughtReinforceRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck>) responseObserver);
          break;
        case METHODID_THOUGHT_CREATE:
          serviceImpl.thoughtCreate((com.jervis.contracts.knowledgebase.ThoughtCreateRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck>) responseObserver);
          break;
        case METHODID_THOUGHT_BOOTSTRAP:
          serviceImpl.thoughtBootstrap((com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck>) responseObserver);
          break;
        case METHODID_THOUGHT_MAINTAIN:
          serviceImpl.thoughtMaintain((com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtAck>) responseObserver);
          break;
        case METHODID_THOUGHT_STATS:
          serviceImpl.thoughtStats((com.jervis.contracts.knowledgebase.ThoughtStatsRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.ThoughtStatsResult>) responseObserver);
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
          getTraverseMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.TraversalRequest,
              com.jervis.contracts.knowledgebase.GraphNodeList>(
                service, METHODID_TRAVERSE)))
        .addMethod(
          getGetNodeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.GetNodeRequest,
              com.jervis.contracts.knowledgebase.GraphNode>(
                service, METHODID_GET_NODE)))
        .addMethod(
          getSearchNodesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.SearchNodesRequest,
              com.jervis.contracts.knowledgebase.GraphNodeList>(
                service, METHODID_SEARCH_NODES)))
        .addMethod(
          getGetNodeEvidenceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.GetNodeRequest,
              com.jervis.contracts.knowledgebase.EvidencePack>(
                service, METHODID_GET_NODE_EVIDENCE)))
        .addMethod(
          getListQueryEntitiesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ListQueryEntitiesRequest,
              com.jervis.contracts.knowledgebase.EntityList>(
                service, METHODID_LIST_QUERY_ENTITIES)))
        .addMethod(
          getResolveAliasMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ResolveAliasRequest,
              com.jervis.contracts.knowledgebase.AliasResolveResult>(
                service, METHODID_RESOLVE_ALIAS)))
        .addMethod(
          getListAliasesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ListAliasesRequest,
              com.jervis.contracts.knowledgebase.AliasList>(
                service, METHODID_LIST_ALIASES)))
        .addMethod(
          getGetAliasStatsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.AliasStatsRequest,
              com.jervis.contracts.knowledgebase.AliasStats>(
                service, METHODID_GET_ALIAS_STATS)))
        .addMethod(
          getRegisterAliasMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.RegisterAliasRequest,
              com.jervis.contracts.knowledgebase.AliasAck>(
                service, METHODID_REGISTER_ALIAS)))
        .addMethod(
          getMergeAliasMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.MergeAliasRequest,
              com.jervis.contracts.knowledgebase.AliasAck>(
                service, METHODID_MERGE_ALIAS)))
        .addMethod(
          getThoughtTraverseMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ThoughtTraversalRequest,
              com.jervis.contracts.knowledgebase.ThoughtTraversalResult>(
                service, METHODID_THOUGHT_TRAVERSE)))
        .addMethod(
          getThoughtReinforceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ThoughtReinforceRequest,
              com.jervis.contracts.knowledgebase.ThoughtAck>(
                service, METHODID_THOUGHT_REINFORCE)))
        .addMethod(
          getThoughtCreateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ThoughtCreateRequest,
              com.jervis.contracts.knowledgebase.ThoughtAck>(
                service, METHODID_THOUGHT_CREATE)))
        .addMethod(
          getThoughtBootstrapMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ThoughtBootstrapRequest,
              com.jervis.contracts.knowledgebase.ThoughtAck>(
                service, METHODID_THOUGHT_BOOTSTRAP)))
        .addMethod(
          getThoughtMaintainMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ThoughtMaintenanceRequest,
              com.jervis.contracts.knowledgebase.ThoughtAck>(
                service, METHODID_THOUGHT_MAINTAIN)))
        .addMethod(
          getThoughtStatsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.ThoughtStatsRequest,
              com.jervis.contracts.knowledgebase.ThoughtStatsResult>(
                service, METHODID_THOUGHT_STATS)))
        .build();
  }

  private static abstract class KnowledgeGraphServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    KnowledgeGraphServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.knowledgebase.KnowledgeGraphProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("KnowledgeGraphService");
    }
  }

  private static final class KnowledgeGraphServiceFileDescriptorSupplier
      extends KnowledgeGraphServiceBaseDescriptorSupplier {
    KnowledgeGraphServiceFileDescriptorSupplier() {}
  }

  private static final class KnowledgeGraphServiceMethodDescriptorSupplier
      extends KnowledgeGraphServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    KnowledgeGraphServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (KnowledgeGraphServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new KnowledgeGraphServiceFileDescriptorSupplier())
              .addMethod(getTraverseMethod())
              .addMethod(getGetNodeMethod())
              .addMethod(getSearchNodesMethod())
              .addMethod(getGetNodeEvidenceMethod())
              .addMethod(getListQueryEntitiesMethod())
              .addMethod(getResolveAliasMethod())
              .addMethod(getListAliasesMethod())
              .addMethod(getGetAliasStatsMethod())
              .addMethod(getRegisterAliasMethod())
              .addMethod(getMergeAliasMethod())
              .addMethod(getThoughtTraverseMethod())
              .addMethod(getThoughtReinforceMethod())
              .addMethod(getThoughtCreateMethod())
              .addMethod(getThoughtBootstrapMethod())
              .addMethod(getThoughtMaintainMethod())
              .addMethod(getThoughtStatsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
