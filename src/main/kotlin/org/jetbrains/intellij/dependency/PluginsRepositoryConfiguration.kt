// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.dependency

import org.gradle.api.Action
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.jetbrains.intellij.IntelliJPluginConstants.DEFAULT_INTELLIJ_PLUGINS_REPOSITORY

abstract class PluginsRepositoryConfiguration {

    private val pluginsRepositories = mutableListOf<PluginsRepository>()

    /**
     * Use default marketplace repository
     */
    fun marketplace() {
        pluginsRepositories.add(MavenRepositoryPlugin(DEFAULT_INTELLIJ_PLUGINS_REPOSITORY))
    }

    /**
     * Use a Maven repository with plugin artifacts
     */
    fun maven(url: String) {
        pluginsRepositories.add(MavenRepositoryPlugin(url))
    }

    /**
     * Use a Maven repository by action
     */
    fun maven(action: Action<in MavenArtifactRepository>) {
        pluginsRepositories.add(MavenRepositoryPluginByAction(action))
    }

    /**
     * Use custom plugin repository. The URL should point to the `plugins.xml` or `updatePlugins.xml` file.
     */
    fun custom(url: String) {
        pluginsRepositories.add(CustomPluginsRepository(url))
    }

    fun getRepositories() = pluginsRepositories.toList()
}
