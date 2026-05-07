package com.trib3.server.swagger

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.trib3.config.ConfigLoader
import com.trib3.json.ObjectMapperProvider
import com.trib3.server.config.TribeApplicationConfig
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.integration.OpenApiContextLocator
import jakarta.ws.rs.core.Application
import org.testng.annotations.Test

@JvmInline
value class InlineId(
    val value: String,
)

data class ModelWithInlineClass(
    val id: InlineId,
    val name: String,
)

class SwaggerInitializerTest {
    @Test
    fun testInlineClassPropertyNames() {
        val initializer =
            SwaggerInitializer(
                "testInlineClassPropertyNames",
                TribeApplicationConfig(ConfigLoader()),
                ObjectMapperProvider().get(),
            )
        initializer.process(object : Application() {})
        val schemas = ModelConverters.getInstance().read(ModelWithInlineClass::class.java)
        val schema = schemas["ModelWithInlineClass"]
        assertThat(schema).isNotNull()
        assertThat(schema!!.properties.keys).containsExactlyInAnyOrder("id", "name")
    }

    @Test
    fun testServerUrlsForCustomAppContextPath() {
        val initializer =
            SwaggerInitializer(
                "serverUrlsForCustomAppContextPath",
                TribeApplicationConfig(ConfigLoader("appContextPathTestCase")),
                ObjectMapperProvider().get(),
            )
        initializer.process(object : Application() {})
        val context = OpenApiContextLocator.getInstance().getOpenApiContext("serverUrlsForCustomAppContextPath")
        assertThat(context.read().servers.map { it.url }).isEqualTo(
            listOf(
                "https://localhost/custom",
                "http://localhost/custom",
                "http://localhost:9080/custom",
            ),
        )
    }
}
