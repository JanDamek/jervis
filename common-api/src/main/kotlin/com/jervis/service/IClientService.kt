package com.jervis.service

import com.jervis.dto.ClientDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange

@HttpExchange("/api/clients")
interface IClientService {
    @PostExchange("/createByName")
    suspend fun createByName(
        @RequestParam clientName: String,
    ): ClientDto

    @PostExchange("/create")
    suspend fun create(
        @RequestBody client: ClientDto,
    ): ClientDto

    @PutExchange("/update")
    suspend fun update(
        @RequestBody client: ClientDto,
    ): ClientDto

    @DeleteExchange("/delete/{id}")
    suspend fun delete(
        @PathVariable id: String,
    )

    @GetExchange("/list")
    suspend fun list(): List<ClientDto>

    @GetExchange("/client/{id}")
    suspend fun getClientById(
        @PathVariable id: String,
    ): ClientDto?
}
