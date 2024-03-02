// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)
fun Jar.patchManifest() = manifest { attributes("Version" to project.version) }

plugins {
    `jvm-test-suite`
    `java-test-fixtures`
    `kotlin-dsl`
    `maven-publish`
    kotlin("plugin.serialization") version embeddedKotlinVersion
    alias(libs.plugins.pluginPublish)
    alias(libs.plugins.changelog)
    alias(libs.plugins.dokka)
}

val isSnapshot = properties("snapshot").get().toBoolean()
version = when (isSnapshot) {
    true -> properties("snapshotVersion").map { "$it-SNAPSHOT" }
    false -> properties("version")
}.get()
group = properties("group").get()
description = properties("description").get()

repositories {
    mavenCentral()
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

val additionalPluginClasspath: Configuration by configurations.creating

dependencies {
    implementation(libs.annotations)
    implementation(libs.intellijStructureBase) {
        exclude("org.jetbrains.kotlin")
    }
    implementation(libs.intellijStructureIntellij) {
        exclude("org.jetbrains.kotlin")
    }
    implementation(libs.intellijPluginRepositoryRestClient) {
        exclude("org.jetbrains.kotlin")
        exclude("org.slf4j")
    }

    implementation(libs.jacksonDatabind)
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.jaxbApi)

    compileOnly(embeddedKotlin("gradle-plugin"))
    additionalPluginClasspath(embeddedKotlin("gradle-plugin"))

    api(libs.retrofit)

    testImplementation(gradleTestKit())
    testImplementation(embeddedKotlin("test"))
    testImplementation(embeddedKotlin("test-junit"))

    testFixturesImplementation(gradleTestKit())
    testFixturesImplementation(embeddedKotlin("test"))
    testFixturesImplementation(embeddedKotlin("test-junit"))
    testFixturesImplementation(libs.annotations)
}

configurations.configureEach {
    if (isCanBeConsumed) {
        attributes {
            attribute(
                GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
                objects.named<GradlePluginApiVersion>(GradleVersion.version("8.1").version)
            )
        }
    }
}

kotlin {
    jvmToolchain(11)
}

tasks {
    wrapper {
        gradleVersion = properties("gradleVersion").get()
        distributionUrl = "https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-$gradleVersion-all.zip"
    }

    pluginUnderTestMetadata {
        pluginClasspath.from(additionalPluginClasspath)
    }

    test {
        val testGradleHome = properties("testGradleUserHome")
            .map { File(it) }
            .getOrElse(
                layout.buildDirectory.asFile.map { it.resolve("testGradleHome") }.get()
            )

        systemProperties["test.gradle.home"] = testGradleHome
        systemProperties["test.gradle.scan"] = project.gradle.startParameter.isBuildScan
        systemProperties["test.kotlin.version"] = properties("kotlinVersion").get()
        systemProperties["test.gradle.default"] = properties("gradleVersion").get()
        systemProperties["test.gradle.version"] = properties("testGradleVersion").get()
        systemProperties["test.gradle.arguments"] = properties("testGradleArguments").get()
        systemProperties["test.intellijPlatform.type"] = properties("testIntellijPlatformType").get()
        systemProperties["test.intellijPlatform.version"] = properties("testIntellijPlatformVersion").get()
        systemProperties["test.ci"] = environment("CI").orElse("false")
        systemProperties["test.markdownPlugin.version"] = properties("testMarkdownPluginVersion").get()
        systemProperties["plugins.repository"] = properties("pluginsRepository").get()
        outputs.dir(testGradleHome)

// Verbose tests output used for debugging tasks:
//        testLogging {
//            outputs.upToDateWhen { false }
//            showStandardStreams = true
//        }
    }

    jar {
        patchManifest()
    }

    validatePlugins {
        enableStricterValidation.set(true)
    }

//    @Suppress("UnstableApiUsage")
//    check {
//        dependsOn(testing.suites.getByName("integrationTest")) // TODO: run after `test`?
//    }
}

@Suppress("UnstableApiUsage")
testing {
    suites {
        fun JvmComponentDependencies.embeddedKotlin(module: String) =
            project.dependencies.embeddedKotlin(module) as String

//        named<JvmTestSuite>("test") {
//            dependencies {
//                implementation(project())
//                implementation(testFixtures(project()))
//            }
//        }

        register<JvmTestSuite>("integrationTest") {
            useJUnit()
            testType.set(TestSuiteType.INTEGRATION_TEST)

            dependencies {
                implementation(project())
                implementation(gradleTestKit())
                implementation(testFixtures(project()))
                implementation(embeddedKotlin("test"))
                implementation(embeddedKotlin("test-junit"))
            }

            targets {
                all {
                    testTask.configure {
                        val testGradleHome = properties("testGradleUserHome")
                            .map { File(it) }
                            .getOrElse(
                                layout.buildDirectory.asFile
                                    .map { it.resolve("testGradleHome") }
                                    .get()
                            )

                        systemProperties["test.gradle.home"] = testGradleHome
                        systemProperties["test.gradle.scan"] = project.gradle.startParameter.isBuildScan
                        systemProperties["test.kotlin.version"] = properties("kotlinVersion").get()
                        systemProperties["test.gradle.default"] = properties("gradleVersion").get()
                        systemProperties["test.gradle.version"] = properties("testGradleVersion").get()
                        systemProperties["test.gradle.arguments"] = properties("testGradleArguments").get()
                        systemProperties["test.intellijPlatform.type"] = properties("testIntellijPlatformType").get()
                        systemProperties["test.intellijPlatform.version"] = properties("testIntellijPlatformVersion").get()
                        systemProperties["test.ci"] = environment("CI").orElse("false")
                        systemProperties["test.markdownPlugin.version"] = properties("testMarkdownPluginVersion").get()
                        systemProperties["plugins.repository"] = properties("pluginsRepository").get()
                    }
                }
            }
        }
    }
}

val dokkaHtml by tasks.existing(DokkaTask::class)
val javadocJar by tasks.registering(Jar::class) {
    dependsOn(dokkaHtml)
    archiveClassifier.set("javadoc")
    from(dokkaHtml.map { it.outputDirectory })
    patchManifest()
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
    patchManifest()
}

artifacts {
    archives(javadocJar)
    archives(sourcesJar)
}

@Suppress("UnstableApiUsage")
gradlePlugin {
    website.set(properties("website"))
    vcsUrl.set(properties("vcsUrl"))

    plugins.create("intellijPlatform") {
        id = "org.jetbrains.intellij.platform"
        displayName = "IntelliJ Platform Gradle Plugin"
        implementationClass = "org.jetbrains.intellij.platform.gradle.plugins.project.IntelliJPlatformPlugin"
        description = project.description
        tags.set(properties("tags").map { it.split(',') })
    }

    plugins.create("intellijPlatformBase") {
        id = "org.jetbrains.intellij.platform.base"
        displayName = "IntelliJ Platform Gradle Plugin (base)"
        implementationClass = "org.jetbrains.intellij.platform.gradle.plugins.project.IntelliJPlatformBasePlugin"
        description = project.description
        tags.set(properties("tags").map { it.split(',') })
    }

    plugins.create("intellijPlatformTasks") {
        id = "org.jetbrains.intellij.platform.tasks"
        displayName = "IntelliJ Platform Gradle Plugin (tasks)"
        implementationClass = "org.jetbrains.intellij.platform.gradle.plugins.project.IntelliJPlatformTasksPlugin"
        description = project.description
        tags.set(properties("tags").map { it.split(',') })
    }

    plugins.create("intellijPlatformSettings") {
        id = "org.jetbrains.intellij.platform.settings"
        displayName = "IntelliJ Platform Gradle Plugin (settings)"
        implementationClass = "org.jetbrains.intellij.platform.gradle.plugins.settings.IntelliJPlatformSettingsPlugin"
        description = project.description
        tags.set(properties("tags").map { it.split(',') })
    }

    plugins.create("intellijPlatformMigration") {
        id = "org.jetbrains.intellij.platform.migration"
        displayName = "IntelliJ Platform Gradle Plugin (migration helper)"
        implementationClass = "org.jetbrains.intellij.platform.gradle.plugins.project.IntelliJPlatformMigrationPlugin"
        description = project.description
        tags.set(properties("tags").map { it.split(',') })
    }

    testSourceSets.add(sourceSets["integrationTest"])
}

publishing {
    repositories {
        maven {
            name = "snapshot"
            url = uri(properties("snapshotUrl").get())
            credentials {
                username = properties("ossrhUsername").get()
                password = properties("ossrhPassword").get()
            }
        }
    }
    publications {
        create<MavenPublication>("pluginMaven") {
            groupId = properties("group").get()
            artifactId = properties("artifactId").get()
            version = version.toString()
        }

//        create<MavenPublication>("snapshot") {
//            groupId = properties("group").get()
//            artifactId = properties("artifactId").get()
//            version = version.toString()
//
//            from(components["java"])
//
//            pom {
//                name.set("IntelliJ Platform Gradle Plugin")
//                description.set(project.description)
//                url.set(properties("website"))
//
//                packaging = "jar"
//
//                scm {
//                    connection.set(properties("scmUrl"))
//                    developerConnection.set(properties("scmUrl"))
//                    url.set(properties("vcsUrl"))
//                }
//
//                licenses {
//                    license {
//                        name.set("The Apache License, Version 2.0")
//                        url.set("https://github.com/JetBrains/gradle-intellij-plugin/blob/master/LICENSE")
//                    }
//                }
//
//                developers {
//                    developer {
//                        id.set("zolotov")
//                        name.set("Alexander Zolotov")
//                        email.set("zolotov@jetbrains.com")
//                    }
//                    developer {
//                        id.set("hsz")
//                        name.set("Jakub Chrzanowski")
//                        email.set("jakub.chrzanowski@jetbrains.com")
//                    }
//                }
//            }
//        }
    }
}

changelog {
    unreleasedTerm.set("next")
    groups.empty()
    repositoryUrl.set("https://github.com/JetBrains/gradle-intellij-plugin")
}
