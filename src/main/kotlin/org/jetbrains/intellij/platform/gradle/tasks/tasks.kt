// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.platform.gradle.tasks

import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.*
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.JavaExecSpec
import org.gradle.process.JavaForkOptions
import org.jetbrains.intellij.platform.gradle.IntelliJPluginConstants.Configurations
import org.jetbrains.intellij.platform.gradle.IntelliJPluginConstants.Configurations.Attributes
import org.jetbrains.intellij.platform.gradle.IntelliJPluginConstants.LOG_PREFIX
import org.jetbrains.intellij.platform.gradle.IntelliJPluginConstants.Sandbox
import org.jetbrains.intellij.platform.gradle.IntelliJPluginConstants.Tasks
import org.jetbrains.intellij.platform.gradle.argumentProviders.IntelliJPlatformArgumentProvider
import org.jetbrains.intellij.platform.gradle.argumentProviders.SandboxArgumentProvider
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.model.ProductInfo
import org.jetbrains.intellij.platform.gradle.model.launchFor
import org.jetbrains.intellij.platform.gradle.model.productInfo
import org.jetbrains.intellij.platform.gradle.pathResolver.IntelliJPluginVerifierResolver
import org.jetbrains.intellij.platform.gradle.pathResolver.MarketplaceZipSignerResolver
import org.jetbrains.intellij.platform.gradle.pathResolver.RuntimeResolver
import org.jetbrains.intellij.platform.gradle.provider.ExecutableArchValueSource
import org.jetbrains.intellij.platform.gradle.tasks.aware.*
import org.jetbrains.intellij.platform.gradle.toIntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.utils.ALL_TASKS
import org.jetbrains.intellij.platform.gradle.utils.asPath
import org.jetbrains.intellij.platform.gradle.utils.create
import org.jetbrains.intellij.platform.gradle.utils.isSpecified
import java.util.*
import kotlin.io.path.createDirectories

/**
 * Registers a task of type T with the given names and configures its extra capabilities based on the [org.jetbrains.intellij.platform.gradle.tasks.aware]
 * interfaces it utilizes.
 * Every new task is supposed to be registered using this method to get extra configuration utilized.
 *
 * @param T the type of task to register
 * @param names the names of the task to register
 * @param configuration the configuration function for the task, default is an empty function
 */
internal inline fun <reified T : Task> Project.registerTask(vararg names: String, noinline configuration: T.() -> Unit = {}) {

    // Register new tasks of T type if do not exist yet
    names.forEach { name ->
        project.logger.info("$LOG_PREFIX Configuring task: $name")
        tasks.maybeCreate<T>(name)
    }

    // Preconfigure all tasks of T type if they inherit from *Aware interfaces
    tasks.withType<T> {
        /**
         * The suffix used to build unique names for configurations and tasks for [CustomIntelliJPlatformVersionAware] purposes
         *
         * @see CustomIntelliJPlatformVersionAware
         */
        val suffix = UUID.randomUUID().toString().substring(0, 8)
        val extension = project.the<IntelliJPlatformExtension>()

        /**
         * Applies the base [Configurations.INTELLIJ_PLATFORM] configuration to [IntelliJPlatformVersionAware] tasks so they can access details of the used IntelliJ
         * Platform, such as [ProductInfo] or its root directory location.
         *
         * @see IntelliJPlatformVersionAware
         */
        if (this is IntelliJPlatformVersionAware) {
            intelliJPlatformConfiguration.from(configurations.getByName(Configurations.INTELLIJ_PLATFORM))
        }

        /**
         * Makes tasks aware of the Coroutines Java Agent file required to debug coroutines when running IDE locally.
         *
         * @see CoroutinesJavaAgentAware
         */
        if (this is CoroutinesJavaAgentAware) {
            val initializeIntelliJPlatformPluginTaskProvider = tasks.named<InitializeIntelliJPlatformPluginTask>(Tasks.INITIALIZE_INTELLIJ_PLATFORM_PLUGIN)

            coroutinesJavaAgentFile.convention(initializeIntelliJPlatformPluginTaskProvider.flatMap {
                it.coroutinesJavaAgent
            })

            dependsOn(initializeIntelliJPlatformPluginTaskProvider)
        }

        /**
         * The concept of [CustomIntelliJPlatformVersionAware] allows to let task accept and utilize a custom IntelliJ Platform other than the one used to build the project
         *
         * @see CustomIntelliJPlatformVersionAware
         */
        if (this is CustomIntelliJPlatformVersionAware) {
            val baseIntellijPlatformConfiguration = configurations.getByName(Configurations.INTELLIJ_PLATFORM)
            val dependenciesExtension = this@registerTask.dependencies.the<IntelliJPlatformDependenciesExtension>()
            val defaultTypeProvider = provider {
                val productInfo = baseIntellijPlatformConfiguration.productInfo()
                productInfo.productCode.toIntelliJPlatformType()
            }
            val defaultVersionProvider = provider {
                val productInfo = baseIntellijPlatformConfiguration.productInfo()
                IdeVersion.createIdeVersion(productInfo.version).toString()
            }

            with(configurations) {
                /**
                 * A custom IntelliJ Platform Dependency configuration to which we add a new artifact using [CustomIntelliJPlatformVersionAware.type]
                 * and [CustomIntelliJPlatformVersionAware.version].
                 * As both parameters default to the base IntelliJ Platform values, this configuration always holds some dependency.
                 * This configuration is ignored if [CustomIntelliJPlatformVersionAware.localPath] is set.
                 */
                val intellijPlatformDependencyConfiguration = create(
                    name = "${Configurations.INTELLIJ_PLATFORM_DEPENDENCY}_$suffix",
                    description = "Custom IntelliJ Platform dependency archive",
                ) {
                    dependenciesExtension.create(
                        type = type.orElse(defaultTypeProvider),
                        version = version.orElse(defaultVersionProvider),
                        configurationName = name,
                    )
                }

                /**
                 * A custom IntelliJ Platform Local Instance configuration to which we add a new artifact using [CustomIntelliJPlatformVersionAware.localPath].
                 */
                val intellijPlatformLocalInstanceConfiguration = create(
                    name = "${Configurations.INTELLIJ_PLATFORM_LOCAL_INSTANCE}_$suffix",
                    description = "Custom IntelliJ Platform local instance",
                ) {
                    dependenciesExtension.local(
                        localPath = localPath,
                        configurationName = name,
                    )
                }

                /**
                 * A high-level configuration to extract the configuration defined with `intellijPlatformDependencyConfiguration`.
                 */
                val intellijPlatformConfiguration = create(
                    name = "${Configurations.INTELLIJ_PLATFORM}_$suffix",
                    description = "Custom IntelliJ Platform",
                ) {
                    attributes {
                        attribute(Attributes.extracted, true)
                    }

                    extendsFrom(intellijPlatformDependencyConfiguration)
                }

                /**
                 * A high-level configuration to extract the configuration defined with `intellijPlatformLocalInstanceConfiguration`.
                 */
                val intellijPlatformForLocalInstanceConfiguration = create(
                    name = "${Configurations.INTELLIJ_PLATFORM}_forLocalInstance_$suffix",
                    description = "Custom IntelliJ Platform for local instance",
                ) {
                    attributes {
                        attribute(Attributes.extracted, true)
                    }

                    extendsFrom(intellijPlatformLocalInstanceConfiguration)
                }

                /**
                 * Override the default [intelliJPlatformConfiguration] with a custom IntelliJ Platform configuration so the current task can refer to it.
                 * Depending on whether [CustomIntelliJPlatformVersionAware.localPath] or any of [CustomIntelliJPlatformVersionAware.type] and
                 * [CustomIntelliJPlatformVersionAware.version] is set, and a custom configuration is picked as a replacement.
                 * Otherwise, refer to the base IntelliJ Platform — useful, i.e., when we want to execute a regular [RunIdeTask] using defaults.
                 */
                intelliJPlatformConfiguration.setFrom(provider {
                    when {
                        localPath.isSpecified() -> intellijPlatformForLocalInstanceConfiguration
                        type.isSpecified() || version.isSpecified() -> intellijPlatformConfiguration
                        else -> baseIntellijPlatformConfiguration
                    }
                })
            }
        }

        /**
         * It lets tasks utilize the sandbox directories, i.e., to run a guest IDE instance or execute various tests.
         */
        if (this is SandboxAware) {
            val sandboxDirectoryProvider = extension.sandboxContainer.map { container ->
                container
                    .dir("${productInfo.productCode}-${productInfo.version}")
                    .apply { asPath.createDirectories() }
            }

            /**
             * multiple [PrepareSandboxTask] tasks may be registered for different purposes — running tests or IDE.
             * To keep sandboxes separated, we introduce sandbox suffixes.
             */
            sandboxSuffix.convention(
                when {
                    this is PrepareSandboxTask -> when (name.substringBefore("_")) {
                        Tasks.PREPARE_TEST_SANDBOX -> "-test"
                        Tasks.PREPARE_UI_TEST_SANDBOX -> "-uiTest"
                        Tasks.PREPARE_SANDBOX -> ""
                        else -> ""
                    }

                    else -> ""
                }
            )
            sandboxContainerDirectory.convention(sandboxDirectoryProvider)
            sandboxConfigDirectory.configureSandbox(sandboxContainerDirectory, sandboxSuffix, Sandbox.CONFIG)
            sandboxPluginsDirectory.configureSandbox(sandboxContainerDirectory, sandboxSuffix, Sandbox.PLUGINS)
            sandboxSystemDirectory.configureSandbox(sandboxContainerDirectory, sandboxSuffix, Sandbox.SYSTEM)
            sandboxLogDirectory.configureSandbox(sandboxContainerDirectory, sandboxSuffix, Sandbox.LOG)

            /**
             * Some tasks are designed to work with the sandbox, so we explicitly make them depend on the [PrepareSandboxTask] task.
             * This also handles [CustomIntelliJPlatformVersionAware] tasks, for which we refer to the `suffix` variable.
             * No suffix is used if a task is a base task provided with the IntelliJ Platform Gradle Plugin as a default.
             */
            if (this !is PrepareSandboxTask) {
                val isBuiltInTask = ALL_TASKS.contains(name)
                val prepareSandboxTaskName = when (this) {
                    is RunIdeTask -> Tasks.PREPARE_SANDBOX
                    is TestIdeTask -> Tasks.PREPARE_TEST_SANDBOX
                    is TestIdeUiTask -> Tasks.PREPARE_UI_TEST_SANDBOX
                    else -> Tasks.PREPARE_SANDBOX
                } + "_$suffix".takeUnless { isBuiltInTask }.orEmpty()

                val prepareSandboxTask = tasks.maybeCreate<PrepareSandboxTask>(prepareSandboxTaskName)
                dependsOn(prepareSandboxTask)
            }
        }

        /**
         * The [PluginAware] resolves and parses the `plugin.xml` file for easy access in other tasks.
         */
        if (this is PluginAware) {
            val patchPluginXmlTaskProvider = tasks.named<PatchPluginXmlTask>(Tasks.PATCH_PLUGIN_XML)

            pluginXml.convention(patchPluginXmlTaskProvider.flatMap { it.outputFile })

            dependsOn(patchPluginXmlTaskProvider)
        }

        /**
         * The [RuntimeAware] adjusts tasks for the running a guest IDE purpose.
         * This configuration picks relevant Java Runtime using the [RuntimeResolver] and [RuntimeAware.runtimeArch].
         */
        if (this is RuntimeAware) {
            val runtimeResolver = RuntimeResolver(
                jetbrainsRuntime = configurations.getByName(Configurations.JETBRAINS_RUNTIME),
                intellijPlatform = intelliJPlatformConfiguration,
                javaToolchainSpec = project.the<JavaPluginExtension>().toolchain,
                javaToolchainService = project.serviceOf<JavaToolchainService>(),
            )

            runtimeDirectory.convention(layout.dir(provider {
                runtimeResolver.resolve().toFile()
            }))
            runtimeExecutable.convention(layout.file(provider {
                runtimeResolver.resolveExecutable().toFile()
            }))
            runtimeArch.set(providers.of(ExecutableArchValueSource::class) {
                parameters.executable.set(runtimeExecutable)
            })

            if (this is TestIdeTask) {
                executable(runtimeExecutable)
            }
        }

        /**
         * The [PluginVerifierAware] resolves and provides the IntelliJ Plugin Verifier for the further usage.
         */
        if (this is PluginVerifierAware) {
            // TODO: test if no PV dependency is added to the project
            val pluginVerifierResolver = IntelliJPluginVerifierResolver(
                intellijPluginVerifier = configurations.getByName(Configurations.INTELLIJ_PLUGIN_VERIFIER),
                localPath = extension.verifyPlugin.cliPath,
            )

            pluginVerifierExecutable.convention(layout.file(provider {
                pluginVerifierResolver.resolve().toFile()
            }))
        }

        /**
         * The [SigningAware] resolves and provides the Marketplace ZIP Signer for the further usage.
         */
        if (this is SigningAware) {
            // TODO: test if no ZIP Signer dependency is added to the project
            val marketplaceZipSignerResolver = MarketplaceZipSignerResolver(
                marketplaceZipSigner = configurations.getByName(Configurations.MARKETPLACE_ZIP_SIGNER),
                localPath = extension.signing.cliPath,
            )

            zipSignerExecutable.convention(layout.file(provider {
                marketplaceZipSignerResolver.resolve().toFile()
            }))
        }

        /**
         * The [RunnableIdeAware] is more complex one than [RuntimeAware] as it preconfigures also the [JavaForkOptions]-based tasks
         * by setting JVM Arguments providers and classpath.
         */
        if (this is RunnableIdeAware) {
            enableAssertions = true

            jvmArgumentProviders.add(
                IntelliJPlatformArgumentProvider(
                    intellijPlatformConfiguration = intelliJPlatformConfiguration,
                    coroutinesJavaAgentFile = coroutinesJavaAgentFile,
                    pluginXml = pluginXml,
                    runtimeArchProvider = runtimeArch,
                    options = this,
                )
            )
            jvmArgumentProviders.add(
                SandboxArgumentProvider(
                    sandboxConfigDirectory,
                    sandboxPluginsDirectory,
                    sandboxSystemDirectory,
                    sandboxLogDirectory,
                )
            )

//                outputs.dir(sandboxSystemDirectory)
//                    .withPropertyName("System directory")
//                inputs.dir(sandboxConfigDirectory)
//                    .withPropertyName("Config Directory")
//                    .withPathSensitivity(PathSensitivity.RELATIVE)
//                inputs.files(sandboxPluginsDirectory)
//                    .withPropertyName("Plugins directory")
//                    .withPathSensitivity(PathSensitivity.RELATIVE)
//                    .withNormalizer(ClasspathNormalizer::class)

            systemProperty("java.system.class.loader", "com.intellij.util.lang.PathClassLoader")

            if (this is JavaExecSpec) {
                mainClass.set("com.intellij.idea.Main")

                classpath += files(
                    provider {
                        productInfo
                            .launchFor(runtimeArch.get())
                            .bootClassPathJarNames
                            .map { platformPath.resolve("lib/$it") }
                    }
                )

                classpath += files(
                    runtimeDirectory.map { it.file("lib/tools") }
                )
            }
        }
    }

    tasks.withType<T>(configuration)
}

/**
 * Creates a specific sandbox directory using the [suffixProvider] and [name] within the [sandboxContainer] container directory.
 *
 * @param sandboxContainer The sandbox container directory.
 * @param suffixProvider The suffix for the sandbox directory.
 * @param name The name for the sandbox directory.
 *
 * @see Sandbox
 * @see SandboxAware
 */
internal fun DirectoryProperty.configureSandbox(sandboxContainer: DirectoryProperty, suffixProvider: Provider<String>, name: String) {
    convention(sandboxContainer.zip(suffixProvider) { container, suffix ->
        container.dir(name + suffix).apply { asPath.createDirectories() }
    })
}

/**
 * An interface to unify how IntelliJ Platform Gradle Plugin tasks are registered.
 * Every task, when registered, can rely on resources resolved with the [project] instance.
 * If the current task depends on another task, make sure it is registered latter.
 * The [register] method should most likely be combined with [Project.registerTask].
 */
internal interface Registrable {

    fun register(project: Project)
}