// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.tasks

import org.gradle.internal.impldep.org.testng.annotations.BeforeTest
import org.jetbrains.intellij.IntelliJPluginConstants.VERIFY_PLUGIN_CONFIGURATION_TASK_NAME
import org.jetbrains.intellij.IntelliJPluginSpecBase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

@Suppress("PluginXmlCapitalization", "PluginXmlValidity", "ComplexRedundantLet")
class VerifyPluginConfigurationTaskSpec : IntelliJPluginSpecBase() {

    companion object {
        const val HEADER = "The following plugin configuration issues were found"
    }

    @BeforeTest
    override fun setUp() {
        super.setUp()

        gradleArguments.add("-Duser.home=$gradleHome")
        File(gradleHome).run {
            deleteRecursively()
            mkdirs()
        }
    }

    @Test
    fun `do not show errors when configuration is valid`() {
        pluginXml.xml(
            """
            <idea-plugin>
                <name>PluginName</name>
                <description>Lorem ipsum.</description>
                <vendor>JetBrains</vendor>
                <idea-version since-build="212" until-build='212.*' />
            </idea-plugin>
            """.trimIndent()
        )

        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertNotContains(HEADER, it.output)
        }
    }

    @Test
    fun `report too low since-build`() {
        buildFile.groovy(
            """
            patchPluginXml {
                sinceBuild = "211"
            }
            """.trimIndent()
        )

        pluginXml.xml(
            """
            <idea-plugin>
                <name>PluginName</name>
                <description>Lorem ipsum.</description>
                <vendor>JetBrains</vendor>
                <idea-version since-build="211" until-build='212.*' />
            </idea-plugin>
            """.trimIndent()
        )

        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertContains(HEADER, it.output)
            assertContains("- The 'since-build' property is lower than the target IntelliJ Platform major version: 211 < 212.", it.output)
        }
    }

    @Test
    fun `report too low Java sourceCompatibility`() {
        buildFile.groovy(
            """
            sourceCompatibility = 1.8
            """.trimIndent()
        )

        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertContains(HEADER, it.output)
            assertContains(
                "- The Java configuration specifies sourceCompatibility=1.8 but IntelliJ Platform 2021.2.4 requires sourceCompatibility=11.",
                it.output
            )
        }
    }

    @Test
    fun `report too high Java targetCompatibility`() {
        buildFile.groovy(
            """
            targetCompatibility = 17
            """.trimIndent()
        )

        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertContains(HEADER, it.output)
            assertContains(
                "- The Java configuration specifies targetCompatibility=17 but IntelliJ Platform 2021.2.4 requires targetCompatibility=11.",
                it.output
            )
        }
    }

    @Test
    fun `report too high Kotlin jvmTarget`() {
        buildFile.groovy(
            """
            compileKotlin {
                kotlinOptions {
                    jvmTarget = "17"
                }
            }
            """.trimIndent()
        )

        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertContains(HEADER, it.output)
            assertContains("- The Kotlin configuration specifies jvmTarget=17 but IntelliJ Platform 2021.2.4 requires jvmTarget=11.", it.output)
        }
    }

    @Test
    fun `report too high Kotlin apiVersion`() {
        pluginXml.xml(
            """
            <idea-plugin>
                <name>PluginName</name>
                <description>Lorem ipsum.</description>
                <vendor>JetBrains</vendor>
                <idea-version since-build="211" until-build='212.*' />
            </idea-plugin>
            """.trimIndent()
        )

        buildFile.groovy(
            """
            compileKotlin {
                kotlinOptions {
                    apiVersion = "1.9"
                }
            }
            """.trimIndent()
        )

        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertContains(HEADER, it.output)
            assertContains("- The Kotlin configuration specifies apiVersion=1.9 but since-build='212.5712' property requires apiVersion=1.5.10.", it.output)
        }
    }

    @Test
    fun `report too low Kotlin languageVersion`() {
        buildFile.groovy(
            """
            compileKotlin {
                kotlinOptions {
                    languageVersion = "1.3"
                }
            }
            """.trimIndent()
        )

        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertContains(HEADER, it.output)
            assertContains(
                "- The Kotlin configuration specifies languageVersion=1.3 but IntelliJ Platform 2021.2.4 requires languageVersion=1.5.10.",
                it.output
            )
        }
    }

    @Test
    fun `report Kotlin stdlib bundling`() {
        // kotlin.stdlib.default.dependency gets unset
        gradleProperties.writeText("")

        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertContains(HEADER, it.output)
            assertContains(
                "- The dependency on the Kotlin Standard Library (stdlib) is automatically added when using the Gradle Kotlin plugin and may conflict with the version provided with the IntelliJ Platform, see: https://jb.gg/intellij-platform-kotlin-stdlib",
                it.output
            )
        }

        gradleProperties.properties(
            """
            kotlin.stdlib.default.dependency = true
            """.trimIndent()
        )

        build("clean", VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertNotContains(HEADER, it.output)
        }

        gradleProperties.properties(
            """
            kotlin.stdlib.default.dependency = false
            """.trimIndent()
        )

        build("clean", VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            assertNotContains(HEADER, it.output)
        }
    }

    @Test
    fun `report not-empty old Plugin Verifier download directory`() {
        Path.of(gradleHome).resolve(".pluginVerifier/ides").let {
            Files.createDirectories(it)
            Files.createTempFile(it, null, null)
        }

        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME).let {
            val pluginVerifierDownloadDir = "$gradleHome/.cache/pluginVerifier/ides"
            val oldPluginVerifierDownloadDir = "$gradleHome/.pluginVerifier/ides"

            assertContains(HEADER, it.output)
            assertContains(
                "The Plugin Verifier download directory is set to $pluginVerifierDownloadDir, but downloaded IDEs were also found in $oldPluginVerifierDownloadDir, see: https://jb.gg/intellij-platform-plugin-verifier-old-download-dir",
                it.output
            )
        }
    }

    @Test
    fun `reuse configuration cache`() {
        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME, "--configuration-cache")
        build(VERIFY_PLUGIN_CONFIGURATION_TASK_NAME, "--configuration-cache").let {
            assertContains("Reusing configuration cache.", it.output)
        }
    }
}
