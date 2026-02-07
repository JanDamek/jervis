package com.jervis.service.environment

import com.jervis.entity.ComponentType
import com.jervis.entity.PortMapping

/**
 * Default Docker images and ports for each infrastructure component type.
 */
data class ComponentDefault(
    val image: String,
    val ports: List<PortMapping>,
    val defaultEnvVars: Map<String, String> = emptyMap(),
)

val COMPONENT_DEFAULTS: Map<ComponentType, ComponentDefault> = mapOf(
    ComponentType.POSTGRESQL to ComponentDefault(
        image = "postgres:16-alpine",
        ports = listOf(PortMapping(5432, 5432, "postgres")),
        defaultEnvVars = mapOf("POSTGRES_PASSWORD" to "jervis"),
    ),
    ComponentType.MONGODB to ComponentDefault(
        image = "mongo:7",
        ports = listOf(PortMapping(27017, 27017, "mongo")),
    ),
    ComponentType.REDIS to ComponentDefault(
        image = "redis:7-alpine",
        ports = listOf(PortMapping(6379, 6379, "redis")),
    ),
    ComponentType.RABBITMQ to ComponentDefault(
        image = "rabbitmq:3-management-alpine",
        ports = listOf(
            PortMapping(5672, 5672, "amqp"),
            PortMapping(15672, 15672, "management"),
        ),
    ),
    ComponentType.KAFKA to ComponentDefault(
        image = "confluentinc/cp-kafka:7.6.0",
        ports = listOf(PortMapping(9092, 9092, "kafka")),
    ),
    ComponentType.ELASTICSEARCH to ComponentDefault(
        image = "elasticsearch:8.12.0",
        ports = listOf(PortMapping(9200, 9200, "http")),
        defaultEnvVars = mapOf("discovery.type" to "single-node", "xpack.security.enabled" to "false"),
    ),
    ComponentType.ORACLE to ComponentDefault(
        image = "gvenzl/oracle-free:23-slim",
        ports = listOf(PortMapping(1521, 1521, "oracle")),
        defaultEnvVars = mapOf("ORACLE_PASSWORD" to "jervis"),
    ),
    ComponentType.MYSQL to ComponentDefault(
        image = "mysql:8.0",
        ports = listOf(PortMapping(3306, 3306, "mysql")),
        defaultEnvVars = mapOf("MYSQL_ROOT_PASSWORD" to "jervis"),
    ),
    ComponentType.MINIO to ComponentDefault(
        image = "minio/minio:latest",
        ports = listOf(
            PortMapping(9000, 9000, "api"),
            PortMapping(9001, 9001, "console"),
        ),
        defaultEnvVars = mapOf("MINIO_ROOT_USER" to "jervis", "MINIO_ROOT_PASSWORD" to "jervis123"),
    ),
)
