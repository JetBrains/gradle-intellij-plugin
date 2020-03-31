package org.intellij.examples.simple.plugin

import com.jetbrains.test.RemoteRobot

fun uiTest(url: String = "http://127.0.0.1:8082", test: RemoteRobot.() -> Unit) {
    RemoteRobot(url).apply(test)
}