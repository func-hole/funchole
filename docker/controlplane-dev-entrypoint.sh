#!/bin/sh

set -eu

. /workspace/docker/openbao-common.sh

cd /workspace
chmod +x ./gradlew
chmod +x /workspace/docker/watch-and-run.sh

wait_for_openbao
export_secret_document controlplane/app

DNS_SERVICE_NAME="${DNS_SERVICE_NAME:-dns}"
DNS_SERVER_IP="$(getent hosts "${DNS_SERVICE_NAME}" | awk 'NR==1 { print $1 }')"

if [ -n "${DNS_SERVER_IP}" ]; then
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Ddns.server=${DNS_SERVER_IP}"
    echo "Resolved ${DNS_SERVICE_NAME} to ${DNS_SERVER_IP} for dnsjava"
else
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
