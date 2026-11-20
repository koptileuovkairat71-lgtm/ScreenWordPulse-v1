#!/bin/sh
# Minimal wrapper: uses system gradle if present, else downloads Gradle 8.7
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
GRADLE_ZIP=/tmp/gradle-8.7-bin.zip
if [ ! -d /tmp/gradle-8.7 ]; then curl -L -o $GRADLE_ZIP https://services.gradle.org/distributions/gradle-8.7-bin.zip && unzip -q $GRADLE_ZIP -d /tmp; fi
exec /tmp/gradle-8.7/bin/gradle "$@"
