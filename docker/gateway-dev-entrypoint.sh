#!/bin/sh

set -eu

. /workspace/docker/openbao-common.sh

cd /workspace
chmod +x ./gradlew
chmod +x /workspace/docker/watch-and-run.sh

# Container-local (not bind-mounted), so this service's Gradle build output
# never races with another dev-compose service compiling the same shared
# module (e.g. :core) at the same time. See build.gradle.
export FUNCHOLE_BUILD_DIR_ROOT="/tmp/funchole-gradle-build"

wait_for_openbao
export_secret_document gateway/app

exec /workspace/docker/watch-and-run.sh \
    /workspace/core/src \
    /workspace/gateway/src \
    /workspace/gateway/build.gradle \
    /workspace/core/build.gradle \
    /workspace/build.gradle \
    /workspace/settings.gradle \
    /workspace/gradle.properties \
    -- \
    ./gradlew :gateway:run --no-daemon --project-cache-dir /tmp/gradle-gateway-project
