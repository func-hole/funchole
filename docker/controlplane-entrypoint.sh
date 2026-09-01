#!/bin/sh

set -eu

. /opt/funchole/openbao-common.sh

wait_for_openbao
export_secret_document controlplane/app

exec java -jar /app/app.jar
