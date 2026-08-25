plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
}

val paperApiVersion = project.property("paperApiVersion").toString()
val lunaChatVersion = project.property("lunaChatVersion").toString()
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://raw.githubusercontent.com/ucchyocean/mvn-repo/master")
}

dependencies {
    implementation(project(":lunabridge-core"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // Official LunaChat distribution; never shaded or bundled.
    compileOnly("com.github.ucchyocean:LunaChat:$lunaChatVersion") { isTransitive = false }
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("com.github.ucchyocean:LunaChat:$lunaChatVersion") { isTransitive = false }
}

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
tasks.test { systemProperty("lunabridge.projectVersion", pluginVersion) }

tasks.shadowJar {
    archiveBaseName.set("lunabridge-paper")
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
