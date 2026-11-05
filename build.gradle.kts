import java.security.MessageDigest
import java.time.YearMonth
import java.util.Properties

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "com.prituladima.plugin.intellij"

// ---- Version yyyy.mm.NNNN, kept in version.properties --------------------------------------------
// NNNN goes up by 1 on every build (build, assemble, buildPlugin, signPlugin, publishPlugin) whose
// inputs changed since the last bump: src/, the Gradle build files, gradle.properties. IDE syncs and
// other tasks (runIde, verifyPlugin, ...) use the current version as it is.

/** SHA-256 of the plugin's inputs; CR bytes of text files are ignored, so Windows and Unix checkouts agree. */
abstract class InputsHash : ValueSource<String, InputsHash.Params> {
    interface Params : ValueSourceParameters {
        val root: DirectoryProperty
    }

    override fun obtain(): String {
        val root = parameters.root.get().asFile
        val files = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties").map(root::resolve) +
            root.resolve("src").walkTopDown().filter { it.isFile }
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files.filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }) {
            val bytes = file.readBytes()
            val binary = file.extension.lowercase() in setOf("png", "jpg", "gif", "ico", "jar")
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
            digest.update(0)
            digest.update(if (binary) bytes else bytes.filter { it != '\r'.code.toByte() }.toByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val pluginVersion: String = run {
    val file = layout.projectDirectory.file("version.properties").asFile
    val state = Properties().apply { if (file.isFile) file.reader().use { load(it) } }
    val current: String? = state.getProperty("version")
    val hash = providers.of(InputsHash::class) { parameters.root.set(layout.projectDirectory) }.get()
    val bumpTasks = setOf("build", "assemble", "buildPlugin", "signPlugin", "publishPlugin")
    val bump = gradle.startParameter.taskNames.any { it.substringAfterLast(':') in bumpTasks }
    val now = YearMonth.now()
    when {
        current != null && (!bump || state.getProperty("inputsHash") == hash) -> current
        !bump -> "%d.%02d.%04d".format(now.year, now.monthValue, 0)
        else -> {
            val number = (current?.substringAfterLast('.')?.toIntOrNull() ?: 0) + 1
            "%d.%02d.%04d".format(now.year, now.monthValue, number).also {
                file.writeText(
                    "# yyyy.mm.NNNN, written by build.gradle.kts: NNNN goes up by 1 on every build with changed inputs\n" +
                        "version=$it\ninputsHash=$hash\n"
                )
            }
        }
    }
}
version = pluginVersion

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get())
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // SSH for the remote computer (password and key login, commands, the Jupyter port forward);
    // pure Java, so it works the same on Linux, Windows and macOS. BSD-style license.
    implementation("com.github.mwiede:jsch:${providers.gradleProperty("jschVersion").get()}")
    // CPU cores, memory and temperatures for the system monitor, on Linux, Windows and macOS. MIT license.
    // Built against the JNA the IDE ships (in the platform up to 2026.2, the bundled JNA plugin from 2026.3),
    // which it uses instead of a copy of its own; the IDE also ships SLF4J.
    implementation("com.github.oshi:oshi-core:${providers.gradleProperty("oshiVersion").get()}") {
        exclude(group = "net.java.dev.jna")
        exclude(group = "org.slf4j")
    }

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugins("org.jetbrains.plugins.terminal", "org.jetbrains.plugins.yaml")
        plugin("PythonCore", providers.gradleProperty("pythonPluginVersion").get())
    }
}

intellijPlatform {
    // Both start a headless IDE during the build; this plugin has no settings pages or forms.
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        version = pluginVersion
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }   // no upper bound: the plugin stays enabled after IDE updates
        }
    }

    // `./gradlew verifyPlugin`: binary compatibility with the current IDE releases
    pluginVerification {
        ides {
            recommended()
        }
    }

    // `./gradlew publishPlugin`, after the first manual upload to JetBrains Marketplace:
    // export ORG_GRADLE_PROJECT_intellijPlatformPublishingToken=<token from Marketplace | My Tokens>
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}

// `./gradlew build` also produces the installable build/distributions/<name>-<version>.zip
tasks.named("assemble") {
    dependsOn(tasks.named("buildPlugin"))
}

// only the current version's zip stays in build/distributions
tasks.named<Zip>("buildPlugin") {
    val distributions = destinationDirectory
    doFirst {
        distributions.get().asFile.listFiles { f -> f.name.endsWith(".zip") }?.forEach { it.delete() }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}
