plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
}

val paperApiVersion = project.property("paperApiVersion").toString()
val lunaChatApiVersion = project.property("lunaChatApiVersion").toString()
val lunaChatApiJar = providers.gradleProperty("lunaChatApiJar").orNull
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://raw.githubusercontent.com/ucchyocean/mvn-repo/master")
}

dependencies {
    implementation(project(":lunabridge-discord"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    if (lunaChatApiJar == null) compileOnly("com.github.ucchyocean:lunachat-api:$lunaChatApiVersion")
    else compileOnly(files(lunaChatApiJar))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testCompileOnly(if (lunaChatApiJar == null) "com.github.ucchyocean:lunachat-api:$lunaChatApiVersion" else files(lunaChatApiJar))
}

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
tasks.test { systemProperty("lunabridge.projectVersion", pluginVersion) }

tasks.shadowJar {
    archiveBaseName.set("lunabridge-paper-standalone")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.jar {
    archiveClassifier.set("internal")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("plugin.yml")
}
tasks.assemble { dependsOn(tasks.shadowJar) }
