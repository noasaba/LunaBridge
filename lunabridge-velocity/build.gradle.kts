plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
}

val velocityApiVersion = project.property("velocityApiVersion").toString()
val lunaChatApiVersion = project.property("lunaChatApiVersion").toString()
val lunaChatApiJar = providers.gradleProperty("lunaChatApiJar").orNull
val svsyncApiVersion = project.property("svsyncApiVersion").toString()
val svsyncApiJar = providers.gradleProperty("svsyncApiJar").orNull
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
    implementation(project(":lunabridge-discord"))
    if (lunaChatApiJar == null) compileOnly("com.github.ucchyocean:lunachat-api:$lunaChatApiVersion")
    else compileOnly(files(lunaChatApiJar))
    if (svsyncApiJar == null) compileOnly("com.noasaba.svsync:SVSync-API:$svsyncApiVersion")
    else compileOnly(files(svsyncApiJar))
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityApiVersion")
    testImplementation("com.velocitypowered:velocity-api:$velocityApiVersion")
    testCompileOnly(if (lunaChatApiJar == null) "com.github.ucchyocean:lunachat-api:$lunaChatApiVersion" else files(lunaChatApiJar))
    testCompileOnly(if (svsyncApiJar == null) "com.noasaba.svsync:SVSync-API:$svsyncApiVersion" else files(svsyncApiJar))
    testRuntimeOnly(if (svsyncApiJar == null) "com.noasaba.svsync:SVSync-API:$svsyncApiVersion" else files(svsyncApiJar))
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
