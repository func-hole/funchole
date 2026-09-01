#!/bin/sh

set -eu

. /opt/funchole/openbao-common.sh

cd /workspace
chmod +x ./gradlew
chmod +x /opt/funchole/watch-and-run.sh

wait_for_openbao
export_secret_document gateway/app

exec /opt/funchole/watch-and-run.sh \
    /workspace/core/src \
    /workspace/gateway/src \
    /workspace/gateway/build.gradle \
    /workspace/core/build.gradle \
    /workspace/build.gradle \
    /workspace/settings.gradle \
    /workspace/gradle.properties \
    -- \
    ./gradlew :gateway:run --no-daemon --project-cache-dir /tmp/gradle-gateway-project
