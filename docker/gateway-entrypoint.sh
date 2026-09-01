#!/bin/sh

set -eu

. /opt/funchole/openbao-common.sh

wait_for_openbao
export_secret_document gateway/app

exec java -jar /app/app.jar
