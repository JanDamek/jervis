package com.jervis.mapper

import com.jervis.domain.context.TaskContext
import com.jervis.dto.TaskContextDto
import org.bson.types.ObjectId

/**
 * TaskContextMapper class.
 * <p>
 * This class is a part of the application's core functionality.
 * It was created to provide features such as...
 * </p>
 *
 * @author damekjan
 * @version 1.0
 * @since 17.10.2025
 */
fun TaskContextDto.toDomain() =
    TaskContext(
        id = ObjectId(id),
        clientDocument = client.toDocument(),
        projectDocument = project.toDocument(),
        name = name,
        plans = plans.map { it.toDomain() },
        quick = quick,
        projectContextInfo = projectContextInfo?.toDomain(),
        contextSummary = contextSummary,
    )

fun TaskContext.toDto() =
    TaskContextDto(
        id = id.toString(),
        client = clientDocument.toDto(),
        project = projectDocument.toDto(),
        name = name,
        plans = plans.map { it.toDto() },
        quick = quick,
        projectContextInfo = projectContextInfo?.toDto(),
        contextSummary = contextSummary,
    )
