package org.jetbrains.intellij

object IntelliJPluginConstants {
    const val NAME = "Gradle IntelliJ Plugin"
    const val GROUP_NAME = "intellij"
    const val EXTENSION_NAME = "intellij"
    const val DEFAULT_SANDBOX = "idea-sandbox"
    const val PATCH_PLUGIN_XML_TASK_NAME = "patchPluginXml"
    const val PLUGIN_XML_DIR_NAME = "patchedPluginXmlFiles"
    const val PREPARE_SANDBOX_TASK_NAME = "prepareSandbox"
    const val PREPARE_TESTING_SANDBOX_TASK_NAME = "prepareTestingSandbox"
    const val PREPARE_UI_TESTING_SANDBOX_TASK_NAME = "prepareUiTestingSandbox"
    const val DOWNLOAD_ROBOT_SERVER_PLUGIN_TASK_NAME = "downloadRobotServerPlugin"
    const val RUN_PLUGIN_VERIFIER_TASK_NAME = "runPluginVerifier"
    const val VERIFY_PLUGIN_TASK_NAME = "verifyPlugin"
    const val RUN_IDE_TASK_NAME = "runIde"
    const val RUN_IDE_FOR_UI_TESTS_TASK_NAME = "runIdeForUiTests"
    const val BUILD_SEARCHABLE_OPTIONS_TASK_NAME = "buildSearchableOptions"
    const val SEARCHABLE_OPTIONS_DIR_NAME = "searchableOptions"
    const val JAR_SEARCHABLE_OPTIONS_TASK_NAME = "jarSearchableOptions"
    const val BUILD_PLUGIN_TASK_NAME = "buildPlugin"
    const val SIGN_PLUGIN_TASK_NAME = "signPlugin"
    const val PUBLISH_PLUGIN_TASK_NAME = "publishPlugin"
    const val LIST_PRODUCTS_RELEASES_TASK_NAME = "listProductsReleases"
    const val SETUP_DEPENDENCIES_TASK_NAME = "setupDependencies"

    const val IDEA_CONFIGURATION_NAME = "idea"
    const val IDEA_PLUGINS_CONFIGURATION_NAME = "ideaPlugins"
    const val INTELLIJ_DEFAULT_DEPENDENCIES_CONFIGURATION_NAME = "intellijDefaultDependencies"

    const val ANNOTATIONS_DEPENDENCY_VERSION = "22.0.0"
    const val DEFAULT_IDEA_VERSION = "LATEST-EAP-SNAPSHOT"

    const val MARKETPLACE_HOST = "https://plugins.jetbrains.com"
    const val IDEA_PRODUCTS_RELEASES_URL = "https://www.jetbrains.com/updates/updates.xml"
//    const val ANDROID_STUDIO_PRODUCTS_RELEASES_URL = "https://dl.google.com/android/studio/patches/updates.xml"
    const val CACHE_REDIRECTOR = "https://cache-redirector.jetbrains.com"
    const val INTELLIJ_DEPENDENCIES = "$CACHE_REDIRECTOR/intellij-dependencies"
    const val DEFAULT_INTELLIJ_REPOSITORY = "$CACHE_REDIRECTOR/www.jetbrains.com/intellij-repository"
    const val DEFAULT_INTELLIJ_PLUGINS_REPOSITORY = "$CACHE_REDIRECTOR/plugins.jetbrains.com/maven"
    const val DEFAULT_JBR_REPOSITORY = "$CACHE_REDIRECTOR/intellij-jbr"
    const val PLUGIN_VERIFIER_REPOSITORY =
        "$CACHE_REDIRECTOR/packages.jetbrains.team/maven/p/intellij-plugin-verifier/intellij-plugin-verifier"
    const val ANDROID_STUDIO_DOWNLOAD_URL = "https://redirector.gvt1.com/edgedl/android/studio/ide-zips"
    const val IDEA_DOWNLOAD_URL = "https://data.services.jetbrains.com/products/download"
    const val ZIP_SIGNER_LATEST_RELEASE_URL = "https://github.com/JetBrains/marketplace-zip-signer/releases/latest"
    const val ZIP_SIGNER_DOWNLOAD_URL = "https://github.com/JetBrains/marketplace-zip-signer/releases/download/%VERSION%/marketplace-zip-signer-cli.jar"

    const val PLUGIN_PATH = "plugin.path"
    const val VERSION_LATEST = "latest"
    const val ANDROID_STUDIO_TYPE = "AI"
}

