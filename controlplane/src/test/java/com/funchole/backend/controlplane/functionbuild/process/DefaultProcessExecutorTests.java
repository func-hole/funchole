package com.funchole.backend.controlplane.functionbuild.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plain unit tests against the real {@link DefaultProcessExecutor}. These
 * spawn trivial shell commands (echo/sleep) - never npm or any network
 * access - specifically to prove the generic process-execution boundary
 * works, and works correctly, entirely on its own: nothing here references
 * {@code NodeRuntimeBuilder} or any other Node-specific type.
 */
class DefaultProcessExecutorTests {

    private static final String NODE_SPECIFIC_PACKAGE = "com.funchole.backend.controlplane.functionbuild.runtime.node";

    private final DefaultProcessExecutor executor = new DefaultProcessExecutor();

    @Test
    void successfulProcessReturnsExitCodeStdoutAndStderr(@TempDir Path workingDirectory) {
        ProcessResult result = executor.execute(
                List.of("sh", "-c", "echo out-line; echo err-line 1>&2; exit 3"),
                workingDirectory,
                Duration.ofSeconds(10)
        );

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).contains("out-line");
        assertThat(result.stderr()).contains("err-line");
    }

    @Test
    void timeoutReturnsTimedOutTrueWithNoExitCode(@TempDir Path workingDirectory) {
        ProcessResult result = executor.execute(
                List.of("sh", "-c", "sleep 30"),
                workingDirectory,
                Duration.ofMillis(200)
        );

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isNull();
    }

    @Test
    void timeoutTerminatesTheChildProcess(@TempDir Path workingDirectory) throws Exception {
        Path counterFile = workingDirectory.resolve("counter");
        Files.writeString(counterFile, "");
        List<String> command = List.of("sh", "-c",
                "i=0; while [ $i -lt 200 ]; do echo $i >> " + counterFile + "; i=$((i+1)); sleep 0.05; done");

        ProcessResult result = executor.execute(command, workingDirectory, Duration.ofMillis(200));

        assertThat(result.timedOut()).isTrue();
        long linesRightAfterTimeout = countLines(counterFile);

        Thread.sleep(500);
        long linesAfterWaiting = countLines(counterFile);

        // If the child were still running, it would have appended more lines
        // during the extra wait - it must not have, since execute(...) is
        // required to only return once the child has actually terminated.
        assertThat(linesAfterWaiting).isEqualTo(linesRightAfterTimeout);
    }

    @Test
    void interruptionDoesNotLeaveTheChildProcessRunning(@TempDir Path workingDirectory) throws Exception {
        Path counterFile = workingDirectory.resolve("counter");
        Files.writeString(counterFile, "");
        List<String> command = List.of("sh", "-c",
                "i=0; while [ $i -lt 200 ]; do echo $i >> " + counterFile + "; i=$((i+1)); sleep 0.05; done");

        ExecutorService callerThread = Executors.newSingleThreadExecutor();
        try {
            Future<ProcessResult> future = callerThread.submit(
                    () -> executor.execute(command, workingDirectory, Duration.ofSeconds(30)));

            Thread.sleep(300);
            boolean cancelled = future.cancel(true);
            assertThat(cancelled).isTrue();

            long linesRightAfterInterrupt = countLines(counterFile);
            Thread.sleep(500);
            long linesAfterWaiting = countLines(counterFile);

            assertThat(linesAfterWaiting).isEqualTo(linesRightAfterInterrupt);
        } finally {
            callerThread.shutdownNow();
            assertThat(callerThread.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void processExecutorAbstractionHasNoNodeSpecificDependency() {
        List<Class<?>> genericTypes = List.of(ProcessExecutor.class, ProcessResult.class, DefaultProcessExecutor.class);
        for (Class<?> type : genericTypes) {
            for (Field field : type.getDeclaredFields()) {
                assertPackageIsNotNodeSpecific(field.getType());
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    assertPackageIsNotNodeSpecific(parameterType);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                assertPackageIsNotNodeSpecific(method.getReturnType());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertPackageIsNotNodeSpecific(parameterType);
                }
            }
        }
    }

    private void assertPackageIsNotNodeSpecific(Class<?> type) {
        assertThat(type.getPackageName())
                .as("type %s must not belong to the Node-specific runtime package", type.getName())
                .doesNotStartWith(NODE_SPECIFIC_PACKAGE);
    }

    private long countLines(Path file) throws IOException {
        try (var lines = Files.lines(file)) {
            return lines.count();
        }
    }
}
