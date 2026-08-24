#!/bin/sh

# Gradle wrapper launcher. The binary wrapper JAR is the unmodified Gradle 9.6.1 wrapper.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
exec java -Dorg.gradle.appname=gradlew -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
