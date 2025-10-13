package com.jervis.controller

import com.jervis.dto.ClientDto
import com.jervis.service.IClientService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/clients")
class ClientRestController(
    private val clientService: IClientService,
) {
    @PostMapping
    suspend fun create(
        @RequestBody client: ClientDto,
    ): ClientDto = clientService.create(client)

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable id: String,
        @RequestBody client: ClientDto,
    ): ClientDto = clientService.update(ObjectId(id), client)

    @DeleteMapping("/{id}")
    suspend fun delete(
        @PathVariable id: String,
    ) {
        clientService.delete(ObjectId(id))
    }

    @GetMapping
    suspend fun list(): List<ClientDto> = clientService.list()

    @GetMapping("/{id}")
    suspend fun getClientById(
        @PathVariable id: String,
    ): ClientDto? = clientService.getClientById(ObjectId(id))
}
