package com.jervis.contracts.knowledgebase;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * KnowledgeIngestService — content ingestion into KB (RAG chunks +
 * graph nodes). FullIngest variants carry the main text inline; binary
 * attachments travel over the blob side channel (PUT /blob/kb-doc/{id})
 * and are referenced via AttachmentRef.blob_ref.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class KnowledgeIngestServiceGrpc {

  private KnowledgeIngestServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.knowledgebase.KnowledgeIngestService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestRequest,
      com.jervis.contracts.knowledgebase.IngestResult> getIngestMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Ingest",
      requestType = com.jervis.contracts.knowledgebase.IngestRequest.class,
      responseType = com.jervis.contracts.knowledgebase.IngestResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestRequest,
      com.jervis.contracts.knowledgebase.IngestResult> getIngestMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestRequest, com.jervis.contracts.knowledgebase.IngestResult> getIngestMethod;
    if ((getIngestMethod = KnowledgeIngestServiceGrpc.getIngestMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getIngestMethod = KnowledgeIngestServiceGrpc.getIngestMethod) == null) {
          KnowledgeIngestServiceGrpc.getIngestMethod = getIngestMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.IngestRequest, com.jervis.contracts.knowledgebase.IngestResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Ingest"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.IngestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.IngestResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("Ingest"))
              .build();
        }
      }
    }
    return getIngestMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestRequest,
      com.jervis.contracts.knowledgebase.IngestResult> getIngestImmediateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "IngestImmediate",
      requestType = com.jervis.contracts.knowledgebase.IngestRequest.class,
      responseType = com.jervis.contracts.knowledgebase.IngestResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestRequest,
      com.jervis.contracts.knowledgebase.IngestResult> getIngestImmediateMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestRequest, com.jervis.contracts.knowledgebase.IngestResult> getIngestImmediateMethod;
    if ((getIngestImmediateMethod = KnowledgeIngestServiceGrpc.getIngestImmediateMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getIngestImmediateMethod = KnowledgeIngestServiceGrpc.getIngestImmediateMethod) == null) {
          KnowledgeIngestServiceGrpc.getIngestImmediateMethod = getIngestImmediateMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.IngestRequest, com.jervis.contracts.knowledgebase.IngestResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IngestImmediate"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.IngestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.IngestResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("IngestImmediate"))
              .build();
        }
      }
    }
    return getIngestImmediateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestRequest,
      com.jervis.contracts.knowledgebase.IngestQueueAck> getIngestQueueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "IngestQueue",
      requestType = com.jervis.contracts.knowledgebase.IngestRequest.class,
      responseType = com.jervis.contracts.knowledgebase.IngestQueueAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestRequest,
      com.jervis.contracts.knowledgebase.IngestQueueAck> getIngestQueueMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestRequest, com.jervis.contracts.knowledgebase.IngestQueueAck> getIngestQueueMethod;
    if ((getIngestQueueMethod = KnowledgeIngestServiceGrpc.getIngestQueueMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getIngestQueueMethod = KnowledgeIngestServiceGrpc.getIngestQueueMethod) == null) {
          KnowledgeIngestServiceGrpc.getIngestQueueMethod = getIngestQueueMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.IngestRequest, com.jervis.contracts.knowledgebase.IngestQueueAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IngestQueue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.IngestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.IngestQueueAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("IngestQueue"))
              .build();
        }
      }
    }
    return getIngestQueueMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestFileRequest,
      com.jervis.contracts.knowledgebase.IngestResult> getIngestFileMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "IngestFile",
      requestType = com.jervis.contracts.knowledgebase.IngestFileRequest.class,
      responseType = com.jervis.contracts.knowledgebase.IngestResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestFileRequest,
      com.jervis.contracts.knowledgebase.IngestResult> getIngestFileMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.IngestFileRequest, com.jervis.contracts.knowledgebase.IngestResult> getIngestFileMethod;
    if ((getIngestFileMethod = KnowledgeIngestServiceGrpc.getIngestFileMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getIngestFileMethod = KnowledgeIngestServiceGrpc.getIngestFileMethod) == null) {
          KnowledgeIngestServiceGrpc.getIngestFileMethod = getIngestFileMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.IngestFileRequest, com.jervis.contracts.knowledgebase.IngestResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IngestFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.IngestFileRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.IngestResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("IngestFile"))
              .build();
        }
      }
    }
    return getIngestFileMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.FullIngestRequest,
      com.jervis.contracts.knowledgebase.FullIngestResult> getIngestFullMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "IngestFull",
      requestType = com.jervis.contracts.knowledgebase.FullIngestRequest.class,
      responseType = com.jervis.contracts.knowledgebase.FullIngestResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.FullIngestRequest,
      com.jervis.contracts.knowledgebase.FullIngestResult> getIngestFullMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.FullIngestRequest, com.jervis.contracts.knowledgebase.FullIngestResult> getIngestFullMethod;
    if ((getIngestFullMethod = KnowledgeIngestServiceGrpc.getIngestFullMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getIngestFullMethod = KnowledgeIngestServiceGrpc.getIngestFullMethod) == null) {
          KnowledgeIngestServiceGrpc.getIngestFullMethod = getIngestFullMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.FullIngestRequest, com.jervis.contracts.knowledgebase.FullIngestResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IngestFull"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.FullIngestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.FullIngestResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("IngestFull"))
              .build();
        }
      }
    }
    return getIngestFullMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.AsyncFullIngestRequest,
      com.jervis.contracts.knowledgebase.AsyncIngestAck> getIngestFullAsyncMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "IngestFullAsync",
      requestType = com.jervis.contracts.knowledgebase.AsyncFullIngestRequest.class,
      responseType = com.jervis.contracts.knowledgebase.AsyncIngestAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.AsyncFullIngestRequest,
      com.jervis.contracts.knowledgebase.AsyncIngestAck> getIngestFullAsyncMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.AsyncFullIngestRequest, com.jervis.contracts.knowledgebase.AsyncIngestAck> getIngestFullAsyncMethod;
    if ((getIngestFullAsyncMethod = KnowledgeIngestServiceGrpc.getIngestFullAsyncMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getIngestFullAsyncMethod = KnowledgeIngestServiceGrpc.getIngestFullAsyncMethod) == null) {
          KnowledgeIngestServiceGrpc.getIngestFullAsyncMethod = getIngestFullAsyncMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.AsyncFullIngestRequest, com.jervis.contracts.knowledgebase.AsyncIngestAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IngestFullAsync"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.AsyncFullIngestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.AsyncIngestAck.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("IngestFullAsync"))
              .build();
        }
      }
    }
    return getIngestFullAsyncMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GitStructureIngestRequest,
      com.jervis.contracts.knowledgebase.GitStructureIngestResult> getIngestGitStructureMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "IngestGitStructure",
      requestType = com.jervis.contracts.knowledgebase.GitStructureIngestRequest.class,
      responseType = com.jervis.contracts.knowledgebase.GitStructureIngestResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GitStructureIngestRequest,
      com.jervis.contracts.knowledgebase.GitStructureIngestResult> getIngestGitStructureMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GitStructureIngestRequest, com.jervis.contracts.knowledgebase.GitStructureIngestResult> getIngestGitStructureMethod;
    if ((getIngestGitStructureMethod = KnowledgeIngestServiceGrpc.getIngestGitStructureMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getIngestGitStructureMethod = KnowledgeIngestServiceGrpc.getIngestGitStructureMethod) == null) {
          KnowledgeIngestServiceGrpc.getIngestGitStructureMethod = getIngestGitStructureMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.GitStructureIngestRequest, com.jervis.contracts.knowledgebase.GitStructureIngestResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IngestGitStructure"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.GitStructureIngestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.GitStructureIngestResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("IngestGitStructure"))
              .build();
        }
      }
    }
    return getIngestGitStructureMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GitCommitIngestRequest,
      com.jervis.contracts.knowledgebase.GitCommitIngestResult> getIngestGitCommitsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "IngestGitCommits",
      requestType = com.jervis.contracts.knowledgebase.GitCommitIngestRequest.class,
      responseType = com.jervis.contracts.knowledgebase.GitCommitIngestResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GitCommitIngestRequest,
      com.jervis.contracts.knowledgebase.GitCommitIngestResult> getIngestGitCommitsMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.GitCommitIngestRequest, com.jervis.contracts.knowledgebase.GitCommitIngestResult> getIngestGitCommitsMethod;
    if ((getIngestGitCommitsMethod = KnowledgeIngestServiceGrpc.getIngestGitCommitsMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getIngestGitCommitsMethod = KnowledgeIngestServiceGrpc.getIngestGitCommitsMethod) == null) {
          KnowledgeIngestServiceGrpc.getIngestGitCommitsMethod = getIngestGitCommitsMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.GitCommitIngestRequest, com.jervis.contracts.knowledgebase.GitCommitIngestResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IngestGitCommits"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.GitCommitIngestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.GitCommitIngestResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("IngestGitCommits"))
              .build();
        }
      }
    }
    return getIngestGitCommitsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.CpgIngestRequest,
      com.jervis.contracts.knowledgebase.CpgIngestResult> getIngestCpgMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "IngestCpg",
      requestType = com.jervis.contracts.knowledgebase.CpgIngestRequest.class,
      responseType = com.jervis.contracts.knowledgebase.CpgIngestResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.CpgIngestRequest,
      com.jervis.contracts.knowledgebase.CpgIngestResult> getIngestCpgMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.CpgIngestRequest, com.jervis.contracts.knowledgebase.CpgIngestResult> getIngestCpgMethod;
    if ((getIngestCpgMethod = KnowledgeIngestServiceGrpc.getIngestCpgMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getIngestCpgMethod = KnowledgeIngestServiceGrpc.getIngestCpgMethod) == null) {
          KnowledgeIngestServiceGrpc.getIngestCpgMethod = getIngestCpgMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.CpgIngestRequest, com.jervis.contracts.knowledgebase.CpgIngestResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IngestCpg"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.CpgIngestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.CpgIngestResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("IngestCpg"))
              .build();
        }
      }
    }
    return getIngestCpgMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.CrawlRequest,
      com.jervis.contracts.knowledgebase.IngestResult> getCrawlMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Crawl",
      requestType = com.jervis.contracts.knowledgebase.CrawlRequest.class,
      responseType = com.jervis.contracts.knowledgebase.IngestResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.CrawlRequest,
      com.jervis.contracts.knowledgebase.IngestResult> getCrawlMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.CrawlRequest, com.jervis.contracts.knowledgebase.IngestResult> getCrawlMethod;
    if ((getCrawlMethod = KnowledgeIngestServiceGrpc.getCrawlMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getCrawlMethod = KnowledgeIngestServiceGrpc.getCrawlMethod) == null) {
          KnowledgeIngestServiceGrpc.getCrawlMethod = getCrawlMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.CrawlRequest, com.jervis.contracts.knowledgebase.IngestResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Crawl"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.CrawlRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.IngestResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("Crawl"))
              .build();
        }
      }
    }
    return getCrawlMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.PurgeRequest,
      com.jervis.contracts.knowledgebase.PurgeResult> getPurgeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Purge",
      requestType = com.jervis.contracts.knowledgebase.PurgeRequest.class,
      responseType = com.jervis.contracts.knowledgebase.PurgeResult.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.PurgeRequest,
      com.jervis.contracts.knowledgebase.PurgeResult> getPurgeMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.knowledgebase.PurgeRequest, com.jervis.contracts.knowledgebase.PurgeResult> getPurgeMethod;
    if ((getPurgeMethod = KnowledgeIngestServiceGrpc.getPurgeMethod) == null) {
      synchronized (KnowledgeIngestServiceGrpc.class) {
        if ((getPurgeMethod = KnowledgeIngestServiceGrpc.getPurgeMethod) == null) {
          KnowledgeIngestServiceGrpc.getPurgeMethod = getPurgeMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.knowledgebase.PurgeRequest, com.jervis.contracts.knowledgebase.PurgeResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Purge"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.PurgeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.knowledgebase.PurgeResult.getDefaultInstance()))
              .setSchemaDescriptor(new KnowledgeIngestServiceMethodDescriptorSupplier("Purge"))
              .build();
        }
      }
    }
    return getPurgeMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static KnowledgeIngestServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeIngestServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeIngestServiceStub>() {
        @java.lang.Override
        public KnowledgeIngestServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeIngestServiceStub(channel, callOptions);
        }
      };
    return KnowledgeIngestServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static KnowledgeIngestServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeIngestServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeIngestServiceBlockingV2Stub>() {
        @java.lang.Override
        public KnowledgeIngestServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeIngestServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return KnowledgeIngestServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static KnowledgeIngestServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeIngestServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeIngestServiceBlockingStub>() {
        @java.lang.Override
        public KnowledgeIngestServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeIngestServiceBlockingStub(channel, callOptions);
        }
      };
    return KnowledgeIngestServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static KnowledgeIngestServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<KnowledgeIngestServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<KnowledgeIngestServiceFutureStub>() {
        @java.lang.Override
        public KnowledgeIngestServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new KnowledgeIngestServiceFutureStub(channel, callOptions);
        }
      };
    return KnowledgeIngestServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * KnowledgeIngestService — content ingestion into KB (RAG chunks +
   * graph nodes). FullIngest variants carry the main text inline; binary
   * attachments travel over the blob side channel (PUT /blob/kb-doc/{id})
   * and are referenced via AttachmentRef.blob_ref.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void ingest(com.jervis.contracts.knowledgebase.IngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestMethod(), responseObserver);
    }

    /**
     */
    default void ingestImmediate(com.jervis.contracts.knowledgebase.IngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestImmediateMethod(), responseObserver);
    }

    /**
     */
    default void ingestQueue(com.jervis.contracts.knowledgebase.IngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestQueueAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestQueueMethod(), responseObserver);
    }

    /**
     */
    default void ingestFile(com.jervis.contracts.knowledgebase.IngestFileRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestFileMethod(), responseObserver);
    }

    /**
     */
    default void ingestFull(com.jervis.contracts.knowledgebase.FullIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.FullIngestResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestFullMethod(), responseObserver);
    }

    /**
     */
    default void ingestFullAsync(com.jervis.contracts.knowledgebase.AsyncFullIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AsyncIngestAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestFullAsyncMethod(), responseObserver);
    }

    /**
     */
    default void ingestGitStructure(com.jervis.contracts.knowledgebase.GitStructureIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GitStructureIngestResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestGitStructureMethod(), responseObserver);
    }

    /**
     */
    default void ingestGitCommits(com.jervis.contracts.knowledgebase.GitCommitIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GitCommitIngestResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestGitCommitsMethod(), responseObserver);
    }

    /**
     */
    default void ingestCpg(com.jervis.contracts.knowledgebase.CpgIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.CpgIngestResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestCpgMethod(), responseObserver);
    }

    /**
     */
    default void crawl(com.jervis.contracts.knowledgebase.CrawlRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCrawlMethod(), responseObserver);
    }

    /**
     */
    default void purge(com.jervis.contracts.knowledgebase.PurgeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.PurgeResult> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPurgeMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service KnowledgeIngestService.
   * <pre>
   * KnowledgeIngestService — content ingestion into KB (RAG chunks +
   * graph nodes). FullIngest variants carry the main text inline; binary
   * attachments travel over the blob side channel (PUT /blob/kb-doc/{id})
   * and are referenced via AttachmentRef.blob_ref.
   * </pre>
   */
  public static abstract class KnowledgeIngestServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return KnowledgeIngestServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service KnowledgeIngestService.
   * <pre>
   * KnowledgeIngestService — content ingestion into KB (RAG chunks +
   * graph nodes). FullIngest variants carry the main text inline; binary
   * attachments travel over the blob side channel (PUT /blob/kb-doc/{id})
   * and are referenced via AttachmentRef.blob_ref.
   * </pre>
   */
  public static final class KnowledgeIngestServiceStub
      extends io.grpc.stub.AbstractAsyncStub<KnowledgeIngestServiceStub> {
    private KnowledgeIngestServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeIngestServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeIngestServiceStub(channel, callOptions);
    }

    /**
     */
    public void ingest(com.jervis.contracts.knowledgebase.IngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ingestImmediate(com.jervis.contracts.knowledgebase.IngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestImmediateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ingestQueue(com.jervis.contracts.knowledgebase.IngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestQueueAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestQueueMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ingestFile(com.jervis.contracts.knowledgebase.IngestFileRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestFileMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ingestFull(com.jervis.contracts.knowledgebase.FullIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.FullIngestResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestFullMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ingestFullAsync(com.jervis.contracts.knowledgebase.AsyncFullIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AsyncIngestAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestFullAsyncMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ingestGitStructure(com.jervis.contracts.knowledgebase.GitStructureIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GitStructureIngestResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestGitStructureMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ingestGitCommits(com.jervis.contracts.knowledgebase.GitCommitIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GitCommitIngestResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestGitCommitsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void ingestCpg(com.jervis.contracts.knowledgebase.CpgIngestRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.CpgIngestResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestCpgMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void crawl(com.jervis.contracts.knowledgebase.CrawlRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCrawlMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void purge(com.jervis.contracts.knowledgebase.PurgeRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.PurgeResult> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPurgeMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service KnowledgeIngestService.
   * <pre>
   * KnowledgeIngestService — content ingestion into KB (RAG chunks +
   * graph nodes). FullIngest variants carry the main text inline; binary
   * attachments travel over the blob side channel (PUT /blob/kb-doc/{id})
   * and are referenced via AttachmentRef.blob_ref.
   * </pre>
   */
  public static final class KnowledgeIngestServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeIngestServiceBlockingV2Stub> {
    private KnowledgeIngestServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeIngestServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeIngestServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestResult ingest(com.jervis.contracts.knowledgebase.IngestRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIngestMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestResult ingestImmediate(com.jervis.contracts.knowledgebase.IngestRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIngestImmediateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestQueueAck ingestQueue(com.jervis.contracts.knowledgebase.IngestRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIngestQueueMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestResult ingestFile(com.jervis.contracts.knowledgebase.IngestFileRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIngestFileMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.FullIngestResult ingestFull(com.jervis.contracts.knowledgebase.FullIngestRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIngestFullMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AsyncIngestAck ingestFullAsync(com.jervis.contracts.knowledgebase.AsyncFullIngestRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIngestFullAsyncMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GitStructureIngestResult ingestGitStructure(com.jervis.contracts.knowledgebase.GitStructureIngestRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIngestGitStructureMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GitCommitIngestResult ingestGitCommits(com.jervis.contracts.knowledgebase.GitCommitIngestRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIngestGitCommitsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.CpgIngestResult ingestCpg(com.jervis.contracts.knowledgebase.CpgIngestRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIngestCpgMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestResult crawl(com.jervis.contracts.knowledgebase.CrawlRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCrawlMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.PurgeResult purge(com.jervis.contracts.knowledgebase.PurgeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPurgeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service KnowledgeIngestService.
   * <pre>
   * KnowledgeIngestService — content ingestion into KB (RAG chunks +
   * graph nodes). FullIngest variants carry the main text inline; binary
   * attachments travel over the blob side channel (PUT /blob/kb-doc/{id})
   * and are referenced via AttachmentRef.blob_ref.
   * </pre>
   */
  public static final class KnowledgeIngestServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<KnowledgeIngestServiceBlockingStub> {
    private KnowledgeIngestServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeIngestServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeIngestServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestResult ingest(com.jervis.contracts.knowledgebase.IngestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestResult ingestImmediate(com.jervis.contracts.knowledgebase.IngestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestImmediateMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestQueueAck ingestQueue(com.jervis.contracts.knowledgebase.IngestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestQueueMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestResult ingestFile(com.jervis.contracts.knowledgebase.IngestFileRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestFileMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.FullIngestResult ingestFull(com.jervis.contracts.knowledgebase.FullIngestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestFullMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.AsyncIngestAck ingestFullAsync(com.jervis.contracts.knowledgebase.AsyncFullIngestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestFullAsyncMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GitStructureIngestResult ingestGitStructure(com.jervis.contracts.knowledgebase.GitStructureIngestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestGitStructureMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.GitCommitIngestResult ingestGitCommits(com.jervis.contracts.knowledgebase.GitCommitIngestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestGitCommitsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.CpgIngestResult ingestCpg(com.jervis.contracts.knowledgebase.CpgIngestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestCpgMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.IngestResult crawl(com.jervis.contracts.knowledgebase.CrawlRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCrawlMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.knowledgebase.PurgeResult purge(com.jervis.contracts.knowledgebase.PurgeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPurgeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service KnowledgeIngestService.
   * <pre>
   * KnowledgeIngestService — content ingestion into KB (RAG chunks +
   * graph nodes). FullIngest variants carry the main text inline; binary
   * attachments travel over the blob side channel (PUT /blob/kb-doc/{id})
   * and are referenced via AttachmentRef.blob_ref.
   * </pre>
   */
  public static final class KnowledgeIngestServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<KnowledgeIngestServiceFutureStub> {
    private KnowledgeIngestServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected KnowledgeIngestServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new KnowledgeIngestServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.IngestResult> ingest(
        com.jervis.contracts.knowledgebase.IngestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.IngestResult> ingestImmediate(
        com.jervis.contracts.knowledgebase.IngestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestImmediateMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.IngestQueueAck> ingestQueue(
        com.jervis.contracts.knowledgebase.IngestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestQueueMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.IngestResult> ingestFile(
        com.jervis.contracts.knowledgebase.IngestFileRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestFileMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.FullIngestResult> ingestFull(
        com.jervis.contracts.knowledgebase.FullIngestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestFullMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.AsyncIngestAck> ingestFullAsync(
        com.jervis.contracts.knowledgebase.AsyncFullIngestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestFullAsyncMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.GitStructureIngestResult> ingestGitStructure(
        com.jervis.contracts.knowledgebase.GitStructureIngestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestGitStructureMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.GitCommitIngestResult> ingestGitCommits(
        com.jervis.contracts.knowledgebase.GitCommitIngestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestGitCommitsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.CpgIngestResult> ingestCpg(
        com.jervis.contracts.knowledgebase.CpgIngestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestCpgMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.IngestResult> crawl(
        com.jervis.contracts.knowledgebase.CrawlRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCrawlMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.knowledgebase.PurgeResult> purge(
        com.jervis.contracts.knowledgebase.PurgeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPurgeMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_INGEST = 0;
  private static final int METHODID_INGEST_IMMEDIATE = 1;
  private static final int METHODID_INGEST_QUEUE = 2;
  private static final int METHODID_INGEST_FILE = 3;
  private static final int METHODID_INGEST_FULL = 4;
  private static final int METHODID_INGEST_FULL_ASYNC = 5;
  private static final int METHODID_INGEST_GIT_STRUCTURE = 6;
  private static final int METHODID_INGEST_GIT_COMMITS = 7;
  private static final int METHODID_INGEST_CPG = 8;
  private static final int METHODID_CRAWL = 9;
  private static final int METHODID_PURGE = 10;

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
        case METHODID_INGEST:
          serviceImpl.ingest((com.jervis.contracts.knowledgebase.IngestRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult>) responseObserver);
          break;
        case METHODID_INGEST_IMMEDIATE:
          serviceImpl.ingestImmediate((com.jervis.contracts.knowledgebase.IngestRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult>) responseObserver);
          break;
        case METHODID_INGEST_QUEUE:
          serviceImpl.ingestQueue((com.jervis.contracts.knowledgebase.IngestRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestQueueAck>) responseObserver);
          break;
        case METHODID_INGEST_FILE:
          serviceImpl.ingestFile((com.jervis.contracts.knowledgebase.IngestFileRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult>) responseObserver);
          break;
        case METHODID_INGEST_FULL:
          serviceImpl.ingestFull((com.jervis.contracts.knowledgebase.FullIngestRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.FullIngestResult>) responseObserver);
          break;
        case METHODID_INGEST_FULL_ASYNC:
          serviceImpl.ingestFullAsync((com.jervis.contracts.knowledgebase.AsyncFullIngestRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.AsyncIngestAck>) responseObserver);
          break;
        case METHODID_INGEST_GIT_STRUCTURE:
          serviceImpl.ingestGitStructure((com.jervis.contracts.knowledgebase.GitStructureIngestRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GitStructureIngestResult>) responseObserver);
          break;
        case METHODID_INGEST_GIT_COMMITS:
          serviceImpl.ingestGitCommits((com.jervis.contracts.knowledgebase.GitCommitIngestRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.GitCommitIngestResult>) responseObserver);
          break;
        case METHODID_INGEST_CPG:
          serviceImpl.ingestCpg((com.jervis.contracts.knowledgebase.CpgIngestRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.CpgIngestResult>) responseObserver);
          break;
        case METHODID_CRAWL:
          serviceImpl.crawl((com.jervis.contracts.knowledgebase.CrawlRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.IngestResult>) responseObserver);
          break;
        case METHODID_PURGE:
          serviceImpl.purge((com.jervis.contracts.knowledgebase.PurgeRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.knowledgebase.PurgeResult>) responseObserver);
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
          getIngestMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.IngestRequest,
              com.jervis.contracts.knowledgebase.IngestResult>(
                service, METHODID_INGEST)))
        .addMethod(
          getIngestImmediateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.IngestRequest,
              com.jervis.contracts.knowledgebase.IngestResult>(
                service, METHODID_INGEST_IMMEDIATE)))
        .addMethod(
          getIngestQueueMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.IngestRequest,
              com.jervis.contracts.knowledgebase.IngestQueueAck>(
                service, METHODID_INGEST_QUEUE)))
        .addMethod(
          getIngestFileMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.IngestFileRequest,
              com.jervis.contracts.knowledgebase.IngestResult>(
                service, METHODID_INGEST_FILE)))
        .addMethod(
          getIngestFullMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.FullIngestRequest,
              com.jervis.contracts.knowledgebase.FullIngestResult>(
                service, METHODID_INGEST_FULL)))
        .addMethod(
          getIngestFullAsyncMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.AsyncFullIngestRequest,
              com.jervis.contracts.knowledgebase.AsyncIngestAck>(
                service, METHODID_INGEST_FULL_ASYNC)))
        .addMethod(
          getIngestGitStructureMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.GitStructureIngestRequest,
              com.jervis.contracts.knowledgebase.GitStructureIngestResult>(
                service, METHODID_INGEST_GIT_STRUCTURE)))
        .addMethod(
          getIngestGitCommitsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.GitCommitIngestRequest,
              com.jervis.contracts.knowledgebase.GitCommitIngestResult>(
                service, METHODID_INGEST_GIT_COMMITS)))
        .addMethod(
          getIngestCpgMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.CpgIngestRequest,
              com.jervis.contracts.knowledgebase.CpgIngestResult>(
                service, METHODID_INGEST_CPG)))
        .addMethod(
          getCrawlMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.CrawlRequest,
              com.jervis.contracts.knowledgebase.IngestResult>(
                service, METHODID_CRAWL)))
        .addMethod(
          getPurgeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.knowledgebase.PurgeRequest,
              com.jervis.contracts.knowledgebase.PurgeResult>(
                service, METHODID_PURGE)))
        .build();
  }

  private static abstract class KnowledgeIngestServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    KnowledgeIngestServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.knowledgebase.KnowledgeIngestProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("KnowledgeIngestService");
    }
  }

  private static final class KnowledgeIngestServiceFileDescriptorSupplier
      extends KnowledgeIngestServiceBaseDescriptorSupplier {
    KnowledgeIngestServiceFileDescriptorSupplier() {}
  }

  private static final class KnowledgeIngestServiceMethodDescriptorSupplier
      extends KnowledgeIngestServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    KnowledgeIngestServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (KnowledgeIngestServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new KnowledgeIngestServiceFileDescriptorSupplier())
              .addMethod(getIngestMethod())
              .addMethod(getIngestImmediateMethod())
              .addMethod(getIngestQueueMethod())
              .addMethod(getIngestFileMethod())
              .addMethod(getIngestFullMethod())
              .addMethod(getIngestFullAsyncMethod())
              .addMethod(getIngestGitStructureMethod())
              .addMethod(getIngestGitCommitsMethod())
              .addMethod(getIngestCpgMethod())
              .addMethod(getCrawlMethod())
              .addMethod(getPurgeMethod())
              .build();
        }
      }
    }
    return result;
  }
}
