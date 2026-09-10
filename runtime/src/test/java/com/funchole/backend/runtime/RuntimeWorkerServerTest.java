package com.funchole.backend.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeWorkerServerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Path socketPath;
    private RuntimeWorkerServer server;

    @BeforeEach
    void setUp() throws Exception {
        // Unix Domain Socket paths are limited to ~104 bytes on macOS/BSD, so this
        // deliberately avoids Files.createTempDirectory() - the default JDK temp
        // directory (e.g. macOS's /var/folders/.../T/) is often already too long.
        socketPath = Path.of("/tmp", "fh-worker-test-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
        server = RuntimeWorkerServer.bind(socketPath, "runtime-node-test-1", "NODE");
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
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "NODE");
            String response = client.readLine();

            RuntimeAcceptedMessage accepted = OBJECT_MAPPER.readValue(response, RuntimeAcceptedMessage.class);
            assertEquals("ACCEPTED", accepted.type());
            assertEquals(executionId, accepted.executionId());
        }
        assertTrue(server.hasAccepted(executionId));
    }

    @Test
    void deduplicatesRepeatedExecutionIdWithinProcessLifetime() throws Exception {
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "NODE");
            String firstResponse = client.readLine();
            client.sendInvoke(executionId, "NODE");
            String secondResponse = client.readLine();

            assertEquals(executionId, OBJECT_MAPPER.readValue(firstResponse, RuntimeAcceptedMessage.class).executionId());
            assertEquals(executionId, OBJECT_MAPPER.readValue(secondResponse, RuntimeAcceptedMessage.class).executionId());
        }
        assertEquals(1, server.acceptedCount());
    }

    @Test
    void rejectsIncompatibleRuntimeTypeByClosingConnection() throws Exception {
        UUID executionId = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(executionId, "PYTHON");
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
    void singleConnectionAcceptsMultipleSequentialInvokes() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        try (TestClient client = TestClient.connect(socketPath)) {
            client.sendInvoke(first, "NODE");
            RuntimeAcceptedMessage firstAccepted = OBJECT_MAPPER.readValue(client.readLine(), RuntimeAcceptedMessage.class);
            client.sendInvoke(second, "NODE");
            RuntimeAcceptedMessage secondAccepted = OBJECT_MAPPER.readValue(client.readLine(), RuntimeAcceptedMessage.class);

            assertEquals(first, firstAccepted.executionId());
            assertEquals(second, secondAccepted.executionId());
        }
        assertEquals(2, server.acceptedCount());
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

        void sendInvoke(UUID executionId, String runtimeType) throws IOException {
            String json = """
                    {"type":"INVOKE","executionId":"%s","payload":{"invocationId":"%s","flowId":null,"flowVersionId":null,\
                    "stepId":"%s","attempt":1,"componentType":"FUNCTION","componentId":"%s","componentVersionId":"%s",\
                    "runtimeType":"%s","input":"{}"}}
                    """.formatted(executionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), runtimeType);
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
