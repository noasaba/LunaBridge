plugins {
    `java-library`
}

val jdaVersion = project.property("jdaVersion").toString()

dependencies {
    api(project(":lunachat-integration-api"))
    implementation("net.dv8tion:JDA:$jdaVersion")
}
