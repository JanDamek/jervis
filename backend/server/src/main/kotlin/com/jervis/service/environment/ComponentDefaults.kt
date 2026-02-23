package com.jervis.service.environment

import com.jervis.entity.ComponentType
import com.jervis.entity.PortMapping

/**
 * Multi-version component defaults with Docker images, ports, ENV vars and volume paths.
 */
data class ComponentVersion(val label: String, val image: String)

data class ComponentDefault(
    val versions: List<ComponentVersion>,
    val ports: List<PortMapping>,
    val defaultEnvVars: Map<String, String> = emptyMap(),
    val defaultVolumeMountPath: String? = null,
) {
    /** First version image (newest) — used as default when no explicit image is set. */
    val image: String get() = versions.first().image
}

val COMPONENT_DEFAULTS: Map<ComponentType, ComponentDefault> = mapOf(
    ComponentType.POSTGRESQL to ComponentDefault(
        versions = listOf(
            ComponentVersion("PostgreSQL 17 (Alpine)", "postgres:17-alpine"),
            ComponentVersion("PostgreSQL 16 (Alpine)", "postgres:16-alpine"),
            ComponentVersion("PostgreSQL 15 (Alpine)", "postgres:15-alpine"),
            ComponentVersion("PostgreSQL 14 (Alpine)", "postgres:14-alpine"),
        ),
        ports = listOf(PortMapping(5432, 5432, "postgres")),
        defaultEnvVars = mapOf("POSTGRES_PASSWORD" to "jervis"),
        defaultVolumeMountPath = "/var/lib/postgresql/data",
    ),
    ComponentType.MONGODB to ComponentDefault(
        versions = listOf(
            ComponentVersion("MongoDB 7", "mongo:7"),
            ComponentVersion("MongoDB 6", "mongo:6"),
            ComponentVersion("MongoDB 5", "mongo:5"),
        ),
        ports = listOf(PortMapping(27017, 27017, "mongo")),
        defaultVolumeMountPath = "/data/db",
    ),
    ComponentType.REDIS to ComponentDefault(
        versions = listOf(
            ComponentVersion("Redis 7 (Alpine)", "redis:7-alpine"),
            ComponentVersion("Redis 6 (Alpine)", "redis:6-alpine"),
        ),
        ports = listOf(PortMapping(6379, 6379, "redis")),
        defaultVolumeMountPath = "/data",
    ),
    ComponentType.RABBITMQ to ComponentDefault(
        versions = listOf(
            ComponentVersion("RabbitMQ 3 (Management)", "rabbitmq:3-management-alpine"),
        ),
        ports = listOf(
            PortMapping(5672, 5672, "amqp"),
            PortMapping(15672, 15672, "management"),
        ),
        defaultVolumeMountPath = "/var/lib/rabbitmq",
    ),
    ComponentType.KAFKA to ComponentDefault(
        versions = listOf(
            ComponentVersion("Confluent 7.6.0", "confluentinc/cp-kafka:7.6.0"),
            ComponentVersion("Confluent 7.5.0", "confluentinc/cp-kafka:7.5.0"),
        ),
        ports = listOf(PortMapping(9092, 9092, "kafka")),
        defaultVolumeMountPath = "/var/lib/kafka/data",
    ),
    ComponentType.ELASTICSEARCH to ComponentDefault(
        versions = listOf(
            ComponentVersion("Elasticsearch 8.12.0", "elasticsearch:8.12.0"),
            ComponentVersion("Elasticsearch 8.11.0", "elasticsearch:8.11.0"),
            ComponentVersion("Elasticsearch 7.17.0", "elasticsearch:7.17.0"),
        ),
        ports = listOf(PortMapping(9200, 9200, "http")),
        defaultEnvVars = mapOf("discovery.type" to "single-node", "xpack.security.enabled" to "false"),
        defaultVolumeMountPath = "/usr/share/elasticsearch/data",
    ),
    ComponentType.ORACLE to ComponentDefault(
        versions = listOf(
            ComponentVersion("Oracle Free 23 (Slim)", "gvenzl/oracle-free:23-slim"),
        ),
        ports = listOf(PortMapping(1521, 1521, "oracle")),
        defaultEnvVars = mapOf("ORACLE_PASSWORD" to "jervis"),
        defaultVolumeMountPath = "/opt/oracle/oradata",
    ),
    ComponentType.MYSQL to ComponentDefault(
        versions = listOf(
            ComponentVersion("MySQL 8.0", "mysql:8.0"),
            ComponentVersion("MySQL 5.7", "mysql:5.7"),
        ),
        ports = listOf(PortMapping(3306, 3306, "mysql")),
        defaultEnvVars = mapOf("MYSQL_ROOT_PASSWORD" to "jervis"),
        defaultVolumeMountPath = "/var/lib/mysql",
    ),
    ComponentType.MINIO to ComponentDefault(
        versions = listOf(
            ComponentVersion("MinIO (Latest)", "minio/minio:latest"),
        ),
        ports = listOf(
            PortMapping(9000, 9000, "api"),
            PortMapping(9001, 9001, "console"),
        ),
        defaultEnvVars = mapOf("MINIO_ROOT_USER" to "jervis", "MINIO_ROOT_PASSWORD" to "jervis123"),
        defaultVolumeMountPath = "/data",
    ),
)
