// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij

import java.nio.file.Files
import kotlin.test.Test

class VerifyPluginConfigurationIntegrationTest : IntelliJPlatformIntegrationTestBase(
    resourceName = "verify-plugin-configuration",
) {

    private val issuesFoundSentence = "[gradle-intellij-plugin :verifyPluginConfiguration] The following plugin configuration issues were found:"

    private val userHome = dir.resolve("home").toPath()
    private val defaultSystemProperties = mapOf(
        "user.home" to userHome,
    )
    private val defaultProjectProperties = mapOf(
        "intellijVersion" to "2022.2",
        "sinceBuild" to "222",
        "languageVersion" to "17",
        "downloadDir" to dir.resolve("home"),
    )

    @Test
    fun `should not report issues on valid configuration`() {
        build(
            "clean",
            "javaToolchains",
            "verifyPluginConfiguration",
            systemProperties = defaultSystemProperties,
            projectProperties = defaultProjectProperties,
        ).let {
            it.output notContainsText issuesFoundSentence
        }
    }

    @Test
    fun `should report incorrect source compatibility`() {
        build(
            "clean",
            "javaToolchains",
            "verifyPluginConfiguration",
            systemProperties = defaultSystemProperties,
            projectProperties = defaultProjectProperties + mapOf("languageVersion" to "11"),
        ).let {
            it.output containsText issuesFoundSentence
            it.output containsText "- The Java configuration specifies sourceCompatibility=11 but IntelliJ Platform 2022.2 requires sourceCompatibility=17."
        }
    }

    @Test
    fun `should report incorrect target compatibility`() {
        build(
            "clean",
            "javaToolchains",
            "verifyPluginConfiguration",
            systemProperties = defaultSystemProperties,
            projectProperties = defaultProjectProperties + mapOf("sinceBuild" to "203"),
        ).let {
            it.output containsText issuesFoundSentence
            it.output containsText "- The 'since-build' property is lower than the target IntelliJ Platform major version: 203 < 222."
            it.output containsText "- The Java configuration specifies targetCompatibility=17 but since-build='203' property requires targetCompatibility=11."
        }
    }

    @Test
    fun `should report existing Plugin Verifier download directory`() {
        val ides = userHome.resolve(".pluginVerifier/ides").also {
            Files.deleteIfExists(it.resolve("foo"))
        }.also {
            Files.createDirectories(it)
            Files.createFile(it.resolve("foo"))
        }
        val downloadDir = dir.resolve("home")

        build(
            "clean",
            "javaToolchains",
            "verifyPluginConfiguration",
            systemProperties = defaultSystemProperties,
            projectProperties = defaultProjectProperties + mapOf("downloadDir" to downloadDir),
            args = listOf("--info"),
        ).let {
            it.output containsText "The Plugin Verifier download directory is set to $downloadDir, but downloaded IDEs were also found in $ides, see: https://jb.gg/intellij-platform-plugin-verifier-old-download-dir"
        }
    }
}
