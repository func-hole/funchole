package com.funchole.backend.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentNodeExecutorTest {

    private static final Path SCRIPT_PATH = Path.of("node", "executor.mjs").toAbsolutePath();

    @TempDir
    Path artifactsRoot;

    private PersistentNodeExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void executesRealJavaScriptHandlerAndReturnsOutput() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH);
        Path artifact = writeArtifact("success", "export async function handler(input) { return { ok: true, input }; }");

        NodeExecutionResult result = execute(artifact, "{\"path\":\"/orders\"}");

        assertTrue(result.success());
        assertEquals("{\"ok\":true,\"input\":{\"path\":\"/orders\"}}", result.output());
    }

    @Test
    void awaitsAsyncHandlerCorrectly() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH);
        Path artifact = writeArtifact("async", """
                export async function handler(input) {
                    await new Promise((resolve) => setTimeout(resolve, 50));
                    return { delayed: true };
                }
                """);

        NodeExecutionResult result = execute(artifact, "{}");

        assertTrue(result.success());
        assertEquals("{\"delayed\":true}", result.output());
    }

    @Test
    void mapsThrownHandlerErrorToArtifactExecutionError() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH);
        Path artifact = writeArtifact("throwing", """
                export async function handler(input) {
                    throw new Error("simulated artifact failure");
                }
                """);

        NodeExecutionResult result = execute(artifact, "{}");

        assertFalse(result.success());
        assertEquals("ARTIFACT_EXECUTION_ERROR", result.errorCode());
        assertEquals("simulated artifact failure", result.errorMessage());
    }

    @Test
    void mapsMissingHandlerExportToHandlerNotFound() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH);
        Path artifact = writeArtifact("no-handler", "export const notAHandler = 42;");

        NodeExecutionResult result = execute(artifact, "{}");

        assertFalse(result.success());
        assertEquals("HANDLER_NOT_FOUND", result.errorCode());
    }

    @Test
    void mapsMissingArtifactFileToArtifactNotFound() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH);
        Path missing = artifactsRoot.resolve("does-not-exist.mjs");

        NodeExecutionResult result = execute(missing, "{}");

        assertFalse(result.success());
        assertEquals("ARTIFACT_NOT_FOUND", result.errorCode());
    }

    @Test
    void reusesTheSameProcessAcrossMultipleExecutions() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH);
        Path artifact = writeArtifact("success", "export async function handler(input) { return { ok: true, input }; }");

        long pidBeforeFirst = executor.pid();
        NodeExecutionResult first = execute(artifact, "{\"n\":1}");
        long pidAfterFirst = executor.pid();
        NodeExecutionResult second = execute(artifact, "{\"n\":2}");
        long pidAfterSecond = executor.pid();

        assertTrue(first.success());
        assertTrue(second.success());
        assertTrue(executor.isAlive());
        assertEquals(pidBeforeFirst, pidAfterFirst);
        assertEquals(pidAfterFirst, pidAfterSecond);
    }

    @Test
    void correlatesConcurrentExecutionsByExecutionId() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH);
        Path artifact = writeArtifact("async", """
                export async function handler(input) {
                    await new Promise((resolve) => setTimeout(resolve, 20 + Math.random() * 30));
                    return { echoed: input };
                }
                """);

        List<NodeExecutionRequest> requests = IntStream.range(0, 8)
                .mapToObj(i -> new NodeExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        artifact, "{\"n\":" + i + "}"))
                .toList();

        List<CompletableFuture<NodeExecutionResult>> futures = requests.stream()
                .map(request -> executor.execute(request).toCompletableFuture())
                .toList();

        for (int i = 0; i < requests.size(); i++) {
            NodeExecutionResult result = futures.get(i).get(5, TimeUnit.SECONDS);
            assertEquals(requests.get(i).executionId(), result.executionId());
            assertEquals("{\"echoed\":{\"n\":" + i + "}}", result.output());
        }
    }

    @Test
    void concurrentWritesDoNotCorruptFraming() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH);
        Path artifact = writeArtifact("success", "export async function handler(input) { return input; }");

        List<NodeExecutionRequest> requests = IntStream.range(0, 20)
                .mapToObj(i -> new NodeExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        artifact, "{\"index\":" + i + "}"))
                .toList();

        List<CompletableFuture<NodeExecutionResult>> futures = requests.stream()
                .map(request -> executor.execute(request).toCompletableFuture())
                .toList();

        for (int i = 0; i < requests.size(); i++) {
            NodeExecutionResult result = futures.get(i).get(5, TimeUnit.SECONDS);
            assertTrue(result.success());
            assertEquals(requests.get(i).executionId(), result.executionId());
            assertEquals("{\"index\":" + i + "}", result.output());
        }
    }

    @Test
    void failsPendingExecutionWhenProcessDiesRatherThanHangingForever() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH);
        Path artifact = writeArtifact("slow", """
                export async function handler(input) {
                    await new Promise((resolve) => setTimeout(resolve, 30000));
                    return { tooLate: true };
                }
                """);
        NodeExecutionRequest request = new NodeExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), artifact, "{}");

        CompletableFuture<NodeExecutionResult> pending = executor.execute(request).toCompletableFuture();
        Thread.sleep(100);
        executor.close();

        ExecutionException exception = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertNotNull(exception.getCause());
    }

    private NodeExecutionResult execute(Path artifact, String input) throws Exception {
        NodeExecutionRequest request = new NodeExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), artifact, input);
        return executor.execute(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private Path writeArtifact(String name, String source) throws IOException {
        Path path = artifactsRoot.resolve(name + ".mjs");
        Files.writeString(path, source);
        return path;
    }
}
