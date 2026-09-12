package com.funchole.backend.controlplane.functionbuild.runtime.node;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Real {@link ProcessExecutor}: wraps {@link ProcessBuilder}. Stdout/stderr
 * are drained on separate threads concurrently with the process running, to
 * avoid the classic deadlock where a process blocks writing to a full pipe
 * while nothing is reading the other one.
 */
@Component
public class DefaultProcessExecutor implements ProcessExecutor {

    @Override
    public ProcessResult execute(List<String> command, Path workingDirectory, Duration timeout) {
        ProcessBuilder processBuilder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        ExecutorService streamReaders = Executors.newFixedThreadPool(2);
        try {
            Process process = processBuilder.start();
            Future<String> stdout = streamReaders.submit(() -> readFully(process.getInputStream()));
            Future<String> stderr = streamReaders.submit(() -> readFully(process.getErrorStream()));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(null, awaitOutput(stdout), awaitOutput(stderr), true);
            }
            return new ProcessResult(process.exitValue(), awaitOutput(stdout), awaitOutput(stderr), false);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to execute command: " + command, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing command: " + command, exception);
        } finally {
            streamReaders.shutdownNow();
        }
    }

    private String readFully(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    private String awaitOutput(Future<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            return "";
        }
    }
}
