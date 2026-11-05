plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "com.prituladima.plugin.intelij"
version = providers.gradleProperty("pluginVersion").get()

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
        version = providers.gradleProperty("pluginVersion")
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
}

// `./gradlew build` also produces the installable build/distributions/<name>-<version>.zip
tasks.named("assemble") {
    dependsOn(tasks.named("buildPlugin"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}
