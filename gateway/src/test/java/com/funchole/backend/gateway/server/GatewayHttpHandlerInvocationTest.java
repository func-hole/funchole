package com.funchole.backend.gateway.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.gateway.GatewayRegistry;
import com.funchole.backend.gateway.GatewayRegistrySnapshot;
import com.funchole.backend.gateway.GatewayRequestContext;
import com.funchole.backend.gateway.GatewayRuntimeEntry;
import com.funchole.backend.gateway.flow.FlowResolution;
import com.funchole.backend.gateway.flow.FlowResolver;
import com.funchole.backend.invocation.CreateInvocationRequest;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationRegistry;
import com.funchole.backend.invocation.InvocationStatus;
import com.funchole.backend.invocation.InvocationTransition;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GatewayHttpHandlerInvocationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID GATEWAY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FLOW_ID = UUID.fromString("55555555-5555-5555-5555-555555555553");
    private static final UUID FLOW_VERSION_ID = UUID.fromString("66666666-6666-6666-6666-666666666663");
    private static final UUID INVOCATION_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private ScheduledExecutorService timeoutExecutor;

    @AfterEach
    void tearDown() {
        if (timeoutExecutor != null) {
            timeoutExecutor.shutdownNow();
        }
    }

    @Test
    void createsInvocationAndDoesNotWriteAResponseUntilCompletion() throws Exception {
        CountingFlowResolver flowResolver = flowResolver();
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver, invocationRegistry, pendingRegistry);

        channel.writeInbound(checkoutRequest());

        assertNull(channel.readOutbound());
        assertEquals(1, flowResolver.calls);
        assertEquals(FLOW_ID, invocationRegistry.request.flowId());
        assertEquals("flw_checkout", invocationRegistry.request.flowKey());
        assertEquals(FLOW_VERSION_ID, invocationRegistry.request.flowVersionId());
        assertTrue(invocationRegistry.request.inputPayload().contains("\"path\":\"/checkout\""));
        assertTrue(invocationRegistry.request.inputPayload().contains("\"body\":\"{\\\"total\\\":100}\""));
        assertEquals(1, pendingRegistry.pendingCount());
    }

    @Test
    void writesFinalResponseWhenInvocationCompletes() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());

        invocationRegistry.completeWith("{\"status\":201,\"body\":{\"ok\":true}}");
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.CREATED, response.status());
        JsonNode body = OBJECT_MAPPER.readTree(response.content().toString(StandardCharsets.UTF_8));
        assertTrue(body.at("/ok").asBoolean());
    }

    @Test
    void writesServerErrorWhenInvocationFails() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());

        invocationRegistry.failWith();
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
    }

    @Test
    void writesGatewayTimeoutWhenPendingCompletionTimesOut() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofMillis(50));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());

        awaitCondition(() -> pendingRegistry.pendingCount() == 0, Duration.ofSeconds(2));
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.GATEWAY_TIMEOUT, response.status());
    }

    @Test
    void duplicateCompletionDoesNotWriteASecondResponse() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());
        invocationRegistry.completeWith("{\"status\":200,\"body\":{\"ok\":true}}");

        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();
        FullHttpResponse first = channel.readOutbound();
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();
        FullHttpResponse second = channel.readOutbound();

        assertEquals(HttpResponseStatus.OK, first.status());
        assertNull(second);
    }

    @Test
    void alreadyTerminalInvocationCompletesImmediatelyWithoutWaitingForTerminalEvent() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        // The whole pipeline completed during the registration window - the
        // terminal NATS event was dropped before the pending entry existed.
        invocationRegistry.completeOnFirstFindWith("{\"status\":200,\"body\":{\"ok\":true}}");

        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        JsonNode body = OBJECT_MAPPER.readTree(response.content().toString(StandardCharsets.UTF_8));
        assertTrue(body.at("/ok").asBoolean());
        assertEquals(0, pendingRegistry.pendingCount());
    }

    @Test
    void reconciliationWritesOnlyOnceWhenALateTerminalEventAlsoArrives() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        invocationRegistry.completeOnFirstFindWith("{\"status\":200,\"body\":{\"ok\":true}}");

        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();
        FullHttpResponse first = channel.readOutbound();
        // Duplicate terminal delivery (event + reconcile): must be a no-op.
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();
        FullHttpResponse second = channel.readOutbound();

        assertEquals(HttpResponseStatus.OK, first.status());
        assertNull(second);
    }

    private CountingFlowResolver flowResolver() {
        return new CountingFlowResolver(new FlowResolution(FLOW_ID, "flw_checkout", FLOW_VERSION_ID));
    }

    private EmbeddedChannel channel(
            FlowResolver flowResolver,
            InvocationRegistry invocationRegistry,
            PendingInvocationResponseRegistry pendingRegistry
    ) {
        GatewayRuntimeEntry gateway = new GatewayRuntimeEntry(
                GATEWAY_ID, "Primary Gateway", "a6n1y8", "funchole.test", "a6n1y8.funchole.test", null, null);
        GatewayRegistry registry = new GatewayRegistry(new GatewayRegistrySnapshot(
                Map.of(gateway.hostname(), gateway), null, Map.of()));
        GatewayHttpHandler handler =
                new GatewayHttpHandler(OBJECT_MAPPER, registry, flowResolver, invocationRegistry, pendingRegistry);
        return new EmbeddedChannel(handler);
    }

    private DefaultFullHttpRequest checkoutRequest() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/checkout",
                Unpooled.copiedBuffer("{\"total\":100}", StandardCharsets.UTF_8)
        );
        request.headers().set(HttpHeaderNames.HOST, "a6n1y8.funchole.test");
        return request;
    }

    private PendingInvocationResponseRegistry pendingRegistry(Duration timeout) {
        timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        return new PendingInvocationResponseRegistry(timeoutExecutor, timeout);
    }

    private void awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadlineMillis) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    private static final class CountingFlowResolver implements FlowResolver {
        private final FlowResolution resolution;
        private int calls;

        private CountingFlowResolver(FlowResolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public Optional<FlowResolution> resolve(GatewayRuntimeEntry gateway, GatewayRequestContext requestContext) {
            calls++;
            return Optional.of(resolution);
        }
    }

    private static final class CapturingInvocationRegistry implements InvocationRegistry {
        private CreateInvocationRequest request;
        private volatile InvocationStatus status = InvocationStatus.PENDING;
        private volatile String result;
        private volatile String resultOnFirstFind;
        private boolean findSeen;

        @Override
        public Invocation create(CreateInvocationRequest request) {
            this.request = request;
            return currentInvocation();
        }

        /** Simulates the complete pipeline finishing while the Gateway is registering. */
        void completeOnFirstFindWith(String result) {
            this.status = InvocationStatus.COMPLETED;
            this.resultOnFirstFind = result;
        }

        void completeWith(String result) {
            this.status = InvocationStatus.COMPLETED;
            this.result = result;
        }

        void failWith() {
            this.status = InvocationStatus.FAILED;
        }

        private Invocation currentInvocation() {
            String effectiveResult = result != null || resultOnFirstFind == null ? result : resultOnFirstFind;
            return new Invocation(
                    INVOCATION_ID,
                    request.flowId(),
                    request.flowKey(),
                    request.flowVersionId(),
                    status,
                    request.inputPayload(),
                    "{}",
                    effectiveResult,
                    null,
                    OffsetDateTime.now(),
                    OffsetDateTime.now(),
                    status == InvocationStatus.PENDING ? null : OffsetDateTime.now()
            );
        }

        @Override
        public Optional<Invocation> findById(UUID invocationId) {
            if (request == null || !invocationId.equals(INVOCATION_ID)) {
                return Optional.empty();
            }
            boolean firstFind = !findSeen;
            findSeen = true;
            if (firstFind && resultOnFirstFind != null) {
                result = resultOnFirstFind;
            }
            return Optional.of(currentInvocation());
        }

        @Override
        public InvocationTransition markCompleted(UUID invocationId, String result) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public InvocationTransition markFailed(UUID invocationId, String error) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
