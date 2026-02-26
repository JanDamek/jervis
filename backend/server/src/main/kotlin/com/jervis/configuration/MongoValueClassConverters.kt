package com.jervis.configuration

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.PollingStateId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import org.bson.types.ObjectId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions

/**
 * Registers custom MongoDB converters for Kotlin inline value classes.
 *
 * Problem: Spring Data MongoDB cannot introspect Kotlin inline value class
 * constructor parameters because their names are mangled in bytecode (e.g.
 * `clientId-<hash>`). This causes "Parameter does not have a name" MappingException
 * when reading documents from MongoDB.
 *
 * Solution: Explicit converters that tell Spring Data how to convert between
 * MongoDB native types (ObjectId, String) and our value classes. This bypasses
 * the reflection-based parameter resolution entirely.
 */
@Configuration
class MongoValueClassConverters {

    @Bean
    fun customConversions(): MongoCustomConversions = MongoCustomConversions(
        listOf(
            // TaskId (ObjectId)
            ObjectIdToTaskIdConverter(),
            TaskIdToObjectIdConverter(),
            // ClientId (ObjectId)
            ObjectIdToClientIdConverter(),
            ClientIdToObjectIdConverter(),
            // ProjectId (ObjectId)
            ObjectIdToProjectIdConverter(),
            ProjectIdToObjectIdConverter(),
            // ConnectionId (ObjectId)
            ObjectIdToConnectionIdConverter(),
            ConnectionIdToObjectIdConverter(),
            // EnvironmentId (ObjectId)
            ObjectIdToEnvironmentIdConverter(),
            EnvironmentIdToObjectIdConverter(),
            // PollingStateId (ObjectId)
            ObjectIdToPollingStateIdConverter(),
            PollingStateIdToObjectIdConverter(),
            // ProjectGroupId (ObjectId)
            ObjectIdToProjectGroupIdConverter(),
            ProjectGroupIdToObjectIdConverter(),
            // SourceUrn (String)
            StringToSourceUrnConverter(),
            SourceUrnToStringConverter(),
        ),
    )

    // ── TaskId ──────────────────────────────────────────────────────────

    @ReadingConverter
    class ObjectIdToTaskIdConverter : Converter<ObjectId, TaskId> {
        override fun convert(source: ObjectId): TaskId = TaskId(source)
    }

    @WritingConverter
    class TaskIdToObjectIdConverter : Converter<TaskId, ObjectId> {
        override fun convert(source: TaskId): ObjectId = source.value
    }

    // ── ClientId ────────────────────────────────────────────────────────

    @ReadingConverter
    class ObjectIdToClientIdConverter : Converter<ObjectId, ClientId> {
        override fun convert(source: ObjectId): ClientId = ClientId(source)
    }

    @WritingConverter
    class ClientIdToObjectIdConverter : Converter<ClientId, ObjectId> {
        override fun convert(source: ClientId): ObjectId = source.value
    }

    // ── ProjectId ───────────────────────────────────────────────────────

    @ReadingConverter
    class ObjectIdToProjectIdConverter : Converter<ObjectId, ProjectId> {
        override fun convert(source: ObjectId): ProjectId = ProjectId(source)
    }

    @WritingConverter
    class ProjectIdToObjectIdConverter : Converter<ProjectId, ObjectId> {
        override fun convert(source: ProjectId): ObjectId = source.value
    }

    // ── ConnectionId ────────────────────────────────────────────────────

    @ReadingConverter
    class ObjectIdToConnectionIdConverter : Converter<ObjectId, ConnectionId> {
        override fun convert(source: ObjectId): ConnectionId = ConnectionId(source)
    }

    @WritingConverter
    class ConnectionIdToObjectIdConverter : Converter<ConnectionId, ObjectId> {
        override fun convert(source: ConnectionId): ObjectId = source.value
    }

    // ── EnvironmentId ───────────────────────────────────────────────────

    @ReadingConverter
    class ObjectIdToEnvironmentIdConverter : Converter<ObjectId, EnvironmentId> {
        override fun convert(source: ObjectId): EnvironmentId = EnvironmentId(source)
    }

    @WritingConverter
    class EnvironmentIdToObjectIdConverter : Converter<EnvironmentId, ObjectId> {
        override fun convert(source: EnvironmentId): ObjectId = source.value
    }

    // ── PollingStateId ──────────────────────────────────────────────────

    @ReadingConverter
    class ObjectIdToPollingStateIdConverter : Converter<ObjectId, PollingStateId> {
        override fun convert(source: ObjectId): PollingStateId = PollingStateId(source)
    }

    @WritingConverter
    class PollingStateIdToObjectIdConverter : Converter<PollingStateId, ObjectId> {
        override fun convert(source: PollingStateId): ObjectId = source.value
    }

    // ── ProjectGroupId ──────────────────────────────────────────────────

    @ReadingConverter
    class ObjectIdToProjectGroupIdConverter : Converter<ObjectId, ProjectGroupId> {
        override fun convert(source: ObjectId): ProjectGroupId = ProjectGroupId(source)
    }

    @WritingConverter
    class ProjectGroupIdToObjectIdConverter : Converter<ProjectGroupId, ObjectId> {
        override fun convert(source: ProjectGroupId): ObjectId = source.value
    }

    // ── SourceUrn (String-based) ────────────────────────────────────────

    @ReadingConverter
    class StringToSourceUrnConverter : Converter<String, SourceUrn> {
        override fun convert(source: String): SourceUrn = SourceUrn(source)
    }

    @WritingConverter
    class SourceUrnToStringConverter : Converter<SourceUrn, String> {
        override fun convert(source: SourceUrn): String = source.value
    }
}
