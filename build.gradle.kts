import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.simzhou.audiolens"
version = "1.0.0"

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
            <p>AudioLens for PyCharm 1.0.0.</p>
            <ul>
              <li>Adds adaptive, source PCM sample-value, and symmetric dBFS waveform amplitude scales.</li>
              <li>Localizes the PyCharm configuration page in Chinese and adds contextual help for its parameters.</li>
              <li>Adds localized round-question-mark explanations for every audio parameter in the embedded settings panel.</li>
              <li>Opens audio directly from PyCharm's Remote Host view by caching Web Deployment virtual-file content locally.</li>
              <li>Moves selection analysis behind the selection context menu and adds an explicit close button.</li>
              <li>Guards delayed JCEF page loads and callbacks when an audio editor is closing.</li>
              <li>Extends compatibility to PyCharm 2025.1 (build branch 251).</li>
              <li>Adds local PyCharm + remote interpreter SFTP audio support through existing Deployment configurations, with progress, cancellation, reuse, and a bounded local cache.</li>
              <li>Adds SFTP connection testing, path mapping, friendly remote-file errors, cache controls, and privacy-safe SFTP diagnostics.</li>
              <li>Remote SFTP Ark files are intentionally unsupported; download them locally before using Ark offsets.</li>
              <li>Ctrl/Command-click audio paths in Python, JSON, text, logs and Kaldi wav.scp files, including relative paths and ark offsets.</li>
              <li>Adds actionable FFmpeg errors and privacy-safe diagnostics that can be copied from the error, settings, or Tools menu.</li>
              <li>Implements the current FileEditor file contract to prevent internal IDE deprecation errors.</li>
              <li>Registers supported audio extensions so files open directly in AudioLens instead of showing the file-association dialog.</li>
              <li>Waveform, spectrogram, playback, multichannel and selection analysis.</li>
              <li>WAV, MP3, FLAC, OGG, Opus, M4A, AAC, raw PCM and Kaldi WAV ark support.</li>
              <li>Optional system FFmpeg integration for encoded audio and streamed PCM caches.</li>
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
