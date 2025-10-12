package com.jervis.configuration

import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.PropertySource
import org.springframework.core.io.support.EncodedResource
import org.springframework.core.io.support.PropertySourceFactory
import java.io.IOException

/**
 * Custom property source factory for loading YAML files.
 * This enables Spring Boot to load custom YAML files like prompts-tools.yaml and prompts-services.yaml.
 */
class YamlPropertySourceFactory : PropertySourceFactory {
    @Throws(IOException::class)
    override fun createPropertySource(
        name: String?,
        resource: EncodedResource,
    ): PropertySource<*> {
        val loader = YamlPropertySourceLoader()
        val propertyName = name ?: resource.resource.filename ?: "yaml-property-source"
        return loader.load(propertyName, resource.resource).first()
    }
}
