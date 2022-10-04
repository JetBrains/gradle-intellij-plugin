// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.model

import kotlinx.serialization.Serializable
import org.gradle.api.GradleException
import org.gradle.internal.os.OperatingSystem

@Serializable
data class ProductInfo(
    val name: String? = null,
    val version: String? = null,
    val versionSuffix: String? = null,
    val buildNumber: String? = null,
    val productCode: String? = null,
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
            launch.find {
                when {
                    isLinux -> OS.Linux
                    isWindows -> OS.Windows
                    isMacOsX -> OS.macOS
                    else -> OS.Linux
                } == it.os
            } ?: throw GradleException("Could not find launch information for the current OS: $name")
        }
}

@Serializable
data class Launch(
    val os: OS? = null,
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
