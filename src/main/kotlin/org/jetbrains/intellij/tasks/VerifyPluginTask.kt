// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.tasks

import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.base.plugin.PluginProblem
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.intellij.asPath
import org.jetbrains.intellij.error
import org.jetbrains.intellij.logCategory
import org.jetbrains.intellij.warn

@CacheableTask
abstract class VerifyPluginTask : DefaultTask() {

    /**
     * Specifies whether the build should fail when the verifications performed by this task fail.
     *
     * Default value: `false`
     */
    @get:Input
    abstract val ignoreFailures: Property<Boolean>

    /**
     * Specifies whether the build should fail when the verifications performed by this task emit unacceptable warnings.
     *
     * Default value: `false`
     */
    @get:Input
    abstract val ignoreUnacceptableWarnings: Property<Boolean>

    /**
     * Specifies whether the build should fail when the verifications performed by this task emit warnings.
     *
     * Default value: `true`
     */
    @get:Input
    abstract val ignoreWarnings: Property<Boolean>

    /**
     * The location of the built plugin file which will be used for verification.
     *
     * Default value: `${prepareSandboxTask.destinationDir}/${prepareSandboxTask.pluginName}``
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginDir: DirectoryProperty

    private val context = logCategory()

    @TaskAction
    fun verifyPlugin() {
        val creationResult = pluginDir.get().let { IdePluginManager.createManager().createPlugin(it.asPath) }
        when (creationResult) {
            is PluginCreationSuccess -> {
                creationResult.warnings.forEach {
                    warn(context, it.message)
                }
                creationResult.unacceptableWarnings.forEach {
                    error(context, it.message)
                }
            }

            is PluginCreationFail -> creationResult.errorsAndWarnings.forEach {
                if (it.level == PluginProblem.Level.ERROR) {
                    error(context, it.message)
                } else {
                    warn(context, it.message)
                }
            }

            else -> error(context, creationResult.toString())
        }
        val failBuild = creationResult !is PluginCreationSuccess
                || !ignoreUnacceptableWarnings.get() && creationResult.unacceptableWarnings.isNotEmpty()
                || !ignoreWarnings.get() && creationResult.warnings.isNotEmpty()
        if (failBuild && !ignoreFailures.get()) {
            throw GradleException("Plugin verification failed.")
        }
    }
}
