import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.simzhou.audiolens"
version = "1.0.1"

val localPycharmPath = providers.gradleProperty("localPycharmPath").orNull
val verificationPycharmPaths = providers.gradleProperty("verificationPycharmPaths").orNull

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (localPycharmPath == null) pycharm("2025.1") else local(localPycharmPath)
        bundledPlugin("PythonCore")
        bundledPlugin("com.jetbrains.plugins.webDeployment")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

configurations.testCompileClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

configurations.testRuntimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

kotlin {
    jvmToolchain(21)
}

val npmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "Install the pinned AudioLens web UI dependencies."
    commandLine(npmCommand, "ci", "--ignore-scripts")
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

val buildWebview by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the AudioLens browser UI used by JCEF."
    dependsOn(npmInstall)
    commandLine(npmCommand, "run", "build:pycharm")
    inputs.files(fileTree("src") { include("shared/**", "webview/**", "ffmpegWav.ts") })
    inputs.file("scripts/build-pycharm.mjs")
    outputs.file("dist/webview.js")
}

tasks.processResources {
    dependsOn(buildWebview)
    from("dist/webview.js") {
        into("web")
    }
}

tasks.jar {
    from(listOf("LICENSE", "NOTICE")) {
        into("META-INF")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "AudioLens"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "261.*"
        }
        changeNotes = """
            <p>AudioLens for PyCharm 1.0.1.</p>
            <ul>
              <li>Shows the actual local directory used to cache downloaded remote audio files.</li>
              <li>Updates the upstream-project attribution in the README.</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            if (verificationPycharmPaths == null) {
                recommended()
            } else {
                verificationPycharmPaths.split(',').filter(String::isNotBlank).forEach(::local)
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
