package com.funchole.backend.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.runtimeregistry.RuntimeTarget;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IpcRuntimeExecutionGatewayTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private FakeIpcWorker worker;
    private IpcRuntimeExecutionGateway gateway;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
        if (worker != null) {
            worker.close();
        }
    }

    @Test
    void invokeMessageSerializationPreservesAllFields() throws Exception {
        RuntimeExecutionRequest request = validRequest();

        IpcInvokeMessage message = IpcInvokeMessage.from(request);
        String json = OBJECT_MAPPER.writeValueAsString(message);
        IpcInvokeMessage decoded = OBJECT_MAPPER.readValue(json, IpcInvokeMessage.class);

        assertEquals("INVOKE", decoded.type());
        assertEquals(request.executionId(), decoded.executionId());
        assertEquals(request.invocationId(), decoded.payload().invocationId());
        assertEquals(request.stepId(), decoded.payload().stepId());
        assertEquals(request.attempt(), decoded.payload().attempt());
        assertEquals(request.componentId(), decoded.payload().componentId());
        assertEquals(request.componentVersionId(), decoded.payload().componentVersionId());
        assertEquals(request.runtimeType(), decoded.payload().runtimeType());
        assertEquals(request.input(), decoded.payload().input());
    }

    @Test
    void acceptedMessageRoundTripsThroughJackson() throws Exception {
        UUID executionId = UUID.randomUUID();

        String json = OBJECT_MAPPER.writeValueAsString(new IpcAcceptedMessage("ACCEPTED", executionId));
        IpcAcceptedMessage decoded = OBJECT_MAPPER.readValue(json, IpcAcceptedMessage.class);

        assertEquals("ACCEPTED", decoded.type());
        assertEquals(executionId, decoded.executionId());
    }

    @Test
    void resultMessageRoundTripsThroughJackson() throws Exception {
        UUID executionId = UUID.randomUUID();
        String output = "{\"ok\":true,\"executionId\":\"" + executionId + "\"}";

        String json = OBJECT_MAPPER.writeValueAsString(new IpcRuntimeTerminalMessage("RESULT", executionId, output, null));
        IpcRuntimeTerminalMessage decoded = OBJECT_MAPPER.readValue(json, IpcRuntimeTerminalMessage.class);

        assertEquals("RESULT", decoded.type());
        assertEquals(executionId, decoded.executionId());
        assertEquals(output, decoded.output());
    }

    @Test
    void errorMessageRoundTripsThroughJackson() throws Exception {
        UUID executionId = UUID.randomUUID();

        String json = OBJECT_MAPPER.writeValueAsString(new IpcRuntimeTerminalMessage(
                "ERROR",
                executionId,
                null,
                new IpcRuntimeErrorPayload("FAKE_RUNTIME_ERROR", "Simulated runtime failure")
        ));
        IpcRuntimeTerminalMessage decoded = OBJECT_MAPPER.readValue(json, IpcRuntimeTerminalMessage.class);

        assertEquals("ERROR", decoded.type());
        assertEquals(executionId, decoded.executionId());
        assertEquals("FAKE_RUNTIME_ERROR", decoded.error().code());
        assertEquals("Simulated runtime failure", decoded.error().message());
    }

    @Test
    void handsOffAndReceivesMatchingAcceptance() throws Exception {
        worker = FakeIpcWorker.start();
        gateway = new IpcRuntimeExecutionGateway(Duration.ofSeconds(2));
        RuntimeExecutionRequest request = validRequest();

        RuntimeExecutionHandle handle = gateway.handoff(target(worker.socketPath()), request);
        RuntimeExecutionAcceptance acceptance = handle.acceptance();

        assertTrue(acceptance.accepted());
        assertEquals(request.executionId(), acceptance.executionId());
        assertEquals(request.executionId(), handle.completion().toCompletableFuture()
                .get(2, java.util.concurrent.TimeUnit.SECONDS).executionId());
        assertEquals(1, worker.receivedExecutionIds().size());
    }

    @Test
    void reusesThePersistentConnectionAcrossSequentialRequests() throws Exception {
        worker = FakeIpcWorker.start();
        gateway = new IpcRuntimeExecutionGateway(Duration.ofSeconds(2));
        RuntimeTarget target = target(worker.socketPath());

        gateway.handoff(target, validRequest());
        gateway.handoff(target, validRequest());

        assertEquals(2, worker.receivedExecutionIds().size());
        assertEquals(1, worker.acceptedConnectionCount());
    }

    @Test
    void correlatesResponsesByExecutionIdEvenWhenReceivedOutOfOrder() throws Exception {
        Path socketPath = Path.of("/tmp", "fh-reorder-worker-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
        Files.deleteIfExists(socketPath);
        try (ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
            ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
            Future<Void> serverTask = serverExecutor.submit(() -> {
                respondToTwoRequestsInReverseOrder(serverChannel);
                return null;
            });

            gateway = new IpcRuntimeExecutionGateway(Duration.ofSeconds(5));
            RuntimeTarget target = new RuntimeTarget("runtime-node-reorder", "NODE", socketPath.toString());
            RuntimeExecutionRequest requestA = validRequest();
            RuntimeExecutionRequest requestB = validRequest();

            CountDownLatch bothStarted = new CountDownLatch(2);
            ExecutorService clientExecutor = Executors.newFixedThreadPool(2);
            Future<RuntimeExecutionAcceptance> futureA = clientExecutor.submit(() -> {
                bothStarted.countDown();
                bothStarted.await();
                return gateway.handoff(target, requestA).acceptance();
            });
            Future<RuntimeExecutionAcceptance> futureB = clientExecutor.submit(() -> {
                bothStarted.countDown();
                bothStarted.await();
                return gateway.handoff(target, requestB).acceptance();
            });

            RuntimeExecutionAcceptance acceptanceA = futureA.get(10, java.util.concurrent.TimeUnit.SECONDS);
            RuntimeExecutionAcceptance acceptanceB = futureB.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(requestA.executionId(), acceptanceA.executionId());
            assertEquals(requestB.executionId(), acceptanceB.executionId());
            assertNotEquals(acceptanceA.executionId(), acceptanceB.executionId());

            serverTask.get(10, java.util.concurrent.TimeUnit.SECONDS);
            clientExecutor.shutdownNow();
            serverExecutor.shutdownNow();
        } finally {
            Files.deleteIfExists(socketPath);
        }
    }

    @Test
    void failsClearlyWhenTargetSocketIsUnavailable() {
        gateway = new IpcRuntimeExecutionGateway(Duration.ofSeconds(1));
        RuntimeTarget target = new RuntimeTarget("runtime-node-missing", "NODE", "/tmp/fh-does-not-exist.sock");

        assertThrows(RuntimeIpcException.class, () -> gateway.handoff(target, validRequest()));
    }

    @Test
    void timesOutWhenWorkerNeverRespondsAndReleasesNothingItself() throws Exception {
        worker = FakeIpcWorker.start();
        worker.behave(FakeIpcWorker.Behavior.SILENT);
        gateway = new IpcRuntimeExecutionGateway(Duration.ofMillis(300));
        RuntimeExecutionRequest request = validRequest();

        assertThrows(RuntimeIpcException.class, () -> gateway.handoff(target(worker.socketPath()), request));
        assertEquals(0, gateway.pendingAcceptanceCount(worker.socketPath()));
        assertEquals(0, gateway.pendingCompletionCount(worker.socketPath()));
    }

    @Test
    void doesNotAcceptWhenWorkerRespondsWithWrongExecutionId() throws Exception {
        worker = FakeIpcWorker.start();
        worker.behave(FakeIpcWorker.Behavior.WRONG_EXECUTION_ID);
        gateway = new IpcRuntimeExecutionGateway(Duration.ofMillis(300));

        assertThrows(RuntimeIpcException.class, () -> gateway.handoff(target(worker.socketPath()), validRequest()));
    }

    @Test
    void failsWhenWorkerClosesConnectionWithoutAccepting() throws Exception {
        worker = FakeIpcWorker.start();
        worker.behave(FakeIpcWorker.Behavior.CLOSE_IMMEDIATELY);
        gateway = new IpcRuntimeExecutionGateway(Duration.ofSeconds(2));

        assertThrows(RuntimeIpcException.class, () -> gateway.handoff(target(worker.socketPath()), validRequest()));
    }

    private void respondToTwoRequestsInReverseOrder(ServerSocketChannel serverChannel) throws IOException {
        try (
                SocketChannel client = serverChannel.accept();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
                OutputStream out = Channels.newOutputStream(client)
        ) {
            String firstLine = reader.readLine();
            String secondLine = reader.readLine();
            UUID firstExecutionId = UUID.fromString(OBJECT_MAPPER.readTree(firstLine).get("executionId").asText());
            UUID secondExecutionId = UUID.fromString(OBJECT_MAPPER.readTree(secondLine).get("executionId").asText());

            out.write(("{\"type\":\"ACCEPTED\",\"executionId\":\"" + secondExecutionId + "\"}\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.write(("{\"type\":\"ACCEPTED\",\"executionId\":\"" + firstExecutionId + "\"}\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private RuntimeTarget target(String socketPath) {
        return new RuntimeTarget("runtime-node-test", "NODE", socketPath);
    }

    private RuntimeExecutionRequest validRequest() {
        return new RuntimeExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.fromString("10000000-0000-0000-0000-000000000101"),
                UUID.fromString("20000000-0000-0000-0000-000000000101"),
                UUID.randomUUID(),
                1,
                "FUNCTION",
                UUID.fromString("88888888-8888-8888-8888-888888888861"),
                UUID.fromString("99999999-9999-9999-9999-999999999861"),
                "NODE",
                "{\"path\":\"/orders\"}"
        );
    }
}
