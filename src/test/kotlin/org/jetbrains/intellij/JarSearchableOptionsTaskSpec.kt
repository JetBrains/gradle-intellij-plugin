package org.jetbrains.intellij

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JarSearchableOptionsTaskSpec : SearchableOptionsSpecBase() {

    @Test
    fun `skip jarring searchable options using IDEA prior 2019_1`() {
        buildFile.groovy("""
            intellij {
                version = '14.1.4'
            }
        """)

        val result = build(IntelliJPlugin.JAR_SEARCHABLE_OPTIONS_TASK_NAME)
        assertTrue(result.output.contains("${IntelliJPlugin.JAR_SEARCHABLE_OPTIONS_TASK_NAME} SKIPPED"))
    }

    @Test
    fun `jar searchable options produces archive`() {
        pluginXml.xml(getPluginXmlWithSearchableConfigurable())
        buildFile.groovy("""
            intellij {
                version = '$intellijVersion'
            }
        """)
        getTestSearchableConfigurableJava().java(getSearchableConfigurableCode())

        build(IntelliJPlugin.JAR_SEARCHABLE_OPTIONS_TASK_NAME)

        val libsSearchableOptions = File(buildDirectory, "libsSearchableOptions")
        assertTrue(libsSearchableOptions.exists())
        assertEquals(setOf("/lib/searchableOptions.jar"), collectPaths(libsSearchableOptions))
    }
}
