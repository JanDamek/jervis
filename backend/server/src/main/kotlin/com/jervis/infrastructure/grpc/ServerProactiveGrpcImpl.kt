package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.MorningBriefingRequest
import com.jervis.contracts.server.MorningBriefingResponse
import com.jervis.contracts.server.OverdueCheckRequest
import com.jervis.contracts.server.OverdueCheckResponse
import com.jervis.contracts.server.ServerProactiveServiceGrpcKt
import com.jervis.contracts.server.VipAlertRequest
import com.jervis.contracts.server.VipAlertResponse
import com.jervis.contracts.server.WeeklySummaryRequest
import com.jervis.contracts.server.WeeklySummaryResponse
import com.jervis.proactive.ProactiveScheduler
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerProactiveGrpcImpl(
    private val proactiveScheduler: ProactiveScheduler,
) : ServerProactiveServiceGrpcKt.ServerProactiveServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun morningBriefing(request: MorningBriefingRequest): MorningBriefingResponse {
        val briefing = proactiveScheduler.generateMorningBriefing(request.clientId)
        return MorningBriefingResponse.newBuilder()
            .setBriefing(briefing.take(200))
            .build()
    }

    override suspend fun overdueCheck(request: OverdueCheckRequest): OverdueCheckResponse {
        val count = proactiveScheduler.checkOverdueInvoices()
        return OverdueCheckResponse.newBuilder().setOverdueCount(count).build()
    }

    override suspend fun weeklySummary(request: WeeklySummaryRequest): WeeklySummaryResponse {
        val summary = proactiveScheduler.generateWeeklySummary(request.clientId)
        return WeeklySummaryResponse.newBuilder()
            .setSummary(summary.take(200))
            .build()
    }

    override suspend fun vipAlert(request: VipAlertRequest): VipAlertResponse {
        proactiveScheduler.sendVipEmailAlert(request.clientId, request.senderName, request.subject)
        return VipAlertResponse.newBuilder().setOk(true).build()
    }
}
