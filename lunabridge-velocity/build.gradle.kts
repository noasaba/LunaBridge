plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
}

val velocityApiVersion: String by project
val jdaVersion: String by project

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":lunabridge-core"))
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityApiVersion")
    implementation("net.dv8tion:JDA:$jdaVersion")
    testImplementation("com.velocitypowered:velocity-api:$velocityApiVersion")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("velocity-plugin.json") { expand("version" to project.version) }
}

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
