#!/bin/sh
# Gradle wrapper (versão enxuta do script oficial, só POSIX).
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ]; then JAVACMD=$JAVA_HOME/bin/java; else JAVACMD=java; fi
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
