package org.jetbrains.intellij

import org.apache.tools.ant.BuildException
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.annotations.NotNull

class IntelliJInstrumentCodeAction implements Action<Task> {
    private static final String FILTER_ANNOTATION_REGEXP_CLASS = 'com.intellij.ant.ClassFilterAnnotationRegexp'
    private static final LOADER_REF = "java2.loader"
    private final boolean myTestInstrumentation

    IntelliJInstrumentCodeAction(boolean testInstrumentation) {
        myTestInstrumentation = testInstrumentation
    }

    @Override
    void execute(Task task) {
        def extension = task.project.extensions.getByType(IntelliJPluginExtension)
        def classpath = task.project.files(
                "$extension.ideaDirectory/lib/javac2.jar",
                "$extension.ideaDirectory/lib/jdom.jar",
                "$extension.ideaDirectory/lib/asm-all.jar",
                "$extension.ideaDirectory/lib/jgoodies-forms.jar")
        task.project.ant.taskdef(name: 'instrumentIdeaExtensions',
                classpath: classpath.asPath,
                loaderref: LOADER_REF,
                classname: 'com.intellij.ant.InstrumentIdeaExtensions')

        IntelliJPlugin.LOG.info("Compiling forms and instrumenting code with nullability preconditions")
        //noinspection GroovyAssignabilityCheck
        task.taskDependencies.getDependencies(task).findAll { it instanceof AbstractCompile }.each {
            AbstractCompile compileTask ->
                boolean instrumentNotNull = prepareNotNullInstrumenting(compileTask, classpath)
                def sourceSet = myTestInstrumentation ?
                        Utils.testSourceSet(compileTask.project).compiledBy(compileTask) :
                        Utils.mainSourceSet(compileTask.project).compiledBy(compileTask)
                def srcDirs = existingDirs(sourceSet.allSource)
                srcDirs.removeAll(existingDirs(sourceSet.resources))
                if (!srcDirs.empty) {
                    instrumentCode(compileTask, srcDirs, instrumentNotNull)
                }
        }
    }

    private static HashSet<File> existingDirs(SourceDirectorySet sourceDirectorySet) {
        return sourceDirectorySet.srcDirs.findAll { it.exists() }
    }

    private static boolean prepareNotNullInstrumenting(@NotNull Task compileTask,
                                                       @NotNull ConfigurableFileCollection classpath) {
        try {
            compileTask.project.ant.typedef(name: 'skip', classpath: classpath.asPath, loaderref: LOADER_REF,
                    classname: FILTER_ANNOTATION_REGEXP_CLASS)
        } catch (BuildException e) {
            def cause = e.getCause()
            if (cause instanceof ClassNotFoundException && FILTER_ANNOTATION_REGEXP_CLASS.equals(cause.getMessage())) {
                IntelliJPlugin.LOG.info("Old version of Javac2 is used, " +
                        "instrumenting code with nullability will be skipped. Use IDEA >14 SDK (139.*) to fix this")
                return false
            } else {
                throw e
            }
        }
        return true
    }

    private static void instrumentCode(@NotNull AbstractCompile compileTask,
                                       @NotNull Collection<File> srcDirs,
                                       boolean instrumentNotNull) {
        def headlessOldValue = System.setProperty('java.awt.headless', 'true')
        compileTask.project.ant.instrumentIdeaExtensions(srcdir: compileTask.project.files(srcDirs).asPath,
                destdir: compileTask.destinationDir, classpath: compileTask.classpath.asPath,
                includeantruntime: false, instrumentNotNull: instrumentNotNull) {
            if (instrumentNotNull) {
                compileTask.project.ant.skip(pattern: 'kotlin/jvm/internal/.*')
            }
        }
        if (headlessOldValue != null) {
            System.setProperty('java.awt.headless', headlessOldValue)
        } else {
            System.clearProperty('java.awt.headless')
        }
    }

}
