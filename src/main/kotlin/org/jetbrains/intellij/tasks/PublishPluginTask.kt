// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.tasks

import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.base.plugin.PluginProblem
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.intellij.asPath
import org.jetbrains.intellij.info
import org.jetbrains.intellij.logCategory
import org.jetbrains.intellij.pluginRepository.PluginRepositoryFactory
import org.jetbrains.intellij.pluginRepository.model.StringPluginId
import org.jetbrains.intellij.utils.ToolboxEnterprisePluginRepositoryService

@UntrackedTask(because = "Output is stored remotely")
abstract class PublishPluginTask : DefaultTask() {

    /**
     * Jar or Zip file of plugin to upload.
     *
     * Default value: output of the `buildPlugin` task
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributionFile: RegularFileProperty

    /**
     * URL host of a plugin repository.
     *
     * Default value: `https://plugins.jetbrains.com`
     */
    @get:Input
    @get:Optional
    abstract val host: Property<String>

    /**
     * Required.
     * Authentication token.
     */
    @get:Input
    @get:Optional
    abstract val token: Property<String>

    /**
     * List of channel names to upload plugin to.
     *
     * Default value: `["default"]`
     */
    @get:Input
    @get:Optional
    abstract val channels: ListProperty<String>

    /**
     * Specifies if the Toolbox Enterprise plugin repository service should be used.
     *
     * Default value: `false`
     */
    @get:Input
    @get:Optional
    abstract val toolboxEnterprise: Property<Boolean>

    private val context = logCategory()

    @TaskAction
    fun publishPlugin() {
        validateInput()

        val path = distributionFile.get().asPath
        when (val creationResult = IdePluginManager.createManager().createPlugin(path)) {
            is PluginCreationSuccess -> {
                if (creationResult.unacceptableWarnings.isNotEmpty()) {
                    val problems = creationResult.unacceptableWarnings.joinToString()
                    throw TaskExecutionException(this, GradleException("Cannot upload plugin: $problems"))
                }
                val pluginId = creationResult.plugin.pluginId
                channels.get().forEach { channel ->
                    info(context, "Uploading plugin '$pluginId' from '$path' to '${host.get()}', channel: '$channel'")
                    try {
                        val repositoryClient = when (toolboxEnterprise.get()) {
                            true -> PluginRepositoryFactory.createWithImplementationClass(
                                host.get(),
                                token.get(),
                                "Automation",
                                ToolboxEnterprisePluginRepositoryService::class.java,
                            )

                            false -> PluginRepositoryFactory.create(host.get(), token.get())
                        }
                        repositoryClient.uploader.upload(pluginId as StringPluginId, path.toFile(), channel.takeIf { it != "default" }, null)
                        info(context, "Uploaded successfully")
                    } catch (exception: Exception) {
                        throw TaskExecutionException(this, GradleException("Failed to upload plugin: ${exception.message}", exception))
                    }
                }
            }

            is PluginCreationFail -> {
                val problems = creationResult.errorsAndWarnings.filter { it.level == PluginProblem.Level.ERROR }.joinToString()
                throw TaskExecutionException(this, GradleException("Cannot upload plugin: $problems"))
            }

            else -> {
                throw TaskExecutionException(this, GradleException("Cannot upload plugin: $creationResult"))
            }
        }
    }

    private fun validateInput() {
        if (token.orNull.isNullOrEmpty()) {
            throw TaskExecutionException(this, GradleException("token property must be specified for plugin publishing"))
        }
    }
}
