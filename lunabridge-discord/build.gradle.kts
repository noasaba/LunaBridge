plugins {
    `java-library`
}

val jdaVersion = project.property("jdaVersion").toString()
val lunaChatApiVersion = project.property("lunaChatApiVersion").toString()
val lunaChatApiJar = providers.gradleProperty("lunaChatApiJar").orNull

dependencies {
    if (lunaChatApiJar == null) compileOnly("com.github.ucchyocean:lunachat-api:$lunaChatApiVersion")
    else compileOnly(files(lunaChatApiJar))
    testCompileOnly(if (lunaChatApiJar == null) "com.github.ucchyocean:lunachat-api:$lunaChatApiVersion" else files(lunaChatApiJar))
    testRuntimeOnly(if (lunaChatApiJar == null) "com.github.ucchyocean:lunachat-api:$lunaChatApiVersion" else files(lunaChatApiJar))
    implementation("net.dv8tion:JDA:$jdaVersion")
}
