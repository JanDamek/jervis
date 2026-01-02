package com.jervis.coding.a2a

import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AgentCardController {
    @GetMapping("/.well-known/agent-card.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getAgentCard(): org.springframework.core.io.Resource {
        return ClassPathResource("static/.well-known/agent-card.json")
    }
}
