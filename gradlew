#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
if [ ! -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    echo 'Missing gradle-wrapper.jar. Run scripts/bootstrap-macos.sh or see README.md.' >&2
    exit 1
fi
exec "$JAVA" -Dorg.gradle.appname=gradlew -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
