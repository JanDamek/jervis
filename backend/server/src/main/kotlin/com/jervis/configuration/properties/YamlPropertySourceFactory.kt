package com.jervis.configuration.properties

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.support.EncodedResource
import org.springframework.core.io.support.PropertySourceFactory
import java.util.Properties

/**
 * Custom PropertySourceFactory for loading YAML files.
 * Enables @PropertySource to work with YAML files.
 */
class YamlPropertySourceFactory : PropertySourceFactory {
    override fun createPropertySource(name: String?, resource: EncodedResource): PropertySource<*> {
        val factory = YamlPropertiesFactoryBean()
        factory.setResources(resource.resource)

        val properties: Properties = factory.getObject() ?: Properties()

        val sourceName = name ?: resource.resource.filename ?: "yaml-property-source"

        return PropertiesPropertySource(sourceName, properties)
    }
}
