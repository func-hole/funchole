#!/bin/sh

set -eu

. /workspace/docker/openbao-common.sh

cd /workspace
chmod +x ./gradlew
chmod +x /workspace/docker/watch-and-run.sh

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
