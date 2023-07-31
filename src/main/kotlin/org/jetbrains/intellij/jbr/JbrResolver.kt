// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.jbr

import com.jetbrains.plugin.structure.base.utils.exists
import com.jetbrains.plugin.structure.base.utils.listFiles
import com.jetbrains.plugin.structure.base.utils.simpleName
import org.gradle.api.provider.Provider
import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.kotlin.dsl.create
import org.jetbrains.intellij.*
import org.jetbrains.intellij.IntelliJPluginConstants.DEFAULT_JBR_REPOSITORY
import org.jetbrains.intellij.IntelliJPluginConstants.JETBRAINS_JAVA_TOOLCHAIN_VENDOR_NAME
import org.jetbrains.intellij.utils.ArchiveUtils
import org.jetbrains.intellij.utils.DependenciesDownloader
import org.jetbrains.intellij.utils.ivyRepository
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.nio.file.Path
import java.util.*
import javax.inject.Inject

abstract class JbrResolver @Inject constructor(
    private val jreRepository: Provider<String>,
    private val archiveUtils: ArchiveUtils,
    private val dependenciesDownloader: DependenciesDownloader,
    private val javaToolchainSpec: JavaToolchainSpec,
    private val javaToolchainService: JavaToolchainService,
    private val context: String?,
) {

    private val operatingSystem = OperatingSystem.current()

    fun resolveRuntimeDir(
        runtimeDir: String? = null,
        jbrVersion: String? = null,
        jbrVariant: String? = null,
        jbrArch: String? = null,
        ideDir: File? = null,
        validate: (executable: Path) -> Boolean = { true },
    ) = resolveRuntime(runtimeDir, jbrVersion, jbrVariant, jbrArch, ideDir, false, validate)

    fun resolveRuntime(
        runtimeDir: String? = null,
        jbrVersion: String? = null,
        jbrVariant: String? = null,
        jbrArch: String? = null,
        ideDir: File? = null,
        resolveExecutable: Boolean = true,
        validate: (executable: Path) -> Boolean = { true },
    ): Path? {
        debug(context, "Resolving runtime directory.")

        return listOf(
            {
                runtimeDir?.let { path ->
                    Path.of(path)
                        .let(::getJbrRoot)
                        .run {
                            when (resolveExecutable) {
                                true -> resolve("bin/java")
                                else -> this
                            }
                        }
                        .takeIf(Path::exists)
                        .also { debug(context, "Runtime specified with runtimeDir='$path' resolved as: $it") }
                        .ifNull { debug(context, "Cannot resolve runtime with runtimeDir='$path'") }
                }
            },
            {
                jbrVersion?.let { version ->
                    resolve(version, jbrVariant, jbrArch)?.run {
                        when (resolveExecutable) {
                            true -> javaExecutable?.let { Path.of(it) }
                            else -> javaHome.let(::getJbrRoot)
                        }
                    }
                        .also { debug(context, "Runtime specified with jbrVersion='$version', jbrVariant='$jbrVariant', jbrArch='$jbrArch' resolved as: $it") }
                        .ifNull { debug(context, "Cannot resolve runtime with jbrVersion='$version', jbrVariant='$jbrVariant', jbrArch='$jbrArch'") }
                }
            },
            {
                @Suppress("UnstableApiUsage")
                with(javaToolchainSpec) {
                    when {
                        vendor.isPresent && vendor.get().matches(JETBRAINS_JAVA_TOOLCHAIN_VENDOR_NAME) -> javaToolchainService.launcherFor(this).get()
                        else -> null
                    }
                }?.let { javaLauncher ->
                    val path = javaLauncher.metadata
                        .installationPath
                        .asPath
                        .let(::getJbrRoot)

                    path
                        .run {
                            when (resolveExecutable) {
                                true -> resolve("bin/java")
                                else -> this
                            }
                        }
                        .takeIf(Path::exists)
                        .also { debug(context, "Runtime specified with gradle project java toolchain='$path' resolved as: $it") }
                        .ifNull { debug(context, "Cannot resolve runtime specified with gradle project java toolchain='$path'") }
                }
            },
            {
                ideDir?.let { file ->
                    file
                        .toPath()
                        .let(::getJbrRoot)
                        .run {
                            resolve("bin/java").takeIf(Path::exists)?.let { executable ->
                                when (resolveExecutable) {
                                    true -> executable
                                    else -> takeIf { executable.exists() }
                                }
                            }
                        }
                        .also { debug(context, "Runtime specified with ideDir='$file' resolved as: $it") }
                        .ifNull { debug(context, "Cannot resolve runtime with ideDir='$file'") }
                }
            },
            {
                ideDir?.let { file ->
                    getBuiltinJbrVersion(file)
                        ?.let { version ->
                            resolve(version, jbrVariant, jbrArch)?.run {
                                when (resolveExecutable) {
                                    true -> javaExecutable?.let { Path.of(it) }
                                    else -> javaHome.let(::getJbrRoot)
                                }
                            }
                                .also { debug(context, "Runtime specified with ideDir='$file', version='$version' resolved as: $it") }
                                .ifNull { debug(context, "Cannot resolve runtime with ideDir='$file', version='$version'") }
                        }
                        .ifNull { debug(context, "Cannot resolve runtime with ideDir='$file'") }
                }
            },
            {
                Jvm.current()
                    .run {
                        when (resolveExecutable) {
                            true -> javaExecutable.toPath()
                            false -> javaHome.toPath().let(::getJbrRoot)
                        }
                    }
                    .also { debug(context, "Using current JVM: $it") }
                    .ifNull { debug(context, "Cannot resolve current JVM") }
            },
        )
            .asSequence()
            .mapNotNull { it()?.takeIf(validate) }
            .firstOrNull()
            ?.also { info(context, "Resolved JVM Runtime directory: $it") }
            .also {
                println("it = ${it}")
            }
    }

    fun resolve(version: String?, variant: String?, arch: String?): Jbr? {
        if (version.isNullOrEmpty()) {
            return null
        }
        val jbrArtifact = JbrArtifact.from(version, variant, arch, operatingSystem)

        return getJavaArchive(jbrArtifact)?.let {
            val javaDir = Path.of(it.toString().replaceAfter(jbrArtifact.name, "")).resolve("extracted")
            archiveUtils.extract(it, javaDir, context)
            fromDir(javaDir, version)
        }
    }

    private fun fromDir(javaDir: Path, version: String) =
        findJavaExecutable(javaDir)
            ?.let { Jbr(version, javaDir, it.toFile().canonicalPath) }
            .ifNull { warn(context, "Cannot find java executable in: $javaDir") }

    private fun getJavaArchive(jbrArtifact: JbrArtifact): Path? {
        val url = jreRepository
            .orNull
            .takeUnless { it.isNullOrBlank() }
            .or(jbrArtifact.repositoryUrl)

        return try {
            dependenciesDownloader.downloadFromRepository(context, {
                create(
                    group = "com.jetbrains",
                    name = "jbre",
                    version = jbrArtifact.name,
                    ext = "tar.gz",
                )
            }, {
                ivyRepository(url, "[revision].tar.gz")
            }, true).first().toPath()
        } catch (e: Exception) {
            warn(context, "Cannot download JetBrains Java Runtime '${jbrArtifact.name}'")
            null
        }
    }

    private fun findJavaExecutable(javaHome: Path): Path? {
        val root = getJbrRoot(javaHome)
        val jre = root.resolve("jre")
        val java = jre
            .takeIf { it.exists() }
            .or(root)
            .resolve("bin/java" + ".exe".takeIf { operatingSystem.isWindows }.orEmpty())
        return java.takeIf { java.exists() }
    }

    private fun getJbrRoot(javaHome: Path): Path {
        val jbr = javaHome.listFiles().firstOrNull { it.simpleName.startsWith("jbr") }?.takeIf(Path::exists)
        return when {
            operatingSystem.isMacOsX -> when {
                javaHome.endsWith("Contents/Home") -> javaHome
                jbr != null -> jbr.resolve("Contents/Home")
                else -> javaHome.resolve("jdk/Contents/Home")
            }

            else -> when {
                jbr != null -> jbr
                else -> javaHome
            }
        }
    }

    private fun getBuiltinJbrVersion(ideDirectory: File): String? {
        val dependenciesFile = File(ideDirectory, "dependencies.txt")
        if (dependenciesFile.exists()) {
            val properties = Properties()
            val reader = FileReader(dependenciesFile)
            try {
                properties.load(reader)
                return properties.getProperty("runtimeBuild") ?: properties.getProperty("jdkBuild")
            } catch (ignore: IOException) {
            } finally {
                reader.close()
            }
        }
        return null
    }

    internal class JbrArtifact(val name: String, val repositoryUrl: String) {

        companion object {
            fun from(jbrVersion: String, jbrVariant: String?, jbrArch: String?, operatingSystem: OperatingSystem): JbrArtifact {
                val version = "8".takeIf { jbrVersion.startsWith('u') }.orEmpty() + jbrVersion
                var prefix = getPrefix(version, jbrVariant)
                val lastIndexOfB = version.lastIndexOf('b')
                val lastIndexOfDash = version.lastIndexOf('-') + 1
                val majorVersion = when (lastIndexOfB > -1) {
                    true -> version.substring(lastIndexOfDash, lastIndexOfB)
                    false -> version.substring(lastIndexOfDash)
                }
                val buildNumberString = when (lastIndexOfB > -1) {
                    (lastIndexOfDash == lastIndexOfB) -> version.substring(0, lastIndexOfDash - 1)
                    true -> version.substring(lastIndexOfB + 1)
                    else -> ""
                }
                val buildNumber = Version.parse(buildNumberString)
                val isJava8 = majorVersion.startsWith("8")
                val isJava17 = majorVersion.startsWith("17")

                val oldFormat = prefix == "jbrex" || isJava8 && buildNumber < Version.parse("1483.24")
                if (oldFormat) {
                    return JbrArtifact(
                        "jbrex${majorVersion}b${buildNumberString}_${platform(operatingSystem)}_${arch(false)}",
                        DEFAULT_JBR_REPOSITORY,
                    )
                }

                val arch = jbrArch ?: arch(isJava8)
                if (prefix.isEmpty()) {
                    prefix = when {
                        isJava17 -> "jbr_jcef-"
                        isJava8 -> "jbrx-"
                        operatingSystem.isMacOsX && arch == "aarch64" -> "jbr_jcef-"
                        buildNumber < Version.parse("1319.6") -> "jbr-"
                        else -> "jbr_jcef-"
                    }
                }

                return JbrArtifact(
                    "$prefix$majorVersion-${platform(operatingSystem)}-$arch-b$buildNumberString",
                    DEFAULT_JBR_REPOSITORY,
                )
            }

            private fun getPrefix(version: String, variant: String?) = when {
                !variant.isNullOrEmpty() -> when (variant) {
                    "sdk" -> "jbrsdk-"
                    else -> "jbr_$variant-"
                }

                version.startsWith("jbrsdk-") -> "jbrsdk-"
                version.startsWith("jbr_jcef-") -> "jbr_jcef-"
                version.startsWith("jbr_dcevm-") -> "jbr_dcevm-"
                version.startsWith("jbr_fd-") -> "jbr_fd-"
                version.startsWith("jbr_nomod-") -> "jbr_nomod-"
                version.startsWith("jbr-") -> "jbr-"
                version.startsWith("jbrx-") -> "jbrx-"
                version.startsWith("jbrex8") -> "jbrex"
                else -> ""
            }

            internal fun platform(operatingSystem: OperatingSystem) = when {
                operatingSystem.isWindows -> "windows"
                operatingSystem.isMacOsX -> "osx"
                else -> "linux"
            }

            internal fun arch(newFormat: Boolean): String {
                val arch = System.getProperty("os.arch")
                if ("aarch64" == arch || "arm64" == arch) {
                    return "aarch64"
                }
                if ("x86_64" == arch || "amd64" == arch) {
                    return "x64"
                }
                val name = System.getProperty("os.name")
                if (name.contains("Windows") && System.getenv("ProgramFiles(x86)") != null) {
                    return "x64"
                }
                return when (newFormat) {
                    true -> "i586"
                    false -> "x86"
                }
            }
        }
    }
}
