## Configuration

Plugin provides following options to configure target IntelliJ SDK and build archive

- `intellij.version` defines the version of IDEA distribution that should be used as a dependency. 
The option accepts build numbers, version numbers and two meta values `LATEST-EAP-SNAPSHOT`, `LATEST-TRUNK-SNAPSHOT`.<br/>
Value may have `IC-`, `IU-` or `JPS-` prefix in order to define IDEA distribution type. <br/><br/> 
**Default value**: `LATEST-EAP-SNAPSHOT`

- `intellij.localPath` defines path to locally installed IDEA distribution that should be used as a dependency. 
The option accepts path, e.g. `/Applications/IntelliJIDEA.app`<br/>
`intellij.version` and `intellij.localPath` should not be specified at the same time.<br/><br/> 
**Default value**: `null`

- `intellij.type` defines the type of IDEA distribution: `IC` for community version, `IU` for ultimate, `JPS` for jps-only dependencies and `RD` for Rider.<br/><br/>
**Default value**: `IC`

- `intellij.plugins` defines the list of bundled IDEA plugins and plugins from [idea repository](https://plugins.jetbrains.com/) 
that should be used as dependencies in format `org.plugin.id:version[@channel]`.
E.g. `plugins = ['org.intellij.plugins.markdown:8.5.0.20160208', 'org.intellij.scala:2017.2.638@nightly']`.
For bundled plugins a plugin's directory should be used as a name and a version should be omitted, e.g. `plugins = ['android', 'Groovy']`.
You can can also specify a Gradle subproject as a plugin dependency, e.g. `plugins = [project(':plugin-subproject')]`.<br/><br/>
**Default value:** `<empty>`

- `intellij.pluginName` is used for naming target zip-archive and defines the name of plugin artifact. 
of bundled IDEA plugins that should be used as dependencies.<br/><br/>
**Default value:** `$project.name`

- `intellij.sandboxDirectory` defines path of sandbox directory that is used for running IDEA with developing plugin.<br/><br/>
**Default value**: `$project.buildDir/idea-sandbox`

- `intellij.instrumentCode` defines whether plugin should instrument java classes with nullability assertions.
Also it might be required for compiling forms created by IntelliJ GUI designer.<br/><br/>
Instrumentation code cannot be performed while using Rider distributions `RD`.<br/><br/>
**Default value**: `true`

- `intellij.updateSinceUntilBuild` defines whether plugin should patch `plugin.xml` with since and until build values, 
if true then `IntelliJIDEABuildNumber` will be used as a `since` value and `IntelliJIDEABranch.*` will be used as an until value.<br/><br/>
**Default value**: `true`

- `intellij.sameSinceUntilBuild` defines whether plugin should patch `plugin.xml` with "open" until build. 
if true then the same `IntelliJIDEABuildNumber` will be used as a `since` value and as an until value, 
which is useful for building plugins against EAP IDEA builds.<br/><br/>
**Default value**: `false`

- `intellij.downloadSources` defines whether plugin should download IntelliJ sources while 
initializing Gradle build. Since sources are no needed while testing on CI, you can set
it to `false` for particular environment.<br/><br/>
**Default value**: `true` unless the `CI` environment variable is set

- `intellij.systemProperties` defines the map of system properties which will be passed to IDEA instance on
executing `runIdea` task and tests.<br/>
Also you can use `intellij.systemProperty(name, value)` method in order to set single system property.<br/><br/>
**Deprecated**. Use `systemProperties` methods of a particular tasks like `runIde` or `test`.<br/><br/>
**Default value**: `[]`

- `intellij.alternativeIdePath` – absolute path to the locally installed JetBrains IDE.
It makes sense to use this property if you want to test your plugin in WebStorm or any other non-IDEA JetBrains IDE.
Empty value means that the IDE that was used for compiling will be used for running/debugging as well.<br/><br/>
**Default value**: `<empty>`

- `intellij.ideaDependencyCachePath` – absolute path to the local directory that should be used for storing IDEA
distributions. If empty – Gradle cache directory will be used.
**Default value**: `<empty>`

### Patching plugin.xml

The `patchPluginXml` task supports following properties:

- `version` is a value for `<version>` tag.<br/>
**Default value**: `<project.version>`

- `sinceBuild` is a value for `<idea-version since-build="">` attribute.<br/>
**Default value**: `<IntelliJIDEABuildNumber>`

- `untilBuild` is a value for `<idea-version until-build="">` attribute.<br/>
**Default value**: `<IntelliJIDEABranch.*>`

- `pluginDescription` is a value for `<description>` tag.<br/>
**Default value**: null

- `pluginXmlFiles` is a collections of xml files to patch.<br/>
**Default value**: `<all plugin.xml files with idea-plugin root tag in resources>`

- `destinationDir` is a directory to store patched xml files.<br/>
**Default value**: `<project.buildDir>/patchedPluginXmlFiles`

### Publishing plugin

**`intellij.publish.\* properties are deprecated**
- `intellij.publish.username` your login at JetBrains plugin repository.
- `intellij.publish.password` your password at JetBrains plugin repository.
- `intellij.publish.channel` defines channel to upload, you may use any string here, empty string means default channel.
- `intellij.publish.channels` defines several channels to upload, you may use any comma-separated strings here, 
`default` string means default channel.<br/><br/>
**Default value**: `<empty>`

`publishPlugin` task supports following properties:

- `username` is a login at JetBrains plugin repository.
- `password` is a password at JetBrains plugin repository.
- `channels` are channels names to upload the plugin to.<br/>
**Default value**: `[default]`

- `host` host of plugin repository.<br/>
**Default value**: `http://plugins.jetbrains.com`

- `distributionFile` is a file to upload.<br/>
**Default value**: `<output of buildPlugin task>`


