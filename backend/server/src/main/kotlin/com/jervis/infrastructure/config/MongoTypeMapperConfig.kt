package com.jervis.infrastructure.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper
import org.springframework.data.mongodb.core.convert.MappingMongoConverter

/**
 * Disables MongoDB _class field in stored documents.
 *
 * After the package reorganization (2026-03), class FQNs changed.
 * Since we don't use polymorphic MongoDB collections, disabling _class
 * is the cleanest solution — no migration needed, no stale references.
 */
@Configuration
class MongoTypeMapperConfig {

    // Type mapper customization is handled via the postProcessor below

    @Bean
    fun mongoConverterPostProcessor() = object : org.springframework.beans.factory.config.BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is MappingMongoConverter) {
                bean.setTypeMapper(DefaultMongoTypeMapper(null))
            }
            return bean
        }
    }
}
