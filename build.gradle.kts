import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

val javaToolchainVersion: String by project
val paperJavaVersion: String by project
val velocityJavaVersion: String by project
val junitVersion: String by project

allprojects {
    group = providers.gradleProperty("projectGroup").get()
    version = providers.gradleProperty("projectVersion").get()
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    val release = if (name == "lunabridge-velocity") velocityJavaVersion.toInt() else paperJavaVersion.toInt()
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
        withSourcesJar()
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(release)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
}
