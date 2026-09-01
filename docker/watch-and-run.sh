#!/bin/sh

set -eu

if [ "$#" -lt 2 ]; then
    echo "usage: $0 <watch-paths>... -- <command>..." >&2
    exit 1
fi

WATCH_PATHS=""
while [ "$#" -gt 0 ]; do
    if [ "$1" = "--" ]; then
        shift
        break
    fi
    WATCH_PATHS="${WATCH_PATHS} $1"
    shift
done

if [ "$#" -eq 0 ]; then
    echo "missing command to run" >&2
    exit 1
fi

APP_PID=""

cleanup() {
    if [ -n "${APP_PID}" ] && kill -0 "${APP_PID}" 2>/dev/null; then
        kill "${APP_PID}" 2>/dev/null || true
        wait "${APP_PID}" 2>/dev/null || true
    fi
}

trap cleanup EXIT INT TERM

compute_state() {
    find $WATCH_PATHS -type f 2>/dev/null \
        | sort \
        | xargs stat -c '%Y %n' 2>/dev/null \
        | sha256sum \
        | awk '{print $1}'
}

start_app() {
    "$@" &
    APP_PID=$!
    echo "Started app process with PID ${APP_PID}"
}

stop_app() {
    if [ -n "${APP_PID}" ] && kill -0 "${APP_PID}" 2>/dev/null; then
        echo "Stopping app process ${APP_PID}"
        kill "${APP_PID}" 2>/dev/null || true
        wait "${APP_PID}" 2>/dev/null || true
    fi
    APP_PID=""
}

LAST_STATE="$(compute_state)"
start_app "$@"

while true; do
    sleep 2

    CURRENT_STATE="$(compute_state)"
    if [ "${CURRENT_STATE}" != "${LAST_STATE}" ]; then
        echo "Source change detected, restarting application"
        LAST_STATE="${CURRENT_STATE}"
        stop_app
        start_app "$@"
        continue
    fi

    if [ -n "${APP_PID}" ] && ! kill -0 "${APP_PID}" 2>/dev/null; then
        echo "Application process exited, starting again"
        start_app "$@"
    fi
done
