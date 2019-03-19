package org.jetbrains.intellij.tasks

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.gradle.util.CollectionUtils
import org.jetbrains.intellij.IntelliJPlugin
import org.jetbrains.intellij.Utils

class RunIdeTask extends RunIdeBase {
}

class BuildSearchableOptionsTask extends RunIdeBase {

    private static final List<String> TRAVERSE_UI_ARG = ["traverseUI"]

    BuildSearchableOptionsTask() {
        super()
        super.setArgs(TRAVERSE_UI_ARG)
    }

    @Override
    JavaExec setArgs(List<String> applicationArgs) {
        super.setArgs(TRAVERSE_UI_ARG + applicationArgs)
    }

    @Override
    JavaExec setArgs(Iterable<?> applicationArgs) {
        super.setArgs(TRAVERSE_UI_ARG + applicationArgs)
    }
}

abstract class RunIdeBase extends JavaExec {
    private static final def PREFIXES = [IU: null,
                                         IC: 'Idea',
                                         RM: 'Ruby',
                                         PY: 'Python',
                                         PC: 'PyCharmCore',
                                         PE: 'PyCharmEdu',
                                         PS: 'PhpStorm',
                                         WS: 'WebStorm',
                                         OC: 'AppCode',
                                         CL: 'CLion',
                                         DB: '0xDBE',
                                         AI: 'AndroidStudio',
                                         GO: 'GoLand',
                                         RD: 'Rider',
                                         RS: 'Rider']

    private List<Object> requiredPluginIds = []
    private Object ideaDirectory
    private Object configDirectory
    private Object systemDirectory
    private Object pluginsDirectory
    private Object jbrVersion

    List<String> getRequiredPluginIds() {
        CollectionUtils.stringize(requiredPluginIds.collect {
            it instanceof Closure ? (it as Closure).call() : it
        }.flatten())
    }

    void setRequiredPluginIds(Object... requiredPluginIds) {
        this.requiredPluginIds.clear()
        this.requiredPluginIds.addAll(requiredPluginIds as List)
    }

    void requiredPluginIds(Object... requiredPluginIds) {
        this.requiredPluginIds.addAll(requiredPluginIds as List)
    }

    @Input
    @Optional
    @Deprecated
    String getJbreVersion() {
        Utils.stringInput(jbrVersion)
    }

    @Deprecated
    void setJbreVersion(Object jbreVersion) {
        IntelliJPlugin.LOG.warn("jbreVersion is deprecated, use jbrVersion instead")
        this.jbrVersion = jbreVersion
    }

    @Deprecated
    void jbreVersion(Object jbreVersion) {
        IntelliJPlugin.LOG.warn("jbreVersion is deprecated, use jbrVersion instead")
        this.jbrVersion = jbreVersion
    }

    @Input
    @Optional
    String getJbrVersion() {
        Utils.stringInput(jbrVersion)
    }

    void setJbrVersion(Object jbrVersion) {
        this.jbrVersion = jbrVersion
    }

    void jbrVersion(Object jbrVersion) {
        this.jbrVersion = jbrVersion
    }

    @InputDirectory
    File getIdeaDirectory() {
        ideaDirectory != null ? project.file(ideaDirectory) : null
    }

    void setIdeaDirectory(Object ideaDirectory) {
        this.ideaDirectory = ideaDirectory
    }

    void ideaDirectory(Object ideaDirectory) {
        this.ideaDirectory = ideaDirectory
    }

    File getConfigDirectory() {
        configDirectory != null ? project.file(configDirectory) : null
    }

    void setConfigDirectory(Object configDirectory) {
        this.configDirectory = configDirectory
    }

    void configDirectory(Object configDirectory) {
        this.configDirectory = configDirectory
    }

    File getSystemDirectory() {
        systemDirectory != null ? project.file(systemDirectory) : null
    }

    void setSystemDirectory(Object systemDirectory) {
        this.systemDirectory = systemDirectory
    }

    void systemDirectory(Object systemDirectory) {
        this.systemDirectory = systemDirectory
    }

    File getPluginsDirectory() {
        pluginsDirectory != null ? project.file(pluginsDirectory) : null
    }

    void setPluginsDirectory(Object pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory
    }

    void pluginsDirectory(Object pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory
    }

    RunIdeBase() {
        setMain("com.intellij.idea.Main")
        enableAssertions = true
        outputs.upToDateWhen { false }
    }

    @Override
    void exec() {
        workingDir = project.file("${getIdeaDirectory()}/bin/")
        configureClasspath()
        configureSystemProperties()
        configureJvmArgs()
        executable(getExecutable())
        super.exec()
    }

    private void configureClasspath() {
        File ideaDirectory = getIdeaDirectory()
        def executable = getExecutable()
        def toolsJar = executable ? project.file(Utils.resolveToolsJar(executable)) : null
        toolsJar = toolsJar?.exists() ? toolsJar : Jvm.current().toolsJar
        if (toolsJar != null) {
            classpath += project.files(toolsJar)
        }
        classpath += project.files("$ideaDirectory/lib/idea_rt.jar",
                "$ideaDirectory/lib/idea.jar",
                "$ideaDirectory/lib/bootstrap.jar",
                "$ideaDirectory/lib/extensions.jar",
                "$ideaDirectory/lib/util.jar",
                "$ideaDirectory/lib/openapi.jar",
                "$ideaDirectory/lib/trove4j.jar",
                "$ideaDirectory/lib/jdom.jar",
                "$ideaDirectory/lib/log4j.jar")
    }

    def configureSystemProperties() {
        systemProperties(getSystemProperties())
        systemProperties(Utils.getIdeaSystemProperties(getConfigDirectory(), getSystemDirectory(), getPluginsDirectory(), getRequiredPluginIds()))
        def operatingSystem = OperatingSystem.current()
        def userDefinedSystemProperties = getSystemProperties()
        if (operatingSystem.isMacOsX()) {
            systemPropertyIfNotDefined("idea.smooth.progress", false, userDefinedSystemProperties)
            systemPropertyIfNotDefined("apple.laf.useScreenMenuBar", true, userDefinedSystemProperties)
            systemPropertyIfNotDefined("apple.awt.fileDialogForDirectories", true, userDefinedSystemProperties)
        } else if (operatingSystem.isUnix()) {
            systemPropertyIfNotDefined("sun.awt.disablegrab", true, userDefinedSystemProperties)
        }
        systemPropertyIfNotDefined("idea.classpath.index.enabled", false, userDefinedSystemProperties)
        systemPropertyIfNotDefined("idea.is.internal", true, userDefinedSystemProperties)

        if (!getSystemProperties().containsKey('idea.platform.prefix')) {
            def matcher = Utils.VERSION_PATTERN.matcher(Utils.ideaBuildNumber(getIdeaDirectory()))
            if (matcher.find()) {
                def abbreviation = matcher.group(1)
                def prefix = PREFIXES.get(abbreviation)
                if (prefix) {
                    systemProperty('idea.platform.prefix', prefix)

                    if (abbreviation == 'RD') {
                        // Allow debugging Rider's out of process ReSharper host
                        systemPropertyIfNotDefined('rider.debug.mono.debug', true, userDefinedSystemProperties)
                        systemPropertyIfNotDefined('rider.debug.mono.allowConnect', true, userDefinedSystemProperties)
                    }
                }
            }
        }
    }

    private void systemPropertyIfNotDefined(String name, Object value, Map<String, Object> userDefinedSystemProperties) {
        if (!userDefinedSystemProperties.containsKey(name)) {
            systemProperty(name, value)
        }
    }

    def configureJvmArgs() {
        jvmArgs = Utils.getIdeaJvmArgs(this, getJvmArgs(), getIdeaDirectory())
    }
}
