package com.jervis.entity.mongo

import com.jervis.domain.background.CoverageSnapshot
import com.jervis.domain.background.CoverageWeights
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "coverage_snapshots")
@CompoundIndexes(
    CompoundIndex(name = "project_created", def = "{'projectKey': 1, 'createdAt': -1}"),
)
data class CoverageSnapshotDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val projectKey: String,
    val docs: Double,
    val tasks: Double,
    val code: Double,
    val meetings: Double,
    val overall: Double,
    val weightsData: Map<String, Double>,
    @Indexed
    val createdAt: Instant = Instant.now(),
) {
    fun toDomain(): CoverageSnapshot =
        CoverageSnapshot(
            id = id,
            projectKey = projectKey,
            docs = docs,
            tasks = tasks,
            code = code,
            meetings = meetings,
            overall = overall,
            weights =
                CoverageWeights(
                    docs = weightsData["docs"] ?: 0.3,
                    tasks = weightsData["tasks"] ?: 0.2,
                    code = weightsData["code"] ?: 0.4,
                    meetings = weightsData["meetings"] ?: 0.1,
                ),
            createdAt = createdAt,
        )

    companion object {
        fun fromDomain(snapshot: CoverageSnapshot): CoverageSnapshotDocument =
            CoverageSnapshotDocument(
                id = snapshot.id,
                projectKey = snapshot.projectKey,
                docs = snapshot.docs,
                tasks = snapshot.tasks,
                code = snapshot.code,
                meetings = snapshot.meetings,
                overall = snapshot.overall,
                weightsData =
                    mapOf(
                        "docs" to snapshot.weights.docs,
                        "tasks" to snapshot.weights.tasks,
                        "code" to snapshot.weights.code,
                        "meetings" to snapshot.weights.meetings,
                    ),
                createdAt = snapshot.createdAt,
            )
    }
}
