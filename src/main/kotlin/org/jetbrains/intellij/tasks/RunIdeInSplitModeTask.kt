// Copyright 2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.tasks

import org.gradle.api.tasks.UntrackedTask
import org.jetbrains.intellij.IntelliJPluginConstants.PLUGIN_GROUP_NAME

/**
 * Runs the IDE instance in split mode, with backend and frontend parts running in separate processes.
 * The developed plugin is installed in the backend part.
 * 
 * @see [RunIdeTask]
 */
@UntrackedTask(because = "Should always run the IDE")
abstract class RunIdeInSplitModeTask : RunIdeBase() {
    init {
        group = PLUGIN_GROUP_NAME 
        description = 
            "Runs the backend and the frontend parts of the IDE in separate process with the developed plugin " +
            "installed in the backend part"
    }
}