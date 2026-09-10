package com.funchole.backend.dispatcher;

record IpcRuntimeErrorPayload(
        String code,
        String message
) {
    RuntimeExecutionError toRuntimeError() {
        return new RuntimeExecutionError(code, message);
    }
}
