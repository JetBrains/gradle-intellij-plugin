// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.tasks

import com.jetbrains.plugin.structure.base.utils.createDir
import com.jetbrains.plugin.structure.base.utils.exists
import org.apache.tools.ant.util.TeeOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.newInstance
import org.gradle.process.ExecOperations
import org.gradle.process.internal.ExecException
import org.jetbrains.intellij.*
import org.jetbrains.intellij.IntelliJPluginConstants.CACHE_REDIRECTOR
import org.jetbrains.intellij.IntelliJPluginConstants.PLATFORM_TYPE_ANDROID_STUDIO
import org.jetbrains.intellij.IntelliJPluginConstants.PLATFORM_TYPE_INTELLIJ_COMMUNITY
import org.jetbrains.intellij.IntelliJPluginConstants.PLUGIN_VERIFIER_REPOSITORY
import org.jetbrains.intellij.IntelliJPluginConstants.VERSION_LATEST
import org.jetbrains.intellij.jbr.JbrResolver
import org.jetbrains.intellij.utils.ArchiveUtils
import org.jetbrains.intellij.utils.DependenciesDownloader
import org.jetbrains.intellij.utils.LatestVersionResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.util.*
import javax.inject.Inject

abstract class RunPluginVerifierTask @Inject constructor(
    private val objectFactory: ObjectFactory,
    private val execOperations: ExecOperations,
    private val providers: ProviderFactory,
) : DefaultTask() {

    companion object {
        private const val METADATA_URL = "$PLUGIN_VERIFIER_REPOSITORY/org/jetbrains/intellij/plugins/verifier-cli/maven-metadata.xml"
        private const val IDEA_DOWNLOAD_URL = "https://data.services.jetbrains.com/products/download"
        private const val ANDROID_STUDIO_DOWNLOAD_URL = "https://redirector.gvt1.com/edgedl/android/studio/ide-zips"

        fun resolveLatestVersion() = LatestVersionResolver.fromMaven("Plugin Verifier", METADATA_URL)
    }

    /**
     * Defines the verification level at which task should fail if any reported issue will match.
     * Can be set as [FailureLevel] enum or [EnumSet<FailureLevel>].
     *
     * Default value: [FailureLevel.COMPATIBILITY_PROBLEMS]
     */
    @get:Input
    abstract val failureLevel: ListProperty<FailureLevel>

    /**
     * A fallback file with a list of the releases generated with [ListProductsReleasesTask].
     * Used if [ideVersions] is not provided.
     */
    @get:Input
    @get:Optional
    abstract val productsReleasesFile: Property<File>

    /**
     * IDEs to check, in `intellij.version` format, i.e.: `["IC-2019.3.5", "PS-2019.3.2"]`.
     * Check the available build versions on [IntelliJ Platform Builds list](https://jb.gg/intellij-platform-builds-list).
     *
     * Default value: output of the [org.jetbrains.intellij.tasks.ListProductsReleasesTask] task
     */
    @get:Input
    @get:Optional
    abstract val ideVersions: ListProperty<String>

    /**
     * List of the paths to the specified IDE versions in [ideVersions] used for the verification.
     * By default, it resolves paths to the downloaded [ideVersions] IDEs.
     */
    @get:Input
    abstract val ides: ListProperty<File>

    /**
     * A list of the paths to locally installed IDE distributions that should be used for verification
     * in addition to those specified in [ideVersions].
     */
    @get:Input
    abstract val localPaths: ListProperty<File>

    /**
     * Returns the version of the IntelliJ Plugin Verifier that will be used.
     *
     * Default value: `latest`
     */
    @get:Input
    @get:Optional
    abstract val verifierVersion: Property<String>

    /**
     * Local path to the IntelliJ Plugin Verifier that will be used.
     * If provided, [verifierVersion] is ignored.
     *
     * Default value: path to the JAR file resolved using the [verifierVersion] property
     */
    @get:Input
    @get:Optional
    abstract val verifierPath: Property<String>

    /**
     * JAR or ZIP file of the plugin to verify.
     * If empty, the task will be skipped.
     *
     * Default value: output of the `buildPlugin` task
     */
    @get:InputFile
    @get:SkipWhenEmpty
    abstract val distributionFile: RegularFileProperty

    /**
     * The path to the directory where verification reports will be saved.
     *
     * Default value: `${project.buildDir}/reports/pluginVerifier`
     */
    @get:OutputDirectory
    @get:Optional
    abstract val verificationReportsDir: Property<String>

    /**
     * The path to the directory where IDEs used for the verification will be downloaded.
     *
     * Default value: `System.getProperty("plugin.verifier.home.dir")/ides`, `System.getenv("XDG_CACHE_HOME")/pluginVerifier/ides`,
     * `System.getProperty("user.home")/.cache/pluginVerifier/ides` or system temporary directory.
     */
    @get:Input
    @get:Optional
    abstract val downloadDir: Property<String>

    /**
     * Custom JBR version to use for running the IDE.
     *
     * All JetBrains Java versions are available at JetBrains Space Packages, and [GitHub](https://github.com/JetBrains/JetBrainsRuntime/releases).
     *
     * Accepted values:
     * - `8u112b752.4`
     * - `8u202b1483.24`
     * - `11_0_2b159`
     */
    @get:Input
    @get:Optional
    abstract val jbrVersion: Property<String>

    /**
     * JetBrains Runtime variant to use when running the IDE with the plugin.
     * See [JetBrains Runtime Releases](https://github.com/JetBrains/JetBrainsRuntime/releases).
     *
     * Default value: `null`
     *
     * Acceptable values:
     * - `jcef`
     * - `sdk`
     * - `fd`
     * - `dcevm`
     * - `nomod`
     *
     * Note: For `JBR 17`, `dcevm` is bundled by default. As a consequence, separated `dcevm` and `nomod` variants are no longer available.
     *
     * **Accepted values:**
     * - `8u112b752.4`
     * - `8u202b1483.24`
     * - `11_0_2b159`
     *
     * All JetBrains Java versions are available at JetBrains Space Packages,
     * and [GitHub](https://github.com/JetBrains/JetBrainsRuntime/releases).
     */
    @get:Input
    @get:Optional
    abstract val jbrVariant: Property<String>

    /**
     * URL of repository for downloading JetBrains Runtime.
     */
    @get:Input
    @get:Optional
    abstract val jreRepository: Property<String>

    /**
     * The path to directory containing JVM runtime, overrides [jbrVersion].
     */
    @get:Input
    @get:Optional
    abstract val runtimeDir: Property<String>

    /**
     * The list of classes prefixes from the external libraries.
     * The Plugin Verifier will not report `No such class` for classes of these packages.
     */
    @get:Input
    @get:Optional
    abstract val externalPrefixes: ListProperty<String>

    /**
     * A flag that controls the output format - if set to `true`, the TeamCity compatible output
     * will be returned to stdout.
     *
     * Default value: `false`
     */
    @get:Input
    @get:Optional
    abstract val teamCityOutputFormat: Property<Boolean>

    /**
     * Specifies which subsystems of IDE should be checked.
     *
     * Default value: `all`
     *
     * Acceptable values:**
     * - `all`
     * - `android-only`
     * - `without-android`
     */
    @get:Input
    @get:Optional
    abstract val subsystemsToCheck: Property<String>

    @get:Internal
    abstract val ideDir: Property<File>

    @get:Internal
    abstract val offline: Property<Boolean>

    private val context = logCategory()

    /**
     * Runs the IntelliJ Plugin Verifier against the plugin artifact.
     */
    @TaskAction
    fun runPluginVerifier() {
        val file = distributionFile.orNull
        if (file == null || !file.asFile.exists()) {
            throw IllegalStateException("Plugin file does not exist: $file")
        }

        val paths = (ides.get() + localPaths.get()).mapNotNull(File::getCanonicalPath)
        if (paths.isEmpty()) {
            throw GradleException("'ideVersions' and 'localPaths' properties should not be empty")
        }

        val verifierPath = resolveVerifierPath()
        val verifierArgs = listOf("check-plugin") + getOptions() + file.asFile.canonicalPath + paths

        debug(context, "Distribution file: ${file.asFile.canonicalPath}")
        debug(context, "Verifier path: $verifierPath")

        ByteArrayOutputStream().use { os ->
            try {
                execOperations.javaexec {
                    classpath = objectFactory.fileCollection().from(verifierPath)
                    mainClass.set("com.jetbrains.pluginverifier.PluginVerifierMain")
                    args = verifierArgs
                    standardOutput = TeeOutputStream(System.out, os)
                }
            } catch (e: ExecException) {
                error(context, "Error during Plugin Verifier CLI execution:\n$os")
                throw e
            }

            debug(context, "Current failure levels: ${FailureLevel.values().joinToString(", ")}")
            FailureLevel.values().forEach { level ->
                if (failureLevel.get().contains(level) && os.toString().contains(level.sectionHeading)) {
                    debug(context, "Failing task on '$failureLevel' failure level")
                    throw GradleException(
                        "$level: ${level.message} Check Plugin Verifier report for more details.\n" +
                                "Incompatible API Changes: https://jb.gg/intellij-api-changes"
                    )
                }
            }
        }
    }

    /**
     * Resolves the path to the IntelliJ Plugin Verifier file.
     * At first, checks if it was provided with [verifierPath].
     * Fetches IntelliJ Plugin Verifier artifact from the [PLUGIN_VERIFIER_REPOSITORY] repository and resolves the path to `verifier-cli` jar file.
     *
     * @return path to `verifier-cli` jar
     */
    private fun resolveVerifierPath() =
        verifierPath.orNull
            ?.let(Path::of)
            ?.takeIf(Path::exists)
            ?: throw InvalidUserDataException("Provided Plugin Verifier path doesn't exist: '$path'. Downloading Plugin Verifier: $verifierVersion")

    /**
     * Resolves the Java Runtime directory.
     * [runtimeDir] property is used if provided with the task configuration.
     * Otherwise, [jbrVersion] is used for resolving the JBR.
     * If it's not set, or it's impossible to resolve a valid version, built-in JBR will be used.
     * As a last fallback, current JVM will be used.
     *
     * @return path to the Java Runtime directory
     */
    private fun resolveRuntimeDir(): String {
        val archiveUtils = objectFactory.newInstance<ArchiveUtils>()
        val dependenciesDownloader = objectFactory.newInstance<DependenciesDownloader>()
        val jbrResolver = objectFactory.newInstance<JbrResolver>(
            jreRepository.orNull.orEmpty(),
            offline.get(),
            archiveUtils,
            dependenciesDownloader,
            context,
        )

        return jbrResolver.resolveRuntimeDir(
            runtimeDir = runtimeDir.orNull,
            jbrVersion = jbrVersion.orNull,
            jbrVariant = jbrVariant.orNull,
            ideDir = ideDir.orNull,
        ) {
            validateRuntimeDir(it)
        } ?: throw InvalidUserDataException(
            when {
                requiresJava11() -> "Java Runtime directory couldn't be resolved. Note: Plugin Verifier 1.260+ requires Java 11"
                else -> "Java Runtime directory couldn't be resolved"
            }
        )
    }

    /**
     * Verifies if provided Java Runtime directory points to Java 11 when using Plugin Verifier 1.260+.
     *
     * @return Java Runtime directory points to Java 8 for Plugin Verifier versions < 1.260, or Java 11 for 1.260+.
     */
    private fun validateRuntimeDir(runtimeDirPath: String) = ByteArrayOutputStream().use { os ->
        debug(context, "Plugin Verifier JRE verification: $runtimeDirPath")

        if (!requiresJava11()) {
            return true
        }

        execOperations.exec {
            executable = File(runtimeDirPath).resolve("bin/java").canonicalPath
            args = listOf("-version")
            errorOutput = os
        }
        val version = Version.parse(os.toString())
        val result = version >= Version(11)

        result.ifFalse {
            debug(context, "Plugin Verifier 1.260+ requires Java 11, but '$version' was provided with 'runtimeDir': $runtimeDirPath")
        }
    }

    /**
     * Checks the Plugin Verifier version, if 1.260+, require Java 11 to run.
     */
    private fun requiresJava11() = resolveVerifierVersion(verifierVersion.orNull).let(Version::parse) >= Version(1, 260)

    /**
     * Collects all the options for the Plugin Verifier CLI provided with the task configuration.
     *
     * @return array with all available CLI options
     */
    private fun getOptions(): List<String> {
        val args = mutableListOf(
            "-verification-reports-dir", verificationReportsDir.get(),
            "-runtime-dir", resolveRuntimeDir(),
        )

        externalPrefixes.get().takeIf { it.isNotEmpty() }?.let {
            args.add("-external-prefixes")
            args.add(it.joinToString(":"))
        }
        if (teamCityOutputFormat.get()) {
            args.add("-team-city")
        }
        subsystemsToCheck.orNull?.let {
            args.add("-subsystems-to-check")
            args.add(it)
        }
        if (offline.get()) {
            args.add("-offline")
        }

        return args
    }

    /**
     * Retrieve the Plugin Verifier home directory used for storing downloaded IDEs.
     * Following home directory resolving method is taken directly from the Plugin Verifier to keep the compatibility.
     *
     * @TODO: Remove `forUseAtConfigurationTime` when Gradle 7+ is used
     * @return Plugin Verifier home directory
     */
    @Suppress("DEPRECATION")
    private fun verifierHomeDir() = providers.systemProperty("plugin.verifier.home.dir")
        .forUseAtConfigurationTime()
        .map { Path.of(it) }
        .orElse(providers.environmentVariable("XDG_CACHE_HOME").forUseAtConfigurationTime().map { Path.of(it).resolve("pluginVerifier") })
        .orElse(providers.systemProperty("user.home").forUseAtConfigurationTime().map { Path.of(it).resolve(".cache/pluginVerifier") })
        .orElse(temporaryDir.toPath().resolve("pluginVerifier"))

    /**
     * Resolves the Plugin Verifier version.
     * If set to [VERSION_LATEST], there's request to [METADATA_URL] performed for the latest available version.
     *
     * @return Plugin Verifier version
     */
    internal fun resolveVerifierVersion(version: String?) = version?.takeIf { it != VERSION_LATEST } ?: resolveLatestVersion()

    /**
     * Resolves the IDE type and version. If only `version` is provided, `type` is set to "IC".
     *
     * @param ideVersion IDE version. Can be "2020.2", "IC-2020.2", "202.1234.56"
     * @return path to the resolved IDE
     */
    internal fun resolveIdePath(
        ideVersion: String,
        downloadPath: Path,
        context: String?,
        block: (type: String, version: String, buildType: String) -> Path,
    ): Path {
        debug(context, "Resolving IDE path for: $ideVersion")
        var (type, version) = ideVersion.trim().split('-', limit = 2) + null

        if (version == null) {
            debug(context, "IDE type not specified, setting type to $PLATFORM_TYPE_INTELLIJ_COMMUNITY")
            version = type
            type = PLATFORM_TYPE_INTELLIJ_COMMUNITY
        }

        val name = "$type-$version"
        val ideDirPath = downloadPath.resolve(name)

        if (ideDirPath.exists()) {
            debug(context, "IDE already available in: $ideDirPath")
            return ideDirPath
        }

        val buildTypes = when (type) {
            PLATFORM_TYPE_ANDROID_STUDIO -> listOf("")
            else -> listOf("release", "rc", "eap", "beta")
        }

        buildTypes.forEach { buildType ->
            debug(context, "Downloading IDE '$type-$version' from '$buildType' channel to: $downloadPath")
            try {
                return block(type!!, version!!, buildType).also {
                    debug(context, "Resolved IDE '$type-$version' path: $it")
                }
            } catch (e: IOException) {
                debug(context, "Cannot download IDE '$type-$version' from '$buildType' channel. Trying another channel...", e)
            }
        }

        throw GradleException("IDE '$ideVersion' cannot be downloaded. Please verify the specified IDE version against the products available for testing: https://jb.gg/intellij-platform-builds-list")
    }

    /**
     * Resolves direct IDE download URL provided by the JetBrains Data Services.
     * The URL created with [IDEA_DOWNLOAD_URL] contains HTTP redirection, which is supposed to be resolved.
     * Direct download URL is prepended with [CACHE_REDIRECTOR] host for providing caching mechanism.
     *
     * @param type IDE type, i.e. IC, PS
     * @param version IDE version, i.e. 2020.2 or 203.1234.56
     * @param buildType release, rc, eap, beta
     * @return direct download URL prepended with [CACHE_REDIRECTOR] host
     */
    internal fun resolveIdeUrl(type: String, version: String, buildType: String, context: String?): String {
        val isAndroidStudio = type == PLATFORM_TYPE_ANDROID_STUDIO
        val url = when {
            isAndroidStudio -> "$ANDROID_STUDIO_DOWNLOAD_URL/$version/android-studio-$version-linux.tar.gz"
            else -> "$IDEA_DOWNLOAD_URL?code=$type&platform=linux&type=$buildType&${versionParameterName(version)}=$version"
        }

        debug(context, "Resolving direct IDE download URL for: $url")

        var connection: HttpURLConnection? = null

        try {
            with(URL(url).openConnection() as HttpURLConnection) {
                connection = this
                instanceFollowRedirects = false
                inputStream.use {
                    if ((responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) && !isAndroidStudio) {
                        val redirectUrl = URL(getHeaderField("Location"))
                        disconnect()
                        debug(context, "Resolved IDE download URL: $url")
                        return "$CACHE_REDIRECTOR/${redirectUrl.host}${redirectUrl.file}"
                    } else {
                        debug(context, "IDE download URL has no redirection provided. Skipping")
                    }
                }
            }
        } catch (e: Exception) {
            info(context, "Cannot resolve direct download URL for: $url")
            debug(context, "Download exception stacktrace:", e)
            throw e
        } finally {
            connection?.disconnect()
        }

        return url
    }

    /**
     * Obtains the version parameter name used for downloading IDE artifact.
     *
     * Examples:
     * - 202.7660.26 -> build
     * - 2020.1, 2020.2.3 -> version
     *
     * @param version current version
     * @return version parameter name
     */
    private fun versionParameterName(version: String) = when {
        version.matches("\\d{3}(\\.\\d+)+".toRegex()) -> "build"
        else -> "version"
    }

    /**
     * Provides target directory used for storing downloaded IDEs.
     * Path is compatible with the Plugin Verifier approach.
     *
     * @return directory for downloaded IDEs
     */
    internal fun ideDownloadDir() = verifierHomeDir().map { it.resolve("ides").createDir() }

    enum class FailureLevel(val sectionHeading: String, val message: String) {
        COMPATIBILITY_WARNINGS(
            "Compatibility warnings",
            "Compatibility warnings detected against the specified IDE version."
        ),
        COMPATIBILITY_PROBLEMS(
            "Compatibility problems",
            "Compatibility problems detected against the specified IDE version."
        ),
        DEPRECATED_API_USAGES(
            "Deprecated API usages",
            "Plugin uses API marked as deprecated (@Deprecated)."
        ),
        SCHEDULED_FOR_REMOVAL_API_USAGES(
            /* # usage(s) of */"scheduled for removal API",
            "Plugin uses API marked as scheduled for removal (ApiStatus.@ScheduledForRemoval)."
        ),
        EXPERIMENTAL_API_USAGES(
            "Experimental API usages",
            "Plugin uses API marked as experimental (ApiStatus.@Experimental)."
        ),
        INTERNAL_API_USAGES(
            "Internal API usages",
            "Plugin uses API marked as internal (ApiStatus.@get:Internal)."
        ),
        OVERRIDE_ONLY_API_USAGES(
            "Override-only API usages",
            "Override-only API is used incorrectly (ApiStatus.@OverrideOnly)."
        ),
        NON_EXTENDABLE_API_USAGES(
            "Non-extendable API usages",
            "Non-extendable API is used incorrectly (ApiStatus.@NonExtendable)."
        ),
        PLUGIN_STRUCTURE_WARNINGS(
            "Plugin structure warnings",
            "The structure of the plugin is not valid."
        ),
        MISSING_DEPENDENCIES(
            "Missing dependencies",
            "Plugin has some dependencies missing."
        ),
        INVALID_PLUGIN(
            "The following files specified for the verification are not valid plugins",
            "Provided plugin artifact is not valid."
        ),
        NOT_DYNAMIC(
            "Plugin cannot be loaded/unloaded without IDE restart",
            "Plugin cannot be loaded/unloaded without IDE restart."
        );

        companion object {
            val ALL: EnumSet<FailureLevel> = EnumSet.allOf(FailureLevel::class.java)
            val NONE: EnumSet<FailureLevel> = EnumSet.noneOf(FailureLevel::class.java)
        }
    }
}
