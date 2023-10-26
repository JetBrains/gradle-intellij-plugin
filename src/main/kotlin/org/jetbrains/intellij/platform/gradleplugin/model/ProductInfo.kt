// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.platform.gradleplugin.model

import com.jetbrains.plugin.structure.base.utils.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.internal.os.OperatingSystem
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

@Serializable
data class ProductInfo(
    val name: String? = null,
    val version: String = "",
    val versionSuffix: String? = null,
    val buildNumber: String = "",
    val productCode: String = "",
    val dataDirectoryName: String? = null,
    val svgIconPath: String? = null,
    val productVendor: String? = null,
    val launch: List<Launch> = mutableListOf(),
    val customProperties: List<CustomProperty> = mutableListOf(),
    val bundledPlugins: List<String> = mutableListOf(),
    val fileExtensions: List<String> = mutableListOf(),
    val modules: List<String> = mutableListOf(),
) {
    val currentLaunch: Launch
        get() = with(OperatingSystem.current()) {
            val currentArchitecture = System.getProperty("os.arch")
            val availableArchitectures = launch.mapNotNull { it.arch }.toSet()

            val arch = with(availableArchitectures) {
                when {
                    isEmpty() -> null// older SDKs or Maven releases don't provide architecture information, null is used in such a case
                    contains(currentArchitecture) -> currentArchitecture
                    contains("amd64") && currentArchitecture == "x86_64" -> "amd64"
                    else -> throw GradleException("Unsupported JVM architecture was selected for running Gradle tasks: $currentArchitecture. Supported architectures: ${joinToString()}")
                }
            }

            launch.find {
                when {
                    isLinux -> OS.Linux
                    isWindows -> OS.Windows
                    isMacOsX -> OS.macOS
                    else -> OS.Linux
                } == it.os && arch == it.arch
            } ?: throw GradleException("Could not find launch information for the current OS: $name ($arch)")
        }.run {
            copy(additionalJvmArguments = additionalJvmArguments.map { it.trim('"') })
        }
}

@Serializable
data class Launch(
    val os: OS? = null,
    val arch: String? = null,
    val launcherPath: String? = null,
    val javaExecutablePath: String? = null,
    val vmOptionsFilePath: String? = null,
    val startupWmClass: String? = null,
    val bootClassPathJarNames: List<String> = mutableListOf(),
    val additionalJvmArguments: List<String> = mutableListOf(),
)

@Serializable
data class CustomProperty(
    val key: String? = null,
    val value: String? = null,
)

@Suppress("EnumEntryName")
enum class OS {
    Linux, Windows, macOS
}

internal fun ProductInfo.getBootClasspath(ideDir: Path) =
    currentLaunch
        .bootClassPathJarNames
        .map { "$ideDir/lib/$it" }

internal fun Path.resolveProductInfoPath(name: String = "product-info.json") =
    listOf(this, resolve(name), resolve("Resources").resolve(name))
        .find { it.name == name && it.exists() }
        ?: throw GradleException("Could not resolve $name file in: $this")

private val json = Json { ignoreUnknownKeys = true }
fun Path.productInfo() = resolveProductInfoPath()
    .run { json.decodeFromString<ProductInfo>(readText()) }