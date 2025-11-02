package com.jervis.common.client

import com.jervis.common.dto.JoernQueryDto
import com.jervis.common.dto.JoernResultDto
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/joern")
interface IJoernClient {
    @PostExchange("/api/joern/query")
    suspend fun run(request: JoernQueryDto): JoernResultDto
}
