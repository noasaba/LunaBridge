plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
}

val velocityApiVersion = project.property("velocityApiVersion").toString()
val jdaVersion = project.property("jdaVersion").toString()
val pluginVersion = version.toString()
require(pluginVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) {
    "projectVersion must be a SemVer-compatible value"
}
val generatedVersionSource = layout.buildDirectory.dir("generated/sources/lunabridgeVersion/java")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":lunabridge-core"))
    compileOnly(project(":lunachat-integration-api"))
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityApiVersion")
    implementation("net.dv8tion:JDA:$jdaVersion")
    testImplementation("com.velocitypowered:velocity-api:$velocityApiVersion")
    testImplementation(project(":lunachat-integration-api"))
}

sourceSets.main {
    java.srcDir(generatedVersionSource)
}

val generateVersionSource = tasks.register("generateVersionSource") {
    inputs.property("version", pluginVersion)
    outputs.dir(generatedVersionSource)
    doLast {
        val packageDirectory = generatedVersionSource.get().dir("dev/lunabridge/velocity").asFile
        packageDirectory.mkdirs()
        packageDirectory.resolve("LunaBridgeBuildVersion.java").writeText(
            """package dev.lunabridge.velocity;

final class LunaBridgeBuildVersion {
    static final String VERSION = "$pluginVersion";
    private LunaBridgeBuildVersion() { }
}
"""
        )
    }
}

tasks.compileJava { dependsOn(generateVersionSource) }
tasks.sourcesJar { dependsOn(generateVersionSource) }
tasks.test { systemProperty("lunabridge.projectVersion", pluginVersion) }

tasks.shadowJar {
    archiveBaseName.set("lunabridge-velocity")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.jar {
    archiveClassifier.set("internal")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("velocity-plugin.json")
}
tasks.assemble { dependsOn(tasks.shadowJar) }
