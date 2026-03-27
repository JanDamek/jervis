package com.jervis.environment

import com.jervis.environment.ComponentType
import com.jervis.environment.PortMapping

/**
 * Multi-version component defaults with Docker images, ports, ENV vars, volume paths and health probes.
 */
data class ComponentVersion(val label: String, val image: String)

enum class ProbeType { TCP, HTTP }

data class HealthProbeConfig(
    val type: ProbeType,
    val port: Int,
    val path: String? = null,
    val initialDelaySeconds: Int = 30,
    val periodSeconds: Int = 10,
)

data class ComponentDefault(
    val versions: List<ComponentVersion>,
    val ports: List<PortMapping>,
    val defaultEnvVars: Map<String, String> = emptyMap(),
    val defaultVolumeMountPath: String? = null,
    val healthProbe: HealthProbeConfig? = null,
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
        healthProbe = HealthProbeConfig(ProbeType.TCP, port = 5432, initialDelaySeconds = 20),
    ),
    ComponentType.MONGODB to ComponentDefault(
        versions = listOf(
            ComponentVersion("MongoDB 7", "mongo:7"),
            ComponentVersion("MongoDB 6", "mongo:6"),
            ComponentVersion("MongoDB 5", "mongo:5"),
        ),
        ports = listOf(PortMapping(27017, 27017, "mongo")),
        defaultVolumeMountPath = "/data/db",
        healthProbe = HealthProbeConfig(ProbeType.TCP, port = 27017, initialDelaySeconds = 15),
    ),
    ComponentType.REDIS to ComponentDefault(
        versions = listOf(
            ComponentVersion("Redis 7 (Alpine)", "redis:7-alpine"),
            ComponentVersion("Redis 6 (Alpine)", "redis:6-alpine"),
        ),
        ports = listOf(PortMapping(6379, 6379, "redis")),
        defaultVolumeMountPath = "/data",
        healthProbe = HealthProbeConfig(ProbeType.TCP, port = 6379, initialDelaySeconds = 5),
    ),
    ComponentType.RABBITMQ to ComponentDefault(
        versions = listOf(
            ComponentVersion("RabbitMQ 4.0 (Management)", "rabbitmq:4.0-management-alpine"),
            ComponentVersion("RabbitMQ 3.13 (Management)", "rabbitmq:3.13-management-alpine"),
            ComponentVersion("RabbitMQ 3.12 (Management)", "rabbitmq:3.12-management-alpine"),
        ),
        ports = listOf(
            PortMapping(5672, 5672, "amqp"),
            PortMapping(15672, 15672, "management"),
        ),
        defaultVolumeMountPath = "/var/lib/rabbitmq",
        healthProbe = HealthProbeConfig(ProbeType.HTTP, port = 15672, path = "/api/healthchecks/node", initialDelaySeconds = 30),
    ),
    ComponentType.KAFKA to ComponentDefault(
        versions = listOf(
            ComponentVersion("Confluent 7.6.0", "confluentinc/cp-kafka:7.6.0"),
            ComponentVersion("Confluent 7.5.0", "confluentinc/cp-kafka:7.5.0"),
        ),
        ports = listOf(PortMapping(9092, 9092, "kafka")),
        defaultVolumeMountPath = "/var/lib/kafka/data",
        healthProbe = HealthProbeConfig(ProbeType.TCP, port = 9092, initialDelaySeconds = 30),
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
        healthProbe = HealthProbeConfig(ProbeType.HTTP, port = 9200, path = "/_cluster/health", initialDelaySeconds = 30),
    ),
    ComponentType.ORACLE to ComponentDefault(
        versions = listOf(
            ComponentVersion("Oracle Free 23 (Slim)", "gvenzl/oracle-free:23-slim"),
            ComponentVersion("Oracle XE 21 (Slim)", "gvenzl/oracle-xe:21-slim"),
            ComponentVersion("Oracle XE 18 (Slim)", "gvenzl/oracle-xe:18-slim"),
        ),
        ports = listOf(PortMapping(1521, 1521, "oracle")),
        defaultEnvVars = mapOf("ORACLE_PASSWORD" to "jervis"),
        defaultVolumeMountPath = "/opt/oracle/oradata",
        healthProbe = HealthProbeConfig(ProbeType.TCP, port = 1521, initialDelaySeconds = 60),
    ),
    ComponentType.MYSQL to ComponentDefault(
        versions = listOf(
            ComponentVersion("MySQL 8.0", "mysql:8.0"),
            ComponentVersion("MySQL 5.7", "mysql:5.7"),
        ),
        ports = listOf(PortMapping(3306, 3306, "mysql")),
        defaultEnvVars = mapOf("MYSQL_ROOT_PASSWORD" to "jervis"),
        defaultVolumeMountPath = "/var/lib/mysql",
        healthProbe = HealthProbeConfig(ProbeType.TCP, port = 3306, initialDelaySeconds = 20),
    ),
    ComponentType.MINIO to ComponentDefault(
        versions = listOf(
            ComponentVersion("MinIO RELEASE.2024-12-18", "minio/minio:RELEASE.2024-12-18T13-15-44Z"),
            ComponentVersion("MinIO RELEASE.2024-06-29", "minio/minio:RELEASE.2024-06-29T01-20-47Z"),
            ComponentVersion("MinIO (Latest)", "minio/minio:latest"),
        ),
        ports = listOf(
            PortMapping(9000, 9000, "api"),
            PortMapping(9001, 9001, "console"),
        ),
        defaultEnvVars = mapOf("MINIO_ROOT_USER" to "jervis", "MINIO_ROOT_PASSWORD" to "jervis123"),
        defaultVolumeMountPath = "/data",
        healthProbe = HealthProbeConfig(ProbeType.HTTP, port = 9000, path = "/minio/health/ready", initialDelaySeconds = 15),
    ),
)

// ============================================================
// Property Mapping Templates
// ============================================================
// Predefined templates for automatically passing infrastructure
// connection details (URL, username, password) to project components.
// Template syntax: {host}, {port}, {name}, {env:VAR_NAME}

/**
 * Represents a predefined property mapping for an infrastructure component type.
 * Used to auto-generate PropertyMapping entries when linking a project to infrastructure.
 *
 * @param envVarName the ENV var name set in the PROJECT component (e.g., SPRING_DATASOURCE_URL)
 * @param valueTemplate template string with placeholders for resolution
 * @param description human-readable description (Czech) for the UI
 */
data class PropertyMappingTemplate(
    val envVarName: String,
    val valueTemplate: String,
    val description: String,
)

/**
 * Predefined property mapping templates per infrastructure component type.
 * When a project component is linked to an infrastructure component,
 * these templates are auto-suggested in the UI and applied during provisioning.
 *
 * Placeholders:
 * - {host} → resolved to <component-name>.<namespace>.svc.cluster.local
 * - {port} → resolved to the first port of the target component
 * - {name} → resolved to the target component name
 * - {env:VAR_NAME} → resolved from the target component's envVars
 */
val PROPERTY_MAPPING_TEMPLATES: Map<ComponentType, List<PropertyMappingTemplate>> = mapOf(
    ComponentType.POSTGRESQL to listOf(
        PropertyMappingTemplate(
            "SPRING_DATASOURCE_URL",
            "jdbc:postgresql://{host}:{port}/jervis",
            "JDBC URL pro Spring Boot (PostgreSQL)",
        ),
        PropertyMappingTemplate(
            "SPRING_DATASOURCE_USERNAME",
            "postgres",
            "Uživatel databáze (PostgreSQL)",
        ),
        PropertyMappingTemplate(
            "SPRING_DATASOURCE_PASSWORD",
            "{env:POSTGRES_PASSWORD}",
            "Heslo databáze (z POSTGRES_PASSWORD)",
        ),
        PropertyMappingTemplate(
            "DATABASE_URL",
            "postgresql://postgres:{env:POSTGRES_PASSWORD}@{host}:{port}/jervis",
            "Connection string (Node.js, Django, Rails)",
        ),
    ),
    ComponentType.MONGODB to listOf(
        PropertyMappingTemplate(
            "SPRING_DATA_MONGODB_URI",
            "mongodb://{host}:{port}/jervis",
            "MongoDB URI pro Spring Boot",
        ),
        PropertyMappingTemplate(
            "MONGODB_URI",
            "mongodb://{host}:{port}/jervis",
            "MongoDB connection string",
        ),
    ),
    ComponentType.REDIS to listOf(
        PropertyMappingTemplate(
            "SPRING_DATA_REDIS_HOST",
            "{host}",
            "Redis host pro Spring Boot",
        ),
        PropertyMappingTemplate(
            "SPRING_DATA_REDIS_PORT",
            "{port}",
            "Redis port pro Spring Boot",
        ),
        PropertyMappingTemplate(
            "REDIS_URL",
            "redis://{host}:{port}/0",
            "Redis URL (Node.js, Python)",
        ),
    ),
    ComponentType.RABBITMQ to listOf(
        PropertyMappingTemplate(
            "SPRING_RABBITMQ_HOST",
            "{host}",
            "RabbitMQ host pro Spring Boot",
        ),
        PropertyMappingTemplate(
            "SPRING_RABBITMQ_PORT",
            "{port}",
            "RabbitMQ port pro Spring Boot",
        ),
        PropertyMappingTemplate(
            "AMQP_URL",
            "amqp://guest:guest@{host}:{port}/",
            "AMQP connection string",
        ),
    ),
    ComponentType.KAFKA to listOf(
        PropertyMappingTemplate(
            "SPRING_KAFKA_BOOTSTRAP_SERVERS",
            "{host}:{port}",
            "Kafka bootstrap servers pro Spring Boot",
        ),
        PropertyMappingTemplate(
            "KAFKA_BOOTSTRAP_SERVERS",
            "{host}:{port}",
            "Kafka bootstrap servers",
        ),
    ),
    ComponentType.ELASTICSEARCH to listOf(
        PropertyMappingTemplate(
            "SPRING_ELASTICSEARCH_URIS",
            "http://{host}:{port}",
            "Elasticsearch URI pro Spring Boot",
        ),
        PropertyMappingTemplate(
            "ELASTICSEARCH_URL",
            "http://{host}:{port}",
            "Elasticsearch URL",
        ),
    ),
    ComponentType.ORACLE to listOf(
        PropertyMappingTemplate(
            "SPRING_DATASOURCE_URL",
            "jdbc:oracle:thin:@{host}:{port}/FREEPDB1",
            "JDBC URL pro Spring Boot (Oracle)",
        ),
        PropertyMappingTemplate(
            "SPRING_DATASOURCE_USERNAME",
            "system",
            "Uživatel databáze (Oracle)",
        ),
        PropertyMappingTemplate(
            "SPRING_DATASOURCE_PASSWORD",
            "{env:ORACLE_PASSWORD}",
            "Heslo databáze (z ORACLE_PASSWORD)",
        ),
    ),
    ComponentType.MYSQL to listOf(
        PropertyMappingTemplate(
            "SPRING_DATASOURCE_URL",
            "jdbc:mysql://{host}:{port}/jervis",
            "JDBC URL pro Spring Boot (MySQL)",
        ),
        PropertyMappingTemplate(
            "SPRING_DATASOURCE_USERNAME",
            "root",
            "Uživatel databáze (MySQL root)",
        ),
        PropertyMappingTemplate(
            "SPRING_DATASOURCE_PASSWORD",
            "{env:MYSQL_ROOT_PASSWORD}",
            "Heslo databáze (z MYSQL_ROOT_PASSWORD)",
        ),
        PropertyMappingTemplate(
            "DATABASE_URL",
            "mysql://root:{env:MYSQL_ROOT_PASSWORD}@{host}:{port}/jervis",
            "Connection string (Node.js, Django, Rails)",
        ),
    ),
    ComponentType.MINIO to listOf(
        PropertyMappingTemplate(
            "MINIO_ENDPOINT",
            "http://{host}:{port}",
            "MinIO S3-compatible endpoint",
        ),
        PropertyMappingTemplate(
            "AWS_S3_ENDPOINT",
            "http://{host}:{port}",
            "S3 endpoint (AWS SDK compatible)",
        ),
        PropertyMappingTemplate(
            "AWS_ACCESS_KEY_ID",
            "{env:MINIO_ROOT_USER}",
            "MinIO access key (z MINIO_ROOT_USER)",
        ),
        PropertyMappingTemplate(
            "AWS_SECRET_ACCESS_KEY",
            "{env:MINIO_ROOT_PASSWORD}",
            "MinIO secret key (z MINIO_ROOT_PASSWORD)",
        ),
    ),
)
