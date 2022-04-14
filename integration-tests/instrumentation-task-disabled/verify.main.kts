#!/usr/bin/env -S kotlin -J-ea

@file:Import("../verify.utils.kts")

logs matchesRegex ":instrumentation-task-disabled:buildPlugin .*? completed."
logs containsText "> Task :instrumentation-task-disabled:instrumentCode SKIPPED"

buildDirectory containsFile "libs/code-instrumentation.jar"

buildDirectory.resolve("libs/code-instrumentation.jar").run {

    this containsFileInArchive "META-INF/MANIFEST.MF"

    this containsFileInArchive "META-INF/plugin.xml"
    this readEntry "META-INF/plugin.xml" containsText "<action id=\"ExampleAction\" class=\"ExampleAction\" text=\"Example Action\">"

    this containsFileInArchive "ExampleAction.class"
}
