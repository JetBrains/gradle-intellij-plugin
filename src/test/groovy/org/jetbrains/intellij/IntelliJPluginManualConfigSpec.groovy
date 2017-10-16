package org.jetbrains.intellij

class IntelliJPluginManualConfigSpec extends IntelliJPluginSpecBase {

    def 'configure sdk manually test'() {
        given:
        writeTestFile()
        buildFile << """\
            intellij.configureDefaultDependencies = false
            afterEvaluate {
                dependencies {
                    compileOnly intellij { include('openapi.jar') }
                    compile     intellij { include('asm-all.jar') }
                    runtime     intellij { exclude('idea.jar') }
                    testCompile intellij { include('boot.jar') }
                    testRuntime intellij()
                } 
            }
            task printMainCompileClassPath { doLast { println 'compile: ' + sourceSets.main.compileClasspath.asPath } }
            task printMainRuntimeClassPath { doLast { println 'runtime: ' + sourceSets.main.runtimeClasspath.asPath } }
            task printTestCompileClassPath { doLast { println 'testCompile: ' + sourceSets.test.compileClasspath.asPath } }
            task printTestRuntimeClassPath { doLast { println 'testRuntime: ' + sourceSets.test.runtimeClasspath.asPath } }
            """.stripIndent()

        when:
        def result = build('printMainCompileClassPath', 'printTestCompileClassPath', 'printTestRuntimeClassPath', 'printMainRuntimeClassPath')
        def mainClasspath = result.output.readLines().find { it.startsWith('compile:') }
        def mainRuntimeClasspath = result.output.readLines().find { it.startsWith('runtime:') }
        def testClasspath = result.output.readLines().find { it.startsWith('testCompile:') }
        def testRuntimeClasspath = result.output.readLines().find { it.startsWith('testRuntime:') }

        then:
        assert  mainClasspath.contains('openapi.jar')           // included explicitly in compileOnly 
        assert  mainRuntimeClasspath.contains('openapi.jar')    // includes all but idea.jar
        assert !testClasspath.contains('openapi.jar')
        assert  testRuntimeClasspath.contains('openapi.jar')    // includes all

        assert  mainClasspath.contains('asm-all.jar')           // included explicitly 
        assert  testClasspath.contains('asm-all.jar')
        assert  testRuntimeClasspath.contains('asm-all.jar')    // includes all

        assert !mainClasspath.contains('boot.jar')
        assert  testClasspath.contains('boot.jar')              // included explicitly
        assert  testRuntimeClasspath.contains('boot.jar')       // includes all

        assert !mainClasspath.contains('idea.jar')
        assert !mainRuntimeClasspath.contains('idea.jar')       // excluded explicitly
        assert !testClasspath.contains('idea.jar')
        assert  testRuntimeClasspath.contains('idea.jar')       // includes all

        assert mainRuntimeClasspath.contains('idea_rt.jar')     // includes all but idea.jar
    }

    def 'configure plugins manually test'() {
        given:
        writeTestFile()
        buildFile << """\
            intellij {
                configureDefaultDependencies = false
                plugins = ['junit', 'testng', 'copyright']
            }
            afterEvaluate {
                dependencies {
                    compileOnly intellijPlugin('junit')  { include('junit-rt.jar') }
                    compile     intellijPlugin('junit')  { include('idea-junit.jar') }
                    runtime     intellijPlugin('testng') { exclude('testng-plugin.jar') }
                    testCompile intellijPlugin('testng') { include("testng.jar") }
                    testRuntime intellijPlugins('junit', 'testng')
                } 
            }
            task printMainCompileClassPath { doLast { println 'compile: ' + sourceSets.main.compileClasspath.asPath } }
            task printMainRuntimeClassPath { doLast { println 'runtime: ' + sourceSets.main.runtimeClasspath.asPath } }
            task printTestCompileClassPath { doLast { println 'testCompile: ' + sourceSets.test.compileClasspath.asPath } }
            task printTestRuntimeClassPath { doLast { println 'testRuntime: ' + sourceSets.test.runtimeClasspath.asPath } }
            """.stripIndent()

        when:
        def result = build('printMainCompileClassPath', 'printTestCompileClassPath', 'printTestRuntimeClassPath', 'printMainRuntimeClassPath')
        def mainClasspath = result.output.readLines().find { it.startsWith('compile:') }
        def mainRuntimeClasspath = result.output.readLines().find { it.startsWith('runtime:') }
        def testClasspath = result.output.readLines().find { it.startsWith('testCompile:') }
        def testRuntimeClasspath = result.output.readLines().find { it.startsWith('testRuntime:') }

        then:
        assert  mainClasspath.contains('junit-rt.jar')          // included explicitly in compileOnly
        assert !mainRuntimeClasspath.contains('junit-rt.jar')
        assert !testClasspath.contains('junit-rt.jar')
        assert  testRuntimeClasspath.contains('junit-rt.jar')   // includes all

        assert  mainClasspath.contains('idea-junit.jar')        // included explicitly in compile
        assert  testClasspath.contains('idea-junit.jar')        // inherited from compile
        assert  testRuntimeClasspath.contains('idea-junit.jar') // includes all

        assert !mainClasspath.contains('testng-plugin.jar')
        assert !mainRuntimeClasspath.contains('testng-plugin.jar') // excluded explicitly
        assert !testClasspath.contains('testng-plugin.jar')
        assert  testRuntimeClasspath.contains('testng-plugin.jar') // includes all

        assert !mainClasspath.contains('testng.jar')
        assert  mainRuntimeClasspath.contains('testng.jar')     // includes testng
        assert  testClasspath.contains('testng.jar')            // included explicitly
        assert  testRuntimeClasspath.contains('testng.jar')     // includes all

        assert !mainClasspath.contains('copyright.jar')         // not included (same for all below)
        assert !mainRuntimeClasspath.contains('copyright.jar')
        assert !testClasspath.contains('copyright.jar')
        assert !testRuntimeClasspath.contains('copyright.jar')
    }

    def 'configure sdk manually fail without afterEvaluate'() {
        given:
        writeTestFile()
        buildFile << """\
            intellij.configureDefaultDependencies = false
            dependencies {
                compile intellij { include('asm-all.jar') }
            } 
            """.stripIndent()

        when:
        def result = buildAndFail('tasks')

        then:
        result.output.contains('intellij is not (yet) configured. Please note that you should configure intellij dependencies in the afterEvaluate block')
    }

    def 'configure plugins manually fail without afterEvaluate'() {
        given:
        writeTestFile()
        buildFile << """\
            intellij.configureDefaultDependencies = false
            dependencies {
                compile intellijPlugin('junit')
            } 
            """.stripIndent()

        when:
        def result = buildAndFail('tasks')

        then:
        result.output.contains('intellij plugin \'junit\' is not (yet) configured or not found. Please note that you should specify plugins in the intellij.plugins property and configure dependencies to them in the afterEvaluate block')
    }

    def 'configure plugins manually fail on unconfigured plugin'() {
        given:
        writeTestFile()
        buildFile << """\
            intellij {
                configureDefaultDependencies = false
                plugins = []
            }
            afterEvaluate {
                dependencies {
                    compile intellijPlugin('junit')
                }
            } 
            """.stripIndent()

        when:
        def result = buildAndFail('tasks')

        then:
        result.output.contains('intellij plugin \'junit\' is not (yet) configured or not found. Please note that you should specify plugins in the intellij.plugins property and configure dependencies to them in the afterEvaluate block')
    }

    def 'configure plugins manually fail on some unconfigured plugins'() {
        given:
        writeTestFile()
        buildFile << """\
            intellij {
                configureDefaultDependencies = false
                plugins = ['junit']
            }
            afterEvaluate {
                dependencies {
                    compile intellijPlugins('testng', 'junit', 'copyright')
                }
            } 
            """.stripIndent()

        when:
        def result = buildAndFail('tasks')

        then:
        result.output.contains('intellij plugins [testng, copyright] are not (yet) configured or not found. Please note that you should specify plugins in the intellij.plugins property and configure dependencies to them in the afterEvaluate block')
    }
}
