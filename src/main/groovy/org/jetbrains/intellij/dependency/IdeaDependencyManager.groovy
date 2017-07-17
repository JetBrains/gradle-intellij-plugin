package org.jetbrains.intellij.dependency

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolveException
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.ivy.internal.artifact.DefaultIvyArtifact
import org.gradle.api.publish.ivy.internal.publication.DefaultIvyConfiguration
import org.gradle.api.publish.ivy.internal.publication.DefaultIvyPublicationIdentity
import org.gradle.api.publish.ivy.internal.publisher.IvyDescriptorFileGenerator
import org.gradle.internal.os.OperatingSystem
import org.gradle.tooling.BuildException
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.intellij.IntelliJPluginExtension
import org.jetbrains.intellij.Utils

import static org.jetbrains.intellij.IntelliJPlugin.LOG

class IdeaDependencyManager {
    private final String repoUrl

    IdeaDependencyManager(@NotNull String repoUrl) {
        this.repoUrl = repoUrl
    }

    @NotNull
    IdeaDependency resolveRemote(@NotNull Project project, @NotNull String version, @NotNull String type, boolean sources) {
        LOG.debug("Adding IntelliJ IDEA repository")
        def releaseType = version.contains('SNAPSHOT') ? 'snapshots' : 'releases'
        project.repositories.maven { it.url = "${repoUrl}/$releaseType" }

        LOG.debug("Adding IntelliJ IDEA dependency")
        def dependencyGroup = 'com.jetbrains.intellij.idea'
        def dependencyName = 'ideaIC'
        if (type == 'IU') {
            dependencyName = 'ideaIU'
        } else if (type == 'RD') {
            dependencyGroup = 'com.jetbrains.intellij.rider'
            dependencyName = "riderRD"
        } else if (type == 'RS') {
            dependencyGroup = 'com.jetbrains.intellij.rider'
            dependencyName = "riderRS"
            LOG.warn("'RS' type is deprecated and will be removed in 0.3.0. Use 'RD' type instead")
        }
        def dependency = project.dependencies.create("$dependencyGroup:$dependencyName:$version")
        def configuration = project.configurations.detachedConfiguration(dependency)

        def classesDirectory = extractClassesFromRemoteDependency(project, configuration, type)
        def buildNumber = Utils.ideaBuildNumber(classesDirectory)
        def sourcesDirectory = sources ? resolveSources(project, version) : null
        return createDependency(dependencyName, type, version, buildNumber, classesDirectory, sourcesDirectory, project)
    }

    @NotNull
    IdeaDependency resolveLocal(@NotNull Project project, @NotNull String localPath) {
        LOG.debug("Adding local IDE dependency")
        def ideaDir = Utils.ideaDir(localPath)
        if (!ideaDir.exists() || !ideaDir.isDirectory()) {
            throw new BuildException("Specified localPath '$localPath' doesn't exist or is not a directory", null)
        }
        def buildNumber = Utils.ideaBuildNumber(ideaDir)
        return createDependency("ideaLocal", null, buildNumber, buildNumber, ideaDir, null, project)
    }

    static void register(@NotNull Project project, @NotNull IdeaDependency dependency, @NotNull String configuration) {
        def ivyFile = getOrCreateIvyXml(dependency)
        project.repositories.ivy { repo ->
            repo.url = dependency.classes
            repo.ivyPattern(ivyFile.absolutePath) // ivy xml
            repo.artifactPattern("$dependency.classes.path/[artifact].[ext]") // idea libs
            if (dependency.sources) {
                repo.artifactPattern("$dependency.sources.parent/[artifact]-$dependency.version-[classifier].[ext]")
            }
        }
        project.dependencies.add(configuration, [
            group: 'com.jetbrains', name: dependency.name, version: dependency.version, configuration: 'compile'
        ])
    }

    @NotNull
    private static IdeaDependency createDependency(String name, String type, String version,
                                                   String buildNumber,
                                                   File classesDirectory, File sourcesDirectory, Project project) {
        if (type == 'JPS') {
            return new JpsIdeaDependency(version, buildNumber, classesDirectory, sourcesDirectory,
                    !hasKotlinDependency(project))
        }
        return new IdeaDependency(name, version, buildNumber, classesDirectory, sourcesDirectory,
                !hasKotlinDependency(project))
    }

    @Nullable
    private static File resolveSources(@NotNull Project project, @NotNull String version) {
        LOG.info("Adding IntelliJ IDEA sources repository")
        try {
            def dependency = project.dependencies.create("com.jetbrains.intellij.idea:ideaIC:$version:sources@jar")
            def sourcesConfiguration = project.configurations.detachedConfiguration(dependency)
            def sourcesFiles = sourcesConfiguration.files
            if (sourcesFiles.size() == 1) {
                File sourcesDirectory = sourcesFiles.first()
                LOG.debug("IDEA sources jar: " + sourcesDirectory.path)
                return sourcesDirectory
            } else {
                LOG.warn("Cannot attach IDEA sources. Found files: " + sourcesFiles)
            }
        } catch (ResolveException e) {
            LOG.warn("Cannot resolve IDEA sources dependency", e)
        }
        return null
    }

    @NotNull
    private static File extractClassesFromRemoteDependency(
            @NotNull Project project, @NotNull Configuration configuration, @NotNull String type) {
        File zipFile = configuration.singleFile
        LOG.debug("IDEA zip: " + zipFile.path)
        def directoryName = zipFile.name - ".zip"

        String cacheParentDirectoryPath = zipFile.parent
        def intellijExtension = project.extensions.findByType(IntelliJPluginExtension.class)
        if (intellijExtension && intellijExtension.ideaDependencyCachePath) {
            def customCacheParent = new File(intellijExtension.ideaDependencyCachePath)
            if (customCacheParent.exists()) {
                cacheParentDirectoryPath = customCacheParent.absolutePath
            }
        } else if (type == 'RS' || type == 'RD') {
            cacheParentDirectoryPath = project.buildDir
        }
        def cacheDirectory = new File(cacheParentDirectoryPath, directoryName)
        unzipDependencyFile(cacheDirectory, project, zipFile, type)
        return cacheDirectory
    }

    private static void unzipDependencyFile(@NotNull File cacheDirectory, @NotNull Project project, @NotNull File zipFile, @NotNull String type) {
        def markerFile = new File(cacheDirectory, "markerFile")
        if (!markerFile.exists()) {
            if (cacheDirectory.exists()) cacheDirectory.deleteDir()
            cacheDirectory.mkdir()
            LOG.debug("Unzipping idea")
            project.copy {
                it.from(project.zipTree(zipFile))
                it.into(cacheDirectory)
            }
            resetExecutablePermissions(cacheDirectory, project, type)
            markerFile.createNewFile()
            LOG.debug("Unzipped")
        }
    }

    private static void resetExecutablePermissions(@NotNull File cacheDirectory, @NotNull Project project, @NotNull String type) {
        if (type == 'RS' || type == 'RD') {
            LOG.debug("Resetting executable permissions")
            def operatingSystem = OperatingSystem.current()
            if (!operatingSystem.isWindows()) {
                setExecutable(cacheDirectory, "lib/ReSharperHost/dupfinder.sh")
                setExecutable(cacheDirectory, "lib/ReSharperHost/inspectcode.sh")
                setExecutable(cacheDirectory, "lib/ReSharperHost/JetBrains.ReSharper.Host.sh")
                setExecutable(cacheDirectory, "lib/ReSharperHost/runtime.sh")
                setExecutable(cacheDirectory, "lib/ReSharperHost/macos-x64/mono/bin/mono-sgen")
                setExecutable(cacheDirectory, "lib/ReSharperHost/macos-x64/mono/bin/mono-sgen-gdb.py")
                setExecutable(cacheDirectory, "lib/ReSharperHost/linux-x64/mono/bin/mono-sgen")
                setExecutable(cacheDirectory, "lib/ReSharperHost/linux-x64/mono/bin/mono-sgen-gdb.py")
            }
        }
    }

    static def setExecutable(File parent, String child) {
        new File(parent, child).setExecutable(true, true)
    }

    private static File getOrCreateIvyXml(@NotNull IdeaDependency dependency) {
        def ivyFile = new File(dependency.classes, "${dependency.fqn}.xml")
        if (!ivyFile.exists()) {
            def generator = new IvyDescriptorFileGenerator(new DefaultIvyPublicationIdentity("com.jetbrains", dependency.name, dependency.version))
            generator.addConfiguration(new DefaultIvyConfiguration("default"))
            generator.addConfiguration(new DefaultIvyConfiguration("compile"))
            generator.addConfiguration(new DefaultIvyConfiguration("sources"))
            dependency.jarFiles.each {
                generator.addArtifact(Utils.createJarDependency(it, "compile", dependency.classes))
            }
            if (dependency.sources) {
                def artifact = new DefaultIvyArtifact(dependency.sources, 'ideaIC', "jar", "sources", "sources")
                artifact.conf = "sources"
                generator.addArtifact(artifact)
            }
            generator.writeTo(ivyFile)
        }
        return ivyFile
    }

    private static def hasKotlinDependency(@NotNull Project project) {
        def configurations = project.configurations
        def closure = {
            if ("org.jetbrains.kotlin" == it.group) {
                return "kotlin-runtime" == it.name || it.name.startsWith('kotlin-stdlib') || "kotlin-reflect" == it.name
            }
            return false
        }
        return configurations.getByName(JavaPlugin.RUNTIME_CONFIGURATION_NAME).getAllDependencies().find(closure) ||
                configurations.getByName(JavaPlugin.COMPILE_CONFIGURATION_NAME).getAllDependencies().find(closure)
    }
}


