package com.jervis.contracts.server

import com.jervis.contracts.server.ServerTaskApiServiceGrpc.getServiceDescriptor
import io.grpc.CallOptions
import io.grpc.CallOptions.DEFAULT
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.ServerServiceDefinition.builder
import io.grpc.ServiceDescriptor
import io.grpc.Status.UNIMPLEMENTED
import io.grpc.StatusException
import io.grpc.kotlin.AbstractCoroutineServerImpl
import io.grpc.kotlin.AbstractCoroutineStub
import io.grpc.kotlin.ClientCalls.unaryRpc
import io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition
import io.grpc.kotlin.StubFor
import kotlin.String
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerTaskApiService.
 */
public object ServerTaskApiServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerTaskApiServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val createTaskMethod: MethodDescriptor<CreateTaskRequest, CreateTaskResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getCreateTaskMethod()

  public val respondToTaskMethod: MethodDescriptor<RespondToTaskRequest, RespondToTaskResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getRespondToTaskMethod()

  public val getTaskMethod: MethodDescriptor<TaskIdRequest, GetTaskResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getGetTaskMethod()

  public val getTaskStatusMethod: MethodDescriptor<TaskIdRequest, GetTaskStatusResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getGetTaskStatusMethod()

  public val searchTasksMethod: MethodDescriptor<SearchTasksRequest, TaskListResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getSearchTasksMethod()

  public val createWorkPlanMethod: MethodDescriptor<CreateWorkPlanRequest, CreateWorkPlanResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getCreateWorkPlanMethod()

  public val recentTasksMethod: MethodDescriptor<RecentTasksRequest, TaskListResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getRecentTasksMethod()

  public val getQueueMethod: MethodDescriptor<GetQueueRequest, TaskListResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getGetQueueMethod()

  public val retryTaskMethod: MethodDescriptor<TaskIdRequest, SimpleTaskActionResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getRetryTaskMethod()

  public val cancelTaskMethod: MethodDescriptor<TaskIdRequest, SimpleTaskActionResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getCancelTaskMethod()

  public val markDoneMethod: MethodDescriptor<TaskNoteRequest, SimpleTaskActionResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getMarkDoneMethod()

  public val reopenMethod: MethodDescriptor<TaskNoteRequest, SimpleTaskActionResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getReopenMethod()

  public val setPriorityMethod: MethodDescriptor<SetPriorityRequest, SetPriorityResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getSetPriorityMethod()

  public val pushNotificationMethod:
      MethodDescriptor<PushNotificationRequest, PushNotificationResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getPushNotificationMethod()

  public val pushBackgroundResultMethod:
      MethodDescriptor<PushBackgroundResultRequest, PushBackgroundResultResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getPushBackgroundResultMethod()

  public val tasksByStateMethod: MethodDescriptor<TasksByStateRequest, TaskListResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getTasksByStateMethod()

  public val agentDispatchedMethod:
      MethodDescriptor<AgentDispatchedRequest, SimpleTaskActionResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getAgentDispatchedMethod()

  public val agentCompletedMethod: MethodDescriptor<TaskIdRequest, SimpleTaskActionResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getAgentCompletedMethod()

  public val createBackgroundTaskMethod:
      MethodDescriptor<CreateBackgroundTaskRequest, CreateBackgroundTaskResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getCreateBackgroundTaskMethod()

  public val dispatchCodingAgentMethod:
      MethodDescriptor<DispatchCodingAgentRequest, DispatchCodingAgentResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getDispatchCodingAgentMethod()

  public val listUserTasksMethod: MethodDescriptor<ListUserTasksRequest, TaskListResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getListUserTasksMethod()

  public val getUserTaskMethod: MethodDescriptor<TaskIdRequest, UserTaskResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getGetUserTaskMethod()

  public val respondToUserTaskMethod:
      MethodDescriptor<RespondToUserTaskRequest, SimpleTaskActionResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getRespondToUserTaskMethod()

  public val dismissUserTasksMethod:
      MethodDescriptor<DismissUserTasksRequest, DismissUserTasksResponse>
    @JvmStatic
    get() = ServerTaskApiServiceGrpc.getDismissUserTasksMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerTaskApiService service as suspending coroutines.
   */
  @StubFor(ServerTaskApiServiceGrpc::class)
  public class ServerTaskApiServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerTaskApiServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerTaskApiServiceCoroutineStub = ServerTaskApiServiceCoroutineStub(channel, callOptions)

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun createTask(request: CreateTaskRequest, headers: Metadata = Metadata()): CreateTaskResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getCreateTaskMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun respondToTask(request: RespondToTaskRequest, headers: Metadata = Metadata()): RespondToTaskResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getRespondToTaskMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun getTask(request: TaskIdRequest, headers: Metadata = Metadata()): GetTaskResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getGetTaskMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun getTaskStatus(request: TaskIdRequest, headers: Metadata = Metadata()): GetTaskStatusResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getGetTaskStatusMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun searchTasks(request: SearchTasksRequest, headers: Metadata = Metadata()): TaskListResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getSearchTasksMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun createWorkPlan(request: CreateWorkPlanRequest, headers: Metadata = Metadata()): CreateWorkPlanResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getCreateWorkPlanMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun recentTasks(request: RecentTasksRequest, headers: Metadata = Metadata()): TaskListResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getRecentTasksMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun getQueue(request: GetQueueRequest, headers: Metadata = Metadata()): TaskListResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getGetQueueMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun retryTask(request: TaskIdRequest, headers: Metadata = Metadata()): SimpleTaskActionResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getRetryTaskMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun cancelTask(request: TaskIdRequest, headers: Metadata = Metadata()): SimpleTaskActionResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getCancelTaskMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun markDone(request: TaskNoteRequest, headers: Metadata = Metadata()): SimpleTaskActionResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getMarkDoneMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun reopen(request: TaskNoteRequest, headers: Metadata = Metadata()): SimpleTaskActionResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getReopenMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun setPriority(request: SetPriorityRequest, headers: Metadata = Metadata()): SetPriorityResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getSetPriorityMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun pushNotification(request: PushNotificationRequest, headers: Metadata = Metadata()): PushNotificationResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getPushNotificationMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun pushBackgroundResult(request: PushBackgroundResultRequest, headers: Metadata = Metadata()): PushBackgroundResultResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getPushBackgroundResultMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun tasksByState(request: TasksByStateRequest, headers: Metadata = Metadata()): TaskListResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getTasksByStateMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun agentDispatched(request: AgentDispatchedRequest, headers: Metadata = Metadata()): SimpleTaskActionResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getAgentDispatchedMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun agentCompleted(request: TaskIdRequest, headers: Metadata = Metadata()): SimpleTaskActionResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getAgentCompletedMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun createBackgroundTask(request: CreateBackgroundTaskRequest, headers: Metadata = Metadata()): CreateBackgroundTaskResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getCreateBackgroundTaskMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun dispatchCodingAgent(request: DispatchCodingAgentRequest, headers: Metadata = Metadata()): DispatchCodingAgentResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getDispatchCodingAgentMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun listUserTasks(request: ListUserTasksRequest, headers: Metadata = Metadata()): TaskListResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getListUserTasksMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun getUserTask(request: TaskIdRequest, headers: Metadata = Metadata()): UserTaskResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getGetUserTaskMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun respondToUserTask(request: RespondToUserTaskRequest, headers: Metadata = Metadata()): SimpleTaskActionResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getRespondToUserTaskMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun dismissUserTasks(request: DismissUserTasksRequest, headers: Metadata = Metadata()): DismissUserTasksResponse = unaryRpc(
      channel,
      ServerTaskApiServiceGrpc.getDismissUserTasksMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerTaskApiService service based on Kotlin coroutines.
   */
  public abstract class ServerTaskApiServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.CreateTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createTask(request: CreateTaskRequest): CreateTaskResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.CreateTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.RespondToTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun respondToTask(request: RespondToTaskRequest): RespondToTaskResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.RespondToTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.GetTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getTask(request: TaskIdRequest): GetTaskResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.GetTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.GetTaskStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getTaskStatus(request: TaskIdRequest): GetTaskStatusResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.GetTaskStatus is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.SearchTasks.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun searchTasks(request: SearchTasksRequest): TaskListResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.SearchTasks is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.CreateWorkPlan.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createWorkPlan(request: CreateWorkPlanRequest): CreateWorkPlanResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.CreateWorkPlan is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.RecentTasks.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun recentTasks(request: RecentTasksRequest): TaskListResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.RecentTasks is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.GetQueue.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getQueue(request: GetQueueRequest): TaskListResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.GetQueue is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.RetryTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun retryTask(request: TaskIdRequest): SimpleTaskActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.RetryTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.CancelTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun cancelTask(request: TaskIdRequest): SimpleTaskActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.CancelTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.MarkDone.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun markDone(request: TaskNoteRequest): SimpleTaskActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.MarkDone is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.Reopen.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun reopen(request: TaskNoteRequest): SimpleTaskActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.Reopen is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.SetPriority.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun setPriority(request: SetPriorityRequest): SetPriorityResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.SetPriority is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.PushNotification.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun pushNotification(request: PushNotificationRequest): PushNotificationResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.PushNotification is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.PushBackgroundResult.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun pushBackgroundResult(request: PushBackgroundResultRequest): PushBackgroundResultResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.PushBackgroundResult is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.TasksByState.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun tasksByState(request: TasksByStateRequest): TaskListResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.TasksByState is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.AgentDispatched.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun agentDispatched(request: AgentDispatchedRequest): SimpleTaskActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.AgentDispatched is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.AgentCompleted.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun agentCompleted(request: TaskIdRequest): SimpleTaskActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.AgentCompleted is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.CreateBackgroundTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createBackgroundTask(request: CreateBackgroundTaskRequest): CreateBackgroundTaskResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.CreateBackgroundTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.DispatchCodingAgent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun dispatchCodingAgent(request: DispatchCodingAgentRequest): DispatchCodingAgentResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.DispatchCodingAgent is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.ListUserTasks.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listUserTasks(request: ListUserTasksRequest): TaskListResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.ListUserTasks is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.GetUserTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getUserTask(request: TaskIdRequest): UserTaskResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.GetUserTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.RespondToUserTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun respondToUserTask(request: RespondToUserTaskRequest): SimpleTaskActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.RespondToUserTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskApiService.DismissUserTasks.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun dismissUserTasks(request: DismissUserTasksRequest): DismissUserTasksResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskApiService.DismissUserTasks is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getCreateTaskMethod(),
      implementation = ::createTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getRespondToTaskMethod(),
      implementation = ::respondToTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getGetTaskMethod(),
      implementation = ::getTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getGetTaskStatusMethod(),
      implementation = ::getTaskStatus
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getSearchTasksMethod(),
      implementation = ::searchTasks
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getCreateWorkPlanMethod(),
      implementation = ::createWorkPlan
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getRecentTasksMethod(),
      implementation = ::recentTasks
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getGetQueueMethod(),
      implementation = ::getQueue
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getRetryTaskMethod(),
      implementation = ::retryTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getCancelTaskMethod(),
      implementation = ::cancelTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getMarkDoneMethod(),
      implementation = ::markDone
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getReopenMethod(),
      implementation = ::reopen
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getSetPriorityMethod(),
      implementation = ::setPriority
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getPushNotificationMethod(),
      implementation = ::pushNotification
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getPushBackgroundResultMethod(),
      implementation = ::pushBackgroundResult
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getTasksByStateMethod(),
      implementation = ::tasksByState
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getAgentDispatchedMethod(),
      implementation = ::agentDispatched
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getAgentCompletedMethod(),
      implementation = ::agentCompleted
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getCreateBackgroundTaskMethod(),
      implementation = ::createBackgroundTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getDispatchCodingAgentMethod(),
      implementation = ::dispatchCodingAgent
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getListUserTasksMethod(),
      implementation = ::listUserTasks
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getGetUserTaskMethod(),
      implementation = ::getUserTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getRespondToUserTaskMethod(),
      implementation = ::respondToUserTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskApiServiceGrpc.getDismissUserTasksMethod(),
      implementation = ::dismissUserTasks
    )).build()
  }
}
