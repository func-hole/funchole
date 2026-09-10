#!/bin/sh

set -eu

cd /workspace
chmod +x ./gradlew
chmod +x /workspace/docker/watch-and-run.sh

exec /workspace/docker/watch-and-run.sh \
    /workspace/runtime/src \
    /workspace/runtime/build.gradle \
    /workspace/build.gradle \
    /workspace/settings.gradle \
    /workspace/gradle.properties \
    -- \
    ./gradlew :runtime:run --no-daemon --project-cache-dir /tmp/gradle-runtime-project
