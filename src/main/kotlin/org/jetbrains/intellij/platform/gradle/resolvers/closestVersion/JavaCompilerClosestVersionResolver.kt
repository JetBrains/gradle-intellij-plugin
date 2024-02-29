// Copyright 2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.platform.gradle.resolvers.closestVersion

import org.jetbrains.intellij.platform.gradle.Constants.Locations
import org.jetbrains.intellij.platform.gradle.model.ProductInfo
import org.jetbrains.intellij.platform.gradle.utils.toVersion

class JavaCompilerClosestVersionResolver(private val productInfo: ProductInfo) : ClosestVersionResolver(
    subject = "Java Compiler",
    url = "${Locations.INTELLIJ_REPOSITORY}/releases/com/jetbrains/intellij/java/java-compiler-ant-tasks/maven-metadata.xml",
) {

    override fun resolve() = inMaven(productInfo.buildNumber.toVersion())
}