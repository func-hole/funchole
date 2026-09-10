package com.funchole.backend.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeWorkerServerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path NODE_SCRIPT_PATH = Path.of("node", "executor.mjs").toAbsolutePath();

    private static PersistentNodeExecutor nodeExecutor;

    @TempDir
    Path artifactsRoot;

    private Path socketPath;
    private RuntimeWorkerServer server;

    @BeforeAll
    static void startNodeExecutor() throws Exception {
        nodeExecutor = PersistentNodeExecutor.start("node", NODE_SCRIPT_PATH);
    }

    @AfterAll
    static void stopNodeExecutor() {
        nodeExecutor.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        // Unix Domain Socket paths are limited to ~104 bytes on macOS/BSD, so this
        // deliberately avoids Files.createTempDirectory() - the default JDK temp
        // directory (e.g. macOS's /var/folders/.../T/) is often already too long.
        socketPath = Path.of("/tmp", "fh-worker-test-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
        ArtifactResolver artifactResolver = new DirectoryArtifactResolver(artifactsRoot, "NODE");
        server = RuntimeWorkerServer.bind(socketPath, "runtime-node-test-1", "NODE", artifactResolver, nodeExecutor);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void invokeMessageDecodesFromJson() throws Exception {
        UUID executionId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        String json = """
                {"type":"INVOKE","executionId":"%s","payload":{"invocationId":"%s","flowId":null,"flowVersionId":null,\
                "stepId":"%s","attempt":1,"componentType":"FUNCTION","componentId":"%s","componentVersionId":"%s",\
                "runtimeType":"NODE","input":"{\\"path\\":\\"/orders\\"}"}}
                """.formatted(executionId, invocationId, stepId, componentId, componentVersionId);

        RuntimeInvokeMessage message = OBJECT_MAPPER.readValue(json, RuntimeInvokeMessage.class);

        assertEquals("INVOKE", message.type());
        assertEquals(executionId, message.executionId());
        assertEquals(invocationId, message.payload().invocationId());
        assertEquals(stepId, message.payload().stepId());
        assertEquals(1, message.payload().attempt());
        assertEquals(componentId, message.payload().componentId());
        assertEquals(componentVersionId, message.payload().componentVersionId());
        assertEquals("NODE", message.payload().runtimeType());
        assertEquals("{\"path\":\"/orders\"}", message.payload().input());
    }

    @Test
    void acceptedMessageRoundTripsThroughJackson() throws Exception {
        UUID executionId = UUID.randomUUID();

        String json = OBJECT_MAPPER.writeValueAsString(RuntimeAcceptedMessage.of(executionId));
        RuntimeAcceptedMessage decoded = OBJECT_MAPPER.readValue(json, RuntimeAcceptedMessage.class);

        assertEquals("ACCEPTED", decoded.type());
        assertEquals(executionId, decoded.executionId());
    }

    @Test
    void acceptsValidInvokeAndReturnsMatchingAccepted() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = writeSuccessArtifact();
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            String response = client.readLine();

            RuntimeAcceptedMessage accepted = OBJECT_MAPPER.readValue(response, RuntimeAcceptedMessage.class);
            assertEquals("ACCEPTED", accepted.type());
            assertEquals(executionId, accepted.executionId());
        }
        assertTrue(server.hasAccepted(executionId));
    }

    @Test
    void executesRealArtifactAndReturnsRealResult() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = writeSuccessArtifact();
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{\"path\":\"/orders\"}");
            RuntimeAcceptedMessage accepted = OBJECT_MAPPER.readValue(client.readLine(), RuntimeAcceptedMessage.class);
            RuntimeTerminalMessage result = OBJECT_MAPPER.readValue(client.readLine(), RuntimeTerminalMessage.class);

            assertEquals(executionId, accepted.executionId());
            assertEquals("RESULT", result.type());
            assertEquals(executionId, result.executionId());
            assertNull(result.error());
            assertEquals(
                    "{\"ok\":true,\"input\":{\"path\":\"/orders\"}}",
                    result.output()
            );
        }
    }

    @Test
    void realArtifactFailureProducesRealError() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = writeThrowingArtifact();
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            RuntimeAcceptedMessage accepted = OBJECT_MAPPER.readValue(client.readLine(), RuntimeAcceptedMessage.class);
            RuntimeTerminalMessage error = OBJECT_MAPPER.readValue(client.readLine(), RuntimeTerminalMessage.class);

            assertEquals(executionId, accepted.executionId());
            assertEquals("ERROR", error.type());
            assertEquals(executionId, error.executionId());
            assertNull(error.output());
            assertEquals("ARTIFACT_EXECUTION_ERROR", error.error().code());
            assertEquals("simulated artifact failure", error.error().message());
        }
    }

    @Test
    void missingArtifactMappingProducesArtifactNotFoundError() throws Exception {
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "NODE", UUID.randomUUID(), UUID.randomUUID(), "{}");
            client.readLine();
            RuntimeTerminalMessage error = OBJECT_MAPPER.readValue(client.readLine(), RuntimeTerminalMessage.class);

            assertEquals("ERROR", error.type());
            assertEquals("ARTIFACT_NOT_FOUND", error.error().code());
        }
    }

    @Test
    void deduplicatesRepeatedExecutionIdWhileExecutingAndInvokesHandlerExactlyOnce() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = writeSlowCountingArtifact();
        UUID executionId = UUID.randomUUID();

        try (TestClient first = TestClient.connect(socketPath); TestClient second = TestClient.connect(socketPath)) {
            first.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            RuntimeAcceptedMessage firstAccepted = OBJECT_MAPPER.readValue(first.readLine(), RuntimeAcceptedMessage.class);

            second.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            RuntimeAcceptedMessage secondAccepted = OBJECT_MAPPER.readValue(second.readLine(), RuntimeAcceptedMessage.class);

            RuntimeTerminalMessage result = OBJECT_MAPPER.readValue(
                    assertTimeoutPreemptively(Duration.ofSeconds(5), first::readLine), RuntimeTerminalMessage.class);

            assertEquals(executionId, firstAccepted.executionId());
            assertEquals(executionId, secondAccepted.executionId());
            assertEquals("RESULT", result.type());
            assertEquals("{\"invocationCount\":1}", result.output());
        }
    }

    @Test
    void deduplicatesRepeatedExecutionIdAfterResultAndReplaysIt() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = writeSuccessArtifact();
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            client.readLine();
            String firstResult = client.readLine();

            client.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            String secondAccepted = client.readLine();
            String replayedResult = client.readLine();

            assertEquals(executionId, OBJECT_MAPPER.readValue(secondAccepted, RuntimeAcceptedMessage.class).executionId());
            assertEquals(firstResult, replayedResult);
        }
        assertEquals(1, server.acceptedCount());
    }

    @Test
    void deduplicatesRepeatedExecutionIdAfterErrorAndReplaysIt() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = writeThrowingArtifact();
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            client.readLine();
            String firstError = client.readLine();

            client.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            client.readLine();
            String replayedError = client.readLine();

            assertEquals(firstError, replayedError);
        }
    }

    @Test
    void rejectsIncompatibleRuntimeTypeByClosingConnection() throws Exception {
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "PYTHON", UUID.randomUUID(), UUID.randomUUID(), "{}");
            String response = assertTimeoutPreemptively(Duration.ofSeconds(2), client::readLine);
            assertNull(response);
        }
        assertTrue(!server.hasAccepted(executionId));
    }

    @Test
    void rejectsMalformedInvokeByClosingConnection() throws Exception {
        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendRaw("{\"type\":\"INVOKE\",\"executionId\":null,\"payload\":null}");
            String response = assertTimeoutPreemptively(Duration.ofSeconds(2), client::readLine);
            assertNull(response);
        }
    }

    @Test
    void terminalResultIsDeliveredToNewConnectionAfterOriginalDisconnects() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = writeSlowCountingArtifact();
        UUID executionId = UUID.randomUUID();

        try (TestClient first = TestClient.connect(socketPath)) {
            first.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            RuntimeAcceptedMessage firstAccepted =
                    OBJECT_MAPPER.readValue(first.readLine(), RuntimeAcceptedMessage.class);
            assertEquals(executionId, firstAccepted.executionId());
            // Disconnect after ACCEPTED; the artifact is still executing.
        }

        try (TestClient second = TestClient.connect(socketPath)) {
            second.sendInvoke(executionId, "NODE", componentId, componentVersionId, "{}");
            RuntimeAcceptedMessage secondAccepted =
                    OBJECT_MAPPER.readValue(second.readLine(), RuntimeAcceptedMessage.class);
            assertEquals(executionId, secondAccepted.executionId());

            RuntimeTerminalMessage result = OBJECT_MAPPER.readValue(
                    assertTimeoutPreemptively(Duration.ofSeconds(5), second::readLine), RuntimeTerminalMessage.class);
            assertEquals("RESULT", result.type());
            assertEquals(executionId, result.executionId());
            assertEquals("{\"invocationCount\":1}", result.output());
        }

        assertEquals(1, server.acceptedCount());
    }

    @Test
    void singleConnectionAcceptsMultipleSequentialInvokes() throws Exception {        // A slightly delayed artifact keeps ACCEPTED deterministically ahead of
        // RESULT on the wire, since with real (fast, synchronous) artifact
        // resolution an immediate artifact could otherwise write its RESULT
        // before this test reads the second invoke's ACCEPTED line.
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = writeDelayedArtifact();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(first, "NODE", componentId, componentVersionId, "{}");
            RuntimeAcceptedMessage firstAccepted = OBJECT_MAPPER.readValue(client.readLine(), RuntimeAcceptedMessage.class);
            client.sendInvoke(second, "NODE", componentId, componentVersionId, "{}");
            RuntimeAcceptedMessage secondAccepted = OBJECT_MAPPER.readValue(client.readLine(), RuntimeAcceptedMessage.class);

            assertEquals(first, firstAccepted.executionId());
            assertEquals(second, secondAccepted.executionId());
        }
        assertEquals(2, server.acceptedCount());
    }

    private UUID writeSuccessArtifact() throws IOException {
        return writeArtifact("export async function handler(input) { return { ok: true, input }; }");
    }

    private UUID writeThrowingArtifact() throws IOException {
        return writeArtifact("""
                export async function handler(input) {
                    throw new Error("simulated artifact failure");
                }
                """);
    }

    private UUID writeDelayedArtifact() throws IOException {
        return writeArtifact("""
                export async function handler(input) {
                    await new Promise((resolve) => setTimeout(resolve, 300));
                    return { ok: true };
                }
                """);
    }

    private UUID writeSlowCountingArtifact() throws IOException {
        return writeArtifact("""
                let invocationCount = 0;
                export async function handler(input) {
                    invocationCount++;
                    await new Promise((resolve) => setTimeout(resolve, 300));
                    return { invocationCount };
                }
                """);
    }

    private UUID writeArtifact(String source) throws IOException {
        UUID componentVersionId = UUID.randomUUID();
        Path directory = artifactsRoot.resolve(componentVersionId.toString());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("index.mjs"), source);
        return componentVersionId;
    }

    private static final class TestClient implements AutoCloseable {
        private final SocketChannel channel;
        private final BufferedReader reader;
        private final OutputStream out;

        private TestClient(SocketChannel channel) {
            this.channel = channel;
            this.reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            this.out = Channels.newOutputStream(channel);
        }

        static TestClient connect(Path socketPath) throws IOException {
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            return new TestClient(channel);
        }

        void sendInvoke(UUID executionId, String runtimeType, UUID componentId, UUID componentVersionId, String input) throws IOException {
            String escapedInput = input.replace("\\", "\\\\").replace("\"", "\\\"");
            String json = """
                    {"type":"INVOKE","executionId":"%s","payload":{"invocationId":"%s","flowId":null,"flowVersionId":null,\
                    "stepId":"%s","attempt":1,"componentType":"FUNCTION","componentId":"%s","componentVersionId":"%s",\
                    "runtimeType":"%s","input":"%s"}}
                    """.formatted(executionId, UUID.randomUUID(), UUID.randomUUID(), componentId, componentVersionId, runtimeType, escapedInput);
            sendRaw(json.strip());
        }

        void sendRaw(String json) throws IOException {
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        String readLine() throws IOException {
            return reader.readLine();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
