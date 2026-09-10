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
export_secret_document controlplane/app

DNS_SERVICE_NAME="${DNS_SERVICE_NAME:-dns}"
DNS_SERVER_IP="$(getent hosts "${DNS_SERVICE_NAME}" | awk 'NR==1 { print $1 }')"

if [ -n "${DNS_SERVER_IP}" ]; then
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Ddns.server=${DNS_SERVER_IP} -Dspring.devtools.restart.enabled=false"
    echo "Resolved ${DNS_SERVICE_NAME} to ${DNS_SERVER_IP} for dnsjava"
else
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dspring.devtools.restart.enabled=false"
    echo "Could not resolve ${DNS_SERVICE_NAME}; dnsjava will use its default resolver configuration"
fi

exec /workspace/docker/watch-and-run.sh \
    /workspace/core/src \
    /workspace/controlplane/src \
    /workspace/controlplane/build.gradle \
    /workspace/core/build.gradle \
    /workspace/build.gradle \
    /workspace/settings.gradle \
    /workspace/gradle.properties \
    -- \
    ./gradlew :controlplane:bootRun --no-daemon --project-cache-dir /tmp/gradle-controlplane-project
