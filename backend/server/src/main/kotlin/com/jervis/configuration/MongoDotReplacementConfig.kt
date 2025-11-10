package com.jervis.configuration

import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.convert.MappingMongoConverter

/**
 * Configures MongoDB mapping to safely persist Map keys that may contain dots ('.').
 *
 * Spring Data MongoDB cannot store raw map keys with dots as they are not valid
 * in BSON field names. We set a global replacement to avoid MappingException
 * when such keys appear (e.g., dynamically generated map keys).
 */
@Configuration
class MongoDotReplacementConfig {

    @Bean
    fun mappingMongoConverterCustomizer(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is MappingMongoConverter) {
                // Replace dots in map keys with a unicode middle dot to keep readability
                // and minimize collision with common characters. Using "·" is safe for
                // Mongo field names and reversible if needed in application logic.
                bean.setMapKeyDotReplacement("·")
            }
            return bean
        }

        override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any = bean
    }
}
