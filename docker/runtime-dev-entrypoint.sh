#!/bin/sh

set -eu

cd /workspace
chmod +x ./gradlew
chmod +x /workspace/docker/watch-and-run.sh

# Container-local (not bind-mounted), so this service's Gradle build output
# never races with another dev-compose service compiling the same shared
# module at the same time. See build.gradle.
export FUNCHOLE_BUILD_DIR_ROOT="/tmp/funchole-gradle-build"

exec /workspace/docker/watch-and-run.sh \
    /workspace/runtime/src \
    /workspace/runtime/build.gradle \
    /workspace/build.gradle \
    /workspace/settings.gradle \
    /workspace/gradle.properties \
    -- \
    ./gradlew :runtime:run --no-daemon --project-cache-dir /tmp/gradle-runtime-project
