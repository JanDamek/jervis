package com.jervis.contracts.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ServerTaskApiService covers the core task CRUD + queue inspection +
 * chat-agent helpers. The broader task surface (agent-dispatched /
 * agent-completed callbacks, dispatch-coding-agent, dismiss-user-tasks,
 * create-background-task) lives on KtorRpcServer inline and migrates in
 * a follow-up slice.
 * Task list responses use `items_json` because TaskDocument is a deep
 * tree that Python only reads at the surface (id/title/state/clientId).
 * Typed fields live on requests only.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerTaskApiServiceGrpc {

  private ServerTaskApiServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "jervis.server.ServerTaskApiService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateTaskRequest,
      com.jervis.contracts.server.CreateTaskResponse> getCreateTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateTask",
      requestType = com.jervis.contracts.server.CreateTaskRequest.class,
      responseType = com.jervis.contracts.server.CreateTaskResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateTaskRequest,
      com.jervis.contracts.server.CreateTaskResponse> getCreateTaskMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateTaskRequest, com.jervis.contracts.server.CreateTaskResponse> getCreateTaskMethod;
    if ((getCreateTaskMethod = ServerTaskApiServiceGrpc.getCreateTaskMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getCreateTaskMethod = ServerTaskApiServiceGrpc.getCreateTaskMethod) == null) {
          ServerTaskApiServiceGrpc.getCreateTaskMethod = getCreateTaskMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateTaskRequest, com.jervis.contracts.server.CreateTaskResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateTaskRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateTaskResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("CreateTask"))
              .build();
        }
      }
    }
    return getCreateTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.RespondToTaskRequest,
      com.jervis.contracts.server.RespondToTaskResponse> getRespondToTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondToTask",
      requestType = com.jervis.contracts.server.RespondToTaskRequest.class,
      responseType = com.jervis.contracts.server.RespondToTaskResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.RespondToTaskRequest,
      com.jervis.contracts.server.RespondToTaskResponse> getRespondToTaskMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.RespondToTaskRequest, com.jervis.contracts.server.RespondToTaskResponse> getRespondToTaskMethod;
    if ((getRespondToTaskMethod = ServerTaskApiServiceGrpc.getRespondToTaskMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getRespondToTaskMethod = ServerTaskApiServiceGrpc.getRespondToTaskMethod) == null) {
          ServerTaskApiServiceGrpc.getRespondToTaskMethod = getRespondToTaskMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.RespondToTaskRequest, com.jervis.contracts.server.RespondToTaskResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondToTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.RespondToTaskRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.RespondToTaskResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("RespondToTask"))
              .build();
        }
      }
    }
    return getRespondToTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.GetTaskResponse> getGetTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTask",
      requestType = com.jervis.contracts.server.TaskIdRequest.class,
      responseType = com.jervis.contracts.server.GetTaskResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.GetTaskResponse> getGetTaskMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.GetTaskResponse> getGetTaskMethod;
    if ((getGetTaskMethod = ServerTaskApiServiceGrpc.getGetTaskMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getGetTaskMethod = ServerTaskApiServiceGrpc.getGetTaskMethod) == null) {
          ServerTaskApiServiceGrpc.getGetTaskMethod = getGetTaskMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.GetTaskResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetTaskResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("GetTask"))
              .build();
        }
      }
    }
    return getGetTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.GetTaskStatusResponse> getGetTaskStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTaskStatus",
      requestType = com.jervis.contracts.server.TaskIdRequest.class,
      responseType = com.jervis.contracts.server.GetTaskStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.GetTaskStatusResponse> getGetTaskStatusMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.GetTaskStatusResponse> getGetTaskStatusMethod;
    if ((getGetTaskStatusMethod = ServerTaskApiServiceGrpc.getGetTaskStatusMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getGetTaskStatusMethod = ServerTaskApiServiceGrpc.getGetTaskStatusMethod) == null) {
          ServerTaskApiServiceGrpc.getGetTaskStatusMethod = getGetTaskStatusMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.GetTaskStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTaskStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetTaskStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("GetTaskStatus"))
              .build();
        }
      }
    }
    return getGetTaskStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.SearchTasksRequest,
      com.jervis.contracts.server.TaskListResponse> getSearchTasksMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SearchTasks",
      requestType = com.jervis.contracts.server.SearchTasksRequest.class,
      responseType = com.jervis.contracts.server.TaskListResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.SearchTasksRequest,
      com.jervis.contracts.server.TaskListResponse> getSearchTasksMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.SearchTasksRequest, com.jervis.contracts.server.TaskListResponse> getSearchTasksMethod;
    if ((getSearchTasksMethod = ServerTaskApiServiceGrpc.getSearchTasksMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getSearchTasksMethod = ServerTaskApiServiceGrpc.getSearchTasksMethod) == null) {
          ServerTaskApiServiceGrpc.getSearchTasksMethod = getSearchTasksMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.SearchTasksRequest, com.jervis.contracts.server.TaskListResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SearchTasks"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SearchTasksRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskListResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("SearchTasks"))
              .build();
        }
      }
    }
    return getSearchTasksMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateWorkPlanRequest,
      com.jervis.contracts.server.CreateWorkPlanResponse> getCreateWorkPlanMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateWorkPlan",
      requestType = com.jervis.contracts.server.CreateWorkPlanRequest.class,
      responseType = com.jervis.contracts.server.CreateWorkPlanResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateWorkPlanRequest,
      com.jervis.contracts.server.CreateWorkPlanResponse> getCreateWorkPlanMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.CreateWorkPlanRequest, com.jervis.contracts.server.CreateWorkPlanResponse> getCreateWorkPlanMethod;
    if ((getCreateWorkPlanMethod = ServerTaskApiServiceGrpc.getCreateWorkPlanMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getCreateWorkPlanMethod = ServerTaskApiServiceGrpc.getCreateWorkPlanMethod) == null) {
          ServerTaskApiServiceGrpc.getCreateWorkPlanMethod = getCreateWorkPlanMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.CreateWorkPlanRequest, com.jervis.contracts.server.CreateWorkPlanResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateWorkPlan"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateWorkPlanRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.CreateWorkPlanResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("CreateWorkPlan"))
              .build();
        }
      }
    }
    return getCreateWorkPlanMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.RecentTasksRequest,
      com.jervis.contracts.server.TaskListResponse> getRecentTasksMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RecentTasks",
      requestType = com.jervis.contracts.server.RecentTasksRequest.class,
      responseType = com.jervis.contracts.server.TaskListResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.RecentTasksRequest,
      com.jervis.contracts.server.TaskListResponse> getRecentTasksMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.RecentTasksRequest, com.jervis.contracts.server.TaskListResponse> getRecentTasksMethod;
    if ((getRecentTasksMethod = ServerTaskApiServiceGrpc.getRecentTasksMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getRecentTasksMethod = ServerTaskApiServiceGrpc.getRecentTasksMethod) == null) {
          ServerTaskApiServiceGrpc.getRecentTasksMethod = getRecentTasksMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.RecentTasksRequest, com.jervis.contracts.server.TaskListResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RecentTasks"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.RecentTasksRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskListResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("RecentTasks"))
              .build();
        }
      }
    }
    return getRecentTasksMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.GetQueueRequest,
      com.jervis.contracts.server.TaskListResponse> getGetQueueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetQueue",
      requestType = com.jervis.contracts.server.GetQueueRequest.class,
      responseType = com.jervis.contracts.server.TaskListResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.GetQueueRequest,
      com.jervis.contracts.server.TaskListResponse> getGetQueueMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.GetQueueRequest, com.jervis.contracts.server.TaskListResponse> getGetQueueMethod;
    if ((getGetQueueMethod = ServerTaskApiServiceGrpc.getGetQueueMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getGetQueueMethod = ServerTaskApiServiceGrpc.getGetQueueMethod) == null) {
          ServerTaskApiServiceGrpc.getGetQueueMethod = getGetQueueMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.GetQueueRequest, com.jervis.contracts.server.TaskListResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetQueue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.GetQueueRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskListResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("GetQueue"))
              .build();
        }
      }
    }
    return getGetQueueMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.SimpleTaskActionResponse> getRetryTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RetryTask",
      requestType = com.jervis.contracts.server.TaskIdRequest.class,
      responseType = com.jervis.contracts.server.SimpleTaskActionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.SimpleTaskActionResponse> getRetryTaskMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.SimpleTaskActionResponse> getRetryTaskMethod;
    if ((getRetryTaskMethod = ServerTaskApiServiceGrpc.getRetryTaskMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getRetryTaskMethod = ServerTaskApiServiceGrpc.getRetryTaskMethod) == null) {
          ServerTaskApiServiceGrpc.getRetryTaskMethod = getRetryTaskMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.SimpleTaskActionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RetryTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SimpleTaskActionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("RetryTask"))
              .build();
        }
      }
    }
    return getRetryTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.SimpleTaskActionResponse> getCancelTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CancelTask",
      requestType = com.jervis.contracts.server.TaskIdRequest.class,
      responseType = com.jervis.contracts.server.SimpleTaskActionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest,
      com.jervis.contracts.server.SimpleTaskActionResponse> getCancelTaskMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.SimpleTaskActionResponse> getCancelTaskMethod;
    if ((getCancelTaskMethod = ServerTaskApiServiceGrpc.getCancelTaskMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getCancelTaskMethod = ServerTaskApiServiceGrpc.getCancelTaskMethod) == null) {
          ServerTaskApiServiceGrpc.getCancelTaskMethod = getCancelTaskMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TaskIdRequest, com.jervis.contracts.server.SimpleTaskActionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CancelTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SimpleTaskActionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("CancelTask"))
              .build();
        }
      }
    }
    return getCancelTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskNoteRequest,
      com.jervis.contracts.server.SimpleTaskActionResponse> getMarkDoneMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MarkDone",
      requestType = com.jervis.contracts.server.TaskNoteRequest.class,
      responseType = com.jervis.contracts.server.SimpleTaskActionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskNoteRequest,
      com.jervis.contracts.server.SimpleTaskActionResponse> getMarkDoneMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskNoteRequest, com.jervis.contracts.server.SimpleTaskActionResponse> getMarkDoneMethod;
    if ((getMarkDoneMethod = ServerTaskApiServiceGrpc.getMarkDoneMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getMarkDoneMethod = ServerTaskApiServiceGrpc.getMarkDoneMethod) == null) {
          ServerTaskApiServiceGrpc.getMarkDoneMethod = getMarkDoneMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TaskNoteRequest, com.jervis.contracts.server.SimpleTaskActionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MarkDone"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskNoteRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SimpleTaskActionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("MarkDone"))
              .build();
        }
      }
    }
    return getMarkDoneMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskNoteRequest,
      com.jervis.contracts.server.SimpleTaskActionResponse> getReopenMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Reopen",
      requestType = com.jervis.contracts.server.TaskNoteRequest.class,
      responseType = com.jervis.contracts.server.SimpleTaskActionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskNoteRequest,
      com.jervis.contracts.server.SimpleTaskActionResponse> getReopenMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.TaskNoteRequest, com.jervis.contracts.server.SimpleTaskActionResponse> getReopenMethod;
    if ((getReopenMethod = ServerTaskApiServiceGrpc.getReopenMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getReopenMethod = ServerTaskApiServiceGrpc.getReopenMethod) == null) {
          ServerTaskApiServiceGrpc.getReopenMethod = getReopenMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.TaskNoteRequest, com.jervis.contracts.server.SimpleTaskActionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Reopen"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.TaskNoteRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SimpleTaskActionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("Reopen"))
              .build();
        }
      }
    }
    return getReopenMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.SetPriorityRequest,
      com.jervis.contracts.server.SetPriorityResponse> getSetPriorityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SetPriority",
      requestType = com.jervis.contracts.server.SetPriorityRequest.class,
      responseType = com.jervis.contracts.server.SetPriorityResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.SetPriorityRequest,
      com.jervis.contracts.server.SetPriorityResponse> getSetPriorityMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.SetPriorityRequest, com.jervis.contracts.server.SetPriorityResponse> getSetPriorityMethod;
    if ((getSetPriorityMethod = ServerTaskApiServiceGrpc.getSetPriorityMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getSetPriorityMethod = ServerTaskApiServiceGrpc.getSetPriorityMethod) == null) {
          ServerTaskApiServiceGrpc.getSetPriorityMethod = getSetPriorityMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.SetPriorityRequest, com.jervis.contracts.server.SetPriorityResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SetPriority"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SetPriorityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.SetPriorityResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("SetPriority"))
              .build();
        }
      }
    }
    return getSetPriorityMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.PushNotificationRequest,
      com.jervis.contracts.server.PushNotificationResponse> getPushNotificationMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PushNotification",
      requestType = com.jervis.contracts.server.PushNotificationRequest.class,
      responseType = com.jervis.contracts.server.PushNotificationResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.PushNotificationRequest,
      com.jervis.contracts.server.PushNotificationResponse> getPushNotificationMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.PushNotificationRequest, com.jervis.contracts.server.PushNotificationResponse> getPushNotificationMethod;
    if ((getPushNotificationMethod = ServerTaskApiServiceGrpc.getPushNotificationMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getPushNotificationMethod = ServerTaskApiServiceGrpc.getPushNotificationMethod) == null) {
          ServerTaskApiServiceGrpc.getPushNotificationMethod = getPushNotificationMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.PushNotificationRequest, com.jervis.contracts.server.PushNotificationResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PushNotification"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PushNotificationRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PushNotificationResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("PushNotification"))
              .build();
        }
      }
    }
    return getPushNotificationMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.jervis.contracts.server.PushBackgroundResultRequest,
      com.jervis.contracts.server.PushBackgroundResultResponse> getPushBackgroundResultMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PushBackgroundResult",
      requestType = com.jervis.contracts.server.PushBackgroundResultRequest.class,
      responseType = com.jervis.contracts.server.PushBackgroundResultResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.jervis.contracts.server.PushBackgroundResultRequest,
      com.jervis.contracts.server.PushBackgroundResultResponse> getPushBackgroundResultMethod() {
    io.grpc.MethodDescriptor<com.jervis.contracts.server.PushBackgroundResultRequest, com.jervis.contracts.server.PushBackgroundResultResponse> getPushBackgroundResultMethod;
    if ((getPushBackgroundResultMethod = ServerTaskApiServiceGrpc.getPushBackgroundResultMethod) == null) {
      synchronized (ServerTaskApiServiceGrpc.class) {
        if ((getPushBackgroundResultMethod = ServerTaskApiServiceGrpc.getPushBackgroundResultMethod) == null) {
          ServerTaskApiServiceGrpc.getPushBackgroundResultMethod = getPushBackgroundResultMethod =
              io.grpc.MethodDescriptor.<com.jervis.contracts.server.PushBackgroundResultRequest, com.jervis.contracts.server.PushBackgroundResultResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PushBackgroundResult"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PushBackgroundResultRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.jervis.contracts.server.PushBackgroundResultResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerTaskApiServiceMethodDescriptorSupplier("PushBackgroundResult"))
              .build();
        }
      }
    }
    return getPushBackgroundResultMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerTaskApiServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTaskApiServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTaskApiServiceStub>() {
        @java.lang.Override
        public ServerTaskApiServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTaskApiServiceStub(channel, callOptions);
        }
      };
    return ServerTaskApiServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerTaskApiServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTaskApiServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTaskApiServiceBlockingV2Stub>() {
        @java.lang.Override
        public ServerTaskApiServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTaskApiServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerTaskApiServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerTaskApiServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTaskApiServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTaskApiServiceBlockingStub>() {
        @java.lang.Override
        public ServerTaskApiServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTaskApiServiceBlockingStub(channel, callOptions);
        }
      };
    return ServerTaskApiServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerTaskApiServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerTaskApiServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerTaskApiServiceFutureStub>() {
        @java.lang.Override
        public ServerTaskApiServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerTaskApiServiceFutureStub(channel, callOptions);
        }
      };
    return ServerTaskApiServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ServerTaskApiService covers the core task CRUD + queue inspection +
   * chat-agent helpers. The broader task surface (agent-dispatched /
   * agent-completed callbacks, dispatch-coding-agent, dismiss-user-tasks,
   * create-background-task) lives on KtorRpcServer inline and migrates in
   * a follow-up slice.
   * Task list responses use `items_json` because TaskDocument is a deep
   * tree that Python only reads at the surface (id/title/state/clientId).
   * Typed fields live on requests only.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void createTask(com.jervis.contracts.server.CreateTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateTaskResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateTaskMethod(), responseObserver);
    }

    /**
     */
    default void respondToTask(com.jervis.contracts.server.RespondToTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.RespondToTaskResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRespondToTaskMethod(), responseObserver);
    }

    /**
     */
    default void getTask(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetTaskResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaskMethod(), responseObserver);
    }

    /**
     */
    default void getTaskStatus(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetTaskStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaskStatusMethod(), responseObserver);
    }

    /**
     */
    default void searchTasks(com.jervis.contracts.server.SearchTasksRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TaskListResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSearchTasksMethod(), responseObserver);
    }

    /**
     */
    default void createWorkPlan(com.jervis.contracts.server.CreateWorkPlanRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateWorkPlanResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateWorkPlanMethod(), responseObserver);
    }

    /**
     */
    default void recentTasks(com.jervis.contracts.server.RecentTasksRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TaskListResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRecentTasksMethod(), responseObserver);
    }

    /**
     */
    default void getQueue(com.jervis.contracts.server.GetQueueRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TaskListResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetQueueMethod(), responseObserver);
    }

    /**
     */
    default void retryTask(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRetryTaskMethod(), responseObserver);
    }

    /**
     */
    default void cancelTask(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCancelTaskMethod(), responseObserver);
    }

    /**
     */
    default void markDone(com.jervis.contracts.server.TaskNoteRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMarkDoneMethod(), responseObserver);
    }

    /**
     */
    default void reopen(com.jervis.contracts.server.TaskNoteRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReopenMethod(), responseObserver);
    }

    /**
     */
    default void setPriority(com.jervis.contracts.server.SetPriorityRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SetPriorityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSetPriorityMethod(), responseObserver);
    }

    /**
     */
    default void pushNotification(com.jervis.contracts.server.PushNotificationRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PushNotificationResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPushNotificationMethod(), responseObserver);
    }

    /**
     */
    default void pushBackgroundResult(com.jervis.contracts.server.PushBackgroundResultRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PushBackgroundResultResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPushBackgroundResultMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerTaskApiService.
   * <pre>
   * ServerTaskApiService covers the core task CRUD + queue inspection +
   * chat-agent helpers. The broader task surface (agent-dispatched /
   * agent-completed callbacks, dispatch-coding-agent, dismiss-user-tasks,
   * create-background-task) lives on KtorRpcServer inline and migrates in
   * a follow-up slice.
   * Task list responses use `items_json` because TaskDocument is a deep
   * tree that Python only reads at the surface (id/title/state/clientId).
   * Typed fields live on requests only.
   * </pre>
   */
  public static abstract class ServerTaskApiServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerTaskApiServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerTaskApiService.
   * <pre>
   * ServerTaskApiService covers the core task CRUD + queue inspection +
   * chat-agent helpers. The broader task surface (agent-dispatched /
   * agent-completed callbacks, dispatch-coding-agent, dismiss-user-tasks,
   * create-background-task) lives on KtorRpcServer inline and migrates in
   * a follow-up slice.
   * Task list responses use `items_json` because TaskDocument is a deep
   * tree that Python only reads at the surface (id/title/state/clientId).
   * Typed fields live on requests only.
   * </pre>
   */
  public static final class ServerTaskApiServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ServerTaskApiServiceStub> {
    private ServerTaskApiServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTaskApiServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTaskApiServiceStub(channel, callOptions);
    }

    /**
     */
    public void createTask(com.jervis.contracts.server.CreateTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateTaskResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void respondToTask(com.jervis.contracts.server.RespondToTaskRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.RespondToTaskResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRespondToTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getTask(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetTaskResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getTaskStatus(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetTaskStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTaskStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void searchTasks(com.jervis.contracts.server.SearchTasksRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TaskListResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSearchTasksMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createWorkPlan(com.jervis.contracts.server.CreateWorkPlanRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateWorkPlanResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateWorkPlanMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void recentTasks(com.jervis.contracts.server.RecentTasksRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TaskListResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRecentTasksMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getQueue(com.jervis.contracts.server.GetQueueRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.TaskListResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetQueueMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void retryTask(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRetryTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void cancelTask(com.jervis.contracts.server.TaskIdRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCancelTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void markDone(com.jervis.contracts.server.TaskNoteRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMarkDoneMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void reopen(com.jervis.contracts.server.TaskNoteRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReopenMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void setPriority(com.jervis.contracts.server.SetPriorityRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.SetPriorityResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSetPriorityMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void pushNotification(com.jervis.contracts.server.PushNotificationRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PushNotificationResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPushNotificationMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void pushBackgroundResult(com.jervis.contracts.server.PushBackgroundResultRequest request,
        io.grpc.stub.StreamObserver<com.jervis.contracts.server.PushBackgroundResultResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPushBackgroundResultMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerTaskApiService.
   * <pre>
   * ServerTaskApiService covers the core task CRUD + queue inspection +
   * chat-agent helpers. The broader task surface (agent-dispatched /
   * agent-completed callbacks, dispatch-coding-agent, dismiss-user-tasks,
   * create-background-task) lives on KtorRpcServer inline and migrates in
   * a follow-up slice.
   * Task list responses use `items_json` because TaskDocument is a deep
   * tree that Python only reads at the surface (id/title/state/clientId).
   * Typed fields live on requests only.
   * </pre>
   */
  public static final class ServerTaskApiServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerTaskApiServiceBlockingV2Stub> {
    private ServerTaskApiServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTaskApiServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTaskApiServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.CreateTaskResponse createTask(com.jervis.contracts.server.CreateTaskRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.RespondToTaskResponse respondToTask(com.jervis.contracts.server.RespondToTaskRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRespondToTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetTaskResponse getTask(com.jervis.contracts.server.TaskIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetTaskStatusResponse getTaskStatus(com.jervis.contracts.server.TaskIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetTaskStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TaskListResponse searchTasks(com.jervis.contracts.server.SearchTasksRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSearchTasksMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateWorkPlanResponse createWorkPlan(com.jervis.contracts.server.CreateWorkPlanRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateWorkPlanMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TaskListResponse recentTasks(com.jervis.contracts.server.RecentTasksRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRecentTasksMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TaskListResponse getQueue(com.jervis.contracts.server.GetQueueRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetQueueMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SimpleTaskActionResponse retryTask(com.jervis.contracts.server.TaskIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRetryTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SimpleTaskActionResponse cancelTask(com.jervis.contracts.server.TaskIdRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCancelTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SimpleTaskActionResponse markDone(com.jervis.contracts.server.TaskNoteRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getMarkDoneMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SimpleTaskActionResponse reopen(com.jervis.contracts.server.TaskNoteRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReopenMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SetPriorityResponse setPriority(com.jervis.contracts.server.SetPriorityRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSetPriorityMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PushNotificationResponse pushNotification(com.jervis.contracts.server.PushNotificationRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPushNotificationMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PushBackgroundResultResponse pushBackgroundResult(com.jervis.contracts.server.PushBackgroundResultRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPushBackgroundResultMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ServerTaskApiService.
   * <pre>
   * ServerTaskApiService covers the core task CRUD + queue inspection +
   * chat-agent helpers. The broader task surface (agent-dispatched /
   * agent-completed callbacks, dispatch-coding-agent, dismiss-user-tasks,
   * create-background-task) lives on KtorRpcServer inline and migrates in
   * a follow-up slice.
   * Task list responses use `items_json` because TaskDocument is a deep
   * tree that Python only reads at the surface (id/title/state/clientId).
   * Typed fields live on requests only.
   * </pre>
   */
  public static final class ServerTaskApiServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerTaskApiServiceBlockingStub> {
    private ServerTaskApiServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTaskApiServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTaskApiServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.jervis.contracts.server.CreateTaskResponse createTask(com.jervis.contracts.server.CreateTaskRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.RespondToTaskResponse respondToTask(com.jervis.contracts.server.RespondToTaskRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRespondToTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetTaskResponse getTask(com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.GetTaskStatusResponse getTaskStatus(com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTaskStatusMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TaskListResponse searchTasks(com.jervis.contracts.server.SearchTasksRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSearchTasksMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.CreateWorkPlanResponse createWorkPlan(com.jervis.contracts.server.CreateWorkPlanRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateWorkPlanMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TaskListResponse recentTasks(com.jervis.contracts.server.RecentTasksRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRecentTasksMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.TaskListResponse getQueue(com.jervis.contracts.server.GetQueueRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetQueueMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SimpleTaskActionResponse retryTask(com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRetryTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SimpleTaskActionResponse cancelTask(com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCancelTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SimpleTaskActionResponse markDone(com.jervis.contracts.server.TaskNoteRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMarkDoneMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SimpleTaskActionResponse reopen(com.jervis.contracts.server.TaskNoteRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReopenMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.SetPriorityResponse setPriority(com.jervis.contracts.server.SetPriorityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSetPriorityMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PushNotificationResponse pushNotification(com.jervis.contracts.server.PushNotificationRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPushNotificationMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.jervis.contracts.server.PushBackgroundResultResponse pushBackgroundResult(com.jervis.contracts.server.PushBackgroundResultRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPushBackgroundResultMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerTaskApiService.
   * <pre>
   * ServerTaskApiService covers the core task CRUD + queue inspection +
   * chat-agent helpers. The broader task surface (agent-dispatched /
   * agent-completed callbacks, dispatch-coding-agent, dismiss-user-tasks,
   * create-background-task) lives on KtorRpcServer inline and migrates in
   * a follow-up slice.
   * Task list responses use `items_json` because TaskDocument is a deep
   * tree that Python only reads at the surface (id/title/state/clientId).
   * Typed fields live on requests only.
   * </pre>
   */
  public static final class ServerTaskApiServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerTaskApiServiceFutureStub> {
    private ServerTaskApiServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerTaskApiServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerTaskApiServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CreateTaskResponse> createTask(
        com.jervis.contracts.server.CreateTaskRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateTaskMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.RespondToTaskResponse> respondToTask(
        com.jervis.contracts.server.RespondToTaskRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRespondToTaskMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetTaskResponse> getTask(
        com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTaskMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.GetTaskStatusResponse> getTaskStatus(
        com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTaskStatusMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.TaskListResponse> searchTasks(
        com.jervis.contracts.server.SearchTasksRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSearchTasksMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.CreateWorkPlanResponse> createWorkPlan(
        com.jervis.contracts.server.CreateWorkPlanRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateWorkPlanMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.TaskListResponse> recentTasks(
        com.jervis.contracts.server.RecentTasksRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRecentTasksMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.TaskListResponse> getQueue(
        com.jervis.contracts.server.GetQueueRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetQueueMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.SimpleTaskActionResponse> retryTask(
        com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRetryTaskMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.SimpleTaskActionResponse> cancelTask(
        com.jervis.contracts.server.TaskIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCancelTaskMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.SimpleTaskActionResponse> markDone(
        com.jervis.contracts.server.TaskNoteRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getMarkDoneMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.SimpleTaskActionResponse> reopen(
        com.jervis.contracts.server.TaskNoteRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReopenMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.SetPriorityResponse> setPriority(
        com.jervis.contracts.server.SetPriorityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSetPriorityMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.PushNotificationResponse> pushNotification(
        com.jervis.contracts.server.PushNotificationRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPushNotificationMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.jervis.contracts.server.PushBackgroundResultResponse> pushBackgroundResult(
        com.jervis.contracts.server.PushBackgroundResultRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPushBackgroundResultMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CREATE_TASK = 0;
  private static final int METHODID_RESPOND_TO_TASK = 1;
  private static final int METHODID_GET_TASK = 2;
  private static final int METHODID_GET_TASK_STATUS = 3;
  private static final int METHODID_SEARCH_TASKS = 4;
  private static final int METHODID_CREATE_WORK_PLAN = 5;
  private static final int METHODID_RECENT_TASKS = 6;
  private static final int METHODID_GET_QUEUE = 7;
  private static final int METHODID_RETRY_TASK = 8;
  private static final int METHODID_CANCEL_TASK = 9;
  private static final int METHODID_MARK_DONE = 10;
  private static final int METHODID_REOPEN = 11;
  private static final int METHODID_SET_PRIORITY = 12;
  private static final int METHODID_PUSH_NOTIFICATION = 13;
  private static final int METHODID_PUSH_BACKGROUND_RESULT = 14;

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
        case METHODID_CREATE_TASK:
          serviceImpl.createTask((com.jervis.contracts.server.CreateTaskRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateTaskResponse>) responseObserver);
          break;
        case METHODID_RESPOND_TO_TASK:
          serviceImpl.respondToTask((com.jervis.contracts.server.RespondToTaskRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.RespondToTaskResponse>) responseObserver);
          break;
        case METHODID_GET_TASK:
          serviceImpl.getTask((com.jervis.contracts.server.TaskIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetTaskResponse>) responseObserver);
          break;
        case METHODID_GET_TASK_STATUS:
          serviceImpl.getTaskStatus((com.jervis.contracts.server.TaskIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.GetTaskStatusResponse>) responseObserver);
          break;
        case METHODID_SEARCH_TASKS:
          serviceImpl.searchTasks((com.jervis.contracts.server.SearchTasksRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.TaskListResponse>) responseObserver);
          break;
        case METHODID_CREATE_WORK_PLAN:
          serviceImpl.createWorkPlan((com.jervis.contracts.server.CreateWorkPlanRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.CreateWorkPlanResponse>) responseObserver);
          break;
        case METHODID_RECENT_TASKS:
          serviceImpl.recentTasks((com.jervis.contracts.server.RecentTasksRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.TaskListResponse>) responseObserver);
          break;
        case METHODID_GET_QUEUE:
          serviceImpl.getQueue((com.jervis.contracts.server.GetQueueRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.TaskListResponse>) responseObserver);
          break;
        case METHODID_RETRY_TASK:
          serviceImpl.retryTask((com.jervis.contracts.server.TaskIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse>) responseObserver);
          break;
        case METHODID_CANCEL_TASK:
          serviceImpl.cancelTask((com.jervis.contracts.server.TaskIdRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse>) responseObserver);
          break;
        case METHODID_MARK_DONE:
          serviceImpl.markDone((com.jervis.contracts.server.TaskNoteRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse>) responseObserver);
          break;
        case METHODID_REOPEN:
          serviceImpl.reopen((com.jervis.contracts.server.TaskNoteRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.SimpleTaskActionResponse>) responseObserver);
          break;
        case METHODID_SET_PRIORITY:
          serviceImpl.setPriority((com.jervis.contracts.server.SetPriorityRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.SetPriorityResponse>) responseObserver);
          break;
        case METHODID_PUSH_NOTIFICATION:
          serviceImpl.pushNotification((com.jervis.contracts.server.PushNotificationRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.PushNotificationResponse>) responseObserver);
          break;
        case METHODID_PUSH_BACKGROUND_RESULT:
          serviceImpl.pushBackgroundResult((com.jervis.contracts.server.PushBackgroundResultRequest) request,
              (io.grpc.stub.StreamObserver<com.jervis.contracts.server.PushBackgroundResultResponse>) responseObserver);
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
          getCreateTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateTaskRequest,
              com.jervis.contracts.server.CreateTaskResponse>(
                service, METHODID_CREATE_TASK)))
        .addMethod(
          getRespondToTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.RespondToTaskRequest,
              com.jervis.contracts.server.RespondToTaskResponse>(
                service, METHODID_RESPOND_TO_TASK)))
        .addMethod(
          getGetTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TaskIdRequest,
              com.jervis.contracts.server.GetTaskResponse>(
                service, METHODID_GET_TASK)))
        .addMethod(
          getGetTaskStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TaskIdRequest,
              com.jervis.contracts.server.GetTaskStatusResponse>(
                service, METHODID_GET_TASK_STATUS)))
        .addMethod(
          getSearchTasksMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.SearchTasksRequest,
              com.jervis.contracts.server.TaskListResponse>(
                service, METHODID_SEARCH_TASKS)))
        .addMethod(
          getCreateWorkPlanMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.CreateWorkPlanRequest,
              com.jervis.contracts.server.CreateWorkPlanResponse>(
                service, METHODID_CREATE_WORK_PLAN)))
        .addMethod(
          getRecentTasksMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.RecentTasksRequest,
              com.jervis.contracts.server.TaskListResponse>(
                service, METHODID_RECENT_TASKS)))
        .addMethod(
          getGetQueueMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.GetQueueRequest,
              com.jervis.contracts.server.TaskListResponse>(
                service, METHODID_GET_QUEUE)))
        .addMethod(
          getRetryTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TaskIdRequest,
              com.jervis.contracts.server.SimpleTaskActionResponse>(
                service, METHODID_RETRY_TASK)))
        .addMethod(
          getCancelTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TaskIdRequest,
              com.jervis.contracts.server.SimpleTaskActionResponse>(
                service, METHODID_CANCEL_TASK)))
        .addMethod(
          getMarkDoneMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TaskNoteRequest,
              com.jervis.contracts.server.SimpleTaskActionResponse>(
                service, METHODID_MARK_DONE)))
        .addMethod(
          getReopenMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.TaskNoteRequest,
              com.jervis.contracts.server.SimpleTaskActionResponse>(
                service, METHODID_REOPEN)))
        .addMethod(
          getSetPriorityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.SetPriorityRequest,
              com.jervis.contracts.server.SetPriorityResponse>(
                service, METHODID_SET_PRIORITY)))
        .addMethod(
          getPushNotificationMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.PushNotificationRequest,
              com.jervis.contracts.server.PushNotificationResponse>(
                service, METHODID_PUSH_NOTIFICATION)))
        .addMethod(
          getPushBackgroundResultMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.jervis.contracts.server.PushBackgroundResultRequest,
              com.jervis.contracts.server.PushBackgroundResultResponse>(
                service, METHODID_PUSH_BACKGROUND_RESULT)))
        .build();
  }

  private static abstract class ServerTaskApiServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerTaskApiServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.jervis.contracts.server.ServerTaskApiProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerTaskApiService");
    }
  }

  private static final class ServerTaskApiServiceFileDescriptorSupplier
      extends ServerTaskApiServiceBaseDescriptorSupplier {
    ServerTaskApiServiceFileDescriptorSupplier() {}
  }

  private static final class ServerTaskApiServiceMethodDescriptorSupplier
      extends ServerTaskApiServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerTaskApiServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerTaskApiServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerTaskApiServiceFileDescriptorSupplier())
              .addMethod(getCreateTaskMethod())
              .addMethod(getRespondToTaskMethod())
              .addMethod(getGetTaskMethod())
              .addMethod(getGetTaskStatusMethod())
              .addMethod(getSearchTasksMethod())
              .addMethod(getCreateWorkPlanMethod())
              .addMethod(getRecentTasksMethod())
              .addMethod(getGetQueueMethod())
              .addMethod(getRetryTaskMethod())
              .addMethod(getCancelTaskMethod())
              .addMethod(getMarkDoneMethod())
              .addMethod(getReopenMethod())
              .addMethod(getSetPriorityMethod())
              .addMethod(getPushNotificationMethod())
              .addMethod(getPushBackgroundResultMethod())
              .build();
        }
      }
    }
    return result;
  }
}
