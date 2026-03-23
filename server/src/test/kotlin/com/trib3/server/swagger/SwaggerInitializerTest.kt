package com.trib3.server.swagger

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isNotNull
import com.trib3.config.ConfigLoader
import com.trib3.json.ObjectMapperProvider
import com.trib3.server.config.TribeApplicationConfig
import io.swagger.v3.core.converter.ModelConverters
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
                TribeApplicationConfig(ConfigLoader()),
                ObjectMapperProvider().get(),
            )
        initializer.process(object : Application() {})
        val schemas = ModelConverters.getInstance().read(ModelWithInlineClass::class.java)
        val schema = schemas["ModelWithInlineClass"]
        assertThat(schema).isNotNull()
        assertThat(schema!!.properties.keys).containsExactlyInAnyOrder("id", "name")
    }
}
