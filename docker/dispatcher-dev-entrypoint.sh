#!/bin/sh

set -eu

cd /workspace
chmod +x ./gradlew
chmod +x /workspace/docker/watch-and-run.sh

exec /workspace/docker/watch-and-run.sh \
    /workspace/core/src \
    /workspace/invocation/src \
    /workspace/dispatcher/src \
    /workspace/invocation/build.gradle \
    /workspace/dispatcher/build.gradle \
    /workspace/core/build.gradle \
    /workspace/build.gradle \
    /workspace/settings.gradle \
    /workspace/gradle.properties \
    -- \
    ./gradlew :dispatcher:run --no-daemon --project-cache-dir /tmp/gradle-dispatcher-project
